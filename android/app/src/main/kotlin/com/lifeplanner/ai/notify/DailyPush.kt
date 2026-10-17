package com.lifeplanner.ai.notify

import com.lifeplanner.core.daily.DailyQuotes
import com.lifeplanner.core.daily.PushKind
import kotlinx.datetime.LocalDate

/** 一条推送的标题与正文。 */
data class PushContent(val title: String, val body: String)

/**
 * 推送文案生成：纯函数，不引用任何 Android 类型。
 *
 * 为什么单独抽出来：
 * - 推送必须离线可用（用户可能没配 AI Key，或当时断网），所以内容只来自 core 的
 *   [DailyQuotes]：同一天恒定、跨天自动换一组，不依赖网络与模型；
 * - 纯函数才能放进 JVM 单元测试，不必起模拟器验证文案。
 */
object DailyPush {

    /**
     * 早间：问候 + 每日一句 + 生活/学习/工作三条可执行提醒。
     * 问候固定按 8 点取词（早间时段），保证同一天多次调用结果一致。
     */
    fun forKind(kind: PushKind, date: LocalDate): PushContent {
        val quote = DailyQuotes.forDate(date)
        val label = DailyQuotes.dateLabel(date)
        return when (kind) {
            PushKind.MORNING -> PushContent(
                title = DailyQuotes.greeting(8) + " · " + label,
                body = buildString {
                    append("「").append(quote.quote).append("」—— ").append(quote.author)
                    append("\n\n今天想请你照顾好三件事：")
                    append("\n· 生活：").append(quote.lifeTip)
                    append("\n· 学习：").append(quote.studyTip)
                    append("\n· 工作：").append(quote.workTip)
                },
            )

            PushKind.EVENING -> PushContent(
                title = "晚间复盘 · " + label,
                body = buildString {
                    append("花 3 分钟回顾今天：做成了什么？卡在哪里？明天的第一步是什么？")
                    append("\n\n「").append(quote.quote).append("」—— ").append(quote.author)
                    append("\n").append(encouragement(date))
                },
            )
        }
    }

    /** 通知点击后要打开的页面路由；与 LifePlannerRoot 的 NavTarget.route 一致。 */
    fun routeOf(kind: PushKind): String = when (kind) {
        PushKind.MORNING -> PushNavigation.ROUTE_DASHBOARD
        PushKind.EVENING -> PushNavigation.ROUTE_REVIEW
    }

    /** 晚间鼓励语按日期轮换，避免每天同一句让人觉得是群发。 */
    private fun encouragement(date: LocalDate): String =
        ENCOURAGEMENTS[Math.floorMod(date.toEpochDays(), ENCOURAGEMENTS.size)]

    private val ENCOURAGEMENTS: List<String> = listOf(
        "今天已经过去了，明天只需要先迈出第一步。",
        "睡个好觉，比多熬一小时更能推进明天。",
        "不必把每条待办都清空，重要的是那条最关键的。",
        "记录本身就有力量：写下来，就不会一直在脑子里打转。",
        "慢一点没关系，方向对就行。",
        "把手机放远一点，今晚对自己好一点。",
        "明天先把最难的那件事做掉，剩下的会轻很多。",
        "今天没做到的，是明天的起点，不是失败的证据。",
        "给自己留一点空白，那不是浪费。",
        "早点休息，是明天效率的第一笔投资。",
    )
}
