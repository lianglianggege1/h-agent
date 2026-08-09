import pytest
from httpx import ASGITransport, AsyncClient

from realtime_voice.api import create_app
from realtime_voice.settings import VoiceSettings


@pytest.mark.asyncio
async def test_health_endpoint_identifies_the_service() -> None:
    app = create_app(VoiceSettings.from_env({}))

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get("/healthz")

    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "service": "realtime-voice",
        "version": "0.1.0",
    }


@pytest.mark.asyncio
async def test_frontend_config_exposes_only_public_voice_settings() -> None:
    settings = VoiceSettings.from_env(
        {
            "VOICE_AGENT_NAME": "harness-voice",
            "BACKEND_API_BASE_URL": "https://backend.internal",
        }
    )
    app = create_app(settings)

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get("/v1/config")

    assert response.status_code == 200
    assert response.json() == {"agentName": "harness-voice"}
    assert "backend" not in response.text.lower()
    assert "secret" not in response.text.lower()
