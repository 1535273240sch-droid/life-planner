package com.lifeplanner.core.ai

import com.lifeplanner.core.model.AiMessage
import com.lifeplanner.core.model.AiSources
import com.lifeplanner.core.model.DailyLog
import com.lifeplanner.core.model.Goal
import com.lifeplanner.core.model.GoalStatuses
import com.lifeplanner.core.model.Profile
import com.lifeplanner.core.model.Skill
import com.lifeplanner.core.model.Task
import com.lifeplanner.core.port.AiMessageRepository
import com.lifeplanner.core.port.DailyLogRepository
import com.lifeplanner.core.port.GoalRepository
import com.lifeplanner.core.port.HttpTransport
import com.lifeplanner.core.port.ProfileRepository
import com.lifeplanner.core.port.SkillRepository
import com.lifeplanner.core.port.TaskRepository
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一句话整理（organizeCapture / applyCapture）的纯 JVM 单元测试。
 *
 * 传输与仓储全部是手写假实现（与 AiServiceTest 同一套思路），重点断言：
 * - 解析时越界值被裁掉、非法值不猜；模型违约（reply 缺失 / 整段不是 JSON）抛 BAD_RESPONSE
 * - 危机命中完全离线，不调用传输、不产生草稿
 * - 整理阶段只写一条历史，绝不写业务库
 * - 落库时 null 字段不覆盖原值、meals 按 key 合并、任务/目标按标题去重且计数真实
 */
class CaptureTest {

    private val today = LocalDate(2026, 10, 17)

    /** 一份“各项都合法但混入越界值”的模型输出，用于验证逐字段裁剪。 */
    private val goodJson = """
        {
          "reply": "  今天学了 90 分钟，继续保持  ",
          "log": {
            "study_minutes": 90,
            "study_note": "看完两章",
            "sleep_start": "23:30",
            "sleep_end": "7:5",
            "exercise_type": "跑步",
            "exercise_minutes": 2000,
            "mood": 7,
            "energy": 3,
            "meals": {"breakfast": "鸡蛋", "lunch": "   ", "dinner": "面条", "snack": "", "夜宵": "泡面"},
            "done_items": " 完成周报 ",
            "reflection": null
          },
          "tasks": [
            {"title": "背单词", "est_minutes": 45, "goal_id": 1},
            {"title": "背单词", "est_minutes": 60, "goal_id": null},
            {"title": "   ", "est_minutes": 30},
            {"title": "跑步", "est_minutes": 0, "goal_id": 999}
          ],
          "goals": [
            {"title": "每周跑三次", "level": "weekly", "category": "健康", "description": "有氧", "metric": "每周3次"},
            {"title": "学吉他", "level": "随便", "category": "瞎写", "description": null, "metric": ""}
          ]
        }
    """.trimIndent()

    // ------------------------------------------------------------------
    // organizeCapture：解析与校验
    // ------------------------------------------------------------------

