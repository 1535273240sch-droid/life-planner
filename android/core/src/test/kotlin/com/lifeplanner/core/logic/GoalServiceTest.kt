package com.lifeplanner.core.logic

import com.lifeplanner.core.model.Goal
import com.lifeplanner.core.model.GoalLevels
import com.lifeplanner.core.model.GoalStatuses
import com.lifeplanner.core.model.Task
import com.lifeplanner.core.port.GoalRepository
import com.lifeplanner.core.port.TaskRepository
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** GoalService 编排单测：手写极简假仓储，不连库、不连网、无 Mockito。 */
class GoalServiceTest {

    // ---- 假仓储 ----

    private class FakeGoalRepository(initial: List<Goal> = emptyList()) : GoalRepository {
        private val store = LinkedHashMap<Long, Goal>()
        private var autoId = 0L

        init {
            for (goal in initial) {
                val id = goal.id ?: (++autoId)
                store[id] = goal.copy(id = id)
                if (id > autoId) autoId = id
            }
        }

        override fun list(includeDeleted: Boolean): List<Goal> =
            store.values.filter { includeDeleted || it.deletedAt == null }

        override fun findById(id: Long, includeDeleted: Boolean): Goal? {
            val goal = store[id] ?: return null
            return if (!includeDeleted && goal.deletedAt != null) null else goal
        }

        override fun insert(goal: Goal): Long {
            val id = goal.id ?: (++autoId)
            store[id] = goal.copy(id = id)
            if (id > autoId) autoId = id
            return id
        }

        override fun update(goal: Goal) {
            val id = goal.id ?: return
            store[id] = goal
        }

        override fun softDeleteSubtree(id: Long, deletedAtIso: String): Int {
            val target = store[id] ?: return 0
            if (target.deletedAt != null) return 0
            // 收集整棵子树（不限已删除）
            val visited = LinkedHashSet<Long>()
            val stack = ArrayDeque<Long>()
            stack.addLast(id)
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                if (!visited.add(cur)) continue
                store.values.filter { it.parentId == cur && it.id != null }
                    .forEach { stack.addLast(it.id!!) }
            }
            var changed = 0
            for (gid in visited) {
                val goal = store[gid] ?: continue
                if (goal.deletedAt == null) {
                    store[gid] = goal.copy(deletedAt = deletedAtIso)
                    changed++
                }
            }
            return changed
        }

