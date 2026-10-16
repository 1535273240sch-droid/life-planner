"""模型返回内容的校验：无法解析/缺字段 -> ai_bad_response（零副作用）；合法变体 -> 成功。"""
import json

import pytest

from helpers import (
    AI_BREAKDOWN_ENDPOINT,
    BREAKDOWN_MARKDOWN,
    GOALS_BREAKDOWN_ENDPOINT,
    JSON_ENDPOINT_IDS,
    JSON_ENDPOINTS,
    add_goal,
    call,
    delete_goal,
    query_all,
    row_counts,
    seed_user_data,
    valid_ask,
    valid_daily_plan,
    valid_reply_for,
)
from models import Task

UNPARSEABLE_REPLIES = [
    pytest.param("", id="empty-string"),
    pytest.param("   \n  ", id="whitespace-only"),
    pytest.param("这不是 JSON，只是一段闲聊。", id="plain-prose"),
    pytest.param("{'conclusion': '建议做'", id="truncated-json"),
    pytest.param("\u00e6\u0096\u0087\u00e5\u00ad\u0097\u00e4\u00b9\u00b1\u00e7\u00a0\u0081 \ufffd\ufffd{{", id="mojibake"),
    pytest.param("[]", id="json-array-not-object"),
    pytest.param("null", id="json-null"),
    pytest.param('"just a string"', id="json-string"),
]


def _assert_bad_response_without_side_effects(client, res, before):
    assert res.status_code == 502, res.text
    assert res.json()["detail"]["code"] == "ai_bad_response"
    assert res.json()["detail"]["message"].strip()
    assert row_counts() == before
    assert client.get("/api/ai/history").json() == []
    assert client.get("/api/tasks").json() == []


@pytest.mark.parametrize("raw", UNPARSEABLE_REPLIES)
@pytest.mark.parametrize("endpoint", JSON_ENDPOINTS, ids=JSON_ENDPOINT_IDS)
def test_unparseable_model_output_is_bad_response(client, fake_model, endpoint, raw):
    seed_user_data(client)
    before = row_counts()
    fake_model.respond(raw)

    res = call(client, endpoint)

    _assert_bad_response_without_side_effects(client, res, before)


@pytest.mark.parametrize("raw", ["", "   \n "], ids=["empty", "whitespace"])
@pytest.mark.parametrize("endpoint", [GOALS_BREAKDOWN_ENDPOINT, AI_BREAKDOWN_ENDPOINT], ids=["goals-ai-breakdown", "ai-breakdown"])
def test_empty_breakdown_output_is_bad_response(client, fake_model, endpoint, raw):
    before = row_counts()
    fake_model.respond(raw)

    res = call(client, endpoint)

    _assert_bad_response_without_side_effects(client, res, before)


MISSING_FIELD_CASES = [
    pytest.param("ask", "reason", id="ask-missing-reason"),
    pytest.param("ask", "conclusion", id="ask-missing-conclusion"),
    pytest.param("ask", "goal_relation", id="ask-missing-goal_relation"),
    pytest.param("daily-plan", "tasks", id="plan-missing-tasks"),
    pytest.param("review-daily", "summary", id="daily-review-missing-summary"),
    pytest.param("review-weekly", "summary", id="weekly-review-missing-summary"),
]


@pytest.mark.parametrize("endpoint_name,missing", MISSING_FIELD_CASES)
def test_reply_missing_required_field_is_bad_response(client, fake_model, endpoint_name, missing):
    endpoint = next(e for e in JSON_ENDPOINTS if e[0] == endpoint_name)
    seed_user_data(client)
    before = row_counts()
    payload = dict(valid_reply_for(endpoint))
    payload.pop(missing)
    fake_model.respond(payload)

    res = call(client, endpoint)

    _assert_bad_response_without_side_effects(client, res, before)


@pytest.mark.parametrize("field", ["reason", "pros", "cons_risks", "alternative", "goal_relation"])
def test_ask_blank_required_field_is_bad_response(client, fake_model, field):
    before = row_counts()
    payload = valid_ask()
    payload[field] = ""
    fake_model.respond(payload)

    res = client.post("/api/ai/ask", json={"question": "今晚可以点外卖吗"})

    _assert_bad_response_without_side_effects(client, res, before)


@pytest.mark.parametrize("conclusion", ["随便吧", "也许", "yes", ""])
def test_ask_conclusion_outside_three_choices_is_never_passed_through(client, fake_model, conclusion):
    payload = valid_ask()
    payload["conclusion"] = conclusion
    fake_model.respond(payload)

    res = client.post("/api/ai/ask", json={"question": "今晚可以点外卖吗"})

    if res.status_code == 200:
        assert res.json()["conclusion"] in ("建议做", "不建议做", "可以但有条件")
    else:
        assert res.status_code == 502
        assert res.json()["detail"]["code"] == "ai_bad_response"
        assert client.get("/api/ai/history").json() == []


