from pydantic import BaseModel, Field, ConfigDict
from typing import Optional, List, Dict, Any
from datetime import date as dt_date, datetime as dt_datetime

# --- Profile Schemas ---
class ProfileBase(BaseModel):
    age: Optional[int] = None
    gender: Optional[str] = None
    occupation: Optional[str] = None
    industry: Optional[str] = None
    years_of_work: Optional[int] = None
    education: Optional[str] = None
    major: Optional[str] = None
    health_info: Optional[Dict[str, Any]] = Field(default_factory=dict)
    vision: Optional[str] = None
    constraints: Optional[str] = None

class ProfileUpdate(ProfileBase):
    pass

class ProfileOut(ProfileBase):
    id: int
    user_id: int
    updated_at: Optional[dt_datetime] = None
    model_config = ConfigDict(from_attributes=True)


# --- Skill Schemas ---
class SkillBase(BaseModel):
    name: str
    category: Optional[str] = "通用"
    level: int = Field(default=5, ge=1, le=10)
    note: Optional[str] = None

class SkillCreate(SkillBase):
    pass

class SkillUpdate(BaseModel):
    name: Optional[str] = None
    category: Optional[str] = None
    level: Optional[int] = Field(default=None, ge=1, le=10)
    note: Optional[str] = None

class SkillOut(SkillBase):
    id: int
    user_id: int
    model_config = ConfigDict(from_attributes=True)


# --- Goal Schemas ---
class GoalBase(BaseModel):
    parent_id: Optional[int] = None
    level: str = "long"  # vision / long / stage / weekly
    title: str
    category: Optional[str] = "职业"
    description: Optional[str] = None
    metric: Optional[str] = None
    start_date: Optional[dt_date] = None
    due_date: Optional[dt_date] = None
    progress: int = Field(default=0, ge=0, le=100)
    status: str = "in_progress"  # in_progress / completed / paused

class GoalCreate(GoalBase):
    pass

class GoalBatchCreate(BaseModel):
    parent_id: Optional[int] = None
    goals: List[GoalCreate]

class GoalUpdate(BaseModel):
    parent_id: Optional[int] = None
    level: Optional[str] = None
    title: Optional[str] = None
    category: Optional[str] = None
    description: Optional[str] = None
    metric: Optional[str] = None
    start_date: Optional[dt_date] = None
    due_date: Optional[dt_date] = None
    progress: Optional[int] = Field(default=None, ge=0, le=100)
    status: Optional[str] = None

class GoalOut(GoalBase):
    id: int
    user_id: int
    children: List['GoalOut'] = []
    model_config = ConfigDict(from_attributes=True)

GoalOut.model_rebuild()


# --- Task Schemas ---
class TaskBase(BaseModel):
    goal_id: Optional[int] = None
    date: Optional[dt_date] = None
    title: str
    est_minutes: int = 30
    done: bool = False

class TaskCreate(TaskBase):
    pass

class TaskUpdate(BaseModel):
    goal_id: Optional[int] = None
    date: Optional[dt_date] = None
    title: Optional[str] = None
    est_minutes: Optional[int] = None
    done: Optional[bool] = None

class TaskOut(TaskBase):
    id: int
    user_id: int
    done_at: Optional[dt_datetime] = None
    model_config = ConfigDict(from_attributes=True)


# --- DailyLog Schemas ---
class DailyLogBase(BaseModel):
    date: Optional[dt_date] = None
    done_items: Optional[str] = None
    study_minutes: int = 0
    study_note: Optional[str] = None
    meals: Optional[Dict[str, Any]] = Field(default_factory=dict)
    sleep_start: Optional[str] = None
    sleep_end: Optional[str] = None
    exercise_type: Optional[str] = None
    exercise_minutes: int = 0
    mood: int = Field(default=3, ge=1, le=5)
    energy: int = Field(default=3, ge=1, le=5)
    reflection: Optional[str] = None

class DailyLogCreate(DailyLogBase):
    pass

class DailyLogUpdate(DailyLogBase):
    pass

class DailyLogOut(DailyLogBase):
    id: int
    user_id: int
    model_config = ConfigDict(from_attributes=True)


# --- AI Schemas ---
class AIAskRequest(BaseModel):
    question: str

class AIReviewRequest(BaseModel):
    type: str = "daily"  # daily / weekly
    date: Optional[str] = None

class AIBreakdownRequest(BaseModel):
    goal_id: Optional[int] = None
    goal_title: Optional[str] = None
    goal_description: Optional[str] = None

class AIMessageOut(BaseModel):
    id: int
    mode: str
    question: Optional[str] = None
    answer: str
    source: Optional[str] = "ai_model"
    created_at: dt_datetime
    model_config = ConfigDict(from_attributes=True)


# --- AI Breakdown Apply Schemas ---
class ApplyBreakdownWeeklyItem(BaseModel):
    title: str
    description: Optional[str] = None
    metric: Optional[str] = None
    category: Optional[str] = None
    start_date: Optional[dt_date] = None
    due_date: Optional[dt_date] = None

class ApplyBreakdownStageItem(BaseModel):
    title: str
    description: Optional[str] = None
    metric: Optional[str] = None
    category: Optional[str] = None
    start_date: Optional[dt_date] = None
    due_date: Optional[dt_date] = None
    weekly_goals: Optional[List[ApplyBreakdownWeeklyItem]] = Field(default_factory=list)
    weekly_tasks: Optional[List[ApplyBreakdownWeeklyItem]] = Field(default_factory=list)

class ApplyBreakdownRequest(BaseModel):
    parent_id: Optional[int] = None
    goal_title: Optional[str] = None
    category: Optional[str] = "职业"
    plan: Optional[str] = None
    stages: Optional[List[ApplyBreakdownStageItem]] = Field(default_factory=list)

class ApplyBreakdownResponse(BaseModel):
    success: bool = True
    message: str
    root_goal_id: Optional[int] = None
    created_stages: int = 0
    created_weekly: int = 0

