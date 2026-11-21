"""AI 엔진 설정 상태 조회.

`configured`는 "필요한 설정값이 존재한다"는 뜻일 뿐, 실제 API 호출 성공을 의미하지 않는다.
실제 연결 확인(경량 호출)은 PHASE 2에서 각 엔진 모듈을 구현할 때 추가한다.
API Key 값은 어떤 형태로도(일부 마스킹 포함) 응답하지 않는다.
"""

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from ..config import Settings
from ..security import get_settings, require_app_token

router = APIRouter(prefix="/v1", tags=["engines"], dependencies=[Depends(require_app_token)])


class EngineInfo(BaseModel):
    id: str
    configured: bool
    provider: str
    model: str | None = None
    note: str | None = None


class EnginesResponse(BaseModel):
    engines: list[EngineInfo]


_GOOGLE_PROVIDER_NAMES = {
    "custom_search": "Google Custom Search JSON API",
    "vertex_ai_search": "Vertex AI Search",
    "gemini_grounding": "Gemini API · Grounding with Google Search",
}


def _note(missing: list[str]) -> str | None:
    return f"설정 필요: {', '.join(missing)}" if missing else None


@router.get("/engines", response_model=EnginesResponse)
def engines(settings: Settings = Depends(get_settings)) -> EnginesResponse:
    nvidia_missing = settings.nvidia_missing()
    google_missing = settings.google_missing()
    claude_missing = settings.claude_missing()

    google_note = _note(google_missing)
    if settings.google_search_provider == "custom_search":
        google_note = (google_note + " · " if google_note else "") + (
            "Custom Search JSON API는 2027-01-01 종료 예정입니다."
        )

    return EnginesResponse(
        engines=[
            EngineInfo(
                id="nvidia",
                configured=not nvidia_missing,
                provider="NVIDIA API Catalog",
                model=settings.nvidia_model,
                note=_note(nvidia_missing),
            ),
            EngineInfo(
                id="google",
                configured=not google_missing,
                provider=_GOOGLE_PROVIDER_NAMES.get(settings.google_search_provider or "", "미선택"),
                note=google_note,
            ),
            EngineInfo(
                id="claude",
                configured=not claude_missing,
                provider="Anthropic Claude API",
                model=settings.claude_model,
                note=_note(claude_missing),
            ),
        ]
    )
