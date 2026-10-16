import datetime
import re
from typing import List, Optional, Dict, Any
from sqlalchemy.orm import Session
from models import Goal, Task
from schemas import GoalCreate, GoalUpdate, TaskCreate, TaskUpdate, ApplyBreakdownRequest
from services.profile_service import get_or_create_user

def get_goals(db: Session, user_id: int = 1, status: Optional[str] = None, level: Optional[str] = None) -> List[Goal]:
    query = db.query(Goal).filter(Goal.user_id == user_id, Goal.deleted_at.is_(None))
    if status:
        query = query.filter(Goal.status == status)
    if level:
        query = query.filter(Goal.level == level)
    return query.order_by(Goal.id.asc()).all()

def get_goal_tree(db: Session, user_id: int = 1) -> List[dict]:
    """获取树形组织的目标结构：顶级目标包含 children 属性（不含已软删除目标）"""
    all_goals = db.query(Goal).filter(Goal.user_id == user_id, Goal.deleted_at.is_(None)).all()
    goals_dict = {}
    for g in all_goals:
        goals_dict[g.id] = {
            "id": g.id,
            "parent_id": g.parent_id,
            "level": g.level,
            "title": g.title,
            "category": g.category,
            "description": g.description,
            "metric": g.metric,
            "start_date": g.start_date.isoformat() if g.start_date else None,
            "due_date": g.due_date.isoformat() if g.due_date else None,
            "progress": g.progress,
            "status": g.status,
            "children": []
        }

    roots = []
    for g in all_goals:
        node = goals_dict[g.id]
        if g.parent_id and g.parent_id in goals_dict:
            goals_dict[g.parent_id]["children"].append(node)
        else:
            roots.append(node)

    # 兜底：从根向下标记可达节点。若历史数据里父级链闭合（成环），这些节点挂不到任何根上，
    # 就会从目标树里消失且界面无从管理。这里在环内 id 最小的节点处断开并提升为根。
    reachable = set()
    stack = [r["id"] for r in roots]
    while stack:
        cur = stack.pop()
        if cur in reachable:
            continue
        reachable.add(cur)
        stack.extend(ch["id"] for ch in goals_dict[cur]["children"])

    for gid in sorted(g.id for g in all_goals if g.id not in reachable):
        if gid in reachable:
            continue
        node = goals_dict[gid]
        parent_id = node["parent_id"]
        if parent_id in goals_dict:
            goals_dict[parent_id]["children"] = [
                c for c in goals_dict[parent_id]["children"] if c["id"] != gid
            ]
        node["parent_id"] = None
        roots.append(node)
        # 从这个新的根出发重新标记，把同一环上的其他节点一并带出来
        stack = [gid]
        while stack:
            cur = stack.pop()
            if cur in reachable:
                continue
            reachable.add(cur)
            stack.extend(ch["id"] for ch in goals_dict[cur]["children"])

    return roots

def get_goal_by_id(db: Session, goal_id: int, user_id: int = 1) -> Optional[Goal]:
    return db.query(Goal).filter(
        Goal.id == goal_id, Goal.user_id == user_id, Goal.deleted_at.is_(None)
    ).first()


def validate_parent(db: Session, user_id: int, parent_id: Optional[int], goal_id: Optional[int] = None) -> Optional[str]:
    """校验父级目标可用。返回中文错误说明；None 表示通过。

    规则：父级必须存在、未删除、属于当前用户，且不能形成环（不能是自己，也不能是自己的下级）。
    成环会让相关目标在目标树里彻底消失，所以在这里挡住，而不是留给前端。
    """
    if parent_id is None:
        return None
    if goal_id is not None and parent_id == goal_id:
        return "父级目标不能是它自己"
    parent = db.query(Goal).filter(
        Goal.id == parent_id, Goal.user_id == user_id, Goal.deleted_at.is_(None)
    ).first()
    if parent is None:
        return "父级目标不存在或已被删除"
    if goal_id is None:
        return None
    seen = set()
    cur = parent
    while cur is not None and cur.id not in seen:
        if cur.id == goal_id:
            return "不能把自己的下级目标设为父级（会形成循环）"
        seen.add(cur.id)
        cur = db.query(Goal).filter(
            Goal.id == cur.parent_id, Goal.user_id == user_id
        ).first() if cur.parent_id else None
    return None

