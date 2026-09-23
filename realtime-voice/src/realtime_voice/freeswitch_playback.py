"""Legacy mod_audio_fork downlink: playAudio -> ESL file event -> actual playback.

The module owns its temporary files and deletes them on channel teardown. Only one
400 ms chunk is in flight, so backpressure bounds buffering and interruption never
leaves a playlist of old speech on FreeSWITCH.
"""
from __future__ import annotations

import asyncio
import base64
import contextlib
import json
import re
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass

CHUNK_BYTES = 16000 * 2 * 400 // 1000


@dataclass(frozen=True)
class PlayoutResult:
    turn_id: str
    status: str
    played_chars: int
    confidence: str


async def read_message(reader: asyncio.StreamReader) -> tuple[dict[str, str], bytes]:
    headers = {}
    while True:
        line = await reader.readline()
        if not line:
            raise ConnectionError("FreeSWITCH ESL disconnected")
        if line in (b"\n", b"\r\n"):
            break
        key, value = line.decode().rstrip("\r\n").split(":", 1)
        headers[key] = value.strip()
    length = int(headers.get("Content-Length", "0"))
    if not 0 <= length <= 1024 * 1024:
        raise ConnectionError("Invalid ESL frame length")
    return headers, await reader.readexactly(length)


class PlaybackEsl:
    """A filtered event socket; command sockets never compete with its reader."""

    def __init__(self, host: str, port: int, password: str):
        self.host, self.port, self.password = host, port, password
        self._writer = None
        self._reader_task = None
        self.ready = False

    async def _connect(self):
        reader, writer = await asyncio.open_connection(self.host, self.port)
        try:
            headers, _ = await read_message(reader)
            if headers.get("Content-Type") != "auth/request":
                raise ConnectionError("Missing ESL authentication request")
            await self._request(reader, writer, f"auth {self.password}")
            return reader, writer
        except BaseException:
            writer.close()
            await writer.wait_closed()
            raise

    async def _request(self, reader, writer, command: str) -> str:
        writer.write((command + "\n\n").encode())
        await writer.drain()
        headers, body = await read_message(reader)
        result = body.decode() if body else headers.get("Reply-Text", "")
        if not result.strip().startswith("+OK"):
            # Never include the command: authentication contains a credential.
            raise ConnectionError("FreeSWITCH rejected playback command")
        return result

    async def start(self, call_id: str, on_event: Callable, on_failure: Callable):
        uuid.UUID(call_id)  # Call IDs are also ESL arguments; reject injected commands.
        try:
            async with asyncio.timeout(5):
                reader, self._writer = await self._connect()
                await self._request(reader, self._writer, f"filter Unique-ID {call_id}")
                await self._request(reader, self._writer,
                                    "event json PLAYBACK_STOP CHANNEL_HANGUP CUSTOM mod_audio_fork::play_audio")
            self.ready = True
            self._reader_task = asyncio.create_task(self._read_events(reader, on_event, on_failure))
        except BaseException:
            await self.close()
            raise

    async def _read_events(self, reader, on_event, on_failure):
        try:
            while True:
                headers, body = await read_message(reader)
                if headers.get("Content-Type") == "text/event-json":
                    event = json.loads(body)
                    if not isinstance(event, dict):
                        raise ValueError("Invalid ESL event")
                    on_event(event)
        except (ConnectionError, OSError, ValueError, asyncio.IncompleteReadError):
            self.ready = False
            on_failure()

    async def api(self, command: str):
        async def execute():
            async with asyncio.timeout(5):
                reader, writer = await self._connect()
                try:
                    return await self._request(reader, writer, f"api {command}")
                finally:
                    writer.close()
                    await writer.wait_closed()
        task = asyncio.create_task(execute())
        try:
            return await asyncio.shield(task)
        except asyncio.CancelledError:
            # Wait for command acceptance before the caller sends uuid_break. A
            # cancelled broadcast must not arrive AFTER the interrupt command.
            while not task.done():
                try:
                    await asyncio.shield(task)
                except asyncio.CancelledError:
                    continue  # Cleanup may cancel the parent a second time.
                except Exception:
                    break
            with contextlib.suppress(Exception):
                task.result()
            raise

    async def close(self):
        self.ready = False
        if self._reader_task is not None:
            self._reader_task.cancel()
            await asyncio.gather(self._reader_task, return_exceptions=True)
        if self._writer is not None:
            self._writer.close()
            await self._writer.wait_closed()
            self._writer = None


