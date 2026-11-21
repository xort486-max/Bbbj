"""테스트용 가짜 업스트림(NVIDIA / Gemini) 구성."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Callable

import httpx2

from app.config import Settings
from app.engines.gemini_search import GeminiSearchEngine
from app.engines.nvidia import NvidiaChatEngine
from app.services.container import Services
from app.services.guard import RequestGuard
from app.services.usage import UsageStore

TOKEN = "test-app-token"

BASE_ENV = {
    "JARVIS_APP_TOKEN": TOKEN,
    "NVIDIA_API_KEY": "nvapi-test",
    "GOOGLE_SEARCH_PROVIDER": "gemini_grounding",
    "GOOGLE_API_KEY": "gemini-test",
    "JARVIS_USAGE_DB": ":memory:",
}


async def _no_sleep(_: float) -> None:
    return None


@dataclass
class Upstream:
    """호스트별 응답 핸들러와 요청 기록."""

    handlers: dict[str, Callable[[httpx2.Request], httpx2.Response]] = field(default_factory=dict)
    requests: list[httpx2.Request] = field(default_factory=list)

    def __call__(self, request: httpx2.Request) -> httpx2.Response:
        self.requests.append(request)
        handler = self.handlers.get(request.url.host)
        if handler is None:
            return httpx2.Response(599, text="no handler")
        return handler(request)

    def calls(self, host: str) -> list[httpx2.Request]:
        return [r for r in self.requests if r.url.host == host]


def build_services(upstream: Upstream, env_overrides: dict[str, str] | None = None, drop: tuple[str, ...] = ()) -> Services:
    env = {**BASE_ENV, **(env_overrides or {})}
    for key in drop:
        env.pop(key, None)
    settings = Settings.from_env(env)
    transport = httpx2.MockTransport(upstream)
    http = httpx2.AsyncClient(transport=transport)
    return Services(
        settings=settings,
        usage=UsageStore(":memory:", settings.timezone),
        guard=RequestGuard(settings.response_cache_ttl_seconds),
        nvidia=NvidiaChatEngine(settings, http, sleep=_no_sleep) if settings.nvidia_ready else None,
        gemini=GeminiSearchEngine(settings, http, sleep=_no_sleep) if settings.google_ready else None,
        http=http,
    )


def sse_body(events: list[tuple[str | None, object]]) -> str:
    out = []
    for name, data in events:
        if name:
            out.append(f"event: {name}")
        out.append("data: " + (data if isinstance(data, str) else json.dumps(data, ensure_ascii=False)))
        out.append("")
    return "\n".join(out) + "\n"


def parse_sse(text: str) -> list[tuple[str, dict]]:
    events = []
    for block in text.strip().split("\n\n"):
        name, data = None, None
        for line in block.splitlines():
            if line.startswith("event:"):
                name = line[6:].strip()
            elif line.startswith("data:"):
                data = json.loads(line[5:].strip())
        if name:
            events.append((name, data))
    return events


# --- 업스트림 응답 샘플 ---------------------------------------------------------


def nvidia_stream(*pieces: str, usage: dict | None = None) -> Callable[[httpx2.Request], httpx2.Response]:
    chunks: list[tuple[str | None, object]] = [
        (None, {"choices": [{"index": 0, "delta": {"content": p}}]}) for p in pieces
    ]
    if usage:
        chunks.append((None, {"choices": [], "usage": usage}))
    chunks.append((None, "[DONE]"))
    body = sse_body(chunks)
    return lambda request: httpx2.Response(200, text=body, headers={"content-type": "text/event-stream"})


GEMINI_INTERACTION = {
    "id": "v1_test",
    "status": "completed",
    "steps": [
        {"type": "thought", "summary": [{"type": "text", "text": "..."}], "signature": "sig"},
        {"type": "google_search_call", "arguments": {"queries": ["UEFA Euro 2024 winner"]}},
        {"type": "google_search_result", "call_id": "search_001",
         "result": [{"search_suggestions": "<div class=\"container\"><a href=\"https://www.google.com/search?q=euro\">euro</a></div>"}]},
        {"type": "model_output", "content": [{
            "type": "text",
            "text": "Spain won Euro 2024, defeating England 2-1 in the final. This victory marks Spain's record fourth European Championship title.",
            "annotations": [
                {"type": "url_citation", "url": "https://www.aljazeera.com/sports/euro-2024-final", "title": "aljazeera.com", "start_index": 0, "end_index": 56},
                {"type": "url_citation", "url": "https://www.uefa.com/euro2024/news/spain-wins-euro-2024", "title": "uefa.com", "start_index": 57, "end_index": 124},
                {"type": "url_citation", "url": "https://www.uefa.com/euro2024/news/spain-wins-euro-2024", "title": "uefa.com", "start_index": 0, "end_index": 10},
            ],
        }]},
    ],
    "usage": {"total_tokens": 300, "total_input_tokens": 20, "total_output_tokens": 60},
}


def respond_sse(body: str) -> Callable[[httpx2.Request], httpx2.Response]:
    return lambda request: httpx2.Response(200, text=body, headers={"content-type": "text/event-stream"})
