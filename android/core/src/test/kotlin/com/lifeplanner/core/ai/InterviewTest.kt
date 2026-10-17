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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 建档访谈（interviewTurn / saveInterview）的纯 JVM 单元测试。
 * 传输与仓储全部手写假实现，重点断言：
 * - 逐轮补全时模型返回的 null 不覆盖已知道的信息；越界值不写入
 * - finished 与 question 冲突时以 finished 为准；结束时产出草稿且只写一条历史
 * - 危机短路完全离线、fields 原样返回
 * - 落库只合并 Profile（含 healthInfo 固定键）与去重后的目标，不写任务
 */
class InterviewTest {

    private val today = LocalDate(2026, 10, 17)

    private val normalJson = """
        {
          "reply": "听起来你最近挺忙的。",
          "question": "那你现在主要做什么工作？",
          "finished": false,
          "collected": {"age": null, "occupation": "产品经理", "income": "月薪 1.2 万", "unexpected": "忽略"}
        }
    """.trimIndent()

    private val finishedJson = """
        {
          "reply": "谢谢你愿意聊这些。",
          "question": "还需要补充什么吗？",
          "finished": true,
          "collected": {
            "age": 28,
            "occupation": "产品经理",
            "income": "月薪 1.2 万",
            "desired_state": "想有更多自主时间",
            "future_self": "成为能独立带团队的负责人"
          },
          "profile": {
            "age": "不是数字",
            "gender": "男",
            "occupation": "产品经理",
            "industry": "互联网",
            "years_of_work": 6,
            "education": "本科",
            "major": "计算机"
          },
          "vision": "三年内成为独立负责人",
          "constraints": "每周加班较多",
          "summary": "你在互联网做产品，渴望自主与成长。",
          "goals": [
            {"title": "成为产品负责人", "level": "long", "category": "职业", "metric": "独立负责一条业务线"},
            {"title": "稳定作息", "level": "monthly", "category": "健康", "metric": ""},
            {"title": "   ", "level": "long", "category": "学习"},
            {"title": "第四目标", "level": "weekly", "category": "学习"},
            {"title": "第五目标", "level": "weekly", "category": "学习"}
          ]
        }
    """.trimIndent()

    // ------------------------------------------------------------------
    // interviewTurn：逐轮补全
    // ------------------------------------------------------------------

    @Test
    fun 逐轮补全时null不覆盖已知道字段且中途不写历史() {
        val f = InterviewFixture()
        f.transport.response = wrap(normalJson)
        val base = InterviewFields(age = 30, futureSelf = "成为独立负责人")

        val turn = f.service.interviewTurn(
            listOf(InterviewMessage(true, "最近在带一个项目，有点累")),
            base,
            today,
        )

        assertFalse(turn.finished)
        assertNull(turn.draft)
        assertEquals("那你现在主要做什么工作？", turn.question)
        assertTrue(turn.reply.isNotBlank())
        assertEquals(AiSources.AI_MODEL, turn.source)
        assertEquals("test-model", turn.modelName)
        // 用户之前说过的不能丢
        assertEquals(30, turn.fields.age)
        assertEquals("成为独立负责人", turn.fields.futureSelf)
        // 本轮新增的写入
        assertEquals("产品经理", turn.fields.occupation)
        assertEquals("月薪 1.2 万", turn.fields.income)
        // 未结束不写历史
        assertEquals(1, f.transport.calls)
        assertEquals(0, f.messages.inserted.size)
    }

    @Test
    fun 越界数值与空白文本不覆盖原有信息() {
        val f = InterviewFixture()
        f.transport.response = wrap(
            """{"reply":"嗯。","question":"继续说说？","finished":false,""" +
                """"collected":{"age":121,"years_of_work":71,"gender":"   ","income":"  "}}""",
        )
        val base = InterviewFields(age = 30, yearsOfWork = 6, gender = "女", income = "月薪 8 千")

        val turn = f.service.interviewTurn(listOf(InterviewMessage(true, "嗯")), base, today)

        assertEquals(30, turn.fields.age)
        assertEquals(6, turn.fields.yearsOfWork)
        assertEquals("女", turn.fields.gender)
        assertEquals("月薪 8 千", turn.fields.income)
    }

