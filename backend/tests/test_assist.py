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
    claude_message_stream,
    nvidia_stream,
    parse_sse,
    respond_sse,
)

NVIDIA = "integrate.api.nvidia.com"
GEMINI = "generativelanguage.googleapis.com"
ANTHROPIC = "api.anthropic.com"
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


# --- Claude (agent / research) ------------------------------------------------


def test_agent_streams_claude_with_fallback_opt_in_and_caches():
    body = claude_message_stream([{"type": "text", "pieces": ["프로젝트 ", "계획입니다."]}])
    upstream = Upstream({ANTHROPIC: respond_sse(body)})
    client, services = make(upstream)

    events = parse_sse(assist(client, "agent", "파이썬 자동화 프로젝트 기획해줘").text)
    assert dict(events)["route"]["engine"] == "claude"
    assert ("stage", {"stage": "reasoning", "detail": None}) in events
    assert text_of(events) == "프로젝트 계획입니다."
    done = dict(events)["done"]
    assert (done["input_tokens"], done["output_tokens"], done["cached"]) == (12, 34, False)

    sent = upstream.calls(ANTHROPIC)[0]
    assert "server-side-fallback-2026-07-01" in sent.headers["anthropic-beta"]
    request_body = json.loads(sent.content)
    assert request_body["model"] == "claude-opus-5-5"
    assert request_body["fallbacks"] == "default"
    assert request_body["output_config"] == {"effort": "medium"}
    assert request_body["stream"] is True
    assert "tools" not in request_body

    # 같은 요청 반복 → 캐시 응답 (Claude 재호출 없음)
    again = parse_sse(assist(client, "agent", "파이썬 자동화 프로젝트 기획해줘").text)
    assert text_of(again) == "프로젝트 계획입니다." and dict(again)["done"]["cached"] is True
    assert len(upstream.calls(ANTHROPIC)) == 1
    claude_usage = services.usage.summary()[2]
    assert (claude_usage.requests, claude_usage.cached) == (2, 1)


def test_research_uses_claude_web_search_and_reports_sources():
    body = claude_message_stream(
        [
            {"type": "server_tool_use", "id": "srvtoolu_1", "query": "2026 전기차 판매량"},
            {"type": "web_search_tool_result", "tool_use_id": "srvtoolu_1", "content": [
                {"type": "web_search_result", "url": "https://news.example.com/ev", "title": "EV 판매 동향",
                 "encrypted_content": "enc", "page_age": None},
            ]},
            {"type": "text", "pieces": ["분석 결과: 증가 추세입니다."]},
        ],
        web_search_requests=1,
    )
    upstream = Upstream({ANTHROPIC: respond_sse(body)})
    client, _ = make(upstream)
    events = parse_sse(assist(client, "research", "올해 전기차 판매량 분석해줘").text)

    stages = [d["stage"] for n, d in events if n == "stage"]
    assert stages == ["reasoning", "searching", "responding"]
    sources = dict(events)["sources"]
    assert sources["provider"] == "claude_web_search"
    assert sources["items"] == [{"title": "EV 판매 동향", "url": "https://news.example.com/ev"}]
    assert dict(events)["done"]["search_queries"] == 1
    tools = json.loads(upstream.calls(ANTHROPIC)[0].content)["tools"]
    assert tools == [{"type": "web_search_20260209", "name": "web_search", "max_uses": 5}]


def test_claude_refusal_becomes_error_and_is_not_cached():
    upstream = Upstream({ANTHROPIC: respond_sse(claude_message_stream([], stop_reason="refusal"))})
    client, _ = make(upstream)
    for _ in range(2):
        error = dict(parse_sse(assist(client, "agent", "거절될 요청").text))["error"]
        assert error["code"] == "refusal"
    assert len(upstream.calls(ANTHROPIC)) == 2


def test_claude_pause_turn_is_continued():
    bodies = [
        claude_message_stream([{"type": "text", "pieces": ["1부 "]}], stop_reason="pause_turn"),
        claude_message_stream([{"type": "text", "pieces": ["2부"]}]),
    ]
    upstream = Upstream({ANTHROPIC: lambda r: respond_sse(bodies[len(upstream.calls(ANTHROPIC)) - 1])(r)})
    client, _ = make(upstream)
    events = parse_sse(assist(client, "research", "긴 조사").text)
    assert text_of(events) == "1부 2부"
    second = json.loads(upstream.calls(ANTHROPIC)[1].content)
    assert second["messages"][-1]["role"] == "assistant"  # 이어받기: 이전 assistant 내용을 다시 보냄
    assert dict(events)["done"]["input_tokens"] == 24


# --- 라우팅 · 대체 처리 · 한도 · 중복 차단 ----------------------------------------


def test_research_falls_back_to_google_when_claude_missing():
    upstream = Upstream({GEMINI: lambda r: httpx2.Response(200, json=GEMINI_INTERACTION)})
    client, _ = make(upstream, drop=("ANTHROPIC_API_KEY",))
    events = parse_sse(assist(client, "research", "분석해줘").text)
    assert dict(events)["route"]["engine"] == "google"
    assert "ANTHROPIC_API_KEY" in dict(events)["notice"]["text"]


def test_agent_falls_back_to_nvidia_when_claude_limit_reached():
    upstream = Upstream({NVIDIA: nvidia_stream("NVIDIA 대체 응답")})
    client, services = make(upstream, env_overrides={"DAILY_LIMIT_CLAUDE": "1"})
    services.usage.record(engine="claude", model="m", route="agent", status="ok")
    events = parse_sse(assist(client, "agent", "기획해줘").text)
    assert dict(events)["route"]["engine"] == "nvidia"
    assert "일일 한도 1회 초과" in dict(events)["notice"]["text"]


def test_unavailable_engine_returns_clear_http_error():
    client, _ = make(Upstream(), drop=("NVIDIA_API_KEY",))
    response = assist(client, "chat", "안녕")
    assert response.status_code == 503
    assert "NVIDIA_API_KEY" in response.json()["detail"]

    client, services = make(Upstream(), env_overrides={"DAILY_LIMIT_GOOGLE": "1"})
    services.usage.record(engine="google", model="m", route="search", status="ok")
    response = assist(client, "search", "검색")
    assert response.status_code == 429


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
    services.usage.record(engine="claude", model="m", route="agent", status="error", error_code="auth")
    data = client.get("/v1/usage", headers=AUTH).json()
    engines = {e["engine"]: e for e in data["engines"]}
    assert engines["nvidia"]["requests"] == 1 and engines["nvidia"]["output_tokens"] == 5
    assert engines["claude"]["errors"] == 1
    assert engines["google"]["requests"] == 0
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
        ANTHROPIC: respond_sse(claude_message_stream([{"type": "text", "pieces": ["비밀 답변"]}])),
    }
    client, services = make(Upstream(handlers))
    assist(client, route, "개인적인 질문 내용")
    dump = "\n".join(services.usage._db.iterdump())
    assert "개인적인 질문 내용" not in dump and "비밀 답변" not in dump and "Spain" not in dump
