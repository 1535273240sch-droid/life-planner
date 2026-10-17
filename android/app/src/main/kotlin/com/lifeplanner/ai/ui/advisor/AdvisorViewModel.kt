package com.lifeplanner.ai.ui.advisor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeplanner.ai.AppContainer
import com.lifeplanner.core.ai.AdvisorResult
import com.lifeplanner.core.ai.AiErrorCodes
import com.lifeplanner.core.ai.AiException
import com.lifeplanner.core.model.AiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 顾问页状态。问题文本放在 ViewModel 里，旋转屏幕不丢；
 * 结果与错误都来自真实的 [com.lifeplanner.core.ai.AiService] 调用，没有本地模板降级。
 */
data class AdvisorUiState(
    val question: String = "",
    val asking: Boolean = false,
    /** 最近一次成功的咨询结果 */
    val result: AdvisorResult? = null,
    /** 最近一次失败的原始可读消息（AiException.message 或通用文案） */
    val error: String? = null,
    /** 错误是否为"尚未配置 AI 服务"，需要额外显示"去设置"入口 */
    val notConfigured: Boolean = false,
    val history: List<AiMessage> = emptyList(),
    val historyLoading: Boolean = false,
    /** 静默刷新历史：已有记录时只做后台更新，不把列表整块换成 loading。 */
    val historyRefreshing: Boolean = false,
    val historyError: String? = null,
)

class AdvisorViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(AdvisorUiState())
    val state: StateFlow<AdvisorUiState> = _state.asStateFlow()

    init {
        refreshHistory()
    }

    fun onQuestionChange(value: String) {
        _state.update { it.copy(question = value) }
    }

    /**
     * 咨询。输入为空或正在请求时忽略；所有网络/解析都在 IO 线程。
     * AI 层失败零副作用，这里只负责把真实原因展示给用户。
     */
    fun ask() {
        val question = _state.value.question.trim()
        if (question.isEmpty() || _state.value.asking) return

        // 清空上一条结果：本次失败时不能让人误以为旧答案属于新问题。
        _state.update { it.copy(asking = true, result = null, error = null, notConfigured = false) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    container.aiService().askAdvisor(question)
                }
                _state.update { it.copy(asking = false, result = result, error = null) }
                // 服务成功（含危机短路）时会写入一条历史，刷新以显示最新一条。
                refreshHistory()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                _state.update {
                    it.copy(
                        asking = false,
                        error = e.message,
                        notConfigured = e.code == AiErrorCodes.NOT_CONFIGURED,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(asking = false, error = "请求失败，请稍后重试", notConfigured = false)
                }
            }
        }
    }

    /** 重新读取最近 20 条顾问历史（仓储按 id 倒序返回）；已有记录时静默刷新。 */
    fun refreshHistory() {
        val silent = _state.value.history.isNotEmpty()
        _state.update {
            it.copy(
                historyLoading = !silent,
                historyRefreshing = silent,
                historyError = null,
            )
        }
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    container.aiMessageRepository.listByMode("advisor", 20)
                }
                _state.update {
                    it.copy(
                        history = list,
                        historyLoading = false,
                        historyRefreshing = false,
                        historyError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        historyLoading = false,
                        historyRefreshing = false,
                        historyError = "历史记录读取失败，请重试",
                    )
                }
            }
        }
    }
}
