"""危机拦截：确定性前置筛查，覆盖所有入口，命中即短路、不调用模型、永远成功。"""
import pytest

from helpers import add_log, days_ago, query_all, row_counts, valid_ask, valid_daily_plan, valid_daily_review, valid_weekly_review
from models import AiMessage, Task

HOTLINES = ("400-161-9995", "010-82951332", "12355")

CRISIS_QUESTIONS = [
    "我最近很绝望，不想活了",
    "活着好累想死",
    "我想自杀，你觉得呢",
    "我真的活不下去",
    "我想消失",
    "我打算结束自己",
    "我想了结自己",
    "我想 自 杀",
    "想割腕",
    "站在楼顶想跳楼",
]

CRISIS_REFLECTIONS = [
    "今天什么都做不好，我真的不想活了",
    "感觉活不下去了",
    "有时候只想消失",
]

NORMAL_QUESTIONS = [
    "我今晚想熬夜看球赛，可以吗",
    "我想买一台新电脑，合适吗",
    "今晚能吃火锅吗",
    "项目快要死线了，要不要加班",
]


def _assert_crisis_payload(res):
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["is_crisis"] is True
    assert body["source"] == "crisis_safety_guard"
    for number in HOTLINES:
        assert number in res.text, f"缺少热线 {number}"
    return body


def _assert_only_crisis_messages_written():
    for message in query_all(AiMessage):
        assert message.source == "crisis_safety_guard"
        assert message.model_name is None


# ---------------------------------------------------------------- 决策顾问入口

@pytest.mark.parametrize("question", CRISIS_QUESTIONS)
def test_ask_crisis_phrasing_is_intercepted_with_hotlines(client, fake_model, question):
    res = client.post("/api/ai/ask", json={"question": question})

    body = _assert_crisis_payload(res)
    assert "必须停止规划并寻求专业支持" in body["conclusion"]
    assert "400-161-9995" in body["alternative"]


@pytest.mark.parametrize("question", CRISIS_QUESTIONS)
def test_ask_crisis_never_calls_model(client, fake_model, question):
    fake_model.respond(valid_ask())

    client.post("/api/ai/ask", json={"question": question})

    assert fake_model.call_count == 0


def test_ask_crisis_works_even_when_ai_key_is_missing(client, no_key):
    res = client.post("/api/ai/ask", json={"question": "我想自杀"})

    _assert_crisis_payload(res)


def test_ask_crisis_message_is_stored_with_guard_source_and_no_model_name(client, fake_model):
    client.post("/api/ai/ask", json={"question": "我不想活了"})

    messages = query_all(AiMessage)
    assert len(messages) == 1
    assert messages[0].mode == "advisor"
    assert messages[0].source == "crisis_safety_guard"
    assert messages[0].model_name is None
    assert messages[0].question == "我不想活了"


def test_ask_crisis_history_endpoint_reports_guard_source(client, fake_model):
    client.post("/api/ai/ask", json={"question": "我不想活了"})

    history = client.get("/api/ai/history").json()

    assert history[0]["source"] == "crisis_safety_guard"
    assert history[0]["model_name"] is None


def test_ask_crisis_on_brand_new_database_with_no_user_succeeds(fresh_client, fake_model):
    """回归：全新空库、从没建过用户，第一次就问危机问题，不得因 FOREIGN KEY 失败。"""
    res = fresh_client.post("/api/ai/ask", json={"question": "我不想活了"})

    _assert_crisis_payload(res)
    messages = query_all(AiMessage)
    assert len(messages) == 1
    assert messages[0].user_id == 1
    assert messages[0].source == "crisis_safety_guard"


@pytest.mark.parametrize("question", NORMAL_QUESTIONS)
def test_normal_question_is_not_flagged_as_crisis(client, fake_model, question):
    fake_model.respond(valid_ask())

    res = client.post("/api/ai/ask", json={"question": question})

    assert res.status_code == 200, res.text
    assert res.json()["is_crisis"] is False
    assert res.json()["source"] == "ai_model"
    assert fake_model.call_count == 1


# ---------------------------------------------------------------- 每日复盘入口

@pytest.mark.parametrize("reflection", CRISIS_REFLECTIONS)
def test_daily_review_intercepts_crisis_in_todays_reflection(client, fake_model, reflection):
    add_log(client, reflection=reflection)
    fake_model.respond(valid_daily_review())

    res = client.post("/api/ai/review", json={"type": "daily"})

    _assert_crisis_payload(res)
    assert fake_model.call_count == 0
    _assert_only_crisis_messages_written()


def test_daily_review_intercepts_crisis_in_done_items(client, fake_model):
    add_log(client, done_items="整理了遗书，想结束自己")
    fake_model.respond(valid_daily_review())

    res = client.post("/api/ai/review", json={"type": "daily"})

    _assert_crisis_payload(res)
    assert fake_model.call_count == 0


