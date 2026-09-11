"""Only Java commits conversational state. All mutating retries keep their original IDs."""
from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator

import httpx


class Control:
    def __init__(self, base_url: str, token: str, call_id: str):
        self.root = f"/internal/voice/calls/{call_id}"
        self.client = httpx.AsyncClient(
            base_url=base_url.rstrip("/"),
            headers={"Authorization": f"Bearer {token}"},
            timeout=httpx.Timeout(10, read=30),
        )

    async def request(self, method: str, suffix: str, body: dict | None = None) -> dict:
        for attempt in range(3):
            try:
                response = await self.client.request(method, self.root + suffix, json=body)
                response.raise_for_status()
                result = response.json()
                # The global Java advice may wrap business failures even with HTTP 200.
                if "code" in result and result["code"] != 0:
                    raise RuntimeError(f"Java control rejected operation: {result['code']}")
                return result
            except (httpx.TransportError, httpx.HTTPStatusError) as exc:
                if isinstance(exc, httpx.HTTPStatusError) and exc.response.status_code < 500:
                    raise
                if attempt == 2:
                    raise
                await asyncio.sleep(0.2 * (attempt + 1))
        raise AssertionError("unreachable")

    async def claim(self, room: str, secret: str, worker: str) -> dict:
        result = await self.request("POST", "/claim", {
            "roomName": room, "claimSecret": secret, "workerId": worker,
        })
        self.client.headers["X-Voice-Worker-Epoch"] = str(result["workerEpoch"])
        return result

    async def events(self, path: str) -> AsyncIterator[dict]:
        # A consumed stream is never reopened or replayed into the synthesizer.
        async with self.client.stream("GET", path) as response:
            response.raise_for_status()
            if "text/event-stream" not in response.headers.get("content-type", ""):
                raise RuntimeError("Java reply was not an event stream")
            data: list[str] = []
            async for line in response.aiter_lines():
                if line.startswith("data:"):
                    data.append(line[5:].lstrip())
                elif not line and data:
                    event = json.loads("\n".join(data))
                    data.clear()
                    yield event
                    if event["type"] == "generation_end":
                        return
        raise RuntimeError("Java reply ended without terminal event")

    async def close(self) -> None:
        await self.client.aclose()
