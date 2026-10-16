"""测试公共辅助：造数据、合法的模型回复样本、副作用计数。"""
import datetime

import database
from models import AiMessage, Goal, Task

VISION_TEXT = "成为能独立交付医疗影像AI产品的工程师"
SKILL_NAME = "Rust内存模型"
ALLERGY_TEXT = "花生过敏"
LAST_LOG_ITEM = "复现了PCIe链路训练失败的问题"

ASK_ENDPOINT = ("ask", "post", "/api/ai/ask", {"question": "我想买一台新电脑，合适吗"})
PLAN_ENDPOINT = ("daily-plan", "post", "/api/ai/daily-plan", None)
DAILY_REVIEW_ENDPOINT = ("review-daily", "post", "/api/ai/review", {"type": "daily"})
WEEKLY_REVIEW_ENDPOINT = ("review-weekly", "post", "/api/ai/review", {"type": "weekly"})
GOALS_BREAKDOWN_ENDPOINT = ("goals-ai-breakdown", "post", "/api/goals/ai-breakdown", {"goal_title": "三个月内掌握嵌入式Linux驱动开发"})
AI_BREAKDOWN_ENDPOINT = ("ai-breakdown", "post", "/api/ai/breakdown", {"goal_title": "三个月内掌握嵌入式Linux驱动开发"})

ALL_AI_ENDPOINTS = [
    ASK_ENDPOINT,
    PLAN_ENDPOINT,
    DAILY_REVIEW_ENDPOINT,
    WEEKLY_REVIEW_ENDPOINT,
    GOALS_BREAKDOWN_ENDPOINT,
    AI_BREAKDOWN_ENDPOINT,
]
ENDPOINT_IDS = [e[0] for e in ALL_AI_ENDPOINTS]


def call(client, endpoint):
    _, method, path, body = endpoint
    kwargs = {"json": body} if body is not None else {}
    return getattr(client, method)(path, **kwargs)


# ---------------------------------------------------------------- 造数据

def put_profile(client, **overrides):
    payload = {
        "age": 29,
        "occupation": "嵌入式软件工程师",
        "industry": "半导体",
        "vision": VISION_TEXT,
        "constraints": "工作日每天最多2小时学习，不接受周末加班",
        "health_info": {
            "height": 178,
            "weight": 72,
            "sleep_habit": "23:30入睡",
            "diet_pref": "高蛋白",
            "taboo": "辛辣",
            "allergy_or_disease": ALLERGY_TEXT,
        },
    }
    payload.update(overrides)
    res = client.put("/api/profile", json=payload)
    assert res.status_code == 200, res.text
    return res.json()


def add_skill(client, name=SKILL_NAME, level=7):
    res = client.post("/api/skills", json={"name": name, "category": "编程", "level": level})
    assert res.status_code == 200, res.text
    return res.json()["id"]


def add_goal(client, title, level="long", parent_id=None, progress=0, status="in_progress", **extra):
    payload = {"title": title, "level": level, "progress": progress, "status": status, "category": "职业"}
    if parent_id is not None:
        payload["parent_id"] = parent_id
    payload.update(extra)
    res = client.post("/api/goals", json=payload)
    assert res.status_code == 200, res.text
    return res.json()["id"]


def add_log(client, day=None, **overrides):
    day = day or datetime.date.today()
    payload = {
        "date": day.isoformat(),
        "done_items": "整理了周报",
        "study_minutes": 30,
        "mood": 3,
        "energy": 3,
    }
    payload.update(overrides)
    res = client.post("/api/logs", json=payload)
    assert res.status_code == 200, res.text
    return res.json()


def days_ago(n):
    return datetime.date.today() - datetime.timedelta(days=n)


def add_other_user_goal(title="别人的目标", progress=10, status="in_progress", user_id=2):
    """直接写库，给 user_id=2 建一个目标，用于验证多用户隔离。返回目标 id。"""
    from models import User

    session = database.SessionLocal()
    try:
        if session.get(User, user_id) is None:
            session.add(User(id=user_id, nickname="另一个用户"))
            session.commit()
        goal = Goal(user_id=user_id, title=title, level="long", category="职业", progress=progress, status=status)
        session.add(goal)
        session.commit()
        return goal.id
    finally:
        session.close()


def delete_goal(client, goal_id):
    res = client.delete(f"/api/goals/{goal_id}")
    assert res.status_code == 200, res.text


