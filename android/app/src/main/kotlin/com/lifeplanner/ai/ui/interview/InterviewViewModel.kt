package com.lifeplanner.ai.ui.interview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeplanner.ai.AppContainer
import com.lifeplanner.core.ai.AiErrorCodes
import com.lifeplanner.core.ai.AiException
import com.lifeplanner.core.ai.CrisisPayload
import com.lifeplanner.core.ai.InterviewApplied
import com.lifeplanner.core.ai.InterviewDraft
import com.lifeplanner.core.ai.InterviewFields
import com.lifeplanner.core.ai.InterviewMessage
import com.lifeplanner.core.ai.InterviewTurn
import com.lifeplanner.core.util.Dates
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
 * 界面气泡：比 core 的 InterviewMessage 多一个 [question]。
 *
 * 为什么要分开存：一次访谈回合里，AI 的「回应上句」和「提出新问题」是两种语气，
 * 界面上要分两段排版（回应弱、问题重）；但送给模型的历史必须带问题，否则下一轮
 * 模型不知道自己刚才问了什么。用这个结构同时满足「渲染」和「历史」两个诉求。
 */
data class InterviewBubble(
    val fromUser: Boolean,
    val text: String,
    /** 仅 AI 气泡使用：本回合提出的问题（可为空）。 */
    val question: String? = null,
) {
    /** 送进模型的历史文本：AI 侧把回应与问题拼成一段。 */
    fun toHistoryMessage(): InterviewMessage {
        if (fromUser) return InterviewMessage(fromUser = true, text = text)
        val merged = listOf(text.trim(), question?.trim().orEmpty())
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        return InterviewMessage(fromUser = false, text = merged)
    }
}

data class InterviewUiState(
    val messages: List<InterviewBubble> = emptyList(),
    val fields: InterviewFields = InterviewFields(),
    val currentQuestion: String? = null,
    val input: String = "",
    val busy: Boolean = false,
    /** start() 是否已经发起过第一轮，避免重组时重复请求。 */
    val started: Boolean = false,
    val finished: Boolean = false,
    val draft: InterviewDraft? = null,
    val error: String? = null,
    /** 未配置 API Key：界面给「去设置」入口，不再反复重试。 */
    val notConfigured: Boolean = false,
    val saving: Boolean = false,
    val selectedGoals: Set<Int> = emptySet(),
    /** 一次性提示（保存成功等），界面消费后清空。 */
    val savedMessage: String? = null,
    /** 已经落库：展示轻量成功态，输入区收起。 */
    val saved: Boolean = false,
    /** 危机短路：只展示关怀与求助信息，禁用保存。 */
    val crisis: CrisisPayload? = null,
) {
    /** 是否还能继续回答（未结束、非危机、未保存、未在请求中）。 */
    val canAnswer: Boolean
        get() = !finished && !busy && !saving && !saved && crisis == null && !notConfigured
}

/**
 * 「认识你」访谈页的 ViewModel。
 *
 * 设计取舍：
 * - 访谈是**多轮**对话，AI 每轮只推进一个小问题；这里只保存气泡、已收集字段与当前问题，
 *   业务校验（字段范围、目标白名单）全部在 core，界面不重复实现规则。
 * - 草稿只在 finished 之后存在，且**用户勾选确认前绝不落库**（与一句话整理同一原则）。
 * - 所有网络/仓储调用都在 IO 线程；失败保留已有对话，让用户可以重试而不是从头再来。
 */
