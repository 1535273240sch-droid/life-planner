from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from typing import List, Optional
import datetime
from database import get_db
from schemas import AIAskRequest, AIBreakdownRequest, AIMessageOut, AIReviewRequest
from models import AiMessage
import ai_agent
from ai_agent import AIError
from config import settings

router = APIRouter(prefix="/api/ai", tags=["AI 智能体引擎"])


def _http_error(e: AIError) -> HTTPException:
    """AIError -> {"detail": {"code", "message"}}；message 为固定文案，不含 Key/请求体/用户数据。"""
    return HTTPException(status_code=e.status_code, detail={"code": e.code, "message": e.message})


@router.post("/daily-plan")
def trigger_daily_plan(auto_apply: bool = Query(True, description="为真时把生成的任务写入今日待办"), db: Session = Depends(get_db)):
    """生成今日规划；auto_apply=true 时将任务推入今日待办"""
    try:
        return ai_agent.generate_daily_plan(db, user_id=settings.DEFAULT_USER_ID, auto_apply=auto_apply)
    except AIError as e:
        db.rollback()
        raise _http_error(e)

@router.post("/ask")
def ask_advisor_endpoint(req: AIAskRequest, db: Session = Depends(get_db)):
    """决策顾问问答，严格输出6段式利弊分析"""
    if not req.question or not req.question.strip():
        raise HTTPException(status_code=400, detail="提问内容不能为空")
    try:
        return ai_agent.ask_advisor(db, user_id=settings.DEFAULT_USER_ID, question=req.question.strip())
    except AIError as e:
        db.rollback()
        raise _http_error(e)

@router.post("/breakdown")
def breakdown_goal_endpoint(req: AIBreakdownRequest, db: Session = Depends(get_db)):
    """AI 智能拆解宏观目标"""
    title = (req.goal_title or "").strip()
    if not title:
        raise HTTPException(status_code=400, detail="请输入拆解目标主题")
    try:
        return ai_agent.breakdown_goal(db, user_id=settings.DEFAULT_USER_ID, goal_title=title, goal_desc=req.goal_description)
    except AIError as e:
        db.rollback()
        raise _http_error(e)

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
    """D3. 触发每日/每周复盘（周复盘附带 progress_suggestions，仅供展示，不写库）"""
    req_data = req or AIReviewRequest()
    r_type = req_data.type or "daily"
    if r_type not in ("daily", "weekly"):
        raise HTTPException(status_code=400, detail="复盘类型只能是 daily 或 weekly")
    target_date = req_data.date
    if target_date:
        # 显式拒绝非法日期，避免"指定某天"被静默换成今天
        try:
            datetime.date.fromisoformat(target_date)
        except ValueError:
            raise HTTPException(status_code=400, detail="日期格式不正确，请使用 YYYY-MM-DD")
    try:
        return ai_agent.generate_review(db, user_id=settings.DEFAULT_USER_ID, review_type=r_type, target_date_str=target_date)
    except AIError as e:
        db.rollback()
        raise _http_error(e)

@router.get("/gaps")
def get_detected_gaps(db: Session = Depends(get_db)):
    """D4. 获取生活与目标缺口检测预警"""
    from services import stats_service
    return stats_service.detect_life_gaps(db, user_id=settings.DEFAULT_USER_ID)

