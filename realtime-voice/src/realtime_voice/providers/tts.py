from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
import weakref
from dataclasses import dataclass
from typing import Any

import aiohttp
from livekit.agents import (
    DEFAULT_API_CONNECT_OPTIONS,
    APIConnectionError,
    APIConnectOptions,
    APIStatusError,
    tts,
    utils,
)

from .protocols import EventType, Message, MsgType, MsgTypeFlagBits

logger = logging.getLogger(__name__)

DEFAULT_TTS_WS_URL = "wss://openspeech.bytedance.com/api/v3/tts/bidirection"
DEFAULT_TTS_RESOURCE_ID = "seed-icl-2.0"
DEFAULT_TTS_SPEAKER = ""
# ICL 2.0 standard is lower-latency than expressive.
DEFAULT_TTS_MODEL = "seed-tts-2.0-standard"
DEFAULT_SAMPLE_RATE = 24000
DEFAULT_NUM_CHANNELS = 1
# 语速：0 为正常，-50 为 0.5x，100 为 2.0x（火山 audio_params.speech_rate）
DEFAULT_SPEECH_RATE = 10
_WS_CLOSE_TYPES = (
    aiohttp.WSMsgType.CLOSED,
    aiohttp.WSMsgType.CLOSE,
    aiohttp.WSMsgType.CLOSING,
)
_SKIP_EVENTS = {
    EventType.UsageResponse,
    EventType.TTSSentenceStart,
    EventType.TTSSentenceEnd,
    EventType.TTSResponse,
    EventType.TTSSubtitle,
    EventType.TTSEnded,
}
_START_CONNECTION_PAYLOAD = json.dumps(
    {"namespace": "BidirectionalTTS"}
).encode()


def _ws_logid(ws: aiohttp.ClientWebSocketResponse) -> str:
    """获取火山引擎返回的 X-Tt-Logid。

    不同 aiohttp 版本的 ClientWebSocketResponse 属性命名不同：
    新版暴露公开的 response，旧版只有私有属性 _response。
    """
    response = getattr(ws, "response", None) or getattr(ws, "_response", None)
    if response is None:
        return ""
    return response.headers.get("X-Tt-Logid", "")


@dataclass(frozen=True, slots=True)
class HuoshanTTSOptions:
    api_key: str
    resource_id: str
    ws_url: str
    speaker: str
    model: str
    sample_rate: int
    speech_rate: int
    uid: str


