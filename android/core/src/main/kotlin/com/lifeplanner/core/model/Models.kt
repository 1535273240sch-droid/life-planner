package com.lifeplanner.core.model

import kotlinx.datetime.LocalDate

/**
 * v2 领域模型（手机本地，单用户）。
 *
 * 与 v1 Python 版保持字段语义一致，便于把电脑上的数据通过 JSON 导入。
 * 日期一律用 kotlinx-datetime 的 LocalDate；入库/导出时转 ISO 字符串（YYYY-MM-DD）。
 */

// ---- 目标层级 / 状态 / 来源：用字符串常量，与 v1 的库取值完全一致 ----
object GoalLevels {
    const val VISION = "vision"
    const val LONG = "long"
    const val STAGE = "stage"
    const val WEEKLY = "weekly"

    /** 层级深度：愿景 0 → 长期 1 → 阶段 2 → 周 3。父级必须比子级"更靠前"。 */
    val RANK = mapOf(VISION to 0, LONG to 1, STAGE to 2, WEEKLY to 3)

    fun rankOf(level: String?): Int? = RANK[level]

    /** 合法父级层级：比 current 更靠前的层级。返回 null 表示不限制（未知层级时不做限制）。 */
    fun allowedParentLevels(current: String?): Set<String>? {
        val cur = rankOf(current) ?: return null
        return RANK.filterValues { it < cur }.keys
    }
}

object GoalStatuses {
    const val IN_PROGRESS = "in_progress"
    const val COMPLETED = "completed"
    const val PAUSED = "paused"
}

object AiSources {
    const val AI_MODEL = "ai_model"
    const val CRISIS = "crisis_safety_guard"
}

data class Profile(
    val id: Long? = null,
    val age: Int? = null,
    val gender: String? = null,
    val occupation: String? = null,
    val industry: String? = null,
    val yearsOfWork: Int? = null,
    val education: String? = null,
    val major: String? = null,
    /** 健康底座：height/weight/sleep_habit/diet_pref/taboo/allergy_or_disease */
    val healthInfo: Map<String, String> = emptyMap(),
    val vision: String? = null,
    val constraints: String? = null,
    val updatedAt: String? = null,
)

data class Skill(
    val id: Long? = null,
    val name: String,
    val category: String = "通用",
    val level: Int = 5,
    val note: String? = null,
)

data class Goal(
    val id: Long? = null,
    val parentId: Long? = null,
    val level: String = GoalLevels.LONG,
    val title: String,
    val category: String = "职业",
    val description: String? = null,
    val metric: String? = null,
    val startDate: LocalDate? = null,
    val dueDate: LocalDate? = null,
    val progress: Int = 0,
    val status: String = GoalStatuses.IN_PROGRESS,
    /** 非空即已软删除；读目标默认排除 */
    val deletedAt: String? = null,
)

/** 目标树节点（用于界面展示） */
data class GoalNode(
    val goal: Goal,
    val children: List<GoalNode> = emptyList(),
)

data class Task(
    val id: Long? = null,
    val goalId: Long? = null,
    val date: LocalDate,
    val title: String,
    val estMinutes: Int = 30,
    val done: Boolean = false,
    val doneAt: String? = null,
)

data class DailyLog(
    val id: Long? = null,
    val date: LocalDate,
    val doneItems: String? = null,
    val studyMinutes: Int = 0,
    val studyNote: String? = null,
    /** breakfast / lunch / dinner / snack */
    val meals: Map<String, String> = emptyMap(),
    val sleepStart: String? = null,
    val sleepEnd: String? = null,
    val exerciseType: String? = null,
    val exerciseMinutes: Int = 0,
    val mood: Int = 3,
    val energy: Int = 3,
    val reflection: String? = null,
)

data class AiMessage(
    val id: Long? = null,
    /** daily_plan / advisor / review / breakdown */
    val mode: String,
    val question: String? = null,
    val answer: String,
    val source: String = AiSources.AI_MODEL,
    val modelName: String? = null,
    val createdAt: String,
)

// ---- 缺口提醒（D4）与统计结果，供界面直接渲染 ----

data class LifeGap(
    val category: String,
    val level: String,   // 预警 / 提醒 / 注意
    val title: String,
    val detail: String,
)

data class StatsOverview(
    val streak: Int,
    val totalGoals: Int,
    val completedGoals: Int,
    val inProgressGoals: Int,
)

/** 近 N 天趋势；无记录的日期用 null 表示"断线"，不得补 0 */
data class StatsTrends(
    val labels: List<String>,
    val studyMinutes: List<Int?>,
    val sleepHours: List<Double?>,
    val mood: List<Int?>,
    val energy: List<Int?>,
)
