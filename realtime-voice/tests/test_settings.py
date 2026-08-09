from __future__ import annotations

import pytest

from realtime_voice.settings import VoiceSettings


def test_settings_have_local_development_defaults() -> None:
    settings = VoiceSettings.from_env({})

    assert settings.agent_name == "h-agent-realtime-voice"
    assert settings.backend_api_base_url == "http://127.0.0.1:8081"
    assert settings.api_host == "127.0.0.1"
    assert settings.api_port == 8090
    assert settings.health_host == "127.0.0.1"
    assert settings.health_port == 8091
    assert settings.llm_model == "openai:gpt-4.1-mini"
    assert settings.stt_model == "deepgram/nova-3"
    assert settings.tts_model == "cartesia/sonic-3"


def test_settings_accept_runtime_overrides() -> None:
    settings = VoiceSettings.from_env(
        {
            "VOICE_AGENT_NAME": "harness-voice",
            "BACKEND_API_BASE_URL": "https://backend.example.com/",
            "VOICE_API_HOST": "0.0.0.0",
            "VOICE_API_PORT": "9000",
            "VOICE_HEALTH_HOST": "0.0.0.0",
            "VOICE_HEALTH_PORT": "9010",
            "VOICE_LLM_MODEL": "openai:gpt-4.1",
        }
    )

    assert settings.agent_name == "harness-voice"
    assert settings.backend_api_base_url == "https://backend.example.com"
    assert settings.api_host == "0.0.0.0"
    assert settings.api_port == 9000
    assert settings.health_host == "0.0.0.0"
    assert settings.health_port == 9010
    assert settings.llm_model == "openai:gpt-4.1"


@pytest.mark.parametrize(
    ("environment", "message"),
    [
        ({"BACKEND_API_BASE_URL": "backend:8081"}, "BACKEND_API_BASE_URL"),
        ({"VOICE_API_PORT": "0"}, "VOICE_API_PORT"),
        ({"VOICE_HEALTH_PORT": "70000"}, "VOICE_HEALTH_PORT"),
    ],
)
def test_settings_reject_invalid_network_configuration(
    environment: dict[str, str], message: str
) -> None:
    with pytest.raises(ValueError, match=message):
        VoiceSettings.from_env(environment)
