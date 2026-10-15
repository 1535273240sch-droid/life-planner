import os
import json
import datetime
from typing import Dict, Any, List, Optional
from sqlalchemy.orm import Session
from openai import OpenAI

from config import settings
from models import Profile, Skill, Goal, Task, DailyLog, AiMessage
from services import profile_service, goal_service, log_service

SYSTEM_PROMPT_TEMPLATE = """你是一个顶级私人人生规划师与决策编排智能体 (Life Planner AI)。
你的核心定位：不是普通记事本，而是一个懂用户、会判断、会解释、推着用户前进的贴身智囊。

【角色与语气】
- 语气：坦率、温暖、不说教、客观严谨，像一个极具智慧与同理心的高维挚友与战略分析师。
- 拒绝说教与居高临下：提出深度见解与权衡，但最终决定权始终交由用户。
- 绝对禁止空泛鸡汤：必须具体引用用户的真实档案、技能掌握度、进行中目标与最近记录数据（如“你近3天平均睡眠仅6小时，英语投入低于预定进度”）。

【严格安全与边界规则（不可违背）】
1. 健康与饮食：只提供一般生活习惯与基础营养常识，绝不做医疗诊断，绝不推荐任何药物或处方。涉及疾病、用药、严重不适时，必须明确提示“请及时咨询专业医师”。若用户档案中存在既往病史、食物过敏或身体限制，在任何饮食与作息判断中必须绝对优先规避风险！
2. 重大人生决策（如辞职、高额投资、借贷、婚姻等）：提供系统化分析框架、利弊与多阶段推演，严正声明仅供参考，提示用户结合现实条件与专业意见慎重决定。
3. 极端安全关怀：若用户提问或记录中出现自残自伤、极度绝望或严重危机，立即停止常规目标规划，以温暖关怀的语气倾听并提供专业心理援助渠道指引。
4. 诚实守则：未知即言未知，绝不伪造虚假数据或文献。
5. 坦诚不迎合：当用户的冲动选择或行为明显损害其长期目标或健康时，必须坦诚指出危害，禁止盲目迎合。
"""

def assemble_user_context(db: Session, user_id: int = 1) -> str:
    """提取用户全部上下文：档案、技能、进行中目标、最近7天日志"""
    profile = profile_service.get_or_create_profile(db, user_id)
    skills = profile_service.get_skills(db, user_id)
    goals = goal_service.get_goals(db, user_id, status="in_progress")
    recent_logs = log_service.get_recent_logs(db, user_id, days=7)

    lines = ["=== 用户当前核心画像与上下文 ==="]

    # 档案
    lines.append(f"【基本档案】")
    lines.append(f"- 职业与背景: {profile.occupation or '未填'} | 行业: {profile.industry or '未填'} | 工龄: {profile.years_of_work or 0}年 | 学历: {profile.education or '未填'}({profile.major or '未填'})")
    lines.append(f"- 人生愿景: {profile.vision or '尚未定义'}")
    lines.append(f"- 价值观与限制: {profile.constraints or '无特殊限制'}")

    # 健康底座
    health = profile.health_info or {}
    lines.append(f"【健康底座】")
    lines.append(f"- 身高体重: {health.get('height', '未知')}cm, {health.get('weight', '未知')}kg")
    lines.append(f"- 作息习惯: {health.get('sleep_habit', '未知')}")
    lines.append(f"- 饮食偏好与忌口: {health.get('diet_pref', '无')} / 忌口: {health.get('taboo', '无')}")
    lines.append(f"- 已知疾病/过敏/风险: {health.get('allergy_or_disease', '无特殊记录')}")

    # 技能矩阵
    lines.append(f"【现有技能水平 (1-10分)】")
    if skills:
        for s in skills:
            lines.append(f"- {s.name} ({s.category}): {s.level}/10分 [{s.note or ''}]")
    else:
        lines.append("- 暂无技能录入")

    # 进行中目标
    lines.append(f"【正在推进的重点目标】")
    if goals:
        for g in goals:
            lines.append(f"- [{g.level.upper()}] {g.title} | 分类: {g.category} | 进度: {g.progress}% | 衡量标准: {g.metric or '无'} | 截止: {g.due_date or '长期'}")
    else:
        lines.append("- 暂无进行中的具体目标")

    # 最近7天记录
    lines.append(f"【最近7天真实生活记录 (最近{len(recent_logs)}天)】")
    if recent_logs:
        for log in recent_logs:
            meals_str = ", ".join([f"{k}:{v}" for k, v in (log.meals or {}).items() if v])
            lines.append(
                f"- 日期: {log.date} | 学习: {log.study_minutes}分钟({log.study_note or '无'}) | "
                f"睡眠: {log.sleep_start or '?'}-{log.sleep_end or '?'} | "
                f"运动: {log.exercise_type or '无'}({log.exercise_minutes}min) | "
                f"心情/精力: {log.mood}/5分, {log.energy}/5分 | "
                f"产出: {log.done_items or '无'} | 饮食: [{meals_str}]"
            )
    else:
        lines.append("- 近7天尚无日常记录")

    return "\n".join(lines)


