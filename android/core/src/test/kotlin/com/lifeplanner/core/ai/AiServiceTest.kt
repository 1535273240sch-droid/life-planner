package com.lifeplanner.core.ai

import com.lifeplanner.core.model.AiMessage
import com.lifeplanner.core.model.AiSources
import com.lifeplanner.core.model.DailyLog
import com.lifeplanner.core.model.Goal
import com.lifeplanner.core.model.Profile
import com.lifeplanner.core.model.Skill
import com.lifeplanner.core.model.Task
import com.lifeplanner.core.port.AiMessageRepository
import com.lifeplanner.core.port.DailyLogRepository
import com.lifeplanner.core.port.GoalRepository
import com.lifeplanner.core.port.HttpTransport
import com.lifeplanner.core.port.HttpTransportException
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AiService：纯 JVM 单元测试。传输与仓储全部为手写假实现，
 * 断言“危机命中时传输未被调用”“失败零副作用”“进度建议只取数据库真实值”等契约。
 */
class AiServiceTest {

    private val advisorJson =
        """{"conclusion":"建议做","reason":"因为","pros":"好处","cons_risks":"风险","alternative":"折中","goal_relation":"关系"}"""

    // ------------------------------------------------------------------
    // 配置与 HTTP 异常映射
    // ------------------------------------------------------------------

    @Test
    fun 未配置时顾问抛未配置且不调用传输() {
        val f = Fixture(config = AiConfig(apiKey = "", baseUrl = "https://x/v1", model = "m"))
        val e = assertThrows(AiException::class.java) { f.service.askAdvisor("我该不该换工作") }
        assertEquals(AiErrorCodes.NOT_CONFIGURED, e.code)
        assertEquals(0, f.transport.calls)
        assertEquals(0, f.messages.inserted.size)
    }

    @Test
    fun 传输401映射认证失败() {
        assertAdvisorHttpError(HttpTransportException(401, "unauthorized"), AiErrorCodes.AUTH_FAILED)
    }

    @Test
    fun 传输429映射限流() {
        assertAdvisorHttpError(HttpTransportException(429, "too many"), AiErrorCodes.RATE_LIMITED)
    }

    @Test
    fun 传输500映射不可达() {
        assertAdvisorHttpError(HttpTransportException(500, "server error"), AiErrorCodes.UNREACHABLE)
    }

    @Test
    fun 其他4xx映射响应格式错误() {
        assertAdvisorHttpError(HttpTransportException(404, "not found"), AiErrorCodes.BAD_RESPONSE)
    }

    @Test
    fun IO异常映射不可达() {
        assertAdvisorError(java.io.IOException("timeout"), AiErrorCodes.UNREACHABLE)
    }

    // ------------------------------------------------------------------
    // 解析与校验
    // ------------------------------------------------------------------

    @Test
    fun 乱码内容返回响应格式错误() {
        val f = Fixture()
        f.transport.response = wrap("你好，我不太确定你的问题")
        val e = assertThrows(AiException::class.java) { f.service.askAdvisor("我该不该换工作") }
        assertEquals(AiErrorCodes.BAD_RESPONSE, e.code)
        assertEquals(0, f.messages.inserted.size)
    }

    @Test
    fun 顾问缺少必需字段返回响应格式错误() {
        val f = Fixture()
        f.transport.response = wrap("""{"conclusion":"建议做","reason":"只有理由"}""")
        val e = assertThrows(AiException::class.java) { f.service.askAdvisor("我该不该换工作") }
        assertEquals(AiErrorCodes.BAD_RESPONSE, e.code)
    }

    @Test
    fun 顾问结论不在三选一返回响应格式错误且不纠正() {
        val f = Fixture()
        f.transport.response = wrap(
            """{"conclusion":"也许吧","reason":"r","pros":"p","cons_risks":"c","alternative":"a","goal_relation":"g"}""",
        )
        val e = assertThrows(AiException::class.java) { f.service.askAdvisor("我该不该换工作") }
        assertEquals(AiErrorCodes.BAD_RESPONSE, e.code)
        assertEquals(0, f.messages.inserted.size)
    }

