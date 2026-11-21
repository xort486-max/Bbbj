"""중복 요청 차단 + 응답 캐시.

- 처리 중인 것과 같은 요청(경로·입력·대화 맥락 동일)이 다시 들어오면 즉시 거절한다 (모든 엔진).
- Claude 응답(agent/research)만 짧은 시간 캐시한다. Google(Gemini) 근거 결과는 약관상 캐시하지 않는다.
"""

from __future__ import annotations

import hashlib
import json
import time
from collections import OrderedDict
from dataclasses import dataclass, field
from typing import Callable

CACHEABLE_ROUTES = {"agent", "research"}


@dataclass(frozen=True)
class CachedResponse:
    text: str
    engine: str
    model: str
    sources: list[dict] = field(default_factory=list)


class RequestGuard:
    def __init__(self, ttl_seconds: int = 600, max_entries: int = 200, clock: Callable[[], float] = time.monotonic):
        self.ttl = ttl_seconds
        self.max_entries = max_entries
        self.clock = clock
        self._in_flight: set[str] = set()
        self._cache: OrderedDict[str, tuple[float, CachedResponse]] = OrderedDict()

    @staticmethod
    def key(route: str, text: str, history: list[dict]) -> str:
        raw = json.dumps({"r": route, "t": text.strip(), "h": history}, ensure_ascii=False, sort_keys=True)
        return hashlib.sha256(raw.encode()).hexdigest()

    def try_acquire(self, key: str) -> bool:
        if key in self._in_flight:
            return False
        self._in_flight.add(key)
        return True

    def release(self, key: str) -> None:
        self._in_flight.discard(key)

    def get(self, key: str, route: str) -> CachedResponse | None:
        if route not in CACHEABLE_ROUTES or self.ttl <= 0:
            return None
        entry = self._cache.get(key)
        if entry is None:
            return None
        stored_at, value = entry
        if self.clock() - stored_at > self.ttl:
            self._cache.pop(key, None)
            return None
        self._cache.move_to_end(key)
        return value

    def put(self, key: str, route: str, value: CachedResponse) -> None:
        if route not in CACHEABLE_ROUTES or self.ttl <= 0 or not value.text.strip():
            return
        self._cache[key] = (self.clock(), value)
        self._cache.move_to_end(key)
        while len(self._cache) > self.max_entries:
            self._cache.popitem(last=False)
