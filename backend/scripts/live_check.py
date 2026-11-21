"""실제 AI API 연결 점검 (개발자용).

설정된 엔진마다 아주 짧은 요청을 한 번씩 보내 실제 응답이 오는지 확인한다. 비용이 조금 발생한다.

    python -m scripts.live_check            # .env 또는 환경변수의 키 사용
    python -m scripts.live_check agent      # 특정 점검만 (nvidia / agent / google)

결과 본문은 앞부분만 출력하고, API Key는 출력하지 않는다.
"""

from __future__ import annotations

import asyncio
import sys
import time

from dotenv import load_dotenv

from app.config import Settings
from app.engines.base import TextDelta, Usage, build_messages
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


async def check_agent(services: Services) -> None:
    started, parts = time.monotonic(), []
    prompt = "파이썬으로 매일 아침 날씨를 메일로 보내는 프로그램을 만들려고 해. 단계별 계획을 3줄로 짜줘."
    async for event in services.nvidia.stream(build_messages([], prompt), agent=True):
        if isinstance(event, TextDelta):
            parts.append(event.text)
    print(f"  모델: {services.nvidia.agent_model}")
    print(f"  응답({time.monotonic() - started:.1f}s): {''.join(parts)[:160]!r}")


CHECKS = {"nvidia": ("nvidia", check_nvidia), "agent": ("nvidia", check_agent), "google": ("gemini", check_google)}


async def main(selected: list[str]) -> int:
    load_dotenv(".env")  # backend 폴더의 .env (이미 설정된 환경변수는 덮어쓰지 않음)
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
    # Windows 콘솔(cp949)에서 ✅/❌ 같은 문자 때문에 출력 오류가 나지 않도록
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(errors="replace")
    sys.exit(asyncio.run(main(sys.argv[1:])))
