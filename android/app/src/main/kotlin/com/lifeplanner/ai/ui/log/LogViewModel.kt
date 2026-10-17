package com.lifeplanner.ai.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeplanner.ai.AppContainer
import com.lifeplanner.core.model.DailyLog
import com.lifeplanner.core.util.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/**
 * 今日记录表单。
 *
 * 数字字段用 String 承载，目的是让用户能暂时输入空串/半截内容；
 * 保存时统一校验，绝不把非法输入静默当成有效值写库。
 */
data class LogForm(
    val doneItems: String = "",
    val studyMinutes: String = "0",
    val studyNote: String = "",
    val breakfast: String = "",
    val lunch: String = "",
    val dinner: String = "",
    val snack: String = "",
    val sleepStart: String = "",
    val sleepEnd: String = "",
    val exerciseType: String = "",
    val exerciseMinutes: String = "0",
    val mood: Int = 3,
    val energy: Int = 3,
    val reflection: String = "",
)

data class LogUiState(
    val selectedDate: LocalDate = Dates.today(),
    val today: LocalDate = Dates.today(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    /** 错误是否来自加载（决定错误条是否显示“重试”）。 */
    val errorFromLoad: Boolean = false,
    val message: String? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
    val form: LogForm = LogForm(),
    /** 用户是否改过表单且尚未保存，用于避免 onResume 重载时静默覆盖编辑。 */
    val dirty: Boolean = false,
    /** 非空表示等待用户确认“放弃未保存的修改”后切换到该日期。 */
    val pendingDate: LocalDate? = null,
    /** 最近 7 天（含今天），倒序。 */
    val recentLogs: List<DailyLog> = emptyList(),
)

class LogViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(LogUiState())
    val state: StateFlow<LogUiState> = _state.asStateFlow()

    /** 防止快速切换日期时旧的 IO 结果覆盖新表单。 */
    private var loadSeq = 0

    /**
     * 页面每次 ON_RESUME 调用：
     * 1. 重新计算“今天”，避免后台跨过午夜后仍把昨天当作今天；
     * 2. 没有未保存修改时重新加载当前日期，确保导入等外部改动可见；
     * 3. 若原本停留在旧“今天”且没有未保存修改，自动推进到新的今天。
     *
     * 有未保存修改时只更新 today，不重载、不推进日期，也绝不静默丢弃编辑。
     */
    fun onResume() {
        val snapshot = _state.value
        val newToday = Dates.today()
        val wasOnOldToday = snapshot.selectedDate == snapshot.today

        if (snapshot.saving || snapshot.dirty) {
            _state.update { it.copy(today = newToday) }
            return
        }

        val target = if (wasOnOldToday && newToday != snapshot.today) {
            newToday
        } else {
            snapshot.selectedDate
        }
        _state.update {
            it.copy(
                selectedDate = target,
                today = newToday,
                dirty = false,
                pendingDate = null,
            )
        }
        load(target, withRecent = true)
    }

    fun retry() {
        load(_state.value.selectedDate, withRecent = true)
    }

    fun previousDay() {
        selectDate(Dates.minusDays(_state.value.selectedDate, 1))
    }

    fun nextDay() {
        val next = Dates.plusDays(_state.value.selectedDate, 1)
        if (next <= Dates.today()) selectDate(next)
    }

    /** 切换到指定日期；严格不允许超过今天。有未保存修改时先请求确认。 */
    fun selectDate(date: LocalDate) {
        val snapshot = _state.value
        if (snapshot.loading || snapshot.saving) return
        val today = Dates.today()
        val safe = if (date > today) today else date
        if (safe == snapshot.selectedDate) return
        if (snapshot.dirty) {
            // 不静默丢弃：先让界面弹确认框，确认后由 confirmDiscard 完成切换。
            _state.update { it.copy(pendingDate = safe, today = today) }
            return
        }
        switchTo(safe)
    }

    /** 用户确认放弃未保存修改后切换日期。 */
    fun confirmDiscard() {
        val target = _state.value.pendingDate ?: return
        switchTo(target)
    }

    /** 取消切换，继续编辑当前表单。 */
    fun cancelDiscard() {
        _state.update { it.copy(pendingDate = null) }
    }

    private fun switchTo(date: LocalDate) {
        val today = Dates.today()
        val safe = if (date > today) today else date
        _state.update {
            it.copy(
                selectedDate = safe,
                today = today,
                dirty = false,
                pendingDate = null,
            )
        }
        load(safe, withRecent = false)
    }

    /** 同一天内的表单编辑；任何编辑都会清掉上一次的字段错误提示。 */
    fun updateForm(transform: (LogForm) -> LogForm) {
        if (_state.value.saving) return
        _state.update {
            it.copy(
                form = transform(it.form),
                fieldErrors = emptyMap(),
                message = null,
                dirty = true,
            )
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    fun save() {
        val snapshot = _state.value
        if (snapshot.saving || snapshot.loading) return

        val errors = validate(snapshot.form)
        if (errors.isNotEmpty()) {
            _state.update {
                it.copy(
                    fieldErrors = errors,
                    error = null,
                    errorFromLoad = false,
                    message = null,
                )
            }
            return
        }

        val log = snapshot.form.toDailyLog(snapshot.selectedDate)
        _state.update {
            it.copy(
                saving = true,
                error = null,
                errorFromLoad = false,
                message = null,
                fieldErrors = emptyMap(),
            )
        }

        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    container.logService.upsertTodayOrDate(log)
                    container.logService.recentLogs(7, Dates.today())
                }
            }
            result.onSuccess { recent ->
                _state.update {
                    it.copy(
                        saving = false,
                        dirty = false,
                        message = "已保存",
                        recentLogs = recent,
                        today = Dates.today(),
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        saving = false,
                        error = "保存失败：${readable(e)}",
                        errorFromLoad = false,
                    )
                }
            }
        }
    }

    private fun load(date: LocalDate, withRecent: Boolean) {
        val seq = ++loadSeq
        _state.update {
            it.copy(
                loading = true,
                error = null,
                errorFromLoad = false,
                message = null,
                fieldErrors = emptyMap(),
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val log = container.dailyLogRepository.findByDate(date)
                    val recent = if (withRecent) {
                        container.logService.recentLogs(7, Dates.today())
                    } else {
                        null
                    }
                    log to recent
                }
            }
            if (seq != loadSeq) return@launch
            result.onSuccess { (log, recent) ->
                _state.update {
                    it.copy(
                        loading = false,
                        dirty = false,
                        form = log?.toForm() ?: LogForm(),
                        recentLogs = recent ?: it.recentLogs,
                        today = Dates.today(),
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        loading = false,
                        error = "加载记录失败：${readable(e)}",
                        errorFromLoad = true,
                    )
                }
            }
        }
    }

    private fun validate(form: LogForm): Map<String, String> {
        val errors = LinkedHashMap<String, String>()
        validateMinutes(form.studyMinutes, "studyMinutes", "学习时长", errors)
        validateMinutes(form.exerciseMinutes, "exerciseMinutes", "运动时长", errors)
        validateClock(form.sleepStart, "sleepStart", "入睡时间", errors)
        validateClock(form.sleepEnd, "sleepEnd", "起床时间", errors)

        if (form.sleepStart.isBlank() != form.sleepEnd.isBlank()) {
            val key = if (form.sleepStart.isBlank()) "sleepStart" else "sleepEnd"
            if (errors[key] == null) {
                errors[key] = "入睡和起床时间请同时填写，或都留空"
            }
        }
        if (form.mood !in 1..5) errors["mood"] = "心情需在 1~5 之间"
        if (form.energy !in 1..5) errors["energy"] = "精力需在 1~5 之间"
        return errors
    }

    private fun validateMinutes(
        raw: String,
        key: String,
        label: String,
        errors: MutableMap<String, String>,
    ) {
        val text = raw.trim()
        // 空串按 0 处理：学习/运动时长明确允许为 0。
        if (text.isEmpty()) return
        if (!NUMBER_REGEX.matches(text)) {
            errors[key] = "${label}必须是整数分钟"
            return
        }
        val value = text.toIntOrNull()
        if (value == null || value !in 0..MAX_MINUTES) {
            errors[key] = "${label}需在 0~$MAX_MINUTES 分钟之间"
        }
    }

    private fun validateClock(
        raw: String,
        key: String,
        label: String,
        errors: MutableMap<String, String>,
    ) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (!CLOCK_REGEX.matches(text)) {
            errors[key] = "${label}格式应为 HH:mm，例如 23:30"
            return
        }
        val parts = text.split(":")
        val hour = parts[0].toIntOrNull()
        val minute = parts[1].toIntOrNull()
        if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
            errors[key] = "${label}不是有效时间（小时 0~23，分钟 0~59）"
        }
    }

    private fun LogForm.toDailyLog(date: LocalDate): DailyLog = DailyLog(
        date = date,
        doneItems = doneItems.nullIfBlank(),
        studyMinutes = studyMinutes.trim().toIntOrNull() ?: 0,
        studyNote = studyNote.nullIfBlank(),
        meals = buildMap {
            breakfast.nullIfBlank()?.let { put("breakfast", it) }
            lunch.nullIfBlank()?.let { put("lunch", it) }
            dinner.nullIfBlank()?.let { put("dinner", it) }
            snack.nullIfBlank()?.let { put("snack", it) }
        },
        sleepStart = sleepStart.nullIfBlank(),
        sleepEnd = sleepEnd.nullIfBlank(),
        exerciseType = exerciseType.nullIfBlank(),
        exerciseMinutes = exerciseMinutes.trim().toIntOrNull() ?: 0,
        mood = mood,
        energy = energy,
        reflection = reflection.nullIfBlank(),
    )

    private fun DailyLog.toForm(): LogForm = LogForm(
        doneItems = doneItems.orEmpty(),
        studyMinutes = studyMinutes.toString(),
        studyNote = studyNote.orEmpty(),
        breakfast = meals["breakfast"].orEmpty(),
        lunch = meals["lunch"].orEmpty(),
        dinner = meals["dinner"].orEmpty(),
        snack = meals["snack"].orEmpty(),
        sleepStart = sleepStart.orEmpty(),
        sleepEnd = sleepEnd.orEmpty(),
        exerciseType = exerciseType.orEmpty(),
        exerciseMinutes = exerciseMinutes.toString(),
        mood = mood.coerceIn(1, 5),
        energy = energy.coerceIn(1, 5),
        reflection = reflection.orEmpty(),
    )

    private companion object {
        /** 单日学习/运动分钟上限，防止明显误输入。 */
        const val MAX_MINUTES = 1440
        val NUMBER_REGEX = Regex("^\\d+$")
        val CLOCK_REGEX = Regex("^\\d{1,2}:\\d{2}$")
    }
}

private fun String?.nullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun readable(e: Throwable): String =
    e.message?.takeIf { it.isNotBlank() } ?: "未知错误，请稍后重试"
