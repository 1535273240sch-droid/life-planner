package com.lifeplanner.core.json

import com.lifeplanner.core.model.AiMessage
import com.lifeplanner.core.model.DailyLog
import com.lifeplanner.core.model.Goal
import com.lifeplanner.core.model.Profile
import com.lifeplanner.core.model.Skill
import com.lifeplanner.core.model.Task
import com.lifeplanner.core.port.AiMessageRepository
import com.lifeplanner.core.port.DailyLogRepository
import com.lifeplanner.core.port.GoalRepository
import com.lifeplanner.core.port.ProfileRepository
import com.lifeplanner.core.port.SkillRepository
import com.lifeplanner.core.port.TaskRepository
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** BackupService 单元测试：纯 JVM，手写内存假仓储。 */
class BackupServiceTest {

    @Test
    fun `导出后重新导入，各实体数量与关键字段一致`() {
        val src = Fixture()
        src.profiles.save(
            Profile(age = 30, gender = "女", vision = "成为更好的自己", healthInfo = mapOf("height" to "165"))
        )
        src.skills.add(Skill(name = "英语", category = "语言", level = 7))
        src.skills.add(Skill(name = "跑步", note = "每周三次"))
        val parentId = src.goals.insert(Goal(level = "long", title = "提升英语", progress = 20))
        src.goals.insert(Goal(level = "stage", title = "通过雅思", parentId = parentId, progress = 40))
        src.goals.insert(Goal(level = "weekly", title = "已删除目标", deletedAt = "2026-01-01T00:00:00Z"))
        src.tasks.insert(Task(goalId = parentId, date = LocalDate(2026, 5, 1), title = "背单词", estMinutes = 45))
        src.logs.upsert(
            DailyLog(date = LocalDate(2026, 5, 1), studyMinutes = 60, mood = 4, meals = mapOf("lunch" to "鸡胸肉"))
        )
        src.messages.insert(
            AiMessage(mode = "advisor", question = "要不要报班", answer = "建议先自学", createdAt = "2026-05-01T10:00:00Z", modelName = "gpt-x")
        )

        val text = src.service.exportJson()

        val dst = Fixture()
        val summary = dst.service.importJson(text, ImportMode.REPLACE)

        // 数量
        assertEquals(2, summary.skills)
        assertEquals(3, summary.goals)
        assertEquals(1, summary.tasks)
        assertEquals(1, summary.dailyLogs)
        assertEquals(1, summary.aiMessages)
        assertTrue(summary.profileImported)

        // 关键字段
        assertEquals(30, dst.profiles.get()?.age)
        assertEquals(mapOf("height" to "165"), dst.profiles.get()?.healthInfo)
        assertEquals(3, dst.goals.items.size) // 含已软删除目标
        assertNotNull(dst.goals.items.firstOrNull { it.title == "已删除目标" && it.deletedAt != null })
        val task = dst.tasks.items.single()
        assertEquals("背单词", task.title)
        assertEquals(45, task.estMinutes)
        assertEquals(LocalDate(2026, 5, 1), task.date)
        assertEquals(60, dst.logs.items.single().studyMinutes)
        assertEquals("gpt-x", dst.messages.items.single().modelName)
    }

    @Test
    fun `导入后父子关系按新id重映射仍然正确`() {
        val src = Fixture()
        val oldParentId = src.goals.insert(Goal(level = "long", title = "父目标"))
        src.goals.insert(Goal(level = "stage", title = "子目标", parentId = oldParentId))
        val text = src.service.exportJson()

        val dst = Fixture()
        // 预先占用若干 id，迫使导入后的自增 id 与备份文件里的旧 id 不同
        repeat(5) { dst.goals.insert(Goal(level = "vision", title = "占位$it")) }

        dst.service.importJson(text, ImportMode.REPLACE)

        val parent = dst.goals.items.first { it.title == "父目标" }
        val child = dst.goals.items.first { it.title == "子目标" }
        assertEquals(parent.id, child.parentId)          // 指向新的父 id
        assertNotEquals(oldParentId, child.parentId)     // 不是旧 id
        assertEquals(2, dst.goals.items.size)            // 占位数据被清空
    }

