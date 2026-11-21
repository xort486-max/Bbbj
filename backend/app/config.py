"""환경변수 기반 설정.

모든 비밀값(API Key, 앱 토큰)은 환경변수 또는 서버의 비밀 관리 기능으로만 주입한다.
코드나 저장소에 비밀값을 넣지 않는다. 로컬 개발 시에는 `.env` 파일(.gitignore 대상)을 사용한다.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Mapping

DEFAULT_NVIDIA_BASE_URL = "https://integrate.api.nvidia.com/v1"
DEFAULT_CLAUDE_MODEL = "claude-opus-5-5"

# Google 검색 제공 방식. Custom Search JSON API는 신규 고객 접수가 중단되었고
# 2027-01-01 종료 예정이므로, PHASE 2 착수 전에 사용자가 제품을 선택한다.
GOOGLE_SEARCH_PROVIDERS = {"custom_search", "vertex_ai_search", "gemini_grounding"}


def _clean(value: str | None) -> str | None:
    if value is None:
        return None
    value = value.strip()
    return value or None


@dataclass(frozen=True)
class Settings:
    # 앱 ↔ 백엔드 인증
    app_token: str | None = field(default=None, repr=False)

    # NVIDIA — 대화 엔진 (OpenAI 호환 Chat Completions)
    nvidia_api_key: str | None = field(default=None, repr=False)
    nvidia_base_url: str = DEFAULT_NVIDIA_BASE_URL
    nvidia_model: str | None = None

    # Google — 검색 엔진
    google_search_provider: str | None = None
    google_api_key: str | None = field(default=None, repr=False)
    google_cse_id: str | None = None
    google_cloud_project: str | None = None
    vertex_search_engine_id: str | None = None

    # Claude — AI 두뇌 · 에이전트
    anthropic_api_key: str | None = field(default=None, repr=False)
    claude_model: str = DEFAULT_CLAUDE_MODEL

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> "Settings":
        env = os.environ if env is None else env
        provider = _clean(env.get("GOOGLE_SEARCH_PROVIDER"))
        return cls(
            app_token=_clean(env.get("JARVIS_APP_TOKEN")),
            nvidia_api_key=_clean(env.get("NVIDIA_API_KEY")),
            nvidia_base_url=_clean(env.get("NVIDIA_BASE_URL")) or DEFAULT_NVIDIA_BASE_URL,
            nvidia_model=_clean(env.get("NVIDIA_MODEL")),
            google_search_provider=provider.lower() if provider else None,
            google_api_key=_clean(env.get("GOOGLE_API_KEY")),
            google_cse_id=_clean(env.get("GOOGLE_CSE_ID")),
            google_cloud_project=_clean(env.get("GOOGLE_CLOUD_PROJECT")),
            vertex_search_engine_id=_clean(env.get("VERTEX_SEARCH_ENGINE_ID")),
            anthropic_api_key=_clean(env.get("ANTHROPIC_API_KEY")),
            claude_model=_clean(env.get("CLAUDE_MODEL")) or DEFAULT_CLAUDE_MODEL,
        )

    # --- 엔진별 설정 완료 여부 (실제 호출 성공 여부와는 별개) ---

    def nvidia_missing(self) -> list[str]:
        missing = []
        if not self.nvidia_api_key:
            missing.append("NVIDIA_API_KEY")
        if not self.nvidia_model:
            missing.append("NVIDIA_MODEL")
        return missing

    def google_missing(self) -> list[str]:
        provider = self.google_search_provider
        if provider is None:
            return ["GOOGLE_SEARCH_PROVIDER"]
        if provider not in GOOGLE_SEARCH_PROVIDERS:
            return [f"GOOGLE_SEARCH_PROVIDER (지원값: {', '.join(sorted(GOOGLE_SEARCH_PROVIDERS))})"]
        missing = []
        if provider == "custom_search":
            if not self.google_api_key:
                missing.append("GOOGLE_API_KEY")
            if not self.google_cse_id:
                missing.append("GOOGLE_CSE_ID")
        elif provider == "vertex_ai_search":
            if not self.google_cloud_project:
                missing.append("GOOGLE_CLOUD_PROJECT")
            if not self.vertex_search_engine_id:
                missing.append("VERTEX_SEARCH_ENGINE_ID")
        elif provider == "gemini_grounding":
            if not self.google_api_key:
                missing.append("GOOGLE_API_KEY")
        return missing

    def claude_missing(self) -> list[str]:
        return [] if self.anthropic_api_key else ["ANTHROPIC_API_KEY"]
