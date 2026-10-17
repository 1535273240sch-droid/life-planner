package com.lifeplanner.ai.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeplanner.ai.AppContainer
import com.lifeplanner.core.ai.AiErrorCodes
import com.lifeplanner.core.ai.AiException
import com.lifeplanner.core.ai.CaptureApplied
import com.lifeplanner.core.ai.CaptureResult
import com.lifeplanner.core.ai.DailyPlanResult
import com.lifeplanner.core.daily.DailyQuote
import com.lifeplanner.core.daily.DailyQuotes
import com.lifeplanner.core.model.LifeGap
import com.lifeplanner.core.model.Profile
import com.lifeplanner.core.model.StatsOverview
import com.lifeplanner.core.model.StatsTrends
import com.lifeplanner.core.model.Task
import com.lifeplanner.core.util.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** 今日任务 + 关联目标名（目标已删除时为空）。 */
data class DashboardTaskItem(
    val task: Task,
    val goalTitle: String?,
)

data class DashboardUiState(
    val loading: Boolean = true,
    /** 为 true 时表示数据已展示、正在后台刷新（用于轻量提示）。 */
    val refreshing: Boolean = false,
    val overview: StatsOverview? = null,
    /** 本次数据对应的日期（MM-DD），跨午夜刷新后同步更新标题。 */
    val todayLabel: String = "",
    val tasks: List<DashboardTaskItem> = emptyList(),
    val trendDays: Int = 7,
    val labels: List<String> = emptyList(),
    val studyMinutes: List<Int?> = emptyList(),
    val sleepHours: List<Double?> = emptyList(),
    val gaps: List<LifeGap> = emptyList(),
    val error: String? = null,
    // ---- 首页头部 / 每日一句：纯本地内容，不依赖网络，未配置 Key 也有内容 ----
    val greeting: String = "",
    val dateLabel: String = "",
    val quote: DailyQuote? = null,
    /** 档案里还没有年龄/职业/愿景：首页显示"先认识你"的访谈入口。 */
    val interviewNeeded: Boolean = false,
    /** 一句话档案摘要（28 岁 · 产品经理 · 月薪 1.2 万），用于访谈卡副标题。 */
    val profileSummary: String = "",
    // ---- AI 一句话整理：草稿先展示，用户勾选确认后才落库 ----
    val captureOpen: Boolean = false,
    val captureInput: String = "",
    val captureBusy: Boolean = false,
    val captureResult: CaptureResult? = null,
    /** 勾选要保存的任务下标（默认全选）。 */
    val captureSelectedTasks: Set<Int> = emptySet(),
    /** 勾选要保存的目标下标（默认全选）。 */
    val captureSelectedGoals: Set<Int> = emptySet(),
    val captureError: String? = null,
    /** 未配置 API Key：界面需要给出「去设置」入口。 */
    val captureNotConfigured: Boolean = false,
    val captureSaving: Boolean = false,
    /** 保存成功提示：由界面消费（Snackbar + 轻震动）后清空。 */
    val captureMessage: String? = null,
    // ---- AI 今日计划：生成结果先展示，用户确认后才写库 ----
    val planLoading: Boolean = false,
    val planResult: DailyPlanResult? = null,
    /** 勾选的任务下标（默认全部勾选）。 */
    val planSelected: Set<Int> = emptySet(),
    val planError: String? = null,
    val planNeedsConfig: Boolean = false,
    val planApplying: Boolean = false,
    val planMessage: String? = null,
)

