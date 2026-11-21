"""`POST /v1/assist` — 앱의 로컬 분류기가 정한 경로로 AI 엔진을 실행하고 결과를 SSE로 스트리밍한다.

경로 → 엔진
  chat     → NVIDIA 대화 모델 (빠른 소형 모델)
  search   → Google Gemini Grounding (웹 검색, 원문·출처·검색 제안 그대로 표시)
  research → Google Gemini Grounding (검색 + 비교·분석 지시)
  agent    → NVIDIA 에이전트 모델 (대형 추론 모델: 기획·코드·문서)

대체 처리: Google 검색을 쓸 수 없으면 research → NVIDIA 에이전트로 대체하고
"최신 정보가 아닐 수 있다"는 notice를 보낸다. search는 실제 검색이 필수라 대체하지 않는다.

SSE 이벤트 (event: 이름 / data: JSON)
  route   {route, engine, model}
  notice  {text}
  stage   {stage: searching|reasoning|responding, detail?}
  delta   {text}
  sources {provider, items:[{title,url}], queries:[], search_suggestions_html?}
  done    {engine, model, cached, input_tokens, output_tokens, search_queries}
  error   {message, code, retryable}
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from dataclasses import asdict, dataclass
from typing import AsyncIterator, Literal

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from ..engines.base import ChatTurn, EngineError, Notice, Sources, Stage, TextDelta, Usage, build_messages
from ..security import require_app_token
from ..services.container import Services
from ..services.guard import CachedResponse

logger = logging.getLogger("jarvis.assist")
router = APIRouter(prefix="/v1", tags=["assist"], dependencies=[Depends(require_app_token)])

Route = Literal["chat", "search", "research", "agent"]
Engine = Literal["nvidia", "google"]


class HistoryItem(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(max_length=20000)


class AssistRequest(BaseModel):
    route: Route
    text: str = Field(min_length=1, max_length=8000)
    history: list[HistoryItem] = Field(default_factory=list, max_length=50)


@dataclass(frozen=True)
class Plan:
    engine: Engine
    agent: bool = False  # NVIDIA 에이전트(대형 추론) 모델 사용
    research: bool = False  # Gemini 검색 + 분석 지시
    notice: str | None = None


def get_services(request: Request) -> Services:
    return request.app.state.services


ENGINE_LABEL = {"nvidia": "NVIDIA", "google": "Google 검색"}


def _available(services: Services, engine: Engine) -> tuple[bool, str | None]:
    """(사용 가능 여부, 불가 사유)"""
    settings = services.settings
    engine_obj = {"nvidia": services.nvidia, "google": services.gemini}[engine]
    if engine_obj is None:
        missing = {"nvidia": settings.nvidia_missing, "google": settings.google_missing}[engine]()
        return False, (f"설정 필요: {', '.join(missing)}" if missing else "구현되지 않은 제공자")
    limit = settings.daily_limit(engine)
    if limit and services.usage.requests_today(engine) >= limit:
        return False, f"일일 한도 {limit}회 초과"
    return True, None


def plan_route(route: Route, services: Services) -> Plan:
    """경로에 맞는 엔진을 고르고, 불가하면 대체 엔진을 고른다. 모두 불가하면 HTTPException."""
    if route in ("chat", "agent"):
        ok, reason = _available(services, "nvidia")
        if ok:
            return Plan("nvidia", agent=route == "agent")
        primary = "nvidia"
    else:  # search / research
        ok, reason = _available(services, "google")
        if ok:
            return Plan("google", research=route == "research")
        primary = "google"
        if route == "research" and _available(services, "nvidia")[0]:
            return Plan(
                "nvidia",
                agent=True,
                notice=f"Google 검색을 사용할 수 없어({reason}) 검색 없이 NVIDIA가 답합니다. 최신 정보가 아닐 수 있습니다.",
            )
    status = 429 if reason and "한도" in reason else 503
    raise HTTPException(status_code=status, detail=f"{ENGINE_LABEL[primary]} 엔진을 사용할 수 없습니다: {reason}")


def sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


def _model(plan: Plan, services: Services) -> str:
    if plan.engine == "google":
        return services.gemini.model if services.gemini else "unknown"
    return services.nvidia.model_for(plan.agent) if services.nvidia else "unknown"


@router.post("/assist")
async def assist(body: AssistRequest, services: Services = Depends(get_services)) -> StreamingResponse:
    history = [h.model_dump() for h in body.history]
    key = services.guard.key(body.route, body.text, history)
    plan = plan_route(body.route, services)

    if not services.guard.try_acquire(key):
        raise HTTPException(status_code=409, detail="같은 요청을 이미 처리하고 있습니다.")

    return StreamingResponse(
        _run(body, plan, key, services),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


async def _run(body: AssistRequest, plan: Plan, key: str, services: Services) -> AsyncIterator[str]:
    model = _model(plan, services)
    started = time.monotonic()
    status, error_code = "ok", None
    usage = Usage()
    text_parts: list[str] = []
    # Google(Gemini) 근거 결과는 약관상 캐시하지 않는다 → NVIDIA 에이전트 응답만 캐시
    cacheable = plan.engine == "nvidia" and plan.agent

    try:
        yield sse("route", {"route": body.route, "engine": plan.engine, "model": model})
        if plan.notice:
            yield sse("notice", {"text": plan.notice})

        cached = services.guard.get(key, body.route) if cacheable else None
        if cached:
            status = "cached"
            yield sse("delta", {"text": cached.text})
            yield sse("done", {"engine": cached.engine, "model": cached.model, "cached": True,
                               "input_tokens": 0, "output_tokens": 0, "search_queries": 0})
            return

        if plan.engine == "google":
            yield sse("stage", {"stage": "searching"})
            result = await services.gemini.search(body.text, research=plan.research)
            # 근거 결과는 수정하지 않고 그대로 전달한다 (약관).
            yield sse("delta", {"text": result.text})
            yield sse("sources", {
                "provider": "google",
                "items": [asdict(i) for i in result.sources],
                "queries": result.queries,
                "search_suggestions_html": result.search_suggestions_html,
            })
            usage = Usage(result.input_tokens, result.output_tokens, len(result.queries))
        else:
            messages = build_messages([ChatTurn(**h.model_dump()) for h in body.history], body.text)
            yield sse("stage", {"stage": "reasoning" if plan.agent else "responding"})
            async for event in services.nvidia.stream(messages, agent=plan.agent):
                if isinstance(event, TextDelta):
                    text_parts.append(event.text)
                    yield sse("delta", {"text": event.text})
                elif isinstance(event, Stage):
                    yield sse("stage", {"stage": event.stage, "detail": event.detail})
                elif isinstance(event, Notice):
                    yield sse("notice", {"text": event.text})
                elif isinstance(event, Sources):  # NVIDIA는 출처를 내지 않지만 확장 대비
                    yield sse("sources", {"provider": event.provider, "items": [asdict(i) for i in event.items],
                                          "queries": event.queries})
                elif isinstance(event, Usage):
                    usage = event

        yield sse("done", {"engine": plan.engine, "model": model, "cached": False,
                           "input_tokens": usage.input_tokens, "output_tokens": usage.output_tokens,
                           "search_queries": usage.search_queries})

        if cacheable:
            services.guard.put(key, body.route, CachedResponse("".join(text_parts), plan.engine, model))
    except EngineError as error:
        status, error_code = "error", error.code
        logger.warning("engine error engine=%s route=%s code=%s", plan.engine, body.route, error.code)
        yield sse("error", {"message": error.message, "code": error.code, "retryable": error.retryable})
    except (asyncio.CancelledError, GeneratorExit):
        status = "cancelled"
        raise
    except Exception:  # noqa: BLE001 — 예기치 못한 오류도 앱에 명확히 알린다
        status, error_code = "error", "internal"
        logger.exception("unexpected error engine=%s route=%s", plan.engine, body.route)
        yield sse("error", {"message": "백엔드 내부 오류가 발생했습니다.", "code": "internal", "retryable": False})
    finally:
        services.guard.release(key)
        services.usage.record(
            engine=plan.engine,
            model=model,
            route=body.route,
            status=status,
            input_tokens=usage.input_tokens,
            output_tokens=usage.output_tokens,
            search_queries=usage.search_queries,
            latency_ms=int((time.monotonic() - started) * 1000),
            error_code=error_code,
        )
