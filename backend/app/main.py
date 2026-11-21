"""JARVIS backend 진입점.

실행 (개발):
    uvicorn app.main:app --reload --env-file .env --host 0.0.0.0 --port 8000
"""

from __future__ import annotations

import logging

from fastapi import FastAPI

from . import __version__
from .config import Settings
from .routers import engines, health

logger = logging.getLogger("jarvis")


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    app = FastAPI(
        title="JARVIS Backend",
        version=__version__,
        # 운영 환경에서 API 문서 노출을 원치 않으면 docs_url=None 으로 변경
    )
    app.state.settings = settings
    app.include_router(health.router)
    app.include_router(engines.router)

    if not settings.app_token:
        logger.warning("JARVIS_APP_TOKEN이 설정되지 않아 보호된 엔드포인트가 모두 503을 반환합니다.")
    return app


app = create_app()
