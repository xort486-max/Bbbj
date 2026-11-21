"""NVIDIA 엔진 — NVIDIA API Catalog의 OpenAI 호환 Chat Completions (스트리밍).

두 가지 모드:
  - 대화(chat): 빠른 소형 모델, 추론 끔 → 일상 대화·간단한 질문
  - 에이전트(agent): 대형 추론 모델 → 복잡한 명령·기획·코드·문서

참고: docs.api.nvidia.com/nim/reference — POST {base}/chat/completions, Authorization: Bearer,
stream=true 이면 `data: {...}` SSE 청크, 마지막은 `data: [DONE]`.
"""

from __future__ import annotations

import asyncio
import json
from typing import AsyncIterator, Awaitable, Callable

import httpx2

from ..config import Settings
from .base import JARVIS_SYSTEM_PROMPT, EngineError, StreamEvent, TextDelta, Usage, http_error

ENGINE_NAME = "NVIDIA"

AGENT_INSTRUCTION = (
    "\n\n지금은 복잡한 작업(기획·설계·코드·문서 작성)을 맡았습니다. 요청을 분석해 단계별로 구체적인 결과물을 만드세요. "
    "코드는 실행 가능한 형태로 작성하고, 가정한 내용과 사용자가 확인해야 할 점을 마지막에 짧게 밝히세요. "
    "최신 정보가 필요한데 확인할 수 없는 내용은 확인이 필요하다고 말하세요."
)


class NvidiaChatEngine:
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
        return self.settings.nvidia_model

    @property
    def agent_model(self) -> str:
        return self.settings.nvidia_agent_model

    def model_for(self, agent: bool) -> str:
        return self.agent_model if agent else self.model

    def _payload(self, messages: list[dict[str, str]], agent: bool) -> dict:
        s = self.settings
        system = JARVIS_SYSTEM_PROMPT + (AGENT_INSTRUCTION if agent else "")
        payload = {
            "model": s.nvidia_agent_model if agent else s.nvidia_model,
            "messages": [{"role": "system", "content": system}, *messages],
            "max_tokens": s.nvidia_agent_max_tokens if agent else s.nvidia_max_tokens,
            "stream": True,
        }
        thinking = s.nvidia_agent_enable_thinking if agent else s.nvidia_enable_thinking
        if thinking is not None:
            payload["chat_template_kwargs"] = {"enable_thinking": thinking}
        return payload

    async def stream(self, messages: list[dict[str, str]], *, agent: bool = False) -> AsyncIterator[StreamEvent]:
        """응답 텍스트를 조각 단위로 내보낸다. 첫 조각이 나오기 전의 일시적 오류만 재시도한다."""
        url = f"{self.settings.nvidia_base_url}/chat/completions"
        headers = {
            "Authorization": f"Bearer {self.settings.nvidia_api_key}",
            "Accept": "text/event-stream",
        }
        payload = self._payload(messages, agent)

        for attempt in range(self.attempts):
            started = False
            try:
                async with self.client.stream("POST", url, json=payload, headers=headers) as response:
                    if response.status_code != 200:
                        raise http_error(ENGINE_NAME, response.status_code, await response.aread())
                    async for line in response.aiter_lines():
                        if not line.startswith("data:"):
                            continue
                        data = line[5:].strip()
                        if data == "[DONE]":
                            break
                        try:
                            chunk = json.loads(data)
                        except ValueError:
                            continue
                        if isinstance(chunk.get("error"), (dict, str)):
                            raise EngineError(f"{ENGINE_NAME} 스트림 오류: {chunk['error']}", code="upstream_error")
                        for choice in chunk.get("choices") or []:
                            content = (choice.get("delta") or {}).get("content")
                            if content:
                                started = True
                                yield TextDelta(content)
                        usage = chunk.get("usage")
                        if isinstance(usage, dict):
                            yield Usage(usage.get("prompt_tokens"), usage.get("completion_tokens"))
                return
            except EngineError as error:
                if started or not error.retryable or attempt == self.attempts - 1:
                    raise
            except httpx2.TimeoutException as error:
                if started or attempt == self.attempts - 1:
                    raise EngineError(f"{ENGINE_NAME} 응답 시간이 초과되었습니다.", code="timeout", retryable=True) from error
            except httpx2.TransportError as error:
                if started or attempt == self.attempts - 1:
                    raise EngineError(f"{ENGINE_NAME} 서버에 연결할 수 없습니다.", code="network", retryable=True) from error
            await self.sleep(0.5 * (2**attempt))
