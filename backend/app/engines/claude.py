"""Claude 엔진 — 복잡한 명령·기획·분석 (Anthropic 공식 Python SDK, 스트리밍).

- 모델 기본값 claude-opus-5-5: 사고(thinking)는 항상 켜져 있으며 깊이는 effort로 조절한다 (기본 medium).
- 서버 측 대체 모델(fallbacks="default", beta server-side-fallback-2026-07-01):
  안전 분류기가 오탐으로 요청을 거절하면 Anthropic 권장 모델로 같은 요청을 자동 재실행한다.
- research 경로: Claude의 웹 검색 서버 도구(web_search_20260209)로 직접 검색·분석한다.
  (Gemini 근거 결과를 다른 AI에 넘겨 분석하는 것은 Google 약관상 금지되어 있으므로 사용하지 않는다.)
- PHASE 5에서 Tool Use(파일·프로젝트·일정 도구)를 추가한다.
"""

from __future__ import annotations

from typing import Any, AsyncIterator

import anthropic

from ..config import Settings
from .base import JARVIS_SYSTEM_PROMPT, EngineError, Notice, SourceLink, Sources, Stage, StreamEvent, TextDelta, Usage

ENGINE_NAME = "Claude"
FALLBACK_BETA = "server-side-fallback-2026-07-01"
MAX_CONTINUATIONS = 3  # pause_turn(서버 도구 반복 한도) 이어받기 최대 횟수

RESEARCH_INSTRUCTION = (
    "\n\n사용자가 최신 정보 조사와 분석을 요청했습니다. 필요한 만큼 웹 검색을 사용해 근거를 확인하고, "
    "출처가 다른 정보를 비교해 결론과 핵심 근거를 정리하세요."
)


class ClaudeEngine:
    def __init__(self, settings: Settings, client: anthropic.AsyncAnthropic):
        self.settings = settings
        self.client = client

    @property
    def model(self) -> str:
        return self.settings.claude_model

    async def stream(self, messages: list[dict[str, Any]], *, web_search: bool) -> AsyncIterator[StreamEvent]:
        params: dict[str, Any] = {
            "model": self.settings.claude_model,
            "max_tokens": self.settings.claude_max_tokens,
            "system": JARVIS_SYSTEM_PROMPT + (RESEARCH_INSTRUCTION if web_search else ""),
            "output_config": {"effort": self.settings.claude_effort},
            "betas": [FALLBACK_BETA],
            "fallbacks": "default",
        }
        if web_search:
            params["tools"] = [
                {"type": "web_search_20260209", "name": "web_search", "max_uses": self.settings.claude_web_search_max_uses}
            ]

        assistant_content: list[Any] = []
        input_tokens = output_tokens = searches = 0
        yield Stage("reasoning")

        try:
            for _ in range(MAX_CONTINUATIONS + 1):
                conversation = messages + ([{"role": "assistant", "content": assistant_content}] if assistant_content else [])
                async with self.client.beta.messages.stream(**params, messages=conversation) as stream:
                    async for event in stream:
                        for out in _translate(event):
                            yield out
                    final = await stream.get_final_message()

                usage = final.usage
                input_tokens += usage.input_tokens or 0
                output_tokens += usage.output_tokens or 0
                server_tool_use = getattr(usage, "server_tool_use", None)
                searches += getattr(server_tool_use, "web_search_requests", 0) or 0

                if final.stop_reason == "refusal":
                    raise EngineError("요청이 Claude 안전 정책에 의해 거절되었습니다.", code="refusal")
                if final.stop_reason == "pause_turn":
                    assistant_content = [*assistant_content, *final.content]
                    continue
                if final.stop_reason == "max_tokens":
                    yield Notice("응답이 최대 길이에 도달해 중간에 끊겼습니다.")
                break
        except anthropic.AuthenticationError as error:
            raise EngineError("Claude API 인증에 실패했습니다. API Key를 확인하세요.", code="auth", status=401) from error
        except anthropic.PermissionDeniedError as error:
            raise EngineError("Claude API 권한이 없습니다.", code="auth", status=403) from error
        except anthropic.NotFoundError as error:
            raise EngineError("Claude 모델을 찾을 수 없습니다. CLAUDE_MODEL을 확인하세요.", code="not_found", status=404) from error
        except anthropic.RateLimitError as error:
            raise EngineError("Claude API 요청 한도를 초과했습니다. 잠시 후 다시 시도하세요.", code="rate_limited", retryable=True, status=429) from error
        except anthropic.BadRequestError as error:
            raise EngineError(f"Claude 요청 오류: {error.message}", code="bad_request", status=400) from error
        except anthropic.APIStatusError as error:
            raise EngineError(f"Claude 서버 오류({error.status_code})입니다.", code="upstream_error", retryable=True, status=error.status_code) from error
        except anthropic.APITimeoutError as error:
            raise EngineError("Claude 응답 시간이 초과되었습니다.", code="timeout", retryable=True) from error
        except anthropic.APIConnectionError as error:
            raise EngineError("Claude 서버에 연결할 수 없습니다.", code="network", retryable=True) from error

        yield Usage(input_tokens, output_tokens, searches)


def _translate(event: Any) -> list[StreamEvent]:
    """SDK 스트림 이벤트 → JARVIS 스트림 이벤트."""
    if event.type == "content_block_start":
        block = event.content_block
        if block.type == "server_tool_use":
            query = (getattr(block, "input", None) or {}).get("query") if isinstance(getattr(block, "input", None), dict) else None
            return [Stage("searching", query)]
        if block.type == "web_search_tool_result":
            content = block.content
            if isinstance(content, list):
                items = [SourceLink(title=r.title or r.url, url=r.url) for r in content if getattr(r, "url", None)]
                return [Sources(provider="claude_web_search", items=items)] if items else []
            return [Notice(f"웹 검색 오류: {getattr(content, 'error_code', 'unknown')}")]
        if block.type == "text":
            return [Stage("responding")]
        return []
    if event.type == "content_block_delta" and event.delta.type == "text_delta":
        return [TextDelta(event.delta.text)]
    return []
