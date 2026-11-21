"""JARVIS backend 진입점.

실행 (개발):
    uvicorn app.main:app --reload --env-file .env --host 0.0.0.0 --port 8000
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from . import __version__
from .config import Settings
from .routers import assist, engines, health, usage
from .services.container import Services

logger = logging.getLogger("jarvis")


def create_app(settings: Settings | None = None, services: Services | None = None) -> FastAPI:
    settings = settings or (services.settings if services else Settings.from_env())

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if getattr(app.state, "services", None) is None:
            app.state.services = Services.build(settings)
        yield
        await app.state.services.aclose()

    app = FastAPI(title="JARVIS Backend", version=__version__, lifespan=lifespan)
    app.state.settings = settings
    app.state.services = services
    app.include_router(health.router)
    app.include_router(engines.router)
    app.include_router(assist.router)
    app.include_router(usage.router)

    if not settings.app_token:
        logger.warning("JARVIS_APP_TOKEN이 설정되지 않아 보호된 엔드포인트가 모두 503을 반환합니다.")
    return app


app = create_app()
