"""模型成功返回时的行为：来源标记、落库、字段齐全、提示词基于真实数据。"""
import pytest

from config import settings
from helpers import (
    ALLERGY_TEXT,
    AI_BREAKDOWN_ENDPOINT,
    BREAKDOWN_MARKDOWN,
    GOALS_BREAKDOWN_ENDPOINT,
    JSON_ENDPOINT_IDS,
    JSON_ENDPOINTS,
    LAST_LOG_ITEM,
    SKILL_NAME,
    VISION_TEXT,
    add_goal,
    add_log,
    call,
    days_ago,
    query_all,
    row_counts,
    seed_user_data,
    valid_ask,
    valid_daily_plan,
    valid_daily_review,
    valid_reply_for,
    valid_weekly_review,
)
from models import AiMessage, Task

ASK_FIELDS = ["conclusion", "reason", "pros", "cons_risks", "alternative", "goal_relation"]
EXPECTED_MODE = {"ask": "advisor", "daily-plan": "daily_plan", "review-daily": "review", "review-weekly": "review"}


@pytest.mark.parametrize("endpoint", JSON_ENDPOINTS, ids=JSON_ENDPOINT_IDS)
def test_success_response_is_marked_as_ai_model_with_configured_model_name(client, fake_model, endpoint):
    seed_user_data(client)
    fake_model.respond(valid_reply_for(endpoint))

    res = call(client, endpoint)

    assert res.status_code == 200, res.text
    body = res.json()
    assert body["source"] == "ai_model"
    assert body["model"] == settings.AI_MODEL
    assert "offline_mock" not in res.text
    assert "test-key" not in res.text


@pytest.mark.parametrize("endpoint", [GOALS_BREAKDOWN_ENDPOINT, AI_BREAKDOWN_ENDPOINT], ids=["goals-ai-breakdown", "ai-breakdown"])
def test_breakdown_success_response_is_marked_as_ai_model(client, fake_model, endpoint):
    fake_model.respond(BREAKDOWN_MARKDOWN)

    res = call(client, endpoint)

    assert res.status_code == 200, res.text
    assert res.json()["source"] == "ai_model"
    assert res.json()["model"] == settings.AI_MODEL
    assert "offline_mock" not in res.text


@pytest.mark.parametrize("endpoint", JSON_ENDPOINTS, ids=JSON_ENDPOINT_IDS)
def test_success_adds_exactly_one_ai_message_with_source_and_model(client, fake_model, endpoint):
    seed_user_data(client)
    fake_model.respond(valid_reply_for(endpoint))
    before = len(query_all(AiMessage))

    call(client, endpoint)

    messages = query_all(AiMessage)
    assert len(messages) == before + 1
    saved = messages[-1]
    assert saved.mode == EXPECTED_MODE[endpoint[0]]
    assert saved.source == "ai_model"
    assert saved.model_name == settings.AI_MODEL
    assert saved.answer.strip()


def test_history_endpoint_exposes_source_and_model_name(client, fake_model):
    fake_model.respond(valid_ask())
    client.post("/api/ai/ask", json={"question": "今晚可以点外卖吗"})

    history = client.get("/api/ai/history").json()

    assert len(history) == 1
    assert history[0]["source"] == "ai_model"
    assert history[0]["model_name"] == settings.AI_MODEL
    assert history[0]["question"] == "今晚可以点外卖吗"


def test_ask_returns_all_six_fields_and_not_crisis(client, fake_model):
    expected = valid_ask()
    fake_model.respond(expected)

    res = client.post("/api/ai/ask", json={"question": "今晚可以点外卖吗"})

    body = res.json()
    for field in ASK_FIELDS:
        assert body[field] == expected[field]
    assert body["is_crisis"] is False
    assert body["full_text"].strip()


def test_ask_rejects_blank_question_without_calling_model(client, fake_model):
    res = client.post("/api/ai/ask", json={"question": "   "})

    assert res.status_code == 400
    assert fake_model.call_count == 0


def test_ask_calls_model_exactly_once(client, fake_model):
    fake_model.respond(valid_ask())

    client.post("/api/ai/ask", json={"question": "今晚可以点外卖吗"})

    assert fake_model.call_count == 1


# ---------------------------------------------------------------- 每日规划落库

def test_daily_plan_persists_tasks_for_today(client, fake_model):
    goal_id = add_goal(client, "完成驱动开发入门课程")
    fake_model.respond(valid_daily_plan(goal_id))

    res = client.post("/api/ai/daily-plan")

    assert res.status_code == 200, res.text
    api_tasks = client.get("/api/tasks").json()
    assert sorted(t["title"] for t in api_tasks) == ["晚间散步三十分钟", "阅读PCIe规范第四章并做笔记"]
    linked = next(t for t in api_tasks if t["title"].startswith("阅读PCIe"))
    assert linked["goal_id"] == goal_id
    assert linked["est_minutes"] == 45
    assert all(t["done"] is False for t in api_tasks)