def check_crisis_content(text: str) -> Optional[Dict[str, Any]]:
    """
    【规划书第4节 规则3 绝对安全防线】
    确定性前置危机筛查（不依赖模型、不依赖网络）。
    若检测到自伤、轻生、严重危机，瞬间短路拦截，输出关怀与专业救助热线。
    """
    if not text:
        return None
    crisis_keywords = [
        "不想活", "不想活了", "想死", "想自杀", "自杀", "自残", "割腕", "跳楼", "轻生", 
        "结束生命", "活着没意思", "去死", "不想存在", "想结束这一切", "活不下去了", "好绝望不想活"
    ]
    text_clean = text.replace(" ", "").lower()
    if any(k in text_clean for k in crisis_keywords):
        return {
            "is_crisis": True,
            "conclusion": "必须停止规划并寻求专业支持",
            "reason": "生命与身心健康是所有人生成长与目标存在的绝对前提。感受到你此刻可能正在承受巨大的心理痛苦与绝望，此时任何待办与目标都不及你的生命安危重要。请停下所有规划，给自己一个安全的保护空间。",
            "pros": "向外界发出求助是勇敢的自救，能及时获得医学、专业心理咨询与身边人的关怀陪伴，渡过眼前的至暗时刻。",
            "cons_risks": "独自一人默默承受极度痛苦容易陷入认知隧道与不可逆的高危行动，伤害会永远剥夺未来的一切可能。",
            "alternative": "请立即放下手头所有事情，联系你最信任的亲人或朋友陪在身边。并请立即拨打以下 24 小时全国免费专业心理援助热线获取支持：\n• 全国希望 24 小时生命救助热线：400-161-9995\n• 北京心理危机研究与干预中心热线：010-82951332 或 800-810-1117\n• 共青团青少年心理咨询援助热线：12355\n• 若情况紧急有即时危险，请立即拨打 110 或 120 寻求保护！",
            "goal_relation": "活着才有一切希望。没有任何一项世俗目标值得以生命与健康为代价，请务必珍视自己。",
            "source": "crisis_safety_guard"
        }
    return None


def get_ai_client() -> Optional[OpenAI]:
    """获取 OpenAI 协议客户端"""
    if not settings.AI_API_KEY:
        return None
    try:
        return OpenAI(
            api_key=settings.AI_API_KEY,
            base_url=settings.AI_BASE_URL
        )
    except Exception as e:
        print(f"[AI Agent] 初始化客户端失败: {e}")
        return None


