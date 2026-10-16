"""目标软删除：整棵子树隐藏，历史任务与 goal_id 原样保留。"""
import datetime

import pytest

from helpers import add_goal, add_log, days_ago, delete_goal, get_goal_row, query_all, valid_ask, valid_daily_plan
from models import Goal, Task


@pytest.fixture
def tree(client):
    """root(long) -> mid(stage) -> leaf(weekly)，外加一个无关的 sibling；每个节点各挂一个任务。"""
    root = add_goal(client, "根目标雅思7分", level="long")
    mid = add_goal(client, "中间阶段词汇突破", level="stage", parent_id=root)
    leaf = add_goal(client, "叶子周目标背单词", level="weekly", parent_id=mid)
    sibling = add_goal(client, "无关目标健身", level="long")
    today = datetime.date.today().isoformat()
    task_ids = {}
    for name, goal_id in (("root", root), ("mid", mid), ("leaf", leaf), ("sibling", sibling)):
        res = client.post("/api/tasks", json={"title": f"{name}的任务", "goal_id": goal_id, "date": today})
        assert res.status_code == 200, res.text
        task_ids[name] = res.json()["id"]
    return {"root": root, "mid": mid, "leaf": leaf, "sibling": sibling, "tasks": task_ids}


def _flatten(nodes):
    for node in nodes:
        yield node["id"]
        yield from _flatten(node["children"])


def test_delete_returns_success_and_sets_deleted_at_without_removing_row(client, tree):
    res = client.delete(f"/api/goals/{tree['mid']}")

    assert res.status_code == 200
    row = get_goal_row(tree["mid"])
    assert row is not None
    assert row.deleted_at is not None


def test_deleting_middle_node_hides_node_and_descendants_from_goal_list(client, tree):
    delete_goal(client, tree["mid"])

    ids = [g["id"] for g in client.get("/api/goals").json()]

    assert tree["mid"] not in ids
    assert tree["leaf"] not in ids
    assert tree["root"] in ids
    assert tree["sibling"] in ids


def test_deleting_middle_node_hides_subtree_from_tree_endpoint(client, tree):
    delete_goal(client, tree["mid"])

    tree_ids = set(_flatten(client.get("/api/goals/tree").json()))

    assert tree_ids == {tree["root"], tree["sibling"]}


def test_deleted_descendants_do_not_become_roots_in_tree(client, tree):
    delete_goal(client, tree["mid"])

    roots = [n["id"] for n in client.get("/api/goals/tree").json()]

    assert tree["leaf"] not in roots


def test_deleting_middle_node_does_not_orphan_descendant_parent_id(client, tree):
    delete_goal(client, tree["mid"])

    assert get_goal_row(tree["leaf"]).parent_id == tree["mid"]
    assert get_goal_row(tree["leaf"]).deleted_at is not None


def test_deleting_middle_node_leaves_parent_and_unrelated_goals_alive(client, tree):
    delete_goal(client, tree["mid"])

    assert get_goal_row(tree["root"]).deleted_at is None
    assert get_goal_row(tree["sibling"]).deleted_at is None


def test_deleted_goals_are_excluded_from_stats_overview(client, tree):
    assert client.get("/api/stats/overview").json()["total_goals"] == 4

    delete_goal(client, tree["mid"])

    overview = client.get("/api/stats/overview").json()
    assert overview["total_goals"] == 2
    assert overview["in_progress_goals"] == 2


def test_deleted_completed_goal_not_counted_as_completed(client):
    done = add_goal(client, "已完成的", progress=100, status="completed")
    assert client.get("/api/stats/overview").json()["completed_goals"] == 1

    delete_goal(client, done)

    assert client.get("/api/stats/overview").json()["completed_goals"] == 0


def test_tasks_of_deleted_goals_keep_rows_and_goal_id(client, tree):
    delete_goal(client, tree["mid"])

    tasks = {t.id: t for t in query_all(Task)}

    assert len(tasks) == 4
    assert tasks[tree["tasks"]["mid"]].goal_id == tree["mid"]
    assert tasks[tree["tasks"]["leaf"]].goal_id == tree["leaf"]


def test_tasks_of_deleted_goals_still_returned_by_date_query(client, tree):
    delete_goal(client, tree["mid"])
    today = datetime.date.today().isoformat()

    api_tasks = client.get(f"/api/tasks?date={today}").json()

    by_id = {t["id"]: t for t in api_tasks}
    assert tree["tasks"]["mid"] in by_id
    assert by_id[tree["tasks"]["mid"]]["goal_id"] == tree["mid"]
    assert by_id[tree["tasks"]["leaf"]]["goal_id"] == tree["leaf"]


def test_task_filter_by_deleted_goal_id_still_works(client, tree):
    delete_goal(client, tree["mid"])
    today = datetime.date.today().isoformat()

    api_tasks = client.get(f"/api/tasks?date={today}&goal_id={tree['mid']}").json()

    assert [t["id"] for t in api_tasks] == [tree["tasks"]["mid"]]


