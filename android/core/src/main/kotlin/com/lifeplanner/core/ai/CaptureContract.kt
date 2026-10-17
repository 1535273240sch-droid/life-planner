package com.lifeplanner.core.ai

import com.lifeplanner.core.model.GoalLevels

/**
 * “AI 一句话整理”的共享数据契约（界面层与 AI 层都依赖）。
 *
 * 用户只在首页弹窗里说一句话（今天干了什么 / 明天想做什么 / 随便问问），
 * AI 负责把它整理成结构化草稿：当日生活记录、今日任务、想新增的目标。
 * 所有草稿**在用户确认前绝不落库**；null / 空列表表示“这次没有整理出该项”。
 */

/** 当日生活记录草稿。字段为 null 表示模型没有提到，落库时应保留数据库里的原值。 */
data class CaptureLogDraft(
    val studyMinutes: Int? = null,
    val studyNote: String? = null,
    val sleepStart: String? = null,
    val sleepEnd: String? = null,
    val exerciseType: String? = null,
    val exerciseMinutes: Int? = null,
    val mood: Int? = null,
    val energy: Int? = null,
    val meals: Map<String, String> = emptyMap(),
    val doneItems: String? = null,
    val reflection: String? = null,
) {
    val isEmpty: Boolean
        get() = studyMinutes == null && studyNote.isNullOrBlank() && sleepStart.isNullOrBlank() &&
            sleepEnd.isNullOrBlank() && exerciseType.isNullOrBlank() && exerciseMinutes == null &&
            mood == null && energy == null && meals.isEmpty() && doneItems.isNullOrBlank() &&
            reflection.isNullOrBlank()
}

/** 今日任务草稿。goalId 只会是上下文中真实存在的进行中目标，否则为 null。 */
data class CaptureTaskDraft(
    val title: String,
    val estMinutes: Int = 30,
    val goalId: Long? = null,
    val goalName: String = "",
)

/** 新目标草稿。层级默认周目标，分类限定在模型建议的常见分类内。 */
data class CaptureGoalDraft(
    val title: String,
    val level: String = GoalLevels.WEEKLY,
    val category: String = "生活",
    val description: String? = null,
    val metric: String? = null,
)

/**
 * “一句话整理”的完整结果。
 *
 * @property reply AI 对用户这句话的直接回应（建议 / 提醒 / 鼓励），可直接展示
 * @property log 整理出的当日记录，可能为 null
 * @property tasks 整理出的今日任务，可为空
 * @property goals 整理出的新目标，可为空
 * @property fullText 写入历史记录的可读全文
 */
data class CaptureResult(
    val reply: String,
    val log: CaptureLogDraft?,
    val tasks: List<CaptureTaskDraft> = emptyList(),
    val goals: List<CaptureGoalDraft> = emptyList(),
    val isCrisis: Boolean = false,
    val source: String,
    val modelName: String?,
    val fullText: String,
    val crisis: CrisisPayload? = null,
) {
    /** 是否整理出了至少一项可以落库的内容。 */
    val hasDrafts: Boolean
        get() = log?.isEmpty == false || tasks.isNotEmpty() || goals.isNotEmpty()
}

/** 落库统计：用于给用户一句可核对的结果说明。 */
data class CaptureApplied(
    val tasksInserted: Int = 0,
    val goalsInserted: Int = 0,
    val logSaved: Boolean = false,
) {
    val isEmpty: Boolean get() = tasksInserted == 0 && goalsInserted == 0 && !logSaved
}
