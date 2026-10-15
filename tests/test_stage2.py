import os
import sys
import json
from fastapi.testclient import TestClient

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from main import app

client = TestClient(app)

def test_stage2_flow():
    print("\n--- 开始阶段 2 AI 智能体接入自动化验收测试 ---")

    # 1. 验证 D1: 生成今日计划
    print("-> 正在测试 D1: 每日规划生成...")
    res = client.post("/api/ai/daily-plan")
    assert res.status_code == 200, f"D1 接口请求失败: {res.text}"
    plan = res.json()
    assert "tasks" in plan, "返回中未包含 tasks"
    assert "health_reminder" in plan, "返回中未包含健康作息提醒"
    assert "tip" in plan, "返回中未包含基于表现的警醒/鼓励"
    assert len(plan["tasks"]) >= 2, "规划的任务数量不足"
    print(f"[PASS] D1 每日规划测试通过，AI 推荐任务数: {len(plan['tasks'])}，健康提醒: {plan['health_reminder'][:25]}...")

    # 验证任务是否已入库今日待办
    res = client.get("/api/tasks")
    assert res.status_code == 200
    today_tasks = res.json()
    assert len(today_tasks) >= len(plan["tasks"])
    print(f"[PASS] 今日任务自动落库验证通过 (当前今日任务数: {len(today_tasks)})")

    # 2. 验证 D2: 决策顾问 (固定6段式结构)
    print("-> 正在测试 D2: 决策顾问结构化推演...")
    ask_payload = {"question": "我今晚想熬夜看球赛，可以吗？"}
    res = client.post("/api/ai/ask", json=ask_payload)
    assert res.status_code == 200, f"D2 接口请求失败: {res.text}"
    adv = res.json()

    # 验证规范第 3 节 D2 的 6 段式核心字段
    required_keys = ["conclusion", "reason", "pros", "cons_risks", "alternative", "goal_relation"]
    for k in required_keys:
        assert k in adv and adv[k], f"缺少必要结构化字段: {k}"

    # 验证结论必须属于三者之一
    valid_conclusions = ["建议做", "不建议做", "可以但有条件"]
    assert any(vc in adv["conclusion"] for vc in valid_conclusions), f"结论格式不合规: {adv['conclusion']}"

    print(f"[PASS] 决策顾问 6 段式结构验证完全合规！")
    print(f"       【结论】: {adv['conclusion']}")
    print(f"       【理由】: {adv['reason'][:40]}...")
    print(f"       【折中方案】: {adv['alternative'][:40]}...")

    # 3. 验证饮食决策问答
    res = client.post("/api/ai/ask", json={"question": "我今晚能吃火锅吗？"})
    assert res.status_code == 200
    food_adv = res.json()
    assert any(vc in food_adv["conclusion"] for vc in valid_conclusions)
    print(f"[PASS] 饮食决策咨询测试通过，结论: {food_adv['conclusion']}")

    # 4. 验证 AI 历史问答查询
    res = client.get("/api/ai/history?mode=advisor")
    assert res.status_code == 200
    history = res.json()
    assert len(history) >= 2
    print(f"[PASS] AI 历史问答查询通过，当前留存顾问记录: {len(history)} 条")

    # 5. 验证 AI 目标自动拆解
    res = client.post("/api/goals/ai-breakdown", json={"goal_title": "在3个月内掌握全栈智能体系统开发"})
    assert res.status_code == 200
    breakdown_res = res.json()
    assert "plan" in breakdown_res or "breakdown" in breakdown_res
    print("[PASS] AI 目标拆解接口测试通过")

    # 6. 验证 /advisor 页面渲染
    res = client.get("/advisor")
    assert res.status_code == 200
    print("[PASS] AI 顾问前端页面 /advisor 渲染正常 (HTTP 200)")

    print("\n=== 阶段 2 AI 智能体接入全链路自测全部通过！ ===\n")

if __name__ == "__main__":
    test_stage2_flow()