    @Test
    fun 正常解析并按范围裁剪越界字段() {
        val f = CaptureFixture(goals = listOf(Goal(id = 1, title = "学会英语", status = GoalStatuses.IN_PROGRESS)))
        f.transport.response = wrap(goodJson)

        val r = f.service.organizeCapture("今天背了单词，晚上跑了步", today)

        assertEquals("今天学了 90 分钟，继续保持", r.reply)
        assertFalse(r.isCrisis)
        assertEquals(AiSources.AI_MODEL, r.source)
        assertEquals("test-model", r.modelName)
        assertTrue(r.hasDrafts)
        assertEquals(1, f.transport.calls)

        val log = requireNotNull(r.log)
        assertEquals(90, log.studyMinutes)
        assertEquals("看完两章", log.studyNote)
        assertEquals("23:30", log.sleepStart)
        assertNull(log.sleepEnd)            // "7:5" 不是 HH:MM
        assertEquals("跑步", log.exerciseType)
        assertNull(log.exerciseMinutes)     // 2000 越界
        assertNull(log.mood)                // 7 越界
        assertEquals(3, log.energy)
        // 空字符串与自造 key 都不进 meals
        assertEquals(mapOf("breakfast" to "鸡蛋", "dinner" to "面条"), log.meals)
        assertEquals("完成周报", log.doneItems)
        assertNull(log.reflection)

        assertEquals(2, r.tasks.size)
        assertEquals("背单词", r.tasks[0].title)
        assertEquals(45, r.tasks[0].estMinutes)
        assertEquals(1L, r.tasks[0].goalId)
        assertEquals("学会英语", r.tasks[0].goalName)
        assertEquals("跑步", r.tasks[1].title)
        assertEquals(30, r.tasks[1].estMinutes) // est 0 -> 默认 30
        assertNull(r.tasks[1].goalId)          // 999 不存在 -> null

        assertEquals(2, r.goals.size)
        assertEquals("每周跑三次", r.goals[0].title)
        assertEquals("健康", r.goals[0].category)
        assertEquals("有氧", r.goals[0].description)
        assertEquals("每周3次", r.goals[0].metric)
        assertEquals("weekly", r.goals[1].level)  // 非法层级回落 weekly
        assertEquals("生活", r.goals[1].category) // 非法分类回落生活
        assertNull(r.goals[1].metric)             // 空串视为没提到
    }

    @Test
    fun 任务按标题去重且最多保留六个() {
        val f = CaptureFixture()
        val tasks = (1..8).joinToString(",") { """{"title":"任务$it","est_minutes":30}""" }
        f.transport.response = wrap("""{"reply":"好","log":null,"tasks":[$tasks],"goals":[]}""")

        val r = f.service.organizeCapture("帮我排一下任务", today)

        assertEquals(6, r.tasks.size)
        assertEquals("任务1", r.tasks[0].title)
        assertEquals("任务6", r.tasks[5].title)
    }

    @Test
    fun 目标最多两个且标题超长截断到一百字() {
        val f = CaptureFixture()
        val longTitle = "长".repeat(120)
        f.transport.response = wrap(
            """{"reply":"好","log":null,"tasks":[],"goals":[""" +
                """{"title":"$longTitle","level":"weekly","category":"学习"},""" +
                """{"title":"目标二","level":"weekly","category":"健康"},""" +
                """{"title":"目标三","level":"weekly","category":"职业"}""" +
                """]}""",
        )

        val r = f.service.organizeCapture("我想定几个目标", today)

        assertEquals(2, r.goals.size)
        assertEquals(100, r.goals[0].title.length)
        assertEquals("目标二", r.goals[1].title)
    }

    @Test
    fun 任务只能挂在进行中且未删除的目标上() {
        val f = CaptureFixture(
            goals = listOf(
                Goal(id = 1, title = "已暂停", status = GoalStatuses.PAUSED),
                Goal(id = 2, title = "已删除", status = GoalStatuses.IN_PROGRESS, deletedAt = "2026-10-01T00:00:00Z"),
                Goal(id = 3, title = "进行中", status = GoalStatuses.IN_PROGRESS),
            ),
        )
        f.transport.response = wrap(
            """{"reply":"好","log":null,"tasks":[""" +
                """{"title":"A","est_minutes":30,"goal_id":1},""" +
                """{"title":"B","est_minutes":30,"goal_id":2},""" +
                """{"title":"C","est_minutes":30,"goal_id":3}""" +
                """],"goals":[]}""",
        )

        val r = f.service.organizeCapture("把任务挂到目标上", today)

        assertNull(r.tasks[0].goalId)
        assertNull(r.tasks[1].goalId)
        assertEquals(3L, r.tasks[2].goalId)
        assertEquals("进行中", r.tasks[2].goalName)
    }

