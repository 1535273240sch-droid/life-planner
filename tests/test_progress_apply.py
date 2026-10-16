"""POST /api/goals/progress/apply：用户确认后才写库。"""
import pytest

from helpers import add_goal, add_other_user_goal, delete_goal, get_goal_row

URL = "/api/goals/progress/apply"


def _apply(client, *items):
    return client.post(URL, json={"items": [{"goal_id": g, "progress": p} for g, p in items]})


def test_route_is_not_swallowed_by_goal_id_route(client):
    goal_id = add_goal(client, "目标", progress=10)

    res = _apply(client, (goal_id, 20))

    assert res.status_code == 200, res.text


def test_update_writes_progress_and_reports_it(client):
    goal_id = add_goal(client, "目标", progress=10)

    res = _apply(client, (goal_id, 45))

    body = res.json()
    assert body["skipped"] == []
    assert body["updated"] == [{"goal_id": goal_id, "progress": 45, "status": "in_progress"}]
    assert get_goal_row(goal_id).progress == 45
    assert client.get("/api/goals").json()[0]["progress"] == 45


def test_progress_one_hundred_marks_goal_completed(client):
    goal_id = add_goal(client, "目标", progress=80)

    body = _apply(client, (goal_id, 100)).json()

    assert body["updated"][0]["status"] == "completed"
    assert get_goal_row(goal_id).status == "completed"
    assert get_goal_row(goal_id).progress == 100


def test_completed_goal_with_lower_progress_returns_to_in_progress(client):
    goal_id = add_goal(client, "目标", progress=100, status="completed")

    body = _apply(client, (goal_id, 70)).json()

    assert body["updated"][0] == {"goal_id": goal_id, "progress": 70, "status": "in_progress"}
    assert get_goal_row(goal_id).status == "in_progress"


def test_completed_goal_staying_at_one_hundred_remains_completed(client):
    goal_id = add_goal(client, "目标", progress=100, status="completed")

    body = _apply(client, (goal_id, 100)).json()

    assert body["updated"][0]["status"] == "completed"


def test_paused_goal_progress_changes_but_status_stays_paused(client):
    goal_id = add_goal(client, "目标", progress=10, status="paused")

    body = _apply(client, (goal_id, 60)).json()

    assert body["updated"][0] == {"goal_id": goal_id, "progress": 60, "status": "paused"}
    goal = get_goal_row(goal_id)
    assert (goal.progress, goal.status) == (60, "paused")


def test_zero_progress_is_accepted(client):
    goal_id = add_goal(client, "目标", progress=50)

    body = _apply(client, (goal_id, 0)).json()

    assert body["updated"][0]["progress"] == 0
    assert get_goal_row(goal_id).progress == 0


def test_duplicate_goal_id_uses_last_item(client):
    goal_id = add_goal(client, "目标", progress=10)

    body = _apply(client, (goal_id, 30), (goal_id, 55)).json()

    assert get_goal_row(goal_id).progress == 55
    assert len(body["updated"]) == 1
    assert body["updated"][0]["progress"] == 55


def test_multiple_goals_are_updated_in_one_call(client):
    a = add_goal(client, "A", progress=0)
    b = add_goal(client, "B", progress=0)

    body = _apply(client, (a, 20), (b, 100)).json()

    assert {u["goal_id"]: (u["progress"], u["status"]) for u in body["updated"]} == {
        a: (20, "in_progress"),
        b: (100, "completed"),
    }


def test_nonexistent_goal_is_skipped_with_reason_while_valid_items_succeed(client):
    goal_id = add_goal(client, "目标", progress=10)

    body = _apply(client, (goal_id, 40), (777777, 50)).json()

    assert [u["goal_id"] for u in body["updated"]] == [goal_id]
    assert len(body["skipped"]) == 1
    assert body["skipped"][0]["goal_id"] == 777777
    assert str(body["skipped"][0]["reason"]).strip()
    assert get_goal_row(goal_id).progress == 40


def test_soft_deleted_goal_is_skipped_and_not_modified(client):
    live = add_goal(client, "活着的", progress=10)
    gone = add_goal(client, "已删除", progress=10)
    delete_goal(client, gone)

    body = _apply(client, (gone, 90), (live, 20)).json()

    assert [s["goal_id"] for s in body["skipped"]] == [gone]
    assert str(body["skipped"][0]["reason"]).strip()
    assert [u["goal_id"] for u in body["updated"]] == [live]
    assert get_goal_row(gone).progress == 10


def test_other_users_goal_cannot_be_modified(client):
    mine = add_goal(client, "我的", progress=10)
    theirs = add_other_user_goal(progress=33)

    body = _apply(client, (theirs, 99), (mine, 15)).json()

    assert [s["goal_id"] for s in body["skipped"]] == [theirs]
    assert [u["goal_id"] for u in body["updated"]] == [mine]
    other = get_goal_row(theirs)
    assert (other.progress, other.status) == (33, "in_progress")


def test_all_items_invalid_still_returns_200_with_everything_skipped(client):
    body = _apply(client, (111, 10), (222, 20)).json()

    assert body["updated"] == []
    assert sorted(s["goal_id"] for s in body["skipped"]) == [111, 222]


@pytest.mark.parametrize("bad", [101, 150, -1, -50])
def test_progress_out_of_range_is_rejected_with_422_and_changes_nothing(client, bad):
    goal_id = add_goal(client, "目标", progress=10)

    res = _apply(client, (goal_id, bad))

    assert res.status_code == 422
    assert get_goal_row(goal_id).progress == 10


def test_one_out_of_range_item_rejects_whole_request(client):
    a = add_goal(client, "A", progress=10)
    b = add_goal(client, "B", progress=10)

    res = _apply(client, (a, 50), (b, 120))

    assert res.status_code == 422
    assert get_goal_row(a).progress == 10


def test_empty_items_is_rejected_with_422(client):
    res = client.post(URL, json={"items": []})

    assert res.status_code == 422


def test_missing_items_is_rejected_with_422(client):
    res = client.post(URL, json={})

    assert res.status_code == 422


def test_non_integer_progress_is_rejected_with_422(client):
    goal_id = add_goal(client, "目标", progress=10)

    res = client.post(URL, json={"items": [{"goal_id": goal_id, "progress": "abc"}]})

    assert res.status_code == 422


def test_more_than_fifty_items_is_rejected_with_422(client):
    items = [{"goal_id": i + 1, "progress": 10} for i in range(51)]

    res = client.post(URL, json={"items": items})

    assert res.status_code == 422
