from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass
from urllib.parse import urlparse


@dataclass(frozen=True, slots=True)
class VoiceSettings:
    """Runtime settings for the realtime voice worker."""

    agent_name: str
    backend_api_base_url: str
    api_host: str
    api_port: int
    health_host: str
    health_port: int
    llm_model: str
    stt_model: str
    tts_model: str
    tts_voice: str

    @classmethod
    def from_env(cls, environment: Mapping[str, str] | None = None) -> VoiceSettings:
        env = os.environ if environment is None else environment
        backend_url = env.get("BACKEND_API_BASE_URL", "http://127.0.0.1:8081").rstrip("/")
        parsed_backend_url = urlparse(backend_url)
        if parsed_backend_url.scheme not in {"http", "https"} or not parsed_backend_url.netloc:
            raise ValueError("BACKEND_API_BASE_URL must be an absolute HTTP(S) URL")

        api_port = _parse_port(env.get("VOICE_API_PORT", "8090"), "VOICE_API_PORT")
        health_port = _parse_port(env.get("VOICE_HEALTH_PORT", "8091"), "VOICE_HEALTH_PORT")

        return cls(
            agent_name=env.get("VOICE_AGENT_NAME", "h-agent-realtime-voice"),
            backend_api_base_url=backend_url,
            api_host=env.get("VOICE_API_HOST", "127.0.0.1"),
            api_port=api_port,
            health_host=env.get("VOICE_HEALTH_HOST", "127.0.0.1"),
            health_port=health_port,
            llm_model=env.get("VOICE_LLM_MODEL", "openai:gpt-4.1-mini"),
            stt_model=env.get("VOICE_STT_MODEL", "deepgram/nova-3"),
            tts_model=env.get("VOICE_TTS_MODEL", "cartesia/sonic-3"),
            tts_voice=env.get(
                "VOICE_TTS_VOICE",
                "9626c31c-bec5-4cca-baa8-f8ba9e84c8bc",
            ),
        )


def _parse_port(raw_port: str, variable_name: str) -> int:
    try:
        port = int(raw_port)
    except ValueError as error:
        raise ValueError(f"{variable_name} must be an integer") from error
    if not 1 <= port <= 65535:
        raise ValueError(f"{variable_name} must be between 1 and 65535")
    return port
