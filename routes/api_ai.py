from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from typing import List, Optional
from database import get_db
from schemas import AIAskRequest, AIBreakdownRequest, AIMessageOut, AIReviewRequest
from models import AiMessage
import ai_agent
from config import settings

router = APIRouter(prefix="/api/ai", tags=["AI 智能体引擎"])

@router.post("/daily-plan")
def trigger_daily_plan(db: Session = Depends(get_db)):
    """生成今日规划并自动将 Top 任务推入今日待办"""
    plan = ai_agent.generate_daily_plan(db, user_id=settings.DEFAULT_USER_ID)
    return plan

@router.post("/ask")
def ask_advisor_endpoint(req: AIAskRequest, db: Session = Depends(get_db)):
    """决策顾问问答，严格输出6段式利弊分析"""
    if not req.question or not req.question.strip():
        raise HTTPException(status_code=400, detail="提问内容不能为空")
    result = ai_agent.ask_advisor(db, user_id=settings.DEFAULT_USER_ID, question=req.question.strip())
    return result

@router.post("/breakdown")
def breakdown_goal_endpoint(req: AIBreakdownRequest, db: Session = Depends(get_db)):
    """AI 智能拆解宏观目标"""
    title = req.goal_title or "未命名目标"
    return ai_agent.breakdown_goal(db, user_id=settings.DEFAULT_USER_ID, goal_title=title, goal_desc=req.goal_description)

@router.get("/history", response_model=List[AIMessageOut])
def get_ai_history(
    mode: Optional[str] = Query(None, description="过滤模式: daily_plan / advisor / review"),
    limit: int = Query(20, ge=1, le=100),
    db: Session = Depends(get_db)
):
    query = db.query(AiMessage).filter(AiMessage.user_id == settings.DEFAULT_USER_ID)
    if mode:
        query = query.filter(AiMessage.mode == mode)
    return query.order_by(AiMessage.id.desc()).limit(limit).all()

@router.post("/review")
def trigger_ai_review(req: Optional[AIReviewRequest] = None, db: Session = Depends(get_db)):
    """D3. 触发每日/每周复盘"""
    req_data = req or AIReviewRequest()
    r_type = req_data.type or "daily"
    target_date = req_data.date
    return ai_agent.generate_review(db, user_id=settings.DEFAULT_USER_ID, review_type=r_type, target_date_str=target_date)

@router.get("/gaps")
def get_detected_gaps(db: Session = Depends(get_db)):
    """D4. 获取生活与目标缺口检测预警"""
    from services import stats_service
    return stats_service.detect_life_gaps(db, user_id=settings.DEFAULT_USER_ID)