@pytest.mark.parametrize("conclusion", ["建议做", "不建议做", "可以但有条件"])
def test_ask_accepts_each_of_the_three_conclusions(client, fake_model, conclusion):
    payload = valid_ask()
    payload["conclusion"] = conclusion
    fake_model.respond(payload)

    res = client.post("/api/ai/ask", json={"question": "今晚可以点外卖吗"})

    assert res.status_code == 200, res.text
    assert res.json()["conclusion"] == conclusion


@pytest.mark.parametrize("wrapper", ["```json\n{body}\n```", "```\n{body}\n```", "\n\n```json\n{body}\n```\n"], ids=["json-fence", "bare-fence", "padded-fence"])
@pytest.mark.parametrize("endpoint", JSON_ENDPOINTS, ids=JSON_ENDPOINT_IDS)
def test_fenced_json_is_accepted(client, fake_model, endpoint, wrapper):
    seed_user_data(client)
    body = json.dumps(valid_reply_for(endpoint), ensure_ascii=False)
    fake_model.respond(wrapper.format(body=body))

    res = call(client, endpoint)

    assert res.status_code == 200, res.text
    assert res.json()["source"] == "ai_model"


@pytest.mark.parametrize("endpoint", JSON_ENDPOINTS, ids=JSON_ENDPOINT_IDS)
def test_fenced_json_with_surrounding_commentary_is_success_or_bad_response(client, fake_model, endpoint):
    seed_user_data(client)
    before = row_counts()
    body = json.dumps(valid_reply_for(endpoint), ensure_ascii=False)
    fake_model.respond(f"好的，下面是结果：\n```json\n{body}\n```\n希望对你有帮助。")

    res = call(client, endpoint)

    if res.status_code == 200:
        assert res.json()["source"] == "ai_model"
    else:
        _assert_bad_response_without_side_effects(client, res, before)


@pytest.mark.parametrize("endpoint", [GOALS_BREAKDOWN_ENDPOINT, AI_BREAKDOWN_ENDPOINT], ids=["goals-ai-breakdown", "ai-breakdown"])
def test_breakdown_markdown_is_parsed_into_stages(client, fake_model, endpoint):
    fake_model.respond(BREAKDOWN_MARKDOWN)

    res = call(client, endpoint)

    assert res.status_code == 200, res.text
    data = res.json()
    assert data["source"] == "ai_model"
    assert data["plan"].strip() == BREAKDOWN_MARKDOWN.strip()
    assert [s["title"] for s in data["stages"]] == ["阶段一：基础筑底 (第 1-4 周)", "阶段二：实战攻坚 (第 5-8 周)"]
    assert [len(s["weekly_goals"]) for s in data["stages"]] == [2, 2]


# ---------------------------------------------------------------- daily-plan 字段规整

def _plan_with_tasks(tasks):
    plan = valid_daily_plan()
    plan["tasks"] = tasks
    return plan


def test_plan_goal_id_of_nonexistent_goal_is_nulled(client, fake_model):
    fake_model.respond(_plan_with_tasks([{"title": "读一章规范", "goal_id": 987654, "goal_name": "幽灵目标", "est_minutes": 20}]))

    res = client.post("/api/ai/daily-plan")

    assert res.status_code == 200, res.text
    tasks = query_all(Task)
    assert [t.title for t in tasks] == ["读一章规范"]
    assert tasks[0].goal_id is None


def test_plan_goal_id_of_soft_deleted_goal_is_nulled(client, fake_model):
    goal_id = add_goal(client, "已经放弃的目标")
    delete_goal(client, goal_id)
    fake_model.respond(_plan_with_tasks([{"title": "继续推进旧目标", "goal_id": goal_id, "est_minutes": 20}]))

    res = client.post("/api/ai/daily-plan")

    assert res.status_code == 200, res.text
    assert [t.goal_id for t in query_all(Task)] == [None]


def test_plan_keeps_goal_id_of_live_goal(client, fake_model):
    goal_id = add_goal(client, "还在进行的目标")
    fake_model.respond(_plan_with_tasks([{"title": "推进目标", "goal_id": goal_id, "est_minutes": 40}]))

    client.post("/api/ai/daily-plan")

    task = query_all(Task)[0]
    assert task.goal_id == goal_id
    assert task.est_minutes == 40


def test_plan_missing_est_minutes_defaults_to_30(client, fake_model):
    fake_model.respond(_plan_with_tasks([{"title": "没有写时长的任务", "goal_id": None}]))

    res = client.post("/api/ai/daily-plan")

    assert res.status_code == 200, res.text
    assert query_all(Task)[0].est_minutes == 30
