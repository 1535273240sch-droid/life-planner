"""周复盘的进度建议：只保留合法条目，且 AI 路径永远不写 goals.progress。"""
import pytest

from helpers import add_goal, add_log, days_ago, delete_goal, get_goal_row, query_all, valid_daily_review, valid_weekly_review
from models import Goal

REASON = "本周完成 3 次听力训练、2 篇精读，约推进 10 个百分点"


def _suggestion(goal_id, suggested=30, reason=REASON, title="雅思7分", current=20):
    return {
        "goal_id": goal_id,
        "title": title,
        "current_progress": current,
        "suggested_progress": suggested,
        "reason": reason,
    }


def _weekly(client, fake_model, suggestions):
    fake_model.respond(valid_weekly_review(suggestions))
    res = client.post("/api/ai/review", json={"type": "weekly"})
    assert res.status_code == 200, res.text
    return res.json()


def _progress_snapshot():
    return {g.id: (g.progress, g.status) for g in query_all(Goal)}


def test_valid_suggestion_is_returned_unchanged(client, fake_model):
    goal_id = add_goal(client, "雅思7分", progress=20)

    body = _weekly(client, fake_model, [_suggestion(goal_id)])

    assert len(body["progress_suggestions"]) == 1
    item = body["progress_suggestions"][0]
    assert item["goal_id"] == goal_id
    assert item["suggested_progress"] == 30
    assert item["reason"] == REASON


def test_suggestion_current_progress_and_title_come_from_database_not_model(client, fake_model):
    goal_id = add_goal(client, "雅思7分", progress=20)

    body = _weekly(client, fake_model, [_suggestion(goal_id, title="模型瞎编的标题", current=99)])

    item = body["progress_suggestions"][0]
    assert item["current_progress"] == 20
    assert item["title"] == "雅思7分"


def test_empty_suggestion_list_is_valid(client, fake_model):
    add_goal(client, "雅思7分", progress=20)

    body = _weekly(client, fake_model, [])

    assert body["progress_suggestions"] == []


def test_missing_progress_suggestions_key_from_model_yields_empty_list(client, fake_model):
    add_goal(client, "雅思7分", progress=20)
    fake_model.respond(valid_weekly_review(None))

    res = client.post("/api/ai/review", json={"type": "weekly"})

    assert res.status_code == 200, res.text
    assert res.json()["progress_suggestions"] == []


def test_suggestion_for_nonexistent_goal_is_dropped(client, fake_model):
    goal_id = add_goal(client, "雅思7分", progress=20)

    body = _weekly(client, fake_model, [_suggestion(goal_id), _suggestion(424242)])

    assert [s["goal_id"] for s in body["progress_suggestions"]] == [goal_id]


def test_suggestion_for_soft_deleted_goal_is_dropped(client, fake_model):
    live = add_goal(client, "雅思7分", progress=20)
    gone = add_goal(client, "已删除的目标", progress=10)
    delete_goal(client, gone)

    body = _weekly(client, fake_model, [_suggestion(gone), _suggestion(live)])

    assert [s["goal_id"] for s in body["progress_suggestions"]] == [live]


@pytest.mark.parametrize("status", ["completed", "paused"])
def test_suggestion_for_goal_not_in_progress_is_dropped(client, fake_model, status):
    live = add_goal(client, "雅思7分", progress=20)
    other = add_goal(client, "非进行中目标", progress=100 if status == "completed" else 40, status=status)

    body = _weekly(client, fake_model, [_suggestion(other, suggested=60), _suggestion(live)])

    assert [s["goal_id"] for s in body["progress_suggestions"]] == [live]


@pytest.mark.parametrize("bad_value", [150, 101, -5, -1, "abc", "", None, 30.5, True, [30]], ids=repr)
def test_suggestion_with_invalid_progress_value_is_dropped(client, fake_model, bad_value):
    goal_id = add_goal(client, "雅思7分", progress=20)

    body = _weekly(client, fake_model, [_suggestion(goal_id, suggested=bad_value)])

    assert body["progress_suggestions"] == []


@pytest.mark.parametrize("value", [0, 100])
def test_suggestion_with_boundary_progress_value_is_kept(client, fake_model, value):
    goal_id = add_goal(client, "雅思7分", progress=20)

    body = _weekly(client, fake_model, [_suggestion(goal_id, suggested=value)])

    assert [s["suggested_progress"] for s in body["progress_suggestions"]] == [value]


@pytest.mark.parametrize("reason", ["", "   ", None])
def test_suggestion_with_empty_reason_is_dropped(client, fake_model, reason):
    goal_id = add_goal(client, "雅思7分", progress=20)

    body = _weekly(client, fake_model, [_suggestion(goal_id, reason=reason)])

    assert body["progress_suggestions"] == []


def test_suggestion_missing_goal_id_is_dropped(client, fake_model):
    add_goal(client, "雅思7分", progress=20)
    broken = _suggestion(1)
    del broken["goal_id"]

    body = _weekly(client, fake_model, [broken])

    assert body["progress_suggestions"] == []


def test_mixed_valid_and_invalid_suggestions_keep_only_valid_ones(client, fake_model):
    live = add_goal(client, "雅思7分", progress=20)
    deleted = add_goal(client, "删掉的", progress=10)
    done = add_goal(client, "已完成的", progress=100, status="completed")
    delete_goal(client, deleted)

    body = _weekly(client, fake_model, [
        _suggestion(live, suggested=35),
        _suggestion(999),
        _suggestion(deleted),
        _suggestion(done, suggested=60),
        _suggestion(live, suggested=150),
        _suggestion(live, suggested=-5),
        _suggestion(live, suggested="abc"),
        _suggestion(live, suggested=40, reason=""),
    ])

    assert [(s["goal_id"], s["suggested_progress"]) for s in body["progress_suggestions"]] == [(live, 35)]


def test_ai_review_never_changes_goal_progress_or_status(client, fake_model):
    a = add_goal(client, "目标A", progress=20)
    b = add_goal(client, "目标B", progress=55, status="paused")
    c = add_goal(client, "目标C", progress=100, status="completed")
    before = _progress_snapshot()

    _weekly(client, fake_model, [_suggestion(a, suggested=90), _suggestion(b, suggested=80), _suggestion(c, suggested=10)])

    assert _progress_snapshot() == before
    assert get_goal_row(a).progress == 20


def test_ai_review_with_progress_one_hundred_suggestion_does_not_complete_goal(client, fake_model):
    goal_id = add_goal(client, "目标A", progress=20)

    _weekly(client, fake_model, [_suggestion(goal_id, suggested=100)])

    goal = get_goal_row(goal_id)
    assert goal.progress == 20
    assert goal.status == "in_progress"


def test_daily_review_response_has_no_progress_suggestions_field(client, fake_model):
    add_goal(client, "雅思7分", progress=20)
    payload = valid_daily_review()
    payload["progress_suggestions"] = [_suggestion(1)]
    fake_model.respond(payload)

    res = client.post("/api/ai/review", json={"type": "daily"})

    assert res.status_code == 200, res.text
    assert "progress_suggestions" not in res.json()


def test_weekly_review_prompt_lists_in_progress_goals_so_model_can_cite_them(client, fake_model):
    add_goal(client, "雅思7分", progress=20)
    add_log(client, days_ago(1), done_items="练了雅思听力")
    fake_model.respond(valid_weekly_review([]))

    client.post("/api/ai/review", json={"type": "weekly"})

    assert "雅思7分" in fake_model.last_prompt
