package com.lifeplanner.core.logic

import com.lifeplanner.core.model.DailyLog
import com.lifeplanner.core.model.GoalStatuses
import com.lifeplanner.core.model.LifeGap
import com.lifeplanner.core.model.StatsOverview
import com.lifeplanner.core.model.StatsTrends
import com.lifeplanner.core.port.DailyLogRepository
import com.lifeplanner.core.port.GoalRepository
import com.lifeplanner.core.port.TaskRepository
import com.lifeplanner.core.util.Dates
import kotlinx.datetime.LocalDate

/**
 * 每日记录编排：把仓储数据取出来，交给纯逻辑计算。
 * 只做取数与组装，业务规则在 LogStatsLogic / GapDetector。
 */
class LogService(
    private val dailyLogs: DailyLogRepository,
    private val goals: GoalRepository,
    private val tasks: TaskRepository,
) {

    /** 按日期 upsert（一天一条）。 */
    fun upsertTodayOrDate(log: DailyLog): DailyLog = dailyLogs.upsert(log)

    /** 近 days 天日志（含今天），按日期倒序。 */
    fun recentLogs(days: Int, today: LocalDate): List<DailyLog> {
        if (days <= 0) return emptyList()
        val from = Dates.minusDays(today, days - 1)
        return dailyLogs.listBetween(from, today).sortedByDescending { it.date }
    }

    /** 总览：目标计数（排除已删除）+ 连续打卡天数。 */
    fun overview(today: LocalDate): StatsOverview {
        val dates = recentLogs(STREAK_LOOKBACK_DAYS, today).map { it.date }.toSet()
        val streak = LogStatsLogic.calculateStreak(dates, today)
        return LogStatsLogic.overview(goals.list(), streak)
    }

    /** 近 days 天趋势。 */
    fun trends(days: Int, today: LocalDate): StatsTrends =
        LogStatsLogic.trends(recentLogs(days, today), days, today)

    /** 缺口检测：近 14 天日志 + 进行中目标 + 近 7 天已完成任务数。 */
    fun detectGaps(today: LocalDate): List<LifeGap> {
        val logs = recentLogs(GAP_WINDOW_DAYS, today)
        val ongoing = goals.list().filter { it.status == GoalStatuses.IN_PROGRESS }
        val since = Dates.minusDays(today, 7)
        val doneTaskCounts = ongoing
            .mapNotNull { it.id }
            .associateWith { tasks.countDoneSince(it, since) }
        return GapDetector.detect(logs, ongoing, doneTaskCounts)
    }

    private companion object {
        /** 连续打卡回溯上限（约 10 年，足够覆盖单用户历史）。 */
        const val STREAK_LOOKBACK_DAYS = 3650

        /** 缺口检测取数窗口。 */
        const val GAP_WINDOW_DAYS = 14
    }
}