    @Test
    fun reply为空视为模型违约() {
        val f = CaptureFixture()
        f.transport.response = wrap("""{"reply":"   ","log":null,"tasks":[],"goals":[]}""")

        val e = assertThrows(AiException::class.java) { f.service.organizeCapture("随便说说", today) }

        assertEquals(AiErrorCodes.BAD_RESPONSE, e.code)
        assertEquals(0, f.messages.inserted.size)
    }

    @Test
    fun 坏JSON抛响应格式错误且不写历史() {
        val f = CaptureFixture()
        f.transport.response = wrap("好的，我明白了，但你这句话信息量太少。")

        val e = assertThrows(AiException::class.java) { f.service.organizeCapture("随便说说", today) }

        assertEquals(AiErrorCodes.BAD_RESPONSE, e.code)
        assertEquals(0, f.messages.inserted.size)
        assertTrue(f.tasksRepo.inserted.isEmpty())
    }

    // ------------------------------------------------------------------
    // organizeCapture：危机与本模式副作用
    // ------------------------------------------------------------------

    @Test
    fun 危机命中时不联网且不产生草稿() {
        val f = CaptureFixture()

        val r = f.service.organizeCapture("我不想活了", today)

        assertTrue(r.isCrisis)
        assertEquals(AiSources.CRISIS, r.source)
        assertNull(r.modelName)
        assertNull(r.log)
        assertTrue(r.tasks.isEmpty())
        assertTrue(r.goals.isEmpty())
        assertFalse(r.hasDrafts)
        assertTrue(r.reply.contains("生命"))
        assertEquals(0, f.transport.calls)
        assertEquals(1, f.messages.inserted.size)
        assertEquals("capture", f.messages.inserted[0].mode)
        assertEquals(AiSources.CRISIS, f.messages.inserted[0].source)
        assertNull(f.messages.inserted[0].modelName)
    }

    @Test
    fun 整理成功只写一条历史且不落业务库() {
        val f = CaptureFixture(goals = listOf(Goal(id = 1, title = "学会英语", status = GoalStatuses.IN_PROGRESS)))
        f.transport.response = wrap(goodJson)

        f.service.organizeCapture("今天背了单词，晚上跑了步", today)

        assertEquals(1, f.transport.calls)
        assertEquals(1, f.messages.inserted.size)
        val saved = f.messages.inserted[0]
        assertEquals("capture", saved.mode)
        assertEquals(AiSources.AI_MODEL, saved.source)
        assertEquals("test-model", saved.modelName)
        assertTrue(saved.answer.contains("【整理出的任务（待确认）】"))
        assertTrue(saved.answer.contains("背单词"))
        // 确认前不写任务/目标/日志
        assertTrue(f.tasksRepo.inserted.isEmpty())
        assertTrue(f.goalsRepo.insertedGoals.isEmpty())
        assertTrue(f.logsRepo.stored.isEmpty())
    }

    // ------------------------------------------------------------------
    // applyCapture：合并、去重与真实计数
    // ------------------------------------------------------------------

    @Test
    fun 落库时null字段保留原值且meals按键合并() {
        val existing = DailyLog(
            date = today,
            doneItems = "旧产出",
            studyMinutes = 100,
            studyNote = "旧笔记",
            meals = mapOf("breakfast" to "包子", "lunch" to "米饭"),
            sleepStart = "22:00",
            sleepEnd = "06:00",
            exerciseType = "游泳",
            exerciseMinutes = 40,
            mood = 2,
            energy = 4,
            reflection = "旧反思",
        )
        val f = CaptureFixture(logs = listOf(existing))

        val applied = f.service.applyCapture(
            captureResult(
                log = CaptureLogDraft(
                    sleepStart = "23:00",
                    mood = 5,
                    meals = mapOf("lunch" to "面条", "dinner" to "饺子"),
                ),
            ),
            today,
        )

        assertTrue(applied.logSaved)
        assertEquals(0, applied.tasksInserted)
        assertEquals(0, applied.goalsInserted)
        val merged = f.logsRepo.stored.single() // 一天仍然只有一条
        assertEquals(100, merged.studyMinutes)
        assertEquals("旧笔记", merged.studyNote)
        assertEquals("23:00", merged.sleepStart)
        assertEquals("06:00", merged.sleepEnd)
        assertEquals("游泳", merged.exerciseType)
        assertEquals(40, merged.exerciseMinutes)
        assertEquals(5, merged.mood)
        assertEquals(4, merged.energy)
        assertEquals(mapOf("breakfast" to "包子", "lunch" to "面条", "dinner" to "饺子"), merged.meals)
        assertEquals("旧产出", merged.doneItems)
        assertEquals("旧反思", merged.reflection)
    }

