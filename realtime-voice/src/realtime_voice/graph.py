from __future__ import annotations

from typing import Annotated, Protocol

from langchain_core.messages import BaseMessage, SystemMessage
from langgraph.graph import END, START, StateGraph, add_messages
from typing_extensions import TypedDict

from realtime_voice.prompts import VOICE_ASSISTANT_INSTRUCTIONS


class ChatModel(Protocol):
    def invoke(self, messages: list[BaseMessage]) -> BaseMessage: ...


class VoiceAgentState(TypedDict):
    messages: Annotated[list[BaseMessage], add_messages]


def build_voice_graph(model: ChatModel):
    """Compile the low-latency graph used inside each LiveKit voice job."""

    def call_model(state: VoiceAgentState) -> dict[str, list[BaseMessage]]:
        messages = [SystemMessage(content=VOICE_ASSISTANT_INSTRUCTIONS), *state["messages"]]
        return {"messages": [model.invoke(messages)]}

    builder = StateGraph(VoiceAgentState)
    builder.add_node("assistant", call_model)
    builder.add_edge(START, "assistant")
    builder.add_edge("assistant", END)
    return builder.compile()
