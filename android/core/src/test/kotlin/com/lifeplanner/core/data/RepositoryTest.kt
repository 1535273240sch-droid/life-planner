package com.lifeplanner.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.lifeplanner.core.db.LifePlannerDb
import com.lifeplanner.core.model.AiMessage
import com.lifeplanner.core.model.DailyLog
import com.lifeplanner.core.model.Goal
import com.lifeplanner.core.model.Profile
import com.lifeplanner.core.model.Skill
import com.lifeplanner.core.model.Task
import com.lifeplanner.core.util.Dates
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 数据层测试：用 sqlite-driver 的 JVM 内存库跑，不依赖安卓环境。
 * 每个用例在 @Before 里新建一个内存库，互不干扰。
 */
class RepositoryTest {

    private lateinit var profileRepo: ProfileRepositoryImpl
    private lateinit var skillRepo: SkillRepositoryImpl
    private lateinit var goalRepo: GoalRepositoryImpl
    private lateinit var taskRepo: TaskRepositoryImpl
    private lateinit var logRepo: DailyLogRepositoryImpl
    private lateinit var aiRepo: AiMessageRepositoryImpl

    private val d1 = LocalDate(2026, 1, 1)
    private val d2 = LocalDate(2026, 1, 2)
    private val iso = "2026-01-01T08:00:00Z"

