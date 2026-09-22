"""Bounded WebSocket audio transport for a FreeSWITCH PHONE call."""
from __future__ import annotations

import asyncio
import json
import logging
from dataclasses import dataclass, field

from aiohttp import WSMsgType, web

logger = logging.getLogger("realtime_voice.freeswitch_io")

SAMPLE_RATE = 16000
MAX_QUEUE_FRAMES = 300


@dataclass(frozen=True)
class AudioFrame:
    data: bytes
    sample_rate: int = SAMPLE_RATE


@dataclass(frozen=True)
class PlayoutResult:
    turn_id: str
    status: str
    played_chars: int
    confidence: str


@dataclass
class FreeswitchIO:
    """Owns one authenticated-by-call-id media connection and its bounded input queue."""

    call_id: str
    _audio_queue: asyncio.Queue[AudioFrame | None] = field(
        default_factory=lambda: asyncio.Queue(maxsize=MAX_QUEUE_FRAMES)
    )
    _socket: web.WebSocketResponse | None = None
    _connected: asyncio.Event = field(default_factory=asyncio.Event)
    _closed: bool = False
    _frame_count: int = 0
    _playout_waiters: dict[str, asyncio.Future[PlayoutResult]] = field(default_factory=dict)

    async def serve(self, request: web.Request) -> web.WebSocketResponse:
        if self._socket is not None and not self._socket.closed:
            raise web.HTTPConflict(text="media already connected")
        socket = web.WebSocketResponse(
            autoping=True,
            heartbeat=15,
            protocols=("audio.drachtio.org", "audiostream.drachtio.org"),
        )
        await socket.prepare(request)
        self._socket = socket
        self._connected.set()
        logger.info("[FS-IO] media connected call=%s", self.call_id)
        try:
            async for message in socket:
                if message.type is WSMsgType.BINARY:
                    self._frame_count += 1
                    self._offer_audio(AudioFrame(bytes(message.data)))
                elif message.type is WSMsgType.TEXT:
                    self._accept_control(message.data)
                elif message.type is WSMsgType.ERROR:
                    raise socket.exception() or RuntimeError("media websocket failed")
        finally:
            self._socket = None
            self._offer_end()
            logger.info("[FS-IO] media disconnected call=%s frames=%s", self.call_id, self._frame_count)
        return socket

    def _offer_audio(self, frame: AudioFrame) -> None:
        if self._audio_queue.full():
            try:
                self._audio_queue.get_nowait()
            except asyncio.QueueEmpty:
                pass
            logger.warning("[FS-IO] dropping oldest input frame call=%s", self.call_id)
        self._audio_queue.put_nowait(frame)

    def _offer_end(self) -> None:
        if self._audio_queue.full():
            try:
                self._audio_queue.get_nowait()
            except asyncio.QueueEmpty:
                pass
        self._audio_queue.put_nowait(None)

    async def wait_connected(self, timeout: float) -> None:
        await asyncio.wait_for(self._connected.wait(), timeout=timeout)

    async def read_audio(self) -> AudioFrame | None:
        return await self._audio_queue.get()

    async def send_audio(self, pcm: bytes) -> None:
        socket = self._socket
        if socket is None or socket.closed or self._closed:
            raise ConnectionError("FreeSWITCH media is not connected")
        await socket.send_bytes(pcm)

    async def finish_playback(
        self, turn_id: str, generated_chars: int, timeout: float = 10.0
    ) -> PlayoutResult:
        socket = self._socket
        if socket is None or socket.closed or self._closed:
            raise ConnectionError("FreeSWITCH media is not connected")
        waiter = self._playout_waiters.get(turn_id)
        if waiter is None or waiter.done():
            waiter = asyncio.get_running_loop().create_future()
            self._playout_waiters[turn_id] = waiter
        await socket.send_json({
            "type": "endPlayback",
            "turnId": turn_id,
            "generatedChars": generated_chars,
        })
        return await self.wait_playout(turn_id, timeout)

    async def wait_playout(self, turn_id: str, timeout: float) -> PlayoutResult:
        waiter = self._playout_waiters.get(turn_id)
        if waiter is None or waiter.done():
            waiter = asyncio.get_running_loop().create_future()
            self._playout_waiters[turn_id] = waiter
        try:
            return await asyncio.wait_for(asyncio.shield(waiter), timeout)
        except TimeoutError:
            return PlayoutResult(turn_id, "UNKNOWN", 0, "UNKNOWN")
        finally:
            self._playout_waiters.pop(turn_id, None)

    def _accept_control(self, raw: str) -> None:
        try:
            message = json.loads(raw)
            if message.get("type") != "playback":
                return
            turn_id = str(message["turnId"])
            status = str(message["status"])
            confidence = str(message.get("confidence", "UNKNOWN"))
            played_chars = int(message.get("playedChars", 0))
            if status not in {"COMPLETED", "INTERRUPTED", "UNKNOWN"}:
                raise ValueError("invalid playout status")
            if confidence not in {"ALIGNED", "ESTIMATED", "UNKNOWN"} or played_chars < 0:
                raise ValueError("invalid playout result")
        except (KeyError, TypeError, ValueError, json.JSONDecodeError):
            logger.warning("[FS-IO] ignored invalid media control call=%s", self.call_id)
            return
        waiter = self._playout_waiters.get(turn_id)
        if waiter is not None and not waiter.done():
            waiter.set_result(PlayoutResult(turn_id, status, played_chars, confidence))

    async def stop_playback(self) -> None:
        """Tell the selected media module to drop queued outbound audio."""
        socket = self._socket
        if socket is not None and not socket.closed:
            await socket.send_json({"type": "killAudio"})

    async def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        socket = self._socket
        if socket is not None and not socket.closed:
            await socket.close(code=1000, message=b"call ended")
        for turn_id, waiter in list(self._playout_waiters.items()):
            if not waiter.done():
                waiter.set_result(PlayoutResult(turn_id, "UNKNOWN", 0, "UNKNOWN"))
        self._offer_end()

    @property
    def connected(self) -> bool:
        return self._socket is not None and not self._socket.closed

    @property
    def frame_count(self) -> int:
        return self._frame_count
