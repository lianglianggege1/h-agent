from __future__ import annotations

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from realtime_voice.graph import build_voice_graph
from realtime_voice.prompts import VOICE_ASSISTANT_INSTRUCTIONS


class RecordingModel:
    def __init__(self) -> None:
        self.messages: list[object] = []

    def invoke(self, messages: list[object]) -> AIMessage:
        self.messages = messages
        return AIMessage(content="你好，我在。")


def test_voice_graph_adds_voice_instructions_and_returns_model_reply() -> None:
    model = RecordingModel()
    graph = build_voice_graph(model)

    result = graph.invoke({"messages": [HumanMessage(content="你好")]})

    assert result["messages"][-1].content == "你好，我在。"
    assert isinstance(model.messages[0], SystemMessage)
    assert model.messages[0].content == VOICE_ASSISTANT_INSTRUCTIONS
    assert model.messages[1].content == "你好"
