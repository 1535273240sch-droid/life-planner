import datetime
from typing import List, Optional
from sqlalchemy.orm import Session
from models import DailyLog
from schemas import DailyLogCreate, DailyLogUpdate
from services.profile_service import get_or_create_user

def get_log_by_date(db: Session, user_id: int = 1, target_date: Optional[datetime.date] = None) -> Optional[DailyLog]:
    if not target_date:
        target_date = datetime.date.today()
    return db.query(DailyLog).filter(DailyLog.user_id == user_id, DailyLog.date == target_date).first()

def get_recent_logs(db: Session, user_id: int = 1, days: int = 7) -> List[DailyLog]:
    start_date = datetime.date.today() - datetime.timedelta(days=days - 1)
    return db.query(DailyLog).filter(
        DailyLog.user_id == user_id,
        DailyLog.date >= start_date
    ).order_by(DailyLog.date.desc()).all()

def get_logs_range(db: Session, user_id: int = 1, start_date: Optional[datetime.date] = None, end_date: Optional[datetime.date] = None) -> List[DailyLog]:
    query = db.query(DailyLog).filter(DailyLog.user_id == user_id)
    if start_date:
        query = query.filter(DailyLog.date >= start_date)
    if end_date:
        query = query.filter(DailyLog.date <= end_date)
    return query.order_by(DailyLog.date.asc()).all()

def upsert_daily_log(db: Session, user_id: int, log_in: DailyLogCreate) -> DailyLog:
    get_or_create_user(db, user_id)
    log_date = log_in.date or datetime.date.today()
    existing_log = db.query(DailyLog).filter(DailyLog.user_id == user_id, DailyLog.date == log_date).first()

    if existing_log:
        data = log_in.model_dump(exclude_unset=True)
        for field, value in data.items():
            setattr(existing_log, field, value)
        db.commit()
        db.refresh(existing_log)
        return existing_log
    else:
        new_log = DailyLog(
            user_id=user_id,
            date=log_date,
            done_items=log_in.done_items,
            study_minutes=log_in.study_minutes,
            study_note=log_in.study_note,
            meals=log_in.meals or {},
            sleep_start=log_in.sleep_start,
            sleep_end=log_in.sleep_end,
            exercise_type=log_in.exercise_type,
            exercise_minutes=log_in.exercise_minutes,
            mood=log_in.mood,
            energy=log_in.energy,
            reflection=log_in.reflection
        )
        db.add(new_log)
        db.commit()
        db.refresh(new_log)
        return new_log

def calculate_streak(db: Session, user_id: int = 1) -> int:
    """计算连续记录天数 (单次查询优化，避免 N+1 循环)"""
    today = datetime.date.today()
    dates = {row[0] for row in db.query(DailyLog.date).filter(DailyLog.user_id == user_id).all()}
    if not dates:
        return 0

    streak = 0
    curr = today
    if curr not in dates:
        curr = today - datetime.timedelta(days=1)

    while curr in dates:
        streak += 1
        curr -= datetime.timedelta(days=1)
    return streak
