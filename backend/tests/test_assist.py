from __future__ import annotations

import json

import httpx2
import pytest
from fastapi.testclient import TestClient

from app.engines.base import ChatTurn, build_messages
from app.main import create_app
from tests.helpers import (
    GEMINI_INTERACTION,
    TOKEN,
    Upstream,
    build_services,
    nvidia_stream,
    parse_sse,
)

NVIDIA = "integrate.api.nvidia.com"
GEMINI = "generativelanguage.googleapis.com"
AUTH = {"Authorization": f"Bearer {TOKEN}"}


def make(upstream: Upstream, **kwargs) -> tuple[TestClient, object]:
    services = build_services(upstream, **kwargs)
    return TestClient(create_app(services=services)), services


def assist(client: TestClient, route: str, text: str, history: list | None = None):
    return client.post("/v1/assist", headers=AUTH, json={"route": route, "text": text, "history": history or []})


def names(events):
    return [name for name, _ in events]


def text_of(events):
    return "".join(data["text"] for name, data in events if name == "delta")


# --- NVIDIA 대화 --------------------------------------------------------------


def test_chat_streams_nvidia_response_with_history():
    upstream = Upstream({NVIDIA: nvidia_stream("안녕하세요, ", "무엇을 도와드릴까요?",
                                               usage={"prompt_tokens": 30, "completion_tokens": 9})})
    client, services = make(upstream)
    response = assist(client, "chat", "안녕 자비스",
                      history=[{"role": "user", "content": "내 이름은 민수야"}, {"role": "assistant", "content": "반가워요 민수님"}])
    assert response.status_code == 200
    events = parse_sse(response.text)
    assert names(events)[0] == "route" and events[0][1]["engine"] == "nvidia"
    assert text_of(events) == "안녕하세요, 무엇을 도와드릴까요?"
    done = dict(events)["done"]
    assert done["input_tokens"] == 30 and done["output_tokens"] == 9 and done["cached"] is False

    sent = upstream.calls(NVIDIA)[0]
    assert sent.url.path == "/v1/chat/completions"
    assert sent.headers["authorization"] == "Bearer nvapi-test"
    body = json.loads(sent.content)
    assert body["stream"] is True and body["model"] == "nvidia/nemotron-3.5-lightning-30b-a3b"
    assert "복잡한 작업" not in body["messages"][0]["content"]  # 대화 모드에는 에이전트 지시 없음
    assert body["chat_template_kwargs"] == {"enable_thinking": False}
    assert [m["role"] for m in body["messages"]] == ["system", "user", "assistant", "user"]
    assert body["messages"][-1]["content"] == "안녕 자비스"

    usage = services.usage.summary()[0]
    assert (usage.engine, usage.requests, usage.input_tokens) == ("nvidia", 1, 30)


def test_chat_retries_transient_error_then_succeeds():
    attempts = []

    def handler(request):
        attempts.append(request)
        if len(attempts) == 1:
            return httpx2.Response(503, json={"error": {"message": "overloaded"}})
        return nvidia_stream("복구됨")(request)

    client, _ = make(Upstream({NVIDIA: handler}))
    events = parse_sse(assist(client, "chat", "테스트").text)
    assert len(attempts) == 2
    assert text_of(events) == "복구됨"


def test_chat_thinking_option_can_be_omitted_for_other_models():
    upstream = Upstream({NVIDIA: nvidia_stream("ok")})
    client, _ = make(upstream, env_overrides={"NVIDIA_MODEL": "other/model", "NVIDIA_ENABLE_THINKING": "auto"})
    assist(client, "chat", "x")
    assert "chat_template_kwargs" not in json.loads(upstream.calls(NVIDIA)[0].content)


def test_chat_retired_model_has_clear_message():
    upstream = Upstream({NVIDIA: lambda r: httpx2.Response(410, json={"detail": "reached its end of life"})})
    client, _ = make(upstream)
    error = dict(parse_sse(assist(client, "chat", "x").text))["error"]
    assert error["code"] == "model_retired" and "모델 ID" in error["message"]


