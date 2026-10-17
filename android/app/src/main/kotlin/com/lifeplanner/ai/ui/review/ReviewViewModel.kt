package com.lifeplanner.ai.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeplanner.ai.AppContainer
import com.lifeplanner.core.ai.AiErrorCodes
import com.lifeplanner.core.ai.AiException
import com.lifeplanner.core.ai.ReviewResult
import com.lifeplanner.core.model.AiMessage
import com.lifeplanner.core.util.Dates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/**
 * 复盘页状态。
 *
 * - [selectedGoalIds] 是用户勾选的进度建议（默认空 = 不应用）。
 * - [message] 用于拨号失败等一次性提示，显示后调用 [ReviewViewModel.consumeMessage]。
 */
data class ReviewUiState(
    val type: String = ReviewViewModel.TYPE_DAILY,
    val date: LocalDate = Dates.today(),
    val generating: Boolean = false,
    val result: ReviewResult? = null,
    val error: String? = null,
    val notConfigured: Boolean = false,
    val selectedGoalIds: Set<Long> = emptySet(),
    val applyingProgress: Boolean = false,
    val progressMessage: String? = null,
    val progressError: String? = null,
    val history: List<AiMessage> = emptyList(),
    val historyLoading: Boolean = false,
    val historyError: String? = null,
    val expandedHistoryIds: Set<Long> = emptySet(),
    val message: String? = null,
)

/**
 * 复盘页 ViewModel。
 *
 * AI 只通过 [AppContainer.aiService] 调用；生成结果不会自动落库到目标进度，
 * 只有用户显式勾选并点击“应用选中进度”才调用 [com.lifeplanner.core.logic.GoalService.confirmProgress]。
 */
class ReviewViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    init {
        refreshHistory()
    }

    fun selectType(type: String) {
        if (type != TYPE_DAILY && type != TYPE_WEEKLY) return
        val current = _state.value
        if (current.generating || current.applyingProgress) return
        if (current.type == type) return
        _state.value = current.copy(
            type = type,
            result = null,
            error = null,
            notConfigured = false,
            selectedGoalIds = emptySet(),
            progressMessage = null,
            progressError = null,
        )
    }

    fun previousDay() {
        val current = _state.value
        if (current.generating || current.applyingProgress) return
        _state.value = current.copy(
            date = Dates.minusDays(current.date, 1),
            // 日期变了，旧结果不再对应当前选择，清空避免误读
            result = null,
            error = null,
            notConfigured = false,
            selectedGoalIds = emptySet(),
            progressMessage = null,
            progressError = null,
        )
    }

    fun nextDay() {
        val current = _state.value
        if (current.generating || current.applyingProgress) return
        val today = Dates.today()
        if (current.date >= today) return
        _state.value = current.copy(
            date = Dates.plusDays(current.date, 1),
            result = null,
            error = null,
            notConfigured = false,
            selectedGoalIds = emptySet(),
            progressMessage = null,
            progressError = null,
        )
    }

    fun generate() {
        val snapshot = _state.value
        if (snapshot.generating || snapshot.applyingProgress) return
        // 不允许对未来日期生成复盘（UI 已限制，这里再兜一层）
        if (snapshot.date > Dates.today()) return

        _state.value = snapshot.copy(
            generating = true,
            result = null,
            error = null,
            notConfigured = false,
            selectedGoalIds = emptySet(),
            progressMessage = null,
            progressError = null,
        )

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    container.aiService().generateReview(snapshot.type, snapshot.date)
                }
                _state.value = _state.value.copy(generating = false, result = result)
                // AiService 成功后已写入历史；重新读取以保证列表与服务端写入一致
                refreshHistory()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                _state.value = _state.value.copy(
                    generating = false,
                    error = e.message,
                    notConfigured = e.code == AiErrorCodes.NOT_CONFIGURED,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    generating = false,
                    error = "请求失败，请稍后重试",
                )
            }
        }
    }

    fun toggleSuggestion(goalId: Long) {
        if (_state.value.applyingProgress) return
        val current = _state.value
        val next = if (goalId in current.selectedGoalIds) {
            current.selectedGoalIds - goalId
        } else {
            current.selectedGoalIds + goalId
        }
        _state.value = current.copy(selectedGoalIds = next)
    }

    /** 只在用户点击按钮时调用：把勾选的建议写入目标进度。 */
    fun applySelectedProgress() {
        val snapshot = _state.value
        if (snapshot.applyingProgress) return
        val result = snapshot.result ?: return
        val chosen = result.progressSuggestions.filter { it.goalId in snapshot.selectedGoalIds }
        if (chosen.isEmpty()) return

        _state.value = snapshot.copy(
            applyingProgress = true,
            progressMessage = null,
            progressError = null,
        )

        viewModelScope.launch {
            try {
                val applied = withContext(Dispatchers.IO) {
                    container.goalService.confirmProgress(
                        chosen.map { it.goalId to it.suggestedProgress },
                    )
                }
                val titleById = result.progressSuggestions.associate { it.goalId to it.title }
                val text = buildString {
                    append("已更新 ${applied.updated.size} 个目标进度")
                    if (applied.updated.isEmpty() && applied.skipped.isEmpty()) {
                        append("（建议值与当前进度一致，无需变更）")
                    }
                    if (applied.skipped.isNotEmpty()) {
                        append("；跳过 ${applied.skipped.size} 个：")
                        append(
                            applied.skipped.joinToString("；") { (id, reason) ->
                                "${titleById[id] ?: "目标 #$id"}（$reason）"
                            },
                        )
                    }
                }
                _state.value = _state.value.copy(
                    applyingProgress = false,
                    selectedGoalIds = emptySet(),
                    progressMessage = text,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    applyingProgress = false,
                    progressError = "应用进度失败：${e.message ?: "未知错误"}",
                )
            }
        }
    }

    fun toggleHistory(id: Long) {
        val current = _state.value
        val next = if (id in current.expandedHistoryIds) {
            current.expandedHistoryIds - id
        } else {
            current.expandedHistoryIds + id
        }
        _state.value = current.copy(expandedHistoryIds = next)
    }

    fun refreshHistory() {
        _state.value = _state.value.copy(historyLoading = true, historyError = null)
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    container.aiMessageRepository.listByMode(MODE_REVIEW, HISTORY_LIMIT)
                }
                _state.value = _state.value.copy(history = list, historyLoading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    historyLoading = false,
                    historyError = "历史记录加载失败：${e.message ?: "未知错误"}",
                )
            }
        }
    }

    fun notify(message: String) {
        _state.value = _state.value.copy(message = message)
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    companion object {
        const val TYPE_DAILY = "daily"
        const val TYPE_WEEKLY = "weekly"

        private const val MODE_REVIEW = "review"
        private const val HISTORY_LIMIT = 10
    }
}