    @Test
    fun 超长文本按上限截断而不整段丢弃() {
        val f = InterviewFixture()
        val longState = "很".repeat(250)
        val longIncome = "收".repeat(61)
        f.transport.response = wrap(
            """{"reply":"嗯。","question":"继续说说？","finished":false,""" +
                """"collected":{"current_state":"$longState","income":"$longIncome"}}""",
        )

        val turn = f.service.interviewTurn(listOf(InterviewMessage(true, "嗯")), InterviewFields(), today)

        assertEquals(200, turn.fields.currentState?.length)
        assertEquals(60, turn.fields.income?.length)
    }

    @Test
    fun 提示词只带最近十二条对话且包含已了解字段() {
        val f = InterviewFixture()
        f.transport.response = wrap(normalJson)
        val history = ArrayList<InterviewMessage>()
        history += InterviewMessage(true, "最早的一句话，不应出现在提示词里")
        for (i in 1..19) history += InterviewMessage(i % 2 == 0, "消息$i")

        f.service.interviewTurn(history, InterviewFields(occupation = "产品经理"), today)

        val body = f.transport.bodies.last()
        assertFalse(body.contains("最早的一句话"))
        assertTrue(body.contains("消息19"))
        assertTrue(body.contains("- 职业: 产品经理"))
    }

    // ------------------------------------------------------------------
    // interviewTurn：结束
    // ------------------------------------------------------------------

    @Test
    fun 结束回合以finished为准并解析出草稿() {
        val f = InterviewFixture()
        f.transport.response = wrap(finishedJson)
        val history = listOf(InterviewMessage(true, "我 28 岁，做产品经理"))

        val turn = f.service.interviewTurn(history, InterviewFields(), today)

        assertTrue(turn.finished)
        assertNull(turn.question) // 模型同时给了 question，以 finished 为准
        val draft = requireNotNull(turn.draft)
        assertEquals(turn.fields, draft.fields)
        assertEquals(28, draft.fields.age)          // profile 里的 age 非法，保留 collected 的 28
        assertEquals("男", draft.fields.gender)
        assertEquals("互联网", draft.fields.industry)
        assertEquals(6, draft.fields.yearsOfWork)
        assertEquals("本科", draft.fields.education)
        assertEquals("计算机", draft.fields.major)
        assertEquals("三年内成为独立负责人", draft.vision)
        assertEquals("每周加班较多", draft.constraints)
        assertEquals("你在互联网做产品，渴望自主与成长。", draft.summary)
        assertEquals("三年内成为独立负责人", draft.fields.vision)
        assertEquals("每周加班较多", draft.fields.constraints)

        assertEquals(3, draft.goals.size) // 空标题被丢、最多 3 个
        assertEquals("成为产品负责人", draft.goals[0].title)
        assertEquals("long", draft.goals[0].level)
        assertEquals("职业", draft.goals[0].category)
        assertEquals("独立负责一条业务线", draft.goals[0].metric)
        // 非法层级回落到 long；空字符串 metric 视为没提到（细节见下一个用例）
        assertEquals("long", draft.goals[1].level)
    }

    @Test
    fun 空标题目标被丢弃与非法层级分类回落() {
        val f = InterviewFixture()
        f.transport.response = wrap(finishedJson)

        val turn = f.service.interviewTurn(listOf(InterviewMessage(true, "好")), InterviewFields(), today)

        val draft = requireNotNull(turn.draft)
        assertFalse(draft.goals.any { it.title.isBlank() })
        assertEquals("long", draft.goals[1].level) // monthly -> long
        assertEquals("健康", draft.goals[1].category)
        assertNull(draft.goals[1].metric)
    }

