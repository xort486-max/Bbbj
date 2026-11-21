"""Google 검색 엔진 — Gemini API "Grounding with Google Search" (Interactions API).

공식 문서(aistudio.google.com/docs/google-search, 2026-11 확인):
  POST {base}/interactions  · 헤더 x-goog-api-key, Api-Revision: 2026-05-20
  body: {"model", "input", "tools": [{"type": "google_search"}], "store": false}
  응답 steps: google_search_call(arguments.queries) · google_search_result(result[].search_suggestions HTML)
             · model_output(content[].text + annotations[] url_citation{url,title,start_index,end_index})
  usage: total_input_tokens / total_output_tokens

이용약관(Gemini API Additional Terms — Grounding with Google Search) 준수 사항:
  - 근거 결과(Grounded Result)와 검색 제안(Search Suggestions)을 요청한 사용자에게 함께 표시한다.
  - 근거 결과·검색 제안을 캐시하거나, 제3자(다른 AI 포함)가 분석하게 하거나, 수정·다른 내용과 섞지 않는다.
  - 링크 클릭 추적을 하지 않는다.
  → 이 모듈은 결과를 캐시하지 않으며, 서버 로그·사용량 DB에도 본문을 저장하지 않는다.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from typing import Awaitable, Callable

import httpx2

from ..config import Settings
from .base import EngineError, SourceLink, http_error, retry_async

ENGINE_NAME = "Google(Gemini)"
API_REVISION = "2026-05-20"
MAX_SEARCH_SUGGESTIONS_NOTE = 5  # 약관: 최종 결과와 함께 최대 5개의 검색 제안 표시

RESEARCH_INSTRUCTION = (
    "다음 요청을 Google 검색으로 최신 근거를 충분히 찾아 조사하고, 출처가 다른 정보를 비교·분석해 "
    "결론과 핵심 근거를 한국어로 정리하세요."
)


@dataclass(frozen=True)
class Citation:
    title: str
    url: str
    start_index: int
    end_index: int


@dataclass(frozen=True)
class GroundedSearchResult:
    text: str
    citations: list[Citation]
    queries: list[str]
    search_suggestions_html: str | None
    input_tokens: int | None
    output_tokens: int | None
    grounded: bool = field(default=False)

    @property
    def sources(self) -> list[SourceLink]:
        seen: dict[str, SourceLink] = {}
        for c in self.citations:
            seen.setdefault(c.url, SourceLink(title=c.title or c.url, url=c.url))
        return list(seen.values())


class GeminiSearchEngine:
    def __init__(
        self,
        settings: Settings,
        client: httpx2.AsyncClient,
        *,
        attempts: int = 3,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ):
        self.settings = settings
        self.client = client
        self.attempts = attempts
        self.sleep = sleep

    @property
    def model(self) -> str:
        return self.settings.gemini_model

    async def search(self, query: str, *, research: bool = False) -> GroundedSearchResult:
        """웹 검색 근거 답변. research=True면 여러 출처를 비교·분석해 정리하도록 요청한다.

        (요청문에 지시를 덧붙이는 것은 허용되며, 약관상 금지되는 것은 결과를 수정·캐시·타 AI 분석하는 것이다.)
        """
        prompt = f"{RESEARCH_INSTRUCTION}\n\n{query}" if research else query

        async def call() -> dict:
            try:
                response = await self.client.post(
                    f"{self.settings.gemini_base_url}/interactions",
                    headers={
                        "x-goog-api-key": self.settings.google_api_key or "",
                        "Api-Revision": API_REVISION,
                        "Content-Type": "application/json",
                    },
                    json={
                        "model": self.settings.gemini_model,
                        "input": prompt,
                        "tools": [{"type": "google_search"}],
                        # 서버 측 대화 저장 불필요 (단일 검색 요청) — 개인정보 최소화
                        "store": False,
                    },
                )
            except httpx2.TimeoutException as error:
                raise EngineError(f"{ENGINE_NAME} 응답 시간이 초과되었습니다.", code="timeout", retryable=True) from error
            except httpx2.TransportError as error:
                raise EngineError(f"{ENGINE_NAME} 서버에 연결할 수 없습니다.", code="network", retryable=True) from error
            if response.status_code != 200:
                raise http_error(ENGINE_NAME, response.status_code, response.content)
            try:
                return response.json()
            except ValueError as error:
                raise EngineError(f"{ENGINE_NAME} 응답을 해석할 수 없습니다.", code="bad_response") from error

        data = await retry_async(call, attempts=self.attempts, sleep=self.sleep)
        return parse_interaction(data)


def parse_interaction(data: dict) -> GroundedSearchResult:
    """Interactions API 응답에서 답변·인용·검색어·검색 제안을 추출한다."""
    status = data.get("status")
    if status and status not in ("completed",):
        raise EngineError(f"{ENGINE_NAME} 검색이 완료되지 않았습니다 (status={status}).", code="incomplete")

    texts: list[str] = []
    citations: list[Citation] = []
    queries: list[str] = []
    suggestions: list[str] = []
    searched = False

    for step in data.get("steps") or []:
        kind = step.get("type")
        if kind == "google_search_call":
            searched = True
            queries.extend(q for q in (step.get("arguments") or {}).get("queries") or [] if isinstance(q, str) and q.strip())
        elif kind == "google_search_result":
            for item in step.get("result") or []:
                html = (item or {}).get("search_suggestions")
                if isinstance(html, str) and html.strip():
                    suggestions.append(html)
        elif kind == "model_output":
            for block in step.get("content") or []:
                if block.get("type") != "text" or not isinstance(block.get("text"), str):
                    continue
                offset = sum(len(t) for t in texts)
                texts.append(block["text"])
                for ann in block.get("annotations") or []:
                    if ann.get("type") == "url_citation" and ann.get("url"):
                        citations.append(
                            Citation(
                                title=str(ann.get("title") or ""),
                                url=str(ann["url"]),
                                start_index=offset + int(ann.get("start_index") or 0),
                                end_index=offset + int(ann.get("end_index") or 0),
                            )
                        )

    text = "".join(texts).strip()
    if not text:
        raise EngineError(f"{ENGINE_NAME} 검색 결과가 비어 있습니다.", code="empty")

    usage = data.get("usage") or {}
    return GroundedSearchResult(
        text=text,
        citations=citations,
        queries=queries,
        search_suggestions_html="\n".join(suggestions) or None,
        input_tokens=usage.get("total_input_tokens"),
        output_tokens=usage.get("total_output_tokens"),
        grounded=searched or bool(citations),
    )
