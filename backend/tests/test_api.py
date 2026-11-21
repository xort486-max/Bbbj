from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.config import DEFAULT_CLAUDE_MODEL, DEFAULT_NVIDIA_BASE_URL, Settings
from app.main import create_app

TOKEN = "test-app-token"
SECRET_VALUES = ["nvapi-SECRET", "AIza-SECRET", "sk-ant-SECRET"]


def client_for(**env: str) -> TestClient:
    return TestClient(create_app(Settings.from_env(env)))


def auth(token: str = TOKEN) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


# --- /health ---------------------------------------------------------------


def test_health_is_public_and_minimal():
    response = client_for().get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "service": "jarvis-backend", "version": "0.1.0"}


# --- 인증 -------------------------------------------------------------------


def test_engines_fail_closed_without_server_token():
    response = client_for().get("/v1/engines", headers=auth())
    assert response.status_code == 503
    assert "JARVIS_APP_TOKEN" in response.json()["detail"]


@pytest.mark.parametrize(
    "headers",
    [{}, {"Authorization": "Bearer wrong"}, {"Authorization": f"Basic {TOKEN}"}],
)
def test_engines_rejects_bad_credentials(headers):
    response = client_for(JARVIS_APP_TOKEN=TOKEN).get("/v1/engines", headers=headers)
    assert response.status_code == 401


# --- 엔진 상태 ---------------------------------------------------------------


def test_engines_report_missing_configuration():
    response = client_for(JARVIS_APP_TOKEN=TOKEN).get("/v1/engines", headers=auth())
    assert response.status_code == 200
    engines = {e["id"]: e for e in response.json()["engines"]}
    assert set(engines) == {"nvidia", "google", "claude"}
    assert all(not e["configured"] for e in engines.values())
    assert "NVIDIA_API_KEY" in engines["nvidia"]["note"]
    assert "NVIDIA_MODEL" in engines["nvidia"]["note"]
    assert "GOOGLE_SEARCH_PROVIDER" in engines["google"]["note"]
    assert engines["claude"]["model"] == DEFAULT_CLAUDE_MODEL


def test_engines_configured_and_never_leak_secrets():
    response = client_for(
        JARVIS_APP_TOKEN=TOKEN,
        NVIDIA_API_KEY=SECRET_VALUES[0],
        NVIDIA_MODEL="vendor/some-chat-model",
        GOOGLE_SEARCH_PROVIDER="gemini_grounding",
        GOOGLE_API_KEY=SECRET_VALUES[1],
        ANTHROPIC_API_KEY=SECRET_VALUES[2],
    ).get("/v1/engines", headers=auth())
    assert response.status_code == 200
    engines = {e["id"]: e for e in response.json()["engines"]}
    assert all(e["configured"] for e in engines.values())
    assert engines["nvidia"]["model"] == "vendor/some-chat-model"
    assert engines["google"]["provider"] == "Gemini API · Grounding with Google Search"

    body = response.text
    for secret in SECRET_VALUES + [TOKEN]:
        assert secret not in body
        assert secret[-6:] not in body  # 부분 마스킹 노출도 금지


def test_custom_search_shows_shutdown_notice():
    response = client_for(
        JARVIS_APP_TOKEN=TOKEN,
        GOOGLE_SEARCH_PROVIDER="custom_search",
        GOOGLE_API_KEY="k",
        GOOGLE_CSE_ID="cx",
    ).get("/v1/engines", headers=auth())
    google = next(e for e in response.json()["engines"] if e["id"] == "google")
    assert google["configured"] is True
    assert "2027-01-01" in google["note"]


def test_unknown_google_provider_is_not_configured():
    response = client_for(JARVIS_APP_TOKEN=TOKEN, GOOGLE_SEARCH_PROVIDER="bing").get(
        "/v1/engines", headers=auth()
    )
    google = next(e for e in response.json()["engines"] if e["id"] == "google")
    assert google["configured"] is False
    assert google["provider"] == "미선택"


# --- 설정 -------------------------------------------------------------------


def test_settings_defaults_and_trimming():
    settings = Settings.from_env({"NVIDIA_MODEL": "  m  ", "NVIDIA_API_KEY": "   ", "CLAUDE_MODEL": ""})
    assert settings.nvidia_model == "m"
    assert settings.nvidia_api_key is None
    assert settings.nvidia_base_url == DEFAULT_NVIDIA_BASE_URL
    assert settings.claude_model == DEFAULT_CLAUDE_MODEL


def test_settings_repr_hides_secrets():
    settings = Settings.from_env({"NVIDIA_API_KEY": "nvapi-SECRET", "JARVIS_APP_TOKEN": "tok-SECRET"})
    text = repr(settings)
    assert "nvapi-SECRET" not in text
    assert "tok-SECRET" not in text
