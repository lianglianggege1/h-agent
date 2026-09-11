import pytest

from realtime_voice.settings import Settings


def test_settings_requires_explicit_authorized_voice(monkeypatch):
    values = {
        "JAVA_VOICE_URL": "http://java:8081",
        "VOICE_WORKER_TOKEN": "w" * 32,
        "LIVEKIT_URL": "wss://livekit.home.arpa",
        "LIVEKIT_API_KEY": "key",
        "LIVEKIT_API_SECRET": "s" * 32,
        "HUOSHAN_ASR_API_KEY": "asr",
        "HUOSHAN_TTS_API_KEY": "tts",
        "HUOSHAN_TTS_RESOURCE_ID": "resource",
        "HUOSHAN_TTS_MODEL": "model",
    }
    for key, value in values.items():
        monkeypatch.setenv(key, value)
    monkeypatch.delenv("HUOSHAN_TTS_SPEAKER", raising=False)
    with pytest.raises(ValueError, match="HUOSHAN_TTS_SPEAKER"):
        Settings.load()
