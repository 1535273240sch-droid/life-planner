import os
import re
import json
import logging
import datetime
from typing import Dict, Any, List, Optional
from sqlalchemy.orm import Session
import openai
from openai import OpenAI

from config import settings
from models import Profile, Skill, Goal, Task, DailyLog, AiMessage
from services import profile_service, goal_service, log_service

logger = logging.getLogger("ai_agent")

# 明确的超时与重试上限：最坏情况下单次请求约 2 分钟内必定返回（成功或报错）
_AI_TIMEOUT_SECONDS = 60.0
_AI_MAX_RETRIES = 1

# 错误码 -> (HTTP 状态码, 默认中文说明)。说明文字是固定文案：
# 绝不拼接 API Key、请求体、用户档案/日志等任何敏感内容。
_ERROR_DEFAULTS = {
    "ai_not_configured": (503, "尚未配置 AI 服务：请在 .env 中设置 AI_API_KEY（并按需设置 AI_BASE_URL、AI_MODEL）后重启服务。"),
    "ai_unreachable": (502, "无法连接到 AI 服务（网络不通或请求超时），请检查网络与 AI_BASE_URL 后重试。"),
    "ai_auth_failed": (502, "AI 服务拒绝了当前凭证（认证或权限失败），请检查 AI_API_KEY 是否正确、是否有权调用所选模型。"),
    "ai_rate_limited": (502, "AI 服务请求过于频繁或额度不足（HTTP 429），请稍后重试，或检查账户额度。"),
    "ai_bad_response": (502, "AI 返回的内容格式不符合要求，未能生成结果，请重试。"),
}