def test_chat_auth_error_is_reported_without_retry():
    upstream = Upstream({NVIDIA: lambda r: httpx2.Response(401, json={"error": "invalid key"})})
    client, services = make(upstream)
    events = parse_sse(assist(client, "chat", "테스트").text)
    error = dict(events)["error"]
    assert error["code"] == "auth" and "API Key" in error["message"]
    assert "nvapi-test" not in json.dumps(events)  # 키 값은 절대 노출하지 않는다
    assert len(upstream.calls(NVIDIA)) == 1
    assert services.usage.summary()[0].errors == 1


# --- Google (Gemini Grounding) 검색 -------------------------------------------


def test_search_returns_grounded_result_unmodified_with_sources_and_suggestions():
    upstream = Upstream({GEMINI: lambda r: httpx2.Response(200, json=GEMINI_INTERACTION)})
    client, services = make(upstream)
    events = parse_sse(assist(client, "search", "유로 2024 우승팀").text)
    assert names(events) == ["route", "stage", "delta", "sources", "done"]
    assert text_of(events) == GEMINI_INTERACTION["steps"][3]["content"][0]["text"]

    sources = dict(events)["sources"]
    assert sources["provider"] == "google"
    assert [i["title"] for i in sources["items"]] == ["aljazeera.com", "uefa.com"]  # URL 중복 제거
    assert sources["queries"] == ["UEFA Euro 2024 winner"]
    assert "google.com/search" in sources["search_suggestions_html"]

    sent = upstream.calls(GEMINI)[0]
    assert sent.url.path == "/v1beta/interactions"
    assert sent.headers["x-goog-api-key"] == "gemini-test"
    assert sent.headers["api-revision"] == "2026-05-20"
    body = json.loads(sent.content)
    assert body == {"model": "gemini-4-hydrogen", "input": "유로 2024 우승팀",
                    "tools": [{"type": "google_search"}], "store": False}
    assert services.usage.summary()[1].search_queries == 1


def test_search_results_are_never_cached():
    upstream = Upstream({GEMINI: lambda r: httpx2.Response(200, json=GEMINI_INTERACTION)})
    client, _ = make(upstream)
    assist(client, "search", "같은 질문")
    assist(client, "search", "같은 질문")
    assert len(upstream.calls(GEMINI)) == 2  # 약관: 근거 결과 캐시 금지


def test_search_empty_result_is_error():
    upstream = Upstream({GEMINI: lambda r: httpx2.Response(200, json={"status": "completed", "steps": []})})
    client, _ = make(upstream)
    error = dict(parse_sse(assist(client, "search", "x").text))["error"]
    assert error["code"] == "empty"


# --- 에이전트 (NVIDIA 대형 모델) · 검색 분석 (Gemini) ---------------------------------


def test_agent_uses_nvidia_agent_model_with_reasoning_and_caches():
    upstream = Upstream({NVIDIA: nvidia_stream("1단계: ", "API 키 발급")})
    client, services = make(upstream)

    events = parse_sse(assist(client, "agent", "파이썬 자동화 프로젝트 기획해줘").text)
    route = dict(events)["route"]
    assert (route["engine"], route["model"]) == ("nvidia", "nvidia/nemotron-3-ultra-550b-a55b")
    assert ("stage", {"stage": "reasoning"}) in events
    assert text_of(events) == "1단계: API 키 발급"

    body = json.loads(upstream.calls(NVIDIA)[0].content)
    assert body["model"] == "nvidia/nemotron-3-ultra-550b-a55b"
    assert body["max_tokens"] == 8192
    assert "chat_template_kwargs" not in body  # 에이전트 모델은 기본 추론 모드 사용
    assert "복잡한 작업" in body["messages"][0]["content"]

    # 같은 요청 반복 → 캐시 응답 (재호출 없음)
    again = parse_sse(assist(client, "agent", "파이썬 자동화 프로젝트 기획해줘").text)
    assert text_of(again) == "1단계: API 키 발급" and dict(again)["done"]["cached"] is True
    assert len(upstream.calls(NVIDIA)) == 1
    nvidia_usage = services.usage.summary()[0]
    assert (nvidia_usage.requests, nvidia_usage.cached) == (2, 1)


