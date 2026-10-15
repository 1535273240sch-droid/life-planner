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

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from main import app
from database import SessionLocal
from services import stats_service, profile_service, goal_service, log_service
from schemas import DailyLogCreate, GoalCreate

client = TestClient(app)

def test_p0_2_crisis_safety_interception():
    """验证 P0-2: 自伤/危机极端情况确定性短路拦截"""
    crisis_questions = [
        "我最近很绝望，不想活了",
        "活着好累想死",
        "我想自杀，你觉得呢"
    ]
    for q in crisis_questions:
        res = client.post("/api/ai/ask", json={"question": q})
        assert res.status_code == 200, f"请求失败: {res.text}"
        data = res.json()
        assert data.get("is_crisis") is True, f"未触发危机标记: {q}"
        assert data.get("source") == "crisis_safety_guard", f"来源应为安全防线: {data.get('source')}"
        assert "必须停止规划并寻求专业支持" in data.get("conclusion", "")
        # 必须包含权威求助热线
        alt_text = data.get("alternative", "")
        assert "400-161-9995" in alt_text, "缺少全国希望生命救助热线"
        assert "010-82951332" in alt_text, "缺少北京危机干预热线"
        assert "12355" in alt_text, "缺少青少年援助热线"

def test_p0_1_source_transparency_in_fallback():
    """验证 P0-1: 降级路径透明标记 source，不伪造真实 AI 输出"""
    # 1. 顾问接口
    res = client.post("/api/ai/ask", json={"question": "我想买一台新电脑"})
    assert res.status_code == 200
    data = res.json()
    assert "source" in data
    assert data["source"] in ["offline_mock", "ai_model"]

    # 2. 每日规划接口
    res = client.post("/api/ai/daily-plan?auto_apply=false")
    assert res.status_code == 200
    plan = res.json()
    assert "source" in plan
    assert plan["source"] in ["offline_mock", "ai_model"]

    # 3. 复盘接口
    res = client.post("/api/ai/review", json={"type": "daily"})
    assert res.status_code == 200
    review = res.json()
    assert "source" in review

    # 4. 拆解接口
    res = client.post("/api/goals/ai-breakdown", json={"goal_title": "测试自动化构建"})
    assert res.status_code == 200
    breakdown = res.json()
    assert "source" in breakdown

def test_p0_3_sleep_parsing_and_gap_detection():
    """验证 P0-3: 缺失睡眠返回 None，检出连续未记录睡眠缺口"""
    # 纯空或格式不符返回 None
    assert stats_service.parse_sleep_hours(None, None) is None
    assert stats_service.parse_sleep_hours("", "") is None
    assert stats_service.parse_sleep_hours("invalid", "none") is None
    # 正常时间计算
    assert stats_service.parse_sleep_hours("23:00", "07:30") == 8.5
    assert stats_service.parse_sleep_hours("01:00", "07:00") == 6.0

    # 数据库隔离测试：连续 5 天未填写睡眠，应检出连续未记录预警
    db = SessionLocal()
    test_user_id = 8888
    try:
        from models import Goal, Task, DailyLog, Profile
        db.query(DailyLog).filter(DailyLog.user_id == test_user_id).delete()
        db.commit()

        profile_service.get_or_create_user(db, test_user_id)
        # 写入近 5 天无睡眠数据的记录
        today = datetime.date.today()
        for i in range(5):
            d = today - datetime.timedelta(days=i)
            log_service.upsert_daily_log(db, test_user_id, DailyLogCreate(
                date=d,
                done_items=f"测试工作第 {i} 天",
                study_minutes=60,
                sleep_start=None,
                sleep_end=None,
                meals={"lunch": "青菜鸡胸肉"}
            ))

        gaps = stats_service.detect_life_gaps(db, test_user_id)
        sleep_gap_titles = [g["title"] for g in gaps if g["category"] == "睡眠与体能"]
        assert any("连续多天未记录作息睡眠" in t for t in sleep_gap_titles), f"未检出未记录睡眠缺口: {sleep_gap_titles}"
    finally:
        db.close()

def test_p0_4_goal_stagnation_ai_long_title_matching():
    """验证 P0-4: 目标停滞检测对 AI 拆解的长标题不误报"""
    db = SessionLocal()
    test_user_id = 9999
    try:
        from models import Goal, Task, DailyLog, Profile
        db.query(Task).filter(Task.user_id == test_user_id).delete()
        db.query(Goal).filter(Goal.user_id == test_user_id).delete()
        db.query(DailyLog).filter(DailyLog.user_id == test_user_id).delete()
        db.commit()

        profile_service.get_or_create_user(db, test_user_id)
        # 创建带有书名号的长标题目标
        ai_goal = goal_service.create_goal(db, test_user_id, GoalCreate(
            title="攻坚「雅思7分」核心里程碑拆解与执行",
            level="stage",
            category="语言",
            progress=20,
            status="in_progress"
        ))

        # 用户日记写自然语言，包含核心关键词「雅思」
        today = datetime.date.today()
        for i in range(3):
            d = today - datetime.timedelta(days=i)
            log_service.upsert_daily_log(db, test_user_id, DailyLogCreate(
                date=d,
                done_items="今天认真做了雅思听力和阅读练习，背了200个核心词汇",
                study_minutes=90,
                sleep_start="23:30",
                sleep_end="07:30"
            ))

        gaps = stats_service.detect_life_gaps(db, test_user_id)
        stagnant_gaps = [g for g in gaps if g["category"] == "目标停滞" and "雅思7分" in g["title"]]
        assert len(stagnant_gaps) == 0, f"AI 长标题目标被误报为停滞: {stagnant_gaps}"

        # 验证真正停滞的目标仍能被准确检出
        neglected_goal = goal_service.create_goal(db, test_user_id, GoalCreate(
            title="学习古希腊哲学原著精读",
            level="stage",
            category="文化",
            progress=0,
            status="in_progress"
        ))
        gaps_after = stats_service.detect_life_gaps(db, test_user_id)
        neglected_gaps = [g for g in gaps_after if g["category"] == "目标停滞" and "古希腊哲学" in g["title"]]
        assert len(neglected_gaps) == 1, "真正零投入的目标应当被精准检出"
    finally:
        db.close()

def test_p1_1_cors_middleware():
    """验证 P1-1: 后端已配置全局 CORS 允许跨域及预检请求"""
    # 模拟 OPTIONS 跨域预检
    headers = {
        "Origin": "http://192.168.1.100:8000",
        "Access-Control-Request-Method": "POST",
        "Access-Control-Request-Headers": "content-type"
    }
    res = client.options("/api/ai/ask", headers=headers)
    assert res.status_code == 200, f"CORS 预检失败: {res.status_code}"
    assert res.headers.get("access-control-allow-origin") in ["*", "http://192.168.1.100:8000"]

def test_clean_profile_initialization():
    """验证新创建的用户档案不注入虚假默认病史与花粉过敏"""
    db = SessionLocal()
    fresh_user_id = 7777
    try:
        from models import Profile
        db.query(Profile).filter(Profile.user_id == fresh_user_id).delete()
        db.commit()

        profile = profile_service.get_or_create_profile(db, fresh_user_id)
        assert profile.health_info == {}, "新档案不应种入假健康数据"
        assert profile.occupation == "", "新档案职业应为空字符串"
        assert profile.vision == "", "新档案愿景应为空字符串"
    finally:
        db.close()
