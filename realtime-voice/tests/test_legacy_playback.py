"""Regression at the worker -> legacy mod_audio_fork/ESL protocol boundary."""
import asyncio
import base64
import json
from unittest.mock import AsyncMock

import pytest

from realtime_voice.freeswitch_io import FreeswitchIO
from realtime_voice.freeswitch_playback import (
    CHUNK_BYTES,
    AudioForkPlayback,
    PlaybackEsl,
    read_message,
)

CALL = "400bd6aa-c07a-40bc-926c-cc1f9230990e"


class LegacyModule:
    """Protocol peer: JSON creates a file, only uuid_broadcast plays it.

    This intentionally never understands endPlayback or sends WebSocket acks.
    """
    def __init__(self):
        self.ready = True
        self.closed = False
        self.messages = []
        self.commands = []
        self.files = {}
        self.played = bytearray()
        self.complete = True
        self.materialize = True
        self.broadcast = asyncio.Event()
        self.send_bytes = AsyncMock()  # Legacy module discards binary frames.

    async def start(self, call_id, on_event, on_failure):
        self.on_event, self.on_failure = on_event, on_failure

    async def send_json(self, message):
        self.messages.append(message)
        assert message["type"] == "playAudio"
        data = dict(message["data"])
        assert data["audioContentType"] == "raw"
        assert data["sampleRate"] == 16000
        path = f"/tmp/{CALL}_{len(self.files)}.tmp.r16"
        self.files[path] = base64.b64decode(data.pop("audioContent"))
        data["file"] = path
        if self.materialize:
            self.on_event({"Event-Name": "CUSTOM", "Event-Subclass": "mod_audio_fork::play_audio",
                           "Unique-ID": CALL, "_body": json.dumps(data)})

    async def api(self, command):
        self.commands.append(command)
        if command.startswith("uuid_broadcast "):
            _, call, path, leg = command.split()
            assert call == CALL and leg == "aleg"
            self.played.extend(self.files[path])
            self.broadcast.set()
            if self.complete:
                self.stop_event(path)
        return "+OK"

    def stop_event(self, path, status="done", call=CALL):
        self.on_event({"Event-Name": "PLAYBACK_STOP", "Unique-ID": call,
                       "Playback-File-Path": path, "Playback-Status": status})

    async def close(self, **_kwargs):
        self.ready = False
        self.closed = True


async def transport_with_peer():
    peer = LegacyModule()
    playback = AudioForkPlayback(CALL, peer)
    await playback.start()
    transport = FreeswitchIO(CALL, playback=playback)
    transport._socket = peer
    return transport, peer


