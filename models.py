import datetime
from sqlalchemy import (
    Column, Integer, String, Text, Boolean, Date, DateTime, ForeignKey, JSON, UniqueConstraint
)
from sqlalchemy.orm import relationship
from database import Base

def utc_now():
    return datetime.datetime.now(datetime.timezone.utc)

class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True)
    nickname = Column(String(50), default="探索者")
    created_at = Column(DateTime, default=utc_now)

    # 关联
    profile = relationship("Profile", back_populates="user", uselist=False, cascade="all, delete-orphan")
    skills = relationship("Skill", back_populates="user", cascade="all, delete-orphan")
    goals = relationship("Goal", back_populates="user", cascade="all, delete-orphan")
    tasks = relationship("Task", back_populates="user", cascade="all, delete-orphan")
    daily_logs = relationship("DailyLog", back_populates="user", cascade="all, delete-orphan")
    ai_messages = relationship("AiMessage", back_populates="user", cascade="all, delete-orphan")


class Profile(Base):
    __tablename__ = "profiles"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), unique=True, nullable=False)
    age = Column(Integer, nullable=True)
    gender = Column(String(20), nullable=True)
    occupation = Column(String(100), nullable=True)
    industry = Column(String(100), nullable=True)
    years_of_work = Column(Integer, nullable=True)
    education = Column(String(50), nullable=True)
    major = Column(String(100), nullable=True)
    
    # health_info 格式: {"height": 175, "weight": 70, "sleep_habit": "...", "diet_pref": "...", "taboo": "...", "allergy_or_disease": "..."}
    health_info = Column(JSON, default=dict)
    
    vision = Column(Text, nullable=True)          # 人生愿景: 我未来想成为什么样的人
    constraints = Column(Text, nullable=True)     # 价值观与限制: 每天可用时间、经济状况、不愿意做的事
    updated_at = Column(DateTime, default=utc_now, onupdate=utc_now)

    user = relationship("User", back_populates="profile")


class Skill(Base):
    __tablename__ = "skills"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    name = Column(String(100), nullable=False)      # 技能名称，例如 "Python", "英语听说"
    category = Column(String(50), default="通用")   # 类别: 编程/语言/职场/生活等
    level = Column(Integer, default=5)              # 1-10
    note = Column(Text, nullable=True)              # 备注

    user = relationship("User", back_populates="skills")


class Goal(Base):
    __tablename__ = "goals"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    parent_id = Column(Integer, ForeignKey("goals.id", ondelete="SET NULL"), nullable=True)
    
    # 层级: vision (愿景) / long (长期 1-5年) / stage (阶段 1-3个月) / weekly (周目标)
    level = Column(String(20), default="long")
    title = Column(String(200), nullable=False)
    category = Column(String(50), default="职业")   # 职业/学习/语言/健康/财务/其他
    description = Column(Text, nullable=True)
    metric = Column(String(200), nullable=True)     # 衡量标准，如 "雅思7分", "体重70kg"
    start_date = Column(Date, nullable=True)
    due_date = Column(Date, nullable=True)
    progress = Column(Integer, default=0)           # 0-100
    status = Column(String(20), default="in_progress") # in_progress (进行中) / completed (已完成) / paused (暂停)
    deleted_at = Column(DateTime, nullable=True)    # 软删除时间；非空即视为已删除，名下历史任务保留

    user = relationship("User", back_populates="goals")
    parent = relationship("Goal", remote_side=[id], backref="children")
    tasks = relationship("Task", back_populates="goal")


class Task(Base):
    __tablename__ = "tasks"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    goal_id = Column(Integer, ForeignKey("goals.id", ondelete="SET NULL"), nullable=True)
    date = Column(Date, default=datetime.date.today)
    title = Column(String(200), nullable=False)
    est_minutes = Column(Integer, default=30)
    done = Column(Boolean, default=False)
    done_at = Column(DateTime, nullable=True)

    user = relationship("User", back_populates="tasks")
    goal = relationship("Goal", back_populates="tasks")


class DailyLog(Base):
    __tablename__ = "daily_logs"
    __table_args__ = (
        UniqueConstraint("user_id", "date", name="uix_user_daily_log"),
    )

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    date = Column(Date, default=datetime.date.today, index=True)
    
    done_items = Column(Text, nullable=True)        # 今日完成的事项 (文字或多行)
    study_minutes = Column(Integer, default=0)      # 今日学习时长
    study_note = Column(Text, nullable=True)        # 今日学习内容 (语言/技能/阅读)
    
    # 饮食记录: {"breakfast": "...", "lunch": "...", "dinner": "...", "snack": "..."}
    meals = Column(JSON, default=dict)
    
    sleep_start = Column(String(20), nullable=True) # 几点睡，例如 "23:30"
    sleep_end = Column(String(20), nullable=True)   # 几点起，例如 "07:30"
    exercise_type = Column(String(50), nullable=True) # 运动类型: 慢跑/游泳/力量训练等
    exercise_minutes = Column(Integer, default=0)   # 运动时长
    mood = Column(Integer, default=3)               # 心情 (1-5分)
    energy = Column(Integer, default=3)             # 精力 (1-5分)
    reflection = Column(Text, nullable=True)        # 今日反思与一句话备注

    user = relationship("User", back_populates="daily_logs")


class AiMessage(Base):
    __tablename__ = "ai_messages"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    # 模式: daily_plan (每日规划) / advisor (决策顾问) / review (复盘) / gap (缺口提醒) / breakdown (目标拆解)
    mode = Column(String(30), nullable=False)
    question = Column(Text, nullable=True)
    answer = Column(Text, nullable=False)
    source = Column(String(50), default="ai_model") # ai_model / crisis_safety_guard / legacy_unverified
    model_name = Column(String(100), nullable=True) # 实际调用的模型名；非 AI 产出（如危机拦截）为空
    created_at = Column(DateTime, default=utc_now)

    user = relationship("User", back_populates="ai_messages")
