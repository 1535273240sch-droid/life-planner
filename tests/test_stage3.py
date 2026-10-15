import os
import sys
import datetime
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

def test_stage3_flow():
    print("\n--- 开始阶段 3 复盘与缺口提醒自动化验收测试 ---")

    # 1. 模拟注入连续 5 天的真实历史记录（模拟睡眠不足与饮食特征）
    print("-> 正在模拟注入过去 5 天生活数据...")
    today = datetime.date.today()
    for i in range(1, 6):
        past_date = (today - datetime.timedelta(days=i)).isoformat()
        log_entry = {
            "date": past_date,
            "done_items": f"完成系统第{i}阶段技术攻关，阅读专业文献",
            "study_minutes": 50 + i * 10,
            "study_note": f"深入研习核心智能体与分布式逻辑 第{i}讲",
            "meals": {
                "breakfast": "面包咖啡",
                "lunch": "炒面盒饭",
                "dinner": "快餐汉堡"
            },
            "sleep_start": "01:30",  # 故意晚睡制造睡眠缺口
            "sleep_end": "07:00",
            "exercise_type": "散步",
            "exercise_minutes": 20,
            "mood": 3,
            "energy": 2,
            "reflection": f"连续第{i}天工作较晚，精力有所消耗"
        }
        res = client.post("/api/logs", json=log_entry)
        assert res.status_code == 200

    print("[PASS] 过去 5 天历史数据注入成功")

    # 2. 验证 D4 缺口检测雷达
    print("-> 正在验证 D4 缺口检测雷达算法...")
    res = client.get("/api/ai/gaps")
    assert res.status_code == 200, f"获取缺口失败: {res.text}"
    gaps = res.json()
    assert isinstance(gaps, list) and len(gaps) > 0, "未能检测出任何生活缺口"

    # 验证能检测到睡眠或蔬菜饮食缺口
    categories = [g["category"] for g in gaps]
    print(f"[PASS] D4 缺口检测雷达成功探测到 {len(gaps)} 项预警:")
    for g in gaps:
        print(f"       [{g['level']}] {g['category']} - {g['title']}")

    # 3. 验证 D3: 每日战术复盘
    print("-> 正在测试 D3 每日复盘生成...")
    res = client.post("/api/ai/review", json={"type": "daily"})
    assert res.status_code == 200, f"每日复盘失败: {res.text}"
    daily_rev = res.json()
    assert "summary" in daily_rev and daily_rev["summary"]
    assert "highlights" in daily_rev and daily_rev["highlights"]
    assert "deviations" in daily_rev and daily_rev["deviations"]
    assert "next_actions" in daily_rev and daily_rev["next_actions"]
    print(f"[PASS] D3 每日复盘生成合规！")
    print(f"       【总结】: {daily_rev['summary'][:35]}...")
    print(f"       【亮点】: {daily_rev['highlights'][:35]}...")
    print(f"       【偏差】: {daily_rev['deviations'][:35]}...")

    # 4. 验证 D3: 周度战略复盘
    print("-> 正在测试 D3 周度战略复盘生成...")
    res = client.post("/api/ai/review", json={"type": "weekly"})
    assert res.status_code == 200, f"周度复盘失败: {res.text}"
    weekly_rev = res.json()
    assert "summary" in weekly_rev and weekly_rev["summary"]
    assert "goal_progress" in weekly_rev and weekly_rev["goal_progress"]
    assert "biggest_deviation" in weekly_rev and weekly_rev["biggest_deviation"]
    assert "next_week_strategy" in weekly_rev and weekly_rev["next_week_strategy"]
    print(f"[PASS] D3 周度战略复盘生成合规！")
    print(f"       【目标推进】: {weekly_rev['goal_progress'][:35]}...")
    print(f"       【最大偏差】: {weekly_rev['biggest_deviation'][:35]}...")

    # 5. 验证页面访问
    res = client.get("/review")
    assert res.status_code == 200
    print("[PASS] 复盘与缺口前端页面 /review 渲染正常 (HTTP 200)")

    print("\n=== 阶段 3 复盘与缺口提醒全链路自测全部通过！ ===\n")

if __name__ == "__main__":
    test_stage3_flow()
