from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from typing import List, Optional, Union
from database import get_db
from schemas import GoalOut, GoalCreate, GoalUpdate, GoalBatchCreate, ApplyBreakdownRequest, ApplyBreakdownResponse, AIBreakdownRequest, GoalProgressApplyRequest, GoalProgressApplyResponse
from services import goal_service
from config import settings

router = APIRouter(prefix="/api/goals", tags=["目标系统"])

@router.get("", response_model=List[GoalOut])
def list_goals(
    status: Optional[str] = Query(None, description="状态过滤: in_progress/completed/paused"),
    level: Optional[str] = Query(None, description="层级过滤: vision/long/stage/weekly"),
    db: Session = Depends(get_db)
):
    return goal_service.get_goals(db, user_id=settings.DEFAULT_USER_ID, status=status, level=level)

@router.get("/tree")
def get_goal_tree(db: Session = Depends(get_db)):
    return goal_service.get_goal_tree(db, user_id=settings.DEFAULT_USER_ID)

@router.post("", response_model=GoalOut)
def create_goal(goal_in: GoalCreate, db: Session = Depends(get_db)):
    try:
        return goal_service.create_goal(db, user_id=settings.DEFAULT_USER_ID, goal_in=goal_in)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

@router.post("/batch", response_model=List[GoalOut])
def create_goals_batch(batch_in: Union[List[GoalCreate], GoalBatchCreate], db: Session = Depends(get_db)):
    """批量采纳/创建目标清单"""
    if isinstance(batch_in, GoalBatchCreate):
        parent_id = batch_in.parent_id
        items = batch_in.goals
        if parent_id is not None:
            for item in items:
                if item.parent_id is None:
                    item.parent_id = parent_id
    else:
        items = batch_in
    try:
        return goal_service.create_goals_batch(db, user_id=settings.DEFAULT_USER_ID, goals_in=items)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

@router.post("/progress/apply", response_model=GoalProgressApplyResponse)
def apply_goal_progress(req: GoalProgressApplyRequest, db: Session = Depends(get_db)):
    """用户确认后写入目标进度。必须声明在 /{goal_id} 系列路由之前。"""
    return goal_service.apply_progress(db, user_id=settings.DEFAULT_USER_ID, items=req.items)

@router.put("/{goal_id}", response_model=GoalOut)
def update_goal(goal_id: int, goal_in: GoalUpdate, db: Session = Depends(get_db)):
    try:
        goal = goal_service.update_goal(db, goal_id=goal_id, user_id=settings.DEFAULT_USER_ID, goal_in=goal_in)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    if not goal:
        raise HTTPException(status_code=404, detail="目标不存在")
    return goal

@router.delete("/{goal_id}")
def delete_goal(goal_id: int, db: Session = Depends(get_db)):
    success = goal_service.delete_goal(db, goal_id=goal_id, user_id=settings.DEFAULT_USER_ID)
    if not success:
        raise HTTPException(status_code=404, detail="目标不存在")
    return {"message": "目标删除成功"}

@router.post("/ai-breakdown")
def ai_breakdown_goal(req: AIBreakdownRequest, db: Session = Depends(get_db)):
    import ai_agent
    title = (req.goal_title or "").strip()
    if not title:
        raise HTTPException(status_code=400, detail="请输入拆解目标主题")
    # AIError 由 Worker A 在 ai_agent 中定义；按名称延迟获取，A 完成前后均可导入
    ai_error_cls = getattr(ai_agent, "AIError", None)
    try:
        return ai_agent.breakdown_goal(db, user_id=settings.DEFAULT_USER_ID, goal_title=title, goal_desc=req.goal_description)
    except Exception as e:
        if ai_error_cls is not None and isinstance(e, ai_error_cls):
            raise HTTPException(status_code=e.status_code, detail={"code": e.code, "message": e.message})
        raise

@router.post("/ai-breakdown/apply", response_model=ApplyBreakdownResponse)
def apply_ai_breakdown(req: ApplyBreakdownRequest, db: Session = Depends(get_db)):
    """
    接收 AI 拆解出的阶段目标列表与周任务清单，
    自动批量将阶段目标 (level='stage') 与周目标 (level='weekly') 落库到 goals 表，自动绑定 parent_id
    """
    return goal_service.apply_ai_breakdown(db, user_id=settings.DEFAULT_USER_ID, req=req)


