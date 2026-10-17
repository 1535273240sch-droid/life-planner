package com.lifeplanner.core.ai

/**
 * 「AI 建档访谈」的共享契约（界面层 / AI 层 / 测试共用）。
 *
 * 为什么单独定义：访谈是"多轮提问 → 逐轮补全档案 → 最后确认落库"的流程，
 * 界面需要在不理解提示词的前提下渲染进度与草稿，所以字段与回合结构必须显式建模。
 *
 * 所有字段为 null 表示"还没问到"，绝不代表用户说"没有"。
 */

/** 访谈过程中已了解到的信息。 */
data class InterviewFields(
    val age: Int? = null,
    val gender: String? = null,
    val occupation: String? = null,
    val industry: String? = null,
    val yearsOfWork: Int? = null,
    val education: String? = null,
    val major: String? = null,
    /** 收入水平（自由文本：月薪 1.2 万 / 年薪 20 万 / 不稳定） */
    val income: String? = null,
    /** 现在的生活状态与困扰 */
    val currentState: String? = null,
    /** 想达到的状态 */
    val desiredState: String? = null,
    /** 想通过什么方式达到 */
    val approach: String? = null,
    /** 未来想成为什么样的人 */
    val futureSelf: String? = null,
    /** 现实约束（时间、家庭、健康、经济） */
    val constraints: String? = null,
    /** AI 归纳的一句话愿景 */
    val vision: String? = null,
) {
    /** 核心话题的完成度，用于界面进度（0..CORE_TOPICS）。 */
    val coreCovered: Int
        get() = listOf(
            age,
            occupation,
            income,
            currentState,
            desiredState,
            approach,
            futureSelf,
        ).count { it != null && it.toString().isNotBlank() }

    companion object {
        const val CORE_TOPICS = 7
    }
}

/** 一轮访谈：AI 的回应 + 下一个问题 + 更新后的已知信息。 */
data class InterviewTurn(
    /** 对用户上一句的回应（首轮是开场引导），可为空 */
    val reply: String,
    /** 下一个问题；null 表示本轮之后访谈结束 */
    val question: String?,
    val fields: InterviewFields,
    val finished: Boolean,
    val isCrisis: Boolean = false,
    val crisis: CrisisPayload? = null,
    val source: String,
    val modelName: String?,
    /** 写入历史记录的可读全文 */
    val fullText: String,
    /**
     * 访谈结束时的结构化草稿（愿景 / 约束 / 建议目标），仅当 [finished] 为 true 时非空。
     * 为什么放在回合里：界面在"结束这一轮"就要立刻渲染确认卡，
     * 单独再发一次请求既慢又可能让模型给出与刚才不一致的结论。
     */
    val draft: InterviewDraft? = null,
)

/** 访谈建议的目标（用户确认后才落库）。 */
data class InterviewGoalDraft(
    val title: String,
    val level: String = "long",
    val category: String = "职业",
    val metric: String? = null,
    val description: String? = null,
)

/** 访谈结束时给用户确认的草稿。 */
data class InterviewDraft(
    val fields: InterviewFields,
    val vision: String,
    val constraints: String,
    val goals: List<InterviewGoalDraft>,
    /** AI 对这个人整体的一段总结（展示用） */
    val summary: String,
)

/** 落库结果。 */
data class InterviewApplied(
    val profileSaved: Boolean = false,
    val goalsInserted: Int = 0,
)

/** 访谈消息（界面与提示词共用；fromUser=true 表示用户发言）。 */
data class InterviewMessage(
    val fromUser: Boolean,
    val text: String,
)
