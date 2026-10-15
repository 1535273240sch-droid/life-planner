import os
import sys

# 解决 Windows 控制台编码问题
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

# 引入项目根目录
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from fastapi.testclient import TestClient
from main import app

client = TestClient(app)

def test_ai_breakdown_and_apply_flow():
    print("\n--- 开始 AI 目标自动拆解与落库批量绑定全链路测试 ---")

    # 1. 测试 AI 拆解生成接口
    res = client.post("/api/goals/ai-breakdown", json={
        "goal_title": "精通全栈AI智能体系统与量化工程架构",
        "goal_description": "结合当前项目深入落地AI决策引擎与全栈编排"
    })
    assert res.status_code == 200, f"拆解接口失败: {res.text}"
    breakdown_data = res.json()
    assert "plan" in breakdown_data
    assert "stages" in breakdown_data
    assert len(breakdown_data["stages"]) > 0
    print(f"[PASS] AI 拆解成功，返回 {len(breakdown_data['stages'])} 个阶段里程碑")

    # 2. 测试独立主目标批量应用落库
    apply_payload = {
        "goal_title": "精通全栈AI智能体系统与量化工程架构",
        "category": "职业",
        "plan": breakdown_data["plan"],
        "stages": breakdown_data["stages"]
    }
    res_apply = client.post("/api/goals/ai-breakdown/apply", json=apply_payload)
    assert res_apply.status_code == 200, f"落库失败: {res_apply.text}"
    apply_res = res_apply.json()
    assert apply_res["success"] is True
    assert apply_res["created_stages"] > 0
    assert apply_res["created_weekly"] > 0
    root_id = apply_res["root_goal_id"]
    print(f"[PASS] 自动落库完成: 根目标ID={root_id}, 阶段数={apply_res['created_stages']}, 周目标数={apply_res['created_weekly']}")

    # 3. 验证树形层级绑定关系
    res_tree = client.get("/api/goals/tree")
    assert res_tree.status_code == 200
    tree = res_tree.json()
    root_node = next((g for g in tree if g["id"] == root_id), None)
    assert root_node is not None, "未在目标树中检索到新创建的宏观根目标"
    assert len(root_node["children"]) == apply_res["created_stages"]

    for stage_node in root_node["children"]:
        assert stage_node["level"] == "stage"
        assert stage_node["parent_id"] == root_id
        assert len(stage_node["children"]) > 0
        for weekly_node in stage_node["children"]:
            assert weekly_node["level"] == "weekly"
            assert weekly_node["parent_id"] == stage_node["id"]
    print("[PASS] 目标分层树三级拓扑结构 (Root -> Stage -> Weekly) 自动绑定检验全部合格！")

    # 4. 测试归属于既有目标的追加拆解落库
    res_existing_parent = client.post("/api/goals", json={
        "title": "2027年打造个人高杠杆数字资产体系",
        "level": "long",
        "category": "财务"
    })
    assert res_existing_parent.status_code == 200
    existing_parent_id = res_existing_parent.json()["id"]

    res_apply_nested = client.post("/api/goals/ai-breakdown/apply", json={
        "parent_id": existing_parent_id,
        "stages": [
            {
                "title": "阶段一：跑通单机最小闭环盈利",
                "metric": "月度现金流为正",
                "weekly_goals": [
                    {"title": "第1周：选定商业定位并上线落地页", "description": "交付落地页"},
                    {"title": "第2-3周：获得前10位种子付费用户", "description": "完成验证"}
                ]
            }
        ]
    })
    assert res_apply_nested.status_code == 200
    nested_res = res_apply_nested.json()
    assert nested_res["created_stages"] == 1
    assert nested_res["created_weekly"] == 2
    print(f"[PASS] 既有目标追加拆解落库验证通过 (parent_id={existing_parent_id})")

    print("\n=== AI 目标自动拆解与落库全链路自测全部通过！ ===\n")

if __name__ == "__main__":
    test_ai_breakdown_and_apply_flow()