    @Test
    fun 结束回合只写一条历史且把草稿写进可读全文() {
        val f = InterviewFixture()
        f.transport.response = wrap(finishedJson)
        val history = listOf(
            InterviewMessage(true, "我 28 岁，做产品经理"),
            InterviewMessage(false, "谢谢你愿意聊这些。\n那你现在主要做什么工作？"),
            InterviewMessage(true, "产品经理，互联网"),
        )

        f.service.interviewTurn(history, InterviewFields(age = 28), today)

        assertEquals(1, f.messages.inserted.size)
        val saved = f.messages.inserted[0]
        assertEquals("interview", saved.mode)
        assertEquals(AiSources.AI_MODEL, saved.source)
        assertEquals("test-model", saved.modelName)
        // question 存用户的第一句，方便回看起点
        assertEquals("我 28 岁，做产品经理", saved.question)
        assertTrue(saved.answer.contains("【愿景】"))
        assertTrue(saved.answer.contains("三年内成为独立负责人"))
        assertTrue(saved.answer.contains("【现实约束】"))
        assertTrue(saved.answer.contains("【建议目标（待用户确认）】"))
        assertTrue(saved.answer.contains("成为产品负责人"))
    }

    @Test
    fun finished缺失抛响应格式错误() {
        val f = InterviewFixture()
        f.transport.response = wrap("""{"reply":"好","question":"你做什么工作？","collected":{}}""")

        val e = assertThrows(AiException::class.java) {
            f.service.interviewTurn(listOf(InterviewMessage(true, "你好")), InterviewFields(), today)
        }

        assertEquals(AiErrorCodes.BAD_RESPONSE, e.code)
        assertEquals(0, f.messages.inserted.size)
    }

    @Test
    fun 未结束却没有下一个问题抛响应格式错误() {
        val f = InterviewFixture()
        f.transport.response = wrap("""{"reply":"好的，我了解了。","question":null,"finished":false,"collected":{}}""")

        val e = assertThrows(AiException::class.java) {
            f.service.interviewTurn(listOf(InterviewMessage(true, "嗯")), InterviewFields(), today)
        }

        assertEquals(AiErrorCodes.BAD_RESPONSE, e.code)
    }

    // ------------------------------------------------------------------
    // interviewTurn：危机 / 异常
    // ------------------------------------------------------------------

    @Test
    fun 危机命中时不联网且fields原样返回() {
        val f = InterviewFixture()
        val fields = InterviewFields(age = 30, occupation = "教师")
        val history = listOf(
            InterviewMessage(true, "最近状态还行"),
            InterviewMessage(false, "那最近最困扰你的是什么？"),
            InterviewMessage(true, "我最近真的很想死"),
        )

        val turn = f.service.interviewTurn(history, fields, today)

        assertTrue(turn.isCrisis)
        assertTrue(turn.finished)
        assertNull(turn.question)
        assertNull(turn.draft)
        assertEquals(fields, turn.fields)
        assertTrue(turn.reply.contains("生命"))
        assertEquals(0, f.transport.calls)
        assertEquals(1, f.messages.inserted.size)
        assertEquals("interview", f.messages.inserted[0].mode)
        assertEquals(AiSources.CRISIS, f.messages.inserted[0].source)
        assertEquals("最近状态还行", f.messages.inserted[0].question)
    }

    @Test
    fun 坏JSON抛响应格式错误且不写历史() {
        val f = InterviewFixture()
        f.transport.response = wrap("我们先随便聊聊吧，你最近怎么样？")

        val e = assertThrows(AiException::class.java) {
            f.service.interviewTurn(listOf(InterviewMessage(true, "你好")), InterviewFields(), today)
        }

        assertEquals(AiErrorCodes.BAD_RESPONSE, e.code)
        assertEquals(0, f.messages.inserted.size)
    }

    @Test
    fun 未配置时抛未配置且不联网() {
        val f = InterviewFixture(config = AiConfig(apiKey = "", baseUrl = "https://x/v1", model = "m"))

        val e = assertThrows(AiException::class.java) {
            f.service.interviewTurn(listOf(InterviewMessage(true, "你好")), InterviewFields(), today)
        }

        assertEquals(AiErrorCodes.NOT_CONFIGURED, e.code)
        assertEquals(0, f.transport.calls)
    }

    // ------------------------------------------------------------------
    // saveInterview
    // ------------------------------------------------------------------

