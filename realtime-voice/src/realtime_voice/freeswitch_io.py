"""Bounded WebSocket audio transport for a FreeSWITCH PHONE call."""
from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field

from aiohttp import WSMsgType, web

from realtime_voice.freeswitch_playback import AudioForkPlayback, PlayoutResult

logger = logging.getLogger("realtime_voice.freeswitch_io")

SAMPLE_RATE = 16000
MAX_QUEUE_FRAMES = 300


@dataclass(frozen=True)
class AudioFrame:
    data: bytes
    sample_rate: int = SAMPLE_RATE


@dataclass
class FreeswitchIO:
    """Owns one authenticated-by-call-id media connection and its bounded input queue."""

    call_id: str
    playback: AudioForkPlayback | None = None
    _audio_queue: asyncio.Queue[AudioFrame | None] = field(
        default_factory=lambda: asyncio.Queue(maxsize=MAX_QUEUE_FRAMES)
    )
    _socket: web.WebSocketResponse | None = None
    _connected: asyncio.Event = field(default_factory=asyncio.Event)
    _closed: bool = False
    _frame_count: int = 0

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

    def _playback_socket(self):
        socket = self._socket
        if socket is None or socket.closed or self._closed or self.playback is None:
            raise ConnectionError("FreeSWITCH playback is not connected")
        return socket

    async def send_audio(self, pcm: bytes) -> None:
        socket = self._playback_socket()
        await self.playback.send_audio(pcm, socket.send_json)

    async def finish_playback(
        self, turn_id: str, generated_chars: int, timeout: float = 10.0
    ) -> PlayoutResult:
        socket = self._playback_socket()
        return await self.playback.finish(turn_id, generated_chars, socket.send_json, timeout)

    async def stop_playback(self) -> None:
        if self.playback is not None:
            await self.playback.stop()

    async def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        if self.playback is not None:
            await self.playback.close()
        socket = self._socket
        if socket is not None and not socket.closed:
            await socket.close(code=1000, message=b"call ended")
        self._offer_end()

    @property
    def connected(self) -> bool:
        return (self._socket is not None and not self._socket.closed
                and self.playback is not None and self.playback.ready)

    @property
    def frame_count(self) -> int:
        return self._frame_count
