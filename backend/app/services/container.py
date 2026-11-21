"""엔진·저장소 묶음 (앱 수명 동안 공유)."""

from __future__ import annotations

from dataclasses import dataclass

import httpx2

from ..config import Settings
from ..engines.gemini_search import GeminiSearchEngine
from ..engines.nvidia import NvidiaChatEngine
from .guard import RequestGuard
from .usage import UsageStore


@dataclass
class Services:
    settings: Settings
    usage: UsageStore
    guard: RequestGuard
    nvidia: NvidiaChatEngine | None
    gemini: GeminiSearchEngine | None
    http: httpx2.AsyncClient | None = None

    @classmethod
    def build(cls, settings: Settings) -> "Services":
        http = httpx2.AsyncClient(timeout=httpx2.Timeout(120.0, connect=10.0))
        return cls(
            settings=settings,
            usage=UsageStore(settings.usage_db_path, settings.timezone),
            guard=RequestGuard(settings.response_cache_ttl_seconds),
            nvidia=NvidiaChatEngine(settings, http) if settings.nvidia_ready else None,
            gemini=GeminiSearchEngine(settings, http) if settings.google_ready else None,
            http=http,
        )

    async def aclose(self) -> None:
        if self.http is not None:
            await self.http.aclose()
        self.usage.close()