    @Test
    fun 落库时合并档案并保留其它健康信息键() {
        val existing = Profile(
            id = 7,
            age = 30,
            occupation = "旧职业",
            vision = "旧愿景",
            healthInfo = mapOf("height" to "175", "interview_income" to "旧收入"),
        )
        val f = InterviewFixture(profile = existing)
        val draft = InterviewDraft(
            fields = InterviewFields(
                age = 28,
                gender = "男",
                occupation = "产品经理",
                industry = "互联网",
                yearsOfWork = 6,
                education = "本科",
                major = "计算机",
                income = "月薪 1.2 万",
                currentState = "很忙",
                desiredState = "更多自主",
                approach = "做副业",
                futureSelf = "独立负责人",
            ),
            vision = "三年内成为独立负责人",
            constraints = "每周加班",
            goals = emptyList(),
            summary = "总结",
        )

        val applied = f.service.saveInterview(draft, today)

        assertTrue(applied.profileSaved)
        assertEquals(0, applied.goalsInserted)
        val saved = requireNotNull(f.profileRepo.lastSaved)
        assertEquals(7L, saved.id)
        assertEquals(28, saved.age)
        assertEquals("男", saved.gender)
        assertEquals("产品经理", saved.occupation)
        assertEquals("互联网", saved.industry)
        assertEquals(6, saved.yearsOfWork)
        assertEquals("本科", saved.education)
        assertEquals("计算机", saved.major)
        assertEquals("三年内成为独立负责人", saved.vision)
        assertEquals("每周加班", saved.constraints)
        assertNotNull(saved.updatedAt)
        // 其它 healthInfo 键原样保留，访谈字段进固定键
        assertEquals("175", saved.healthInfo["height"])
        assertEquals("月薪 1.2 万", saved.healthInfo["interview_income"])
        assertEquals("很忙", saved.healthInfo["interview_current_state"])
        assertEquals("更多自主", saved.healthInfo["interview_desired_state"])
        assertEquals("做副业", saved.healthInfo["interview_approach"])
        assertEquals("独立负责人", saved.healthInfo["interview_future_self"])
        // 访谈不写任务
        assertTrue(f.tasksRepo.inserted.isEmpty())
    }

    @Test
    fun 落库时空字段不覆盖既有档案() {
        val existing = Profile(
            id = 1,
            age = 30,
            occupation = "教师",
            vision = "旧愿景",
            healthInfo = mapOf("interview_income" to "月薪 8 千", "height" to "175"),
        )
        val f = InterviewFixture(profile = existing)
        val draft = InterviewDraft(
            fields = InterviewFields(gender = "女"),
            vision = "",
            constraints = "",
            goals = emptyList(),
            summary = "",
        )

        val applied = f.service.saveInterview(draft, today)

        assertTrue(applied.profileSaved)
        val saved = requireNotNull(f.profileRepo.lastSaved)
        assertEquals(30, saved.age)                       // null 保留原值
        assertEquals("教师", saved.occupation)
        assertEquals("女", saved.gender)
        assertEquals("旧愿景", saved.vision)               // 空 vision 不覆盖
        assertEquals("月薪 8 千", saved.healthInfo["interview_income"])
        assertEquals("175", saved.healthInfo["height"])
    }

    @Test
    fun 落库时目标按标题去重且最多三个() {
        val f = InterviewFixture(goals = listOf(Goal(id = 1, title = "成为产品负责人")))
        val draft = InterviewDraft(
            fields = InterviewFields(),
            vision = "",
            constraints = "",
            summary = "",
            goals = listOf(
                InterviewGoalDraft(title = "成为产品负责人", level = "long", category = "职业"),
                InterviewGoalDraft(title = "稳定作息", level = "weekly", category = "健康", metric = "每天 23 点前睡"),
                InterviewGoalDraft(title = "稳定作息", level = "weekly", category = "健康"),
                InterviewGoalDraft(title = "写出第一本书", level = "monthly", category = "瞎写"),
                InterviewGoalDraft(title = "练出腹肌", level = "stage", category = "健康"),
            ),
        )

        val applied = f.service.saveInterview(draft, today)

        assertFalse(applied.profileSaved) // 只有目标，没有档案字段
        assertEquals(3, applied.goalsInserted)
        assertEquals(3, f.goalsRepo.insertedGoals.size)
        assertEquals(listOf("稳定作息", "写出第一本书", "练出腹肌"), f.goalsRepo.insertedGoals.map { it.title })
        val second = f.goalsRepo.insertedGoals[1]
        assertEquals("long", second.level)      // 非法层级回落 long
        assertEquals("职业", second.category)   // 非法分类回落职业
        assertEquals(today, second.startDate)
        assertEquals(GoalStatuses.IN_PROGRESS, second.status)
        assertEquals(0, second.progress)
        assertNull(f.goalsRepo.insertedGoals[0].description)
        assertTrue(f.tasksRepo.inserted.isEmpty())
    }

