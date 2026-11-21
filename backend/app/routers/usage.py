"""`GET /v1/usage` — 오늘의 엔진별 API 사용량 (앱의 사용량 표시용)."""

from dataclasses import asdict

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from ..security import require_app_token
from ..services.container import Services
from .assist import get_services

router = APIRouter(prefix="/v1", tags=["usage"], dependencies=[Depends(require_app_token)])


class EngineUsageOut(BaseModel):
    engine: str
    requests: int
    errors: int
    cached: int
    input_tokens: int
    output_tokens: int
    search_queries: int
    daily_limit: int


class UsageResponse(BaseModel):
    day: str
    timezone: str
    engines: list[EngineUsageOut]


@router.get("/usage", response_model=UsageResponse)
def usage(services: Services = Depends(get_services)) -> UsageResponse:
    return UsageResponse(
        day=services.usage.today(),
        timezone=services.settings.timezone,
        engines=[
            EngineUsageOut(**asdict(u), daily_limit=services.settings.daily_limit(u.engine))
            for u in services.usage.summary()
        ],
    )