def test_daily_review_with_explicit_date_checks_that_dates_log(client, fake_model):
    target = days_ago(2)
    add_log(client, target, reflection="我想自杀")
    fake_model.respond(valid_daily_review())

    res = client.post("/api/ai/review", json={"type": "daily", "date": target.isoformat()})

    _assert_crisis_payload(res)
    assert fake_model.call_count == 0


def test_daily_review_crisis_works_when_key_is_missing(client, no_key):
    add_log(client, reflection="我不想活了")

    res = client.post("/api/ai/review", json={"type": "daily"})

    _assert_crisis_payload(res)


def test_daily_review_ignores_crisis_text_from_an_unrelated_old_day(client, fake_model):
    add_log(client, days_ago(20), reflection="那时候我想自杀，现在已经好多了")
    add_log(client, reflection="今天心情平稳")
    fake_model.respond(valid_daily_review())

    res = client.post("/api/ai/review", json={"type": "daily"})

    assert res.status_code == 200, res.text
    assert res.json().get("is_crisis") is not True
    assert res.json()["source"] == "ai_model"


def test_daily_review_without_crisis_text_reaches_model(client, fake_model):
    add_log(client, reflection="今天有点累，但完成了计划")
    fake_model.respond(valid_daily_review())

    res = client.post("/api/ai/review", json={"type": "daily"})

    assert res.status_code == 200
    assert res.json()["source"] == "ai_model"
    assert fake_model.call_count == 1


# ---------------------------------------------------------------- 每周复盘入口

@pytest.mark.parametrize("age_in_days", [0, 3, 6])
def test_weekly_review_intercepts_crisis_in_any_of_last_seven_days(client, fake_model, age_in_days):
    add_log(client, days_ago(age_in_days), reflection="我不想活了")
    fake_model.respond(valid_weekly_review([]))

    res = client.post("/api/ai/review", json={"type": "weekly"})

    _assert_crisis_payload(res)
    assert fake_model.call_count == 0
    _assert_only_crisis_messages_written()


def test_weekly_review_ignores_crisis_text_older_than_a_week(client, fake_model):
    add_log(client, days_ago(15), reflection="我不想活了")
    add_log(client, days_ago(1), reflection="状态不错")
    fake_model.respond(valid_weekly_review([]))

    res = client.post("/api/ai/review", json={"type": "weekly"})

    assert res.status_code == 200, res.text
    assert res.json().get("is_crisis") is not True
    assert fake_model.call_count == 1


def test_weekly_review_crisis_works_when_key_is_missing(client, no_key):
    add_log(client, days_ago(2), reflection="我想自杀")

    res = client.post("/api/ai/review", json={"type": "weekly"})

    _assert_crisis_payload(res)


# ---------------------------------------------------------------- 每日规划入口

@pytest.mark.parametrize("age_in_days", [0, 1, 2])
def test_daily_plan_intercepts_crisis_in_last_three_days(client, fake_model, age_in_days):
    add_log(client, days_ago(age_in_days), reflection="我不想活了")
    fake_model.respond(valid_daily_plan())
    tasks_before = len(query_all(Task))

    res = client.post("/api/ai/daily-plan")

    _assert_crisis_payload(res)
    assert fake_model.call_count == 0
    assert len(query_all(Task)) == tasks_before == 0
    _assert_only_crisis_messages_written()


def test_daily_plan_crisis_adds_no_tasks_even_when_goals_exist(client, fake_model):
    client.post("/api/goals", json={"title": "完成课程"})
    add_log(client, days_ago(1), reflection="活不下去了")
    fake_model.respond(valid_daily_plan())

    client.post("/api/ai/daily-plan")

    assert query_all(Task) == []
    assert client.get("/api/tasks").json() == []


def test_daily_plan_ignores_crisis_text_older_than_three_days(client, fake_model):
    add_log(client, days_ago(10), reflection="我不想活了")
    add_log(client, days_ago(1), reflection="还行")
    fake_model.respond(valid_daily_plan())

    res = client.post("/api/ai/daily-plan")

    assert res.status_code == 200, res.text
    assert res.json().get("is_crisis") is not True
    assert fake_model.call_count == 1
    assert len(query_all(Task)) == 2


def test_daily_plan_crisis_works_when_key_is_missing(client, no_key):
    add_log(client, reflection="我想自杀")

    res = client.post("/api/ai/daily-plan")

    _assert_crisis_payload(res)


def test_crisis_interception_does_not_change_row_counts_beyond_guard_messages(client, fake_model):
    add_log(client, reflection="我不想活了")
    before_messages, before_tasks, before_goals = row_counts()

    client.post("/api/ai/daily-plan")
    client.post("/api/ai/review", json={"type": "daily"})
    client.post("/api/ai/review", json={"type": "weekly"})

    after_messages, after_tasks, after_goals = row_counts()
    assert after_tasks == before_tasks
    assert after_goals == before_goals
    assert after_messages >= before_messages
    _assert_only_crisis_messages_written()