    @Test
    fun 落库时没有当天记录则新建并保留默认值() {
        val f = CaptureFixture()

        val applied = f.service.applyCapture(
            captureResult(log = CaptureLogDraft(studyMinutes = 30)),
            today,
        )

        assertTrue(applied.logSaved)
        val saved = f.logsRepo.stored.single()
        assertEquals(today, saved.date)
        assertEquals(30, saved.studyMinutes)
        // 用户没提到的数值保留 DailyLog 的设计默认值，不臆造
        assertEquals(0, saved.exerciseMinutes)
        assertEquals(3, saved.mood)
        assertEquals(3, saved.energy)
        assertNull(saved.studyNote)
    }

    @Test
    fun 落库时任务按标题去重且跳过当日已存在() {
        val f = CaptureFixture(tasks = listOf(Task(date = today, title = "背单词")))

        val applied = f.service.applyCapture(
            captureResult(
                tasks = listOf(
                    CaptureTaskDraft(title = "背单词", estMinutes = 30),
                    CaptureTaskDraft(title = "跑步", estMinutes = 20),
                    CaptureTaskDraft(title = "跑步", estMinutes = 45),
                    CaptureTaskDraft(title = " 读书 ", estMinutes = 60),
                ),
            ),
            today,
        )

        assertEquals(2, applied.tasksInserted)
        assertEquals(listOf("跑步", "读书"), f.tasksRepo.inserted.map { it.title })
        assertEquals(listOf("背单词", "跑步", "读书"), f.tasksRepo.stored.map { it.title })
        // 重复项只保留第一条的预估时长
        assertEquals(20, f.tasksRepo.inserted.first { it.title == "跑步" }.estMinutes)
        assertEquals("读书", f.tasksRepo.inserted.first { it.title == "读书" }.title)
    }

    @Test
    fun 落库时任务目标id不存在则置空() {
        val f = CaptureFixture(goals = listOf(Goal(id = 1, title = "英语")))

        val applied = f.service.applyCapture(
            captureResult(
                tasks = listOf(
                    CaptureTaskDraft(title = "背单词", estMinutes = 30, goalId = 1),
                    CaptureTaskDraft(title = "散步", estMinutes = 30, goalId = 42),
                ),
            ),
            today,
        )

        assertEquals(2, applied.tasksInserted)
        assertEquals(1L, f.tasksRepo.inserted[0].goalId)
        assertNull(f.tasksRepo.inserted[1].goalId)
    }

    @Test
    fun 落库时目标按标题去重且跳过已删除同名目标() {
        val f = CaptureFixture(
            goals = listOf(
                Goal(id = 1, title = "学会英语", status = GoalStatuses.IN_PROGRESS),
                Goal(id = 2, title = "每天跑步", status = GoalStatuses.IN_PROGRESS, deletedAt = "2026-10-01T00:00:00Z"),
            ),
        )

        val applied = f.service.applyCapture(
            captureResult(
                goals = listOf(
                    CaptureGoalDraft(title = "学会英语"),
                    CaptureGoalDraft(title = "每天跑步", category = "健康", description = "三公里", metric = "每周3次"),
                    CaptureGoalDraft(title = "每天跑步"),
                ),
            ),
            today,
        )

        assertEquals(1, applied.goalsInserted)
        val inserted = f.goalsRepo.insertedGoals.single()
        assertEquals("每天跑步", inserted.title)
        assertEquals("健康", inserted.category)
        assertEquals("三公里", inserted.description)
        assertEquals("每周3次", inserted.metric)
        assertEquals(GoalStatuses.IN_PROGRESS, inserted.status)
        assertEquals(0, inserted.progress)
        assertEquals(today, inserted.startDate)
        // 未删除目标列表 = 原有 1 个 + 本次新建 1 个；已删除的同名目标不参与去重但也不在列表中
        assertEquals(2, f.goalsRepo.list().size)
    }

