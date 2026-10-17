package com.lifeplanner.ai.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeplanner.ai.AppContainer
import com.lifeplanner.core.ai.AiErrorCodes
import com.lifeplanner.core.ai.AiException
import com.lifeplanner.core.ai.DailyPlanResult
import com.lifeplanner.core.model.LifeGap
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

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /** 首次加载 / 手动重试 / 回到前台：显示整页 loading。 */
    fun refresh() {
        // 避免首次组合的 LaunchedEffect 与 ON_RESUME 同时触发重复取数。
        if (loadJob?.isActive == true) return
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
                val snapshot = withContext(Dispatchers.IO) {
                    // 每次刷新都重新取当天日期：应用在后台跨过午夜后，ON_RESUME 会拿到新的一天。
                    val today = Dates.today()
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
                    DashboardSnapshot(overview, Dates.monthDay(today), tasks, trends, gaps)
                }
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
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
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
                // 只刷新数据，不重新显示整页 loading。
                load(showFullLoading = false)
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
                load(showFullLoading = false)
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

    private data class DashboardSnapshot(
        val overview: StatsOverview,
        val todayLabel: String,
        val tasks: List<DashboardTaskItem>,
        val trends: StatsTrends,
        val gaps: List<LifeGap>,
    )
}
