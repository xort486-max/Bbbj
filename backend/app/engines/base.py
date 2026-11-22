"""엔진 공통 타입 — 스트림 이벤트, 오류, 재시도, 대화 기록 정리."""

from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Literal

# --- 스트림 이벤트 (라우터가 SSE로 변환) -----------------------------------------


@dataclass(frozen=True)
class TextDelta:
    text: str


@dataclass(frozen=True)
class Stage:
    """처리 단계. searching=웹 검색 중, reasoning=추론 중, responding=답변 작성 중."""

    stage: Literal["searching", "reasoning", "responding"]
    detail: str | None = None


@dataclass(frozen=True)
class SourceLink:
    title: str
    url: str


@dataclass(frozen=True)
class Sources:
    provider: Literal["google"]
    items: list[SourceLink]
    queries: list[str] = field(default_factory=list)
    # Google 검색 제안 위젯 HTML. Gemini Grounding 약관상 근거 결과와 함께 원본 그대로 표시해야 한다.
    search_suggestions_html: str | None = None


@dataclass(frozen=True)
class Notice:
    text: str


@dataclass(frozen=True)
class Usage:
    input_tokens: int | None = None
    output_tokens: int | None = None
    search_queries: int = 0


StreamEvent = TextDelta | Stage | Sources | Notice | Usage


# --- 오류 ---------------------------------------------------------------------


class EngineError(Exception):
    """사용자에게 보여줄 수 있는 엔진 오류 (API Key 등 비밀값은 메시지에 넣지 않는다)."""

    def __init__(self, message: str, *, code: str = "engine_error", retryable: bool = False, status: int | None = None):
        super().__init__(message)
        self.message = message
        self.code = code
        self.retryable = retryable
        self.status = status


def http_error(engine: str, status: int, body: bytes | str) -> EngineError:
    """HTTP 오류 응답을 한국어 EngineError로 변환한다."""
    detail = _error_detail(body)
    suffix = f" ({detail})" if detail else ""
    if status in (401, 403):
        return EngineError(f"{engine} API 인증에 실패했습니다. API Key를 확인하세요.{suffix}", code="auth", status=status)
    if status == 402:
        return EngineError(f"{engine} API 크레딧이 부족합니다.{suffix}", code="billing", status=status)
    if status == 404:
        return EngineError(f"{engine} 모델 또는 엔드포인트를 찾을 수 없습니다. 모델 ID를 확인하세요.{suffix}", code="not_found", status=status)
    if status == 410:
        return EngineError(f"{engine} 모델이 지원 종료되었습니다. 설정의 모델 ID를 바꾸세요.{suffix}", code="model_retired", status=status)
    if status == 429 and detail and any(k in detail.lower() for k in ("quota", "billing", "plan")):
        # 무료 등급에 할당량이 없거나 결제가 필요한 경우 — 기다려도 해결되지 않으므로 재시도하지 않는다.
        return EngineError(
            f"{engine} API 할당량이 없습니다. 무료 등급에서 지원되지 않거나 결제 설정이 필요합니다.{suffix}",
            code="quota_exceeded",
            status=status,
        )
    if status == 429:
        return EngineError(f"{engine} API 요청 한도를 초과했습니다. 잠시 후 다시 시도하세요.{suffix}", code="rate_limited", retryable=True, status=status)
    if status >= 500:
        return EngineError(f"{engine} 서버 오류({status})입니다. 잠시 후 다시 시도하세요.", code="upstream_error", retryable=True, status=status)
    return EngineError(f"{engine} 요청 오류({status}){suffix}", code="bad_request", status=status)


def _error_detail(body: bytes | str) -> str | None:
    if isinstance(body, bytes):
        body = body.decode("utf-8", errors="replace")
    try:
        data = json.loads(body)
    except (ValueError, TypeError):
        return body.strip()[:200] or None
    if isinstance(data, dict):
        err = data.get("error")
        if isinstance(err, dict):
            return str(err.get("message") or err.get("status") or "")[:200] or None
        if isinstance(err, str):
            return err[:200]
        for key in ("detail", "message"):
            if isinstance(data.get(key), str):
                return data[key][:200]
    return None


async def retry_async(
    call: Callable[[], Awaitable[Any]],
    *,
    attempts: int = 3,
    base_delay: float = 0.5,
    sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
) -> Any:
    """재시도 가능한 EngineError(429, 5xx, 네트워크)만 지수 백오프로 재시도한다."""
    for attempt in range(attempts):
        try:
            return await call()
        except EngineError as error:
            if not error.retryable or attempt == attempts - 1:
                raise
            await sleep(base_delay * (2**attempt))
    raise AssertionError("unreachable")


# --- 대화 기록 -----------------------------------------------------------------


@dataclass(frozen=True)
class ChatTurn:
    role: Literal["user", "assistant"]
    content: str


def build_messages(history: list[ChatTurn], text: str, *, max_turns: int = 20, max_chars: int = 16000) -> list[dict[str, str]]:
    """최근 대화 기록 + 현재 입력을 API 메시지 목록으로 만든다.

    - 최근 max_turns개, 총 max_chars 이내만 보낸다 (토큰 절약). 오래된 대화 요약은 PHASE 7.
    - 첫 메시지는 user여야 하므로 앞쪽 assistant 메시지는 버린다.
    """
    turns = [t for t in history if t.content.strip()][-max_turns:]
    kept: list[ChatTurn] = []
    budget = max_chars - len(text)
    for turn in reversed(turns):
        if len(turn.content) > budget:
            break
        kept.append(turn)
        budget -= len(turn.content)
    kept.reverse()
    while kept and kept[0].role != "user":
        kept.pop(0)
    return [{"role": t.role, "content": t.content} for t in kept] + [{"role": "user", "content": text}]


JARVIS_SYSTEM_PROMPT = (
    "당신의 이름은 JARVIS(자비스)이며, 사용자의 개인 AI 비서입니다. 자신을 소개하거나 정체를 질문받으면 "
    "기반 모델 이름이 아니라 JARVIS라고 답합니다. 기본적으로 한국어로 답하고, 사용자가 다른 언어로 말하면 그 언어로 답합니다. "
    "답변은 음성으로 읽어 줄 수 있도록 핵심부터 간결하게 말합니다. 모르는 것은 추측하지 말고 모른다고 말합니다. "
    "앱이 실제로 실행하지 않은 작업(일정 추가, 파일 생성 등)을 완료했다고 말하지 않습니다."
)
