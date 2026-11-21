"""실제 AI API 연결 점검 (개발자용).

설정된 엔진마다 아주 짧은 요청을 한 번씩 보내 실제 응답이 오는지 확인한다. 비용이 조금 발생한다.

    python -m scripts.live_check            # .env 또는 환경변수의 키 사용
    python -m scripts.live_check nvidia     # 특정 엔진만

결과 본문은 앞부분만 출력하고, API Key는 출력하지 않는다.
"""

from __future__ import annotations

import asyncio
import sys
import time

from app.config import Settings
from app.engines.base import Sources, TextDelta, Usage, build_messages
from app.services.container import Services


async def check_nvidia(services: Services) -> None:
    started, parts, usage = time.monotonic(), [], None
    async for event in services.nvidia.stream(build_messages([], "너는 누구야? 한 문장으로 답해.")):
        if isinstance(event, TextDelta):
            parts.append(event.text)
        elif isinstance(event, Usage):
            usage = event
    print(f"  모델: {services.nvidia.model}")
    print(f"  응답({time.monotonic() - started:.1f}s, 조각 {len(parts)}개): {''.join(parts)[:120]!r}")
    print(f"  사용량: {usage}")


async def check_google(services: Services) -> None:
    started = time.monotonic()
    result = await services.gemini.search("오늘 서울 날씨")
    print(f"  모델: {services.gemini.model} · 검색어: {result.queries} · 근거 검색 여부: {result.grounded}")
    print(f"  응답({time.monotonic() - started:.1f}s): {result.text[:120]!r}")
    print(f"  출처 {len(result.sources)}개: {[s.title for s in result.sources][:5]}")
    print(f"  검색 제안 HTML: {'있음' if result.search_suggestions_html else '없음'} · 토큰 {result.input_tokens}/{result.output_tokens}")


async def check_claude(services: Services) -> None:
    started, parts, usage, sources = time.monotonic(), [], None, []
    async for event in services.claude.stream(build_messages([], "JARVIS라고 한 단어로만 답해."), web_search=False):
        if isinstance(event, TextDelta):
            parts.append(event.text)
        elif isinstance(event, Usage):
            usage = event
        elif isinstance(event, Sources):
            sources.extend(event.items)
    print(f"  모델: {services.claude.model}")
    print(f"  응답({time.monotonic() - started:.1f}s): {''.join(parts)[:120]!r}")
    print(f"  사용량: {usage}")


CHECKS = {"nvidia": ("nvidia", check_nvidia), "google": ("gemini", check_google), "claude": ("claude", check_claude)}


async def main(selected: list[str]) -> int:
    settings = Settings.from_env()
    services = Services.build(settings)
    failures = 0
    try:
        for name in selected or CHECKS:
            attr, check = CHECKS[name]
            print(f"[{name}]")
            if getattr(services, attr) is None:
                print("  건너뜀: 설정되지 않음")
                continue
            try:
                await check(services)
                print("  ✅ 성공")
            except Exception as error:  # noqa: BLE001
                failures += 1
                print(f"  ❌ 실패: {type(error).__name__}: {getattr(error, 'message', error)}")
    finally:
        await services.aclose()
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main(sys.argv[1:])))