class HuoshanRealtimeTTS(tts.TTS):
    """Volcengine bidirectional streaming TTS (openspeech v3 bidirection)."""

    def __init__(
        self,
        *,
        api_key: str,
        resource_id: str = DEFAULT_TTS_RESOURCE_ID,
        ws_url: str = DEFAULT_TTS_WS_URL,
        speaker: str = DEFAULT_TTS_SPEAKER,
        model: str = DEFAULT_TTS_MODEL,
        sample_rate: int = DEFAULT_SAMPLE_RATE,
        speech_rate: int = DEFAULT_SPEECH_RATE,
        uid: str = "livekit-agent",
        http_session: aiohttp.ClientSession | None = None,
    ) -> None:
        super().__init__(
            capabilities=tts.TTSCapabilities(streaming=True, aligned_transcript=False),
            sample_rate=sample_rate,
            num_channels=DEFAULT_NUM_CHANNELS,
        )
        if not api_key:
            raise ValueError("Huoshan TTS api_key is required")
        if not speaker:
            raise ValueError("Huoshan TTS speaker is required")

        self._opts = HuoshanTTSOptions(
            api_key=api_key,
            resource_id=resource_id,
            ws_url=ws_url,
            speaker=speaker,
            model=model,
            sample_rate=sample_rate,
            speech_rate=speech_rate,
            uid=uid,
        )
        self._session = http_session
        self._owns_session = False
        self._streams: weakref.WeakSet[HuoshanSynthesizeStream] = weakref.WeakSet()
        self._pool = utils.ConnectionPool[aiohttp.ClientWebSocketResponse](
            max_session_duration=180.0,
            connect_cb=self._connect_ws,
            close_cb=self._close_ws,
            connect_timeout=10.0,
        )

    @property
    def model(self) -> str:
        return self._opts.model or self._opts.resource_id

    @property
    def provider(self) -> str:
        return "huoshan"

    def _ensure_session(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            try:
                self._session = utils.http_context.http_session()
                self._owns_session = False
            except Exception:
                self._session = aiohttp.ClientSession(trust_env=False)
                self._owns_session = True
        return self._session

    def prewarm(self) -> None:
        """Open WS, finish StartConnection, and warm the speaker before the first turn."""
        self._pool.prewarm()

    def synthesize(
        self,
        text: str,
        *,
        conn_options: APIConnectOptions = DEFAULT_API_CONNECT_OPTIONS,
    ) -> tts.ChunkedStream:
        return self._synthesize_with_stream(text, conn_options=conn_options)

    def stream(
        self,
        *,
        conn_options: APIConnectOptions = DEFAULT_API_CONNECT_OPTIONS,
    ) -> tts.SynthesizeStream:
        stream = HuoshanSynthesizeStream(tts=self, conn_options=conn_options)
        self._streams.add(stream)
        return stream

    async def aclose(self) -> None:
        await asyncio.gather(
            *(stream.aclose() for stream in list(self._streams)),
            return_exceptions=True,
        )
        self._streams.clear()
        await self._pool.aclose()
        if self._owns_session and self._session is not None and not self._session.closed:
            await self._session.close()
            self._session = None
            self._owns_session = False

    def _req_params(self, text: str | None = None) -> dict[str, Any]:
        params: dict[str, Any] = {
            "speaker": self._opts.speaker,
            "audio_params": {
                "format": "pcm",
                "sample_rate": self._opts.sample_rate,
                "speech_rate": self._opts.speech_rate,
            },
        }
        if self._opts.model:
            params["model"] = self._opts.model
        if text:
            params["text"] = text
        return params

    async def _connect_ws(self, timeout: float) -> aiohttp.ClientWebSocketResponse:
        headers = {
            "X-Api-Key": self._opts.api_key,
            "X-Api-Resource-Id": self._opts.resource_id,
            "X-Api-Connect-Id": str(uuid.uuid4()),
        }
        ws = await asyncio.wait_for(
            self._ensure_session().ws_connect(
                self._opts.ws_url,
                headers=headers,
                heartbeat=20,
                max_msg_size=10 * 1024 * 1024,
            ),
            timeout,
        )
        try:
            logid = _ws_logid(ws)
            await _send_event(
                ws, EventType.StartConnection, payload=_START_CONNECTION_PAYLOAD
            )
            await _wait_for_event(ws, EventType.ConnectionStarted, timeout=timeout)
            await self._warmup_session(ws, timeout=timeout)
            logger.debug("huoshan tts connection started logid=%s", logid)
            return ws
        except Exception:
            if not ws.closed:
                await ws.close()
            raise

    async def _warmup_session(
        self, ws: aiohttp.ClientWebSocketResponse, *, timeout: float
    ) -> None:
        """Load the speaker on the server so the first real turn skips cold start."""
        session_id = f"warmup-{uuid.uuid4()}"
        try:
            await _send_event(
                ws,
                EventType.StartSession,
                payload=self._session_payload(),
                session_id=session_id,
            )
            await _wait_for_event(ws, EventType.SessionStarted, timeout=timeout)
            warmup_text = "。"
            await _send_event(
                ws,
                EventType.TaskRequest,
                payload=json.dumps(
                    {
                        "event": int(EventType.TaskRequest),
                        "req_params": self._req_params(warmup_text),
                    },
                    ensure_ascii=False,
                ).encode(),
                session_id=session_id,
            )
            await _send_event(
                ws, EventType.FinishSession, payload=b"{}", session_id=session_id
            )
            await _wait_for_event(ws, EventType.SessionFinished, timeout=timeout)
        except Exception:
            logger.debug("huoshan tts speaker warmup failed", exc_info=True)
            raise

    def _session_payload(self) -> bytes:
        return json.dumps(
            {
                "user": {"uid": self._opts.uid},
                "event": int(EventType.StartSession),
                "namespace": "BidirectionalTTS",
                "req_params": self._req_params(),
            },
            ensure_ascii=False,
        ).encode()

    async def _close_ws(self, ws: aiohttp.ClientWebSocketResponse) -> None:
        try:
            if not ws.closed:
                await _send_event(ws, EventType.FinishConnection, payload=b"{}")
                await _wait_for_event(ws, EventType.ConnectionFinished, timeout=2.0)
        except Exception:
            logger.debug("huoshan tts finish_connection skipped", exc_info=True)
        finally:
            if not ws.closed:
                await ws.close()


class HuoshanSynthesizeStream(tts.SynthesizeStream):
    def __init__(self, *, tts: HuoshanRealtimeTTS, conn_options: APIConnectOptions) -> None:
        super().__init__(tts=tts, conn_options=conn_options)
        self._tts: HuoshanRealtimeTTS = tts
        self._opts = tts._opts
        self._tts_first_text_at: float | None = None
        self._tts_ttfb_ms: float | None = None
        self._tts_final_text: str | None = None
        self._tts_logged = False

    def _log_final(self) -> None:
        if self._tts_logged:
            return
        if not self._tts_final_text or self._tts_ttfb_ms is None:
            return
        self._tts_logged = True
        logger.info(
            "TTS 文本=%s ttfb_ms=%s",
            self._tts_final_text,
            self._tts_ttfb_ms,
        )

    @utils.log_exceptions(logger=logger)
    async def _run(self, output_emitter: tts.AudioEmitter) -> None:
        request_id = utils.shortuuid()
        output_emitter.initialize(
            request_id=request_id,
            sample_rate=self._opts.sample_rate,
            num_channels=DEFAULT_NUM_CHANNELS,
            stream=True,
            mime_type="audio/pcm",
            frame_size_ms=50,
        )

        timeout = self._conn_options.timeout
        async with self._tts._pool.connection(timeout=timeout) as ws:
            if ws.closed:
                raise APIConnectionError("Huoshan TTS websocket already closed")

            self._acquire_time = self._tts._pool.last_acquire_time
            self._connection_reused = self._tts._pool.last_connection_reused

            session_id = str(uuid.uuid4())
            session_ready = asyncio.Event()
            await _send_event(
                ws,
                EventType.StartSession,
                payload=self._tts._session_payload(),
                session_id=session_id,
            )

            send_task = asyncio.create_task(self._send_task(ws, session_id, session_ready))
            recv_task = asyncio.create_task(
                self._recv_task(ws, output_emitter, session_id, session_ready)
            )
            try:
                await _wait_first_exception(send_task, recv_task)
            except BaseException:
                if not send_task.done():
                    send_task.cancel()
                if not recv_task.done():
                    recv_task.cancel()
                try:
                    await _send_event(
                        ws, EventType.CancelSession, payload=b"{}", session_id=session_id
                    )
                except Exception:
                    pass
                raise
            finally:
                await utils.aio.gracefully_cancel(send_task, recv_task)

            if output_emitter.pushed_duration(idx=-1) > 0:
                output_emitter.end_segment()

    async def _send_task(
        self,
        ws: aiohttp.ClientWebSocketResponse,
        session_id: str,
        session_ready: asyncio.Event,
    ) -> None:
        await session_ready.wait()
        text_parts: list[str] = []
        async for data in self._input_ch:
            if not isinstance(data, str) or not data:
                continue
            if not text_parts:
                self._tts_first_text_at = time.perf_counter()
                self._mark_started()
            text_parts.append(data)
            payload = json.dumps(
                {
                    "event": int(EventType.TaskRequest),
                    "req_params": self._tts._req_params(data),
                },
                ensure_ascii=False,
            ).encode()
            await _send_event(
                ws, EventType.TaskRequest, payload=payload, session_id=session_id
            )

        self._tts_final_text = "".join(text_parts)
        if not text_parts:
            await _send_event(
                ws, EventType.CancelSession, payload=b"{}", session_id=session_id
            )
            return

        await _send_event(
            ws, EventType.FinishSession, payload=b"{}", session_id=session_id
        )
        self._log_final()

    async def _recv_task(
        self,
        ws: aiohttp.ClientWebSocketResponse,
        output_emitter: tts.AudioEmitter,
        session_id: str,
        session_ready: asyncio.Event,
    ) -> None:
        first_audio = True
        segment_started = False
        while True:
            msg = await _recv_message(ws, timeout=self._conn_options.timeout)
            if msg.event == EventType.SessionStarted:
                session_ready.set()
                if not segment_started:
                    output_emitter.start_segment(segment_id=session_id)
                    segment_started = True
                continue

            if msg.type == MsgType.AudioOnlyServer:
                if not segment_started:
                    output_emitter.start_segment(segment_id=session_id)
                    segment_started = True
                    session_ready.set()
                if msg.payload:
                    if first_audio:
                        first_audio = False
                        started_at = self._tts_first_text_at
                        if started_at is not None:
                            self._tts_ttfb_ms = round(
                                (time.perf_counter() - started_at) * 1000, 1
                            )
                        self._log_final()
                    output_emitter.push(msg.payload)
                continue

            if msg.type == MsgType.Error:
                session_ready.set()
                body = msg.payload.decode("utf-8", errors="replace")
                raise APIStatusError(
                    message=f"Huoshan TTS error: {body}",
                    status_code=msg.error_code or 500,
                    request_id=session_id,
                    body=body,
                    retryable=False,
                )

            if msg.event in (EventType.SessionFinished, EventType.SessionCanceled):
                session_ready.set()
                if not segment_started and self._num_segments > 0:
                    output_emitter.start_segment(segment_id=session_id)
                return
            if msg.event in (EventType.SessionFailed, EventType.ConnectionFailed):
                session_ready.set()
                body = msg.payload.decode("utf-8", errors="replace")
                raise APIStatusError(
                    message=f"Huoshan TTS session failed: {body}",
                    status_code=500,
                    request_id=session_id,
                    body=body,
                    retryable=True,
                )
            if msg.event in _SKIP_EVENTS:
                continue
            logger.debug("huoshan tts ignored event %s", msg.event)


def _build_event(
    event: EventType, *, payload: bytes, session_id: str = ""
) -> Message:
    msg = Message(type=MsgType.FullClientRequest, flag=MsgTypeFlagBits.WithEvent)
    msg.event = event
    msg.session_id = session_id
    msg.payload = payload
    return msg


async def _send_event(
    ws: aiohttp.ClientWebSocketResponse,
    event: EventType,
    *,
    payload: bytes,
    session_id: str = "",
) -> None:
    await ws.send_bytes(
        _build_event(event, payload=payload, session_id=session_id).marshal()
    )


async def _recv_message(
    ws: aiohttp.ClientWebSocketResponse, *, timeout: float
) -> Message:
    try:
        raw = await asyncio.wait_for(ws.receive(), timeout)
    except TimeoutError as exc:
        raise APIConnectionError("Huoshan TTS websocket receive timed out") from exc

    if raw.type in _WS_CLOSE_TYPES:
        raise APIConnectionError(
            f"Huoshan TTS websocket closed: code={ws.close_code} extra={raw.extra!r}"
        )
    if raw.type == aiohttp.WSMsgType.ERROR:
        raise APIConnectionError("Huoshan TTS websocket error") from ws.exception()
    if raw.type != aiohttp.WSMsgType.BINARY:
        raise APIStatusError(
            message=f"Huoshan TTS unexpected ws message type {raw.type}",
            status_code=-1,
            retryable=True,
        )
    return Message.from_bytes(raw.data)


async def _wait_for_event(
    ws: aiohttp.ClientWebSocketResponse,
    event: EventType,
    *,
    timeout: float,
) -> Message:
    deadline = time.perf_counter() + timeout
    while True:
        remaining = deadline - time.perf_counter()
        if remaining <= 0:
            raise APIConnectionError(f"Huoshan TTS timed out waiting for {event}")
        msg = await _recv_message(ws, timeout=remaining)
        if msg.event == event:
            return msg
        if msg.type == MsgType.AudioOnlyServer:
            continue
        if msg.type == MsgType.Error or msg.event in (
            EventType.SessionFailed,
            EventType.ConnectionFailed,
        ):
            body = msg.payload.decode("utf-8", errors="replace")
            raise APIStatusError(
                message=f"Huoshan TTS failed while waiting for {event}: {body}",
                status_code=getattr(msg, "error_code", 500) or 500,
                body=body,
                retryable=True,
            )
        if msg.event in _SKIP_EVENTS:
            continue
        logger.debug("huoshan tts skip while waiting for %s: %s", event, msg.event)


async def _wait_first_exception(*tasks: asyncio.Task[Any]) -> None:
    pending = set(tasks)
    while pending:
        done, pending = await asyncio.wait(pending, return_when=asyncio.FIRST_EXCEPTION)
        for task in done:
            if task.cancelled():
                continue
            exc = task.exception()
            if exc is not None:
                raise exc


def create_huoshan_realtime_tts(
    *,
    api_key: str,
    resource_id: str = DEFAULT_TTS_RESOURCE_ID,
    ws_url: str = DEFAULT_TTS_WS_URL,
    speaker: str = DEFAULT_TTS_SPEAKER,
    model: str = DEFAULT_TTS_MODEL,
    sample_rate: int = DEFAULT_SAMPLE_RATE,
    speech_rate: int = DEFAULT_SPEECH_RATE,
    uid: str = "livekit-agent",
) -> HuoshanRealtimeTTS:
    """Volcengine bidirectional TTS: LLM token stream → PCM audio."""
    return HuoshanRealtimeTTS(
        api_key=api_key,
        resource_id=resource_id,
        ws_url=ws_url,
        speaker=speaker,
        model=model,
        sample_rate=sample_rate,
        speech_rate=speech_rate,
        uid=uid,
    )