    @Test
    fun 围栏包裹的合法JSON能解析成功() {
        val f = Fixture()
        f.transport.response = wrap("```json\n$advisorJson\n```")
        val r = f.service.askAdvisor("我该不该换工作")
        assertEquals("建议做", r.conclusion)
        assertTrue(!r.isCrisis)
    }

    @Test
    fun 成功时来源与模型名正确且只写一条记录() {
        val f = Fixture()
        f.transport.response = wrap(advisorJson)
        val r = f.service.askAdvisor("我该不该换工作")
        assertEquals(AiSources.AI_MODEL, r.source)
        assertEquals("test-model", r.modelName)
        assertEquals(1, f.transport.calls)
        assertEquals(1, f.messages.inserted.size)
        val saved = f.messages.inserted[0]
        assertEquals("advisor", saved.mode)
        assertEquals(AiSources.AI_MODEL, saved.source)
        assertEquals("test-model", saved.modelName)
        assertTrue(saved.fullTextIsNonEmpty())
    }

    @Test
    fun 危机命中时不调用传输且记录来源为安全守卫() {
        val f = Fixture()
        val r = f.service.askAdvisor("我不想活了")
        assertTrue(r.isCrisis)
        assertEquals(AiSources.CRISIS, r.source)
        assertNull(r.modelName)
        assertEquals(0, f.transport.calls)
        assertEquals(1, f.messages.inserted.size)
        assertEquals(AiSources.CRISIS, f.messages.inserted[0].source)
        assertNull(f.messages.inserted[0].modelName)
    }

    // ------------------------------------------------------------------
    // 每日规划
    // ------------------------------------------------------------------

    @Test
    fun 日计划不合法的目标id被置空且非法时长取30() {
        val f = Fixture(goals = listOf(Goal(id = 1, title = "英语", status = "in_progress")))
        f.transport.response = wrap(
            """{"tasks":[""" +
                """{"title":"背单词","goal_id":999,"goal_name":"外部目标","est_minutes":45},""" +
                """{"title":"跑步","goal_id":1,"goal_name":"","est_minutes":0}""" +
                """],"health_reminder":"早睡","tip":"加油"}""",
        )
        val r = f.service.generateDailyPlan(autoApply = false)
        assertEquals(2, r.tasks.size)
        assertNull(r.tasks[0].goalId)
        assertEquals("外部目标", r.tasks[0].goalName)
        assertEquals(45, r.tasks[0].estMinutes)
        assertEquals(1L, r.tasks[1].goalId)
        assertEquals("英语", r.tasks[1].goalName)
        assertEquals(30, r.tasks[1].estMinutes)
        assertEquals(0, f.tasks.inserted.size)
    }

    @Test
    fun 每日规划危机命中时不调用传输() {
        val today: LocalDate = com.lifeplanner.core.util.Dates.today()
        val f = Fixture(logs = listOf(DailyLog(date = today, reflection = "活着没意义")))
        val r = f.service.generateDailyPlan(autoApply = true)
        assertTrue(r.isCrisis)
        assertTrue(r.tasks.isEmpty())
        assertEquals(0, f.transport.calls)
        assertEquals(AiSources.CRISIS, f.messages.inserted[0].source)
    }

    // ------------------------------------------------------------------
    // 复盘
    // ------------------------------------------------------------------