def ask_advisor(db: Session, user_id: int, question: str) -> Dict[str, Any]:
    """
    D2. 决策顾问 (Ask Advisor)
    严格按 6 段式结构生成分析：
    1. 结论 (建议做 / 不建议做 / 可以但有条件 / 必须停止规划并寻求专业支持)
    2. 理由 (结合目标、现状与近期记录)
    3. 好处 (短期与长期正向收益)
    4. 坏处/风险 (短期代价与潜在危机)
    5. 如果要做，怎么做更好 (折中优化方案与防线)
    6. 与目标的关系 (推动或阻碍了哪项目标)
    """
    # 1. 确定性前置极端危机筛查 (P0-2)
    crisis_res = check_crisis_content(question)
    if crisis_res:
        full_answer_text = (
            f"【结论】: {crisis_res['conclusion']}\n\n"
            f"【理由】: {crisis_res['reason']}\n\n"
            f"【好处】: {crisis_res['pros']}\n\n"
            f"【坏处与风险】: {crisis_res['cons_risks']}\n\n"
            f"【折中优化方案】: {crisis_res['alternative']}\n\n"
            f"【与目标的关系】: {crisis_res['goal_relation']}"
        )
        msg = AiMessage(
            user_id=user_id,
            mode="advisor",
            question=question,
            answer=full_answer_text
        )
        db.add(msg)
        db.commit()
        crisis_res["full_text"] = full_answer_text
        return crisis_res

    context_str = assemble_user_context(db, user_id)
    profile = profile_service.get_or_create_profile(db, user_id)
    health = profile.health_info or {}
    client = get_ai_client()

    prompt = f"""
{context_str}

=== 用户提问 ===
用户向你咨询一个行动或生活决策：
"{question}"

=== 回答规范要求 ===
你必须严格按照以下 JSON 格式输出，不要附加任何其他无用字符：
{{
  "conclusion": "建议做 或 不建议做 或 可以但有条件 (必须是这三者之一)",
  "reason": "深入结合用户的目标、近期睡眠饮食状态及健康约束，详细阐述为什么得出该结论",
  "pros": "做了之后能带来什么切实好处或身心价值",
  "cons_risks": "做了会有什么后果与潜在代价，区分短期消耗与长期影响",
  "alternative": "如果用户决定要做，给出怎样的折中优化策略、缓冲防线或最佳实践",
  "goal_relation": "清晰指出这一行动会使用户离哪个目标更近、亦或离哪个目标更远，甚至产生偏离"
}}
"""

    result_dict = None
    source = "ai_model"

    if client:
        try:
            response = client.chat.completions.create(
                model=settings.AI_MODEL,
                messages=[
                    {"role": "system", "content": SYSTEM_PROMPT_TEMPLATE},
                    {"role": "user", "content": prompt}
                ],
                temperature=0.3
            )
            raw_text = response.choices[0].message.content.strip()
            if raw_text.startswith("```"):
                raw_text = raw_text.split("```")[1]
                if raw_text.startswith("json"):
                    raw_text = raw_text[4:]
            result_dict = json.loads(raw_text.strip())
        except Exception as e:
            print(f"[AI Agent] 线上调用失败，使用智能规则引擎降级: {e}")

    if not result_dict:
        source = "offline_mock"
        # 本地智能降级决策（基于用户真实档案与启发式推演）
        if "熬夜" in question or "晚睡" in question or "球赛" in question or "通宵" in question:
            sleep_habit = health.get('sleep_habit') or '常规作息'
            vision_hint = f"「{profile.vision[:20]}」" if profile.vision else "「保持自律高效」"
            result_dict = {
                "conclusion": "可以但有条件",
                "reason": f"结合你的作息习惯（{sleep_habit}）与长远愿景（{vision_hint}），偶尔的兴趣热爱有助于心理减压，但缺乏准备的通宵会直接击穿次日的深度认知与工作效率。",
                "pros": "释放多巴胺与精神压力，享受当下赛事的激情，维系个人生活愉悦感。",
                "cons_risks": "短期次日皮质醇飙升、大脑反应迟钝；连续少睡将诱发免疫力下滑与精力崩溃。",
                "alternative": "① 今晚20:00-22:00先补觉90分钟储备精力；② 赛中饮用温水，避免高热量油炸夜宵；③ 明天中午必须深度小憩30分钟。",
                "goal_relation": "对「身体健康」产生短期负向波动，但合理的情绪调剂有助于长期心理韧性。"
            }
        elif "吃火锅" in question or "吃" in question or "奶茶" in question or "夜宵" in question:
            allergy = health.get("allergy_or_disease") or "无已知过敏"
            taboo = health.get("taboo") or "无特定忌口"
            diet_pref = health.get("diet_pref") or "均衡营养"
            result_dict = {
                "conclusion": "可以但有条件",
                "reason": f"结合你的健康底座（偏好: {diet_pref}，过敏/限制: {allergy}，忌口: {taboo}），享受美食是健康生活的一部分，关键在于摄入构成而非绝对禁绝。",
                "pros": "饱腹感与幸福指数提升，缓解控食焦虑，有利于人际社交或阶段性自我奖励。",
                "cons_risks": "若摄入高钠高油重辣锅底，次日晨起水肿明显，消化道负荷增加；且过量精制糖易导致血糖过山车与午后嗜睡。",
                "alternative": f"① 严守避险底线（避开: {allergy}）；② 优先选择清汤/番茄锅底，多涮优质蛋白瘦肉与蔬菜；③ 蘸料以生抽醋蒜为主，避开高热量麻酱；④ 饭后散步30分钟消食。",
                "goal_relation": "控制热量与成分则影响有限；若放纵暴食则会拉长健康与体魄目标的周期。"
            }
        elif "辞职" in question or "自媒体" in question or "创业" in question:
            result_dict = {
                "conclusion": "可以但有条件",
                "reason": f"你目前在 {profile.industry or '现有'} 行业拥有 {profile.years_of_work or 0} 年经验。转型自媒体或轻资产创业符合追求更大杠杆的长期愿景，但必须警惕现金流断裂与初期流量不确定性带来的生存焦虑。",
                "pros": "掌握个人IP杠杆，工作时间自主权翻倍，直接享受全流程商业闭环与能力复利。",
                "cons_risks": "短期0收入冷启动期平均需6-12个月；丧失稳定社保与职场资源；自律要求极高，极易陷入自我怀疑与作息紊乱。",
                "alternative": "① 坚持「副业验证法」：在当前工作之余，利用每天可支配的专注时间，跑通第一单付费或连续产出20篇精品内容验证网感；② 储备至少能支撑12-18个月正常生活的无风险备用金；③ 设定6个月的里程碑考核线。",
                "goal_relation": "与「实现时间自由与顶尖实践者」愿景高度契合，但短期若无充分准备，将严重偏离目前的财务安全底线。"
            }
        else:
            vision_hint = f"「{profile.vision[:25]}」" if profile.vision else "不断精进"
            result_dict = {
                "conclusion": "可以但有条件",
                "reason": f"结合你的长远愿景（{vision_hint}）以及当前的精力配额，任何行动的关键衡量标准在于：它是否为你提供长期复利，还是仅仅在消耗你的注意力资产。",
                "pros": "拓宽探索边界，带来新的认知反馈或短期情绪价值。",
                "cons_risks": "可能挤占核心目标的投入时长，造成注意力碎片化与精力损耗。",
                "alternative": "采取最小可行性测试（MVP思维）：先投入15-30分钟进行试探，若产出与反馈符合预期再扩大配额。",
                "goal_relation": "取决于具体执行质量。如果服务于核心成长则为助力，若为逃避困难的代偿行为则会拉大与目标的距离。"
            }

    result_dict["source"] = source
    result_dict["is_crisis"] = False

    # 存入数据库历史消息
    full_answer_text = (
        f"【结论】: {result_dict['conclusion']}\n\n"
        f"【理由】: {result_dict['reason']}\n\n"
        f"【好处】: {result_dict['pros']}\n\n"
        f"【坏处与风险】: {result_dict['cons_risks']}\n\n"
        f"【折中优化方案】: {result_dict['alternative']}\n\n"
        f"【与目标的关系】: {result_dict['goal_relation']}"
    )
    msg = AiMessage(
        user_id=user_id,
        mode="advisor",
        question=question,
        answer=full_answer_text
    )
    db.add(msg)
    db.commit()

    result_dict["full_text"] = full_answer_text
    return result_dict