class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(
        // 问候语 / 日期 / 每日一句都是纯本地内容：构造时就给值，首页首帧不会空着或闪一下。
        DashboardUiState(
            greeting = DailyQuotes.greeting(
                Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour,
            ),
            dateLabel = DailyQuotes.dateLabel(Dates.today()),
            quote = DailyQuotes.forDate(Dates.today()),
        ),
    )
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /** 当前已加载数据所属的日期；跨天后必须重取，否则首页会一直显示昨天的数字。 */
    private var loadedDate: kotlinx.datetime.LocalDate? = null

    /** 上次成功加载的时间戳，用于判断缓存是否新鲜。 */
    private var lastLoadedAtMs: Long = 0L

    /**
     * 首页重新可见时调用。
     *
     * 与旧实现的区别：不再每次 ON_RESUME 都整块重载——切换页面回来会命中当天缓存，
     * 只有跨天（数据已不属于今天）或缓存过期才真正取数，避免整屏闪 loading。
     * 需要强制刷新时用 [forceRefresh]（保存成功、勾选任务等本地写操作之后）。
     */
    fun refresh() {
        if (loadJob?.isActive == true) return
        val today = Dates.today()
        val hasData = _state.value.overview != null
        if (hasData && loadedDate == today &&
            System.currentTimeMillis() - lastLoadedAtMs < CACHE_REUSE_MS
        ) {
            // 数据属于今天且足够新：只刷新随时间变化的文案（问候语可能跨过整点），不重新取数。
            applyDateText(today)
            // 访谈保存/档案编辑后回到首页时，缓存不能挡住"是否需要访谈"的状态更新。
            refreshProfileInfo()
            return
        }
        // 已有内容时走静默刷新：保留旧内容，只在后台替换，避免闪屏。
        load(showFullLoading = !hasData)
    }

    /** 绕过缓存立即重取（本地写库之后用，保证首页与刚写入的数据一致）。 */
    fun forceRefresh() {
        load(showFullLoading = _state.value.overview == null)
    }

    /** 切换趋势窗口（7 / 30 天），随后重新取数。 */
    fun setTrendDays(days: Int) {
        if (days == _state.value.trendDays) return
        _state.update { it.copy(trendDays = days) }
        load(showFullLoading = false)
    }

    private fun load(showFullLoading: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = showFullLoading,
                    refreshing = !showFullLoading,
                    error = null,
                )
            }
            val days = _state.value.trendDays
            try {
                val today = Dates.today()
                val snapshot = withContext(Dispatchers.IO) {
                    // 每次刷新都重新取当天日期：应用在后台跨过午夜后，ON_RESUME 会拿到新的一天。
                    val overview = container.logService.overview(today)
                    val goalsById = container.goalRepository.list()
                        .mapNotNull { goal -> goal.id?.let { it to goal.title } }
                        .toMap()
                    val tasks = container.taskRepository.listByDate(today).map { task ->
                        DashboardTaskItem(
                            task = task,
                            goalTitle = task.goalId?.let { goalsById[it] },
                        )
                    }
                    val trends = container.logService.trends(days, today)
                    val gaps = container.logService.detectGaps(today)
                    val profile = container.profileRepository.get()
                    DashboardSnapshot(
                        overview = overview,
                        todayLabel = Dates.monthDay(today),
                        tasks = tasks,
                        trends = trends,
                        gaps = gaps,
                        interviewNeeded = profileNeedsInterview(profile),
                        profileSummary = profileSummaryOf(profile),
                    )
                }
                loadedDate = today
                lastLoadedAtMs = System.currentTimeMillis()
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        overview = snapshot.overview,
                        todayLabel = snapshot.todayLabel,
                        tasks = snapshot.tasks,
                        labels = snapshot.trends.labels,
                        studyMinutes = snapshot.trends.studyMinutes,
                        sleepHours = snapshot.trends.sleepHours,
                        gaps = snapshot.gaps,
                        error = null,
                        greeting = DailyQuotes.greeting(currentHour()),
                        dateLabel = DailyQuotes.dateLabel(today),
                        quote = DailyQuotes.forDate(today),
                        interviewNeeded = snapshot.interviewNeeded,
                        profileSummary = snapshot.profileSummary,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    // 静默刷新失败时保留旧内容，只把错误放在横幅上，不把页面清空。
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = e.message?.takeIf { msg -> msg.isNotBlank() }
                            ?: "加载失败：${e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    /** 只更新与"今天"有关、随本机时钟变化的文案。 */
    private fun applyDateText(today: kotlinx.datetime.LocalDate) {
        _state.update {
            it.copy(
                greeting = DailyQuotes.greeting(currentHour()),
                dateLabel = DailyQuotes.dateLabel(today),
                quote = DailyQuotes.forDate(today),
            )
        }
    }

    /**
     * 单独刷新档案摘要。
     *
     * 首页数据有 60s 缓存，但"是否已完成访谈"是会被其他页面写入的状态：
     * 从访谈页/档案页返回时必须能立刻看到变化，所以缓存命中时也做一次轻量读取。
     */
    private fun refreshProfileInfo() {
        viewModelScope.launch {
            try {
                val (needed, summary) = withContext(Dispatchers.IO) {
                    val profile = container.profileRepository.get()
                    profileNeedsInterview(profile) to profileSummaryOf(profile)
                }
                _state.update { it.copy(interviewNeeded = needed, profileSummary = summary) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 档案读取失败不该影响首页主体内容，保持旧值即可。
            }
        }
    }

    /** 愿景为空就还值得聊一次（访谈结束会写入 vision）；写过后就不在首页重复打扰。 */
    private fun profileNeedsInterview(profile: Profile?): Boolean =
        profile == null || profile.vision.isNullOrBlank()

    /** "28 岁 · 产品经理 · 月薪 1.2 万"；缺什么就跳过什么，不显示空档。 */
    private fun profileSummaryOf(profile: Profile?): String {
        if (profile == null) return ""
        val parts = ArrayList<String>(3)
        profile.age?.let { parts += it.toString() + " 岁" }
        profile.occupation?.takeIf { it.isNotBlank() }?.let { parts += it }
        profile.healthInfo[KEY_INTERVIEW_INCOME]?.takeIf { it.isNotBlank() }?.let { parts += it }
        return parts.joinToString(" · ")
    }

    /** 勾选 / 取消今日任务：完成后写入完成时间，取消时清空，然后刷新。 */
    fun setTaskDone(taskId: Long, done: Boolean) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    container.taskRepository.setDone(
                        id = taskId,
                        done = done,
                        doneAtIso = if (done) Dates.nowIso() else null,
                    )
                }
                // 本地写完必须重取，否则缓存会让勾选状态看起来没生效。
                forceRefresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        error = "更新任务失败：${
                            e.message?.takeIf { msg -> msg.isNotBlank() }
                                ?: e.javaClass.simpleName
                        }",
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // AI 一句话整理（弹窗）
    // ------------------------------------------------------------------

    fun openCapture() {
        _state.update {
            it.copy(captureOpen = true, captureError = null, captureNotConfigured = false)
        }
    }

    /** 关闭弹窗：保留输入与结果，用户误触关闭后重新打开不用重说一遍。 */
    fun closeCapture() {
        _state.update { it.copy(captureOpen = false) }
    }

    fun onCaptureInputChange(text: String) {
        // 不在这里偷偷截断：超长由界面禁用按钮并提示，用户知道自己写多了。
        _state.update { it.copy(captureInput = text, captureError = null) }
    }

    /** 调 AI 整理：只生成草稿，不写库。 */
    fun organizeCapture() {
        val current = _state.value
        if (current.captureBusy || current.captureSaving) return
        val input = current.captureInput.trim()
        if (input.isEmpty()) {
            _state.update { it.copy(captureError = "先说一句话吧") }
            return
        }
        if (input.length > MAX_CAPTURE_INPUT) {
            _state.update {
                it.copy(captureError = "太长了（${input.length} 字），请精简到 $MAX_CAPTURE_INPUT 字以内")
            }
            return
        }
        _state.update {
            it.copy(
                captureBusy = true,
                captureResult = null,
                captureSelectedTasks = emptySet(),
                captureSelectedGoals = emptySet(),
                captureError = null,
                captureNotConfigured = false,
                captureMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    // 每次重新读设置构造服务：用户刚在设置里填完 Key，回来就能用。
                    container.aiService().organizeCapture(input, Dates.today())
                }
                _state.update {
                    it.copy(
                        captureBusy = false,
                        captureResult = result,
                        captureSelectedTasks = result.tasks.indices.toSet(),
                        captureSelectedGoals = result.goals.indices.toSet(),
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AiException) {
                _state.update {
                    it.copy(
                        captureBusy = false,
                        captureError = e.message,
                        captureNotConfigured = e.code == AiErrorCodes.NOT_CONFIGURED,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        captureBusy = false,
                        captureError = "整理失败：${
                            e.message?.takeIf { msg -> msg.isNotBlank() }
                                ?: e.javaClass.simpleName
                        }",
                    )
                }
            }
        }
    }

    fun toggleCaptureTask(index: Int) {
        _state.update { current ->
            if (index !in (current.captureResult?.tasks?.indices ?: IntRange.EMPTY)) return@update current
            val next = current.captureSelectedTasks.toMutableSet()
            if (!next.add(index)) next.remove(index)
            current.copy(captureSelectedTasks = next)
        }
    }

    fun toggleCaptureGoal(index: Int) {
        _state.update { current ->
            if (index !in (current.captureResult?.goals?.indices ?: IntRange.EMPTY)) return@update current
            val next = current.captureSelectedGoals.toMutableSet()
            if (!next.add(index)) next.remove(index)
            current.copy(captureSelectedGoals = next)
        }
    }

    /** 重新整理：清掉当前草稿，保留用户原话。 */
    fun resetCapture() {
        _state.update {
            it.copy(
                captureBusy = false,
                captureSaving = false,
                captureResult = null,
                captureSelectedTasks = emptySet(),
                captureSelectedGoals = emptySet(),
                captureError = null,
                captureNotConfigured = false,
                captureMessage = null,
            )
        }
    }

    /**
     * 保存勾选内容。
     *
     * 先在本地按勾选过滤 [CaptureResult]，再交给 core 的 applyCapture 落库：
     * 未勾选的任务/目标绝不能因为"AI 生成过"就被写进数据库。
     */
    fun saveCapture() {
        val current = _state.value
        val result = current.captureResult ?: return
        if (current.captureBusy || current.captureSaving) return
        if (result.isCrisis) {
            // 危机短路返回的是安全关怀文案，不是待落库草稿。
            _state.update { it.copy(captureError = "这次没有需要保存的内容，先照顾好自己。") }
            return
        }
        val tasks = result.tasks.filterIndexed { index, _ -> index in current.captureSelectedTasks }
        val goals = result.goals.filterIndexed { index, _ -> index in current.captureSelectedGoals }
        val log = result.log?.takeUnless { it.isEmpty }
        if (tasks.isEmpty() && goals.isEmpty() && log == null) {
            _state.update {
                it.copy(
                    captureError = if (current.captureSelectedTasks.isEmpty() && current.captureSelectedGoals.isEmpty()) {
                        "还没有勾选任何内容，请先勾选任务或目标"
                    } else {
                        "这次没有可保存的内容，可以重新整理一次"
                    },
                )
            }
            return
        }

        _state.update { it.copy(captureSaving = true, captureError = null, captureMessage = null) }
        viewModelScope.launch {
            try {
                val applied: CaptureApplied = withContext(Dispatchers.IO) {
                    container.aiService().applyCapture(
                        result = result.copy(tasks = tasks, goals = goals, log = log),
                        today = Dates.today(),
                    )
                }
                _state.update {
                    it.copy(
                        captureSaving = false,
                        captureOpen = false,
                        captureInput = "",
                        captureResult = null,
                        captureSelectedTasks = emptySet(),
                        captureSelectedGoals = emptySet(),
                        captureMessage = describeApplied(applied),
                    )
                }
                // 刚写入任务/目标/记录：强刷首页，让用户马上看到结果。
                forceRefresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AiException) {
                _state.update {
                    it.copy(
                        captureSaving = false,
                        captureError = e.message,
                        captureNotConfigured = e.code == AiErrorCodes.NOT_CONFIGURED,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        captureSaving = false,
                        captureError = "保存失败：${
                            e.message?.takeIf { msg -> msg.isNotBlank() }
                                ?: e.javaClass.simpleName
                        }",
                    )
                }
            }
        }
    }

    /** 界面消费完 Snackbar 提示后调用，避免旋转屏幕时重复弹。 */
    fun consumeCaptureMessage() {
        _state.update { it.copy(captureMessage = null) }
    }

    private fun describeApplied(applied: CaptureApplied): String {
        val parts = ArrayList<String>(3)
        if (applied.tasksInserted > 0) parts.add("${applied.tasksInserted} 项任务")
        if (applied.goalsInserted > 0) parts.add("${applied.goalsInserted} 个目标")
        if (applied.logSaved) parts.add("今日记录")
        return if (parts.isEmpty()) {
            "没有新增内容：同名任务或目标可能已存在"
        } else {
            "已整理并保存：${parts.joinToString("、")}"
        }
    }

    // ------------------------------------------------------------------
    // AI 今日计划
    // ------------------------------------------------------------------

    /**
     * AI 生成今日计划（只生成、不落库）。
     * 结果先放在界面里由用户勾选确认，确认前绝不写任务表。
     */
    fun generateDailyPlan() {
        if (_state.value.planLoading || _state.value.planApplying) return
        _state.update {
            it.copy(
                planLoading = true,
                planResult = null,
                planSelected = emptySet(),
                planError = null,
                planNeedsConfig = false,
                planMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    container.aiService().generateDailyPlan(
                        autoApply = false,
                        today = Dates.today(),
                    )
                }
                _state.update {
                    it.copy(
                        planLoading = false,
                        planResult = result,
                        planSelected = result.tasks.indices.toSet(),
                        planError = null,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AiException) {
                _state.update {
                    it.copy(
                        planLoading = false,
                        planError = e.message,
                        planNeedsConfig = e.code == AiErrorCodes.NOT_CONFIGURED,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        planLoading = false,
                        planError = "生成今日计划失败：${
                            e.message?.takeIf { msg -> msg.isNotBlank() }
                                ?: e.javaClass.simpleName
                        }",
                    )
                }
            }
        }
    }

    /** 勾选 / 取消勾选一条 AI 计划任务。 */
    fun togglePlannedTask(index: Int) {
        _state.update { current ->
            val tasks = current.planResult?.tasks ?: return@update current
            if (index !in tasks.indices) return@update current
            val selected = current.planSelected.toMutableSet()
            if (!selected.add(index)) selected.remove(index)
            current.copy(planSelected = selected)
        }
    }

    /** 用户确认后，把勾选项写入今日任务；按标题跳过已存在/重复的任务。 */
    fun applyPlannedTasks() {
        val current = _state.value
        val plan = current.planResult ?: return
        if (plan.isCrisis || current.planApplying) return
        if (current.planSelected.isEmpty()) {
            _state.update { it.copy(planMessage = "请先勾选要加入的任务") }
            return
        }
        _state.update {
            it.copy(planApplying = true, planError = null, planMessage = null)
        }
        viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val today = Dates.today()
                    val existingTitles = container.taskRepository.listByDate(today)
                        .map { task -> task.title.trim() }
                        .toMutableSet()
                    val candidates = plan.tasks.filterIndexed { index, task ->
                        index in current.planSelected && task.title.isNotBlank()
                    }
                    var added = 0
                    for (task in candidates) {
                        val title = task.title.trim()
                        // 同名任务已存在（或本批重复）时跳过，不重复写入。
                        if (!existingTitles.add(title)) continue
                        container.taskRepository.insert(
                            Task(
                                goalId = task.goalId,
                                date = today,
                                title = title,
                                estMinutes = task.estMinutes,
                                done = false,
                            ),
                        )
                        added++
                    }
                    added to (candidates.size - added)
                }
                _state.update {
                    it.copy(
                        planApplying = false,
                        planMessage = if (outcome.second > 0) {
                            "已加入 ${outcome.first} 项（跳过 ${outcome.second} 项同名任务）"
                        } else {
                            "已加入 ${outcome.first} 项"
                        },
                    )
                }
                // 刷新今日任务列表（以及统计等），不重新显示整页 loading。
                forceRefresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        planApplying = false,
                        planError = "加入任务失败：${
                            e.message?.takeIf { msg -> msg.isNotBlank() }
                                ?: e.javaClass.simpleName
                        }",
                    )
                }
            }
        }
    }

    private fun currentHour(): Int =
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour

    private data class DashboardSnapshot(
        val overview: StatsOverview,
        val todayLabel: String,
        val tasks: List<DashboardTaskItem>,
        val trends: StatsTrends,
        val gaps: List<LifeGap>,
        val interviewNeeded: Boolean,
        val profileSummary: String,
    )

    companion object {
        /** 一句话整理输入上限：超过后按钮禁用并提示（DESIGN 第 7 节）。 */
        const val MAX_CAPTURE_INPUT = 2000

        /** 当天数据在这段时间内视为新鲜：快速来回切页面时不重复取数。 */
        private const val CACHE_REUSE_MS = 60_000L

        /** 访谈收入在 healthInfo 中的固定键（无 schema 迁移，随备份自动保存）。 */
        private const val KEY_INTERVIEW_INCOME = "interview_income"
    }
}