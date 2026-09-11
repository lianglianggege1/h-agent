from __future__ import annotations

import asyncio
import gzip
import json
import logging
import struct
import time
import uuid
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Any

import aiohttp
from livekit import rtc
from livekit.agents import (
    DEFAULT_API_CONNECT_OPTIONS,
    APIConnectionError,
    APIConnectOptions,
    APIStatusError,
    stt,
    utils,
)
from livekit.agents.types import NOT_GIVEN, NotGivenOr
from livekit.agents.utils import AudioBuffer, is_given

from .gain import AsrGainConfig, RollingAsrGain, boost_pcm

logger = logging.getLogger(__name__)

DEFAULT_WS_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
DEFAULT_RESOURCE_ID = "volc.seedasr.sauc.duration"
DEFAULT_SAMPLE_RATE = 16000
# 双向流式 200ms 分包性能最优 (16000 * 0.2 = 3200 samples)
DEFAULT_SAMPLES_PER_CHUNK = 3200


def _ttfb_ms_from_utterance_start(
    *,
    now: float,
    last_audio_sent_at: float,
    audio_ms_sent: float,
    start_time_ms: float,
) -> float:
    """从本句音频起点推算首包延迟，而不是从上一句之后的静音包开始计时。"""
    audio_age_ms = max(audio_ms_sent - start_time_ms, 0.0)
    spoken_at = last_audio_sent_at - audio_age_ms / 1000.0
    return round(max((now - spoken_at) * 1000.0, 0.0), 1)


def _generate_header(
    message_type: int,
    flags: int,
    serialization: int,
    compression: int,
) -> bytes:
    version = 1
    header_size = 1
    b0 = (version << 4) | header_size
    b1 = (message_type << 4) | flags
    b2 = (serialization << 4) | compression
    b3 = 0
    return bytes([b0, b1, b2, b3])


def _pack_frame(
    message_type: int,
    flags: int,
    serialization: int,
    compression: int,
    payload: bytes,
) -> bytes:
    compressed = gzip.compress(payload) if compression == 1 else payload
    header = _generate_header(message_type, flags, serialization, compression)
    return header + struct.pack(">I", len(compressed)) + compressed


def _parse_server_frame(data: bytes) -> tuple[int, int, int, int, bytes]:
    """解析服务端返回的二进制帧，精准支持 Type 15 错误帧"""
    if len(data) < 4:
        raise ValueError("Invalid frame: header less than 4 bytes")

    h_b1, h_b2 = data[1], data[2]
    msg_type = (h_b1 & 0xF0) >> 4
    flags = h_b1 & 0x0F
    serialization = (h_b2 & 0xF0) >> 4
    compression = h_b2 & 0x0F

    # 错误消息帧 (Type 15) 格式：Header(4B) + ErrorCode(4B) + ErrorMsgSize(4B) + ErrorMsg
    if msg_type == 15:
        if len(data) < 12:
            raise APIStatusError(message="Huoshan ASR server error: truncated error frame", status_code=500)
        err_code = struct.unpack(">I", data[4:8])[0]
        err_size = struct.unpack(">I", data[8:12])[0]
        err_msg = data[12 : 12 + err_size].decode("utf-8", errors="replace")
        raise APIStatusError(
            message=f"Huoshan ASR server error: {err_msg}",
            status_code=err_code,
            body=err_msg,
        )

    offset = 4
    if flags in (1, 3):  # 包含 Sequence (4 字节)
        offset += 4

    if len(data) < offset + 4:
        return msg_type, flags, serialization, compression, b""

    payload_size = struct.unpack(">I", data[offset : offset + 4])[0]
    offset += 4
    payload = data[offset : offset + payload_size]
    if compression == 1 and payload:
        payload = gzip.decompress(payload)

    return msg_type, flags, serialization, compression, payload


def load_hotwords(path: str | Path | None) -> list[dict[str, str]]:
    if not path:
        return []
    words: list[dict[str, str]] = []
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                word = line.strip()
                if word:
                    words.append({"word": word})
    except OSError as exc:
        logger.warning("failed to load hotwords from %s: %s", path, exc)
    return words


