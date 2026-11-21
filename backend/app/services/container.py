"""엔진·저장소 묶음 (앱 수명 동안 공유)."""

from __future__ import annotations

from dataclasses import dataclass

import anthropic
import httpx2

from ..config import Settings
from ..engines.claude import ClaudeEngine
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
    claude: ClaudeEngine | None
    http: httpx2.AsyncClient | None = None
    anthropic_client: anthropic.AsyncAnthropic | None = None

    @classmethod
    def build(cls, settings: Settings) -> "Services":
        http = httpx2.AsyncClient(timeout=httpx2.Timeout(90.0, connect=10.0))
        anthropic_client = (
            anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key, max_retries=2, timeout=600.0)
            if settings.claude_ready
            else None
        )
        return cls(
            settings=settings,
            usage=UsageStore(settings.usage_db_path, settings.timezone),
            guard=RequestGuard(settings.response_cache_ttl_seconds),
            nvidia=NvidiaChatEngine(settings, http) if settings.nvidia_ready else None,
            gemini=GeminiSearchEngine(settings, http) if settings.google_ready else None,
            claude=ClaudeEngine(settings, anthropic_client) if anthropic_client else None,
            http=http,
            anthropic_client=anthropic_client,
        )

    async def aclose(self) -> None:
        if self.http is not None:
            await self.http.aclose()
        if self.anthropic_client is not None:
            await self.anthropic_client.close()
        self.usage.close()