    @Test
    fun 空草稿与危机结果不写库() {
        val f = CaptureFixture()

        assertTrue(f.service.applyCapture(captureResult(), today).isEmpty)

        // 危机结果即便前端误传了草稿，也不允许自动落库
        val crisisResult = captureResult(log = CaptureLogDraft(studyMinutes = 30), isCrisis = true)
        assertTrue(f.service.applyCapture(crisisResult, today).isEmpty)

        assertTrue(f.logsRepo.stored.isEmpty())
        assertTrue(f.tasksRepo.inserted.isEmpty())
        assertTrue(f.goalsRepo.insertedGoals.isEmpty())
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    private fun captureResult(
        reply: String = "收到",
        log: CaptureLogDraft? = null,
        tasks: List<CaptureTaskDraft> = emptyList(),
        goals: List<CaptureGoalDraft> = emptyList(),
        isCrisis: Boolean = false,
    ): CaptureResult = CaptureResult(
        reply = reply,
        log = log,
        tasks = tasks,
        goals = goals,
        isCrisis = isCrisis,
        source = if (isCrisis) AiSources.CRISIS else AiSources.AI_MODEL,
        modelName = if (isCrisis) null else "test-model",
        fullText = reply,
    )

    /** OpenAI 兼容响应外壳：{"choices":[{"message":{"content": ...}}]} */
    private fun wrap(content: String): String = buildJsonObject {
        putJsonArray("choices") {
            addJsonObject {
                putJsonObject("message") { put("content", content) }
            }
        }
    }.toString()
}

// ----------------------------------------------------------------------
// 假实现（名字加 Capture 前缀，避免与 AiServiceTest 里的文件级私有假实现重名）
// ----------------------------------------------------------------------

private class CaptureFakeTransport(
    var response: String = "",
    var throwable: Throwable? = null,
) : HttpTransport {
    var calls = 0

    override fun postJson(url: String, bearerToken: String, body: String, timeoutMs: Long): String {
        calls++
        throwable?.let { throw it }
        return response
    }
}

private class CaptureFakeProfileRepo : ProfileRepository {
    override fun get(): Profile? = null
    override fun save(profile: Profile): Profile = profile
}

private class CaptureFakeSkillRepo : SkillRepository {
    private val items = mutableListOf<Skill>()
    override fun list(): List<Skill> = items.toList()
    override fun add(skill: Skill): Skill {
        items.add(skill)
        return skill
    }

    override fun update(skill: Skill) {}
    override fun delete(id: Long) {}
    override fun replaceAll(skills: List<Skill>) {
        items.clear()
        items.addAll(skills)
    }
}

private class CaptureFakeGoalRepo(items: List<Goal> = emptyList()) : GoalRepository {
    private val stored = items.toMutableList()
    val insertedGoals = mutableListOf<Goal>()
    private var nextId = (stored.mapNotNull { it.id }.maxOrNull() ?: 0L) + 1

    override fun list(includeDeleted: Boolean): List<Goal> =
        if (includeDeleted) stored.toList() else stored.filter { it.deletedAt == null }

    override fun findById(id: Long, includeDeleted: Boolean): Goal? =
        stored.firstOrNull { it.id == id && (includeDeleted || it.deletedAt == null) }

    override fun insert(goal: Goal): Long {
        val id = goal.id ?: nextId++
        val saved = goal.copy(id = id)
        stored.add(saved)
        insertedGoals.add(saved)
        return id
    }