    @Test
    fun 周复盘非法进度建议全部被丢弃() {
        val f = Fixture(goals = listOf(Goal(id = 1, title = "英语", progress = 40, status = "in_progress")))
        f.transport.response = wrap(
            """{"summary":"s","goal_progress":"g","biggest_deviation":"b","next_week_strategy":"n",""" +
                """"progress_suggestions":[""" +
                """{"goal_id":999,"suggested_progress":50,"reason":"不存在"},""" +
                """{"goal_id":1,"suggested_progress":150,"reason":"越界"},""" +
                """{"goal_id":1,"suggested_progress":-5,"reason":"负数"},""" +
                """{"goal_id":1,"suggested_progress":50,"reason":""},""" +
                """{"goal_id":1,"suggested_progress":50,"reason":"依据真实记录"}""" +
                """]}""",
        )
        val r = f.service.generateReview("weekly")
        assertEquals("weekly", r.type)
        assertEquals(1, r.progressSuggestions.size)
        val s = r.progressSuggestions[0]
        assertEquals(1L, s.goalId)
        assertEquals("英语", s.title)
        assertEquals(40, s.currentProgress)
        assertEquals(50, s.suggestedProgress)
        assertEquals("依据真实记录", s.reason)
        // 进度建议绝不写库
        assertEquals(40, f.goalsRepo.list().first().progress)
        assertEquals(0, f.tasks.inserted.size)
    }

    @Test
    fun 每日复盘危机命中时不调用传输() {
        val today: LocalDate = com.lifeplanner.core.util.Dates.today()
        val f = Fixture(logs = listOf(DailyLog(date = today, doneItems = "想不开")))
        val r = f.service.generateReview("daily")
        assertTrue(r.isCrisis)
        assertEquals(0, f.transport.calls)
        assertEquals("review", f.messages.inserted[0].mode)
        assertEquals(AiSources.CRISIS, f.messages.inserted[0].source)
    }

    // ------------------------------------------------------------------
    // 拆解
    // ------------------------------------------------------------------

    @Test
    fun 拆解无任何阶段返回响应格式错误() {
        val f = Fixture()
        f.transport.response = wrap("这是一段没有任何阶段标题的说明文字。")
        val e = assertThrows(AiException::class.java) { f.service.breakdownGoal("学会英语", null) }
        assertEquals(AiErrorCodes.BAD_RESPONSE, e.code)
        assertEquals(0, f.messages.inserted.size)
    }

    @Test
    fun 拆解能解析出阶段里程碑检验标准与周目标() {
        val f = Fixture()
        f.transport.response = wrap(
            "### 阶段一：入门 (第 1-4 周)\n" +
                "- **阶段里程碑**：打下基础\n" +
                "- **检验标准**：能日常对话\n" +
                "  - 第 1 周：掌握音标\n" +
                "  - 第 2 周：积累核心词汇",
        )
        val r = f.service.breakdownGoal("学会英语", "半年内流利")
        assertEquals(1, r.stages.size)
        assertEquals("阶段一：入门 (第 1-4 周)", r.stages[0].title)
        assertEquals("打下基础", r.stages[0].description)
        assertEquals("能日常对话", r.stages[0].metric)
        assertEquals(2, r.stages[0].weeklyGoals.size)
        assertEquals(1, f.messages.inserted.size)
        assertEquals("breakdown", f.messages.inserted[0].mode)
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    private fun assertAdvisorError(t: Throwable, expectedCode: String) {
        val f = Fixture(transport = FakeTransport(throwable = t))
        val e = assertThrows(AiException::class.java) { f.service.askAdvisor("我该不该换工作") }
        assertEquals(expectedCode, e.code)
        assertEquals(0, f.messages.inserted.size)
    }

    private fun assertAdvisorHttpError(t: HttpTransportException, expectedCode: String) =
        assertAdvisorError(t, expectedCode)

    /** OpenAI 兼容响应外壳：{"choices":[{"message":{"content": ...}}]} */
    private fun wrap(content: String): String = buildJsonObject {
        putJsonArray("choices") {
            addJsonObject {
                putJsonObject("message") { put("content", content) }
            }
        }
    }.toString()

    private fun AiMessage.fullTextIsNonEmpty(): Boolean = answer.isNotBlank()
}

// ----------------------------------------------------------------------
// 假实现
// ----------------------------------------------------------------------

private class FakeTransport(
    var response: String = "",
    var throwable: Throwable? = null,
) : HttpTransport {
    var calls = 0
    val bodies = mutableListOf<String>()

    override fun postJson(url: String, bearerToken: String, body: String, timeoutMs: Long): String {
        calls++
        bodies.add(body)
        throwable?.let { throw it }
        return response
    }
}

private class FakeProfileRepo(var profile: Profile?) : ProfileRepository {
    override fun get(): Profile? = profile
    override fun save(profile: Profile): Profile {
        this.profile = profile
        return profile
    }
}

private class FakeSkillRepo(private val items: MutableList<Skill> = mutableListOf()) : SkillRepository {
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

private class FakeGoalRepo(private val items: MutableList<Goal> = mutableListOf()) : GoalRepository {
    override fun list(includeDeleted: Boolean): List<Goal> =
        if (includeDeleted) items.toList() else items.filter { it.deletedAt == null }