def generate_daily_plan(db: Session, user_id: int = 1, auto_apply: bool = True) -> Dict[str, Any]:
    """
    D1. 每日规划 (Daily Plan)
    输出 3-5 个最重要的核心任务（服务于哪个目标、预估耗时）+ 饮食作息提醒 + 鼓励/警醒。
    """
    context_str = assemble_user_context(db, user_id)
    profile = profile_service.get_or_create_profile(db, user_id)
    goals = goal_service.get_goals(db, user_id, status="in_progress")
    client = get_ai_client()

    prompt = f"""
{context_str}

=== 任务要求 ===
今天是新的一天，请你为用户编排今日专属成长日程：
1. 梳理今日 3-5 个最重要的核心任务 (Top Tasks)。每个任务必须具体、可执行，注明服务于哪个进行中的目标、预估耗时 (分钟)。
2. 今日饮食与作息关键提醒 (结合健康状况与昨晚睡眠)。
3. 基于用户最近几天的真实表现，给出一句真诚切中要害的鼓励或警醒 (不要空话鸡汤)。

请严格输出为以下 JSON 格式：
{{
  "tasks": [
    {{
      "title": "任务名称，具体明确",
      "goal_id": 目标ID数字或null,
      "goal_name": "服务的目标名称",
      "est_minutes": 45
    }}
  ],
  "health_reminder": "今日作息与饮食指导提醒",
  "tip": "基于真实表现的鼓励或警醒"
}}
"""

    result_dict = None
    source = "ai_model"

    if client:
        try:
            response = client.chat.completions.create(
                model=settings.AI_MODEL,
                messages=[
                    {"role": "system", "content": SYSTEM_PROMPT_TEMPLATE},
                    {"role": "user", "content": prompt}
                ],
                temperature=0.4
            )
            raw = response.choices[0].message.content.strip()
            if raw.startswith("```"):
                raw = raw.split("```")[1]
                if raw.startswith("json"):
                    raw = raw[4:]
            result_dict = json.loads(raw.strip())
        except Exception as e:
            print(f"[AI Agent] Daily Plan 线上调用异常，切换智能生成: {e}")
            result_dict = None

    if not result_dict:
        source = "offline_mock"
        # 基于当前真实目标动态匹配生成
        tasks_list = []
        if goals:
            for i, g in enumerate(goals[:3]):
                tasks_list.append({
                    "title": f"攻坚「{g.title}」核心里程碑拆解与执行",
                    "goal_id": g.id,
                    "goal_name": g.title,
                    "est_minutes": 45 + i * 15
                })
        else:
            tasks_list = [
                {"title": "在个人目标树中梳理制定第1个季度里程碑", "goal_id": None, "goal_name": "目标规划", "est_minutes": 30},
                {"title": "进行30分钟核心技能深度学习", "goal_id": None, "goal_name": "能力精进", "est_minutes": 30}
            ]

        tasks_list.append({
            "title": "晚间30分钟散步或体能拉伸，复盘今日能量收支",
            "goal_id": None,
            "goal_name": "身心健康",
            "est_minutes": 30
        })

        result_dict = {
            "tasks": tasks_list,
            "health_reminder": "保证全天摄入至少1800ml纯净水，午后减少高浓度咖啡因摄入，晚餐建议摄入清淡优质蛋白与膳食纤维。",
            "tip": "伟大来自于每天微小确定性的重复累加。不追求一天的爆发，而追求连续多天不中断的节奏感！"
        }

    result_dict["source"] = source

    # 仅在 auto_apply 为真时写入 tasks 表（避免静默污染）
    if auto_apply:
        today = datetime.date.today()
        for t_item in result_dict.get("tasks", []):
            exists = db.query(Task).filter(
                Task.user_id == user_id,
                Task.date == today,
                Task.title == t_item["title"]
            ).first()
            if not exists:
                new_task = Task(
                    user_id=user_id,
                    goal_id=t_item.get("goal_id"),
                    date=today,
                    title=t_item["title"],
                    est_minutes=t_item.get("est_minutes", 30),
                    done=False
                )
                db.add(new_task)

    # 存入历史
    msg = AiMessage(
        user_id=user_id,
        mode="daily_plan",
        question="生成今日规划",
        answer=json.dumps(result_dict, ensure_ascii=False)
    )
    db.add(msg)
    db.commit()

    return result_dict


