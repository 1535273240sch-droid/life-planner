package com.lifeplanner.core.logic

import com.lifeplanner.core.model.DailyLog
import com.lifeplanner.core.model.Goal
import com.lifeplanner.core.model.GoalStatuses
import com.lifeplanner.core.util.Dates
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogStatsLogicTest {

    // ---------------------------------------------------------------- 睡眠解析

    @Test
    fun `睡眠跨天 23点30到07点30 等于8小时`() {
        assertEquals(8.0, LogStatsLogic.parseSleepHours("23:30", "07:30")!!, 0.0001)
    }

    @Test
    fun `睡眠跨天 01点到07点 等于6小时`() {
        assertEquals(6.0, LogStatsLogic.parseSleepHours("01:00", "07:00")!!, 0.0001)
    }

    @Test
    fun `睡眠同日 13点到15点 等于2小时`() {
        assertEquals(2.0, LogStatsLogic.parseSleepHours("13:00", "15:00")!!, 0.0001)
    }

    @Test
    fun `睡眠 00点到08点 等于8小时`() {
        assertEquals(8.0, LogStatsLogic.parseSleepHours("00:00", "08:00")!!, 0.0001)
    }

    @Test
    fun `睡眠任一为空 返回null`() {
        assertNull(LogStatsLogic.parseSleepHours(null, "07:00"))
        assertNull(LogStatsLogic.parseSleepHours("23:00", null))
        assertNull(LogStatsLogic.parseSleepHours(null, null))
        assertNull(LogStatsLogic.parseSleepHours("", ""))
    }

    @Test
    fun `睡眠缺冒号 返回null`() {
        assertNull(LogStatsLogic.parseSleepHours("2300", "07:00"))
        assertNull(LogStatsLogic.parseSleepHours("invalid", "none"))
    }

    @Test
    fun `睡眠非数字 返回null`() {
        assertNull(LogStatsLogic.parseSleepHours("ab:30", "07:00"))
        assertNull(LogStatsLogic.parseSleepHours("23:xx", "07:00"))
    }

    @Test
    fun `睡眠小时越界 返回null`() {
        assertNull(LogStatsLogic.parseSleepHours("24:00", "07:00"))
        assertNull(LogStatsLogic.parseSleepHours("99:00", "07:00"))
    }

    @Test
    fun `睡眠分钟越界 返回null`() {
        assertNull(LogStatsLogic.parseSleepHours("23:60", "07:00"))
    }

    // ---------------------------------------------------------------- 连续打卡

    @Test
    fun `连续打卡 今天有记录 从今天起算`() {
        val today = LocalDate(2026, 3, 15)
        val dates = setOf(today, Dates.minusDays(today, 1), Dates.minusDays(today, 2))
        assertEquals(3, LogStatsLogic.calculateStreak(dates, today))
    }

    @Test
    fun `连续打卡 今天无记录但昨天有 从昨天起算`() {
        val today = LocalDate(2026, 3, 15)
        val dates = setOf(Dates.minusDays(today, 1), Dates.minusDays(today, 2))
        assertEquals(2, LogStatsLogic.calculateStreak(dates, today))
    }

    @Test
    fun `连续打卡 中间断档 只算到断点`() {
        val today = LocalDate(2026, 3, 15)
        val dates = setOf(today, Dates.minusDays(today, 2), Dates.minusDays(today, 3))
        assertEquals(1, LogStatsLogic.calculateStreak(dates, today))
    }

    @Test
    fun `连续打卡 今天与昨天都无记录 返回0`() {
        val today = LocalDate(2026, 3, 15)
        val dates = setOf(Dates.minusDays(today, 2), Dates.minusDays(today, 3))
        assertEquals(0, LogStatsLogic.calculateStreak(dates, today))
    }

    @Test
    fun `连续打卡 无任何记录 返回0`() {
        assertEquals(0, LogStatsLogic.calculateStreak(emptySet(), LocalDate(2026, 3, 15)))
    }

    // ---------------------------------------------------------------- 总览

    @Test
    fun `总览 统计目标总数完成数与进行中数`() {
        val goals = listOf(
            Goal(id = 1, title = "A", status = GoalStatuses.IN_PROGRESS),
            Goal(id = 2, title = "B", status = GoalStatuses.COMPLETED),
            Goal(id = 3, title = "C", status = GoalStatuses.IN_PROGRESS),
            Goal(id = 4, title = "D", status = GoalStatuses.PAUSED),
        )
        val overview = LogStatsLogic.overview(goals, streak = 5)
        assertEquals(5, overview.streak)
        assertEquals(4, overview.totalGoals)
        assertEquals(1, overview.completedGoals)
        assertEquals(2, overview.inProgressGoals)
    }

    // ---------------------------------------------------------------- 趋势

    @Test
    fun `趋势 无记录日期 study为0 睡眠心情能量为null断线`() {
        val today = LocalDate(2026, 3, 15)
        val trends = LogStatsLogic.trends(emptyList(), days = 7, today = today)

        assertEquals(7, trends.labels.size)
        assertEquals("03-15", trends.labels.last())
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0), trends.studyMinutes)
        assertEquals(List<Double?>(7) { null }, trends.sleepHours)
        assertEquals(List<Int?>(7) { null }, trends.mood)
        assertEquals(List<Int?>(7) { null }, trends.energy)
    }

    @Test
    fun `趋势 有记录日期取真实值 标签为MMDD`() {
        val today = LocalDate(2026, 3, 15)
        val log = DailyLog(
            date = Dates.minusDays(today, 1),
            studyMinutes = 77,
            sleepStart = "23:00",
            sleepEnd = "07:00",
            mood = 4,
            energy = 2,
        )
        val trends = LogStatsLogic.trends(listOf(log), days = 7, today = today)

        assertEquals("03-14", trends.labels[5])
        assertEquals(0, trends.studyMinutes[0])
        assertEquals(77, trends.studyMinutes[5])
        assertEquals(0, trends.studyMinutes[6])
        assertNull(trends.sleepHours[0])
        assertEquals(8.0, trends.sleepHours[5]!!, 0.0001)
        assertEquals(4, trends.mood[5])
        assertEquals(2, trends.energy[5])
        assertNull(trends.mood[6])
    }

    @Test
    fun `趋势 有记录但睡眠非法时 睡眠断线而其余非空`() {
        val today = LocalDate(2026, 3, 15)
        val log = DailyLog(date = today, studyMinutes = 30, sleepStart = "bad", sleepEnd = "07:00", mood = 3, energy = 3)
        val trends = LogStatsLogic.trends(listOf(log), days = 3, today = today)

        assertNull(trends.sleepHours[2])
        assertEquals(30, trends.studyMinutes[2])
        assertEquals(3, trends.mood[2])
    }
}
