from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from typing import List, Optional
import datetime
from database import get_db
from schemas import DailyLogOut, DailyLogCreate
from services import log_service
from config import settings

router = APIRouter(prefix="/api/logs", tags=["每日记录"])

@router.get("", response_model=Optional[DailyLogOut])
def get_log(
    date: Optional[str] = Query(None, description="日期 YYYY-MM-DD，默认今天"),
    db: Session = Depends(get_db)
):
    target_date = datetime.date.today()
    if date:
        try:
            target_date = datetime.date.fromisoformat(date)
        except ValueError:
            pass
    log = log_service.get_log_by_date(db, user_id=settings.DEFAULT_USER_ID, target_date=target_date)
    return log

@router.get("/recent", response_model=List[DailyLogOut])
def get_recent_logs(
    days: int = Query(7, ge=1, le=90),
    db: Session = Depends(get_db)
):
    return log_service.get_recent_logs(db, user_id=settings.DEFAULT_USER_ID, days=days)

@router.post("", response_model=DailyLogOut)
def save_daily_log(log_in: DailyLogCreate, db: Session = Depends(get_db)):
    return log_service.upsert_daily_log(db, user_id=settings.DEFAULT_USER_ID, log_in=log_in)