    @Test
    fun 空草稿不落库() {
        val f = InterviewFixture()
        val draft = InterviewDraft(
            fields = InterviewFields(),
            vision = "",
            constraints = "",
            goals = emptyList(),
            summary = "",
        )

        val applied = f.service.saveInterview(draft, today)

        assertFalse(applied.profileSaved)
        assertEquals(0, applied.goalsInserted)
        assertNull(f.profileRepo.lastSaved)
        assertTrue(f.goalsRepo.insertedGoals.isEmpty())
        assertTrue(f.tasksRepo.inserted.isEmpty())
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

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
// 假实现（名字加 Interview 前缀，避免与其它测试文件的文件级私有假实现重名）
// ----------------------------------------------------------------------

private class InterviewFakeTransport(
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

private class InterviewFakeProfileRepo(initial: Profile?) : ProfileRepository {
    private var current: Profile? = initial

    /** 最后一次真正写入的档案，供断言；没写过就是 null（区别于初始档案）。 */
    var lastSaved: Profile? = null
        private set

    override fun get(): Profile? = current

    override fun save(profile: Profile): Profile {
        current = profile
        lastSaved = profile
        return profile
    }
}

private class InterviewFakeSkillRepo : SkillRepository {
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

private class InterviewFakeGoalRepo(items: List<Goal> = emptyList()) : GoalRepository {
    private val rows = items.toMutableList()
    val insertedGoals = mutableListOf<Goal>()
    private var nextId = (rows.mapNotNull { it.id }.maxOrNull() ?: 0L) + 1

    override fun list(includeDeleted: Boolean): List<Goal> =
        if (includeDeleted) rows.toList() else rows.filter { it.deletedAt == null }

    override fun findById(id: Long, includeDeleted: Boolean): Goal? =
        rows.firstOrNull { it.id == id && (includeDeleted || it.deletedAt == null) }

    override fun insert(goal: Goal): Long {
        val id = goal.id ?: nextId++
        val saved = goal.copy(id = id)
        rows.add(saved)
        insertedGoals.add(saved)
        return id
    }

    override fun update(goal: Goal) {}
    override fun softDeleteSubtree(id: Long, deletedAtIso: String): Int = 0
    override fun replaceAll(goals: List<Goal>) {
        rows.clear()
        rows.addAll(goals)
    }
}

private class InterviewFakeTaskRepo : TaskRepository {
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

private class InterviewFakeLogRepo : DailyLogRepository {
    private val rows = mutableListOf<DailyLog>()

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

private class InterviewFakeMessageRepo : AiMessageRepository {
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

private class InterviewFixture(
    val transport: InterviewFakeTransport = InterviewFakeTransport(),
    config: AiConfig = AiConfig(apiKey = "test-key", baseUrl = "https://example.test/v1", model = "test-model"),
    profile: Profile? = null,
    goals: List<Goal> = emptyList(),
) {
    val profileRepo = InterviewFakeProfileRepo(profile)
    val goalsRepo = InterviewFakeGoalRepo(goals)
    val tasksRepo = InterviewFakeTaskRepo()
    val messages = InterviewFakeMessageRepo()

    val service = AiService(
        transport = transport,
        config = config,
        profileRepo = profileRepo,
        skillRepo = InterviewFakeSkillRepo(),
        goalRepo = goalsRepo,
        taskRepo = tasksRepo,
        logRepo = InterviewFakeLogRepo(),
        aiMessageRepo = messages,
    )
}
