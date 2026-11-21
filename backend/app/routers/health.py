from fastapi import APIRouter
from pydantic import BaseModel

from .. import __version__

router = APIRouter(tags=["health"])


class HealthResponse(BaseModel):
    status: str
    service: str
    version: str


@router.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    """생존 확인. 인증 불필요, 설정·비밀 정보는 노출하지 않는다."""
    return HealthResponse(status="ok", service="jarvis-backend", version=__version__)