def _ws_logid(ws: aiohttp.ClientWebSocketResponse) -> str:
    """获取火山引擎返回的 X-Tt-Logid。

    不同 aiohttp 版本的 ClientWebSocketResponse 属性命名不同：
    新版暴露公开的 response，旧版只有私有属性 _response。
    """
    response = getattr(ws, "response", None) or getattr(ws, "_response", None)
    if response is None:
        return ""
    return response.headers.get("X-Tt-Logid", "")


@dataclass
class HuoshanSTTOptions:
    api_key: str
    resource_id: str
    ws_url: str
    language: str
    sample_rate: int
    uid: str
    model_name: str
    enable_nonstream: bool
    show_utterances: bool
    result_type: str
    end_window_size: int
    enable_ddc: bool
    enable_itn: bool
    enable_punc: bool
    hotwords: list[dict[str, str]]
    samples_per_chunk: int = DEFAULT_SAMPLES_PER_CHUNK
    gain: AsrGainConfig = field(default_factory=AsrGainConfig)


class HuoshanSTT(stt.STT):
    """火山引擎 / 豆包大模型流式语音识别 (SAUC bigmodel_async)"""

    def __init__(
        self,
        *,
        api_key: str,
        resource_id: str = DEFAULT_RESOURCE_ID,
        ws_url: str = DEFAULT_WS_URL,
        language: str = "zh-CN",
        sample_rate: int = DEFAULT_SAMPLE_RATE,
        uid: str = "livekit-agent",
        model_name: str = "bigmodel",
        enable_nonstream: bool = True,
        show_utterances: bool = True,
        result_type: str = "full",
        end_window_size: int = 550,
        enable_ddc: bool = False,
        enable_itn: bool = True,
        enable_punc: bool = True,
        samples_per_chunk: int = DEFAULT_SAMPLES_PER_CHUNK,
        hotwords_file: str | Path | None = None,
        hotwords: list[dict[str, str]] | None = None,
        gain: AsrGainConfig | None = None,
        http_session: aiohttp.ClientSession | None = None,
    ) -> None:
        super().__init__(
            capabilities=stt.STTCapabilities(
                streaming=True,
                interim_results=True,
                offline_recognize=True,
            )
        )
        if not api_key:
            raise ValueError("Huoshan ASR api_key is required")

        resolved_hotwords = list(hotwords or [])
        if not resolved_hotwords and hotwords_file:
            resolved_hotwords = load_hotwords(hotwords_file)

        self._opts = HuoshanSTTOptions(
            api_key=api_key,
            resource_id=resource_id,
            ws_url=ws_url,
            language=language,
            sample_rate=sample_rate,
            uid=uid,
            model_name=model_name,
            enable_nonstream=enable_nonstream,
            show_utterances=show_utterances,
            result_type=result_type,
            end_window_size=end_window_size,
            enable_ddc=enable_ddc,
            enable_itn=enable_itn,
            enable_punc=enable_punc,
            samples_per_chunk=samples_per_chunk,
            hotwords=resolved_hotwords,
            gain=gain or AsrGainConfig(),
        )
        self._session = http_session
        self._owns_session = False

    @property
    def model(self) -> str:
        return self._opts.model_name

    @property
    def provider(self) -> str:
        return "huoshan"

    def _ensure_session(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession(trust_env=False)
            self._owns_session = True
        return self._session

    def stream(
        self,
        *,
        language: NotGivenOr[str] = NOT_GIVEN,
        conn_options: APIConnectOptions = DEFAULT_API_CONNECT_OPTIONS,
    ) -> SpeechStream:
        opts = self._opts
        if is_given(language):
            opts = replace(opts, language=language)
        return SpeechStream(
            stt=self,
            opts=opts,
            conn_options=conn_options,
            http_session=self._ensure_session(),
        )

    async def _recognize_impl(
        self,
        buffer: AudioBuffer,
        *,
        language: NotGivenOr[str] = NOT_GIVEN,
        conn_options: APIConnectOptions,
    ) -> stt.SpeechEvent:
        opts = self._opts
        if is_given(language):
            opts = replace(opts, language=language)

        combined = utils.combine_frames(buffer)
        pcm, gain_db = boost_pcm(combined.data.tobytes(), opts.gain)
        if gain_db > 0:
            logger.info("ASR gain applied db=%.1f (offline recognize)", gain_db)
        request_id = str(uuid.uuid4())
        session = self._ensure_session()

        try:
            async with session.ws_connect(
                opts.ws_url,
                headers=_auth_headers(opts, request_id),
                heartbeat=20,
                timeout=aiohttp.ClientWSTimeout(ws_close=conn_options.timeout),
            ) as ws:
                logid = _ws_logid(ws)
                logger.debug("Huoshan ASR connected, logid=%s", logid)

                await ws.send_bytes(_build_full_client_request(opts))

                chunk_bytes = opts.samples_per_chunk * 2
                for start in range(0, len(pcm), chunk_bytes):
                    await ws.send_bytes(_build_audio_request(pcm[start : start + chunk_bytes]))
                # 最后一包标记负包
                await ws.send_bytes(_build_audio_request(b"", last=True))

                final_text = ""
                async for msg in ws:
                    if msg.type == aiohttp.WSMsgType.BINARY:
                        msg_type, flags, _, _, payload = _parse_server_frame(msg.data)
                        is_last = flags in (2, 3)
                        if msg_type == 9 and payload:
                            body = json.loads(payload.decode("utf-8"))
                            full_text = (body.get("result", {}).get("text") or "").strip()
                            if full_text:
                                final_text = full_text
                        # 只有收到最后一包才终止
                        if is_last:
                            break
                    elif msg.type in (
                        aiohttp.WSMsgType.CLOSED,
                        aiohttp.WSMsgType.CLOSE,
                        aiohttp.WSMsgType.ERROR,
                    ):
                        break
        except (TimeoutError, aiohttp.ClientError) as exc:
            raise APIConnectionError(f"Huoshan ASR recognize failed: {exc}") from exc

        return stt.SpeechEvent(
            type=stt.SpeechEventType.FINAL_TRANSCRIPT,
            request_id=request_id,
            alternatives=[
                stt.SpeechData(language=opts.language, text=final_text, confidence=1.0)
            ],
        )

    async def aclose(self) -> None:
        if self._owns_session and self._session is not None and not self._session.closed:
            await self._session.close()
            self._session = None
            self._owns_session = False


class SpeechStream(stt.SpeechStream):
    def __init__(
        self,
        *,
        stt: HuoshanSTT,
        opts: HuoshanSTTOptions,
        conn_options: APIConnectOptions,
        http_session: aiohttp.ClientSession,
    ) -> None:
        super().__init__(stt=stt, conn_options=conn_options, sample_rate=opts.sample_rate)
        self._opts = opts
        self._session = http_session
        self._request_id = ""
        self._speaking = False
        self._finalized_count = 0  # 追踪已输出的 definite utterance 数量，避免文本去重失效
        self._last_audio_sent_at: float | None = None
        self._audio_ms_sent = 0.0
        self._ttfb_ms: float | None = None

    async def _run(self) -> None:
        closing_ws = False
        self._request_id = str(uuid.uuid4())

        @utils.log_exceptions(logger=logger)
        async def send_task(ws: aiohttp.ClientWebSocketResponse) -> None:
            nonlocal closing_ws
            await ws.send_bytes(_build_full_client_request(self._opts))

            audio_bstream = utils.audio.AudioByteStream(
                sample_rate=self._opts.sample_rate,
                num_channels=1,
                samples_per_channel=self._opts.samples_per_chunk,
            )
            gainer = RollingAsrGain(
                self._opts.gain, sample_rate=self._opts.sample_rate
            )

            async for data in self._input_ch:
                frames: list[rtc.AudioFrame] = []
                if isinstance(data, rtc.AudioFrame):
                    frames.extend(audio_bstream.write(data.data.tobytes()))
                elif isinstance(data, self._FlushSentinel):
                    frames.extend(audio_bstream.flush())

                for frame in frames:
                    pcm, gain_db = gainer.process(frame.data.tobytes())
                    if gain_db > 0:
                        logger.debug("ASR gain applied db=%.1f", gain_db)
                    self._mark_audio_sent(frame.duration)
                    await ws.send_bytes(_build_audio_request(pcm))

            closing_ws = True
            leftover = audio_bstream.flush()
            for frame in leftover:
                pcm, gain_db = gainer.process(frame.data.tobytes())
                if gain_db > 0:
                    logger.debug("ASR gain applied db=%.1f", gain_db)
                self._mark_audio_sent(frame.duration)
                await ws.send_bytes(_build_audio_request(pcm))
            await ws.send_bytes(_build_audio_request(b"", last=True))

        @utils.log_exceptions(logger=logger)
        async def recv_task(ws: aiohttp.ClientWebSocketResponse) -> None:
            nonlocal closing_ws
            while True:
                msg = await ws.receive()
                if msg.type in (
                    aiohttp.WSMsgType.CLOSED,
                    aiohttp.WSMsgType.CLOSE,
                    aiohttp.WSMsgType.CLOSING,
                ):
                    if closing_ws or self._session.closed:
                        return
                    raise APIStatusError(
                        message="Huoshan ASR connection closed unexpectedly",
                        status_code=ws.close_code or -1,
                        body=str(msg.data),
                    )

                if msg.type != aiohttp.WSMsgType.BINARY:
                    continue

                try:
                    is_last = self._process_frame(msg.data)
                except Exception:
                    logger.exception("failed to process Huoshan ASR frame")
                    continue

                if is_last:
                    return

        try:
            async with self._session.ws_connect(
                self._opts.ws_url,
                headers=_auth_headers(self._opts, self._request_id),
                heartbeat=20,
            ) as ws:
                logid = _ws_logid(ws)
                logger.info("Huoshan ASR stream connected, logid=%s", logid)

                tasks = [
                    asyncio.create_task(send_task(ws)),
                    asyncio.create_task(recv_task(ws)),
                ]
                try:
                    await asyncio.gather(*tasks)
                finally:
                    await utils.aio.gracefully_cancel(*tasks)
        except (TimeoutError, aiohttp.ClientError) as exc:
            raise APIConnectionError(f"Huoshan ASR stream failed: {exc}") from exc

    def _mark_audio_sent(self, duration_s: float) -> None:
        self._last_audio_sent_at = time.perf_counter()
        self._audio_ms_sent += duration_s * 1000.0

    def _maybe_set_ttfb(
        self,
        utterances: list[dict[str, Any]],
        full_text: str,
        *,
        is_last: bool,
    ) -> None:
        if self._ttfb_ms is not None:
            return
        if not (
            self._has_in_progress_utterance(utterances, full_text, is_last=is_last)
            or self._has_unfinalized_definite(utterances)
        ):
            return
        if self._finalized_count >= len(utterances) or self._last_audio_sent_at is None:
            return
        start_time = utterances[self._finalized_count].get("start_time")
        if not isinstance(start_time, (int, float)):
            return
        self._ttfb_ms = _ttfb_ms_from_utterance_start(
            now=time.perf_counter(),
            last_audio_sent_at=self._last_audio_sent_at,
            audio_ms_sent=self._audio_ms_sent,
            start_time_ms=float(start_time),
        )

    def _emit_start_of_speech(self) -> None:
        self._speaking = True
        self._event_ch.send_nowait(
            stt.SpeechEvent(
                type=stt.SpeechEventType.START_OF_SPEECH,
                request_id=self._request_id,
            )
        )

    def _emit_end_of_speech(self) -> None:
        self._speaking = False
        self._event_ch.send_nowait(
            stt.SpeechEvent(
                type=stt.SpeechEventType.END_OF_SPEECH,
                request_id=self._request_id,
            )
        )

    def _has_in_progress_utterance(
        self,
        utterances: list[dict[str, Any]],
        full_text: str,
        *,
        is_last: bool,
    ) -> bool:
        if self._finalized_count < len(utterances):
            utt = utterances[self._finalized_count]
            return not utt.get("definite") and bool((utt.get("text") or "").strip())
        return bool(full_text) and not utterances and not is_last

    def _has_unfinalized_definite(self, utterances: list[dict[str, Any]]) -> bool:
        if self._finalized_count >= len(utterances):
            return False
        return bool(utterances[self._finalized_count].get("definite"))

    def _process_frame(self, data: bytes) -> bool:
        """处理服务端下发数据包，基于分句列表进行准确的状态机与分发控制"""
        msg_type, flags, _, _, payload = _parse_server_frame(data)
        is_last = flags in (2, 3)

        if msg_type != 9 or not payload:
            return is_last

        body = json.loads(payload.decode("utf-8"))
        result = body.get("result") or {}
        utterances = result.get("utterances") or []
        full_text = (result.get("text") or "").strip()

        # 1. 新的进行中/已完成分句才触发 START_OF_SPEECH。
        # 不能仅凭 result.text 判断：分句结束后服务端仍会回带历史文本，
        # 否则 END_OF_SPEECH 后会立刻再次 START，LiveKit STT 切轮无法提交。
        if not self._speaking and (
            self._has_in_progress_utterance(utterances, full_text, is_last=is_last)
            or self._has_unfinalized_definite(utterances)
        ):
            self._emit_start_of_speech()

        self._maybe_set_ttfb(utterances, full_text, is_last=is_last)

        # 2. 依次输出所有新判定的 definite 分句（FINAL_TRANSCRIPT）
        while self._finalized_count < len(utterances):
            utt = utterances[self._finalized_count]
            if not utt.get("definite") and not is_last:
                break
            utt_text = (utt.get("text") or "").strip()
            if utt_text:
                self._event_ch.send_nowait(
                    stt.SpeechEvent(
                        type=stt.SpeechEventType.FINAL_TRANSCRIPT,
                        request_id=self._request_id,
                        alternatives=[
                            stt.SpeechData(
                                language=self._opts.language,
                                text=utt_text,
                                confidence=1.0,
                            )
                        ],
                    )
                )
                logger.debug("ASR final result received")
            self._finalized_count += 1
            self._ttfb_ms = None

        # 3. 输出当前正在识别中的临时分句（INTERIM_TRANSCRIPT）
        if self._finalized_count < len(utterances):
            interim_utt = utterances[self._finalized_count]
            interim_text = (interim_utt.get("text") or "").strip()
            if interim_text:
                self._event_ch.send_nowait(
                    stt.SpeechEvent(
                        type=stt.SpeechEventType.INTERIM_TRANSCRIPT,
                        request_id=self._request_id,
                        alternatives=[
                            stt.SpeechData(
                                language=self._opts.language,
                                text=interim_text,
                                confidence=1.0,
                            )
                        ],
                    )
                )
        elif not utterances and full_text and not is_last:
            # 未开启 show_utterances 时的 fallback 处理
            self._event_ch.send_nowait(
                stt.SpeechEvent(
                    type=stt.SpeechEventType.INTERIM_TRANSCRIPT,
                    request_id=self._request_id,
                    alternatives=[
                        stt.SpeechData(
                            language=self._opts.language,
                            text=full_text,
                            confidence=1.0,
                        )
                    ],
                )
            )

        # 4. 分句完成（definite）或流结束时触发 END_OF_SPEECH。
        # LiveKit turn_detection="stt" 依赖该事件提交用户轮次；
        # 不能等到整段 WebSocket 关闭，否则关 VAD 后 STT 永远切不出轮。
        all_utterances_final = bool(utterances) and self._finalized_count >= len(utterances)
        if self._speaking and (is_last or all_utterances_final):
            self._emit_end_of_speech()

        return is_last


def _auth_headers(opts: HuoshanSTTOptions, request_id: str) -> dict[str, str]:
    return {
        "X-Api-Key": opts.api_key,
        "X-Api-Resource-Id": opts.resource_id,
        "X-Api-Request-Id": request_id,
        "X-Api-Connect-Id": str(uuid.uuid4()),  # 便于排查连接问题的 Connect-Id
        "X-Api-Sequence": "-1",
    }


def _build_full_client_request(opts: HuoshanSTTOptions) -> bytes:
    request_body: dict[str, Any] = {
        "model_name": opts.model_name,
        "enable_nonstream": opts.enable_nonstream,
        "result_type": opts.result_type,
        "show_utterances": opts.show_utterances,
        "end_window_size": opts.end_window_size,
        "enable_ddc": opts.enable_ddc,
        "enable_itn": opts.enable_itn,
        "enable_punc": opts.enable_punc,
    }
    if opts.hotwords:
        request_body["corpus"] = {
            "context": json.dumps({"hotwords": opts.hotwords}, ensure_ascii=False)
        }

    payload = {
        "user": {"uid": opts.uid},
        "audio": {
            "format": "pcm",
            "rate": opts.sample_rate,
            "bits": 16,
            "channel": 1,
            "language": opts.language,
        },
        "request": request_body,
    }
    return _pack_frame(1, 0, 1, 1, json.dumps(payload).encode("utf-8"))


def _build_audio_request(pcm: bytes, *, last: bool = False) -> bytes:
    flags = 2 if last else 0
    return _pack_frame(2, flags, 0, 1, pcm)