def get_goal_including_deleted(db: Session, goal_id: int, user_id: int = 1) -> Optional[Goal]:
    """含已软删除目标的查询，供复盘等历史场景取目标名称"""
    return db.query(Goal).filter(Goal.id == goal_id, Goal.user_id == user_id).first()

def create_goal(db: Session, user_id: int, goal_in: GoalCreate) -> Goal:
    get_or_create_user(db, user_id)
    err = validate_parent(db, user_id, goal_in.parent_id)
    if err:
        raise ValueError(err)
    goal = Goal(
        user_id=user_id,
        parent_id=goal_in.parent_id,
        level=goal_in.level or "long",
        title=goal_in.title,
        category=goal_in.category or "职业",
        description=goal_in.description,
        metric=goal_in.metric,
        start_date=goal_in.start_date,
        due_date=goal_in.due_date,
        progress=goal_in.progress or 0,
        status=goal_in.status or "in_progress"
    )
    db.add(goal)
    db.commit()
    db.refresh(goal)
    return goal

def create_goals_batch(db: Session, user_id: int, goals_in: List[GoalCreate]) -> List[Goal]:
    get_or_create_user(db, user_id)
    for g_in in goals_in:
        err = validate_parent(db, user_id, g_in.parent_id)
        if err:
            raise ValueError(err)
    created_goals = []
    for g_in in goals_in:
        goal = Goal(
            user_id=user_id,
            parent_id=g_in.parent_id,
            level=g_in.level or "stage",
            title=g_in.title,
            category=g_in.category or "职业",
            description=g_in.description,
            metric=g_in.metric,
            start_date=g_in.start_date,
            due_date=g_in.due_date,
            progress=g_in.progress or 0,
            status=g_in.status or "in_progress"
        )
        db.add(goal)
        created_goals.append(goal)
    db.commit()
    for g in created_goals:
        db.refresh(g)
    return created_goals

def update_goal(db: Session, goal_id: int, user_id: int, goal_in: GoalUpdate) -> Optional[Goal]:
    goal = get_goal_by_id(db, goal_id, user_id)
    if not goal:
        return None
    data = goal_in.model_dump(exclude_unset=True)
    if "parent_id" in data:
        err = validate_parent(db, user_id, data["parent_id"], goal_id=goal_id)
        if err:
            raise ValueError(err)
    for field, value in data.items():
        setattr(goal, field, value)
    db.commit()
    db.refresh(goal)
    return goal

def delete_goal(db: Session, goal_id: int, user_id: int) -> bool:
    """软删除目标及其整棵子树：只写 deleted_at，不删行、不动 tasks"""
    goal = get_goal_by_id(db, goal_id, user_id)
    if not goal:
        return False
    now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
    visited = set()
    frontier = [goal.id]
    while frontier:
        visited.update(frontier)
        children = db.query(Goal).filter(
            Goal.user_id == user_id,
            Goal.parent_id.in_(frontier),
        ).all()
        frontier = [c.id for c in children if c.id not in visited]
    db.query(Goal).filter(
        Goal.user_id == user_id, Goal.id.in_(visited), Goal.deleted_at.is_(None)
    ).update({Goal.deleted_at: now}, synchronize_session=False)
    db.commit()
    return True

def apply_progress(db: Session, user_id: int, items: List[Any]) -> Dict[str, Any]:
    """用户确认后写入目标进度。AI 路径（ai_agent）不得调用本函数。
    注意：目标的新建/编辑表单仍可直接设置 progress，本函数只是"AI 建议"的唯一落点。
    状态联动：progress >= 100 → completed；completed 且新值 < 100 → 回到 in_progress；
    paused 仅在 progress < 100 时保持暂停（被确认到 100% 时按完成处理）。
    同一 goal_id 重复出现以最后一项为准；整个请求在一个事务里提交，异常整体回滚。"""
    latest: Dict[int, int] = {}
    for it in items:
        latest[it.goal_id] = it.progress  # 重复 id 以最后一项为准（dict 保持首次出现的顺序）

    updated: List[Dict[str, Any]] = []
    skipped: List[Dict[str, Any]] = []
    try:
        for goal_id, progress in latest.items():
            goal = db.query(Goal).filter(Goal.id == goal_id, Goal.user_id == user_id).first()
            if goal is None:
                skipped.append({"goal_id": goal_id, "reason": "目标不存在"})
                continue
            if goal.deleted_at is not None:
                skipped.append({"goal_id": goal_id, "reason": "目标已删除"})
                continue
            goal.progress = progress
            if progress >= 100:
                goal.status = "completed"
            elif goal.status == "completed":
                goal.status = "in_progress"
            # paused 与其余状态保持不变
            updated.append({"goal_id": goal.id, "progress": goal.progress, "status": goal.status})
        db.commit()
    except Exception:
        db.rollback()
        raise
    return {"updated": updated, "skipped": skipped}

