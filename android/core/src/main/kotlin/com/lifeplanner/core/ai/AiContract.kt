package com.lifeplanner.core.ai

/**
 * AI 层的共享契约：配置、错误码、各模式的返回结构。
 * 界面层直接依赖这里的类型；实现放在本 package 的其他文件（AiService 等）。
 */

data class AiConfig(
    val apiKey: String = "",
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()
}

/** 错误码：与 v1 的 HTTP 语义一一对应，界面对每个码给不同的中文指引 */
object AiErrorCodes {
    const val NOT_CONFIGURED = "ai_not_configured"   // 未配置 Key
    const val UNREACHABLE = "ai_unreachable"         // 连不上/超时
    const val AUTH_FAILED = "ai_auth_failed"         // 401/403
    const val RATE_LIMITED = "ai_rate_limited"       // 429
    const val BAD_RESPONSE = "ai_bad_response"       // 无法解析/缺字段
}

/** AI 调用失败。message 必须是给用户看的中文说明，不得包含 Key 或用户数据。 */
class AiException(
    val code: String,
    override val message: String,
) : Exception(message)

/** 危机关怀内容（本地命中关键词时短路返回，不调用模型） */
data class CrisisPayload(
    val conclusion: String,
    val reason: String,
    val pros: String,
    val consRisks: String,
    val alternative: String,
    val goalRelation: String,
)

data class AdvisorResult(
    val conclusion: String,
    val reason: String,
    val pros: String,
    val consRisks: String,
    val alternative: String,
    val goalRelation: String,
    val isCrisis: Boolean,
    val source: String,
    val modelName: String?,
    val fullText: String,
    val crisis: CrisisPayload? = null,
)

data class PlannedTask(
    val title: String,
    val goalId: Long?,
    val goalName: String,
    val estMinutes: Int,
)

data class DailyPlanResult(
    val tasks: List<PlannedTask>,
    val healthReminder: String,
    val tip: String,
    val isCrisis: Boolean,
    val source: String,
    val modelName: String?,
    val crisis: CrisisPayload? = null,
)

data class ProgressSuggestion(
    val goalId: Long,
    val title: String,
    val currentProgress: Int,
    val suggestedProgress: Int,
    val reason: String,
)

/** 每日复盘与每周复盘共用一个结构：不适用的一方字段为 null */
data class ReviewResult(
    val type: String,                    // daily / weekly
    val title: String,
    val summary: String,
    val highlights: String? = null,
    val deviations: String? = null,
    val nextActions: String? = null,
    val goalProgress: String? = null,
    val biggestDeviation: String? = null,
    val nextWeekStrategy: String? = null,
    val progressSuggestions: List<ProgressSuggestion> = emptyList(),
    val isCrisis: Boolean,
    val source: String,
    val modelName: String?,
    val fullText: String,
    val crisis: CrisisPayload? = null,
)

data class BreakdownWeekly(
    val title: String,
    val description: String? = null,
)

data class BreakdownStage(
    val title: String,
    val description: String? = null,
    val metric: String? = null,
    val weeklyGoals: List<BreakdownWeekly> = emptyList(),
)

data class BreakdownResult(
    val plan: String,                    // 原始 Markdown，用于展示
    val stages: List<BreakdownStage>,    // 解析后的阶段，用于确认后落库
    val goalTitle: String,
    val source: String,
    val modelName: String?,
)
