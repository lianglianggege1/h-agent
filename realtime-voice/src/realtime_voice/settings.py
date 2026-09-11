import os
from dataclasses import dataclass
from urllib.parse import urlparse


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

    @classmethod
    def load(cls):
        def required(name):
            value = os.environ.get(name, "").strip()
            if not value:
                raise ValueError(f"{name} is required")
            return value
        token = required("VOICE_WORKER_TOKEN")
        if len(token) < 32:
            raise ValueError("VOICE_WORKER_TOKEN must contain at least 32 characters")
        url = required("JAVA_VOICE_URL")
        if urlparse(url).scheme not in ("http", "https"):
            raise ValueError("JAVA_VOICE_URL must be HTTP(S)")
        for name in ("LIVEKIT_URL", "LIVEKIT_API_KEY", "LIVEKIT_API_SECRET"):
            required(name)
        return cls(
            url, token, required("HUOSHAN_ASR_API_KEY"),
            os.environ.get("HUOSHAN_ASR_RESOURCE_ID", "volc.seedasr.sauc.duration").strip(),
            os.environ.get("HUOSHAN_ASR_WS_URL", "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async").strip(),
            required("HUOSHAN_TTS_API_KEY"), required("HUOSHAN_TTS_SPEAKER"),
            required("HUOSHAN_TTS_RESOURCE_ID"), required("HUOSHAN_TTS_MODEL"),
            os.environ.get("HUOSHAN_TTS_WS_URL", "wss://openspeech.bytedance.com/api/v3/tts/bidirection").strip(),
        )
