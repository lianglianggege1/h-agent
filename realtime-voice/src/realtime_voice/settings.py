import os
from dataclasses import dataclass
from urllib.parse import urlparse

DEFAULT_GREETING = "您好，我是您的语音助手，请问有什么可以帮您？"


@dataclass(frozen=True)
class Settings:
    java_url: str
    worker_token: str
    asr_key: str
    asr_resource: str
    asr_url: str
    tts_key: str
    tts_speaker: str
    tts_resource: str
    tts_model: str
    tts_url: str
    greeting: str
    # FreeSWITCH / Phone worker
    outbound_internal_token: str
    fs_esl_host: str
    fs_esl_port: int
    fs_esl_password: str
    fs_sip_domain: str
    phone_worker_port: int

    @classmethod
    def load(cls, *, require_livekit: bool = True):
        def required(name):
            value = os.environ.get(name, "").strip()
            if not value:
                raise ValueError(f"{name} is required")
            return value
        token_name = "VOICE_WORKER_TOKEN" if require_livekit else "OUTBOUND_INTERNAL_TOKEN"
        token = required(token_name)
        if len(token) < 32:
            raise ValueError(f"{token_name} must contain at least 32 characters")
        url = required("JAVA_VOICE_URL")
        if urlparse(url).scheme not in ("http", "https"):
            raise ValueError("JAVA_VOICE_URL must be HTTP(S)")
        if require_livekit:
            for name in ("LIVEKIT_URL", "LIVEKIT_API_KEY", "LIVEKIT_API_SECRET"):
                required(name)
        return cls(
            url, token, required("HUOSHAN_ASR_API_KEY"),
            os.environ.get("HUOSHAN_ASR_RESOURCE_ID", "volc.bigasr.sauc.duration").strip(),
            os.environ.get("HUOSHAN_ASR_WS_URL", "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async").strip(),
            required("HUOSHAN_TTS_API_KEY"), required("HUOSHAN_TTS_SPEAKER"),
            required("HUOSHAN_TTS_RESOURCE_ID"), required("HUOSHAN_TTS_MODEL"),
            os.environ.get("HUOSHAN_TTS_WS_URL", "wss://openspeech.bytedance.com/api/v3/tts/bidirection").strip(),
            greeting=os.environ.get("VOICE_GREETING", DEFAULT_GREETING).strip(),
            outbound_internal_token=os.environ.get("OUTBOUND_INTERNAL_TOKEN", "").strip(),
            fs_esl_host=os.environ.get("FS_ESL_HOST", "127.0.0.1").strip(),
            fs_esl_port=int(os.environ.get("FS_ESL_PORT", "8021").strip()),
            fs_esl_password=os.environ.get("FS_ESL_PASSWORD", "ClueCon").strip(),
            fs_sip_domain=os.environ.get("FS_SIP_DOMAIN", "127.0.0.1").strip(),
            phone_worker_port=int(os.environ.get("PHONE_WORKER_PORT", "8082").strip()),
        )
