import re
import datetime
from typing import List, Dict, Any, Optional
from sqlalchemy.orm import Session
from models import DailyLog, Goal, Task, Profile
from services import profile_service, goal_service, log_service

def parse_sleep_hours(start_str: Optional[str], end_str: Optional[str]) -> Optional[float]:
    """计算睡眠时长（小时），跨天按夜间处理。若未记录或格式不符返回 None"""
    if not start_str or not end_str or ":" not in str(start_str) or ":" not in str(end_str):
        return None
    try:
        sh, sm = map(int, str(start_str).strip().split(":")[:2])
        eh, em = map(int, str(end_str).strip().split(":")[:2])
        start_min = sh * 60 + sm
        end_min = eh * 60 + em
        if end_min < start_min: # 跨天
            total_min = (1440 - start_min) + end_min
        else:
            total_min = end_min - start_min
        return round(total_min / 60.0, 1)
    except Exception:
        return None

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

    # 1. 睡眠缺口检测 (连续未记录睡眠 或 真实睡眠不足)
    valid_sleep_hours = []
    short_sleep_days = 0
    missing_sleep_days = 0

    for l in recent_logs[:7]:
        hrs = parse_sleep_hours(l.sleep_start, l.sleep_end)
        if hrs is None:
            missing_sleep_days += 1
        else:
            valid_sleep_hours.append(hrs)
            if hrs < 6.5:
                short_sleep_days += 1

    # 1a. 连续多天未记录睡眠缺口预警 (P0-3 修复)
    if len(recent_logs[:7]) >= 3 and missing_sleep_days >= 3:
        gaps.append({
            "category": "睡眠与体能",
            "level": "提醒",
            "title": f"连续多天未记录作息睡眠 (近7天有{missing_sleep_days}天缺省)",
            "detail": "作息规律是高维认知与体能恢复的基石。当前缺失真实入睡与起床时间，系统无法准确评估你的疲劳程度与能量消耗。建议睡前或晨起顺手记录入睡和醒来时间。"
        })

    # 1b. 真实睡眠不足 (基于有记录的天数)
    if valid_sleep_hours:
        avg_s = round(sum(valid_sleep_hours) / len(valid_sleep_hours), 1)
        if short_sleep_days >= 3 or avg_s < 6.8:
            gaps.append({
                "category": "睡眠与体能",
                "level": "预警",
                "title": f"发现持续性睡眠负债 (近7天有{short_sleep_days}天睡眠不足)",
                "detail": f"在你记录睡眠的天数中，平均睡眠仅为 {avg_s} 小时（{short_sleep_days}天低于6.5小时）。连续欠觉会导致前额叶皮层认知功能下降15-20%，容易产生代偿性吃糖与注意力涣散。建议今明两晚提早45分钟放下手机就寝。"
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

    # 3. 目标投入断流检测 (基于任务完成情况与核心关键词语义匹配，P0-4 修复)
    for g in ongoing_goals:
        # 3a. 提取目标核心关键词（去除攻坚、核心里程碑拆解、阶段、书名号等干扰）
        core_keywords = []
        raw_title = (g.title or "").strip()
        bracket_matches = re.findall(r"「(.*?)」|『(.*?)』|【(.*?)】", raw_title)
        for m in bracket_matches:
            for item in m:
                if item and len(item) >= 2:
                    core_keywords.append(item)
                    # 剥离数字分值、级别等后缀（例如「雅思7分」->「雅思」，「托福105」->「托福」）
                    stem = re.sub(r"(\d+分|\d+级|\d+|核心|突破|进阶|精通)$", "", item).strip()
                    if len(stem) >= 2 and stem not in core_keywords:
                        core_keywords.append(stem)

        cleaned_title = re.sub(r"^(攻坚|推进|执行|完成|拆解|阶段[一二三四五六七八九十\d]+[：:]|学习)", "", raw_title)
        cleaned_title = re.sub(r"(核心里程碑拆解与执行|里程碑|计划|行动|原著精读)$", "", cleaned_title).strip()
        cleaned_title = re.sub(r"[「」『』【】]", "", cleaned_title).strip()
        if len(cleaned_title) >= 2 and cleaned_title not in core_keywords:
            core_keywords.append(cleaned_title)
            stem_clean = re.sub(r"(\d+分|\d+级|\d+)$", "", cleaned_title).strip()
            if len(stem_clean) >= 2 and stem_clean not in core_keywords:
                core_keywords.append(stem_clean)
        if raw_title and raw_title not in core_keywords:
            core_keywords.append(raw_title)

        goal_done_count = 0
        for l in recent_logs[:7]:
            text_corpus = f"{l.done_items or ''} {l.study_note or ''} {l.reflection or ''}"
            if any(kw in text_corpus for kw in core_keywords if len(kw) >= 2):
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
