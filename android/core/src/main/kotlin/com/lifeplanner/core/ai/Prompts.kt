package com.lifeplanner.core.ai

import com.lifeplanner.core.model.DailyLog
import com.lifeplanner.core.model.Goal
import com.lifeplanner.core.model.Profile
import com.lifeplanner.core.model.Skill
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.buildJsonObject

/**
 * AI 提示词与上下文组装。文案与格式照 v1 ai_agent.py：
 * 系统提示词（含 7 条安全与诚实规则）、assemble_user_context、各模式用户提示词。
 */
object Prompts {

    /** 系统提示词，照抄 v1 SYSTEM_PROMPT_TEMPLATE。 */
    fun systemPrompt(): String = SYSTEM_PROMPT

    /**
     * 用户上下文，格式与 v1 assemble_user_context 一致：
     * 基本档案 / 健康底座 / 技能 / 进行中目标（带 (ID:n)，供模型引用）/ 最近 7 天记录。
     * goals 只接收“进行中且未删除”的列表（由调用方过滤）。
     */
    fun buildContext(
        profile: Profile?,
        skills: List<Skill>,
        goals: List<Goal>,
        recentLogs: List<DailyLog>,
    ): String {
        val lines = ArrayList<String>()
        lines += "=== 用户当前核心画像与上下文 ==="

        // 档案
        lines += "【基本档案】"
        lines += "- 职业与背景: ${profile?.occupation ?: "未填"} | 行业: ${profile?.industry ?: "未填"} | " +
            "工龄: ${profile?.yearsOfWork ?: 0}年 | 学历: ${profile?.education ?: "未填"}(${profile?.major ?: "未填"})"
        lines += "- 人生愿景: ${profile?.vision ?: "尚未定义"}"
        lines += "- 价值观与限制: ${profile?.constraints ?: "无特殊限制"}"

        // 健康底座
        val health = profile?.healthInfo ?: emptyMap()
        lines += "【健康底座】"
        lines += "- 身高体重: ${health["height"] ?: "未知"}cm, ${health["weight"] ?: "未知"}kg"
        lines += "- 作息习惯: ${health["sleep_habit"] ?: "未知"}"
        lines += "- 饮食偏好与忌口: ${health["diet_pref"] ?: "无"} / 忌口: ${health["taboo"] ?: "无"}"
        lines += "- 已知疾病/过敏/风险: ${health["allergy_or_disease"] ?: "无特殊记录"}"

        // 技能矩阵
        lines += "【现有技能水平 (1-10分)】"
        if (skills.isNotEmpty()) {
            for (s in skills) lines += "- ${s.name} (${s.category}): ${s.level}/10分 [${s.note ?: ""}]"
        } else {
            lines += "- 暂无技能录入"
        }

        // 进行中目标（带 ID，供日计划/周复盘引用）
        lines += "【正在推进的重点目标】"
        if (goals.isNotEmpty()) {
            for (g in goals) {
                lines += "- (ID:${g.id}) [${g.level.uppercase()}] ${g.title} | 分类: ${g.category} | " +
                    "进度: ${g.progress}% | 衡量标准: ${g.metric ?: "无"} | 截止: ${g.dueDate ?: "长期"}"
            }
        } else {
            lines += "- 暂无进行中的具体目标"
        }

        // 最近 7 天记录
        lines += "【最近7天真实生活记录 (最近${recentLogs.size}天)】"
        if (recentLogs.isNotEmpty()) {
            for (log in recentLogs) {
                val mealsStr = log.meals.entries
                    .filter { it.value.isNotBlank() }
                    .joinToString(", ") { "${it.key}:${it.value}" }
                lines += "- 日期: ${log.date} | 学习: ${log.studyMinutes}分钟(${log.studyNote ?: "无"}) | " +
                    "睡眠: ${log.sleepStart ?: "?"}-${log.sleepEnd ?: "?"} | " +
                    "运动: ${log.exerciseType ?: "无"}(${log.exerciseMinutes}min) | " +
                    "心情/精力: ${log.mood}/5分, ${log.energy}/5分 | " +
                    "产出: ${log.doneItems ?: "无"} | 饮食: [$mealsStr]"
            }
        } else {
            lines += "- 近7天尚无日常记录"
        }

        return lines.joinToString("\n")
    }

    /** 决策顾问提示词，照抄 v1 ask_advisor 的用户提示词。 */
    fun advisorPrompt(context: String, question: String): String = """
$context

=== 用户提问 ===
用户向你咨询一个行动或生活决策：
"$question"

=== 回答规范要求 ===
只能依据上面上下文中真实出现的数据作答；上下文没有的信息就明确说“没有记录”，不要编造。
你必须严格按照以下 JSON 格式输出，不要附加任何其他无用字符：
{
  "conclusion": "建议做 或 不建议做 或 可以但有条件 (必须是这三者之一)",
  "reason": "深入结合用户的目标、近期睡眠饮食状态及健康约束，详细阐述为什么得出该结论",
  "pros": "做了之后能带来什么切实好处或身心价值",
  "cons_risks": "做了会有什么后果与潜在代价，区分短期消耗与长期影响",
  "alternative": "如果用户决定要做，给出怎样的折中优化策略、缓冲防线或最佳实践",
  "goal_relation": "清晰指出这一行动会使用户离哪个目标更近、亦或离哪个目标更远，甚至产生偏离"
}
"""