def test_deleting_same_goal_twice_returns_404(client, tree):
    delete_goal(client, tree["mid"])

    res = client.delete(f"/api/goals/{tree['mid']}")

    assert res.status_code == 404


def test_deleting_descendant_of_already_deleted_goal_returns_404(client, tree):
    delete_goal(client, tree["mid"])

    res = client.delete(f"/api/goals/{tree['leaf']}")

    assert res.status_code == 404


def test_deleting_nonexistent_goal_returns_404(client):
    assert client.delete("/api/goals/987654").status_code == 404


def test_updating_deleted_goal_returns_404_and_changes_nothing(client, tree):
    delete_goal(client, tree["mid"])

    res = client.put(f"/api/goals/{tree['mid']}", json={"title": "复活"})

    assert res.status_code == 404
    assert get_goal_row(tree["mid"]).title == "中间阶段词汇突破"


def test_progress_apply_skips_deleted_goal(client, tree):
    delete_goal(client, tree["mid"])

    body = client.post("/api/goals/progress/apply", json={"items": [{"goal_id": tree["mid"], "progress": 50}]}).json()

    assert [s["goal_id"] for s in body["skipped"]] == [tree["mid"]]


# ---------------------------------------------------------------- 与 AI 的交互

def test_deleted_goals_do_not_appear_in_ai_prompt(client, fake_model, tree):
    delete_goal(client, tree["mid"])
    fake_model.respond(valid_ask())

    client.post("/api/ai/ask", json={"question": "今晚可以点外卖吗"})

    prompt = fake_model.last_prompt
    assert "中间阶段词汇突破" not in prompt
    assert "叶子周目标背单词" not in prompt
    assert "根目标雅思7分" in prompt
    assert "无关目标健身" in prompt


def test_live_goals_appear_in_daily_plan_prompt_but_deleted_ones_do_not(client, fake_model, tree):
    delete_goal(client, tree["sibling"])
    fake_model.respond(valid_daily_plan())

    client.post("/api/ai/daily-plan")

    assert "无关目标健身" not in fake_model.last_prompt
    assert "根目标雅思7分" in fake_model.last_prompt


def test_plan_task_pointing_at_deleted_goal_loses_its_goal_id(client, fake_model, tree):
    delete_goal(client, tree["sibling"])
    plan = valid_daily_plan()
    plan["tasks"] = [{"title": "引用已删除目标的新任务", "goal_id": tree["sibling"], "est_minutes": 20}]
    fake_model.respond(plan)

    client.post("/api/ai/daily-plan")

    created = [t for t in query_all(Task) if t.title == "引用已删除目标的新任务"]
    assert len(created) == 1
    assert created[0].goal_id is None


# ---------------------------------------------------------------- 缺口预警

def _stagnation_titles(client):
    return [g["title"] for g in client.get("/api/ai/gaps").json() if g["category"] == "目标停滞"]


def test_deleted_goal_does_not_trigger_stagnation_gap(client):
    goal_id = add_goal(client, "学习古希腊哲学原著精读", level="stage")
    add_log(client, days_ago(1), done_items="整理了周报")
    add_log(client, days_ago(2), done_items="开会")
    add_log(client, days_ago(3), done_items="修了一个bug")
    assert any("古希腊哲学" in t for t in _stagnation_titles(client))

    delete_goal(client, goal_id)

    assert not any("古希腊哲学" in t for t in _stagnation_titles(client))


# ---------------------------------------------------------------- AI 拆解落库

def test_breakdown_apply_with_deleted_parent_creates_new_root(client):
    parent = add_goal(client, "已删除的父目标", level="long")
    delete_goal(client, parent)

    res = client.post("/api/goals/ai-breakdown/apply", json={
        "parent_id": parent,
        "goal_title": "新的根目标",
        "stages": [{"title": "阶段一", "weekly_goals": [{"title": "第1周：开始"}]}],
    })

    assert res.status_code == 200, res.text
    root_id = res.json()["root_goal_id"]
    assert root_id != parent
    root = get_goal_row(root_id)
    assert root.title == "新的根目标"
    assert root.parent_id is None
    stage = [g for g in query_all(Goal) if g.level == "stage"][0]
    assert stage.parent_id == root_id


def test_breakdown_apply_with_deleted_parent_and_no_title_does_not_attach_to_deleted_goal(client):
    parent = add_goal(client, "已删除的父目标", level="long")
    delete_goal(client, parent)

    client.post("/api/goals/ai-breakdown/apply", json={
        "parent_id": parent,
        "stages": [{"title": "阶段一"}],
    })

    stages = [g for g in query_all(Goal) if g.level == "stage"]
    assert all(s.parent_id != parent for s in stages)
