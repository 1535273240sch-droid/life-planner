package com.lifeplanner.core.logic

import com.lifeplanner.core.model.DailyLog
import com.lifeplanner.core.model.Goal
import com.lifeplanner.core.model.GoalStatuses
import com.lifeplanner.core.model.Task
import com.lifeplanner.core.port.DailyLogRepository
import com.lifeplanner.core.port.GoalRepository
import com.lifeplanner.core.port.TaskRepository
import com.lifeplanner.core.util.Dates
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogServiceTest {

    private val today = LocalDate(2026, 6, 15)

    // ---------------- 极简假仓储 ----------------

    private class FakeDailyLogRepository : DailyLogRepository {
        private val store = LinkedHashMap<LocalDate, DailyLog>()
        private var nextId = 1L

        override fun findByDate(date: LocalDate): DailyLog? = store[date]

        override fun listBetween(from: LocalDate, to: LocalDate): List<DailyLog> =
            store.values.filter { it.date >= from && it.date <= to }.sortedBy { it.date }

        override fun upsert(log: DailyLog): DailyLog {
            val id = store[log.date]?.id ?: nextId++
            val saved = log.copy(id = id)
            store[log.date] = saved
            return saved
        }

        override fun listAll(): List<DailyLog> = store.values.toList()

        override fun replaceAll(logs: List<DailyLog>) {
            store.clear()
            logs.forEach { upsert(it) }
        }
    }

    private class FakeGoalRepository(private val goals: List<Goal>) : GoalRepository {
        override fun list(includeDeleted: Boolean): List<Goal> =
            if (includeDeleted) goals else goals.filter { it.deletedAt == null }

        override fun findById(id: Long, includeDeleted: Boolean): Goal? =
            list(includeDeleted).firstOrNull { it.id == id }

        override fun insert(goal: Goal): Long = 0
        override fun update(goal: Goal) {}
        override fun softDeleteSubtree(id: Long, deletedAtIso: String): Int = 0
        override fun replaceAll(goals: List<Goal>) {}
    }

    private class FakeTaskRepository(private val counts: Map<Long, Int> = emptyMap()) : TaskRepository {
        val countCalls = ArrayList<Pair<Long, LocalDate>>()

        override fun countDoneSince(goalId: Long, since: LocalDate): Int {
            countCalls.add(goalId to since)
            return counts[goalId] ?: 0
        }

        override fun listByDate(date: LocalDate): List<Task> = emptyList()
        override fun listByGoal(goalId: Long): List<Task> = emptyList()
        override fun insert(task: Task): Long = 0
        override fun setDone(id: Long, done: Boolean, doneAtIso: String?) {}
        override fun delete(id: Long) {}
        override fun listAll(): List<Task> = emptyList()
        override fun replaceAll(tasks: List<Task>) {}
    }

    // ---------------- upsert / recentLogs ----------------

    @Test
    fun `同一天重复保存只保留一条并更新内容`() {
        val repo = FakeDailyLogRepository()
        val service = LogService(repo, FakeGoalRepository(emptyList()), FakeTaskRepository())

        val first = service.upsertTodayOrDate(DailyLog(date = today, studyMinutes = 30))
        val second = service.upsertTodayOrDate(DailyLog(date = today, studyMinutes = 90))

        assertEquals(first.id, second.id)
        assertEquals(1, repo.listBetween(today, today).size)
        assertEquals(90, repo.findByDate(today)!!.studyMinutes)
    }

    @Test
    fun `近几天日志含今天且按日期倒序`() {
        val repo = FakeDailyLogRepository()
        val service = LogService(repo, FakeGoalRepository(emptyList()), FakeTaskRepository())
        service.upsertTodayOrDate(DailyLog(date = today))
        service.upsertTodayOrDate(DailyLog(date = Dates.minusDays(today, 1)))
        service.upsertTodayOrDate(DailyLog(date = Dates.minusDays(today, 3)))

        val recent = service.recentLogs(days = 3, today = today)

        assertEquals(listOf(today, Dates.minusDays(today, 1)), recent.map { it.date })
    }

    // ---------------- overview / trends ----------------

    @Test
    fun `总览结合目标计数与连续打卡`() {
        val repo = FakeDailyLogRepository()
        serviceLogs(repo, today, Dates.minusDays(today, 1))
        val goals = listOf(
            Goal(id = 1, title = "A", status = GoalStatuses.IN_PROGRESS),
            Goal(id = 2, title = "B", status = GoalStatuses.COMPLETED),
            Goal(id = 3, title = "已删除", status = GoalStatuses.IN_PROGRESS, deletedAt = "2026-01-01T00:00:00Z"),
        )
        val service = LogService(repo, FakeGoalRepository(goals), FakeTaskRepository())

        val overview = service.overview(today)

        assertEquals(2, overview.streak)
        assertEquals(2, overview.totalGoals)
        assertEquals(1, overview.completedGoals)
        assertEquals(1, overview.inProgressGoals)
    }

    @Test
    fun `趋势从仓储取数并落在正确日期上`() {
        val repo = FakeDailyLogRepository()
        serviceLogs(repo, Dates.minusDays(today, 1), studyMinutes = 77)
        val service = LogService(repo, FakeGoalRepository(emptyList()), FakeTaskRepository())

        val trends = service.trends(days = 7, today = today)

        assertEquals(7, trends.labels.size)
        assertEquals(77, trends.studyMinutes[5])
        assertEquals(0, trends.studyMinutes[0])
    }

    // ---------------- detectGaps 编排 ----------------

    @Test
    fun `缺口检测只对进行中目标统计任务数且窗口为今天前七天`() {
        val repo = FakeDailyLogRepository()
        repeat(3) { repo.upsert(DailyLog(date = Dates.minusDays(today, it), doneItems = "写代码")) }
        val goals = listOf(
            Goal(id = 5, title = "学习古希腊哲学原著精读", status = GoalStatuses.IN_PROGRESS),
            Goal(id = 6, title = "已完成的旧目标", status = GoalStatuses.COMPLETED),
        )
        val taskRepo = FakeTaskRepository(mapOf(5L to 0))
        val service = LogService(repo, FakeGoalRepository(goals), taskRepo)

        val gaps = service.detectGaps(today)

        assertEquals(listOf(5L to Dates.minusDays(today, 7)), taskRepo.countCalls)
        assertEquals(1, gaps.count { it.category == "目标停滞" })
        assertTrue(gaps.any { it.category == "目标停滞" && it.title.contains("古希腊哲学") })
    }

    private fun serviceLogs(repo: FakeDailyLogRepository, vararg dates: LocalDate, studyMinutes: Int = 0) {
        dates.forEach { repo.upsert(DailyLog(date = it, studyMinutes = studyMinutes)) }
    }
}
