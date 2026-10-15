import os
import sys
import datetime
from fastapi.testclient import TestClient

# 解决 Windows 控制台编码问题
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

# 引入项目根目录
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from main import app

client = TestClient(app)

def test_stage4_acceptance_and_e2e():
    print("\n" + "=" * 60)
    print("🚀 开始阶段 4 验收测试与全流程端到端 (E2E) 集成测试")
    print("=" * 60)

    # -------------------------------------------------------------
    # 模块 1: 验证 /api/stats/trends 趋势数据接口
    # -------------------------------------------------------------
    print("\n[1/4] 正在验证 /api/stats/trends 生活指标趋势接口...")
    # 测试默认 30 天
    res = client.get("/api/stats/trends")
    assert res.status_code == 200, f"获取 30 天趋势失败: {res.text}"
    trends30 = res.json()
    for field in ["labels", "study_minutes", "sleep_hours", "mood", "energy"]:
        assert field in trends30, f"趋势数据缺少字段: {field}"
        assert len(trends30[field]) == 30, f"默认天数应为 30 天，实际为: {len(trends30[field])}"
    print(f"  [PASS] 默认 30 天趋势数据结构及点数验证通过 (30个离散时间槽)")

    # 测试自定义 7 天与 14 天趋势
    res7 = client.get("/api/stats/trends?days=7")
    assert res7.status_code == 200
    assert len(res7.json()["labels"]) == 7
    print(f"  [PASS] 自定义 7 天趋势区间过滤验证通过")

    res14 = client.get("/api/stats/trends?days=14")
    assert res14.status_code == 200
    assert len(res14.json()["labels"]) == 14
    print(f"  [PASS] 自定义 14 天趋势区间过滤验证通过")

    # -------------------------------------------------------------
    # 模块 2: 验证目标批量采纳 (/api/goals/batch) 与树形架构
    # -------------------------------------------------------------
    print("\n[2/4] 正在验证目标批量采纳与分层树形管理...")

    # 首先建立顶层愿景/长期目标
    master_goal_payload = {
        "title": "2027 成为顶尖全栈智能架构师与量化工程师",
        "level": "vision",
        "category": "职业",
        "metric": "主导开发并上线 3 套工业级分布式智能体架构",
        "progress": 25,
        "status": "in_progress"
    }
    res = client.post("/api/goals", json=master_goal_payload)
    assert res.status_code == 200, f"创建顶层战略目标失败: {res.text}"
    master_goal_id = res.json()["id"]
    print(f"  [PASS] 创建顶层愿景目标成功 (ID={master_goal_id})")

    # 测试方式 A: 直接以列表格式批量采纳
    batch_items_list = [
        {
            "parent_id": master_goal_id,
            "level": "stage",
            "title": "阶段一：精通 FastAPI 与异步并发核心底座",
            "category": "职业",
            "metric": "单机压测达到 5000+ QPS 且零死锁",
            "progress": 60,
            "status": "in_progress"
        },
        {
            "parent_id": master_goal_id,
            "level": "stage",
            "title": "阶段二：构建多智能体记忆编排与决策推演网络",
            "category": "职业",
            "metric": "支持 6 段式决策闭环及长程状态留存",
            "progress": 30,
            "status": "in_progress"
        }
    ]
    res = client.post("/api/goals/batch", json=batch_items_list)
    assert res.status_code == 200, f"列表形式批量采纳目标失败: {res.text}"
    created_batch_a = res.json()
    assert len(created_batch_a) == 2
    print(f"  [PASS] 列表格式批量采纳成功 (创建了 {len(created_batch_a)} 个阶段目标)")

    # 测试方式 B: 以字典包裹格式批量采纳 (指定公共 parent_id)
    batch_items_dict = {
        "parent_id": master_goal_id,
        "goals": [
            {
                "level": "stage",
                "title": "阶段三：工业级移动端 PWA 离线适配与体验跃迁",
                "category": "技术",
                "metric": "Lighthouse 移动端性能评分 > 95",
                "progress": 10,
                "status": "in_progress"
            },
            {
                "level": "weekly",
                "title": "本周关键攻坚：完成全链路端到端集成测试套件",
                "category": "技术",
                "metric": "100% 测试用例一键秒级通过",
                "progress": 90,
                "status": "in_progress"
            }
        ]
    }
    res = client.post("/api/goals/batch", json=batch_items_dict)
    assert res.status_code == 200, f"字典格式批量采纳目标失败: {res.text}"
    created_batch_b = res.json()
    assert len(created_batch_b) == 2
    for item in created_batch_b:
        assert item["parent_id"] == master_goal_id
    print(f"  [PASS] 字典格式批量采纳成功 (自动绑定公共父级 ID={master_goal_id})")

    # 验证树形接口中的层级结构
    res = client.get("/api/goals/tree")
    assert res.status_code == 200
    tree = res.json()
    target_node = next((n for n in tree if n["id"] == master_goal_id), None)
    assert target_node is not None, "目标树中未能检索到 master_goal"
    child_ids = [c["id"] for c in target_node["children"]]
    for item in created_batch_a + created_batch_b:
        assert item["id"] in child_ids, f"批量创建的子目标 {item['id']} 未挂载至父节点"
    print(f"  [PASS] 目标树层级嵌套关系校验 100% 吻合 (子节点数: {len(target_node['children'])})")

    # -------------------------------------------------------------
    # 模块 3: 全流程闭环 E2E 业务集成链路验证
    # -------------------------------------------------------------
    print("\n[3/4] 正在验证全流程闭环 E2E 业务链路...")

    # 步骤 A: 用户画像与档案底座写入
    profile_payload = {
        "age": 28,
        "occupation": "全栈智能体量化架构师",
        "vision": "成为世界一流的软硬件一体与AI编排专家",
        "constraints": "注重心流、拒绝形式主义、坚守健康基石",
        "health_info": {
            "height": 180,
            "weight": 73,
            "diet_pref": "低GI优质蛋白，大量新鲜蔬菜",
            "sleep_habit": "23:00前入睡，保证7.5小时睡眠",
            "allergy_or_disease": "轻微花粉过敏，无药物过敏"
        }
    }
    res = client.put("/api/profile", json=profile_payload)
    assert res.status_code == 200
    print("  [PASS] 步骤 A: 个人画像与健康限制基座配置完成")

    # 步骤 B: 技能矩阵录入
    skill_payload = {
        "name": "端到端自动化测试与CI/CD",
        "category": "工程能力",
        "level": 9,
        "note": "精通PyTest、FastAPI TestClient与无头全链路验证"
    }
    res = client.post("/api/skills", json=skill_payload)
    assert res.status_code == 200
    print("  [PASS] 步骤 B: 核心技能矩阵录入完成")

    # 步骤 C: D1 智能体生成今日规划并自动落库任务
    res = client.post("/api/ai/daily-plan")
    assert res.status_code == 200
    plan_data = res.json()
    assert "tasks" in plan_data and len(plan_data["tasks"]) > 0
    print(f"  [PASS] 步骤 C: D1 智能体生成今日计划成功，推导任务数: {len(plan_data['tasks'])}")

    # 步骤 D: 任务勾选与状态更新
    res = client.get("/api/tasks")
    assert res.status_code == 200
    tasks = res.json()
    assert len(tasks) > 0
    test_task = tasks[0]
    res = client.put(f"/api/tasks/{test_task['id']}", json={"done": True})
    assert res.status_code == 200
    assert res.json()["done"] is True
    print(f"  [PASS] 步骤 D: 今日待办任务状态流转与打卡完成 (任务ID: {test_task['id']})")

    # 步骤 E: D2 智能决策顾问 6 段式研判
    d2_payload = {"question": "明天上午有个重要技术答辩，今晚想熬夜多准备几个小时，行不行？"}
    res = client.post("/api/ai/ask", json=d2_payload)
    assert res.status_code == 200
    adv = res.json()
    for field in ["conclusion", "reason", "pros", "cons_risks", "alternative", "goal_relation"]:
        assert field in adv and adv[field], f"D2 缺少必要段落: {field}"
    assert any(c in adv["conclusion"] for c in ["建议做", "不建议做", "可以但有条件"])
    print(f"  [PASS] 步骤 E: D2 决策顾问 6 段式研判完成，结论:「{adv['conclusion']}」")

    # 步骤 F: 写入今日生活记录
    today_str = datetime.date.today().isoformat()
    log_payload = {
        "date": today_str,
        "done_items": "1. 阶段4验收测试与全流程端到端覆盖\n2. 移动端PWA与一键脚本交付",
        "study_minutes": 150,
        "study_note": "深度编写了全面的测试用例，覆盖全部核心接口与业务场景",
        "meals": {
            "breakfast": "燕麦蓝莓酸奶碗",
            "lunch": "香煎鸡胸肉配西兰花糙米饭",
            "dinner": "牛油果大虾沙拉",
            "snack": "核桃坚果"
        },
        "sleep_start": "23:00",
        "sleep_end": "07:00",
        "exercise_type": "晨跑与力量训练",
        "exercise_minutes": 45,
        "mood": 5,
        "energy": 5,
        "reflection": "系统设计严密，测试覆盖全面，整体交付质量极高！"
    }
    res = client.post("/api/logs", json=log_payload)
    assert res.status_code == 200
    print("  [PASS] 步骤 F: 今日高价值真实生活记录提交落库")

    # 步骤 G: D4 缺口检测雷达
    res = client.get("/api/ai/gaps")
    assert res.status_code == 200
    gaps = res.json()
    assert isinstance(gaps, list)
    print(f"  [PASS] 步骤 G: D4 缺口雷达扫描完成 (捕获到 {len(gaps)} 项动态提醒)")

    # 步骤 H: D3 每日与每周复盘报告推演
    res = client.post("/api/ai/review", json={"type": "daily"})
    assert res.status_code == 200
    assert "summary" in res.json()
    print("  [PASS] 步骤 H1: D3 每日战术复盘报告生成通过")

    res = client.post("/api/ai/review", json={"type": "weekly"})
    assert res.status_code == 200
    assert "summary" in res.json() and "goal_progress" in res.json()
    print("  [PASS] 步骤 H2: D3 周度战略复盘报告生成通过")

    # 步骤 I: 统计总览数据核验
    res = client.get("/api/stats/overview")
    assert res.status_code == 200
    stats = res.json()
    assert stats["total_goals"] >= 5
    assert stats["streak"] >= 1
    print(f"  [PASS] 步骤 I: 全局大盘统计校验通过 (目标数: {stats['total_goals']}, 连续天数: {stats['streak']})")

    # -------------------------------------------------------------
    # 模块 4: 前端路由与移动端 PWA 资源规范校验
    # -------------------------------------------------------------
    print("\n[4/4] 正在验证前端路由与移动端 PWA 离线资源支持...")
    pages = ["/", "/goals", "/log", "/advisor", "/review", "/profile"]
    for page in pages:
        r = client.get(page)
        assert r.status_code == 200, f"页面 {page} 无法正常访问 (HTTP {r.status_code})"
    print(f"  [PASS] 6 大核心前端页面渲染状态码均为 200 OK")

    # 校验 PWA manifest.json 与图标资源
    res_manifest = client.get("/static/manifest.json")
    assert res_manifest.status_code == 200, "manifest.json 不可访问"
    manifest_data = res_manifest.json()
    assert manifest_data["display"] == "standalone"
    assert manifest_data["theme_color"] == "#000000"
    print("  [PASS] PWA manifest.json 配置完全合规 (支持手机无边框全屏独立运行)")

    res_icon = client.get("/static/icon-192.png")
    assert res_icon.status_code == 200, "192x192 PWA 图标资源不存在"
    print("  [PASS] 移动端 PWA 物理图标资源加载正常")

    print("\n" + "=" * 60)
    print("🎉 阶段 4 验收测试与全流程端到端 (E2E) 集成测试全部通过！")
    print("=" * 60 + "\n")

if __name__ == "__main__":
    test_stage4_acceptance_and_e2e()
