"""HTTP control and WebSocket media entry point for PHONE voice calls."""
from __future__ import annotations

import asyncio
import contextlib
import hmac
import logging
import uuid
from dataclasses import dataclass, field

from aiohttp import web
from dotenv import load_dotenv

from realtime_voice.control import Control
from realtime_voice.freeswitch_io import FreeswitchIO
from realtime_voice.session import Turn, VoiceSession
from realtime_voice.settings import Settings

logger = logging.getLogger("realtime_voice.phone_worker")
load_dotenv()


@dataclass
class PhoneCallContext:
    call_id: str
    extension: str
    domain: str
    agent_id: str
    claim_secret: str
    session_id: str
    prompt_id: int | None
    communication_goal: str
    fs_io: FreeswitchIO
    control: Control | None = None
    session: VoiceSession | None = None
    task: asyncio.Task | None = None
    heartbeat: asyncio.Task | None = None
    audio_pump: asyncio.Task | None = None
    reply_task: asyncio.Task | None = None
    prepared: asyncio.Event = field(default_factory=asyncio.Event)
    prepare_error: str | None = None
    finished: asyncio.Event = field(default_factory=asyncio.Event)
    turn_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    cleanup_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    cleaned: bool = False

    async def cleanup(self) -> None:
        async with self.cleanup_lock:
            if self.cleaned:
                return
            self.cleaned = True
            self.finished.set()
            current = asyncio.current_task()
            tasks = [
                task for task in (self.reply_task, self.audio_pump, self.heartbeat)
                if task is not None and task is not current and not task.done()
            ]
            for task in tasks:
                task.cancel()
            await self.fs_io.close()
            if tasks:
                await asyncio.gather(*tasks, return_exceptions=True)
            if self.session is not None:
                await self.session.cleanup()


