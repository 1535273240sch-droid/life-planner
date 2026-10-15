import datetime
from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session
from typing import Dict, Any, List
from database import get_db
from services import log_service, goal_service, profile_service, stats_service
from config import settings

router = APIRouter(prefix="/api/stats", tags=["统计与可视化"])

@router.get("/overview")
def get_stats_overview(db: Session = Depends(get_db)):
    streak = log_service.calculate_streak(db, user_id=settings.DEFAULT_USER_ID)
    goals = goal_service.get_goals(db, user_id=settings.DEFAULT_USER_ID)
    total_goals = len(goals)
    completed_goals = len([g for g in goals if g.status == "completed"])
    in_progress_goals = len([g for g in goals if g.status == "in_progress"])

    return {
        "streak": streak,
        "total_goals": total_goals,
        "completed_goals": completed_goals,
        "in_progress_goals": in_progress_goals
    }

@router.get("/trends")
def get_stats_trends(
    days: int = Query(30, ge=7, le=90),
    db: Session = Depends(get_db)
):
    """获取近 30 天生活指标趋势数据，用于 Chart.js 图表渲染"""
    end_date = datetime.date.today()
    start_date = end_date - datetime.timedelta(days=days - 1)
    
    logs = log_service.get_logs_range(db, user_id=settings.DEFAULT_USER_ID, start_date=start_date, end_date=end_date)
    logs_by_date = {l.date.isoformat(): l for l in logs}

    labels = []
    study_data = []
    sleep_data = []
    mood_data = []
    energy_data = []

    curr = start_date
    while curr <= end_date:
        d_str = curr.isoformat()
        labels.append(curr.strftime("%m-%d"))
        
        log = logs_by_date.get(d_str)
        if log:
            study_data.append(log.study_minutes)
            sleep_hours = stats_service.parse_sleep_hours(log.sleep_start, log.sleep_end)
            sleep_data.append(sleep_hours)
            mood_data.append(log.mood)
            energy_data.append(log.energy)
        else:
            study_data.append(0)
            sleep_data.append(None)
            mood_data.append(None)
            energy_data.append(None)

        curr += datetime.timedelta(days=1)

    return {
        "labels": labels,
        "study_minutes": study_data,
        "sleep_hours": sleep_data,
        "mood": mood_data,
        "energy": energy_data
    }