@pytest.mark.asyncio
async def test_downlink_uses_legacy_play_audio_instead_of_discarded_binary():
    transport, peer = await transport_with_peer()
    pcm = b"\x01\x02" * (CHUNK_BYTES // 2)
    await transport.send_audio(pcm)
    result = await transport.finish_playback("java-turn", 4)
    peer.send_bytes.assert_not_awaited()
    assert peer.played == pcm
    assert result.turn_id == "java-turn"
    assert (result.status, result.played_chars, result.confidence) == ("COMPLETED", 4, "ESTIMATED")
    assert len(peer.commands) == 1


@pytest.mark.asyncio
async def test_short_tail_is_flushed_and_large_frame_is_bounded():
    transport, peer = await transport_with_peer()
    pcm = b"\x12\x34" * (CHUNK_BYTES * 2 + 3)
    await transport.send_audio(pcm)
    assert bytes(peer.played) == pcm[:-6]
    await transport.finish_playback("turn", 3)
    assert bytes(peer.played) == pcm
    assert max(map(len, peer.files.values())) <= CHUNK_BYTES


@pytest.mark.asyncio
async def test_broadcast_acceptance_and_unrelated_events_do_not_confirm_playout():
    transport, peer = await transport_with_peer()
    peer.complete = False
    task = asyncio.create_task(transport.send_audio(bytes(CHUNK_BYTES)))
    await asyncio.wait_for(peer.broadcast.wait(), 0.2)
    path = next(iter(peer.files))
    peer.stop_event(path, call="another-call")
    peer.stop_event("/tmp/old-turn.r16")
    await asyncio.sleep(0)
    assert not task.done()
    peer.stop_event(path)
    await task
    assert (await transport.finish_playback("turn", 5)).status == "COMPLETED"


@pytest.mark.asyncio
async def test_interrupt_cancels_pending_audio_and_next_turn_cannot_replay_it():
    transport, peer = await transport_with_peer()
    peer.complete = False
    task = asyncio.create_task(transport.send_audio(b"\x01\x02" * CHUNK_BYTES))
    await asyncio.wait_for(peer.broadcast.wait(), 0.2)
    old_message = peer.messages[0]
    await transport.stop_playback()
    assert task.cancelled()
    assert peer.commands[-1] == f"uuid_break {CALL} all"
    assert len(peer.files) == 1
    peer.complete = True
    # Delayed materialization of the old chunk must not trigger playback.
    await peer.send_json(old_message)
    await transport.send_audio(b"\x03\x04")
    result = await transport.finish_playback("new-turn", 1)
    assert result.status == "COMPLETED"
    assert bytes(peer.played) == b"\x01\x02" * (CHUNK_BYTES // 2) + b"\x03\x04"


@pytest.mark.asyncio
@pytest.mark.parametrize("status", ["break", "error", None])
async def test_abnormal_stop_is_never_reported_as_completed(status):
    transport, peer = await transport_with_peer()
    peer.complete = False
    task = asyncio.create_task(transport.send_audio(bytes(CHUNK_BYTES)))
    await peer.broadcast.wait()
    peer.stop_event(next(iter(peer.files)), status)
    with pytest.raises(ConnectionError):
        await task
    assert (await transport.finish_playback("turn", 6)).status == "UNKNOWN"


@pytest.mark.asyncio
async def test_event_disconnect_fails_pending_audio_and_readiness():
    transport, peer = await transport_with_peer()
    peer.complete = False
    task = asyncio.create_task(transport.send_audio(bytes(CHUNK_BYTES)))
    await peer.broadcast.wait()
    peer.ready = False
    peer.on_failure()
    with pytest.raises(ConnectionError):
        await task
    assert not transport.connected


@pytest.mark.asyncio
async def test_missing_file_event_times_out_as_unknown_without_broadcast():
    transport, peer = await transport_with_peer()
    peer.materialize = False
    await transport.send_audio(b"\x01\x02")
    result = await transport.finish_playback("turn", 6, timeout=0.01)
    assert result.status == "UNKNOWN"
    assert not peer.commands


@pytest.mark.asyncio
async def test_silent_tts_does_not_claim_generated_text_was_played():
    transport, _ = await transport_with_peer()
    assert (await transport.finish_playback("turn", 6)).status == "UNKNOWN"


@pytest.mark.asyncio
async def test_esl_parser_handles_fragmented_utf8_and_consecutive_frames():
    reader = asyncio.StreamReader()
    body = json.dumps({"text": "你好"}, ensure_ascii=False).encode()
    wire = f"Content-Type: text/event-json\nContent-Length: {len(body)}\n\n".encode() + body
    async def feed():
        for byte in wire + b"Content-Type: command/reply\nReply-Text: +OK\n\n":
            reader.feed_data(bytes([byte]))
            await asyncio.sleep(0)
        reader.feed_eof()
    task = asyncio.create_task(feed())
    headers, actual = await read_message(reader)
    assert headers["Content-Type"] == "text/event-json"
    assert actual == body
    headers, _ = await read_message(reader)
    assert headers["Reply-Text"] == "+OK"
    await task
    with pytest.raises(ConnectionError):
        await read_message(reader)


@pytest.mark.asyncio
async def test_real_esl_transport_authenticates_filters_and_receives_events():
    commands = []
    clients = []
    event = asyncio.Event()
    received = []
    async def serve(reader, writer):
        clients.append(writer)
        writer.write(b"Content-Type: auth/request\n\n")
        await writer.drain()
        try:
            while True:
                cmd = (await reader.readuntil(b"\n\n")).decode().strip()
                commands.append(cmd)
                writer.write(b"Content-Type: command/reply\nReply-Text: +OK\n\n")
                if cmd.startswith("event json"):
                    data = json.dumps({"Unique-ID": CALL, "Event-Name": "PLAYBACK_STOP"}).encode()
                    writer.write(f"Content-Type: text/event-json\nContent-Length: {len(data)}\n\n".encode() + data)
                await writer.drain()
        except asyncio.IncompleteReadError:
            pass
        finally:
            writer.close()
            await writer.wait_closed()
    server = await asyncio.start_server(serve, "127.0.0.1", 0)
    esl = PlaybackEsl("127.0.0.1", server.sockets[0].getsockname()[1], "test-password")
    def on_event(value):
        received.append(value)
        event.set()
    try:
        await esl.start(CALL, on_event, lambda: None)
        await asyncio.wait_for(event.wait(), 0.2)
        await esl.api(f"uuid_break {CALL} all")
        assert commands.count("auth test-password") == 2
        assert f"filter Unique-ID {CALL}" in commands
        assert received[0]["Unique-ID"] == CALL
    finally:
        await esl.close()
        for writer in clients:
            writer.close()
        server.close()
        await server.wait_closed()


@pytest.mark.asyncio
async def test_esl_broadcast_rejection_fails_audio_instead_of_claiming_completion():
    transport, peer = await transport_with_peer()
    peer.api = AsyncMock(side_effect=ConnectionError("rejected"))
    with pytest.raises(ConnectionError):
        await transport.send_audio(bytes(CHUNK_BYTES))
    assert not transport.connected
    assert (await transport.finish_playback("turn", 1)).status == "UNKNOWN"


@pytest.mark.asyncio
async def test_interruption_does_not_temporarily_fail_readiness_or_wait_for_java_settlement():
    transport, peer = await transport_with_peer()
    peer.complete = False
    java_settlement = asyncio.Event()
    async def reply():
        await transport.send_audio(b"\x01\x02")
        try:
            await transport.finish_playback("turn", 1)
        except asyncio.CancelledError:
            await java_settlement.wait()
            raise
    task = asyncio.create_task(reply())
    await peer.broadcast.wait()
    await asyncio.wait_for(transport.stop_playback(), 0.2)
    assert transport.connected
    assert not task.done()  # Java may be slow; audio was already stopped.
    assert peer.commands[-1] == f"uuid_break {CALL} all"
    java_settlement.set()
    await asyncio.gather(task, return_exceptions=True)


@pytest.mark.asyncio
async def test_cancelled_broadcast_is_accepted_before_break_can_be_sent():
    """The real ESL client shields command acceptance from parent cancellation."""
    esl = PlaybackEsl("test", 8021, "secret")
    writer = AsyncMock()
    writer.close = lambda: None
    esl._connect = AsyncMock(return_value=(object(), writer))
    entered, accepted = asyncio.Event(), asyncio.Event()
    async def request(_reader, _writer, _command):
        entered.set()
        await accepted.wait()
        return "+OK"
    esl._request = request
    task = asyncio.create_task(esl.api(f"uuid_broadcast {CALL} /tmp/test.r16 aleg"))
    await entered.wait()
    task.cancel()
    await asyncio.sleep(0)
    task.cancel()  # Parent reply cancellation and media cleanup can race.
    await asyncio.sleep(0)
    await asyncio.sleep(0)
    assert not task.done()
    accepted.set()
    await asyncio.gather(task, return_exceptions=True)
    assert task.cancelled()
    writer.wait_closed.assert_awaited_once()


@pytest.mark.asyncio
async def test_failed_break_marks_media_unready():
    transport, peer = await transport_with_peer()
    peer.api = AsyncMock(side_effect=ConnectionError("ESL unavailable"))
    with pytest.raises(ConnectionError):
        await transport.stop_playback()
    assert not transport.connected