def seed_user_data(client):
    """档案 + 技能 + 一个目标 + 最近一天日志，返回目标 id。"""
    put_profile(client)
    add_skill(client)
    goal_id = add_goal(client, "完成驱动开发入门课程", level="stage", progress=20)
    add_log(client, days_ago(1), done_items=LAST_LOG_ITEM, study_minutes=60, study_note="读了PCIe规范第三章")
    return goal_id


# ---------------------------------------------------------------- 副作用计数

def row_counts():
    """返回 (ai_messages, tasks, goals) 的行数，用新 Session 读取以免读到缓存。"""
    session = database.SessionLocal()
    try:
        return (
            session.query(AiMessage).count(),
            session.query(Task).count(),
            session.query(Goal).count(),
        )
    finally:
        session.close()


def query_all(model, *criteria):
    session = database.SessionLocal()
    try:
        return session.query(model).filter(*criteria).order_by(model.id.asc()).all()
    finally:
        session.close()


def get_goal_row(goal_id):
    session = database.SessionLocal()
    try:
        goal = session.get(Goal, goal_id)
        if goal is not None:
            session.expunge(goal)
        return goal
    finally:
        session.close()


# ---------------------------------------------------------------- 合法的模型回复样本

def valid_ask():
    return {
        "conclusion": "可以但有条件",
        "reason": "你本月的存款目标还没有完成，但现有电脑已经影响到日常编译效率。",
        "pros": "编译时间缩短，学习效率提升。",
        "cons_risks": "短期现金流变紧。",
        "alternative": "先买二手准新机，预算控制在六千以内。",
        "goal_relation": "有助于推进驱动开发入门课程。",
    }


def valid_daily_plan(goal_id=None):
    return {
        "tasks": [
            {"title": "阅读PCIe规范第四章并做笔记", "goal_id": goal_id, "goal_name": "完成驱动开发入门课程", "est_minutes": 45},
            {"title": "晚间散步三十分钟", "goal_id": None, "goal_name": "身心健康", "est_minutes": 30},
        ],
        "health_reminder": "昨晚睡眠偏短，今天避免咖啡因过量。",
        "tip": "连续两天都在推进课程，保持住。",
    }


def valid_daily_review():
    return {
        "type": "daily",
        "title": "每日复盘",
        "summary": "今天推进了PCIe问题的复现。",
        "highlights": "复现了链路训练失败的问题。",
        "deviations": "晚间学习时间不足。",
        "next_actions": "明天上午先写复现脚本。",
    }


def valid_weekly_review(progress_suggestions=None):
    payload = {
        "type": "weekly",
        "title": "周度复盘",
        "summary": "本周学习投入稳定。",
        "goal_progress": "驱动开发入门课程推进明显。",
        "biggest_deviation": "周三熬夜导致周四效率下降。",
        "next_week_strategy": "固定每晚九点开始学习。",
    }
    if progress_suggestions is not None:
        payload["progress_suggestions"] = progress_suggestions
    return payload


def valid_reply_for(endpoint):
    """给定 ALL_AI_ENDPOINTS 中的一项，返回能让它成功的模型回复（dict 或 Markdown 文本）。"""
    name = endpoint[0]
    if name == "ask":
        return valid_ask()
    if name == "daily-plan":
        return valid_daily_plan()
    if name == "review-daily":
        return valid_daily_review()
    if name == "review-weekly":
        return valid_weekly_review([])
    return BREAKDOWN_MARKDOWN


JSON_ENDPOINTS = [e for e in ALL_AI_ENDPOINTS if e[0] in ("ask", "daily-plan", "review-daily", "review-weekly")]
JSON_ENDPOINT_IDS = [e[0] for e in JSON_ENDPOINTS]


BREAKDOWN_MARKDOWN = """### 三个月驱动开发拆解

#### 阶段一：基础筑底 (第 1-4 周)
- **阶段里程碑**：搭好开发环境并跑通第一个内核模块。
- **周行动清单**：
  - 第 1 周：配置交叉编译环境
  - 第 2-4 周：完成字符设备驱动示例
- **检验标准 (Metric)**：能独立编译并加载内核模块。

#### 阶段二：实战攻坚 (第 5-8 周)
- **阶段里程碑**：完成一个真实外设的驱动。
- **周行动清单**：
  - 第 5-6 周：阅读外设数据手册并写出寄存器映射
  - 第 7-8 周：完成中断处理与调试
- **检验标准 (Metric)**：外设在目标板上稳定工作。
"""
