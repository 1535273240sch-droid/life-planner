package com.lifeplanner.core.ai

import kotlinx.datetime.LocalDate

/**
 * 「AI 建档访谈」的提示词。
 *
 * 设计取舍：
 * - 访谈是对话，不是表单。提示词把“先回应、再只问一个问题、问题要短”放在最前面，
 *   避免模型一次抛出一串问题把用户吓跑。
 * - 结束条件写成硬约束（核心四问至少三项 + 最多 10 轮），但真正的裁剪与兜底在
 *   AiService.interviewTurn 里做：提示词负责“尽量按约定说话”，代码负责“绝不信任”。
 * - 话题顺序是建议而非脚本：允许模型顺着用户的回答调整，只要最终能覆盖核心信息。
 * - 数量上限与分类白名单在此定义，AiService 复用同一份常量，避免提示词与校验漂移。
 */
object InterviewPrompts {

    /** 单次访谈最多轮数（用户发言次数）。 */
    const val MAX_TURNS = 10

    /** 提示词里携带的历史消息上限：太长既费 token 也没必要。 */
    const val HISTORY_LIMIT = 12

    /** 访谈结束时最多接受的目标数。 */
    const val MAX_GOALS = 3

    /** 目标分类白名单与“一句话整理”共用同一份，避免两处口径不一致。 */
    val GOAL_CATEGORIES: List<String> = CapturePrompts.GOAL_CATEGORIES

    /**
     * 访谈提示词。
     *
     * @param fields 已经了解到的信息（模型不得重复提问，也不得用 null 覆盖）
     * @param history 完整对话（这里只取最近 [HISTORY_LIMIT] 条）
     * @param today 今天日期，供模型判断“最近/明年”这类相对时间
     */
    fun interviewPrompt(fields: InterviewFields, history: List<InterviewMessage>, today: LocalDate): String {
        val recent = history.takeLast(HISTORY_LIMIT)
        val turns = history.count { it.fromUser }
        val remaining = (MAX_TURNS - turns).coerceAtLeast(0)
        return """
你是一位温暖、克制、不评判的访谈者。你正在帮用户建立一份真实的人生档案，像真人聊天一样自然推进。

=== 今天日期 ===
$today

=== 访谈进度 ===
已经进行 $turns 轮（最多 $MAX_TURNS 轮），还可以再问 $remaining 轮。

=== 已经了解到的信息（不要重复问） ===
${knownFieldsText(fields)}

=== 对话记录（最近 ${recent.size} 条，按时间顺序） ===
${historyText(recent)}

=== 你的任务 ===
1. 先回应对方上一句（1 句，具体、不空洞、不评判），再问下一个问题。
2. 一次只问一个问题，问题要短（不超过 40 字）、具体、容易回答；不要一次罗列多个问题。
3. 按自然顺序推进话题，但可以顺着对方的回答调整：
   现在做什么工作/行业 → 年龄性别 → 收入水平 → 现在的生活状态与最困扰的事 →
   想达到什么状态 → 打算通过什么方式达到 → 未来想成为什么样的人 → 现实约束。
4. 已经了解到的信息不要重复问。对方说“不知道/不方便说/跳过”时就接受并换话题，不要逼问。
5. 只有收齐「做什么 / 收入 / 想达到的状态 / 未来想成为谁」这四项里的至少三项，才允许结束；
   已经进行到第 $MAX_TURNS 轮时必须结束。
6. 只整理对方明确说过的内容：没说的字段保持 null，绝对不许编造、不许根据上下文推测补全。
7. 结束（finished=true）时 question 必须是 null，并且在同一个 JSON 里额外输出
   profile / vision / constraints / summary / goals；没聊到的字段仍然保持 null。
8. goals 最多 $MAX_GOALS 个，level 只能是 vision / long / stage / weekly（不确定填 long），
   category 只能是 $GOAL_CATEGORY_TEXT 之一；metric 只写对方说过的衡量方式，没说就 null。
9. 输出严格 JSON，不要 Markdown 代码围栏，不要 JSON 之外的任何文字。

=== 每轮输出格式（严格 JSON） ===
{
  "reply": "对上一句的回应",
  "question": "下一个问题；finished=true 时必须是 null",
  "finished": false,
  "collected": {
    "age": null,
    "gender": null,
    "occupation": null,
    "industry": null,
    "years_of_work": null,
    "education": null,
    "major": null,
    "income": null,
    "current_state": null,
    "desired_state": null,
    "approach": null,
    "future_self": null,
    "constraints": null,
    "vision": null
  }
}

=== finished=true 时，在同一个 JSON 对象里再补充 ===
{
  "profile": {
    "age": 28,
    "gender": "男",
    "occupation": "产品经理",
    "industry": "互联网",
    "years_of_work": 6,
    "education": "本科",
    "major": "计算机"
  },
  "vision": "一句话愿景",
  "constraints": "现实约束",
  "summary": "对这个人整体的一段总结",
  "goals": [
    {"title": "目标标题", "level": "long", "category": "职业", "metric": "衡量标准"}
  ]
}
数值必须是 JSON 整数；字符串没内容就写 null，不要写空字符串占位。
""".trimIndent()
    }

    /** 已了解字段的可读清单：让模型知道哪些不能再问。 */
    private fun knownFieldsText(fields: InterviewFields): String {
        val lines = ArrayList<String>()
        fields.age?.let { lines += "- 年龄: $it" }
        fields.gender?.let { lines += "- 性别: $it" }
        fields.occupation?.let { lines += "- 职业: $it" }
        fields.industry?.let { lines += "- 行业: $it" }
        fields.yearsOfWork?.let { lines += "- 工作年限: $it 年" }
        fields.education?.let { lines += "- 学历: $it" }
        fields.major?.let { lines += "- 专业: $it" }
        fields.income?.let { lines += "- 收入: $it" }
        fields.currentState?.let { lines += "- 当前状态与困扰: $it" }
        fields.desiredState?.let { lines += "- 想达到的状态: $it" }
        fields.approach?.let { lines += "- 打算通过什么方式达到: $it" }
        fields.futureSelf?.let { lines += "- 未来想成为什么样的人: $it" }
        fields.constraints?.let { lines += "- 现实约束: $it" }
        fields.vision?.let { lines += "- 一句话愿景: $it" }
        return if (lines.isEmpty()) "- （还没有了解到任何信息，这是刚刚开始）" else lines.joinToString("\n")
    }

    /** 历史对话的可读文本；AI 发言统一标成“访谈者”，避免和用户混淆。 */
    private fun historyText(recent: List<InterviewMessage>): String {
        if (recent.isEmpty()) return "（还没有对话：这是访谈的第一轮，请先做一句自然的开场，并问出第一个问题）"
        return recent.joinToString("\n") {
            (if (it.fromUser) "用户: " else "访谈者: ") + it.text
        }
    }

    /** 分类白名单在提示词里的展示文案；单独抽出来避免字符串模板里写复杂表达式。 */
    private val GOAL_CATEGORY_TEXT: String = GOAL_CATEGORIES.joinToString("、")
}
