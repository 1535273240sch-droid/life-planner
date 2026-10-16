"""目标父子关系、目标树、复盘日期的回归测试。

覆盖独立复核提出的缺陷：
1) 目标树在数据成环时会让目标凭空消失（get_goal_tree 需把环内节点提升为根，且不重复不丢失）；
2) 父级校验缺失：可以把目标挂到自己、自己的下级、已删除目标或其他用户的目标上；
3) 复盘传入非法日期会被静默换成今天，而不是报错；
4) 明确 paused + 100 的语义（确认到 100% 按完成处理）。
"""
import helpers
from models import Goal


def _walk(nodes, seen):
    """递归收集目标树节点，顺带断言没有重复节点（重复即说明成环未断开）。"""
    for node in nodes:
        assert node["id"] not in seen, f"目标 {node['id']} 在树中重复出现"
        seen.add(node["id"])
        _walk(node.get("children") or [], seen)
    return seen


def test_create_goal_with_unknown_parent_is_rejected(client):
    res = client.post("/api/goals", json={"title": "孤儿目标", "level": "stage", "parent_id": 999999})
    assert res.status_code == 400
    assert "父级目标" in res.json()["detail"]


def test_create_goal_with_soft_deleted_parent_is_rejected(client):
    parent_id = helpers.add_goal(client, "将被删除的父级")
    helpers.delete_goal(client, parent_id)

    res = client.post("/api/goals", json={"title": "挂到已删父级", "level": "stage", "parent_id": parent_id})
    assert res.status_code == 400
    assert "父级目标" in res.json()["detail"]


def test_update_goal_parent_to_self_is_rejected(client):
    goal_id = helpers.add_goal(client, "自己当父级")

    res = client.put(f"/api/goals/{goal_id}", json={"parent_id": goal_id})
    assert res.status_code == 400
    assert "自己" in res.json()["detail"]


def test_update_goal_parent_to_own_descendant_is_rejected(client):
    """愿景挂到自己的孙子下，会形成 vision → stage → weekly → vision 的环。"""
    vision = helpers.add_goal(client, "愿景", level="vision")
    stage = helpers.add_goal(client, "阶段", level="stage", parent_id=vision)
    weekly = helpers.add_goal(client, "周目标", level="weekly", parent_id=stage)

    res = client.put(f"/api/goals/{vision}", json={"parent_id": weekly})
    assert res.status_code == 400
    assert "循环" in res.json()["detail"]


def test_update_goal_parent_to_other_users_goal_is_rejected(client):
    others_goal = helpers.add_other_user_goal()
    goal_id = helpers.add_goal(client, "我的目标")

    res = client.put(f"/api/goals/{goal_id}", json={"parent_id": others_goal})
    assert res.status_code == 400


def test_valid_reparenting_still_works(client):
    """正常的换父级不能被误拦。"""
    vision = helpers.add_goal(client, "愿景", level="vision")
    another_vision = helpers.add_goal(client, "另一个愿景", level="vision")
    stage = helpers.add_goal(client, "阶段", level="stage", parent_id=vision)

    res = client.put(f"/api/goals/{stage}", json={"parent_id": another_vision})
    assert res.status_code == 200
    assert res.json()["parent_id"] == another_vision


def test_tree_promotes_cycle_members_instead_of_hiding_them(client, db):
    """旧数据里已经成环时，目标树必须仍然显示这些目标。"""
    first = helpers.add_goal(client, "A", level="long")
    second = helpers.add_goal(client, "B", level="stage", parent_id=first)

    # 绕过接口直接改库造环（模拟历史数据或手工改动）
    db.query(Goal).filter(Goal.id == first).update({Goal.parent_id: second})
    db.commit()

    tree = client.get("/api/goals/tree").json()
    assert _walk(tree, set()) == {first, second}


def test_tree_keeps_deep_chain_intact(client):
    """正常的多层结构不受兜底逻辑影响。"""
    vision = helpers.add_goal(client, "愿景", level="vision")
    long_goal = helpers.add_goal(client, "长期", level="long", parent_id=vision)
    stage = helpers.add_goal(client, "阶段", level="stage", parent_id=long_goal)
    weekly = helpers.add_goal(client, "周目标", level="weekly", parent_id=stage)

    tree = client.get("/api/goals/tree").json()
    assert _walk(tree, set()) == {vision, long_goal, stage, weekly}
    assert len(tree) == 1, "只应有一个根节点"


def test_review_rejects_invalid_date_and_type(client):
    bad_date = client.post("/api/ai/review", json={"type": "daily", "date": "2026-13-45"})
    assert bad_date.status_code == 400
    assert "日期" in bad_date.json()["detail"]

    bad_type = client.post("/api/ai/review", json={"type": "monthly"})
    assert bad_type.status_code == 400


def test_paused_goal_confirmed_to_100_becomes_completed(client):
    """paused 只在 progress < 100 时保持暂停；被确认到 100% 时按完成处理。"""
    goal_id = helpers.add_goal(client, "暂停中的目标", level="stage", progress=60, status="paused")

    kept = client.post("/api/goals/progress/apply", json={"items": [{"goal_id": goal_id, "progress": 70}]}).json()
    assert kept["updated"] == [{"goal_id": goal_id, "progress": 70, "status": "paused"}]

    completed = client.post("/api/goals/progress/apply", json={"items": [{"goal_id": goal_id, "progress": 100}]}).json()
    assert completed["updated"] == [{"goal_id": goal_id, "progress": 100, "status": "completed"}]