    override fun update(goal: Goal) {}
    override fun softDeleteSubtree(id: Long, deletedAtIso: String): Int = 0
    override fun replaceAll(goals: List<Goal>) {
        stored.clear()
        stored.addAll(goals)
    }
}

private class CaptureFakeTaskRepo(items: List<Task> = emptyList()) : TaskRepository {
    private val rows = items.toMutableList()
    val inserted = mutableListOf<Task>()
    private var nextId = (rows.mapNotNull { it.id }.maxOrNull() ?: 0L) + 1

    /** 供断言：当前全部任务（含测试预置的）。 */
    val stored: List<Task> get() = rows.toList()

    override fun listByDate(date: LocalDate): List<Task> = rows.filter { it.date == date }
    override fun listByGoal(goalId: Long): List<Task> = rows.filter { it.goalId == goalId }

    override fun insert(task: Task): Long {
        val id = task.id ?: nextId++
        val saved = task.copy(id = id)
        rows.add(saved)
        inserted.add(saved)
        return id
    }

    override fun setDone(id: Long, done: Boolean, doneAtIso: String?) {}
    override fun delete(id: Long) {}
    override fun countDoneSince(goalId: Long, since: LocalDate): Int = 0
    override fun listAll(): List<Task> = rows.toList()
    override fun replaceAll(tasks: List<Task>) {
        rows.clear()
        rows.addAll(tasks)
    }
}

private class CaptureFakeLogRepo(items: List<DailyLog> = emptyList()) : DailyLogRepository {
    private val rows = items.toMutableList()

    /** 供断言：当前全部日志（一天最多一条）。 */
    val stored: List<DailyLog> get() = rows.toList()

    override fun findByDate(date: LocalDate): DailyLog? = rows.firstOrNull { it.date == date }

    override fun listBetween(from: LocalDate, to: LocalDate): List<DailyLog> =
        rows.filter { it.date >= from && it.date <= to }.sortedBy { it.date }

    override fun upsert(log: DailyLog): DailyLog {
        val idx = rows.indexOfFirst { it.date == log.date }
        if (idx >= 0) rows[idx] = log else rows.add(log)
        return log
    }

    override fun listAll(): List<DailyLog> = rows.toList()

    override fun replaceAll(logs: List<DailyLog>) {
        rows.clear()
        rows.addAll(logs)
    }
}

private class CaptureFakeMessageRepo : AiMessageRepository {
    val inserted = mutableListOf<AiMessage>()

    override fun listByMode(mode: String?, limit: Int): List<AiMessage> =
        inserted.reversed().filter { mode == null || it.mode == mode }.take(limit)

    override fun insert(message: AiMessage): Long {
        inserted.add(message)
        return inserted.size.toLong()
    }

    override fun listAll(): List<AiMessage> = inserted.toList()
    override fun replaceAll(messages: List<AiMessage>) {
        inserted.clear()
        inserted.addAll(messages)
    }

    override fun clear() {
        inserted.clear()
    }
}

private class CaptureFixture(
    val transport: CaptureFakeTransport = CaptureFakeTransport(),
    config: AiConfig = AiConfig(apiKey = "test-key", baseUrl = "https://example.test/v1", model = "test-model"),
    goals: List<Goal> = emptyList(),
    tasks: List<Task> = emptyList(),
    logs: List<DailyLog> = emptyList(),
) {
    val goalsRepo = CaptureFakeGoalRepo(goals)
    val tasksRepo = CaptureFakeTaskRepo(tasks)
    val logsRepo = CaptureFakeLogRepo(logs)
    val messages = CaptureFakeMessageRepo()

    val service = AiService(
        transport = transport,
        config = config,
        profileRepo = CaptureFakeProfileRepo(),
        skillRepo = CaptureFakeSkillRepo(),
        goalRepo = goalsRepo,
        taskRepo = tasksRepo,
        logRepo = logsRepo,
        aiMessageRepo = messages,
    )
}
