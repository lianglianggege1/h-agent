from __future__ import annotations

import uvicorn
from fastapi import FastAPI
from pydantic import BaseModel, Field

from realtime_voice import __version__
from realtime_voice.settings import VoiceSettings


class HealthResponse(BaseModel):
    status: str
    service: str
    version: str


class ClientConfigResponse(BaseModel):
    agent_name: str = Field(serialization_alias="agentName")


def create_app(settings: VoiceSettings | None = None) -> FastAPI:
    runtime_settings = settings or VoiceSettings.from_env()
    app = FastAPI(
        title="h-agent Realtime Voice API",
        version=__version__,
        description="HTTP integration API for the h-agent realtime voice service.",
    )

    @app.get("/healthz", response_model=HealthResponse, tags=["operations"])
    async def health() -> HealthResponse:
        return HealthResponse(status="ok", service="realtime-voice", version=__version__)

    @app.get("/v1/config", response_model=ClientConfigResponse, tags=["frontend"])
    async def client_config() -> ClientConfigResponse:
        return ClientConfigResponse(agent_name=runtime_settings.agent_name)

    return app


app = create_app()


def main() -> None:
    settings = VoiceSettings.from_env()
    uvicorn.run(
        "realtime_voice.api:app",
        host=settings.api_host,
        port=settings.api_port,
        proxy_headers=True,
    )


if __name__ == "__main__":
    main()