# --- 任务服务 ---
def get_tasks(db: Session, user_id: int = 1, target_date: Optional[datetime.date] = None, goal_id: Optional[int] = None) -> List[Task]:
    query = db.query(Task).filter(Task.user_id == user_id)
    if target_date:
        query = query.filter(Task.date == target_date)
    if goal_id:
        query = query.filter(Task.goal_id == goal_id)
    return query.order_by(Task.done.asc(), Task.id.asc()).all()

def create_task(db: Session, user_id: int, task_in: TaskCreate) -> Task:
    get_or_create_user(db, user_id)
    task = Task(
        user_id=user_id,
        goal_id=task_in.goal_id,
        date=task_in.date or datetime.date.today(),
        title=task_in.title,
        est_minutes=task_in.est_minutes or 30,
        done=task_in.done or False
    )
    if task.done:
        task.done_at = datetime.datetime.now(datetime.timezone.utc)
    db.add(task)
    db.commit()
    db.refresh(task)
    return task

def update_task(db: Session, task_id: int, user_id: int, task_in: TaskUpdate) -> Optional[Task]:
    task = db.query(Task).filter(Task.id == task_id, Task.user_id == user_id).first()
    if not task:
        return None
    data = task_in.model_dump(exclude_unset=True)
    if "done" in data:
        if data["done"] and not task.done:
            task.done_at = datetime.datetime.now(datetime.timezone.utc)
        elif not data["done"]:
            task.done_at = None
    for field, value in data.items():
        setattr(task, field, value)
    db.commit()
    db.refresh(task)
    return task

def delete_task(db: Session, task_id: int, user_id: int) -> bool:
    task = db.query(Task).filter(Task.id == task_id, Task.user_id == user_id).first()
    if not task:
        return False
    db.delete(task)
    db.commit()
    return True


# --- AI 目标智能拆解落库服务 ---
def parse_breakdown_markdown(text: str) -> List[Dict[str, Any]]:
    """从 Markdown 蓝图中解析出阶段目标与周任务清单"""
    if not text:
        return []

    stages = []
    # 匹配各类阶段标题：#### 阶段一：xxx / ### 阶段1：xxx / **阶段一：xxx** / 1. 阶段一：xxx
    stage_split_pattern = r'(?m)^(?:#{2,4}\s*|\*{2}\s*|\d+[\.、]\s*)(阶段[一二三四五六七八九十\d]+[^\n\r]*)'
    splits = re.split(stage_split_pattern, text)

    if len(splits) > 1:
        for i in range(1, len(splits), 2):
            raw_title = splits[i].strip(' *#:')
            body = splits[i+1].strip() if i+1 < len(splits) else ''

            # 提取检验标准 Metric
            metric = None
            m_metric = re.search(r'(?:\*\*检验标准[^\*]*\*\*|检验标准|Metric|衡量标准)[*：:\s]*([^\n\r]+)', body, re.IGNORECASE)
            if m_metric:
                metric = m_metric.group(1).strip(' *`')

            # 提取阶段里程碑 / 描述
            desc = None
            m_desc = re.search(r'(?:\*\*阶段里程碑[^\*]*\*\*|阶段里程碑|核心抓手|阶段目标)[*：:\s]*([^\n\r]+)', body)
            if m_desc:
                desc = m_desc.group(1).strip(' *`')
            elif body:
                # 寻找第一段不是周任务的非空行
                desc_lines = [l.strip(' *-#') for l in body.splitlines() if l.strip() and not re.search(r'第\s*[\d\-一二三四五六七八九十]+\s*周', l)]
                if desc_lines:
                    desc = desc_lines[0]

            # 提取周行动清单
            weekly_goals = []
            for line in body.splitlines():
                line = line.strip()
                w_match = re.search(r'[-*•\d\.\s\[\]]*(第\s*[\d\-一二三四五六七八九十]+\s*周[^\n\r]*)', line)
                if w_match:
                    w_title = w_match.group(1).strip(' *`')
                    weekly_goals.append({
                        'title': w_title,
                        'description': w_title,
                        'level': 'weekly'
                    })

            stages.append({
                'title': raw_title,
                'description': desc or raw_title,
                'metric': metric,
                'weekly_goals': weekly_goals,
                'level': 'stage'
            })
    return stages


