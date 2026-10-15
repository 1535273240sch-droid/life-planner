import datetime
from typing import List, Dict, Any
from sqlalchemy.orm import Session
from models import DailyLog, Goal, Task, Profile
from services import profile_service, goal_service, log_service

def parse_sleep_hours(start_str: str, end_str: str) -> float:
    """计算睡眠时长（小时），跨天按夜间处理"""
    if not start_str or not end_str or ":" not in start_str or ":" not in end_str:
        return 7.5
    try:
        sh, sm = map(int, start_str.strip().split(":")[:2])
        eh, em = map(int, end_str.strip().split(":")[:2])
        start_min = sh * 60 + sm
        end_min = eh * 60 + em
        if end_min < start_min: # 跨天
            total_min = (1440 - start_min) + end_min
        else:
            total_min = end_min - start_min
        return round(total_min / 60.0, 1)
    except Exception:
        return 7.5

def detect_life_gaps(db: Session, user_id: int = 1) -> List[Dict[str, Any]]:
    """
    D4. 缺口提醒检测算法
    扫描近 7-14 天的数据，精准发现生活与目标投入中的潜在缺口
    """
    gaps = []
    recent_logs = log_service.get_recent_logs(db, user_id=user_id, days=14)
    ongoing_goals = goal_service.get_goals(db, user_id=user_id, status="in_progress")
    profile = profile_service.get_or_create_profile(db, user_id)

    if not recent_logs:
        gaps.append({
            "category": "系统记录",
            "level": "注意",
            "title": "连续记录处于初期阶段",
            "detail": "当前系统内记录天数不足3天，建议每天抽出2分钟在「今日记录」填写关键事项，AI将为您激活更深度的多维缺口探测！"
        })
        return gaps

    # 1. 睡眠缺口检测 (连续睡眠不足 或 平均睡眠短缺)
    sleep_hours_list = []
    short_sleep_days = 0
    for l in recent_logs[:7]:
        hrs = parse_sleep_hours(l.sleep_start, l.sleep_end)
        sleep_hours_list.append(hrs)
        if hrs < 6.5:
            short_sleep_days += 1

    if short_sleep_days >= 3 or (sleep_hours_list and sum(sleep_hours_list)/len(sleep_hours_list) < 6.8):
        avg_s = round(sum(sleep_hours_list)/len(sleep_hours_list), 1) if sleep_hours_list else 6.0
        gaps.append({
            "category": "睡眠与体能",
            "level": "预警",
            "title": f"发现持续性睡眠负债 (近7天有{short_sleep_days}天睡眠不足)",
            "detail": f"你近7天的平均睡眠仅为 {avg_s} 小时。连续欠觉会导致前额叶皮层认知功能下降15-20%，容易产生代偿性吃糖与注意力涣散。建议今明两晚提早45分钟放下手机就寝。"
        })

    # 2. 营养与饮食缺口检测 (蔬菜/高蛋白)
    veg_keywords = ["菜", "沙拉", "西兰花", "番茄", "黄瓜", "蔬菜", "青菜", "菠菜"]
    protein_keywords = ["蛋", "鸡", "鱼", "牛", "肉", "奶", "豆", "虾"]
    has_veg_count = 0
    has_protein_count = 0

    for l in recent_logs[:7]:
        meals_text = " ".join([str(v) for v in (l.meals or {}).values()])
        if any(w in meals_text for w in veg_keywords):
            has_veg_count += 1
        if any(w in meals_text for w in protein_keywords):
            has_protein_count += 1

    if len(recent_logs) >= 3 and has_veg_count <= 2:
        gaps.append({
            "category": "饮食与营养",
            "level": "提醒",
            "title": "膳食纤维与绿叶蔬菜摄入偏低",
            "detail": f"近7天记录中仅有 {has_veg_count} 天提到摄入蔬菜类食物。长期缺乏绿叶蔬菜与膳食纤维会导致肠道菌群失衡与午后大脑供能迟缓，建议今日午餐或晚餐主动搭配一份水煮/清炒深色蔬菜。"
        })

    if len(recent_logs) >= 3 and has_protein_count <= 2:
        gaps.append({
            "category": "饮食与营养",
            "level": "注意",
            "title": "优质蛋白质摄入频次不足",
            "detail": "近几天饮食多为碳水主食，优质蛋白质摄入频次明显不足。足量蛋白质是维持肌肉量、基础代谢及日间专注度的重要基石，建议早餐增加鸡蛋牛奶，正餐保证手掌大小的瘦肉/豆制品。"
        })

    # 3. 目标投入断流检测 (目标冷落)
    for g in ongoing_goals:
        # 检查近7天有没有做过关于该目标的 tasks 或在 done_items 里提及
        goal_done_count = 0
        for l in recent_logs[:7]:
            if g.title in (l.done_items or "") or g.title in (l.study_note or ""):
                goal_done_count += 1

        recent_tasks = db.query(Task).filter(
            Task.goal_id == g.id,
            Task.date >= datetime.date.today() - datetime.timedelta(days=7),
            Task.done == True
        ).count()

        if goal_done_count == 0 and recent_tasks == 0:
            gaps.append({
                "category": "目标停滞",
                "level": "预警",
                "title": f"目标「{g.title}」已连续数日零投入",
                "detail": f"进行中目标【{g.title}】在近7天没有任何相关完成事项或任务打卡（当前进度停留在 {g.progress}%）。建议将该目标拆出今天1个只需耗时20分钟的最小行动项，先启动动能！"
            })

    # 4. 学习时长检测
    study_mins = [l.study_minutes for l in recent_logs[:7]]
    total_study = sum(study_mins)
    if len(recent_logs) >= 3 and total_study < 120:
        gaps.append({
            "category": "成长与学习",
            "level": "注意",
            "title": "近7天累计学习时长低于预期基线",
            "detail": f"最近一周累计深度学习投入仅 {total_study} 分钟（日均不足20分钟）。建议结合你的价值观约束，在今晚晚饭后划定一段45分钟的不受打扰无手机专注时间槽。"
        })

    return gaps