        override fun replaceAll(goals: List<Goal>) {
            store.clear()
            autoId = 0L
            for (goal in goals) {
                val id = goal.id ?: (++autoId)
                store[id] = goal.copy(id = id)
            }
        }
    }

    private class FakeTaskRepository(
        private val doneDates: Map<Long, List<LocalDate>> = emptyMap(),
    ) : TaskRepository {
        override fun listByDate(date: LocalDate): List<Task> = emptyList()
        override fun listByGoal(goalId: Long): List<Task> = emptyList()
        override fun insert(task: Task): Long = 0L
        override fun setDone(id: Long, done: Boolean, doneAtIso: String?) {}
        override fun delete(id: Long) {}
        override fun countDoneSince(goalId: Long, since: LocalDate): Int =
            doneDates[goalId].orEmpty().count { it >= since }
        override fun listAll(): List<Task> = emptyList()
        override fun replaceAll(tasks: List<Task>) {}
    }

    private fun g(
        id: Long?,
        parentId: Long? = null,
        level: String = GoalLevels.LONG,
        status: String = GoalStatuses.IN_PROGRESS,
        progress: Int = 0,
        deleted: Boolean = false,
    ) = Goal(
        id = id,
        parentId = parentId,
        level = level,
        title = "G${id ?: "new"}",
        progress = progress,
        status = status,
        deletedAt = if (deleted) "2024-01-01T00:00:00Z" else null,
    )

    // ---- createGoal ----

    @Test
    fun `新建目标时父级是自己会抛中文异常`() {
        val service = GoalService(FakeGoalRepository(), FakeTaskRepository())
        val ex = assertThrows(IllegalArgumentException::class.java) {
            service.createGoal(g(5, parentId = 5))
        }
        assertEquals("父级目标不能是它自己", ex.message)
    }

    @Test
    fun `新建目标返回新 id`() {
        val repo = FakeGoalRepository()
        val service = GoalService(repo, FakeTaskRepository())
        val id = service.createGoal(g(null, level = GoalLevels.LONG))
        assertTrue(id > 0)
        assertEquals(1, service.list().size)
    }

    // ---- updateGoal ----

    @Test
    fun `更新不存在的目标抛 NoSuchElementException`() {
        val service = GoalService(FakeGoalRepository(), FakeTaskRepository())
        val ex = assertThrows(NoSuchElementException::class.java) {
            service.updateGoal(g(99))
        }
        assertEquals("目标不存在", ex.message)
    }

    @Test
    fun `更新时把父级设为下级会抛中文异常`() {
        val repo = FakeGoalRepository(
            listOf(g(1, level = GoalLevels.VISION), g(2, parentId = 1, level = GoalLevels.LONG)),
        )
        val service = GoalService(repo, FakeTaskRepository())
        val ex = assertThrows(IllegalArgumentException::class.java) {
            service.updateGoal(g(1, parentId = 2, level = GoalLevels.VISION))
        }
        assertEquals("不能把自己的下级目标设为父级（会形成循环）", ex.message)
    }

    // ---- deleteGoal ----

    @Test
    fun `删除级联软删除整棵子树且其余目标保留`() {
        val repo = FakeGoalRepository(
            listOf(
                g(1, level = GoalLevels.VISION),
                g(2, parentId = 1, level = GoalLevels.LONG),
                g(3, parentId = 2, level = GoalLevels.STAGE),
                g(4, level = GoalLevels.VISION),
            ),
        )
        val service = GoalService(repo, FakeTaskRepository())

        assertTrue(service.deleteGoal(1))
        assertEquals(listOf(4L), service.list().map { it.id })
        assertEquals(4, service.list(includeDeleted = true).size)
        // 已删除再删返回 false
        assertFalse(service.deleteGoal(1))
    }

    // ---- tree ----

    @Test
    fun `tree 返回单根结构且排除已删除目标`() {
        val repo = FakeGoalRepository(
            listOf(
                g(1, level = GoalLevels.VISION),
                g(2, parentId = 1, level = GoalLevels.LONG),
                g(3, parentId = 2, level = GoalLevels.STAGE),
                g(9, level = GoalLevels.VISION, deleted = true),
            ),
        )
        val service = GoalService(repo, FakeTaskRepository())
        val tree = service.tree()
        assertEquals(1, tree.size)
        assertEquals(1L, tree.single().goal.id)
    }

    // ---- confirmProgress ----

    @Test
    fun `confirmProgress 应用合法项并跳过非法项`() {
        val repo = FakeGoalRepository(
            listOf(
                g(1, progress = 0),
                g(2, progress = 0),
                g(3, progress = 10, deleted = true),
            ),
        )
        val service = GoalService(repo, FakeTaskRepository())

        val result = service.confirmProgress(
            listOf(1L to 100, 1L to 40, 2L to 150, 3L to 30, 99L to 50),
        )

        assertEquals(listOf(1L), result.updated.map { it.id })
        assertEquals(40, result.updated.single().progress)
        assertEquals(3, result.skipped.size)
        assertTrue(result.skipped.contains(2L to "进度必须在 0-100 之间"))
        assertTrue(result.skipped.contains(3L to "目标已删除"))
        assertTrue(result.skipped.contains(99L to "目标不存在"))
        // 已落库
        assertEquals(40, repo.findById(1L)!!.progress)
        assertEquals(0, repo.findById(2L)!!.progress)
    }

    // ---- goalStagnantCandidates ----

    @Test
    fun `goalStagnantCandidates 只返回进行中且无已完成任务的目标`() {
        val repo = FakeGoalRepository(
            listOf(
                g(1, status = GoalStatuses.IN_PROGRESS),
                g(2, status = GoalStatuses.IN_PROGRESS),
                g(3, status = GoalStatuses.COMPLETED),
                g(4, status = GoalStatuses.IN_PROGRESS),
            ),
        )
        val tasks = FakeTaskRepository(mapOf(2L to listOf(LocalDate(2024, 1, 5))))
        val service = GoalService(repo, tasks)

        val since = LocalDate(2024, 1, 1)
        assertEquals(setOf(1L, 4L), service.goalStagnantCandidates(since).map { it.id }.toSet())
    }
}