    /** 每日规划提示词，照抄 v1 generate_daily_plan 的用户提示词。 */
    fun dailyPlanPrompt(context: String): String = """
$context

=== 任务要求 ===
今天是新的一天，请你为用户编排今日专属成长日程：
1. 梳理今日 3-5 个最重要的核心任务 (Top Tasks)。每个任务必须具体、可执行，注明服务于哪个进行中的目标、预估耗时 (分钟)。
2. 今日饮食与作息关键提醒 (结合健康状况与昨晚睡眠)。
3. 基于用户最近几天的真实表现，给出一句真诚切中要害的鼓励或警醒 (不要空话鸡汤)。

只能依据上下文中真实出现的数据；没有记录就直说没有，不要编造。
goal_id 只能填上面“正在推进的重点目标”里 (ID:n) 的数字 n；任务不服务于任何列出的目标时填 null。

请严格输出为以下 JSON 格式：
{
  "tasks": [
    {
      "title": "任务名称，具体明确",
      "goal_id": 目标ID数字或null,
      "goal_name": "服务的目标名称",
      "est_minutes": 45
    }
  ],
  "health_reminder": "今日作息与饮食指导提醒",
  "tip": "基于真实表现的鼓励或警醒"
}
"""

    /** 每日复盘提示词，照抄 v1 generate_review（daily 分支）的用户提示词。 */
    fun dailyReviewPrompt(
        context: String,
        date: LocalDate,
        doneCount: Int,
        totalTasks: Int,
        log: DailyLog?,
    ): String {
        val mealsJson = buildJsonObject {
            (log?.meals ?: emptyMap()).forEach { (k, v) ->
                if (v.isNotBlank()) put(k, kotlinx.serialization.json.JsonPrimitive(v))
            }
        }.toString()
        return """
$context

=== 每日复盘数据 ===
- 复盘日期: $date
- 当日计划任务完成情况: $doneCount/${totalTasks}项已完成
- 当日生活记录:
  - 产出事项: ${log?.doneItems ?: "无记录"}
  - 学习时长: ${log?.studyMinutes ?: 0}分钟 (笔记: ${log?.studyNote ?: "无"})
  - 饮食: $mealsJson
  - 睡眠: ${log?.sleepStart ?: "?"}-${log?.sleepEnd ?: "?"}
  - 运动: ${log?.exerciseType ?: "无"}(${log?.exerciseMinutes ?: 0}min)
  - 心情/精力: ${log?.mood ?: "?"}/5分, ${log?.energy ?: "?"}/5分
  - 个人反思: ${log?.reflection ?: "无"}

只能引用上面真实出现的数据；某项显示“无记录”就如实说没有记录，不要编造。
请严格按照以下 JSON 格式输出今日深度复盘：
{
  "summary": "当日表现总体定性评估与能量评价",
  "highlights": "做得很棒的亮点与关键交付（具体引用数据）",
  "deviations": "计划偏离、拖延或未达标之处与根本归因",
  "next_actions": "针对明天的2-3个具体战术修正建议"
}
"""
    }

    /** 每周复盘提示词，照抄 v1 generate_review（weekly 分支）的用户提示词；要求 progress_suggestions。 */
    fun weeklyReviewPrompt(
        context: String,
        totalStudy: Int,
        avgStudyText: String,
        goalCount: Int,
        logDays: Int,
        goalLines: String,
    ): String = """
$context

=== 周度复盘数据 ===
- 周期: 最近7天
- 累计学习投入: ${totalStudy}分钟 (日均 $avgStudyText 分钟)
- 进行中目标数: $goalCount
- 记录留存天数: ${logDays}天
- 进行中目标清单（id / 标题 / 当前进度）:
$goalLines

只能引用上面真实出现的数据，不要编造。
progress_suggestions 的规则：只对“有明确依据”的目标给建议——依据必须是上面最近7天记录里真实出现的行动与数值；
goal_id 只能取自上面清单；suggested_progress 为 0-100 的整数；reason 里要引用具体的真实记录。
没有足够依据的目标不要给建议，没有任何依据时返回空数组 []，不要为了凑数而给出建议。
请严格输出为以下 JSON 格式的周度战略复盘：
{
  "summary": "本周成长全景总结与阶段位移评估",
  "goal_progress": "各重点目标的推进动态与进度变化分析",
  "biggest_deviation": "本周最大的认知、精力或执行偏差",
  "next_week_strategy": "下周的核心攻坚策略与行动防线",
  "progress_suggestions": [
    {"goal_id": 目标id数字, "suggested_progress": 0到100的整数, "reason": "引用真实记录的依据"}
  ]
}
"""

    /** 目标拆解提示词，照抄 v1 breakdown_goal 的用户提示词（要求 Markdown 与三级标题排版）。 */
    fun breakdownPrompt(context: String, goalTitle: String, goalDesc: String?): String = """
$context

=== 目标拆解需求 ===
用户希望将以下目标进行层级化拆解：
【目标名称】: $goalTitle
【补充描述】: ${goalDesc ?: "无"}

请为用户制定一份高度落地、层层推进的拆解方案，只依据上下文真实数据，不要编造用户没提供的信息：
1. 阶段里程碑 (1-3个月阶段性抓手)
2. 每周关键攻坚行动 (Weekly Milestones)
3. 关键检验标准 (Metric) 与避坑指南

请输出 Markdown，并严格遵守下面的排版，程序会据此解析：
- 每个阶段以单独一行的三级标题开头，形如 “### 阶段一：xxx (第 1-4 周)”；
- 阶段下用 “- **阶段里程碑**：…” 写里程碑，“- **检验标准**：…” 写衡量标准；
- 每周行动各占一行，形如 “  - 第 1 周：…”。
"""

    private val SYSTEM_PROMPT = """你是一个顶级私人人生规划师与决策编排智能体 (Life Planner AI)。
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
}
