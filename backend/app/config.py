"""환경변수 기반 설정.

모든 비밀값(API Key, 앱 토큰)은 환경변수 또는 서버의 비밀 관리 기능으로만 주입한다.
코드나 저장소에 비밀값을 넣지 않는다. 로컬 개발 시에는 `.env` 파일(.gitignore 대상)을 사용한다.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Mapping

DEFAULT_NVIDIA_BASE_URL = "https://integrate.api.nvidia.com/v1"
# NVIDIA API Catalog 대화 모델. 2026-11-21 실제 /v1/models 조회 + 한국어 응답 시험으로 선택
# (문서에 남아 있던 qwen/qwen3-next-80b-a3b-instruct는 2026-07-27 지원 종료 — 410 응답 확인).
DEFAULT_NVIDIA_MODEL = "nvidia/nemotron-3.5-lightning-30b-a3b"
DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
# Gemini 4 Hydrogen: 일상 작업용 빠르고 효율적인 모델 (aistudio.google.com/docs/models 확인, 2026-11)
DEFAULT_GEMINI_MODEL = "gemini-4-hydrogen"
DEFAULT_CLAUDE_MODEL = "claude-opus-5-5"

# Google 검색 제공 방식. Custom Search JSON API는 신규 고객 접수가 중단되었고 2027-01-01 종료 예정.
# PHASE 2부터 gemini_grounding을 구현했다. 나머지 값은 설정 확인 용도로만 남겨 둔다.
GOOGLE_SEARCH_PROVIDERS = {"custom_search", "vertex_ai_search", "gemini_grounding"}
IMPLEMENTED_GOOGLE_PROVIDERS = {"gemini_grounding"}
CLAUDE_EFFORTS = {"low", "medium", "high", "xhigh", "max"}


def _clean(value: str | None) -> str | None:
    if value is None:
        return None
    value = value.strip()
    return value or None


def _int(value: str | None, default: int, minimum: int = 0) -> int:
    try:
        return max(minimum, int(value)) if _clean(value) else default
    except ValueError:
        return default


def _tri_state(value: str | None, default: bool | None) -> bool | None:
    value = (_clean(value) or "").lower()
    if value in ("true", "1", "on", "yes"):
        return True
    if value in ("false", "0", "off", "no"):
        return False
    if value == "auto":
        return None
    return default


@dataclass(frozen=True)
class Settings:
    # 앱 ↔ 백엔드 인증
    app_token: str | None = field(default=None, repr=False)

    # NVIDIA — 대화 엔진 (OpenAI 호환 Chat Completions)
    nvidia_api_key: str | None = field(default=None, repr=False)
    nvidia_base_url: str = DEFAULT_NVIDIA_BASE_URL
    nvidia_model: str = DEFAULT_NVIDIA_MODEL
    nvidia_max_tokens: int = 2048
    # 모델의 추론(thinking) 모드. False=끔(빠른 대화), True=켬, None=요청에 넣지 않음(모델 기본값).
    # chat_template_kwargs.enable_thinking — Nemotron 계열 문서에 명시된 옵션. 다른 모델은 "auto" 권장.
    nvidia_enable_thinking: bool | None = False

    # Google — 검색 엔진
    google_search_provider: str | None = None
    google_api_key: str | None = field(default=None, repr=False)
    gemini_base_url: str = DEFAULT_GEMINI_BASE_URL
    gemini_model: str = DEFAULT_GEMINI_MODEL
    google_cse_id: str | None = None
    google_cloud_project: str | None = None
    vertex_search_engine_id: str | None = None

    # Claude — AI 두뇌 · 에이전트
    anthropic_api_key: str | None = field(default=None, repr=False)
    claude_model: str = DEFAULT_CLAUDE_MODEL
    claude_effort: str = "medium"
    claude_max_tokens: int = 16000
    claude_web_search_max_uses: int = 5

    # 사용량 · 한도 (0 = 제한 없음)
    usage_db_path: str = "jarvis_usage.sqlite3"
    timezone: str = "Asia/Seoul"
    daily_limit_nvidia: int = 0
    daily_limit_google: int = 0
    daily_limit_claude: int = 0
    response_cache_ttl_seconds: int = 600

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> "Settings":
        env = os.environ if env is None else env
        provider = _clean(env.get("GOOGLE_SEARCH_PROVIDER"))
        effort = (_clean(env.get("CLAUDE_EFFORT")) or "medium").lower()
        return cls(
            app_token=_clean(env.get("JARVIS_APP_TOKEN")),
            nvidia_api_key=_clean(env.get("NVIDIA_API_KEY")),
            nvidia_base_url=(_clean(env.get("NVIDIA_BASE_URL")) or DEFAULT_NVIDIA_BASE_URL).rstrip("/"),
            nvidia_model=_clean(env.get("NVIDIA_MODEL")) or DEFAULT_NVIDIA_MODEL,
            nvidia_max_tokens=_int(env.get("NVIDIA_MAX_TOKENS"), 2048, minimum=64),
            nvidia_enable_thinking=_tri_state(env.get("NVIDIA_ENABLE_THINKING"), default=False),
            google_search_provider=provider.lower() if provider else None,
            google_api_key=_clean(env.get("GOOGLE_API_KEY")),
            gemini_base_url=(_clean(env.get("GEMINI_BASE_URL")) or DEFAULT_GEMINI_BASE_URL).rstrip("/"),
            gemini_model=_clean(env.get("GEMINI_MODEL")) or DEFAULT_GEMINI_MODEL,
            google_cse_id=_clean(env.get("GOOGLE_CSE_ID")),
            google_cloud_project=_clean(env.get("GOOGLE_CLOUD_PROJECT")),
            vertex_search_engine_id=_clean(env.get("VERTEX_SEARCH_ENGINE_ID")),
            anthropic_api_key=_clean(env.get("ANTHROPIC_API_KEY")),
            claude_model=_clean(env.get("CLAUDE_MODEL")) or DEFAULT_CLAUDE_MODEL,
            claude_effort=effort if effort in CLAUDE_EFFORTS else "medium",
            claude_max_tokens=_int(env.get("CLAUDE_MAX_TOKENS"), 16000, minimum=1024),
            claude_web_search_max_uses=_int(env.get("CLAUDE_WEB_SEARCH_MAX_USES"), 5, minimum=1),
            usage_db_path=_clean(env.get("JARVIS_USAGE_DB")) or "jarvis_usage.sqlite3",
            timezone=_clean(env.get("JARVIS_TIMEZONE")) or "Asia/Seoul",
            daily_limit_nvidia=_int(env.get("DAILY_LIMIT_NVIDIA"), 0),
            daily_limit_google=_int(env.get("DAILY_LIMIT_GOOGLE"), 0),
            daily_limit_claude=_int(env.get("DAILY_LIMIT_CLAUDE"), 0),
            response_cache_ttl_seconds=_int(env.get("RESPONSE_CACHE_TTL_SECONDS"), 600),
        )

    # --- 엔진별 설정 누락 항목 (실제 호출 성공 여부와는 별개) ---

    def nvidia_missing(self) -> list[str]:
        return [] if self.nvidia_api_key else ["NVIDIA_API_KEY"]

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

    # --- 실제로 호출 가능한지 (설정 완료 + 구현된 제공자) ---

    @property
    def nvidia_ready(self) -> bool:
        return not self.nvidia_missing()

    @property
    def google_ready(self) -> bool:
        return not self.google_missing() and self.google_search_provider in IMPLEMENTED_GOOGLE_PROVIDERS

    @property
    def claude_ready(self) -> bool:
        return not self.claude_missing()

    def daily_limit(self, engine: str) -> int:
        return {"nvidia": self.daily_limit_nvidia, "google": self.daily_limit_google, "claude": self.daily_limit_claude}.get(
            engine, 0
        )
