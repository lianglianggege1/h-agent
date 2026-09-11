import json

import httpx
import pytest

from realtime_voice.control import Control


@pytest.mark.asyncio
async def test_control_retries_mutation_with_same_body():
    bodies = []

    async def handler(request):
        bodies.append(json.loads(request.content))
        if len(bodies) == 1:
            raise httpx.ConnectError("lost", request=request)
        return httpx.Response(200, json={"turnId": bodies[-1]["turnId"]})

    control = Control("http://java", "x" * 32, "call")
    await control.client.aclose()
    control.client = httpx.AsyncClient(
        base_url="http://java",
        headers=control.client.headers,
        transport=httpx.MockTransport(handler),
    )
    result = await control.request("POST", "/turns", {"turnId": "stable", "text": "hi"})
    assert result["turnId"] == "stable"
    assert bodies == [{"turnId": "stable", "text": "hi"}] * 2
    await control.close()


@pytest.mark.asyncio
async def test_event_stream_requires_terminal_event():
    async def handler(_request):
        return httpx.Response(
            200,
            headers={"content-type": "text/event-stream"},
            content='data: {"type":"text_delta","text":"hi"}\n\n',
        )

    control = Control("http://java", "x" * 32, "call")
    await control.client.aclose()
    control.client = httpx.AsyncClient(
        base_url="http://java",
        headers=control.client.headers,
        transport=httpx.MockTransport(handler),
    )
    with pytest.raises(RuntimeError, match="without terminal"):
        _ = [event async for event in control.events("/events")]
    await control.close()
