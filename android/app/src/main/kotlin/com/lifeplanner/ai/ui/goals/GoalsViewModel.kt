package com.lifeplanner.ai.ui.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeplanner.ai.AppContainer
import com.lifeplanner.core.ai.AiErrorCodes
import com.lifeplanner.core.ai.AiException
import com.lifeplanner.core.ai.BreakdownResult
import com.lifeplanner.core.ai.BreakdownStage
import com.lifeplanner.core.ai.BreakdownWeekly
import com.lifeplanner.core.model.Goal
import com.lifeplanner.core.model.GoalLevels
import com.lifeplanner.core.model.GoalNode
import com.lifeplanner.core.model.GoalStatuses
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 目标页状态。
 *
 * [tree] 用于渲染层级；[goals] 是未删除的扁平列表（编辑对话框的父级下拉需要）。
 * AI 拆解结果只放在 [breakdown] 里，必须经界面勾选确认后才会调用 [createFromBreakdown] 落库。
 */
data class GoalsUiState(
    val loading: Boolean = true,
    /** 静默刷新：已有目标树时只显示细进度条，不闪成整页 loading。 */
    val refreshing: Boolean = false,
    val tree: List<GoalNode> = emptyList(),
    val goals: List<Goal> = emptyList(),
    val error: String? = null,
    val message: String? = null,
    val aiLoading: Boolean = false,
    val aiError: String? = null,
    val aiNotConfigured: Boolean = false,
    /** 当前 AI 拆解请求对应的目标 id；用于丢弃迟到/串台的旧结果。 */
    val aiGoalId: Long? = null,
    val breakdown: BreakdownResult? = null,
    /** 创建拆解结果时的同步防重入标志。 */
    val creating: Boolean = false,
)

/** 用户在 AI 拆解结果里勾选后、准备落库的内容。 */
data class ConfirmedStage(
    val stage: BreakdownStage,
    val weeks: List<BreakdownWeekly>,
)

class GoalsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(GoalsUiState())
    val state: StateFlow<GoalsUiState> = _state.asStateFlow()

    /** 当前 AI 拆解协程；切换目标或关闭弹窗时取消，避免旧结果串台。 */
    private var breakdownJob: Job? = null

    /** 与 state.creating 同步维护的普通标志，抢在协程启动前挡住双击。 */
    private var creating = false

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /**
     * 重新读取目标树。
     *
     * silent = true（写操作后的刷新）或已有目标树时走「静默刷新」：
     * 保留旧内容，只把 refreshing 置位用于细进度条，避免整页闪 loading。
     */
    fun refresh(silent: Boolean = false) {
        viewModelScope.launch {
            val keepContent = silent || _state.value.tree.isNotEmpty()
            if (keepContent) {
                _state.update { it.copy(refreshing = true, error = null) }
            } else {
                _state.update { it.copy(loading = true, error = null) }
            }
            try {
                val (tree, goals) = withContext(Dispatchers.IO) {
                    container.goalService.tree() to container.goalService.list()
                }
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        tree = tree,
                        goals = goals,
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = "加载目标失败：${e.readable()}",
                    )
                }
            }
        }
    }

    /**
     * 新建或编辑目标。父级/层级的最终校验由 GoalService 完成；
     * IllegalArgumentException / NoSuchElementException 的 message 直接显示在对话框里。
     */
    fun saveGoal(goal: Goal, isNew: Boolean, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val problem: String? = try {
                withContext(Dispatchers.IO) {
                    if (isNew) {
                        container.goalService.createGoal(goal)
                    } else {
                        container.goalService.updateGoal(goal)
                    }
                }
                null
            } catch (e: IllegalArgumentException) {
                e.message ?: "保存失败：目标层级或父级不合法"
            } catch (e: NoSuchElementException) {
                e.message ?: "目标不存在或已删除"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "保存失败：${e.readable()}"
            }

            if (problem == null) {
                _state.update { it.copy(message = if (isNew) "已创建目标" else "已保存目标") }
                onResult(null)
                refresh(silent = true)
            } else {
                onResult(problem)
            }
        }
    }

    /** 软删除整棵子树；任务保留。 */
    fun deleteGoal(id: Long, title: String) {
        viewModelScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) { container.goalService.deleteGoal(id) }
                if (deleted) {
                    _state.update {
                        it.copy(message = "已删除「$title」及其下级目标；历史任务保留")
                    }
                    refresh(silent = true)
                } else {
                    _state.update { it.copy(error = "删除失败：目标不存在或已被删除") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "删除失败：${e.readable()}") }
            }
        }
    }

    /** 用户确认后的进度写入；skipped 时把中文原因展示出来。 */
    fun confirmProgress(goalId: Long, progress: Int) {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    container.goalService.confirmProgress(listOf(goalId to progress))
                }
                val skippedReason = result.skipped.firstOrNull()?.second
                val text = if (skippedReason != null) {
                    "进度未写入：$skippedReason"
                } else {
                    "进度已更新为 ${result.updated.firstOrNull()?.progress ?: progress}%"
                }
                _state.update { it.copy(message = text) }
                refresh(silent = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = "进度写入失败：${e.readable()}") }
            }
        }
    }

    /** 请求 AI 拆解；结果只进入 state.breakdown，绝不自动写库。 */
    fun startBreakdown(goal: Goal) {
        val goalId = goal.id ?: run {
            _state.update { it.copy(message = "目标尚未保存，无法进行 AI 拆解") }
            return
        }
        // 关键：切换目标前取消旧请求，旧协程即便返回也不能再写状态。
        breakdownJob?.cancel()
        _state.update {
            it.copy(
                aiLoading = true,
                aiError = null,
                aiNotConfigured = false,
                aiGoalId = goalId,
                breakdown = null,
            )
        }
        breakdownJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    container.aiService().breakdownGoal(goal.title, goal.description)
                }
                // 双保险：只有当前请求的目标仍匹配时才采纳结果。
                if (_state.value.aiGoalId == goalId) {
                    _state.update { it.copy(aiLoading = false, breakdown = result) }
                }
            } catch (e: AiException) {
                if (_state.value.aiGoalId == goalId) {
                    _state.update {
                        it.copy(
                            aiLoading = false,
                            aiError = e.message,
                            aiNotConfigured = e.code == AiErrorCodes.NOT_CONFIGURED,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_state.value.aiGoalId == goalId) {
                    _state.update {
                        it.copy(aiLoading = false, aiError = "AI 拆解失败：${e.readable()}")
                    }
                }
            }
        }
    }

    fun clearBreakdown() {
        // 关闭弹窗必须取消在途请求，否则迟到结果会串到下一个目标。
        breakdownJob?.cancel()
        breakdownJob = null
        _state.update {
            it.copy(
                aiLoading = false,
                aiError = null,
                aiNotConfigured = false,
                aiGoalId = null,
                breakdown = null,
            )
        }
    }

    /**
     * 用户勾选并确认后才创建：每个阶段作为 [parentId] 的阶段目标，
     * 其下选中的周目标作为该阶段的下级。部分失败时继续创建其余项并汇总。
     *
     * [parentId] 必须来自发起 AI 请求时记录的目标 id，不能读取弹窗时的可变状态。
     * creating 是同步防重入标志：双击时第二次调用会直接返回。
     */
    fun createFromBreakdown(
        parentId: Long,
        stages: List<ConfirmedStage>,
        onDone: () -> Unit,
    ) {
        if (creating) return
        if (stages.isEmpty()) {
            _state.update { it.copy(message = "未勾选任何条目，未创建目标") }
            onDone()
            return
        }
        creating = true
        _state.update { it.copy(creating = true) }
        viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    var created = 0
                    val errors = ArrayList<String>()
                    for (selection in stages) {
                        try {
                            val stageId = container.goalService.createGoal(
                                Goal(
                                    parentId = parentId,
                                    level = GoalLevels.STAGE,
                                    title = selection.stage.title.trim().ifBlank { "未命名阶段" },
                                    description = selection.stage.description?.trim()?.ifBlank { null },
                                    metric = selection.stage.metric?.trim()?.ifBlank { null },
                                    status = GoalStatuses.IN_PROGRESS,
                                )
                            )
                            created++
                            for (week in selection.weeks) {
                                container.goalService.createGoal(
                                    Goal(
                                        parentId = stageId,
                                        level = GoalLevels.WEEKLY,
                                        title = week.title.trim().ifBlank { "未命名周目标" },
                                        description = week.description?.trim()?.ifBlank { null },
                                        status = GoalStatuses.IN_PROGRESS,
                                    )
                                )
                                created++
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            errors.add("${selection.stage.title}：${e.readable()}")
                        }
                    }
                    created to errors
                }

                val (created, errors) = outcome
                val text = buildString {
                    append("已创建 $created 个目标")
                    if (errors.isNotEmpty()) {
                        append("；${errors.size} 项失败：")
                        append(errors.take(3).joinToString("；"))
                        if (errors.size > 3) append("…")
                    }
                }
                _state.update { it.copy(message = text) }
                refresh(silent = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = "创建目标失败：${e.readable()}") }
            } finally {
                creating = false
                _state.update { it.copy(creating = false) }
                onDone()
            }
        }
    }
}

private fun Throwable.readable(): String =
    message?.takeIf { it.isNotBlank() } ?: "未知错误"
