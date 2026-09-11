# H-Agent realtime voice worker

This service owns realtime media only: LiveKit room I/O, VAD, Huoshan ASR and Huoshan streaming TTS. Java owns the Agent invocation, persistent messages, cancellation state and conversation context.

## Run

Copy `.env.example` to the ignored `.env`, fill the authorized credentials, then:

```bash
uv sync
uv run voice-worker download-files
uv run voice-worker dev
```

The worker claims each dispatch with the one-time secret in its metadata. Its Java bearer token is separate from LiveKit credentials and browser room tokens. Run one Java backend instance for v1; restart recovery intentionally fails unfinished calls rather than replaying a model request.

ASR final text becomes a user message only after LiveKit closes a user turn. Assistant text becomes a chat message only after playout settles. On interruption, Java stores the estimated played prefix with an interruption marker; generated but unplayed text is never inserted into shared context.
