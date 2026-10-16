"""缺口检测、日志 upsert、连续记录、趋势接口、档案初始化、CORS。全部跑在隔离库上。"""
import datetime

import pytest

import database
from helpers import add_goal, add_log, days_ago
from schemas import DailyLogCreate
from services import log_service, profile_service, stats_service


# ---------------------------------------------------------------- 睡眠解析

@pytest.mark.parametrize("start,end", [(None, None), ("", ""), ("invalid", "none"), ("23:00", None), (None, "07:00")])
def test_parse_sleep_hours_returns_none_for_missing_or_malformed(start, end):
    assert stats_service.parse_sleep_hours(start, end) is None


@pytest.mark.parametrize("start,end,hours", [("23:00", "07:30", 8.5), ("01:00", "07:00", 6.0), ("22:30", "06:30", 8.0), ("00:00", "08:00", 8.0)])
def test_parse_sleep_hours_handles_overnight_and_same_day(start, end, hours):
    assert stats_service.parse_sleep_hours(start, end) == hours


# ---------------------------------------------------------------- 缺口检测

def _make_logs(db, days, **fields):
    for i in range(days):
        log_service.upsert_daily_log(db, 1, DailyLogCreate(date=days_ago(i), **fields))


def test_gap_detection_with_no_logs_reports_early_stage_notice(db):
    gaps = stats_service.detect_life_gaps(db, 1)

    assert [g["category"] for g in gaps] == ["系统记录"]


def test_five_days_without_sleep_records_triggers_missing_sleep_gap(db):
    _make_logs(db, 5, done_items="工作", study_minutes=60, meals={"lunch": "青菜鸡胸肉"})

    gaps = stats_service.detect_life_gaps(db, 1)

    titles = [g["title"] for g in gaps if g["category"] == "睡眠与体能"]
    assert any("连续多天未记录作息睡眠" in t for t in titles)


def test_recorded_sleep_does_not_trigger_missing_sleep_gap(db):
    _make_logs(db, 5, done_items="工作", study_minutes=60, sleep_start="23:00", sleep_end="07:00")

    gaps = stats_service.detect_life_gaps(db, 1)

    assert not any("连续多天未记录作息睡眠" in g["title"] for g in gaps)


def test_short_sleep_triggers_sleep_debt_warning(db):
    _make_logs(db, 5, done_items="工作", sleep_start="02:00", sleep_end="07:00")

    gaps = stats_service.detect_life_gaps(db, 1)

    assert any(g["category"] == "睡眠与体能" and "睡眠负债" in g["title"] for g in gaps)


def test_long_titled_goal_with_matching_log_keyword_is_not_reported_stagnant(db):
    goal = add_goal_direct(db, "攻坚「雅思7分」核心里程碑拆解与执行", level="stage", category="语言", progress=20)
    for i in range(3):
        log_service.upsert_daily_log(db, 1, DailyLogCreate(
            date=days_ago(i), done_items="今天认真做了雅思听力和阅读练习，背了200个核心词汇",
            study_minutes=90, sleep_start="23:30", sleep_end="07:30"))

    gaps = stats_service.detect_life_gaps(db, 1)

    assert [g for g in gaps if g["category"] == "目标停滞" and "雅思7分" in g["title"]] == []
    assert goal.id


def test_genuinely_neglected_goal_is_reported_stagnant(db):
    add_goal_direct(db, "学习古希腊哲学原著精读", level="stage", category="文化")
    _make_logs(db, 3, done_items="写代码", study_minutes=90, sleep_start="23:30", sleep_end="07:30")

    gaps = stats_service.detect_life_gaps(db, 1)

    assert len([g for g in gaps if g["category"] == "目标停滞" and "古希腊哲学" in g["title"]]) == 1


def test_completed_task_prevents_stagnation_warning(client, db):
    goal_id = add_goal(client, "学习古希腊哲学原著精读", level="stage")
    _make_logs(db, 3, done_items="写代码", study_minutes=90, sleep_start="23:30", sleep_end="07:30")
    res = client.post("/api/tasks", json={"title": "读第一章", "goal_id": goal_id, "done": True})
    assert res.status_code == 200

    gaps = client.get("/api/ai/gaps").json()

    assert not any(g["category"] == "目标停滞" for g in gaps)


def add_goal_direct(db, title, **fields):
    from schemas import GoalCreate
    from services import goal_service

    return goal_service.create_goal(db, 1, GoalCreate(title=title, **fields))


# ---------------------------------------------------------------- 日志 upsert 与 streak

def test_saving_log_twice_for_same_date_updates_instead_of_duplicating(client):
    today = datetime.date.today().isoformat()
    first = client.post("/api/logs", json={"date": today, "study_minutes": 30, "reflection": "初稿"}).json()

    second = client.post("/api/logs", json={"date": today, "study_minutes": 90, "reflection": "改稿"}).json()

    assert second["id"] == first["id"]
    saved = client.get(f"/api/logs?date={today}").json()
    assert saved["study_minutes"] == 90
    assert saved["reflection"] == "改稿"
    assert len(client.get("/api/logs/recent?days=7").json()) == 1


