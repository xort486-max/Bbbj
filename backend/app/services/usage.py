"""API 사용량 기록 (SQLite).

요청마다 엔진·모델·경로·토큰·검색 횟수·지연 시간·결과를 기록한다.
**질문·답변 본문은 저장하지 않는다** (개인정보 최소화, Gemini 근거 결과 저장 금지 약관 준수).
"""

from __future__ import annotations

import logging
import sqlite3
import threading
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone, tzinfo
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

logger = logging.getLogger("jarvis.usage")

# 시간대 DB가 없는 환경(Windows 기본 설치 등)을 위한 대체 고정 시차
_FALLBACK_OFFSETS = {"Asia/Seoul": 9, "Asia/Tokyo": 9, "UTC": 0, "Etc/UTC": 0}


def resolve_timezone(name: str) -> tzinfo:
    """IANA 시간대를 찾는다. Windows처럼 시간대 DB가 없으면(tzdata 미설치) 고정 시차로 대체한다."""
    try:
        return ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError):
        hours = _FALLBACK_OFFSETS.get(name, 0)
        logger.warning("시간대 '%s'를 찾을 수 없어 UTC%+d 고정 시차를 사용합니다 (pip install tzdata 권장)", name, hours)
        return timezone(timedelta(hours=hours), name)

SCHEMA = """
CREATE TABLE IF NOT EXISTS usage_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts TEXT NOT NULL,             -- UTC ISO8601
    day TEXT NOT NULL,            -- 설정 시간대 기준 날짜 (YYYY-MM-DD)
    engine TEXT NOT NULL,         -- nvidia | google
    model TEXT NOT NULL,
    route TEXT NOT NULL,          -- chat | search | research | agent
    status TEXT NOT NULL,         -- ok | error | cancelled | cached
    input_tokens INTEGER,
    output_tokens INTEGER,
    search_queries INTEGER NOT NULL DEFAULT 0,
    latency_ms INTEGER NOT NULL DEFAULT 0,
    error_code TEXT
);
CREATE INDEX IF NOT EXISTS idx_usage_day_engine ON usage_log(day, engine);
"""


@dataclass(frozen=True)
class EngineUsage:
    engine: str
    requests: int
    errors: int
    cached: int
    input_tokens: int
    output_tokens: int
    search_queries: int


class UsageStore:
    def __init__(self, path: str, tz: str = "Asia/Seoul", clock=lambda: datetime.now(timezone.utc)):
        self.tz = resolve_timezone(tz)
        self.clock = clock
        self._lock = threading.Lock()
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.executescript(SCHEMA)

    def today(self) -> str:
        return self.clock().astimezone(self.tz).date().isoformat()

    def record(
        self,
        *,
        engine: str,
        model: str,
        route: str,
        status: str,
        input_tokens: int | None = None,
        output_tokens: int | None = None,
        search_queries: int = 0,
        latency_ms: int = 0,
        error_code: str | None = None,
    ) -> None:
        now = self.clock()
        with self._lock:
            self._db.execute(
                "INSERT INTO usage_log (ts, day, engine, model, route, status, input_tokens, output_tokens,"
                " search_queries, latency_ms, error_code) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                (
                    now.isoformat(),
                    now.astimezone(self.tz).date().isoformat(),
                    engine,
                    model,
                    route,
                    status,
                    input_tokens,
                    output_tokens,
                    search_queries,
                    latency_ms,
                    error_code,
                ),
            )
            self._db.commit()

    def requests_today(self, engine: str) -> int:
        """한도 계산용: 오늘 실제로 API를 호출한 요청 수 (캐시 응답 제외)."""
        with self._lock:
            row = self._db.execute(
                "SELECT COUNT(*) FROM usage_log WHERE day=? AND engine=? AND status != 'cached'",
                (self.today(), engine),
            ).fetchone()
        return int(row[0])

    def summary(self, day: str | None = None) -> list[EngineUsage]:
        day = day or self.today()
        with self._lock:
            rows = self._db.execute(
                "SELECT engine, COUNT(*), SUM(status='error'), SUM(status='cached'),"
                " COALESCE(SUM(input_tokens),0), COALESCE(SUM(output_tokens),0), COALESCE(SUM(search_queries),0)"
                " FROM usage_log WHERE day=? GROUP BY engine",
                (day,),
            ).fetchall()
        by_engine = {r[0]: EngineUsage(r[0], r[1], r[2] or 0, r[3] or 0, r[4], r[5], r[6]) for r in rows}
        return [by_engine.get(e, EngineUsage(e, 0, 0, 0, 0, 0, 0)) for e in ("nvidia", "google")]

    def close(self) -> None:
        with self._lock:
            self._db.close()