def test_research_uses_gemini_with_analysis_instruction_and_no_cache():
    upstream = Upstream({GEMINI: lambda r: httpx2.Response(200, json=GEMINI_INTERACTION)})
    client, _ = make(upstream)
    for _ in range(2):
        events = parse_sse(assist(client, "research", "전기차 시장 동향 분석해줘").text)
        assert dict(events)["route"]["engine"] == "google"
        assert dict(events)["sources"]["provider"] == "google"
    sent = json.loads(upstream.calls(GEMINI)[0].content)
    assert sent["input"].endswith("전기차 시장 동향 분석해줘")
    assert "비교·분석" in sent["input"]
    assert len(upstream.calls(GEMINI)) == 2  # 근거 결과는 캐시하지 않음


# --- 라우팅 · 대체 처리 · 한도 · 중복 차단 ----------------------------------------


def test_research_falls_back_to_nvidia_agent_when_google_missing():
    upstream = Upstream({NVIDIA: nvidia_stream("검색 없는 답변")})
    client, _ = make(upstream, drop=("GOOGLE_API_KEY",))
    events = parse_sse(assist(client, "research", "분석해줘").text)
    assert dict(events)["route"]["engine"] == "nvidia"
    notice = dict(events)["notice"]["text"]
    assert "GOOGLE_API_KEY" in notice and "최신 정보가 아닐 수" in notice
    assert json.loads(upstream.calls(NVIDIA)[0].content)["model"] == "nvidia/nemotron-3-ultra-550b-a55b"


def test_research_falls_back_when_google_limit_reached_but_search_does_not():
    upstream = Upstream({NVIDIA: nvidia_stream("대체")})
    client, services = make(upstream, env_overrides={"DAILY_LIMIT_GOOGLE": "1"})
    services.usage.record(engine="google", model="m", route="search", status="ok")
    events = parse_sse(assist(client, "research", "분석해줘").text)
    assert "일일 한도 1회 초과" in dict(events)["notice"]["text"]
    assert assist(client, "search", "검색").status_code == 429


def test_unavailable_engine_returns_clear_http_error():
    client, _ = make(Upstream(), drop=("NVIDIA_API_KEY",))
    response = assist(client, "chat", "안녕")
    assert response.status_code == 503
    assert "NVIDIA_API_KEY" in response.json()["detail"]

    client, _ = make(Upstream(), drop=("GOOGLE_API_KEY",))
    response = assist(client, "search", "검색")
    assert response.status_code == 503 and "GOOGLE_API_KEY" in response.json()["detail"]


def test_duplicate_in_flight_request_is_rejected():
    client, services = make(Upstream({NVIDIA: nvidia_stream("ok")}))
    key = services.guard.key("chat", "중복", [])
    assert services.guard.try_acquire(key)
    response = assist(client, "chat", "중복")
    assert response.status_code == 409
    services.guard.release(key)
    assert assist(client, "chat", "중복").status_code == 200


def test_assist_requires_token_and_validates_body():
    client, _ = make(Upstream())
    assert client.post("/v1/assist", json={"route": "chat", "text": "x"}).status_code == 401
    assert client.post("/v1/assist", headers=AUTH, json={"route": "hack", "text": "x"}).status_code == 422
    assert client.post("/v1/assist", headers=AUTH, json={"route": "chat", "text": ""}).status_code == 422