def breakdown_goal(db: Session, user_id: int, goal_title: str, goal_desc: Optional[str] = None) -> Dict[str, Any]:
    """
    AI 目标智能自动拆解
    把宏观愿景/长期目标拆成阶段里程碑和周计划
    """
    context_str = assemble_user_context(db, user_id)
    client = get_ai_client()

    prompt = f"""
{context_str}

=== 目标拆解需求 ===
用户希望将以下目标进行层级化拆解：
【目标名称】: {goal_title}
【补充描述】: {goal_desc or '无'}

请为用户制定一份高度落地、层层推进的拆解方案：
1. 阶段里程碑 (1-3个月阶段性抓手)
2. 每周关键攻坚行动 (Weekly Milestones)
3. 关键检验标准 (Metric) 与避坑指南

请输出排版优美清晰的 Markdown 格式分析文本。
"""

    if client:
        try:
            res = client.chat.completions.create(
                model=settings.AI_MODEL,
                messages=[
                    {"role": "system", "content": SYSTEM_PROMPT_TEMPLATE},
                    {"role": "user", "content": prompt}
                ],
                temperature=0.3
            )
            content = res.choices[0].message.content.strip()
            stages = goal_service.parse_breakdown_markdown(content)
            return {"plan": content, "stages": stages, "goal_title": goal_title, "source": "ai_model"}
        except Exception as e:
            print(f"[AI Agent] Breakdown 线上异常: {e}")

    # 智能降级方案
    plan_text = f"""### 🎯「{goal_title}」AI 智能拆解蓝图

#### 阶段一：基础筑底与指标摸底 (第 1-4 周)
- **阶段里程碑**：摸清现状 baseline，建立每日固定执行时间槽（每天建议专注 45 分钟）。
- **周行动清单**：
  - 第 1 周：梳理全景知识地图与核心工具链，明确首期最小交付物。
  - 第 2-4 周：高频输入与刻意练习，完成核心框架第一阶段沉淀。
- **检验标准 (Metric)**：完成阶段摸底自测，指标达成度达到预期 30%。

#### 阶段二：实战攻坚与专项突破 (第 5-8 周)
- **阶段里程碑**：聚焦短板进行靶向突破，形成可复用的模式。
- **周行动清单**：
  - 第 5-6 周：针对难度最高、杠杆最大的模块集中攻坚。
  - 第 7-8 周：模拟实战闭环，沉淀问题日志与避坑手册。

#### 阶段三：集成冲刺与最终验收 (第 9-12 周)
- **阶段里程碑**：全真验收，固化成长习惯。
- **周行动清单**：
  - 第 9-11 周：全真场景实操测试，优化细节与稳定性。
  - 第 12 周：全面复盘总结，对照初始指标验收并开启下一轮迭代。
"""
    stages = [
        {
            "title": f"阶段一：基础筑底与指标摸底 (第 1-4 周)",
            "level": "stage",
            "category": "职业",
            "metric": "完成阶段摸底自测，指标达成度达到预期 30%",
            "description": "摸清现状 baseline，建立每日固定执行时间槽（每天建议专注 45 分钟）。",
            "weekly_goals": [
                {
                    "title": "第 1 周：梳理全景知识地图与核心工具链，明确首期最小交付物",
                    "description": "梳理全景知识地图与核心工具链，明确首期最小交付物",
                    "level": "weekly"
                },
                {
                    "title": "第 2-4 周：高频输入与刻意练习，完成核心框架第一阶段沉淀",
                    "description": "高频输入与刻意练习，完成核心框架第一阶段沉淀",
                    "level": "weekly"
                }
            ]
        },
        {
            "title": f"阶段二：实战攻坚与专项突破 (第 5-8 周)",
            "level": "stage",
            "category": "职业",
            "metric": "模拟实战闭环，沉淀问题日志与避坑手册",
            "description": "聚焦短板进行靶向突破，形成可复用的模式。",
            "weekly_goals": [
                {
                    "title": "第 5-6 周：针对难度最高、杠杆最大的模块集中攻坚",
                    "description": "针对难度最高、杠杆最大的模块集中攻坚",
                    "level": "weekly"
                },
                {
                    "title": "第 7-8 周：模拟实战闭环，沉淀问题日志与避坑手册",
                    "description": "模拟实战闭环，沉淀问题日志与避坑手册",
                    "level": "weekly"
                }
            ]
        },
        {
            "title": f"阶段三：集成冲刺与最终验收 (第 9-12 周)",
            "level": "stage",
            "category": "职业",
            "metric": "全真验收通过，全面复盘总结并开启下一轮迭代",
            "description": "全真验收，固化成长习惯。",
            "weekly_goals": [
                {
                    "title": "第 9-11 周：全真场景实操测试，优化细节与稳定性",
                    "description": "全真场景实操测试，优化细节与稳定性",
                    "level": "weekly"
                },
                {
                    "title": "第 12 周：全面复盘总结，对照初始指标验收并开启下一轮迭代",
                    "description": "全面复盘总结，对照初始指标验收并开启下一轮迭代",
                    "level": "weekly"
                }
            ]
        }
    ]
    return {"plan": plan_text, "stages": stages, "goal_title": goal_title, "source": "offline_mock"}


