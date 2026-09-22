import asyncio
import json
import time
from unittest.mock import AsyncMock

import pytest
from aiohttp.test_utils import TestClient, TestServer

from realtime_voice.freeswitch_io import MAX_QUEUE_FRAMES, AudioFrame, FreeswitchIO
from realtime_voice.phone_worker import PhoneWorkerApp
from realtime_voice.session import Turn, VoiceSession
from realtime_voice.settings import Settings


def settings(token: str = "x" * 32) -> Settings:
    return Settings(
        java_url="http://127.0.0.1:8081",
        worker_token="v" * 32,
        asr_key="asr",
        asr_resource="resource",
        asr_url="wss://asr.example.test",
        tts_key="tts",
        tts_speaker="speaker",
        tts_resource="resource",
        tts_model="model",
        tts_url="wss://tts.example.test",
        greeting="",
        outbound_internal_token=token,
        fs_esl_host="127.0.0.1",
        fs_esl_port=8021,
        fs_esl_password="secret",
        fs_sip_domain="127.0.0.1",
        phone_worker_port=8082,
    )


def test_phone_worker_rejects_short_internal_token():
    with pytest.raises(ValueError, match="OUTBOUND_INTERNAL_TOKEN"):
        PhoneWorkerApp(settings("short"))


@pytest.mark.asyncio
async def test_audio_input_queue_is_bounded_and_keeps_latest_frames():
    transport = FreeswitchIO("call-1")
    for index in range(MAX_QUEUE_FRAMES + 5):
        transport._offer_audio(AudioFrame(index.to_bytes(2, "little")))

    assert transport._audio_queue.qsize() == MAX_QUEUE_FRAMES
    first = await asyncio.wait_for(transport.read_audio(), timeout=0.1)
    assert first is not None
    assert int.from_bytes(first.data, "little") == 5


@pytest.mark.asyncio
async def test_stop_playback_uses_audio_fork_kill_message():
    transport = FreeswitchIO("call-1")
    socket = AsyncMock()
    socket.closed = False
    transport._socket = socket

    await transport.stop_playback()

    socket.send_json.assert_awaited_once_with({"type": "killAudio"})


@pytest.mark.asyncio
async def test_playout_ack_is_correlated_to_the_java_turn_id():
    transport = FreeswitchIO("call-1")
    socket = AsyncMock()
    socket.closed = False
    transport._socket = socket

    waiter = asyncio.create_task(transport.wait_playout("java-turn", timeout=0.2))
    await asyncio.sleep(0)
    transport._accept_control(json.dumps({
        "type": "playback",
        "turnId": "java-turn",
        "status": "COMPLETED",
        "playedChars": 4,
        "confidence": "ALIGNED",
    }))

    result = await waiter
    assert result.turn_id == "java-turn"
    assert result.status == "COMPLETED"
    assert result.played_chars == 4


@pytest.mark.asyncio
async def test_voice_session_settles_the_turn_from_media_ack():
    session = VoiceSession.__new__(VoiceSession)
    session.control = AsyncMock()
    session._reply_task = None
    session._tts_provider = _CompletedTts("您好")
    session.control.events = _events
    turn = Turn(
        id="java-turn",
        accepted={
            "turnId": "java-turn",
            "utteranceId": "utterance-1",
            "streamPath": "/stream",
        },
    )

    async def settle_playout(turn_id: str, generated_chars: int):
        assert turn_id == "java-turn"
        assert generated_chars == 2
        return type("Result", (), {
            "status": "COMPLETED", "played_chars": 2, "confidence": "ALIGNED"
        })()

    await session.run_reply(turn, AsyncMock(), settle_playout)

    session.control.request.assert_awaited_with(
        "PUT",
        "/turns/java-turn/playout",
        {
            "utteranceId": "utterance-1",
            "revision": 1,
            "status": "COMPLETED",
            "playedChars": 2,
            "confidence": "ALIGNED",
            "last": True,
        },
    )


async def _events(_path):
    yield {
        "type": "text_delta",
        "text": "您好",
        "utteranceId": "utterance-1",
    }
    yield {
        "type": "generation_end",
        "status": "GENERATED",
        "utteranceId": "utterance-1",
    }


class _CompletedTts:
    def __init__(self, text: str):
        self.text = text

    def stream(self):
        return _CompletedTtsStream()


class _CompletedTtsStream:
    def push_text(self, _text: str):
        return None

    def end_input(self):
        return None

    async def aclose(self):
        return None

    def __aiter__(self):
        return self

    async def __anext__(self):
        raise StopAsyncIteration


def test_phone_worker_registers_call_scoped_media_endpoint():
    app = PhoneWorkerApp(settings())
    paths = {resource.canonical for resource in app.app.router.resources()}
    assert "/media/{call_id}" in paths


def test_opening_turn_uses_the_id_returned_by_java():
    accepted = {
        "turnId": "java-opening-id",
        "utteranceId": "utterance-1",
        "streamPath": "/stream",
    }

    turn = Turn.from_accepted(accepted)

    assert turn.id == "java-opening-id"
    assert turn.accepted is accepted


@pytest.mark.asyncio
async def test_prepare_returns_only_after_worker_dependencies_are_ready():
    worker = PhoneWorkerApp(settings())

    async def prepare_then_wait(ctx):
        await asyncio.sleep(0.03)
        ctx.prepared.set()
        await ctx.finished.wait()

    worker._run_call = prepare_then_wait
    client = TestClient(TestServer(worker.app))
    await client.start_server()
    started = time.monotonic()
    try:
        response = await client.post(
            "/prepare",
            headers={"Authorization": f"Bearer {'x' * 32}"},
            json={
                "callId": "call-ready",
                "extension": "1000",
                "agentId": "harness",
                "claimSecret": "claim-secret",
                "sessionId": "session-1",
            },
        )
        body = await response.json()
    finally:
        await worker.stop()
        await client.close()

    assert response.status == 200
    assert body["status"] == "ready"
    assert time.monotonic() - started >= 0.03