class PhoneWorkerApp:
    def __init__(self, settings: Settings):
        self.settings = settings
        if len(settings.outbound_internal_token) < 32:
            raise ValueError("OUTBOUND_INTERNAL_TOKEN must contain at least 32 characters")
        self._sessions: dict[str, PhoneCallContext] = {}
        self.app = web.Application(middlewares=[self._authenticate_control])
        self.app.router.add_post("/prepare", self.handle_prepare)
        self.app.router.add_post("/cleanup/{call_id}", self.handle_cleanup)
        self.app.router.add_get("/media/{call_id}", self.handle_media)
        self.app.router.add_get("/health", self.handle_health)
        self._runner: web.AppRunner | None = None

    @web.middleware
    async def _authenticate_control(self, request: web.Request, handler):
        if request.path == "/health" or request.path.startswith("/media/"):
            return await handler(request)
        expected = f"Bearer {self.settings.outbound_internal_token}"
        supplied = request.headers.get("Authorization", "")
        if not hmac.compare_digest(expected, supplied):
            raise web.HTTPUnauthorized()
        return await handler(request)

    async def handle_health(self, request: web.Request) -> web.Response:
        return web.json_response({
            "status": "ok",
            "activeCalls": len(self._sessions),
            "playoutAcknowledgements": True,
        })

    async def handle_prepare(self, request: web.Request) -> web.Response:
        body = await request.json()
        call_id = body["callId"]
        existing = self._sessions.get(call_id)
        if existing is not None:
            if existing.claim_secret != body["claimSecret"]:
                raise web.HTTPConflict(text="call preparation differs")
            await self._wait_prepared(existing)
            return self._prepared_response(call_id)

        ctx = PhoneCallContext(
            call_id=call_id,
            extension=body["extension"],
            domain=body.get("domain", "127.0.0.1"),
            agent_id=body["agentId"],
            claim_secret=body["claimSecret"],
            session_id=body["sessionId"],
            prompt_id=body.get("promptId"),
            communication_goal=body.get("communicationGoal", ""),
            fs_io=FreeswitchIO(call_id),
        )
        self._sessions[call_id] = ctx
        ctx.task = asyncio.create_task(self._run_call(ctx), name=f"phone-call-{call_id}")
        await self._wait_prepared(ctx)
        return self._prepared_response(call_id)

    def _prepared_response(self, call_id: str) -> web.Response:
        return web.json_response({
            "status": "ready",
            "callId": call_id,
            "playoutAcknowledgements": True,
        })

    async def _wait_prepared(self, ctx: PhoneCallContext) -> None:
        try:
            await asyncio.wait_for(ctx.prepared.wait(), timeout=10)
        except TimeoutError as exc:
            await ctx.cleanup()
            raise web.HTTPServiceUnavailable(text="phone worker preparation timed out") from exc
        if ctx.prepare_error is not None:
            raise web.HTTPServiceUnavailable(text=ctx.prepare_error)

    async def handle_media(self, request: web.Request) -> web.StreamResponse:
        ctx = self._sessions.get(request.match_info["call_id"])
        if ctx is None:
            raise web.HTTPNotFound(text="unknown call")
        if not hmac.compare_digest(request.query.get("token", ""), ctx.claim_secret):
            raise web.HTTPUnauthorized(text="invalid media token")
        try:
            return await ctx.fs_io.serve(request)
        finally:
            ctx.finished.set()

    async def handle_cleanup(self, request: web.Request) -> web.Response:
        ctx = self._sessions.get(request.match_info["call_id"])
        if ctx is not None:
            await ctx.cleanup()
        return web.json_response({"status": "cleaning"})

    async def _run_call(self, ctx: PhoneCallContext) -> None:
        control = Control(self.settings.java_url, self.settings.outbound_internal_token, ctx.call_id)
        ctx.control = control
        try:
            await control.claim_phone("phone-worker", ctx.claim_secret)
            ctx.session = VoiceSession(self.settings, control)

            async def on_final(text: str) -> None:
                asyncio.create_task(self._handle_transcript(ctx, text))

            await ctx.session.start_asr(on_final)
            ctx.heartbeat = asyncio.create_task(self._keep_lease(ctx))
            ctx.prepared.set()
            await ctx.fs_io.wait_connected(timeout=60)
            await control.notify_answered()
            await control.notify_ready(ready=True)
            ctx.audio_pump = asyncio.create_task(self._pump_input(ctx))

            opening = Turn.from_accepted(await control.start_opening())
            ctx.reply_task = asyncio.create_task(
                ctx.session.run_reply(
                    opening, ctx.fs_io.send_audio, ctx.fs_io.finish_playback
                ),
                name=f"opening-{ctx.call_id}",
            )
            await ctx.finished.wait()
        except TimeoutError:
            logger.warning("[phone-worker] media connection timeout call=%s", ctx.call_id)
        except asyncio.CancelledError:
            raise
        except Exception:
            if not ctx.prepared.is_set():
                ctx.prepare_error = "phone worker dependencies are unavailable"
                ctx.prepared.set()
            logger.exception("[phone-worker] call failed call=%s", ctx.call_id)
        finally:
            if not ctx.prepared.is_set():
                ctx.prepare_error = "phone worker stopped before preparation completed"
                ctx.prepared.set()
            await ctx.cleanup()
            with contextlib.suppress(Exception):
                await control.end_call()
            await control.close()
            self._sessions.pop(ctx.call_id, None)

    async def _keep_lease(self, ctx: PhoneCallContext) -> None:
        assert ctx.control is not None
        while not ctx.finished.is_set():
            state = await ctx.control.notify_ready(ready=ctx.fs_io.connected)
            if state.get("state") in {"ENDING", "ENDED", "FAILED"}:
                ctx.finished.set()
                return
            await asyncio.sleep(3)

    async def _pump_input(self, ctx: PhoneCallContext) -> None:
        assert ctx.session is not None
        while not ctx.finished.is_set():
            frame = await ctx.fs_io.read_audio()
            if frame is None:
                ctx.finished.set()
                return
            ctx.session.feed_audio(frame.data, frame.sample_rate)

    async def _handle_transcript(self, ctx: PhoneCallContext, text: str) -> None:
        assert ctx.control is not None and ctx.session is not None
        async with ctx.turn_lock:
            await ctx.fs_io.stop_playback()
            await ctx.session.interrupt_reply()
            turn = ctx.session.begin_turn(str(uuid.uuid4()))
            turn.accepted = await ctx.control.submit_text(turn.id, text)
            ctx.reply_task = asyncio.create_task(
                ctx.session.run_reply(
                    turn, ctx.fs_io.send_audio, ctx.fs_io.finish_playback
                ),
                name=f"phone-turn-{turn.id}",
            )

    async def start(self) -> None:
        self._runner = web.AppRunner(self.app)
        await self._runner.setup()
        site = web.TCPSite(self._runner, "0.0.0.0", self.settings.phone_worker_port)
        await site.start()
        logger.info("[phone-worker] listening on %s", self.settings.phone_worker_port)

    async def stop(self) -> None:
        for ctx in list(self._sessions.values()):
            await ctx.cleanup()
        if self._runner is not None:
            await self._runner.cleanup()


async def _serve_forever() -> None:
    app = PhoneWorkerApp(Settings.load(require_livekit=False))
    await app.start()
    try:
        await asyncio.Event().wait()
    finally:
        await app.stop()


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
    asyncio.run(_serve_forever())


if __name__ == "__main__":
    main()