def generate_review(db: Session, user_id: int = 1, review_type: str = "daily", target_date_str: Optional[str] = None) -> Dict[str, Any]:
    """
    D3. 每日/每周复盘 (Review)
    - 每日复盘: 对比当日计划与实际完成情况, 指出做得好的和偏离的
    - 每周复盘: 汇总本周数据, 输出各目标进度变化、本周最大偏差、下周调整建议
    """
    context_str = assemble_user_context(db, user_id)
    today = datetime.date.today()
    if target_date_str:
        try:
            today = datetime.date.fromisoformat(target_date_str)
        except ValueError:
            pass

    client = get_ai_client()
    source = "ai_model"

    if review_type == "daily":
        today_tasks = goal_service.get_tasks(db, user_id=user_id, target_date=today)
        today_log = log_service.get_log_by_date(db, user_id=user_id, target_date=today)
        done_count = len([t for t in today_tasks if t.done])
        total_tasks = len(today_tasks)

        prompt = f"""
{context_str}

=== 每日复盘数据 ===
- 复盘日期: {today.isoformat()}
- 当日计划任务完成情况: {done_count}/{total_tasks}项已完成
- 当日生活记录:
  - 产出事项: {today_log.done_items if today_log else '无记录'}
  - 学习时长: {today_log.study_minutes if today_log else 0}分钟 (笔记: {today_log.study_note if today_log else '无'})
  - 饮食: {json.dumps(today_log.meals if today_log else {}, ensure_ascii=False)}
  - 睡眠: {today_log.sleep_start if today_log else '?'}-{today_log.sleep_end if today_log else '?'}
  - 运动: {today_log.exercise_type if today_log else '无'}({today_log.exercise_minutes if today_log else 0}min)
  - 心情/精力: {today_log.mood if today_log else '?'}/5分, {today_log.energy if today_log else '?'}/5分
  - 个人反思: {today_log.reflection if today_log else '无'}

请严格按照以下 JSON 格式输出今日深度复盘：
{{
  "type": "daily",
  "title": "{today.isoformat()} 每日战术复盘",
  "summary": "当日表现总体定性评估与能量评价",
  "highlights": "做得很棒的亮点与关键交付（具体引用数据）",
  "deviations": "计划偏离、拖延或未达标之处与根本归因",
  "next_actions": "针对明天的2-3个具体战术修正建议"
}}
"""
        result_dict = None
        if client:
            try:
                res = client.chat.completions.create(
                    model=settings.AI_MODEL,
                    messages=[
                        {"role": "system", "content": SYSTEM_PROMPT_TEMPLATE},
                        {"role": "user", "content": prompt}
                    ],
                    temperature=0.3
                )
                raw = res.choices[0].message.content.strip()
                if raw.startswith("```"):
                    raw = raw.split("```")[1]
                    if raw.startswith("json"):
                        raw = raw[4:]
                result_dict = json.loads(raw.strip())
            except Exception as e:
                print(f"[AI Agent] Daily Review 线上异常: {e}")
                result_dict = None

        if not result_dict:
            source = "offline_mock"
            # 智能降级启发式复盘
            study_m = today_log.study_minutes if today_log else 0
            task_ratio = f"{done_count}/{total_tasks}" if total_tasks > 0 else "无预设任务"
            result_dict = {
                "type": "daily",
                "title": f"{today.isoformat()} 每日战术复盘",
                "summary": f"今日任务推进完成度 {task_ratio}，累计学习投入 {study_m} 分钟。生活节奏整体受控，展现了清晰的自我觉察与执行韧性。",
                "highlights": f"成功完成了 {done_count} 项重点计划任务，特别是在核心产出上保持了推进节奏，学习时长达到 {study_m} 分钟，为知识复利打下扎实基底。",
                "deviations": "在多任务切换时偶有注意力精力消耗，部分琐碎事项挤占了深度时间块，晚上入睡时间与预设作息仍有微小偏差。",
                "next_actions": "明天将最重要的第一优先级任务安排在上午精力峰值完成；晚间提前30分钟远离蓝光屏幕，巩固深度睡眠。"
            }

    else: # weekly
        recent_logs = log_service.get_recent_logs(db, user_id=user_id, days=7)
        goals = goal_service.get_goals(db, user_id=user_id, status="in_progress")
        total_study = sum([l.study_minutes for l in recent_logs])
        avg_study = round(total_study / max(len(recent_logs), 1), 1)

        prompt = f"""
{context_str}

=== 周度复盘数据 ===
- 周期: 最近7天
- 累计学习投入: {total_study}分钟 (日均 {avg_study} 分钟)
- 进行中目标数: {len(goals)}
- 记录留存天数: {len(recent_logs)}天

请严格输出为以下 JSON 格式的周度战略复盘：
{{
  "type": "weekly",
  "title": "本周战略复盘与目标推进报告",
  "summary": "本周成长全景总结与阶段位移评估",
  "goal_progress": "各重点目标的推进动态与进度变化分析",
  "biggest_deviation": "本周最大的认知、精力或执行偏差",
  "next_week_strategy": "下周的核心攻坚策略与行动防线"
}}
"""
        result_dict = None
        if client:
            try:
                res = client.chat.completions.create(
                    model=settings.AI_MODEL,
                    messages=[
                        {"role": "system", "content": SYSTEM_PROMPT_TEMPLATE},
                        {"role": "user", "content": prompt}
                    ],
                    temperature=0.3
                )
                raw = res.choices[0].message.content.strip()
                if raw.startswith("```"):
                    raw = raw.split("```")[1]
                    if raw.startswith("json"):
                        raw = raw[4:]
                result_dict = json.loads(raw.strip())
            except Exception as e:
                print(f"[AI Agent] Weekly Review 线上异常: {e}")
                result_dict = None

        if not result_dict:
            source = "offline_mock"
            goals_summary_str = "、".join([f"「{g.title}」(当前进度{g.progress}%)" for g in goals[:3]]) if goals else "目标系统搭建中"
            result_dict = {
                "type": "weekly",
                "title": "本周战略复盘与目标推进报告",
                "summary": f"本周共留存 {len(recent_logs)} 天真实记录，累计专注学习 {total_study} 分钟 (日均约 {avg_study} 分钟)。整体处于稳步上升阶段。",
                "goal_progress": f"本周重点推进了 {goals_summary_str}。核心骨架已经打通，但在子里程碑的量化反馈上仍有继续细化的空间。",
                "biggest_deviation": "本周最大的摩擦点在于高强度工作日与周末之间的作息节奏切换，周末初期容易出现断流效应，拉低了全周的平均专注深度。",
                "next_week_strategy": "下周贯彻「先定桩后填充」原则：周一清晨确立本周不可动摇的 2 个交付硬指标；每晚睡前花 2 分钟做极简记录，避免记录断流。"
            }

    result_dict["source"] = source

    # 存入数据库
    full_text = f"【{result_dict.get('title', '复盘报告')}】\n\n" + "\n\n".join([f"【{k}】: {v}" for k, v in result_dict.items() if k not in ["type", "title"]])
    msg = AiMessage(
        user_id=user_id,
        mode="review",
        question=f"{review_type} review ({today.isoformat()})",
        answer=full_text
    )
    db.add(msg)
    db.commit()

    result_dict["full_text"] = full_text
    return result_dict