    @Test
    fun `坏JSON抛出可读错误`() {
        val f = Fixture()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            f.service.importJson("这不是 json", ImportMode.REPLACE)
        }
        assertTrue(ex.message!!.contains("不是合法的 JSON"))
        assertTrue(f.goals.items.isEmpty())
    }

    @Test
    fun `缺少version或版本不支持时抛出可读错误`() {
        val f = Fixture()
        val missing = assertThrows(IllegalArgumentException::class.java) {
            f.service.importJson("""{"exportedAt":"2026-01-01T00:00:00Z","skills":[]}""", ImportMode.REPLACE)
        }
        assertTrue(missing.message!!.contains("不支持的备份版本"))

        val wrong = assertThrows(IllegalArgumentException::class.java) {
            f.service.importJson("""{"version":2}""", ImportMode.REPLACE)
        }
        assertTrue(wrong.message!!.contains("不支持的备份版本：2"))
    }

    @Test
    fun `导入时越界进度被夹到0到100之间`() {
        val f = Fixture()
        val text = """
            {"version":1,"goals":[
              {"title":"进度过高","progress":150},
              {"title":"进度为负","progress":-10}
            ]}
        """.trimIndent()
        f.service.importJson(text, ImportMode.REPLACE)

        assertEquals(100, f.goals.items.first { it.title == "进度过高" }.progress)
        assertEquals(0, f.goals.items.first { it.title == "进度为负" }.progress)
    }

    @Test
    fun `只有version的空备份能正常导入`() {
        val f = Fixture()
        val summary = f.service.importJson("""{"version":1}""", ImportMode.REPLACE)

        assertEquals(false, summary.profileImported)
        assertEquals(0, summary.skills)
        assertEquals(0, summary.goals)
        assertEquals(0, summary.tasks)
        assertEquals(0, summary.dailyLogs)
        assertEquals(0, summary.aiMessages)
        assertTrue(f.goals.items.isEmpty())
    }

    // ---- 测试夹具：内存假仓储 ----

    private class Fixture {
        val profiles = FakeProfileRepository()
        val skills = FakeSkillRepository()
        val goals = FakeGoalRepository()
        val tasks = FakeTaskRepository()
        val logs = FakeDailyLogRepository()
        val messages = FakeAiMessageRepository()
        val service = BackupService(profiles, skills, goals, tasks, logs, messages)
    }

    private class FakeProfileRepository : ProfileRepository {
        private var profile: Profile? = null
        override fun get(): Profile? = profile
        override fun save(profile: Profile): Profile {
            this.profile = profile
            return profile
        }
    }

    private class FakeSkillRepository : SkillRepository {
        val items = mutableListOf<Skill>()
        private var nextId = 1L
        override fun list(): List<Skill> = items.toList()
        override fun add(skill: Skill): Skill {
            val saved = skill.copy(id = nextId++)
            items.add(saved)
            return saved
        }
        override fun update(skill: Skill) {
            val i = items.indexOfFirst { it.id == skill.id }
            if (i >= 0) items[i] = skill
        }
        override fun delete(id: Long) {
            items.removeAll { it.id == id }
        }
        override fun replaceAll(skills: List<Skill>) {
            items.clear()
            skills.forEach { items.add(it.copy(id = nextId++)) }
        }
    }

    private class FakeGoalRepository : GoalRepository {
        val items = mutableListOf<Goal>()
        private var nextId = 1L
        override fun list(includeDeleted: Boolean): List<Goal> =
            items.filter { includeDeleted || it.deletedAt == null }
        override fun findById(id: Long, includeDeleted: Boolean): Goal? =
            items.firstOrNull { it.id == id && (includeDeleted || it.deletedAt == null) }
        override fun insert(goal: Goal): Long {
            val id = nextId++
            items.add(goal.copy(id = id))
            return id
        }
        override fun update(goal: Goal) {
            val i = items.indexOfFirst { it.id == goal.id }
            if (i >= 0) items[i] = goal
        }
        override fun softDeleteSubtree(id: Long, deletedAtIso: String): Int {
            val target = items.firstOrNull { it.id == id } ?: return 0
            if (target.deletedAt != null) return 0
            var count = 0
            fun mark(gid: Long) {
                val i = items.indexOfFirst { it.id == gid }
                if (i < 0 || items[i].deletedAt != null) return
                items[i] = items[i].copy(deletedAt = deletedAtIso)
                count++
                items.filter { it.parentId == gid }.forEach { mark(it.id!!) }
            }
            mark(id)
            return count
        }
        override fun replaceAll(goals: List<Goal>) {
            items.clear()
            goals.forEach { insert(it) }
        }
    }

    private class FakeTaskRepository : TaskRepository {
        val items = mutableListOf<Task>()
        private var nextId = 1L
        override fun listByDate(date: LocalDate): List<Task> = items.filter { it.date == date }
        override fun listByGoal(goalId: Long): List<Task> = items.filter { it.goalId == goalId }
        override fun listAll(): List<Task> = items.toList()
        override fun insert(task: Task): Long {
            val id = nextId++
            items.add(task.copy(id = id))
            return id
        }
        override fun setDone(id: Long, done: Boolean, doneAtIso: String?) {
            val i = items.indexOfFirst { it.id == id }
            if (i >= 0) items[i] = items[i].copy(done = done, doneAt = doneAtIso)
        }
        override fun delete(id: Long) {
            items.removeAll { it.id == id }
        }
        override fun countDoneSince(goalId: Long, since: LocalDate): Int =
            items.count { it.goalId == goalId && it.done && it.date >= since }
        override fun replaceAll(tasks: List<Task>) {
            items.clear()
            tasks.forEach { insert(it) }
        }
    }

    private class FakeDailyLogRepository : DailyLogRepository {
        val items = mutableListOf<DailyLog>()
        private var nextId = 1L
        override fun findByDate(date: LocalDate): DailyLog? = items.firstOrNull { it.date == date }
        override fun listBetween(from: LocalDate, to: LocalDate): List<DailyLog> =
            items.filter { it.date >= from && it.date <= to }
        override fun listAll(): List<DailyLog> = items.toList()
        override fun upsert(log: DailyLog): DailyLog {
            val i = items.indexOfFirst { it.date == log.date }
            return if (i >= 0) {
                val updated = log.copy(id = items[i].id)
                items[i] = updated
                updated
            } else {
                val added = log.copy(id = nextId++)
                items.add(added)
                added
            }
        }
        override fun replaceAll(logs: List<DailyLog>) {
            items.clear()
            logs.forEach { items.add(it.copy(id = nextId++)) }
        }
    }

    private class FakeAiMessageRepository : AiMessageRepository {
        val items = mutableListOf<AiMessage>()
        private var nextId = 1L
        override fun listByMode(mode: String?, limit: Int): List<AiMessage> =
            items.filter { mode == null || it.mode == mode }.sortedByDescending { it.id }.take(limit)
        override fun listAll(): List<AiMessage> = items.toList()
        override fun insert(message: AiMessage): Long {
            val id = nextId++
            items.add(message.copy(id = id))
            return id
        }
        override fun replaceAll(messages: List<AiMessage>) {
            items.clear()
            messages.forEach { insert(it) }
        }
        override fun clear() {
            items.clear()
        }
    }
}