class AudioForkPlayback:
    def __init__(self, call_id: str, esl: PlaybackEsl):
        self.call_id, self.esl = call_id, esl
        self._buffer = bytearray()
        self._file_waiter = None
        self._stop_waiter = None
        self._chunk_id = None
        self._path = None
        self._played_bytes = 0
        self._failure = False
        self._active = None

    @property
    def ready(self) -> bool:
        return self.esl.ready and not self._failure

    async def start(self):
        await self.esl.start(self.call_id, self._on_event, self._on_failure)

    def _on_failure(self):
        self._failure = True
        for waiter in (self._file_waiter, self._stop_waiter):
            if waiter is not None and not waiter.done():
                waiter.set_result(None)

    def _on_event(self, event: dict):
        if event.get("Unique-ID") != self.call_id:
            return
        if event.get("Event-Name") == "CHANNEL_HANGUP":
            self._on_failure()
        elif event.get("Event-Subclass") == "mod_audio_fork::play_audio":
            try:
                data = json.loads(event.get("_body", "{}"))
                path = data.get("file", "")
                if data.get("id") != self._chunk_id:
                    return  # Includes delayed events from an interrupted turn.
                # Only module-created raw files for this channel may be played.
                if not re.fullmatch(r"/[\w/.-]+/" + re.escape(self.call_id) + r"_\d+\.tmp\.r16", path):
                    raise ValueError("Unexpected module playback file")
                if self._file_waiter is not None and not self._file_waiter.done():
                    self._file_waiter.set_result(path)
            except (ValueError, TypeError, AttributeError):
                self._on_failure()
        elif (event.get("Event-Name") == "PLAYBACK_STOP"
              and self._path is not None
              and event.get("Playback-File-Path") == self._path
              and self._stop_waiter is not None and not self._stop_waiter.done()):
            self._stop_waiter.set_result(event.get("Playback-Status"))

    async def send_audio(self, pcm: bytes, send_json: Callable[[dict], Awaitable[None]]):
        if len(pcm) % 2:
            raise ValueError("PCM must contain complete signed 16-bit samples")
        # Copy at most one chunk into our buffer, even for a large provider frame.
        view = memoryview(pcm)
        while view:
            count = min(CHUNK_BYTES - len(self._buffer), len(view))
            self._buffer.extend(view[:count])
            view = view[count:]
            if len(self._buffer) == CHUNK_BYTES:
                chunk = bytes(self._buffer)
                self._buffer.clear()
                await self._play_chunk(chunk, send_json)

    async def _play_chunk(self, pcm: bytes, send_json):
        # Own the media operation, not the parent task that also settles Java state.
        # Stopping audio must never wait for a Java HTTP request to finish.
        task = asyncio.create_task(self._play(pcm, send_json))
        self._active = task
        try:
            await task
        finally:
            if self._active is task:
                self._active = None

    async def _play(self, pcm: bytes, send_json):
        if self._failure or not self.esl.ready:
            raise ConnectionError("FreeSWITCH playback events unavailable")
        self._chunk_id = str(uuid.uuid4())
        loop = asyncio.get_running_loop()
        self._file_waiter, self._stop_waiter = loop.create_future(), loop.create_future()
        try:
            async with asyncio.timeout(5):
                await send_json({"type": "playAudio", "data": {
                    "audioContentType": "raw", "sampleRate": 16000,
                    "audioContent": base64.b64encode(pcm).decode("ascii"),
                    "id": self._chunk_id,
                }})
                self._path = await self._file_waiter
                if self._path is None:
                    raise ConnectionError("FreeSWITCH did not supply audio file")
                await self.esl.api(f"uuid_broadcast {self.call_id} {self._path} aleg")
                status = await self._stop_waiter
                if status != "done":
                    raise ConnectionError("FreeSWITCH playback did not complete")
                self._played_bytes += len(pcm)
        except Exception:
            self._failure = True
            raise
        finally:
            self._chunk_id = self._path = None
            self._file_waiter = self._stop_waiter = None

    async def finish(self, turn_id: str, chars: int, send_json, timeout: float) -> PlayoutResult:
        try:
            async with asyncio.timeout(timeout):
                if self._buffer:
                    chunk = bytes(self._buffer)
                    self._buffer.clear()
                    await self._play_chunk(chunk, send_json)
            if self._failure or not self._played_bytes:
                self._failure = True
                return PlayoutResult(turn_id, "UNKNOWN", 0, "UNKNOWN")
            # Completion is confirmed; character/sample alignment is not provided.
            return PlayoutResult(turn_id, "COMPLETED", chars, "ESTIMATED")
        except (ConnectionError, TimeoutError):
            self._failure = True
            return PlayoutResult(turn_id, "UNKNOWN", 0, "UNKNOWN")
        finally:
            self._played_bytes = 0

    async def stop(self):
        task = self._active
        if task is not None and task is not asyncio.current_task() and not task.done():
            if not task.cancelling():
                task.cancel()
            await asyncio.gather(task, return_exceptions=True)
        self._buffer.clear()
        self._played_bytes = 0
        try:
            await self.esl.api(f"uuid_break {self.call_id} all")
        except Exception:
            self._failure = True
            raise
        self._failure = False

    async def close(self):
        with contextlib.suppress(ConnectionError, OSError, TimeoutError):
            await self.stop()
        await self.esl.close()
