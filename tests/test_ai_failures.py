"""AI 失败必须明确报错、零副作用、不伪装。契约：detail = {code, message}。"""
import pytest

from helpers import (
    ALL_AI_ENDPOINTS,
    ENDPOINT_IDS,
    call,
    row_counts,
    seed_user_data,
)

ERROR_CASES = [
    ("ai_unreachable", 502),
    ("ai_auth_failed", 502),
    ("ai_rate_limited", 502),
    ("ai_bad_response", 502),
]


def _assert_error_body(res, code, status):
    assert res.status_code == status, res.text
    detail = res.json()["detail"]
    assert detail["code"] == code
    assert isinstance(detail["message"], str) and detail["message"].strip()
    body = res.text
    assert "offline_mock" not in body
    assert "test-key" not in body


@pytest.mark.parametrize("endpoint", ALL_AI_ENDPOINTS, ids=ENDPOINT_IDS)
def test_missing_key_returns_503_ai_not_configured(client, no_key, endpoint):
    res = call(client, endpoint)

    _assert_error_body(res, "ai_not_configured", 503)


@pytest.mark.parametrize("endpoint", ALL_AI_ENDPOINTS, ids=ENDPOINT_IDS)
def test_missing_key_has_zero_side_effects(client, no_key, endpoint):
    seed_user_data(client)
    before = row_counts()

    call(client, endpoint)

    assert row_counts() == before
    assert client.get("/api/ai/history").json() == []
    assert client.get("/api/tasks").json() == []


def test_missing_key_never_calls_model(client, no_key, monkeypatch):
    import ai_agent
    from model_stub import FakeModel

    stub = FakeModel().respond({"anything": 1})
    monkeypatch.setattr(ai_agent, "_call_model", stub)

    for endpoint in ALL_AI_ENDPOINTS:
        call(client, endpoint)

    assert stub.call_count == 0


@pytest.mark.parametrize("code,status", ERROR_CASES)
@pytest.mark.parametrize("endpoint", ALL_AI_ENDPOINTS, ids=ENDPOINT_IDS)
def test_model_errors_map_to_contract_status_and_code(client, fake_model, endpoint, code, status):
    fake_model.fail(code)

    res = call(client, endpoint)

    _assert_error_body(res, code, status)
    assert fake_model.call_count == 1


@pytest.mark.parametrize("code,status", ERROR_CASES)
@pytest.mark.parametrize("endpoint", ALL_AI_ENDPOINTS, ids=ENDPOINT_IDS)
def test_model_errors_have_zero_side_effects(client, fake_model, endpoint, code, status):
    seed_user_data(client)
    before = row_counts()
    fake_model.fail(code)

    call(client, endpoint)

    assert row_counts() == before
    assert client.get("/api/ai/history").json() == []
    assert client.get("/api/tasks").json() == []


@pytest.mark.parametrize("endpoint", ALL_AI_ENDPOINTS, ids=ENDPOINT_IDS)
def test_error_message_does_not_leak_user_health_data(client, fake_model, endpoint):
    seed_user_data(client)
    fake_model.fail("ai_unreachable")

    res = call(client, endpoint)

    assert "花生过敏" not in res.text
    assert "178" not in res.json()["detail"]["message"]


def test_error_response_contains_no_substitute_content(client, no_key):
    res = call(client, ALL_AI_ENDPOINTS[0])

    body = res.json()
    for field in ("conclusion", "reason", "pros", "cons_risks", "alternative", "goal_relation", "source", "full_text"):
        assert field not in body
