import os
import sys
import datetime

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
from database import SessionLocal, engine, Base
from models import User, Profile, Skill, Goal, Task, DailyLog

client = TestClient(app)

def test_stage1_flow():
    print("\n--- 开始阶段 1 自动化验收测试 ---")

    # 1. 测试个人档案接口读取与更新
    res = client.get("/api/profile")
    assert res.status_code == 200, f"获取档案失败: {res.text}"
    profile = res.json()
    assert "user_id" in profile
    print("[PASS] 个人档案默认读取测试通过")

    # 更新档案
    update_data = {
        "age": 29,
        "occupation": "全栈智能体架构师",
        "vision": "成为全球顶尖的AI工程专家",
        "constraints": "每天2小时专注学习，重视复利",
        "health_info": {
            "height": 178,
            "weight": 72,
            "diet_pref": "高蛋白低碳水",
            "allergy_or_disease": "无特殊过敏"
        }
    }
    res = client.put("/api/profile", json=update_data)
    assert res.status_code == 200, f"更新档案失败: {res.text}"
    assert res.json()["occupation"] == "全栈智能体架构师"
    print("[PASS] 个人档案修改保存测试通过")

    # 2. 测试技能增删改查
    skill_data = {
        "name": "Python与FastAPI",
        "category": "专业技能",
        "level": 9,
        "note": "熟练掌握全栈开发与异步高并发"
    }
    res = client.post("/api/skills", json=skill_data)
    assert res.status_code == 200, f"创建技能失败: {res.text}"
    skill_id = res.json()["id"]
    print(f"[PASS] 创建技能测试通过，ID={skill_id}")

    res = client.get("/api/skills")
    assert res.status_code == 200
    assert any(s["id"] == skill_id for s in res.json())
    print("[PASS] 查询技能列表测试通过")

    # 3. 测试目标分层系统增删改查
    # 创建长期目标
    long_goal = {
        "title": "精通人生规划与AI自动化架构",
        "level": "long",
        "category": "职业",
        "metric": "独立落地3款高价值智能体产品",
        "progress": 30,
        "status": "in_progress"
    }
    res = client.post("/api/goals", json=long_goal)
    assert res.status_code == 200, f"创建长期目标失败: {res.text}"
    long_goal_id = res.json()["id"]

    # 创建挂在其下的阶段目标
    stage_goal = {
        "title": "完成人生智能编排体MVP版本",
        "level": "stage",
        "parent_id": long_goal_id,
        "category": "职业",
        "metric": "全功能自测通过",
        "progress": 50,
        "status": "in_progress"
    }
    res = client.post("/api/goals", json=stage_goal)
    assert res.status_code == 200, f"创建阶段目标失败: {res.text}"
    stage_goal_id = res.json()["id"]
    print(f"[PASS] 目标分层创建测试通过 (长期ID={long_goal_id}, 阶段ID={stage_goal_id})")

    # 查询目标树
    res = client.get("/api/goals/tree")
    assert res.status_code == 200
    tree = res.json()
    root_node = next((g for g in tree if g["id"] == long_goal_id), None)
    assert root_node is not None
    assert any(c["id"] == stage_goal_id for c in root_node["children"])
    print("[PASS] 目标树形分层嵌套关联测试通过")

    # 4. 测试每日记录录入与读取
    today_str = datetime.date.today().isoformat()
    log_data = {
        "date": today_str,
        "done_items": "1. 实现了阶段1基础骨架\n2. 编写了完整的测试套件",
        "study_minutes": 120,
        "study_note": "深入学习了SQLAlchemy 2.0与FastAPI配合",
        "meals": {
            "breakfast": "鸡蛋全麦面包",
            "lunch": "清蒸鱼配西兰花",
            "dinner": "蔬菜汤",
            "snack": "无糖黑咖啡"
        },
        "sleep_start": "23:30",
        "sleep_end": "07:30",
        "exercise_type": "核心力量训练",
        "exercise_minutes": 40,
        "mood": 5,
        "energy": 4,
        "reflection": "阶段1骨架开发顺利，状态极佳！"
    }
    res = client.post("/api/logs", json=log_data)
    assert res.status_code == 200, f"保存每日记录失败: {res.text}"
    print("[PASS] 每日记录写入测试通过")

    # 5. 模拟“刷新页面”数据持久化验证：通过纯新请求拉取
    res = client.get(f"/api/logs?date={today_str}")
    assert res.status_code == 200
    saved_log = res.json()
    assert saved_log["study_minutes"] == 120
    assert saved_log["meals"]["lunch"] == "清蒸鱼配西兰花"
    assert saved_log["mood"] == 5
    print("[PASS] 刷新重读每日记录验证通过 (数据不丢)")

    # 6. 测试统计与页面路由
    res = client.get("/api/stats/overview")
    assert res.status_code == 200
    assert res.json()["streak"] >= 1
    print(f"[PASS] 统计接口测试通过，当前连续打卡: {res.json()['streak']} 天")

    # 测试各 HTML 页面能正常返回 200
    for page in ["/", "/goals", "/log", "/profile"]:
        res = client.get(page)
        assert res.status_code == 200, f"页面 {page} 访问失败"
    print("[PASS] 前端全部页面路由渲染正常 (HTTP 200)")

    print("\n=== 阶段 1 核心功能全链路自测全部通过！ ===\n")

if __name__ == "__main__":
    test_stage1_flow()
