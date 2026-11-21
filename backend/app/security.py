"""앱 ↔ 백엔드 인증.

PHASE 1: 단일 앱 토큰(Bearer). 토큰이 서버에 설정되지 않았으면 보호된 엔드포인트는
모두 503으로 거부한다(fail closed). 다중 사용자 인증은 PHASE 7 보안 점검에서 확장한다.
"""

from __future__ import annotations

import hmac

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from .config import Settings

_bearer = HTTPBearer(auto_error=False)


def get_settings(request: Request) -> Settings:
    return request.app.state.settings


def require_app_token(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
    settings: Settings = Depends(get_settings),
) -> None:
    if not settings.app_token:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="JARVIS_APP_TOKEN이 서버에 설정되지 않았습니다.",
        )
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="인증 토큰이 필요합니다.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    # 타이밍 공격 방지를 위한 상수 시간 비교
    if not hmac.compare_digest(credentials.credentials.encode(), settings.app_token.encode()):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="인증 토큰이 올바르지 않습니다.",
            headers={"WWW-Authenticate": "Bearer"},
        )