def apply_ai_breakdown(db: Session, user_id: int, req: ApplyBreakdownRequest) -> Dict[str, Any]:
    """
    接收 AI 拆解出的阶段目标列表与周任务清单，
    自动批量将阶段目标 (level='stage') 与周目标 (level='weekly') 落库到 goals 表，自动绑定 parent_id
    """
    get_or_create_user(db, user_id)

    # 1. 确定宏观/父级目标 (stage_parent_id)
    created_root = False
    stage_parent_id = None

    if req.parent_id:
        parent_goal = db.query(Goal).filter(
            Goal.id == req.parent_id, Goal.user_id == user_id, Goal.deleted_at.is_(None)
        ).first()
        if parent_goal:
            stage_parent_id = parent_goal.id

    if stage_parent_id is None and req.goal_title and req.goal_title.strip():
        # 如果未传入已有父目标，则创建该宏观目标作为顶层节点 (level='long')
        root_goal = Goal(
            user_id=user_id,
            parent_id=None,
            level="long",
            title=req.goal_title.strip(),
            category=req.category or "职业",
            description=req.plan or "由 AI 智能拆解生成的主目标",
            status="in_progress",
            progress=0,
            start_date=datetime.date.today()
        )
        db.add(root_goal)
        db.flush()
        stage_parent_id = root_goal.id
        created_root = True

    # 2. 准备阶段目标列表
    stages_to_process = []
    if req.stages:
        stages_to_process = req.stages
    elif req.plan:
        stages_to_process = parse_breakdown_markdown(req.plan)

    created_stages = 0
    created_weekly = 0

    # 3. 逐级落库并自动绑定 parent_id
    for stage in stages_to_process:
        if isinstance(stage, dict):
            s_title = stage.get("title")
            s_desc = stage.get("description")
            s_metric = stage.get("metric")
            s_cat = stage.get("category") or req.category or "职业"
            s_start = stage.get("start_date")
            s_due = stage.get("due_date")
            w_list = stage.get("weekly_goals") or stage.get("weekly_tasks") or []
        else:
            s_title = stage.title
            s_desc = stage.description
            s_metric = stage.metric
            s_cat = stage.category or req.category or "职业"
            s_start = stage.start_date
            s_due = stage.due_date
            w_list = stage.weekly_goals or stage.weekly_tasks or []

        # 落库阶段目标 (level='stage')，自动绑定 stage_parent_id
        stage_goal = Goal(
            user_id=user_id,
            parent_id=stage_parent_id,
            level="stage",
            title=s_title or "阶段目标",
            category=s_cat,
            description=s_desc,
            metric=s_metric,
            start_date=s_start or datetime.date.today(),
            due_date=s_due,
            status="in_progress",
            progress=0
        )
        db.add(stage_goal)
        db.flush()  # 获得 stage_goal.id
        created_stages += 1

        # 落库周目标 (level='weekly')，自动绑定 parent_id 为该阶段目标
        for w_item in w_list:
            if isinstance(w_item, dict):
                w_title = w_item.get("title")
                w_desc = w_item.get("description")
                w_metric = w_item.get("metric")
                w_cat = w_item.get("category") or s_cat
                w_start = w_item.get("start_date")
                w_due = w_item.get("due_date")
            else:
                w_title = w_item.title
                w_desc = w_item.description
                w_metric = w_item.metric
                w_cat = w_item.category or s_cat
                w_start = w_item.start_date
                w_due = w_item.due_date

            weekly_goal = Goal(
                user_id=user_id,
                parent_id=stage_goal.id,  # 自动绑定当前阶段目标为 parent_id
                level="weekly",
                title=w_title or "周目标",
                category=w_cat,
                description=w_desc,
                metric=w_metric,
                start_date=w_start or stage_goal.start_date,
                due_date=w_due,
                status="in_progress",
                progress=0
            )
            db.add(weekly_goal)
            created_weekly += 1

    db.commit()

    msg_parts = []
    if created_root:
        msg_parts.append("已创建宏观长远目标")
    msg_parts.append(f"成功生成 {created_stages} 个阶段里程碑与 {created_weekly} 个周攻坚目标并完成层级绑定")

    return {
        "success": True,
        "message": "，".join(msg_parts) + "！",
        "root_goal_id": stage_parent_id,
        "created_stages": created_stages,
        "created_weekly": created_weekly
    }

