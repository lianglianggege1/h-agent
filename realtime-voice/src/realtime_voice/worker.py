"""LiveKit owns audio/turn detection; Java exclusively owns messages, runs and context."""
from __future__ import annotations

import asyncio
import contextlib
import json
import os
import uuid
from dataclasses import dataclass, field

from dotenv import load_dotenv
from livekit.agents import Agent, AgentServer, AgentSession, JobContext, cli, llm
from livekit.agents.voice import room_io
from livekit.plugins import silero

from .control import Control
from .providers.asr import HuoshanSTT
from .providers.tts import HuoshanRealtimeTTS
from .settings import Settings


class JavaModel(llm.LLM):
    """Marker enables the pipeline. The Agent's llm_node is the only inference path."""
    def chat(self, **kwargs):
        raise RuntimeError("Use JavaAgent.llm_node")


@dataclass
class Turn:
    id: str = field(default_factory=lambda: str(uuid.uuid4()))
    accepted: dict | None = None
    generated: str = ""
    completed: bool = False


def played_prefix(generated: str, forwarded: str) -> int:
    # Never use a text-length guess when the synchronized transcript differs.
    # LiveKit may strip boundary whitespace; preserve offsets in original model output.
    if generated.startswith(forwarded):
        return len(forwarded)
    stripped = generated.lstrip()
    if forwarded and stripped.startswith(forwarded):
        return len(generated) - len(stripped) + len(forwarded)
    return 0


class JavaAgent(Agent):
    def __init__(self, control: Control):
        super().__init__(instructions="Voice transport", llm=JavaModel())
        self.control = control
        self.handle = None
        self.settling: asyncio.Task | None = None
        self.failed = asyncio.Event()

    async def on_user_turn_completed(self, turn_ctx, new_message):
        if self.settling:
            # Physical generation exit and final playout must precede the next Java run.
            await asyncio.wait_for(asyncio.shield(self.settling), timeout=20)
        if self.failed.is_set():
            raise RuntimeError("Previous voice turn did not settle")

    async def llm_node(self, chat_ctx, tools, model_settings):
        turn = Turn()
        handle = self.handle
        if handle is None:
            raise RuntimeError("Missing LiveKit speech handle")
        user = next((m for m in reversed(chat_ctx.items)
                     if isinstance(m, llm.ChatMessage) and m.role == "user"), None)
        if user is None or not user.text_content:
            return
        submission = asyncio.create_task(self.control.request("POST", "/turns", {
            "turnId": turn.id, "text": user.text_content,
        }))
        self.settling = asyncio.create_task(self._settle(turn, handle, submission))
        self.settling.add_done_callback(self._settled)
        try:
            turn.accepted = await asyncio.shield(submission)
            async for event in self.control.events(turn.accepted["streamPath"]):
                if event["utteranceId"] != turn.accepted["utteranceId"]:
                    raise RuntimeError("Mismatched Java utterance")
                if event["type"] == "text_delta":
                    turn.generated += event["text"]
                    yield event["text"]
                elif event["type"] == "generation_end":
                    turn.completed = event["status"] == "GENERATED"
        finally:
            # The settlement task owns interruption if the submission is still in flight.
            if (not turn.completed and submission.done() and not submission.cancelled()
                    and submission.exception() is None):
                await asyncio.shield(self.control.request(
                    "POST", f"/turns/{turn.id}/interrupt"))

    def _settled(self, task):
        if task.cancelled() or task.exception() is not None:
            self.failed.set()

    async def _settle(self, turn, handle, submission):
        accepted = await submission
        await handle
        if not turn.completed or handle.interrupted or handle.exception():
            await self.control.request("POST", f"/turns/{turn.id}/interrupt")
        forwarded = "".join(
            item.text_content or "" for item in handle.chat_items
            if isinstance(item, llm.ChatMessage) and item.role == "assistant"
        )
        chars = played_prefix(turn.generated, forwarded)
        complete = (turn.completed and not handle.interrupted and not handle.exception()
                    and chars == len(turn.generated) and bool(turn.generated))
        await self.control.request("PUT", f"/turns/{turn.id}/playout", {
            "utteranceId": accepted["utteranceId"], "revision": 1,
            "status": "COMPLETED" if complete else "INTERRUPTED",
            "playedChars": chars, "confidence": "ESTIMATED", "last": True,
        })
        # Cancellation is asynchronous. Do not release the next turn until Java has exited.
        async with asyncio.timeout(15):
            while True:
                state = await self.control.request("GET", f"/turns/{turn.id}")
                if state["state"] == "COMMITTED":
                    return
                await asyncio.sleep(0.1)


load_dotenv()
server = AgentServer()


@server.rtc_session(agent_name=os.environ.get("VOICE_AGENT_NAME", "h-agent-voice"))
async def entrypoint(ctx: JobContext):
    settings = Settings.load()
    metadata = json.loads(ctx.job.metadata)
    control = Control(settings.java_url, settings.worker_token, metadata["callId"])
    session = None
    heartbeat = None
    agent = JavaAgent(control)
    try:
        claim = await control.claim(ctx.room.name, metadata["claimSecret"], ctx.job.id)
        await ctx.connect()
        session = AgentSession(
            stt=HuoshanSTT(api_key=settings.asr_key, resource_id=settings.asr_resource,
                           ws_url=settings.asr_url),
            tts=HuoshanRealtimeTTS(
                api_key=settings.tts_key, speaker=settings.tts_speaker,
                resource_id=settings.tts_resource, model=settings.tts_model,
                ws_url=settings.tts_url,
            ),
            vad=silero.VAD.load(),
            turn_handling={
                "turn_detection": "vad",
                "preemptive_generation": {"enabled": False},
                "interruption": {"enabled": True, "mode": "vad",
                                 "resume_false_interruption": False},
            },
            use_tts_aligned_transcript=False,
            tts_text_transforms=[],
            user_away_timeout=None,
        )

        @session.on("speech_created")
        def speech_created(event):
            agent.handle = event.speech_handle

        closed = asyncio.Event()

        @session.on("close")
        def session_closed(event):
            closed.set()

        async def keep_lease():
            while not closed.is_set():
                state = await control.request("POST", "/heartbeat", {"ready": True})
                if state["state"] in ("ENDING", "ENDED", "FAILED") or agent.failed.is_set():
                    return
                await asyncio.sleep(3)

        # A lease begins before potentially slow model/session setup.
        heartbeat = asyncio.create_task(keep_lease())
        await session.start(agent=agent, room=ctx.room, room_options=room_io.RoomOptions(
            participant_identity=claim["participantIdentity"], close_on_disconnect=False,
            text_output=room_io.TextOutputOptions(sync_transcription=True),
        ))
        waiter = asyncio.create_task(closed.wait())
        await asyncio.wait([heartbeat, waiter], return_when=asyncio.FIRST_COMPLETED)
        waiter.cancel()
        if heartbeat.done():
            heartbeat.result()
    finally:
        if heartbeat:
            heartbeat.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await heartbeat
        if session:
            await session.aclose()
        if agent.settling:
            with contextlib.suppress(Exception):
                await asyncio.wait_for(asyncio.shield(agent.settling), 15)
        with contextlib.suppress(Exception):
            await control.request("POST", "/end")
        await control.close()


def main():
    Settings.load()
    cli.run_app(server)


if __name__ == "__main__":
    main()