def test_daily_plan_response_keeps_health_reminder_and_tip(client, fake_model):
    fake_model.respond(valid_daily_plan())

    body = client.post("/api/ai/daily-plan").json()

    assert body["health_reminder"] == valid_daily_plan()["health_reminder"]
    assert body["tip"] == valid_daily_plan()["tip"]
    assert len(body["tasks"]) == 2


def test_daily_plan_auto_apply_false_does_not_write_tasks(client, fake_model):
    fake_model.respond(valid_daily_plan())

    res = client.post("/api/ai/daily-plan?auto_apply=false")

    assert res.status_code == 200, res.text
    assert res.json()["source"] == "ai_model"
    assert query_all(Task) == []
    assert client.get("/api/tasks").json() == []


def test_daily_plan_does_not_duplicate_existing_same_title_task(client, fake_model):
    fake_model.respond(valid_daily_plan())

    client.post("/api/ai/daily-plan")
    client.post("/api/ai/daily-plan")

    titles = [t.title for t in query_all(Task)]
    assert sorted(titles) == sorted(set(titles))
    assert len(titles) == 2


# ---------------------------------------------------------------- 提示词基于真实数据

@pytest.mark.parametrize("endpoint", JSON_ENDPOINTS, ids=JSON_ENDPOINT_IDS)
def test_prompt_contains_users_real_profile_skill_goal_and_latest_log(client, fake_model, endpoint):
    goal_id = seed_user_data(client)
    fake_model.respond(valid_reply_for(endpoint))

    call(client, endpoint)

    prompt = fake_model.last_prompt
    assert VISION_TEXT in prompt
    assert SKILL_NAME in prompt
    assert "完成驱动开发入门课程" in prompt
    assert LAST_LOG_ITEM in prompt
    assert ALLERGY_TEXT in prompt


@pytest.mark.parametrize("endpoint", [GOALS_BREAKDOWN_ENDPOINT, AI_BREAKDOWN_ENDPOINT], ids=["goals-ai-breakdown", "ai-breakdown"])
def test_breakdown_prompt_contains_goal_title_and_user_context(client, fake_model, endpoint):
    seed_user_data(client)
    fake_model.respond(BREAKDOWN_MARKDOWN)

    call(client, endpoint)

    prompt = fake_model.last_prompt
    assert endpoint[3]["goal_title"] in prompt
    assert VISION_TEXT in prompt
    assert SKILL_NAME in prompt


def test_ask_prompt_contains_the_question_text(client, fake_model):
    seed_user_data(client)
    fake_model.respond(valid_ask())

    client.post("/api/ai/ask", json={"question": "我想换一份远程工作，合适吗"})

    assert "我想换一份远程工作，合适吗" in fake_model.last_prompt


def test_daily_review_prompt_contains_that_days_log_content(client, fake_model):
    seed_user_data(client)
    add_log(client, done_items="今天调通了SPI时序", study_note="示波器抓波形")
    fake_model.respond(valid_daily_review())

    client.post("/api/ai/review", json={"type": "daily"})

    assert "今天调通了SPI时序" in fake_model.last_prompt
    assert "示波器抓波形" in fake_model.last_prompt


def test_daily_review_with_explicit_date_uses_that_days_log(client, fake_model):
    seed_user_data(client)
    target = days_ago(3)
    add_log(client, target, done_items="三天前完成了固件烧录脚本")
    fake_model.respond(valid_daily_review())

    res = client.post("/api/ai/review", json={"type": "daily", "date": target.isoformat()})

    assert res.status_code == 200, res.text
    assert "三天前完成了固件烧录脚本" in fake_model.last_prompt


def test_weekly_review_prompt_contains_recent_week_data(client, fake_model):
    seed_user_data(client)
    add_log(client, days_ago(2), done_items="完成了DMA传输实验", study_minutes=90)
    fake_model.respond(valid_weekly_review([]))

    client.post("/api/ai/review", json={"type": "weekly"})

    assert "完成了DMA传输实验" in fake_model.last_prompt


def test_review_success_response_contains_report_fields(client, fake_model):
    fake_model.respond(valid_daily_review())

    body = client.post("/api/ai/review", json={"type": "daily"}).json()

    for field in ("summary", "highlights", "deviations", "next_actions"):
        assert body[field] == valid_daily_review()[field]
    assert body["full_text"].strip()


def test_weekly_review_success_response_contains_report_fields(client, fake_model):
    fake_model.respond(valid_weekly_review([]))

    body = client.post("/api/ai/review", json={"type": "weekly"}).json()

    for field in ("summary", "goal_progress", "biggest_deviation", "next_week_strategy"):
        assert body[field] == valid_weekly_review()[field]


def test_success_path_writes_only_one_message_and_no_tasks_for_ask(client, fake_model):
    fake_model.respond(valid_ask())
    before = row_counts()

    client.post("/api/ai/ask", json={"question": "今晚可以点外卖吗"})

    after = row_counts()
    assert after[0] == before[0] + 1
    assert after[1] == before[1]
    assert after[2] == before[2]