class InterviewViewModel(
    private val container: AppContainer,
    /** 保存成功后的回调：首页可以用它刷新档案/目标，弹别的提示。 */
    private val onSaved: () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(InterviewUiState())
    val state: StateFlow<InterviewUiState> = _state.asStateFlow()

    private var turnJob: Job? = null

    /** 最近一次请求的参数：失败时「再试一次」要能原样重放，而不是让用户重新打字。 */
    private var lastRequest: Pair<List<InterviewMessage>, InterviewFields>? = null

    /**
     * 进入页面时调用：先用已有档案"喂"给模型，再请它主动开场。
     *
     * 为什么要预填：用户可能已经在档案页填过年龄/职业/收入，如果访谈从零开始问，
     * 会显得这个应用"不记得我"。预填后模型只问还缺的部分，也避免重复回答。
     */
    fun start() {
        if (_state.value.started || turnJob?.isActive == true) return
        _state.update {
            it.copy(started = true, busy = true, error = null, notConfigured = false, crisis = null)
        }
        viewModelScope.launch {
            val seeded = try {
                withContext(Dispatchers.IO) {
                    val p = container.profileRepository.get()
                    if (p == null) {
                        InterviewFields()
                    } else {
                        InterviewFields(
                            age = p.age,
                            gender = p.gender,
                            occupation = p.occupation,
                            industry = p.industry,
                            yearsOfWork = p.yearsOfWork,
                            education = p.education,
                            major = p.major,
                            income = p.healthInfo[KEY_INTERVIEW_INCOME],
                            currentState = p.healthInfo[KEY_CURRENT_STATE],
                            desiredState = p.healthInfo[KEY_DESIRED_STATE],
                            approach = p.healthInfo[KEY_APPROACH],
                            futureSelf = p.healthInfo[KEY_FUTURE_SELF],
                            constraints = p.constraints,
                            vision = p.vision,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                InterviewFields()
            }
            _state.update { it.copy(fields = seeded) }
            requestTurn(history = emptyList(), fields = seeded)
        }
    }

    fun onInputChange(text: String) {
        // 不在输入时截断：超长由界面禁用发送并提示，用户能看见自己写了多少。
        _state.update { it.copy(input = text, error = null) }
    }

    /** 发送用户回答。 */
    fun submit() {
        val current = _state.value
        if (!current.canAnswer) return
        val answer = current.input.trim()
        if (answer.isEmpty()) return
        if (answer.length > MAX_ANSWER) {
            _state.update { it.copy(error = "这段有点长（${answer.length} 字），请精简到 $MAX_ANSWER 字以内") }
            return
        }
        sendAnswer(answer)
    }

    /**
     * 跳过当前问题。
     *
     * 为什么要真的发一条「先跳过」：访谈是连续对话，直接静默跳过会让模型的上下文
     * 断掉（它会以为用户答了上一个问题）；发一句明确的跳过，模型才知道换个话题问。
     */
    fun skip() {
        if (!_state.value.canAnswer) return
        sendAnswer(SKIP_ANSWER)
    }

    /** 失败后重放上一次请求（首轮失败也能重试，不需要用户重新描述）。 */
    fun retry() {
        val request = lastRequest ?: return
        if (turnJob?.isActive == true) return
        _state.update { it.copy(busy = true, error = null, notConfigured = false) }
        requestTurn(history = request.first, fields = request.second)
    }

    fun toggleGoal(index: Int) {
        _state.update { current ->
            val goals = current.draft?.goals ?: return@update current
            if (index !in goals.indices) return@update current
            val next = current.selectedGoals.toMutableSet()
            if (!next.add(index)) next.remove(index)
            current.copy(selectedGoals = next)
        }
    }

    /**
     * 保存到档案：只提交用户勾选的目标。
     * 未勾选的目标即使 AI 建议过也不落库 —— 档案是用户的，不是模型的。
     */
    fun save() {
        val current = _state.value
        val draft = current.draft ?: return
        if (current.saving || current.saved || current.crisis != null) return
        val goals = draft.goals.filterIndexed { index, _ -> index in current.selectedGoals }
        _state.update { it.copy(saving = true, error = null, savedMessage = null) }
        viewModelScope.launch {
            try {
                val applied: InterviewApplied = withContext(Dispatchers.IO) {
                    container.aiService().saveInterview(draft = draft.copy(goals = goals), today = Dates.today())
                }
                _state.update {
                    it.copy(
                        saving = false,
                        saved = true,
                        savedMessage = describeApplied(applied),
                    )
                }
                // 真写了东西才回调：避免首页无谓刷新（空草稿在 core 里已经被拒掉）
                if (applied.profileSaved || applied.goalsInserted > 0) onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                _state.update {
                    it.copy(
                        saving = false,
                        error = e.message,
                        notConfigured = e.code == AiErrorCodes.NOT_CONFIGURED,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        saving = false,
                        error = "保存失败：${
                            e.message?.takeIf { msg -> msg.isNotBlank() } ?: e.javaClass.simpleName
                        }",
                    )
                }
            }
        }
    }

    /** 重新开始一场访谈：清空全部本地状态，包括已保存标记。 */
    fun restart() {
        turnJob?.cancel()
        turnJob = null
        lastRequest = null
        _state.value = InterviewUiState()
        start()
    }

    /** 消费一次性提示（Snackbar 已展示）。 */
    fun consumeMessage() {
        _state.update { it.copy(savedMessage = null) }
    }

    // ------------------------------------------------------------------
    // 内部：一轮问答
    // ------------------------------------------------------------------

    private fun sendAnswer(answer: String) {
        val current = _state.value
        val withUser = current.messages + InterviewBubble(fromUser = true, text = answer)
        _state.update {
            it.copy(
                messages = withUser,
                input = "",
                busy = true,
                error = null,
                currentQuestion = null,
            )
        }
        requestTurn(
            history = withUser.map { bubble -> bubble.toHistoryMessage() },
            fields = current.fields,
        )
    }

    private fun requestTurn(history: List<InterviewMessage>, fields: InterviewFields) {
        turnJob?.cancel()
        lastRequest = history to fields
        turnJob = viewModelScope.launch {
            try {
                val turn = withContext(Dispatchers.IO) {
                    container.aiService().interviewTurn(
                        history = history,
                        fields = fields,
                        today = Dates.today(),
                    )
                }
                applyTurn(turn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                _state.update {
                    it.copy(
                        busy = false,
                        error = e.message,
                        notConfigured = e.code == AiErrorCodes.NOT_CONFIGURED,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        error = "对方没有回应：${
                            e.message?.takeIf { msg -> msg.isNotBlank() } ?: e.javaClass.simpleName
                        }",
                    )
                }
            }
        }
    }

    private fun applyTurn(turn: InterviewTurn) {
        val bubbles = _state.value.messages.toMutableList()
        // 回应与问题都为空说明模型违约（core 会抛 BAD_RESPONSE，这里只是兜底不追加空气泡）
        if (turn.reply.isNotBlank() || !turn.question.isNullOrBlank()) {
            bubbles.add(
                InterviewBubble(
                    fromUser = false,
                    text = turn.reply.trim(),
                    question = turn.question?.takeIf { it.isNotBlank() },
                ),
            )
        }

        if (turn.isCrisis) {
            // 危机：保留已有对话，标记后让界面收起输入、禁用保存
            _state.update {
                it.copy(
                    messages = bubbles,
                    fields = turn.fields,
                    busy = false,
                    finished = true,
                    currentQuestion = null,
                    draft = null,
                    crisis = turn.crisis ?: crisisFallback(turn.reply),
                )
            }
            return
        }

        val draft = if (turn.finished) draftOf(turn) else null
        _state.update {
            it.copy(
                messages = bubbles,
                busy = false,
                fields = turn.fields,
                currentQuestion = if (turn.finished) null else turn.question,
                finished = turn.finished,
                draft = draft,
                selectedGoals = draft?.goals?.indices?.toSet() ?: emptySet(),
                crisis = null,
            )
        }
    }

    /**
     * 结束回合的结构化草稿。
     *
     * 正常路径一定有 core 给好的 turn.draft（goals/summary/vision 都已校验）；
     * 兜底只用于「core 返回 finished 但没带 draft」这种异常，绝不拿它假装有目标。
     */
    private fun draftOf(turn: InterviewTurn): InterviewDraft =
        turn.draft ?: InterviewDraft(
            fields = turn.fields,
            vision = turn.fields.vision.orEmpty(),
            constraints = turn.fields.constraints.orEmpty(),
            goals = emptyList(),
            summary = turn.reply.trim(),
        )

    private fun describeApplied(applied: InterviewApplied): String {
        val parts = ArrayList<String>(2)
        if (applied.profileSaved) parts.add("档案")
        if (applied.goalsInserted > 0) parts.add("${applied.goalsInserted} 个目标")
        return if (parts.isEmpty()) "没有新增内容（与现有档案一致）" else "已保存：${parts.joinToString("、")}"
    }

    companion object {
        // 与 core 的 saveInterview 使用同一组 healthInfo 键（契约见 InterviewContract 文档）
        const val KEY_INTERVIEW_INCOME = "interview_income"
        const val KEY_CURRENT_STATE = "interview_current_state"
        const val KEY_DESIRED_STATE = "interview_desired_state"
        const val KEY_APPROACH = "interview_approach"
        const val KEY_FUTURE_SELF = "interview_future_self"

        /** 单条回答的上限：访谈不是写作文，超过这个长度基本是粘贴了别的内容。 */
        const val MAX_ANSWER = 1000

        /** 跳过时替用户说的一句话。 */
        const val SKIP_ANSWER = "这个先跳过，我们聊下一个吧"
    }
}

/**
 * 危机兜底：理论上 core 一定会带 crisis，这里保证界面不会因为 null 而整块空白。
 * 文案保持中性，不复述用户原话。
 */
private fun crisisFallback(reply: String): CrisisPayload = CrisisPayload(
    conclusion = "先照顾好自己",
    reason = reply.ifBlank { "你刚才说的话让我有些担心。" },
    pros = "",
    consRisks = "",
    alternative = "如果情况紧急，请立即拨打 120 或 110，并联系身边可以信任的人陪着你。",
    goalRelation = "档案可以以后再建，你的安全最重要。",
)