class AIError(Exception):
    """AI 调用失败的统一异常。路由层据此转成 HTTP 状态码 + {"code", "message"}。

    属性 code / message / status_code 被路由层依赖，名称不得更改。
    """

    def __init__(self, code: str, message: Optional[str] = None, status_code: Optional[int] = None):
        default_status, default_message = _ERROR_DEFAULTS.get(code, (502, "AI 服务调用失败，请稍后重试。"))
        self.code = code
        self.message = message or default_message
        self.status_code = status_code or default_status
        super().__init__(self.message)


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
6. 不得编造用户没提供的数据：上下文里没有的信息就明确说“没有记录”，不得臆测或补全。
7. 引用数据时必须使用上下文里出现的真实数值（进度、时长、分数、日期等），不得估算、改写或夸大。
"""

def _active_goals(db: Session, user_id: int) -> List[Goal]:
    """进行中且未软删除的目标。get_goals 理应已过滤 deleted_at，这里再兜底过滤一次。"""
    goals = goal_service.get_goals(db, user_id, status="in_progress")
    return [g for g in goals if getattr(g, "deleted_at", None) is None]


def assemble_user_context(db: Session, user_id: int = 1) -> str:
    """提取用户全部上下文：档案、技能、进行中目标(不含已删除)、最近7天日志"""
    profile = profile_service.get_or_create_profile(db, user_id)
    skills = profile_service.get_skills(db, user_id)
    goals = _active_goals(db, user_id)
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

    # 进行中目标（带 ID，供日计划/周复盘引用）
    lines.append(f"【正在推进的重点目标】")
    if goals:
        for g in goals:
            lines.append(f"- (ID:{g.id}) [{g.level.upper()}] {g.title} | 分类: {g.category} | 进度: {g.progress}% | 衡量标准: {g.metric or '无'} | 截止: {g.due_date or '长期'}")
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


# ---------------------------------------------------------------------------
# 危机筛查（确定性，不依赖模型、不依赖网络）
# ---------------------------------------------------------------------------
# 取舍说明（有意为之）：
# 这里只做子串匹配，不做否定句/语境判断。因此“我不是想死，只是累”“电影里有人自杀”
# 这类句子也会命中。选择“宁多勿漏”：误拦的代价只是多显示一段关怀文字（用户可继续使用），
# 漏拦的代价是生命安全。不要为了降低误报而在这里加否定词豁免。
_CRISIS_KEYWORDS = [
    # 原有
    "不想活", "不想活了", "想死", "想自杀", "自杀", "自残", "割腕", "跳楼", "轻生",
    "结束生命", "活着没意思", "去死", "不想存在", "想结束这一切", "活不下去了", "好绝望不想活",
    # 扩充：生存意愿类
    "活不下去", "不想再活", "活够了", "不如死了", "死了算了", "寻死", "求死", "不想醒来",
    "想消失", "不想继续活", "一了百了", "生无可恋", "想不开", "活着没意义", "寻短见",
    # 扩充：自我终结/自伤类
    "结束自己", "了结自己", "自我了断", "了断自己", "自尽", "上吊", "服毒",
    "伤害自己", "自我伤害", "自伤", "结束自己的生命", "了结生命",
    "自我了结", "了结此生", "了结一切", "以死解脱", "以死明志",
    # 扩充：英文常见表述
    "suicide", "killmyself", "wanttodie", "endmylife", "selfharm", "unalivemyself",
]


def _build_crisis_response() -> Dict[str, Any]:
    """危机关怀响应（每次返回新字典，各入口复用）。"""
    return {
        "is_crisis": True,
        "conclusion": "必须停止规划并寻求专业支持",
        "reason": "生命与身心健康是所有人生成长与目标存在的绝对前提。感受到你此刻可能正在承受巨大的心理痛苦与绝望，此时任何待办与目标都不及你的生命安危重要。请停下所有规划，给自己一个安全的保护空间。",
        "pros": "向外界发出求助是勇敢的自救，能及时获得医学、专业心理咨询与身边人的关怀陪伴，渡过眼前的至暗时刻。",
        "cons_risks": "独自一人默默承受极度痛苦容易陷入认知隧道与不可逆的高危行动，伤害会永远剥夺未来的一切可能。",
        "alternative": "请立即放下手头所有事情，联系你最信任的亲人或朋友陪在身边。并请立即拨打以下 24 小时全国免费专业心理援助热线获取支持：\n• 全国希望 24 小时生命救助热线：400-161-9995\n• 北京心理危机研究与干预中心热线：010-82951332 或 800-810-1117\n• 共青团青少年心理咨询援助热线：12355\n• 若情况紧急有即时危险，请立即拨打 110 或 120 寻求保护！",
        "goal_relation": "活着才有一切希望。没有任何一项世俗目标值得以生命与健康为代价，请务必珍视自己。",
        "source": "crisis_safety_guard",
    }


def _crisis_full_text(c: Dict[str, Any]) -> str:
    return (
        f"【结论】: {c['conclusion']}\n\n"
        f"【理由】: {c['reason']}\n\n"
        f"【好处】: {c['pros']}\n\n"
        f"【坏处与风险】: {c['cons_risks']}\n\n"
        f"【折中优化方案】: {c['alternative']}\n\n"
        f"【与目标的关系】: {c['goal_relation']}"
    )


def check_crisis_content(text: str) -> Optional[Dict[str, Any]]:
    """
    【规划书第4节 规则3 绝对安全防线】
    确定性前置危机筛查。命中返回危机响应字典，否则返回 None。
    宽松匹配的取舍见 _CRISIS_KEYWORDS 上方注释。
    """
    if not text or not isinstance(text, str):
        return None
    text_clean = re.sub(r"\s+", "", text).lower()
    if any(k in text_clean for k in _CRISIS_KEYWORDS):
        return _build_crisis_response()
    return None


def _scan_logs_for_crisis(logs: List[DailyLog]) -> Optional[Dict[str, Any]]:
    """扫描日志里的自由文本字段（反思、产出、学习笔记）。"""
    for log in logs:
        for field in (log.reflection, log.done_items, log.study_note):
            hit = check_crisis_content(field)
            if hit:
                return hit
    return None


def _handle_crisis(db: Session, user_id: int, mode: str, question: str, crisis: Dict[str, Any]) -> Dict[str, Any]:
    """危机短路：不调用模型，写一条 source=crisis_safety_guard 的历史，返回危机响应。"""
    profile_service.get_or_create_user(db, user_id)  # 保证外键满足
    full_text = _crisis_full_text(crisis)
    _save_message(db, user_id, mode, question, full_text, source="crisis_safety_guard", model_name=None)
    db.commit()
    crisis["full_text"] = full_text
    return crisis


def _save_message(db: Session, user_id: int, mode: str, question: str, answer: str,
                  source: str, model_name: Optional[str]) -> None:
    """只 add 不 commit，由调用方与其他写入一并提交。"""
    db.add(AiMessage(
        user_id=user_id, mode=mode, question=question, answer=answer,
        source=source, model_name=model_name,
    ))


# ---------------------------------------------------------------------------
# 模型调用与输出校验
# ---------------------------------------------------------------------------
def _ensure_configured() -> None:
    if not (settings.AI_API_KEY or "").strip():
        raise AIError("ai_not_configured")


def _call_model(messages: List[Dict[str, str]], temperature: float = 0.3) -> str:
    """
    唯一的模型出口：返回模型文本，或抛 AIError。
    日志与异常说明里只出现异常类型/状态码，绝不包含 Key、请求体、用户数据。
    """
    _ensure_configured()
    try:
        client = OpenAI(
            api_key=settings.AI_API_KEY,
            base_url=settings.AI_BASE_URL,
            timeout=_AI_TIMEOUT_SECONDS,
            max_retries=_AI_MAX_RETRIES,
        )
        response = client.chat.completions.create(
            model=settings.AI_MODEL,
            messages=messages,
            temperature=temperature,
        )
    except openai.APIConnectionError as e:  # 含 APITimeoutError
        logger.warning("AI call failed: %s", type(e).__name__)
        raise AIError("ai_unreachable") from None
    except (openai.AuthenticationError, openai.PermissionDeniedError) as e:
        logger.warning("AI call failed: %s", type(e).__name__)
        raise AIError("ai_auth_failed") from None
    except openai.RateLimitError as e:
        logger.warning("AI call failed: %s", type(e).__name__)
        raise AIError("ai_rate_limited") from None
    except openai.APIStatusError as e:
        # 其余 HTTP 错误：5xx/408 是服务端暂时不可用 -> unreachable；
        # 其他 4xx（400/404/422 等，多为模型名或端点配置不对、请求被拒）-> bad_response，
        # 因为契约里没有更贴切的码，用定制说明告诉用户去查配置。只透出状态码数字。
        code = getattr(e, "status_code", 0) or 0
        logger.warning("AI call failed: %s status=%s", type(e).__name__, code)
        if code >= 500 or code == 408:
            raise AIError("ai_unreachable", f"AI 服务暂时不可用（HTTP {code}），请稍后重试。") from None
        raise AIError("ai_bad_response", f"AI 服务拒绝了本次请求（HTTP {code}），请检查 AI_MODEL 与 AI_BASE_URL 配置。") from None
    except openai.APIError as e:
        # 无状态码的其他 SDK 错误（如响应体无法按协议解析）-> bad_response
        logger.warning("AI call failed: %s", type(e).__name__)
        raise AIError("ai_bad_response") from None
    except Exception as e:
        # 非 SDK 异常（如 base_url 非法、底层传输异常）：对用户而言等同于连不上
        logger.warning("AI call failed: %s", type(e).__name__)
        raise AIError("ai_unreachable") from None

    try:
        content = response.choices[0].message.content
    except (AttributeError, IndexError, TypeError):
        raise AIError("ai_bad_response") from None
    if not isinstance(content, str) or not content.strip():
        raise AIError("ai_bad_response")
    return content.strip()


def _extract_json(text: str) -> Dict[str, Any]:
    """
    从模型文本中稳健地取出一个 JSON 对象（dict），取不到抛 ai_bad_response。
    依次尝试：整段直接解析 -> 各个 ``` 围栏块（带不带 json 标记均可，多个围栏取第一个能解析的）
    -> 在全文里从每个 '{' 起 raw_decode（容忍围栏未闭合、JSON 前后夹杂说明文字）。
    顶层不是对象（如数组、字符串）一律视为失败。
    """
    if not isinstance(text, str) or not text.strip():
        raise AIError("ai_bad_response")
    s = text.strip().lstrip("\ufeff")

    def _try(candidate: str) -> Optional[Dict[str, Any]]:
        try:
            val = json.loads(candidate.strip())
        except (ValueError, TypeError):
            return None
        return val if isinstance(val, dict) else None

    direct = _try(s)
    if direct is not None:
        return direct

    for block in re.findall(r"```[ \t]*(?:json|JSON)?[ \t]*\r?\n?(.*?)```", s, flags=re.DOTALL):
        got = _try(block)
        if got is not None:
            return got

    decoder = json.JSONDecoder()
    idx = s.find("{")
    while idx != -1:
        try:
            val, _ = decoder.raw_decode(s, idx)
            if isinstance(val, dict):
                return val
        except ValueError:
            pass
        idx = s.find("{", idx + 1)

    raise AIError("ai_bad_response")


def _require_str_fields(data: Dict[str, Any], fields: List[str]) -> Dict[str, str]:
    """必需字段必须存在且为非空字符串；只返回这些字段（丢弃模型多给的任何键）。"""
    out: Dict[str, str] = {}
    for f in fields:
        v = data.get(f)
        if not isinstance(v, str) or not v.strip():
            raise AIError("ai_bad_response")
        out[f] = v.strip()
    return out


def _as_int(v: Any) -> Optional[int]:
    """int 或纯数字字符串 -> int；bool、小数、其他一律 None。"""
    if isinstance(v, bool):
        return None
    if isinstance(v, int):
        return v
    if isinstance(v, str) and v.strip().isdigit():
        return int(v.strip())
    return None


def _ask_model_json(user_prompt: str, temperature: float) -> Dict[str, Any]:
    raw = _call_model(
        [{"role": "system", "content": SYSTEM_PROMPT_TEMPLATE},
         {"role": "user", "content": user_prompt}],
        temperature,
    )
    return _extract_json(raw)

_SOURCE_AI = "ai_model"
_ALLOWED_CONCLUSIONS = ("建议做", "不建议做", "可以但有条件")


def _recent_logs_crisis(db: Session, user_id: int, days: int) -> Optional[Dict[str, Any]]:
    return _scan_logs_for_crisis(log_service.get_recent_logs(db, user_id, days=days))


def ask_advisor(db: Session, user_id: int, question: str) -> Dict[str, Any]:
    """
    D2. 决策顾问 (Ask Advisor)，严格按 6 段式结构输出：
    结论(三选一) / 理由 / 好处 / 坏处与风险 / 折中优化方案 / 与目标的关系。
    失败时抛 AIError，不写库、不返回替代内容。
    """
    # 1. 确定性前置危机筛查：命中即短路，不调用模型，永远成功
    crisis = check_crisis_content(question)
    if crisis:
        return _handle_crisis(db, user_id, "advisor", question, crisis)

    _ensure_configured()
    context_str = assemble_user_context(db, user_id)

    prompt = f"""
{context_str}

=== 用户提问 ===
用户向你咨询一个行动或生活决策：
"{question}"

=== 回答规范要求 ===
只能依据上面上下文中真实出现的数据作答；上下文没有的信息就明确说“没有记录”，不要编造。
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
    data = _ask_model_json(prompt, 0.3)
    result = _require_str_fields(data, ["conclusion", "reason", "pros", "cons_risks", "alternative", "goal_relation"])
    # 不在三选一之内就是模型违约：按 bad_response 处理，不悄悄纠正成某个值
    if result["conclusion"] not in _ALLOWED_CONCLUSIONS:
        raise AIError("ai_bad_response")

    full_text = (
        f"【结论】: {result['conclusion']}\n\n"
        f"【理由】: {result['reason']}\n\n"
        f"【好处】: {result['pros']}\n\n"
        f"【坏处与风险】: {result['cons_risks']}\n\n"
        f"【折中优化方案】: {result['alternative']}\n\n"
        f"【与目标的关系】: {result['goal_relation']}"
    )
    _save_message(db, user_id, "advisor", question, full_text, _SOURCE_AI, settings.AI_MODEL)
    db.commit()

    result.update({"is_crisis": False, "source": _SOURCE_AI, "model": settings.AI_MODEL, "full_text": full_text})
    return result


def generate_daily_plan(db: Session, user_id: int = 1, auto_apply: bool = True) -> Dict[str, Any]:
    """
    D1. 每日规划 (Daily Plan)
    输出 3-5 个核心任务 + 饮食作息提醒 + 鼓励/警醒。auto_apply 为真时才把任务写入 tasks。
    失败时抛 AIError，不写 ai_messages、不写 tasks。
    """
    # 近 3 天日志里出现危机表述 -> 停止规划
    crisis = _recent_logs_crisis(db, user_id, days=3)
    if crisis:
        return _handle_crisis(db, user_id, "daily_plan", "生成今日规划", crisis)

    _ensure_configured()
    context_str = assemble_user_context(db, user_id)
    goals = {g.id: g for g in _active_goals(db, user_id)}

    prompt = f"""
{context_str}

=== 任务要求 ===
今天是新的一天，请你为用户编排今日专属成长日程：
1. 梳理今日 3-5 个最重要的核心任务 (Top Tasks)。每个任务必须具体、可执行，注明服务于哪个进行中的目标、预估耗时 (分钟)。
2. 今日饮食与作息关键提醒 (结合健康状况与昨晚睡眠)。
3. 基于用户最近几天的真实表现，给出一句真诚切中要害的鼓励或警醒 (不要空话鸡汤)。

只能依据上下文中真实出现的数据；没有记录就直说没有，不要编造。
goal_id 只能填上面“正在推进的重点目标”里 (ID:n) 的数字 n；任务不服务于任何列出的目标时填 null。

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
    data = _ask_model_json(prompt, 0.4)
    texts = _require_str_fields(data, ["health_reminder", "tip"])

    raw_tasks = data.get("tasks")
    if not isinstance(raw_tasks, list) or not raw_tasks:
        raise AIError("ai_bad_response")
    tasks: List[Dict[str, Any]] = []
    for item in raw_tasks:
        if not isinstance(item, dict):
            raise AIError("ai_bad_response")
        title = item.get("title")
        if not isinstance(title, str) or not title.strip():
            raise AIError("ai_bad_response")
        minutes = _as_int(item.get("est_minutes"))
        if minutes is None or not (1 <= minutes <= 1440):
            minutes = 30
        gid = _as_int(item.get("goal_id"))
        goal = goals.get(gid) if gid is not None else None  # 不在进行中集合里 -> 置 None
        goal_name = goal.title if goal else (item.get("goal_name") if isinstance(item.get("goal_name"), str) else "")
        tasks.append({
            "title": title.strip()[:200],  # tasks.title 是 String(200)
            "goal_id": goal.id if goal else None,
            "goal_name": goal_name,
            "est_minutes": minutes,
        })

    result = {
        "tasks": tasks,
        "health_reminder": texts["health_reminder"],
        "tip": texts["tip"],
        "is_crisis": False,
        "source": _SOURCE_AI,
        "model": settings.AI_MODEL,
    }

    # 校验全部通过后才写库；任务与历史消息同一次提交
    if auto_apply:
        today = datetime.date.today()
        seen = set()
        for t in tasks:
            if t["title"] in seen:
                continue
            seen.add(t["title"])
            exists = db.query(Task).filter(
                Task.user_id == user_id, Task.date == today, Task.title == t["title"]
            ).first()
            if not exists:
                db.add(Task(user_id=user_id, goal_id=t["goal_id"], date=today,
                            title=t["title"], est_minutes=t["est_minutes"], done=False))

    _save_message(db, user_id, "daily_plan", "生成今日规划",
                  json.dumps(result, ensure_ascii=False), _SOURCE_AI, settings.AI_MODEL)
    db.commit()
    return result


def _strip_outer_fence(text: str) -> str:
    """模型有时把整份 Markdown 包进 ```markdown ... ```，去掉最外层围栏。"""
    m = re.match(r"^```[ \t]*(?:markdown|md)?[ \t]*\r?\n(.*?)\r?\n?```\s*$", text.strip(), flags=re.DOTALL | re.IGNORECASE)
    return m.group(1).strip() if m else text.strip()


def breakdown_goal(db: Session, user_id: int, goal_title: str, goal_desc: Optional[str] = None) -> Dict[str, Any]:
    """
    AI 目标智能自动拆解：宏观目标 -> 阶段里程碑 + 周计划。
    返回 {"plan", "stages", "goal_title", "source", "model"}；失败抛 AIError，不返回替代内容。
    本函数不写库（落库由 /ai-breakdown/apply 在用户确认后完成）。
    """
    _ensure_configured()
    context_str = assemble_user_context(db, user_id)

    prompt = f"""
{context_str}

=== 目标拆解需求 ===
用户希望将以下目标进行层级化拆解：
【目标名称】: {goal_title}
【补充描述】: {goal_desc or '无'}

请为用户制定一份高度落地、层层推进的拆解方案，只依据上下文真实数据，不要编造用户没提供的信息：
1. 阶段里程碑 (1-3个月阶段性抓手)
2. 每周关键攻坚行动 (Weekly Milestones)
3. 关键检验标准 (Metric) 与避坑指南

请输出 Markdown，并严格遵守下面的排版，程序会据此解析：
- 每个阶段以单独一行的三级标题开头，形如 “### 阶段一：xxx (第 1-4 周)”；
- 阶段下用 “- **阶段里程碑**：…” 写里程碑，“- **检验标准**：…” 写衡量标准；
- 每周行动各占一行，形如 “  - 第 1 周：…”。
"""
    content = _strip_outer_fence(_call_model(
        [{"role": "system", "content": SYSTEM_PROMPT_TEMPLATE},
         {"role": "user", "content": prompt}], 0.3))
    stages = goal_service.parse_breakdown_markdown(content) if content else []
    if not content or not stages:
        # 拆不出任何阶段就无法进入“确认并落库”环节，等同于模型未按约定结构返回
        raise AIError("ai_bad_response")
    return {"plan": content, "stages": stages, "goal_title": goal_title,
            "source": _SOURCE_AI, "model": settings.AI_MODEL}


def _validate_progress_suggestions(raw: Any, goals: Dict[int, Goal]) -> List[Dict[str, Any]]:
    """按契约过滤模型给的进度建议：只留进行中且未删除的目标、0-100 整数、reason 非空。不凑数。"""
    if not isinstance(raw, list):
        return []
    out: List[Dict[str, Any]] = []
    seen = set()
    for item in raw:
        if not isinstance(item, dict):
            continue
        gid = _as_int(item.get("goal_id"))
        goal = goals.get(gid) if gid is not None else None
        if goal is None or gid in seen:
            continue
        sp = item.get("suggested_progress")
        if isinstance(sp, bool) or not isinstance(sp, int) or not (0 <= sp <= 100):
            continue
        reason = item.get("reason")
        if not isinstance(reason, str) or not reason.strip():
            continue
        seen.add(gid)
        out.append({
            "goal_id": goal.id,
            "title": goal.title,
            "current_progress": goal.progress,
            "suggested_progress": sp,
            "reason": reason.strip(),
        })
    return out


def generate_review(db: Session, user_id: int = 1, review_type: str = "daily", target_date_str: Optional[str] = None) -> Dict[str, Any]:
    """
    D3. 每日/每周复盘 (Review)
    - 每日: 对比当日计划与实际完成情况
    - 每周: 汇总近 7 天，并给出 progress_suggestions（只返回，绝不写 goals.progress）
    失败时抛 AIError，不写库。
    """
    today = datetime.date.today()
    if target_date_str:
        try:
            today = datetime.date.fromisoformat(target_date_str)
        except ValueError:
            pass
    question = f"{review_type} review ({today.isoformat()})"
    is_daily = review_type == "daily"

    # 危机筛查：每日看当日日志，每周看近 7 天
    if is_daily:
        today_log = log_service.get_log_by_date(db, user_id=user_id, target_date=today)
        crisis = _scan_logs_for_crisis([today_log] if today_log else [])
    else:
        crisis = _recent_logs_crisis(db, user_id, days=7)
    if crisis:
        return _handle_crisis(db, user_id, "review", question, crisis)

    _ensure_configured()
    context_str = assemble_user_context(db, user_id)

    if is_daily:
        today_tasks = goal_service.get_tasks(db, user_id=user_id, target_date=today)
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

只能引用上面真实出现的数据；某项显示“无记录”就如实说没有记录，不要编造。
请严格按照以下 JSON 格式输出今日深度复盘：
{{
  "summary": "当日表现总体定性评估与能量评价",
  "highlights": "做得很棒的亮点与关键交付（具体引用数据）",
  "deviations": "计划偏离、拖延或未达标之处与根本归因",
  "next_actions": "针对明天的2-3个具体战术修正建议"
}}
"""
        data = _ask_model_json(prompt, 0.3)
        fields = _require_str_fields(data, ["summary", "highlights", "deviations", "next_actions"])
        result: Dict[str, Any] = {"type": "daily", "title": f"{today.isoformat()} 每日战术复盘", **fields}
        labels = {"summary": "总结", "highlights": "亮点", "deviations": "偏离", "next_actions": "明日行动"}
        suggestions: List[Dict[str, Any]] = []
    else:
        recent_logs = log_service.get_recent_logs(db, user_id=user_id, days=7)
        goals = {g.id: g for g in _active_goals(db, user_id)}
        total_study = sum([l.study_minutes or 0 for l in recent_logs])
        avg_study = round(total_study / max(len(recent_logs), 1), 1)
        goal_lines = "\n".join(
            f"  - goal_id={g.id} | {g.title} | 当前进度 {g.progress}%" for g in goals.values()
        ) or "  - （没有进行中的目标）"
        prompt = f"""
{context_str}

=== 周度复盘数据 ===
- 周期: 最近7天
- 累计学习投入: {total_study}分钟 (日均 {avg_study} 分钟)
- 进行中目标数: {len(goals)}
- 记录留存天数: {len(recent_logs)}天
- 进行中目标清单（id / 标题 / 当前进度）:
{goal_lines}

只能引用上面真实出现的数据，不要编造。
progress_suggestions 的规则：只对“有明确依据”的目标给建议——依据必须是上面最近7天记录里真实出现的行动与数值；
goal_id 只能取自上面清单；suggested_progress 为 0-100 的整数；reason 里要引用具体的真实记录。
没有足够依据的目标不要给建议，没有任何依据时返回空数组 []，不要为了凑数而给出建议。
请严格输出为以下 JSON 格式的周度战略复盘：
{{
  "summary": "本周成长全景总结与阶段位移评估",
  "goal_progress": "各重点目标的推进动态与进度变化分析",
  "biggest_deviation": "本周最大的认知、精力或执行偏差",
  "next_week_strategy": "下周的核心攻坚策略与行动防线",
  "progress_suggestions": [
    {{"goal_id": 目标id数字, "suggested_progress": 0到100的整数, "reason": "引用真实记录的依据"}}
  ]
}}
"""
        data = _ask_model_json(prompt, 0.3)
        fields = _require_str_fields(data, ["summary", "goal_progress", "biggest_deviation", "next_week_strategy"])
        suggestions = _validate_progress_suggestions(data.get("progress_suggestions"), goals)
        result = {"type": "weekly", "title": "本周战略复盘与目标推进报告", **fields}
        labels = {"summary": "总结", "goal_progress": "目标推进", "biggest_deviation": "最大偏差", "next_week_strategy": "下周策略"}
        result["progress_suggestions"] = suggestions

    full_text = f"【{result['title']}】\n\n" + "\n\n".join(f"【{labels[k]}】: {result[k]}" for k in labels)
    if suggestions:
        full_text += "\n\n【进度建议（待用户确认，未写入）】\n" + "\n".join(
            f"- {s['title']}: {s['current_progress']}% -> {s['suggested_progress']}%（{s['reason']}）" for s in suggestions
        )

    _save_message(db, user_id, "review", question, full_text, _SOURCE_AI, settings.AI_MODEL)
    db.commit()

    result.update({"is_crisis": False, "source": _SOURCE_AI, "model": settings.AI_MODEL, "full_text": full_text})
    return result
