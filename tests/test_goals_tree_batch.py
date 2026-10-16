"""目标树、批量采纳、AI 拆解落库。"""
from helpers import add_goal, BREAKDOWN_MARKDOWN, query_all
from models import Goal


def _find(nodes, goal_id):
    for node in nodes:
        if node["id"] == goal_id:
            return node
        found = _find(node["children"], goal_id)
        if found:
            return found
    return None


def test_child_goal_is_nested_under_parent_in_tree(client):
    root = add_goal(client, "精通人生规划", level="long", progress=30)
    stage = add_goal(client, "完成MVP", level="stage", parent_id=root, progress=50)

    tree = client.get("/api/goals/tree").json()

    root_node = next(n for n in tree if n["id"] == root)
    assert [c["id"] for c in root_node["children"]] == [stage]
    assert root_node["children"][0]["parent_id"] == root


def test_goal_filters_by_status_and_level(client):
    add_goal(client, "进行中长期", level="long")
    add_goal(client, "已完成阶段", level="stage", status="completed", progress=100)

    assert [g["title"] for g in client.get("/api/goals?status=completed").json()] == ["已完成阶段"]
    assert [g["title"] for g in client.get("/api/goals?level=long").json()] == ["进行中长期"]


def test_goal_update_changes_fields(client):
    goal_id = add_goal(client, "旧标题", progress=10)

    res = client.put(f"/api/goals/{goal_id}", json={"title": "新标题", "progress": 55})

    assert res.status_code == 200
    assert (res.json()["title"], res.json()["progress"]) == ("新标题", 55)


def test_goal_update_of_missing_goal_returns_404(client):
    assert client.put("/api/goals/424242", json={"title": "x"}).status_code == 404


def test_goal_progress_out_of_range_is_rejected(client):
    assert client.post("/api/goals", json={"title": "x", "progress": 101}).status_code == 422


# ---------------------------------------------------------------- 批量采纳

def test_batch_list_format_creates_all_goals(client):
    master = add_goal(client, "顶层愿景", level="vision", progress=25)
    items = [
        {"parent_id": master, "level": "stage", "title": "阶段一", "progress": 60},
        {"parent_id": master, "level": "stage", "title": "阶段二", "progress": 30},
    ]

    res = client.post("/api/goals/batch", json=items)

    assert res.status_code == 200, res.text
    assert [g["title"] for g in res.json()] == ["阶段一", "阶段二"]


def test_batch_dict_format_applies_common_parent_id(client):
    master = add_goal(client, "顶层愿景", level="vision")
    payload = {"parent_id": master, "goals": [
        {"level": "stage", "title": "阶段三"},
        {"level": "weekly", "title": "本周攻坚"},
    ]}

    res = client.post("/api/goals/batch", json=payload)

    assert res.status_code == 200, res.text
    assert all(g["parent_id"] == master for g in res.json())


def test_batch_created_goals_are_mounted_under_parent_in_tree(client):
    master = add_goal(client, "顶层愿景", level="vision")
    created = client.post("/api/goals/batch", json={"parent_id": master, "goals": [
        {"level": "stage", "title": "A"}, {"level": "stage", "title": "B"}]}).json()

    node = _find(client.get("/api/goals/tree").json(), master)

    assert sorted(c["id"] for c in node["children"]) == sorted(g["id"] for g in created)


# ---------------------------------------------------------------- AI 拆解落库

def test_breakdown_apply_builds_root_stage_weekly_tree(client):
    from services import goal_service
    stages = goal_service.parse_breakdown_markdown(BREAKDOWN_MARKDOWN)

    res = client.post("/api/goals/ai-breakdown/apply", json={
        "goal_title": "掌握驱动开发", "category": "职业", "plan": BREAKDOWN_MARKDOWN, "stages": stages})

    body = res.json()
    assert res.status_code == 200, res.text
    assert body["success"] is True
    assert (body["created_stages"], body["created_weekly"]) == (2, 4)
    root = _find(client.get("/api/goals/tree").json(), body["root_goal_id"])
    assert len(root["children"]) == 2
    for stage in root["children"]:
        assert stage["level"] == "stage" and stage["parent_id"] == root["id"]
        assert len(stage["children"]) == 2
        for weekly in stage["children"]:
            assert weekly["level"] == "weekly" and weekly["parent_id"] == stage["id"]


def test_breakdown_apply_under_existing_parent_attaches_without_new_root(client):
    parent = add_goal(client, "2027年打造数字资产体系", level="long", category="财务")
    before = len(query_all(Goal))

    res = client.post("/api/goals/ai-breakdown/apply", json={"parent_id": parent, "stages": [{
        "title": "阶段一：跑通最小闭环", "metric": "月度现金流为正",
        "weekly_goals": [{"title": "第1周：上线落地页"}, {"title": "第2-3周：获得10位种子用户"}]}]})

    body = res.json()
    assert (body["created_stages"], body["created_weekly"]) == (1, 2)
    assert body["root_goal_id"] == parent
    assert len(query_all(Goal)) == before + 3


def test_breakdown_apply_can_parse_plan_markdown_when_stages_omitted(client):
    res = client.post("/api/goals/ai-breakdown/apply", json={"goal_title": "掌握驱动开发", "plan": BREAKDOWN_MARKDOWN})

    assert res.status_code == 200, res.text
    assert res.json()["created_stages"] == 2


def test_parse_breakdown_markdown_of_empty_text_returns_no_stages():
    from services import goal_service

    assert goal_service.parse_breakdown_markdown("") == []
    assert goal_service.parse_breakdown_markdown("没有任何阶段标题的文字") == []
