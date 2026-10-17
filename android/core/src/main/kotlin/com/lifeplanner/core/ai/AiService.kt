package com.lifeplanner.core.ai

import com.lifeplanner.core.model.AiMessage
import com.lifeplanner.core.model.AiSources
import com.lifeplanner.core.model.DailyLog
import com.lifeplanner.core.model.Goal
import com.lifeplanner.core.model.GoalLevels
import com.lifeplanner.core.model.GoalStatuses
import com.lifeplanner.core.model.Profile
import com.lifeplanner.core.model.Task
import com.lifeplanner.core.port.AiMessageRepository
import com.lifeplanner.core.port.DailyLogRepository
import com.lifeplanner.core.port.GoalRepository
import com.lifeplanner.core.port.HttpTransport
import com.lifeplanner.core.port.HttpTransportException
import com.lifeplanner.core.port.ProfileRepository
import com.lifeplanner.core.port.SkillRepository
import com.lifeplanner.core.port.TaskRepository
import com.lifeplanner.core.util.Dates
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * AI 层入口：顾问 / 每日规划 / 复盘 / 拆解四个模式。
 *
 * 统一流程：先本地危机筛查（命中即短路、不发网络请求、返回关怀文案），
 * 再检查配置，然后调用传输、解析校验、写一条历史。失败时零副作用（不写任务、不写历史）。
 * 所有提示词与校验规则与 v1 ai_agent.py 的语义对齐；v2 不做任何降级模板。
 */
