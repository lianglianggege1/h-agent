from __future__ import annotations

import logging

from dotenv import load_dotenv
from langchain.chat_models import init_chat_model
from livekit.agents import Agent, AgentServer, AgentSession, JobContext, JobProcess, cli, inference
from livekit.plugins import langchain, silero

from realtime_voice.graph import build_voice_graph
from realtime_voice.prompts import VOICE_ASSISTANT_INSTRUCTIONS
from realtime_voice.settings import VoiceSettings

logger = logging.getLogger("realtime_voice")

load_dotenv(".env.local")
settings = VoiceSettings.from_env()

server = AgentServer(host=settings.health_host, port=settings.health_port)


def prewarm(proc: JobProcess) -> None:
    """Load models and compile the graph before the process accepts a voice job."""

    proc.userdata["vad"] = silero.VAD.load()
    chat_model = init_chat_model(settings.llm_model, temperature=0.3)
    proc.userdata["voice_graph"] = build_voice_graph(chat_model)


server.setup_fnc = prewarm


@server.rtc_session(agent_name=settings.agent_name)
async def realtime_voice_session(ctx: JobContext) -> None:
    ctx.log_context_fields = {"room": ctx.room.name, "agent": settings.agent_name}

    session = AgentSession(
        stt=inference.STT(model=settings.stt_model, language="multi"),
        llm=langchain.LLMAdapter(
            graph=ctx.proc.userdata["voice_graph"],
            stream_mode="messages",
        ),
        tts=inference.TTS(model=settings.tts_model, voice=settings.tts_voice),
        vad=ctx.proc.userdata["vad"],
        preemptive_generation=True,
    )

    await session.start(
        agent=Agent(instructions=VOICE_ASSISTANT_INSTRUCTIONS),
        room=ctx.room,
    )
    await ctx.connect()
    logger.info("realtime voice session connected")


def main() -> None:
    cli.run_app(server)


if __name__ == "__main__":
    main()
