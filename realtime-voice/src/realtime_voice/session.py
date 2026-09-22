"""Shared provider adapters and Java turn settlement for PHONE calls."""
from __future__ import annotations

import asyncio
import contextlib
import logging
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass

from livekit import rtc
from livekit.agents import stt

from realtime_voice.control import Control
from realtime_voice.providers.asr import HuoshanSTT
from realtime_voice.providers.tts import HuoshanRealtimeTTS
from realtime_voice.settings import Settings

logger = logging.getLogger("realtime_voice.session")


@dataclass
class Turn:
    id: str
    accepted: dict | None = None
    generated: str = ""
    completed: bool = False

    @classmethod
    def from_accepted(cls, accepted: dict) -> Turn:
        return cls(id=str(accepted["turnId"]), accepted=accepted)


class VoiceSession:
    def __init__(self, settings: Settings, control: Control):
        self.control = control
        self._asr_provider = HuoshanSTT(
            api_key=settings.asr_key,
            resource_id=settings.asr_resource,
            ws_url=settings.asr_url,
        )
        self._tts_provider = HuoshanRealtimeTTS(
            api_key=settings.tts_key,
            speaker=settings.tts_speaker,
            resource_id=settings.tts_resource,
            model=settings.tts_model,
            ws_url=settings.tts_url,
            sample_rate=16000,
        )
        self._asr_stream = None
        self._asr_task: asyncio.Task | None = None
        self._reply_task: asyncio.Task | None = None
        self._on_final: Callable[[str], Awaitable[None]] | None = None
        self._closed = False

    async def start_asr(self, on_final: Callable[[str], Awaitable[None]]) -> None:
        self._on_final = on_final
        self._asr_stream = self._asr_provider.stream()
        self._asr_task = asyncio.create_task(self._consume_asr())

    async def _consume_asr(self) -> None:
        assert self._asr_stream is not None
        async for event in self._asr_stream:
            if event.type is stt.SpeechEventType.FINAL_TRANSCRIPT and event.alternatives:
                text = event.alternatives[0].text.strip()
                if text and self._on_final is not None:
                    await self._on_final(text)

    def feed_audio(self, pcm: bytes, sample_rate: int = 16000) -> None:
        if self._asr_stream is None or self._closed or not pcm:
            return
        self._asr_stream.push_frame(rtc.AudioFrame(
            data=pcm,
            sample_rate=sample_rate,
            num_channels=1,
            samples_per_channel=len(pcm) // 2,
        ))

    def begin_turn(self, turn_id: str | None = None) -> Turn:
        return Turn(id=turn_id or str(uuid.uuid4()))

    async def run_reply(
            self,
            turn: Turn,
            send_audio: Callable[[bytes], Awaitable[None]],
            settle_playout: Callable[[str, int], Awaitable[object]],
    ) -> None:
        if turn.accepted is None:
            raise RuntimeError(f"turn {turn.id} was not accepted by Java")
        if self._reply_task is not None and not self._reply_task.done():
            raise RuntimeError("previous phone reply has not settled")
        self._reply_task = asyncio.current_task()
        tts_stream = self._tts_provider.stream()
        generated = False

        async def send_synthesized_audio() -> None:
            async for audio in tts_stream:
                await send_audio(bytes(audio.frame.data))

        audio_task = asyncio.create_task(send_synthesized_audio())
        try:
            async for event in self.control.events(turn.accepted["streamPath"]):
                if event.get("utteranceId") != turn.accepted["utteranceId"]:
                    raise RuntimeError("mismatched Java utterance")
                if event.get("type") == "text_delta":
                    chunk = event.get("text", "")
                    turn.generated += chunk
                    tts_stream.push_text(chunk)
                elif event.get("type") == "generation_end":
                    generated = event.get("status") == "GENERATED"
            tts_stream.end_input()
            await audio_task
            turn.completed = generated
            result = await settle_playout(turn.id, len(turn.generated))
            await self._settle(
                turn, result.status, result.played_chars, result.confidence
            )
        except asyncio.CancelledError:
            await self.control.request("POST", f"/turns/{turn.id}/interrupt")
            await self._settle(turn, "INTERRUPTED", 0, "UNKNOWN")
            raise
        except Exception:
            with contextlib.suppress(Exception):
                await self.control.request("POST", f"/turns/{turn.id}/interrupt")
                await self._settle(turn, "UNKNOWN", 0, "UNKNOWN")
            raise
        finally:
            if not audio_task.done():
                audio_task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await audio_task
            await tts_stream.aclose()
            if self._reply_task is asyncio.current_task():
                self._reply_task = None

    async def _settle(self, turn: Turn, status: str, played_chars: int, confidence: str) -> None:
        assert turn.accepted is not None
        await self.control.request("PUT", f"/turns/{turn.id}/playout", {
            "utteranceId": turn.accepted["utteranceId"],
            "revision": 1,
            "status": status,
            "playedChars": played_chars,
            "confidence": confidence,
            "last": True,
        })

    async def interrupt_reply(self) -> None:
        task = self._reply_task
        if task is not None and not task.done() and task is not asyncio.current_task():
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task

    async def cleanup(self) -> None:
        if self._closed:
            return
        self._closed = True
        await self.interrupt_reply()
        if self._asr_stream is not None:
            self._asr_stream.end_input()
            await self._asr_stream.aclose()
        if self._asr_task is not None and not self._asr_task.done():
            self._asr_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._asr_task
        await self._asr_provider.aclose()
        await self._tts_provider.aclose()