    override fun findById(id: Long, includeDeleted: Boolean): Goal? =
        items.firstOrNull { it.id == id && (includeDeleted || it.deletedAt == null) }

    override fun insert(goal: Goal): Long {
        items.add(goal)
        return goal.id ?: items.size.toLong()
    }

    override fun update(goal: Goal) {}
    override fun softDeleteSubtree(id: Long, deletedAtIso: String): Int = 0
    override fun replaceAll(goals: List<Goal>) {
        items.clear()
        items.addAll(goals)
    }
}

private class FakeTaskRepo : TaskRepository {
    val inserted = mutableListOf<Task>()

    override fun listByDate(date: LocalDate): List<Task> = inserted.filter { it.date == date }
    override fun listByGoal(goalId: Long): List<Task> = inserted.filter { it.goalId == goalId }
    override fun insert(task: Task): Long {
        inserted.add(task)
        return inserted.size.toLong()
    }

    override fun setDone(id: Long, done: Boolean, doneAtIso: String?) {}
    override fun delete(id: Long) {}
    override fun countDoneSince(goalId: Long, since: LocalDate): Int = 0
    override fun listAll(): List<Task> = inserted.toList()
    override fun replaceAll(tasks: List<Task>) {
        inserted.clear()
        inserted.addAll(tasks)
    }
}

private class FakeLogRepo(private val logs: MutableList<DailyLog> = mutableListOf()) : DailyLogRepository {
    override fun findByDate(date: LocalDate): DailyLog? = logs.firstOrNull { it.date == date }

    override fun listBetween(from: LocalDate, to: LocalDate): List<DailyLog> =
        logs.filter { it.date >= from && it.date <= to }.sortedBy { it.date }

    override fun upsert(log: DailyLog): DailyLog {
        val idx = logs.indexOfFirst { it.date == log.date }
        if (idx >= 0) logs[idx] = log else logs.add(log)
        return log
    }

    override fun listAll(): List<DailyLog> = logs.toList()

    override fun replaceAll(logs: List<DailyLog>) {
        this.logs.clear()
        this.logs.addAll(logs)
    }
}

private class FakeAiMessageRepo : AiMessageRepository {
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

private class Fixture(
    val transport: FakeTransport = FakeTransport(),
    config: AiConfig = AiConfig(apiKey = "test-key", baseUrl = "https://example.test/v1", model = "test-model"),
    profile: Profile? = null,
    skills: List<Skill> = emptyList(),
    goals: List<Goal> = emptyList(),
    val tasks: FakeTaskRepo = FakeTaskRepo(),
    logs: List<DailyLog> = emptyList(),
    val messages: FakeAiMessageRepo = FakeAiMessageRepo(),
) {
    val goalsRepo = FakeGoalRepo(goals.toMutableList())
    val service = AiService(
        transport = transport,
        config = config,
        profileRepo = FakeProfileRepo(profile),
        skillRepo = FakeSkillRepo(skills.toMutableList()),
        goalRepo = goalsRepo,
        taskRepo = tasks,
        logRepo = FakeLogRepo(logs.toMutableList()),
        aiMessageRepo = messages,
    )
}
