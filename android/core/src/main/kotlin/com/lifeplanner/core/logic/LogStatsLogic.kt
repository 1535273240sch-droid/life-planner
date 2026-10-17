package com.lifeplanner.core.logic

import com.lifeplanner.core.model.DailyLog
import com.lifeplanner.core.model.Goal
import com.lifeplanner.core.model.GoalStatuses
import com.lifeplanner.core.model.StatsOverview
import com.lifeplanner.core.model.StatsTrends
import com.lifeplanner.core.util.Dates
import kotlinx.datetime.LocalDate
import kotlin.math.round

/**
 * 每日记录的纯统计逻辑（睡眠解析 / 连续打卡 / 总览 / 趋势）。
 * 与 v1 Python 版 services/stats_service.py、services/log_service.py 语义一致。
 */
object LogStatsLogic {

    /**
     * 计算睡眠时长（小时），跨天按夜间处理。
     * 输入为 HH:mm；任一为空、缺冒号、非数字、时>23 或 分>59 时返回 null，绝不伪造默认值。
     */
    fun parseSleepHours(start: String?, end: String?): Double? {
        val startMin = parseClockMinutes(start) ?: return null
        val endMin = parseClockMinutes(end) ?: return null
        val totalMin = if (endMin < startMin) (1440 - startMin) + endMin else endMin - startMin
        return round(totalMin / 60.0 * 10) / 10.0
    }

    /** 把 HH:mm 解析成当天分钟数；非法返回 null。 */
    private fun parseClockMinutes(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        if (!trimmed.contains(":")) return null
        val parts = trimmed.split(":")
        if (parts.size < 2) return null
        val h = parts[0].trim()
        val m = parts[1].trim()
        if (h.isEmpty() || m.isEmpty()) return null
        if (!h.all { it.isDigit() } || !m.all { it.isDigit() }) return null
        val hh = h.toIntOrNull() ?: return null
        val mm = m.toIntOrNull() ?: return null
        if (hh > 23 || mm > 59) return null
        return hh * 60 + mm
    }

    /**
     * 连续记录天数：今天有记录就从今天起算，今天没有就从昨天起算，往前逐日连续计数。
     */
    fun calculateStreak(dates: Set<LocalDate>, today: LocalDate): Int {
        if (dates.isEmpty()) return 0
        var curr = if (today in dates) today else Dates.minusDays(today, 1)
        var streak = 0
        while (curr in dates) {
            streak++
            curr = Dates.minusDays(curr, 1)
        }
        return streak
    }

    /** 目标总览：传入的目标应已排除已删除。 */
    fun overview(goals: List<Goal>, streak: Int): StatsOverview {
        val completed = goals.count { it.status == GoalStatuses.COMPLETED }
        val inProgress = goals.count { it.status == GoalStatuses.IN_PROGRESS }
        return StatsOverview(
            streak = streak,
            totalGoals = goals.size,
            completedGoals = completed,
            inProgressGoals = inProgress,
        )
    }

    /**
     * 近 days 天趋势（升序，含今天）。labels 用 MM-DD；
     * 没有记录的日期：studyMinutes 为 0，sleepHours/mood/energy 为 null（断线，不补 0）。
     */
    fun trends(logs: List<DailyLog>, days: Int, today: LocalDate): StatsTrends {
        if (days <= 0) {
            return StatsTrends(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
        val byDate = logs.associateBy { it.date }
        val labels = ArrayList<String>(days)
        val study = ArrayList<Int?>(days)
        val sleep = ArrayList<Double?>(days)
        val mood = ArrayList<Int?>(days)
        val energy = ArrayList<Int?>(days)

        for (date in Dates.lastDays(days, today)) {
            labels.add(Dates.monthDay(date))
            val log = byDate[date]
            if (log == null) {
                study.add(0)
                sleep.add(null)
                mood.add(null)
                energy.add(null)
            } else {
                study.add(log.studyMinutes)
                sleep.add(parseSleepHours(log.sleepStart, log.sleepEnd))
                mood.add(log.mood)
                energy.add(log.energy)
            }
        }
        return StatsTrends(labels, study, sleep, mood, energy)
    }
}