def test_usage_endpoint_summarises_today():
    client, services = make(Upstream())
    services.usage.record(engine="nvidia", model="m", route="chat", status="ok", input_tokens=10, output_tokens=5)
    services.usage.record(engine="google", model="m", route="search", status="error", error_code="auth")
    data = client.get("/v1/usage", headers=AUTH).json()
    engines = {e["engine"]: e for e in data["engines"]}
    assert set(engines) == {"nvidia", "google"}
    assert engines["nvidia"]["requests"] == 1 and engines["nvidia"]["output_tokens"] == 5
    assert engines["google"]["errors"] == 1
    assert data["timezone"] == "Asia/Seoul"


# --- 대화 기록 정리 -------------------------------------------------------------


def test_build_messages_trims_history_and_starts_with_user():
    history = [ChatTurn("assistant", "인사"), *[ChatTurn("user" if i % 2 == 0 else "assistant", f"m{i}") for i in range(30)]]
    messages = build_messages(history, "현재 질문", max_turns=6)
    assert messages[0]["role"] == "user"
    assert messages[-1] == {"role": "user", "content": "현재 질문"}
    assert len(messages) <= 7

    long_history = [ChatTurn("user", "가" * 10000), ChatTurn("assistant", "짧은 답")]
    trimmed = build_messages(long_history, "질문", max_chars=5000)
    assert trimmed == [{"role": "user", "content": "질문"}]


@pytest.mark.parametrize("route", ["chat", "search", "research", "agent"])
def test_usage_is_recorded_without_content(route):
    handlers = {
        NVIDIA: nvidia_stream("비밀 답변"),
        GEMINI: lambda r: httpx2.Response(200, json=GEMINI_INTERACTION),
    }
    client, services = make(Upstream(handlers))
    assist(client, route, "개인적인 질문 내용")
    dump = "\n".join(services.usage._db.iterdump())
    assert "개인적인 질문 내용" not in dump and "비밀 답변" not in dump and "Spain" not in dump


def test_timezone_falls_back_when_tz_database_missing(monkeypatch):
    """Windows 기본 Python처럼 시간대 DB가 없어도 서버가 죽지 않아야 한다."""
    import zoneinfo
    from datetime import datetime, timezone

    from app.services import usage as usage_module

    def missing(name):
        raise zoneinfo.ZoneInfoNotFoundError(name)

    monkeypatch.setattr(usage_module, "ZoneInfo", missing)
    store = usage_module.UsageStore(":memory:", "Asia/Seoul",
                                    clock=lambda: datetime(2026, 11, 21, 16, 0, tzinfo=timezone.utc))
    assert store.today() == "2026-11-22"  # UTC 16시 = 한국 다음날 01시
    store.record(engine="nvidia", model="m", route="chat", status="ok")
    assert store.summary()[0].requests == 1


def test_quota_exhausted_is_not_retried_and_explains_billing():
    body = {"error": {"code": 429, "message": "You exceeded your current quota, please check your plan and billing details.",
                      "status": "RESOURCE_EXHAUSTED"}}
    upstream = Upstream({GEMINI: lambda r: httpx2.Response(429, json=body)})
    client, _ = make(upstream)
    error = dict(parse_sse(assist(client, "search", "날씨").text))["error"]
    assert error["code"] == "quota_exceeded" and error["retryable"] is False
    assert "결제 설정" in error["message"]
    assert len(upstream.calls(GEMINI)) == 1  # 재시도하지 않음


def test_plain_rate_limit_is_still_retried():
    calls = []

    def handler(request):
        calls.append(request)
        if len(calls) == 1:
            return httpx2.Response(429, json={"error": {"message": "Too many requests per minute"}})
        return httpx2.Response(200, json=GEMINI_INTERACTION)

    client, _ = make(Upstream({GEMINI: handler}))
    events = parse_sse(assist(client, "search", "날씨").text)
    assert "done" in names(events) and len(calls) == 2
