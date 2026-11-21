"""NVIDIA 대화 엔진 — NVIDIA API Catalog의 OpenAI 호환 Chat Completions (스트리밍).

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

    def _payload(self, messages: list[dict[str, str]]) -> dict:
        payload = {
            "model": self.settings.nvidia_model,
            "messages": [{"role": "system", "content": JARVIS_SYSTEM_PROMPT}, *messages],
            "max_tokens": self.settings.nvidia_max_tokens,
            "stream": True,
        }
        if self.settings.nvidia_enable_thinking is not None:
            payload["chat_template_kwargs"] = {"enable_thinking": self.settings.nvidia_enable_thinking}
        return payload

    async def stream(self, messages: list[dict[str, str]]) -> AsyncIterator[StreamEvent]:
        """응답 텍스트를 조각 단위로 내보낸다. 첫 조각이 나오기 전의 일시적 오류만 재시도한다."""
        url = f"{self.settings.nvidia_base_url}/chat/completions"
        headers = {
            "Authorization": f"Bearer {self.settings.nvidia_api_key}",
            "Accept": "text/event-stream",
        }
        payload = self._payload(messages)

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