def test_log_roundtrip_preserves_all_fields(client):
    today = datetime.date.today().isoformat()
    payload = {
        "date": today, "done_items": "1. 写了测试\n2. 修了缺陷", "study_minutes": 120, "study_note": "学习SQLAlchemy 2.0",
        "meals": {"breakfast": "鸡蛋全麦面包", "lunch": "清蒸鱼配西兰花"}, "sleep_start": "23:30", "sleep_end": "07:30",
        "exercise_type": "力量训练", "exercise_minutes": 40, "mood": 5, "energy": 4, "reflection": "状态不错",
    }
    assert client.post("/api/logs", json=payload).status_code == 200

    saved = client.get(f"/api/logs?date={today}").json()

    for key, value in payload.items():
        assert saved[key] == value


@pytest.mark.parametrize("field,value", [("mood", 0), ("mood", 6), ("energy", 0), ("energy", 6)])
def test_log_rejects_out_of_range_mood_and_energy(client, field, value):
    res = client.post("/api/logs", json={field: value})

    assert res.status_code == 422


def test_get_log_for_date_without_record_returns_null(client):
    res = client.get("/api/logs?date=2001-01-01")

    assert res.status_code == 200
    assert res.json() is None


def test_streak_is_zero_without_logs(client):
    assert client.get("/api/stats/overview").json()["streak"] == 0


def test_streak_counts_consecutive_days_ending_today(client):
    for n in (0, 1, 2):
        add_log(client, days_ago(n))

    assert client.get("/api/stats/overview").json()["streak"] == 3


def test_streak_survives_when_today_not_yet_logged(client):
    for n in (1, 2):
        add_log(client, days_ago(n))

    assert client.get("/api/stats/overview").json()["streak"] == 2


def test_streak_stops_at_first_gap(client):
    for n in (0, 1, 3, 4):
        add_log(client, days_ago(n))

    assert client.get("/api/stats/overview").json()["streak"] == 2


# ---------------------------------------------------------------- 趋势接口

TREND_FIELDS = ["labels", "study_minutes", "sleep_hours", "mood", "energy"]


@pytest.mark.parametrize("query,points", [("", 30), ("?days=7", 7), ("?days=14", 14), ("?days=90", 90)])
def test_trends_returns_requested_number_of_points_per_series(client, query, points):
    body = client.get(f"/api/stats/trends{query}").json()

    for field in TREND_FIELDS:
        assert len(body[field]) == points


@pytest.mark.parametrize("days", [6, 91, 0, -3])
def test_trends_rejects_days_out_of_range(client, days):
    assert client.get(f"/api/stats/trends?days={days}").status_code == 422


def test_trends_places_logged_values_on_the_right_day(client):
    add_log(client, days_ago(1), study_minutes=77, mood=4, energy=2, sleep_start="23:00", sleep_end="07:00")

    body = client.get("/api/stats/trends?days=7").json()

    assert body["study_minutes"][-2] == 77
    assert body["mood"][-2] == 4
    assert body["energy"][-2] == 2
    assert body["sleep_hours"][-2] == 8.0
    assert body["labels"][-1] == datetime.date.today().strftime("%m-%d")


def test_trends_fills_unlogged_days_with_zero_or_null(client):
    body = client.get("/api/stats/trends?days=7").json()

    assert body["study_minutes"] == [0] * 7
    assert body["sleep_hours"] == [None] * 7
    assert body["mood"] == [None] * 7


# ---------------------------------------------------------------- 档案与技能

def test_new_profile_does_not_seed_fake_health_data(fresh_db):
    profile = profile_service.get_or_create_profile(fresh_db, 7777)

    assert profile.health_info == {}
    assert profile.occupation == ""
    assert profile.vision == ""


def test_get_profile_creates_default_user_when_database_is_empty(fresh_client):
    res = fresh_client.get("/api/profile")

    assert res.status_code == 200
    assert res.json()["user_id"] == 1


def test_profile_update_roundtrip(client):
    payload = {"age": 29, "occupation": "架构师", "vision": "成为顶尖工程师", "health_info": {"height": 178, "allergy_or_disease": "无"}}

    assert client.put("/api/profile", json=payload).status_code == 200

    saved = client.get("/api/profile").json()
    for key, value in payload.items():
        assert saved[key] == value


def test_skill_crud_roundtrip(client):
    created = client.post("/api/skills", json={"name": "Python", "category": "编程", "level": 9}).json()

    assert any(s["id"] == created["id"] for s in client.get("/api/skills").json())
    updated = client.put(f"/api/skills/{created['id']}", json={"level": 8}).json()
    assert updated["level"] == 8
    assert client.delete(f"/api/skills/{created['id']}").status_code == 200
    assert client.get("/api/skills").json() == []


@pytest.mark.parametrize("level", [0, 11])
def test_skill_level_out_of_range_is_rejected(client, level):
    assert client.post("/api/skills", json={"name": "X", "level": level}).status_code == 422


# ---------------------------------------------------------------- CORS

def test_cors_preflight_allows_cross_origin_requests(client):
    headers = {
        "Origin": "http://192.168.1.100:8000",
        "Access-Control-Request-Method": "POST",
        "Access-Control-Request-Headers": "content-type",
    }

    res = client.options("/api/ai/ask", headers=headers)

    assert res.status_code == 200
    assert res.headers.get("access-control-allow-origin") in ("*", "http://192.168.1.100:8000")
