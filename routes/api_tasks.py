from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from typing import List, Optional
import datetime
from database import get_db
from schemas import TaskOut, TaskCreate, TaskUpdate
from services import goal_service
from config import settings

router = APIRouter(prefix="/api/tasks", tags=["每日任务"])

@router.get("", response_model=List[TaskOut])
def list_tasks(
    date: Optional[str] = Query(None, description="日期 YYYY-MM-DD，默认今天"),
    goal_id: Optional[int] = Query(None, description="所属目标 ID"),
    db: Session = Depends(get_db)
):
    target_date = None
    if date:
        try:
            target_date = datetime.date.fromisoformat(date)
        except ValueError:
            target_date = datetime.date.today()
    else:
        target_date = datetime.date.today()
    return goal_service.get_tasks(db, user_id=settings.DEFAULT_USER_ID, target_date=target_date, goal_id=goal_id)

@router.post("", response_model=TaskOut)
def create_task(task_in: TaskCreate, db: Session = Depends(get_db)):
    return goal_service.create_task(db, user_id=settings.DEFAULT_USER_ID, task_in=task_in)

@router.put("/{task_id}", response_model=TaskOut)
def update_task(task_id: int, task_in: TaskUpdate, db: Session = Depends(get_db)):
    task = goal_service.update_task(db, task_id=task_id, user_id=settings.DEFAULT_USER_ID, task_in=task_in)
    if not task:
        raise HTTPException(status_code=404, detail="任务不存在")
    return task

@router.delete("/{task_id}")
def delete_task(task_id: int, db: Session = Depends(get_db)):
    success = goal_service.delete_task(db, task_id=task_id, user_id=settings.DEFAULT_USER_ID)
    if not success:
        raise HTTPException(status_code=404, detail="任务不存在")
    return {"message": "任务删除成功"}