    @Before
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LifePlannerDb.Schema.create(driver)
        val db = LifePlannerDb(driver)
        profileRepo = ProfileRepositoryImpl(db)
        skillRepo = SkillRepositoryImpl(db)
        goalRepo = GoalRepositoryImpl(db)
        taskRepo = TaskRepositoryImpl(db)
        logRepo = DailyLogRepositoryImpl(db)
        aiRepo = AiMessageRepositoryImpl(db)
    }

    // ---------------- 空库：各 select 返回空而不是崩 ----------------

    @Test
    fun emptyDatabase_selectsReturnEmpty() {
        assertNull(profileRepo.get())
        assertTrue(skillRepo.list().isEmpty())
        assertTrue(goalRepo.list().isEmpty())
        assertTrue(goalRepo.list(includeDeleted = true).isEmpty())
        assertNull(goalRepo.findById(1L))
        assertNull(goalRepo.findById(1L, includeDeleted = true))
        assertTrue(taskRepo.listByDate(d1).isEmpty())
        assertTrue(taskRepo.listByGoal(1L).isEmpty())
        assertTrue(taskRepo.listAll().isEmpty())
        assertNull(logRepo.findByDate(d1))
        assertTrue(logRepo.listBetween(d1, d2).isEmpty())
        assertTrue(logRepo.listAll().isEmpty())
        assertTrue(aiRepo.listByMode(null, 10).isEmpty())
        assertTrue(aiRepo.listByMode("advisor", 10).isEmpty())
        assertTrue(aiRepo.listAll().isEmpty())
        assertEquals(0, goalRepo.softDeleteSubtree(1L, iso))
        assertEquals(0, taskRepo.countDoneSince(1L, d1))
    }

    // ---------------- profile 存取 + healthInfo JSON 往返 ----------------

    @Test
    fun profile_saveAndGet_roundTripsHealthJson() {
        val health = mapOf("height" to "175", "weight" to "70", "taboo" to "花生\"酱\"\\辛辣")
        val saved = profileRepo.save(
            Profile(
                age = 30,
                gender = "男",
                yearsOfWork = 5,
                healthInfo = health,
                vision = "成为架构师",
            ),
        )
        assertNotNull(saved.id)

        val got = profileRepo.get()
        assertNotNull(got)
        assertEquals(30, got!!.age)
        assertEquals("男", got.gender)
        assertEquals(5, got.yearsOfWork)
        assertEquals("成为架构师", got.vision)
        assertEquals(health, got.healthInfo)

        // 再存一次：单行，id 保持，内容更新
        profileRepo.save(got.copy(age = 31))
        assertEquals(31, profileRepo.get()!!.age)
        assertEquals(saved.id, profileRepo.get()!!.id)
    }

    // ---------------- skill CRUD ----------------

    @Test
    fun skill_crudAndReplaceAll() {
        val a = skillRepo.add(Skill(name = "Kotlin", category = "编程", level = 8, note = "熟悉"))
        val b = skillRepo.add(Skill(name = "英语", category = "语言", level = 6))
        assertEquals(2, skillRepo.list().size)
        assertNotNull(a.id)
        assertTrue(a.id != b.id)

        skillRepo.update(a.copy(level = 9, note = "精通"))
        assertEquals(9, skillRepo.list().first { it.id == a.id }.level)

        skillRepo.delete(b.id!!)
        assertEquals(1, skillRepo.list().size)

        // replaceAll 空列表 = 清表
        skillRepo.replaceAll(emptyList())
        assertTrue(skillRepo.list().isEmpty())
    }

    // ---------------- goal 软删除级联 + 任务保留 ----------------

    @Test
    fun goal_softDeleteSubtree_cascadesAndKeepsTasks() {
        val pid = goalRepo.insert(Goal(title = "愿景", level = "vision"))
        val cid = goalRepo.insert(Goal(title = "阶段", level = "stage", parentId = pid))
        val gid = goalRepo.insert(Goal(title = "周", level = "weekly", parentId = cid))
        // 历史任务挂在子目标上
        taskRepo.insert(Task(goalId = gid, date = d1, title = "子任务"))

        assertEquals(3, goalRepo.list().size)
        assertEquals(3, goalRepo.list(includeDeleted = true).size)

        val affected = goalRepo.softDeleteSubtree(pid, iso)
        assertEquals(3, affected)

        // 默认查询看不到；含删除可见且 deleted_at 非空
        assertTrue(goalRepo.list().isEmpty())
        val withDeleted = goalRepo.list(includeDeleted = true)
        assertEquals(3, withDeleted.size)
        assertTrue(withDeleted.all { it.deletedAt != null })
        assertNull(goalRepo.findById(pid))
        assertNotNull(goalRepo.findById(pid, includeDeleted = true))

        // 任务不受影响
        assertEquals(1, taskRepo.listByGoal(gid).size)

        // 目标已删除 → 再次软删除返回 0
        assertEquals(0, goalRepo.softDeleteSubtree(pid, iso))
    }

    @Test
    fun goal_softDeleteSubtree_handlesCycle() {
        val a = goalRepo.insert(Goal(title = "A"))
        val b = goalRepo.insert(Goal(title = "B", parentId = a))
        // 人为制造环：A 的父变成 B
        val aGoal = goalRepo.findById(a)!!
        goalRepo.update(aGoal.copy(parentId = b))

        // 不得死循环；环内两个节点都被删除
        val affected = goalRepo.softDeleteSubtree(a, iso)
        assertEquals(2, affected)
        assertTrue(goalRepo.list().isEmpty())
    }

    @Test
    fun goal_replaceAllEmptyClearsTable() {
        goalRepo.insert(Goal(title = "x"))
        assertEquals(1, goalRepo.list(includeDeleted = true).size)
        goalRepo.replaceAll(emptyList())
        assertTrue(goalRepo.list(includeDeleted = true).isEmpty())
    }

    // ---------------- task 按日期/目标查询与完成计数 ----------------

    @Test
    fun task_queriesAndDoneCount() {
        val gid = goalRepo.insert(Goal(title = "目标"))
        val t1 = taskRepo.insert(Task(goalId = gid, date = d1, title = "t1"))
        taskRepo.insert(Task(goalId = gid, date = d1, title = "t2"))
        taskRepo.insert(Task(goalId = gid, date = d2, title = "t3"))

        assertEquals(2, taskRepo.listByDate(d1).size)
        assertEquals(3, taskRepo.listByGoal(gid).size)
        assertEquals(3, taskRepo.listAll().size)

        taskRepo.setDone(t1, done = true, doneAtIso = iso)
        assertEquals(1, taskRepo.countDoneSince(gid, d1))
        // since = d2：d1 的完成不算
        assertEquals(0, taskRepo.countDoneSince(gid, Dates.plusDays(d1, 1)))
        assertEquals(1, taskRepo.listByDate(d1).first { it.id == t1 }.let { if (it.done) 1 else 0 })

        taskRepo.delete(t1)
        assertEquals(0, taskRepo.countDoneSince(gid, d1))
        assertEquals(2, taskRepo.listAll().size)

        taskRepo.replaceAll(emptyList())
        assertTrue(taskRepo.listAll().isEmpty())
    }

    // ---------------- daily_log upsert 一天一条 + meals JSON ----------------

    @Test
    fun dailyLog_upsertKeepsOnePerDay_andRoundTripsMeals() {
        val first = logRepo.upsert(
            DailyLog(date = d1, studyMinutes = 10, meals = mapOf("lunch" to "面")),
        )
        assertNotNull(first.id)
        val meals = mapOf("dinner" to "饭\"菜\\x", "snack" to "坚果")
        logRepo.upsert(DailyLog(date = d1, studyMinutes = 20, meals = meals))

        assertEquals(1, logRepo.listBetween(d1, d1).size)
        assertEquals(1, logRepo.listAll().size)
        val stored = logRepo.findByDate(d1)
        assertNotNull(stored)
        assertEquals(20, stored!!.studyMinutes)
        // 同一天第二次 upsert 复用同一行 id
        assertEquals(first.id, stored.id)
        assertEquals(meals, stored.meals)

        logRepo.upsert(DailyLog(date = d2, studyMinutes = 5))
        assertEquals(2, logRepo.listAll().size)
        assertEquals(2, logRepo.listBetween(d1, d2).size)

        logRepo.replaceAll(emptyList())
        assertTrue(logRepo.listAll().isEmpty())
    }

    // ---------------- ai_message mode 过滤与 limit ----------------

    @Test
    fun aiMessage_modeFilterAndLimit() {
        val a1 = aiRepo.insert(AiMessage(mode = "advisor", answer = "a1", createdAt = iso))
        val a2 = aiRepo.insert(AiMessage(mode = "advisor", answer = "a2", createdAt = iso))
        val a3 = aiRepo.insert(AiMessage(mode = "advisor", answer = "a3", createdAt = iso))
        aiRepo.insert(AiMessage(mode = "daily_plan", answer = "p1", createdAt = iso))
        aiRepo.insert(AiMessage(mode = "daily_plan", answer = "p2", createdAt = iso))

        val advisors = aiRepo.listByMode("advisor", 2)
        assertEquals(2, advisors.size)
        // 倒序：最新的在前
        assertEquals(a3, advisors[0].id)
        assertEquals(a2, advisors[1].id)
        assertTrue(a1 != advisors[0].id)

        assertEquals(2, aiRepo.listByMode("daily_plan", 10).size)
        assertEquals(3, aiRepo.listByMode(null, 3).size)
        assertEquals(5, aiRepo.listAll().size)

        aiRepo.clear()
        assertTrue(aiRepo.listAll().isEmpty())

        aiRepo.insert(AiMessage(mode = "review", answer = "r", createdAt = iso))
        assertEquals(1, aiRepo.listAll().size)
        aiRepo.replaceAll(emptyList())
        assertTrue(aiRepo.listAll().isEmpty())
    }

    // ---------------- 坏日期行的跳过（不崩） ----------------

    @Test
    fun badDateRows_areSkipped() {
        // 直接经驱动写入一条非法 date，读取应跳过而非抛异常
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LifePlannerDb.Schema.create(driver)
        val db = LifePlannerDb(driver)
        db.queriesQueries.insertTask(null, "not-a-date", "x", 30L, 0L, null)
        val repo = TaskRepositoryImpl(db)
        assertTrue(repo.listAll().isEmpty())
        assertFalse(repo.listByDate(d1).isNotEmpty())
    }
}
