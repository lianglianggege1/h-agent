# syntax=docker/dockerfile:1

ARG PYTHON_VERSION=3.11
FROM ghcr.io/astral-sh/uv:python${PYTHON_VERSION}-bookworm-slim AS base

ENV PYTHONUNBUFFERED=1 \
    UV_COMPILE_BYTECODE=1 \
    HF_HOME=/app/.cache/huggingface \
    TORCH_HOME=/app/.cache/torch

FROM base AS build

RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    g++ \
    python3-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY pyproject.toml uv.lock ./
COPY src/realtime_voice/__init__.py src/realtime_voice/__init__.py
RUN uv sync --locked --no-dev
RUN uv run --no-sync --module livekit.agents download-files

COPY src ./src

FROM base AS runtime

ARG UID=10001
RUN adduser \
    --disabled-password \
    --gecos "" \
    --home /app \
    --shell /sbin/nologin \
    --uid "${UID}" \
    appuser

COPY --from=build --chown=appuser:appuser /app /app

WORKDIR /app
USER appuser

ENV VOICE_HEALTH_HOST=0.0.0.0 \
    VOICE_HEALTH_PORT=8091 \
    VOICE_API_HOST=0.0.0.0 \
    VOICE_API_PORT=8090

EXPOSE 8090 8091

CMD ["uv", "run", "--no-sync", "realtime-voice", "start"]