class AiService(
    private val transport: HttpTransport,
    private val config: AiConfig,
    private val profileRepo: ProfileRepository,
    private val skillRepo: SkillRepository,
    private val goalRepo: GoalRepository,
    private val taskRepo: TaskRepository,
    private val logRepo: DailyLogRepository,
    private val aiMessageRepo: AiMessageRepository,
) {

    // ------------------------------------------------------------------
    // 四个模式入口
    // ------------------------------------------------------------------

    fun askAdvisor(question: String, today: LocalDate = Dates.today()): AdvisorResult {
        // 1. 危机筛查：命中即短路，永远成功
        CrisisGuard.check(question)?.let { crisis ->
            val full = CrisisGuard.fullText(crisis)
            saveMessage("advisor", question, full, AiSources.CRISIS, null)
            return AdvisorResult(
                conclusion = crisis.conclusion,
                reason = crisis.reason,
                pros = crisis.pros,
                consRisks = crisis.consRisks,
                alternative = crisis.alternative,
                goalRelation = crisis.goalRelation,
                isCrisis = true,
                source = AiSources.CRISIS,
                modelName = null,
                fullText = full,
                crisis = crisis,
            )
        }

        ensureConfigured()
        val context = buildContext(today)
        val prompt = Prompts.advisorPrompt(context, question)
        val data = JsonExtract.extractObject(callModel(prompt, 0.3))

        val conclusion = JsonExtract.requireStringField(data, "conclusion")
        val reason = JsonExtract.requireStringField(data, "reason")
        val pros = JsonExtract.requireStringField(data, "pros")
        val consRisks = JsonExtract.requireStringField(data, "cons_risks")
        val alternative = JsonExtract.requireStringField(data, "alternative")
        val goalRelation = JsonExtract.requireStringField(data, "goal_relation")
        // 不在三选一之内就是模型违约：按 bad_response 处理，不悄悄纠正成某个值
        if (conclusion !in ALLOWED_CONCLUSIONS) throw badResponse()

        val fullText = sixSection(conclusion, reason, pros, consRisks, alternative, goalRelation)
        saveMessage("advisor", question, fullText, AiSources.AI_MODEL, config.model)
        return AdvisorResult(
            conclusion = conclusion,
            reason = reason,
            pros = pros,
            consRisks = consRisks,
            alternative = alternative,
            goalRelation = goalRelation,
            isCrisis = false,
            source = AiSources.AI_MODEL,
            modelName = config.model,
            fullText = fullText,
        )
    }

    fun generateDailyPlan(autoApply: Boolean, today: LocalDate = Dates.today()): DailyPlanResult {
        // 近 3 天日志里出现危机表述 -> 停止规划
        CrisisGuard.scanLogs(logRepo.listBetween(Dates.minusDays(today, 2), today))?.let { crisis ->
            val full = CrisisGuard.fullText(crisis)
            saveMessage("daily_plan", "生成今日规划", full, AiSources.CRISIS, null)
            return DailyPlanResult(
                tasks = emptyList(),
                healthReminder = crisis.alternative,
                tip = crisis.goalRelation,
                isCrisis = true,
                source = AiSources.CRISIS,
                modelName = null,
                crisis = crisis,
            )
        }

        ensureConfigured()
        val context = buildContext(today)
        val goalMap = activeGoals().mapNotNull { g -> g.id?.let { it to g } }.toMap()
        val prompt = Prompts.dailyPlanPrompt(context)
        val data = JsonExtract.extractObject(callModel(prompt, 0.4))

        val healthReminder = JsonExtract.requireStringField(data, "health_reminder")
        val tip = JsonExtract.requireStringField(data, "tip")

        val rawTasks = data["tasks"]
        if (rawTasks !is List<*> || rawTasks.isEmpty()) throw badResponse()
        val tasks = ArrayList<PlannedTask>(rawTasks.size)
        for (item in rawTasks) {
            val m = JsonExtract.asMap(item) ?: throw badResponse()
            val titleRaw = m["title"]
            if (titleRaw !is String || titleRaw.isBlank()) throw badResponse()
            val title = titleRaw.trim().take(200) // tasks.title 上限 200
            val minutes = JsonExtract.asLongOrNull(m["est_minutes"])?.toInt()
                ?.takeIf { it in 1..1440 } ?: 30
            val gid = JsonExtract.asLongOrNull(m["goal_id"])
            val goal = gid?.let { goalMap[it] } // 不属于进行中目标 -> 置 null
            val goalName = goal?.title ?: (m["goal_name"] as? String ?: "")
            tasks.add(PlannedTask(title = title, goalId = goal?.id, goalName = goalName, estMinutes = minutes))
        }

        // 校验全部通过后才写库
        if (autoApply) {
            val seen = HashSet<String>()
            for (t in tasks) {
                if (!seen.add(t.title)) continue
                val exists = taskRepo.listByDate(today).any { it.title == t.title }
                if (!exists) {
                    taskRepo.insert(
                        Task(goalId = t.goalId, date = today, title = t.title, estMinutes = t.estMinutes, done = false),
                    )
                }
            }
        }

        val result = DailyPlanResult(
            tasks = tasks,
            healthReminder = healthReminder,
            tip = tip,
            isCrisis = false,
            source = AiSources.AI_MODEL,
            modelName = config.model,
        )
        saveMessage("daily_plan", "生成今日规划", dailyPlanAnswerJson(tasks, healthReminder, tip), AiSources.AI_MODEL, config.model)
        return result
    }

    fun generateReview(type: String, date: LocalDate = Dates.today()): ReviewResult {
        val isDaily = type == "daily"
        val question = "$type review ($date)"

        // 危机筛查：每日看当日日志，每周看近 7 天
        val crisis = if (isDaily) {
            CrisisGuard.scanLogs(listOfNotNull(logRepo.findByDate(date)))
        } else {
            CrisisGuard.scanLogs(logRepo.listBetween(Dates.minusDays(date, 6), date))
        }
        if (crisis != null) {
            val full = CrisisGuard.fullText(crisis)
            saveMessage("review", question, full, AiSources.CRISIS, null)
            return ReviewResult(
                type = type,
                title = crisis.conclusion,
                summary = crisis.reason,
                highlights = crisis.pros,
                deviations = crisis.consRisks,
                nextActions = crisis.alternative,
                goalProgress = crisis.goalRelation,
                biggestDeviation = crisis.consRisks,
                nextWeekStrategy = crisis.alternative,
                progressSuggestions = emptyList(),
                isCrisis = true,
                source = AiSources.CRISIS,
                modelName = null,
                fullText = full,
                crisis = crisis,
            )
        }

        ensureConfigured()
        val context = buildContext(date)
        return if (isDaily) dailyReview(context, date, question) else weeklyReview(context, date, question)
    }

    fun breakdownGoal(goalTitle: String, goalDesc: String?): BreakdownResult {
        ensureConfigured()
        val context = buildContext(Dates.today())
        val prompt = Prompts.breakdownPrompt(context, goalTitle, goalDesc)
        val content = stripOuterFence(callModel(prompt, 0.3))
        val stages = if (content.isBlank()) emptyList() else parseBreakdownMarkdown(content)
        // 拆不出任何阶段就无法进入“确认并落库”环节，等同于模型未按约定结构返回
        if (content.isBlank() || stages.isEmpty()) throw badResponse()

        saveMessage("breakdown", goalTitle, content, AiSources.AI_MODEL, config.model)
        return BreakdownResult(
            plan = content,
            stages = stages,
            goalTitle = goalTitle,
            source = AiSources.AI_MODEL,
            modelName = config.model,
        )
    }

    // ------------------------------------------------------------------
    // 一句话整理
    // ------------------------------------------------------------------

    /**
     * 把用户的一句话整理成“待确认”的结构化草稿。
     *
     * 流程与其它模式一致：本地危机筛查（命中即完全离线短路）→ 配置检查 → 调模型 → 严格校验。
     * 关键约束：**这里只读不写业务库**。用户确认前绝不产生任务 / 目标 / 日志，
     * 唯一的写入是一条 mode="capture" 的历史，便于之后回看 AI 当时整理了什么。
     */
    fun organizeCapture(rawInput: String, today: LocalDate = Dates.today()): CaptureResult {
        // 危机优先于一切：命中后不联网、不整理，直接给出关怀与求助渠道（宁多勿漏，见 CrisisGuard）
        CrisisGuard.check(rawInput)?.let { crisis ->
            val full = CrisisGuard.fullText(crisis)
            saveMessage("capture", rawInput, full, AiSources.CRISIS, null)
            return CaptureResult(
                reply = full,
                log = null,
                isCrisis = true,
                source = AiSources.CRISIS,
                modelName = null,
                fullText = full,
                crisis = crisis,
            )
        }

        ensureConfigured()
        val context = buildContext(today)
        val prompt = CapturePrompts.capturePrompt(context, rawInput, today)
        val data = JsonExtract.extractObject(callModel(prompt, 0.3))

        // reply 是用户唯一必读字段：缺了它这次整理就没有交付物，按模型违约处理，不拿空串糊弄
        val reply = JsonExtract.requireStringField(data, "reply")
        // goal_id 只认“进行中且未删除”的真实目标，避免把任务挂到不存在/已删除的目标上
        val goalMap = activeGoals().mapNotNull { g -> g.id?.let { it to g } }.toMap()
        val log = parseCaptureLog(data["log"])
        val tasks = parseCaptureTasks(data["tasks"], goalMap)
        val goals = parseCaptureGoals(data["goals"])

        val fullText = captureFullText(reply, log, tasks, goals)
        saveMessage("capture", rawInput, fullText, AiSources.AI_MODEL, config.model)
        return CaptureResult(
            reply = reply,
            log = log,
            tasks = tasks,
            goals = goals,
            isCrisis = false,
            source = AiSources.AI_MODEL,
            modelName = config.model,
            fullText = fullText,
        )
    }

    /**
     * 用户确认后把草稿合并落库，返回**真实写入**的数量（没有真的插入就不计数）。
     *
     * 合并口径（与“用户没说就不动”一致）：
     * - 日志：草稿里 null 的字段保留数据库原值；meals 按 key 合并；同一天仍是一条记录。
     * - 任务：同一天按标题去重，已存在的不重复插入；goalId 不存在则置 null。
     * - 目标：与未删除目标按标题去重；新建的一律进行中、进度 0。
     *
     * 危机结果与空草稿不写库：危机时用户需要的是求助信息，自动落库反而是打扰。
     * 分步写入，不保证事务：中途失败可能已写入了部分数据，调用方应提示用户重试前先刷新。
     */
    fun applyCapture(result: CaptureResult, today: LocalDate = Dates.today()): CaptureApplied {
        if (result.isCrisis) return CaptureApplied()

        var logSaved = false
        result.log?.takeIf { !it.isEmpty }?.let { draft ->
            logRepo.upsert(mergeDailyLog(logRepo.findByDate(today), draft, today))
            logSaved = true
        }

        // 用同一个 Set 同时承担“草稿内部去重”和“跳过当天已有任务”，避免漏掉任一种重复
        val knownTaskTitles = taskRepo.listByDate(today).mapTo(HashSet()) { it.title }
        var tasksInserted = 0
        for (draft in result.tasks) {
            // applyCapture 是公开入口，可能被界面用手工草稿调用，所以这里再兜一次底
            val title = draft.title.trim().take(MAX_TASK_TITLE)
            if (title.isEmpty() || !knownTaskTitles.add(title)) continue
            val goalId = draft.goalId?.takeIf { goalRepo.findById(it) != null }
            val minutes = draft.estMinutes.takeIf { it in 1..MAX_MINUTES } ?: DEFAULT_TASK_MINUTES
            taskRepo.insert(
                Task(goalId = goalId, date = today, title = title, estMinutes = minutes, done = false),
            )
            tasksInserted++
        }

        val knownGoalTitles = goalRepo.list().mapTo(HashSet()) { it.title }
        var goalsInserted = 0
        for (draft in result.goals) {
            val title = draft.title.trim().take(MAX_GOAL_TITLE)
            if (title.isEmpty() || !knownGoalTitles.add(title)) continue
            goalRepo.insert(
                Goal(
                    level = normalizeGoalLevel(draft.level),
                    title = title,
                    category = normalizeGoalCategory(draft.category),
                    description = draft.description?.trim()?.takeIf { it.isNotEmpty() },
                    metric = draft.metric?.trim()?.takeIf { it.isNotEmpty() },
                    // 开始日就是用户确认新建的今天：这是真实事实，不是补出来的数据
                    startDate = today,
                    status = GoalStatuses.IN_PROGRESS,
                ),
            )
            goalsInserted++
        }

        return CaptureApplied(tasksInserted = tasksInserted, goalsInserted = goalsInserted, logSaved = logSaved)
    }

    // ------------------------------------------------------------------
    // 一句话整理的解析与校验
    // ------------------------------------------------------------------

    /**
     * 记录草稿：整块缺失 / 不是对象 / 校验后全空都返回 null。
     * 全空返回 null 是为了让界面直接隐藏“记录”区块，而不是展示一张空表。
     */
    private fun parseCaptureLog(raw: Any?): CaptureLogDraft? {
        val m = JsonExtract.asMap(raw) ?: return null
        val draft = CaptureLogDraft(
            studyMinutes = captureMinutes(m["study_minutes"]),
            studyNote = captureText(m["study_note"]),
            sleepStart = captureClock(m["sleep_start"]),
            sleepEnd = captureClock(m["sleep_end"]),
            exerciseType = captureText(m["exercise_type"]),
            exerciseMinutes = captureMinutes(m["exercise_minutes"]),
            mood = captureScore(m["mood"]),
            energy = captureScore(m["energy"]),
            meals = captureMeals(m["meals"]),
            doneItems = captureText(m["done_items"]),
            reflection = captureText(m["reflection"]),
        )
        return draft.takeIf { !it.isEmpty }
    }

    /** 任务草稿：标题非空、≤200 字、同标题去重、最多 6 个；越界时长回落 30 分钟。 */
    private fun parseCaptureTasks(raw: Any?, goalMap: Map<Long, Goal>): List<CaptureTaskDraft> {
        val items = raw as? List<*> ?: return emptyList()
        val out = ArrayList<CaptureTaskDraft>(minOf(items.size, CapturePrompts.MAX_TASKS))
        val seen = HashSet<String>()
        for (item in items) {
            if (out.size >= CapturePrompts.MAX_TASKS) break
            val m = JsonExtract.asMap(item) ?: continue
            val title = captureText(m["title"])?.take(MAX_TASK_TITLE) ?: continue
            // 模型常把同一件事换个说法拆两遍；重复插入会让今日清单出现两条一模一样的任务
            if (!seen.add(title)) continue
            val goal = JsonExtract.asLongOrNull(m["goal_id"])?.let { goalMap[it] }
            val minutes = JsonExtract.asLongOrNull(m["est_minutes"])?.toInt()
                ?.takeIf { it in 1..MAX_MINUTES } ?: DEFAULT_TASK_MINUTES
            out += CaptureTaskDraft(
                title = title,
                estMinutes = minutes,
                goalId = goal?.id,
                goalName = goal?.title ?: "",
            )
        }
        return out
    }

    /** 目标草稿：标题非空、≤100 字、最多 2 个；非法层级/分类回落到 weekly / 生活。 */
    private fun parseCaptureGoals(raw: Any?): List<CaptureGoalDraft> {
        val items = raw as? List<*> ?: return emptyList()
        val out = ArrayList<CaptureGoalDraft>(CapturePrompts.MAX_GOALS)
        for (item in items) {
            if (out.size >= CapturePrompts.MAX_GOALS) break
            val m = JsonExtract.asMap(item) ?: continue
            val title = captureText(m["title"])?.take(MAX_GOAL_TITLE) ?: continue
            out += CaptureGoalDraft(
                title = title,
                level = normalizeGoalLevel(m["level"] as? String),
                category = normalizeGoalCategory(m["category"] as? String),
                description = captureText(m["description"]),
                metric = captureText(m["metric"]),
            )
        }
        return out
    }

    /** 0..1440 的整数分钟；越界 / 非整数 / 非数字字符串一律视为“没提到”。 */
    private fun captureMinutes(raw: Any?): Int? =
        JsonExtract.asLongOrNull(raw)?.toInt()?.takeIf { it in 0..MAX_MINUTES }

    /** 心情/精力 1..5；越界视为没提到（宁可空着，也不替用户猜一个分数）。 */
    private fun captureScore(raw: Any?): Int? =
        JsonExtract.asLongOrNull(raw)?.toInt()?.takeIf { it in 1..5 }

    /** 自由文本：去空白后为空则视为没提到。 */
    private fun captureText(raw: Any?): String? = (raw as? String)?.trim()?.takeIf { it.isNotEmpty() }

    /** 时间只接受 24 小时制 HH:MM；“7:5”“晚上11点”这类模糊值一律置 null，不猜。 */
    private fun captureClock(raw: Any?): String? {
        val s = (raw as? String)?.trim() ?: return null
        return s.takeIf { CLOCK_RE.matches(it) }
    }

    /** 只保留契约约定的四个餐次：模型自造 key（如“夜宵”）会污染记录展示与统计。 */
    private fun captureMeals(raw: Any?): Map<String, String> {
        val m = JsonExtract.asMap(raw) ?: return emptyMap()
        val out = LinkedHashMap<String, String>(MEAL_KEYS.size)
        for (key in MEAL_KEYS) {
            val value = captureText(m[key]) ?: continue
            out[key] = value
        }
        return out
    }

    /** 层级不在 GoalLevels.RANK 内就回落 weekly：契约默认值，也是用户最常新建的层级。 */
    private fun normalizeGoalLevel(raw: String?): String {
        val level = raw?.trim()?.lowercase() ?: return GoalLevels.WEEKLY
        return if (GoalLevels.rankOf(level) != null) level else GoalLevels.WEEKLY
    }

    /** 分类不在白名单内回落“生活”：宁可粗一点，也不让模型自造的分类散落到目标列表里。 */
    private fun normalizeGoalCategory(raw: String?): String {
        val category = raw?.trim() ?: return DEFAULT_GOAL_CATEGORY
        return if (category in CapturePrompts.GOAL_CATEGORIES) category else DEFAULT_GOAL_CATEGORY
    }

    /** 日志合并：草稿非 null 的字段覆盖，null 保留数据库原值；meals 按 key 合并。 */
    private fun mergeDailyLog(existing: DailyLog?, draft: CaptureLogDraft, today: LocalDate): DailyLog {
        val base = existing ?: DailyLog(date = today)
        return base.copy(
            studyMinutes = draft.studyMinutes ?: base.studyMinutes,
            studyNote = draft.studyNote ?: base.studyNote,
            sleepStart = draft.sleepStart ?: base.sleepStart,
            sleepEnd = draft.sleepEnd ?: base.sleepEnd,
            exerciseType = draft.exerciseType ?: base.exerciseType,
            exerciseMinutes = draft.exerciseMinutes ?: base.exerciseMinutes,
            mood = draft.mood ?: base.mood,
            energy = draft.energy ?: base.energy,
            meals = if (draft.meals.isEmpty()) base.meals else base.meals + draft.meals,
            doneItems = draft.doneItems ?: base.doneItems,
            reflection = draft.reflection ?: base.reflection,
        )
    }

    /** 历史记录里保存的可读全文：用户回看时能一眼看出 AI 当时整理出了哪些草稿。 */
    private fun captureFullText(
        reply: String,
        log: CaptureLogDraft?,
        tasks: List<CaptureTaskDraft>,
        goals: List<CaptureGoalDraft>,
    ): String = buildString {
        append("【回应】: ").append(reply)
        if (log != null) {
            val lines = ArrayList<String>()
            if (log.studyMinutes != null || log.studyNote != null) {
                val parts = ArrayList<String>(2)
                log.studyMinutes?.let { parts += "$it 分钟" }
                log.studyNote?.let { parts += it }
                lines += "- 学习: " + parts.joinToString("，")
            }
            if (log.sleepStart != null || log.sleepEnd != null) {
                lines += "- 睡眠: " + (log.sleepStart ?: "?") + "-" + (log.sleepEnd ?: "?")
            }
            if (log.exerciseType != null || log.exerciseMinutes != null) {
                lines += "- 运动: " + (log.exerciseType ?: "运动") + " " + (log.exerciseMinutes ?: 0) + " 分钟"
            }
            if (log.mood != null || log.energy != null) {
                lines += "- 心情/精力: " + (log.mood ?: "?") + "/5, " + (log.energy ?: "?") + "/5"
            }
            if (log.meals.isNotEmpty()) {
                lines += "- 饮食: " + log.meals.entries.joinToString("，") { "${it.key}:${it.value}" }
            }
            log.doneItems?.let { lines += "- 产出: $it" }
            log.reflection?.let { lines += "- 反思: $it" }
            if (lines.isNotEmpty()) {
                append("\n\n【整理出的今日记录（待确认）】\n")
                append(lines.joinToString("\n"))
            }
        }
        if (tasks.isNotEmpty()) {
            append("\n\n【整理出的任务（待确认）】\n")
            append(tasks.joinToString("\n") { "- ${it.title}（${it.estMinutes} 分钟）" })
        }
        if (goals.isNotEmpty()) {
            append("\n\n【整理出的新目标（待确认）】\n")
            append(goals.joinToString("\n") { "- ${it.title}（${it.level} / ${it.category}）" })
        }
    }

    // ------------------------------------------------------------------
    // AI 建档访谈
    // ------------------------------------------------------------------

    /**
     * 推进一轮访谈：把用户最新一句交给模型，返回「回应 + 下一个问题 + 更新后的已知信息」。
     *
     * 与其它模式一致：危机优先本地短路（不联网、fields 原样返回）。
     * 整个访谈只在**结束时**写一条 mode="interview" 历史，中途不写——每轮都记一条
     * 只会把历史刷成一堆碎片，用户回看时反而找不到重点。
     * 模型返回的 null 一律不覆盖已经知道的字段：用户说过的话不能因为模型这轮没提就丢掉。
     */
    fun interviewTurn(
        history: List<InterviewMessage>,
        fields: InterviewFields,
        today: LocalDate = Dates.today(),
    ): InterviewTurn {
        val lastUserText = history.lastOrNull { it.fromUser }?.text
        CrisisGuard.check(lastUserText)?.let { crisis ->
            val full = CrisisGuard.fullText(crisis)
            saveMessage("interview", interviewQuestionOf(history), full, AiSources.CRISIS, null)
            return InterviewTurn(
                reply = full,
                question = null,
                fields = fields,
                finished = true,
                isCrisis = true,
                crisis = crisis,
                source = AiSources.CRISIS,
                modelName = null,
                fullText = full,
            )
        }

        ensureConfigured()
        val prompt = InterviewPrompts.interviewPrompt(fields, history, today)
        val data = JsonExtract.extractObject(callModel(prompt, 0.3))

        val reply = (data["reply"] as? String)?.trim().orEmpty()
        val finishedRaw = data["finished"]
        // finished 决定界面是继续问还是收尾，缺了它整轮都无法解释，只能算模型违约
        if (finishedRaw !is Boolean) throw badResponse()
        var finished = finishedRaw
        var question = interviewText(data["question"], MAX_INTERVIEW_TEXT)
        // 收尾时模型偶尔还会顺手给一个问题：以 finished 为准，问题置 null
        if (finished) question = null
        // 既没有回应、也没有下一个问题：这一轮什么都没交付
        if (reply.isBlank() && question == null) throw badResponse()
        // 未结束却没有下一个问题：界面会卡在一个没有问题的访谈里，同样视为违约
        if (!finished && question == null) throw badResponse()

        val collected = mergeInterviewFields(fields, JsonExtract.asMap(data["collected"]))
        var turnFields = collected
        var draft: InterviewDraft? = null
        val fullText: String

        if (finished) {
            val withProfile = mergeInterviewProfile(collected, JsonExtract.asMap(data["profile"]))
            // 结束段的 vision/constraints 优先；模型没给才回落到本轮 collected 里已有的值
            val vision = interviewText(data["vision"], MAX_INTERVIEW_VISION) ?: withProfile.vision.orEmpty()
            val constraints = interviewText(data["constraints"], MAX_INTERVIEW_CONSTRAINTS) ?: withProfile.constraints.orEmpty()
            val summary = interviewText(data["summary"], MAX_INTERVIEW_SUMMARY).orEmpty()
            val goals = parseInterviewGoals(data["goals"])
            turnFields = withProfile.copy(
                vision = vision.takeIf { it.isNotBlank() } ?: withProfile.vision,
                constraints = constraints.takeIf { it.isNotBlank() } ?: withProfile.constraints,
            )
            draft = InterviewDraft(
                fields = turnFields,
                vision = vision,
                constraints = constraints,
                goals = goals,
                summary = summary,
            )
            fullText = interviewFullText(reply, draft)
            saveMessage("interview", interviewQuestionOf(history), fullText, AiSources.AI_MODEL, config.model)
        } else {
            fullText = interviewTurnText(reply, question)
        }

        return InterviewTurn(
            reply = reply,
            question = question,
            fields = turnFields,
            finished = finished,
            source = AiSources.AI_MODEL,
            modelName = config.model,
            fullText = fullText,
            draft = draft,
        )
    }

    /**
     * 用户确认后把访谈草稿写入档案。
     *
     * 只写 Profile 与 Goal：访谈是建档，不是执行，不碰任务、不改已有目标进度。
     * 合并口径与“用户没说就不动”一致：草稿里为空的值保留现有档案；
     * 收入/当前状态/想达到的状态/方式/未来自我没有独立字段，放进 healthInfo 的固定键。
     */
    fun saveInterview(draft: InterviewDraft, today: LocalDate = Dates.today()): InterviewApplied {
        val fields = draft.fields
        val vision = draft.vision.trim().ifEmpty { fields.vision.orEmpty().trim() }
        val constraints = draft.constraints.trim().ifEmpty { fields.constraints.orEmpty().trim() }
        val hasProfileData = fields.age != null || fields.yearsOfWork != null ||
            listOf(
                fields.gender, fields.occupation, fields.industry, fields.education, fields.major,
                fields.income, fields.currentState, fields.desiredState, fields.approach,
                fields.futureSelf, fields.constraints,
            ).any { !it.isNullOrBlank() } ||
            vision.isNotBlank() || constraints.isNotBlank()

        var profileSaved = false
        if (hasProfileData) {
            val current = profileRepo.get()
            val health = LinkedHashMap(current?.healthInfo ?: emptyMap())
            interviewText(fields.income, MAX_INTERVIEW_INCOME)?.let { health[KEY_INTERVIEW_INCOME] = it }
            interviewText(fields.currentState, MAX_INTERVIEW_TEXT)?.let { health[KEY_INTERVIEW_CURRENT_STATE] = it }
            interviewText(fields.desiredState, MAX_INTERVIEW_TEXT)?.let { health[KEY_INTERVIEW_DESIRED_STATE] = it }
            interviewText(fields.approach, MAX_INTERVIEW_TEXT)?.let { health[KEY_INTERVIEW_APPROACH] = it }
            interviewText(fields.futureSelf, MAX_INTERVIEW_TEXT)?.let { health[KEY_INTERVIEW_FUTURE_SELF] = it }
            profileRepo.save(
                Profile(
                    id = current?.id,
                    age = fields.age?.takeIf { it in 1..120 } ?: current?.age,
                    gender = interviewText(fields.gender, MAX_INTERVIEW_TEXT) ?: current?.gender,
                    occupation = interviewText(fields.occupation, MAX_INTERVIEW_OCCUPATION) ?: current?.occupation,
                    industry = interviewText(fields.industry, MAX_INTERVIEW_OCCUPATION) ?: current?.industry,
                    yearsOfWork = fields.yearsOfWork?.takeIf { it in 0..70 } ?: current?.yearsOfWork,
                    education = interviewText(fields.education, MAX_INTERVIEW_TEXT) ?: current?.education,
                    major = interviewText(fields.major, MAX_INTERVIEW_TEXT) ?: current?.major,
                    healthInfo = health,
                    vision = vision.takeIf { it.isNotEmpty() } ?: current?.vision,
                    constraints = constraints.takeIf { it.isNotEmpty() } ?: current?.constraints,
                    updatedAt = Dates.nowIso(),
                ),
            )
            profileSaved = true
        }

        // 与“一句话整理”同口径：按标题去重（草稿内部 + 已有目标），最多 3 个
        val knownGoalTitles = goalRepo.list().mapTo(HashSet()) { it.title }
        var goalsInserted = 0
        for (draftGoal in draft.goals) {
            if (goalsInserted >= InterviewPrompts.MAX_GOALS) break
            val title = draftGoal.title.trim().take(MAX_GOAL_TITLE)
            if (title.isEmpty() || !knownGoalTitles.add(title)) continue
            goalRepo.insert(
                Goal(
                    level = normalizeInterviewGoalLevel(draftGoal.level),
                    title = title,
                    category = normalizeInterviewGoalCategory(draftGoal.category),
                    description = draftGoal.description?.trim()?.takeIf { it.isNotEmpty() },
                    metric = draftGoal.metric?.trim()?.takeIf { it.isNotEmpty() },
                    startDate = today,
                    status = GoalStatuses.IN_PROGRESS,
                ),
            )
            goalsInserted++
        }

        return InterviewApplied(profileSaved = profileSaved, goalsInserted = goalsInserted)
    }

    // ------------------------------------------------------------------
    // AI 建档访谈：解析与校验
    // ------------------------------------------------------------------

    /** 历史记录里用用户的第一句作为“问题”，方便回看这段访谈是从哪开始的。 */
    private fun interviewQuestionOf(history: List<InterviewMessage>): String? =
        history.firstOrNull { it.fromUser }?.text?.takeIf { it.isNotBlank() }

    /** 访谈文本：trim 后为空视为没提到；超长截断（不因一句啰嗦话丢掉整轮信息）。 */
    private fun interviewText(raw: Any?, limit: Int): String? {
        val s = (raw as? String)?.trim() ?: return null
        return if (s.isEmpty()) null else s.take(limit)
    }

    /** 年龄 1..120；越界/非整数视为“没提到”，保留传入的原值。 */
    private fun interviewAge(raw: Any?): Int? =
        JsonExtract.asLongOrNull(raw)?.toInt()?.takeIf { it in 1..120 }

    /** 工作年限 0..70；越界/非整数视为“没提到”。 */
    private fun interviewYears(raw: Any?): Int? =
        JsonExtract.asLongOrNull(raw)?.toInt()?.takeIf { it in 0..70 }

    /** collected 合并：模型给的值通过校验才写入，null/非法值一律保留 base 里的已知值。 */
    private fun mergeInterviewFields(base: InterviewFields, raw: Map<String, Any?>?): InterviewFields {
        if (raw == null) return base
        return base.copy(
            age = interviewAge(raw["age"]) ?: base.age,
            gender = interviewText(raw["gender"], MAX_INTERVIEW_TEXT) ?: base.gender,
            occupation = interviewText(raw["occupation"], MAX_INTERVIEW_TEXT) ?: base.occupation,
            industry = interviewText(raw["industry"], MAX_INTERVIEW_TEXT) ?: base.industry,
            yearsOfWork = interviewYears(raw["years_of_work"]) ?: base.yearsOfWork,
            education = interviewText(raw["education"], MAX_INTERVIEW_TEXT) ?: base.education,
            major = interviewText(raw["major"], MAX_INTERVIEW_TEXT) ?: base.major,
            income = interviewText(raw["income"], MAX_INTERVIEW_INCOME) ?: base.income,
            currentState = interviewText(raw["current_state"], MAX_INTERVIEW_TEXT) ?: base.currentState,
            desiredState = interviewText(raw["desired_state"], MAX_INTERVIEW_TEXT) ?: base.desiredState,
            approach = interviewText(raw["approach"], MAX_INTERVIEW_TEXT) ?: base.approach,
            futureSelf = interviewText(raw["future_self"], MAX_INTERVIEW_TEXT) ?: base.futureSelf,
            constraints = interviewText(raw["constraints"], MAX_INTERVIEW_TEXT) ?: base.constraints,
            vision = interviewText(raw["vision"], MAX_INTERVIEW_TEXT) ?: base.vision,
        )
    }

    /**
     * 结束段的 profile：只覆盖七个基础字段，职业/行业上限 100（与档案展示口径一致）。
     * 这里仍然“非空才覆盖”，避免收尾时模型把前面聊到的信息洗掉。
     */
    private fun mergeInterviewProfile(base: InterviewFields, raw: Map<String, Any?>?): InterviewFields {
        if (raw == null) return base
        return base.copy(
            age = interviewAge(raw["age"]) ?: base.age,
            gender = interviewText(raw["gender"], MAX_INTERVIEW_TEXT) ?: base.gender,
            occupation = interviewText(raw["occupation"], MAX_INTERVIEW_OCCUPATION) ?: base.occupation,
            industry = interviewText(raw["industry"], MAX_INTERVIEW_OCCUPATION) ?: base.industry,
            yearsOfWork = interviewYears(raw["years_of_work"]) ?: base.yearsOfWork,
            education = interviewText(raw["education"], MAX_INTERVIEW_TEXT) ?: base.education,
            major = interviewText(raw["major"], MAX_INTERVIEW_TEXT) ?: base.major,
        )
    }

    /** 建议目标：标题非空 ≤100、层级白名单否则 long、分类白名单否则职业、最多 3 个。 */
    private fun parseInterviewGoals(raw: Any?): List<InterviewGoalDraft> {
        val items = raw as? List<*> ?: return emptyList()
        val out = ArrayList<InterviewGoalDraft>(InterviewPrompts.MAX_GOALS)
        for (item in items) {
            if (out.size >= InterviewPrompts.MAX_GOALS) break
            val m = JsonExtract.asMap(item) ?: continue
            val title = interviewText(m["title"], MAX_GOAL_TITLE) ?: continue
            out += InterviewGoalDraft(
                title = title,
                level = normalizeInterviewGoalLevel(m["level"] as? String),
                category = normalizeInterviewGoalCategory(m["category"] as? String),
                metric = interviewText(m["metric"], MAX_INTERVIEW_TEXT),
                description = interviewText(m["description"], MAX_INTERVIEW_TEXT),
            )
        }
        return out
    }

    private fun normalizeInterviewGoalLevel(raw: String?): String {
        val level = raw?.trim()?.lowercase() ?: return GoalLevels.LONG
        return if (GoalLevels.rankOf(level) != null) level else GoalLevels.LONG
    }

    private fun normalizeInterviewGoalCategory(raw: String?): String {
        val category = raw?.trim() ?: return DEFAULT_INTERVIEW_GOAL_CATEGORY
        return if (category in InterviewPrompts.GOAL_CATEGORIES) category else DEFAULT_INTERVIEW_GOAL_CATEGORY
    }

    /** 未结束回合的可读文本（不写历史，仅随结果返回给界面/日志）。 */
    private fun interviewTurnText(reply: String, question: String?): String {
        val parts = ArrayList<String>(2)
        if (reply.isNotBlank()) parts += "【回应】: $reply"
        if (question != null) parts += "【下一个问题】: $question"
        return parts.joinToString("\n\n")
    }

    /** 结束回合的可读全文：历史里保存的是“这个人被整理成了什么”，而不是原始 JSON。 */
    private fun interviewFullText(reply: String, draft: InterviewDraft): String = buildString {
        append("【访谈完成】")
        if (reply.isNotBlank()) append("\n\n【回应】: ").append(reply)
        if (draft.summary.isNotBlank()) append("\n\n【总结】: ").append(draft.summary)
        if (draft.vision.isNotBlank()) append("\n\n【愿景】: ").append(draft.vision)
        if (draft.constraints.isNotBlank()) append("\n\n【现实约束】: ").append(draft.constraints)
        val known = interviewFieldsLines(draft.fields)
        if (known.isNotEmpty()) {
            append("\n\n【已了解】\n")
            append(known.joinToString("\n"))
        }
        if (draft.goals.isNotEmpty()) {
            append("\n\n【建议目标（待用户确认）】\n")
            append(draft.goals.joinToString("\n") { "- ${it.title}（${it.level} / ${it.category}）" })
        }
    }

    /** 已了解字段的可读清单（愿景/约束单独展示，不在这里重复）。 */
    private fun interviewFieldsLines(fields: InterviewFields): List<String> {
        val lines = ArrayList<String>()
        fields.age?.let { lines += "- 年龄: $it" }
        fields.gender?.let { lines += "- 性别: $it" }
        fields.occupation?.let { lines += "- 职业: $it" }
        fields.industry?.let { lines += "- 行业: $it" }
        fields.yearsOfWork?.let { lines += "- 工作年限: $it 年" }
        fields.education?.let { lines += "- 学历: $it" }
        fields.major?.let { lines += "- 专业: $it" }
        fields.income?.let { lines += "- 收入: $it" }
        fields.currentState?.let { lines += "- 当前状态: $it" }
        fields.desiredState?.let { lines += "- 想达到的状态: $it" }
        fields.approach?.let { lines += "- 打算的方式: $it" }
        fields.futureSelf?.let { lines += "- 未来想成为: $it" }
        return lines
    }

    // ------------------------------------------------------------------
    // 复盘分支
    // ------------------------------------------------------------------

    private fun dailyReview(context: String, date: LocalDate, question: String): ReviewResult {
        val todayTasks = taskRepo.listByDate(date)
        val doneCount = todayTasks.count { it.done }
        val totalTasks = todayTasks.size
        val log: DailyLog? = logRepo.findByDate(date)

        val prompt = Prompts.dailyReviewPrompt(context, date, doneCount, totalTasks, log)
        val data = JsonExtract.extractObject(callModel(prompt, 0.3))
        val summary = JsonExtract.requireStringField(data, "summary")
        val highlights = JsonExtract.requireStringField(data, "highlights")
        val deviations = JsonExtract.requireStringField(data, "deviations")
        val nextActions = JsonExtract.requireStringField(data, "next_actions")

        val title = "$date 每日战术复盘"
        val fullText = "【$title】\n\n" + listOf(
            "【总结】: $summary",
            "【亮点】: $highlights",
            "【偏离】: $deviations",
            "【明日行动】: $nextActions",
        ).joinToString("\n\n")

        saveMessage("review", question, fullText, AiSources.AI_MODEL, config.model)
        return ReviewResult(
            type = "daily",
            title = title,
            summary = summary,
            highlights = highlights,
            deviations = deviations,
            nextActions = nextActions,
            isCrisis = false,
            source = AiSources.AI_MODEL,
            modelName = config.model,
            fullText = fullText,
        )
    }

    private fun weeklyReview(context: String, date: LocalDate, question: String): ReviewResult {
        val recentLogs = logRepo.listBetween(Dates.minusDays(date, 6), date)
        val goals = activeGoals()
        val goalMap = goals.mapNotNull { g -> g.id?.let { it to g } }.toMap()
        val totalStudy = recentLogs.sumOf { it.studyMinutes }
        val avgText = oneDecimal(totalStudy.toDouble() / maxOf(recentLogs.size, 1))
        val goalLines = if (goals.isEmpty()) {
            "  - （没有进行中的目标）"
        } else {
            goals.mapNotNull { g -> g.id?.let { "  - goal_id=$it | ${g.title} | 当前进度 ${g.progress}%" } }
                .joinToString("\n")
        }

        val prompt = Prompts.weeklyReviewPrompt(context, totalStudy, avgText, goals.size, recentLogs.size, goalLines)
        val data = JsonExtract.extractObject(callModel(prompt, 0.3))
        val summary = JsonExtract.requireStringField(data, "summary")
        val goalProgress = JsonExtract.requireStringField(data, "goal_progress")
        val biggestDeviation = JsonExtract.requireStringField(data, "biggest_deviation")
        val nextWeekStrategy = JsonExtract.requireStringField(data, "next_week_strategy")
        val suggestions = validateProgressSuggestions(data["progress_suggestions"], goalMap)

        val title = "本周战略复盘与目标推进报告"
        var fullText = "【$title】\n\n" + listOf(
            "【总结】: $summary",
            "【目标推进】: $goalProgress",
            "【最大偏差】: $biggestDeviation",
            "【下周策略】: $nextWeekStrategy",
        ).joinToString("\n\n")
        if (suggestions.isNotEmpty()) {
            fullText += "\n\n【进度建议（待用户确认，未写入）】\n" + suggestions.joinToString("\n") {
                "- ${it.title}: ${it.currentProgress}% -> ${it.suggestedProgress}%（${it.reason}）"
            }
        }

        saveMessage("review", question, fullText, AiSources.AI_MODEL, config.model)
        return ReviewResult(
            type = "weekly",
            title = title,
            summary = summary,
            goalProgress = goalProgress,
            biggestDeviation = biggestDeviation,
            nextWeekStrategy = nextWeekStrategy,
            progressSuggestions = suggestions,
            isCrisis = false,
            source = AiSources.AI_MODEL,
            modelName = config.model,
            fullText = fullText,
        )
    }

    /**
     * 只保留“有依据”的进度建议：目标必须存在（进行中且未删除）、去重、
     * suggested_progress 必须是 0-100 的 JSON 整数、reason 非空。
     * title / currentProgress 一律取数据库真实值；绝不写库。
     */
    private fun validateProgressSuggestions(raw: Any?, goals: Map<Long, Goal>): List<ProgressSuggestion> {
        if (raw !is List<*>) return emptyList()
        val out = ArrayList<ProgressSuggestion>()
        val seen = HashSet<Long>()
        for (item in raw) {
            val m = JsonExtract.asMap(item) ?: continue
            val gid = JsonExtract.asLongOrNull(m["goal_id"]) ?: continue
            val goal = goals[gid] ?: continue
            if (gid in seen) continue
            // 必须是 JSON 整数（字符串 "50"、小数、bool 一律丢弃，与 v1 isinstance(int) 口径一致）
            val spRaw = m["suggested_progress"]
            val sp: Long? = when (spRaw) {
                is Long -> spRaw
                is Int -> spRaw.toLong()
                else -> null
            }
            if (sp == null || sp < 0 || sp > 100) continue
            val reason = m["reason"]
            if (reason !is String || reason.isBlank()) continue
            seen.add(gid)
            out.add(
                ProgressSuggestion(
                    goalId = gid,
                    title = goal.title,
                    currentProgress = goal.progress,
                    suggestedProgress = sp.toInt(),
                    reason = reason.trim(),
                ),
            )
        }
        return out
    }

    // ------------------------------------------------------------------
    // 传输与解析
    // ------------------------------------------------------------------

    private fun callModel(prompt: String, temperature: Double): String {
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val body = buildJsonObject {
            put("model", config.model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", Prompts.systemPrompt())
                }
                addJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            }
            put("temperature", temperature)
        }.toString()

        val text = try {
            transport.postJson(url, config.apiKey, body, TIMEOUT_MS)
        } catch (e: HttpTransportException) {
            throw mapHttpError(e.statusCode)
        } catch (e: java.io.IOException) {
            throw AiException(AiErrorCodes.UNREACHABLE, MSG_UNREACHABLE)
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            // 非 SDK 异常（如 base_url 非法、底层传输异常）：对用户而言等同于连不上
            throw AiException(AiErrorCodes.UNREACHABLE, MSG_UNREACHABLE)
        }

        return extractContent(text)
    }

    /** 从 OpenAI 兼容响应里取 choices[0].message.content。 */
    private fun extractContent(text: String?): String {
        if (text.isNullOrBlank()) throw badResponse()
        val root = (try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw badResponse()
        }) as? JsonObject ?: throw badResponse()
        val content = (((root["choices"] as? JsonArray)?.getOrNull(0) as? JsonObject)
            ?.get("message") as? JsonObject)
            ?.get("content") as? JsonPrimitive
        val s = content?.takeIf { it.isString }?.content
        if (s.isNullOrBlank()) throw badResponse()
        return s.trim()
    }

    private fun mapHttpError(code: Int): AiException = when {
        code == 401 || code == 403 -> AiException(AiErrorCodes.AUTH_FAILED, MSG_AUTH)
        code == 429 -> AiException(AiErrorCodes.RATE_LIMITED, MSG_RATE)
        code in 400..499 -> AiException(AiErrorCodes.BAD_RESPONSE, "AI 服务拒绝了本次请求（HTTP $code），请检查模型名与 Base URL 配置。")
        code in 500..599 -> AiException(AiErrorCodes.UNREACHABLE, "AI 服务暂时不可用（HTTP $code），请稍后重试。")
        else -> AiException(AiErrorCodes.UNREACHABLE, MSG_UNREACHABLE)
    }

    // ------------------------------------------------------------------
    // 拆解 Markdown 解析（照 v1 goal_service.parse_breakdown_markdown）
    // ------------------------------------------------------------------

    private fun stripOuterFence(text: String): String {
        val t = text.trim()
        val m = OUTER_FENCE.find(t)
        return m?.groupValues?.get(1)?.trim() ?: t
    }

    private fun parseBreakdownMarkdown(text: String): List<BreakdownStage> {
        if (text.isBlank()) return emptyList()
        val matches = STAGE_SPLIT.findAll(text).toList()
        if (matches.isEmpty()) return emptyList()

        val stages = ArrayList<BreakdownStage>(matches.size)
        for (i in matches.indices) {
            val match = matches[i]
            val title = match.groupValues[1].trim(' ', '*', '#', ':')
            val bodyStart = match.range.last + 1
            val bodyEnd = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            val body = if (bodyStart < bodyEnd) text.substring(bodyStart, bodyEnd).trim() else ""

            val metric = METRIC_RE.find(body)?.groupValues?.get(1)?.trim(' ', '*', '`')
            var desc = DESC_RE.find(body)?.groupValues?.get(1)?.trim(' ', '*', '`')
            if (desc.isNullOrBlank()) {
                desc = body.split('\n')
                    .map { it.trim().trim(' ', '*', '-', '#') }
                    .firstOrNull { it.isNotEmpty() && !WEEKLY_LINE_RE.containsMatchIn(it) }
            }

            val weekly = ArrayList<BreakdownWeekly>()
            for (line in body.split('\n')) {
                val wm = WEEK_RE.find(line.trim()) ?: continue
                val wTitle = wm.groupValues[1].trim(' ', '*', '`')
                weekly.add(BreakdownWeekly(title = wTitle, description = wTitle))
            }

            stages.add(
                BreakdownStage(
                    title = title,
                    description = desc?.takeIf { it.isNotBlank() } ?: title,
                    metric = metric?.takeIf { it.isNotBlank() },
                    weeklyGoals = weekly,
                ),
            )
        }
        return stages
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private fun buildContext(today: LocalDate): String {
        val profile = profileRepo.get()
        val skills = skillRepo.list()
        val goals = activeGoals()
        val logs = logRepo.listBetween(Dates.minusDays(today, 6), today)
        return Prompts.buildContext(profile, skills, goals, logs)
    }

    /** 进行中且未软删除的目标（兜底再过滤一次）。 */
    private fun activeGoals(): List<Goal> =
        goalRepo.list().filter { it.status == GoalStatuses.IN_PROGRESS && it.deletedAt == null }

    private fun ensureConfigured() {
        if (!config.isConfigured) throw AiException(AiErrorCodes.NOT_CONFIGURED, MSG_NOT_CONFIGURED)
    }

    private fun saveMessage(mode: String, question: String?, answer: String, source: String, modelName: String?) {
        aiMessageRepo.insert(
            AiMessage(
                mode = mode,
                question = question,
                answer = answer,
                source = source,
                modelName = modelName,
                createdAt = Dates.nowIso(),
            ),
        )
    }

    private fun dailyPlanAnswerJson(tasks: List<PlannedTask>, healthReminder: String, tip: String): String =
        buildJsonObject {
            putJsonArray("tasks") {
                for (t in tasks) {
                    addJsonObject {
                        put("title", t.title)
                        if (t.goalId == null) put("goal_id", JsonNull) else put("goal_id", t.goalId)
                        put("goal_name", t.goalName)
                        put("est_minutes", t.estMinutes)
                    }
                }
            }
            put("health_reminder", healthReminder)
            put("tip", tip)
        }.toString()

    private fun sixSection(
        conclusion: String,
        reason: String,
        pros: String,
        consRisks: String,
        alternative: String,
        goalRelation: String,
    ): String = "【结论】: $conclusion\n\n【理由】: $reason\n\n【好处】: $pros\n\n【坏处与风险】: $consRisks\n\n" +
        "【折中优化方案】: $alternative\n\n【与目标的关系】: $goalRelation"

    /** 与 Python round(x, 1) 近似的显示：整数补 ".0"。 */
    private fun oneDecimal(v: Double): String {
        val r = kotlin.math.round(v * 10) / 10.0
        return if (r == r.toLong().toDouble()) "${r.toLong()}.0" else r.toString()
    }

    private fun badResponse(): AiException =
        AiException(AiErrorCodes.BAD_RESPONSE, "AI 返回的内容格式不符合要求，未能生成结果，请重试。")

    companion object {
        private const val TIMEOUT_MS = 60_000L
        private val ALLOWED_CONCLUSIONS = setOf("建议做", "不建议做", "可以但有条件")

        private const val MSG_NOT_CONFIGURED =
            "尚未配置 AI 服务：请先在设置里填写 API Key（并按需设置 Base URL 与模型名）。"
        private const val MSG_UNREACHABLE =
            "无法连接到 AI 服务（网络不通或请求超时），请检查网络与 Base URL 后重试。"
        private const val MSG_AUTH =
            "AI 服务拒绝了当前凭证（认证或权限失败），请检查 API Key 是否正确、是否有权调用所选模型。"
        private const val MSG_RATE =
            "AI 服务请求过于频繁或额度不足（HTTP 429），请稍后重试，或检查账户额度。"

        private val OUTER_FENCE = Regex(
            "^```[ \\t]*(?:markdown|md)?[ \\t]*\\r?\\n(.*?)\\r?\\n?```\\s*$",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val STAGE_SPLIT = Regex(
            "^(?:#{2,4}\\s*|\\*{2}\\s*|\\d+[\\.、]\\s*)(阶段[一二三四五六七八九十\\d]+[^\\n\\r]*)",
            RegexOption.MULTILINE,
        )
        private val METRIC_RE = Regex(
            "(?:\\*\\*检验标准[^*]*\\*\\*|检验标准|Metric|衡量标准)[*：:\\s]*([^\\n\\r]+)",
            RegexOption.IGNORE_CASE,
        )
        private val DESC_RE = Regex(
            "(?:\\*\\*阶段里程碑[^*]*\\*\\*|阶段里程碑|核心抓手|阶段目标)[*：:\\s]*([^\\n\\r]+)",
        )
        private val WEEK_RE = Regex("[-*•\\d\\.\\s\\[\\]]*(第\\s*[\\d\\-一二三四五六七八九十]+\\s*周[^\\n\\r]*)")
        private val WEEKLY_LINE_RE = Regex("第\\s*[\\d\\-一二三四五六七八九十]+\\s*周")
        private const val MAX_MINUTES = 1440
        private const val DEFAULT_TASK_MINUTES = 30
        private const val MAX_TASK_TITLE = 200
        private const val MAX_GOAL_TITLE = 100
        private const val DEFAULT_GOAL_CATEGORY = "生活"

        /** 睡眠时间格式：24 小时制 HH:MM（与任务书约定的校验正则完全一致）。 */
        private val CLOCK_RE = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

        /** 记录里合法的餐次 key，与 DailyLog.meals 的约定一致。 */
        private val MEAL_KEYS = listOf("breakfast", "lunch", "dinner", "snack")

        private const val MAX_INTERVIEW_TEXT = 200
        private const val MAX_INTERVIEW_INCOME = 60
        private const val MAX_INTERVIEW_OCCUPATION = 100
        private const val MAX_INTERVIEW_VISION = 300
        private const val MAX_INTERVIEW_CONSTRAINTS = 300
        private const val MAX_INTERVIEW_SUMMARY = 500
        private const val DEFAULT_INTERVIEW_GOAL_CATEGORY = "职业"

        /** 访谈里没有独立字段的信息，统一存进 healthInfo 的固定键（其余键原样保留）。 */
        private const val KEY_INTERVIEW_INCOME = "interview_income"
        private const val KEY_INTERVIEW_CURRENT_STATE = "interview_current_state"
        private const val KEY_INTERVIEW_DESIRED_STATE = "interview_desired_state"
        private const val KEY_INTERVIEW_APPROACH = "interview_approach"
        private const val KEY_INTERVIEW_FUTURE_SELF = "interview_future_self"
    }
}
