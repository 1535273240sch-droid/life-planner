package com.lifeplanner.ai.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeplanner.ai.AppContainer
import com.lifeplanner.core.model.Profile
import com.lifeplanner.core.model.Skill
import com.lifeplanner.core.util.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 档案 + 技能页状态。
 *
 * 表单字段用字符串保存，保存时统一校验；仓储全部在 IO 线程调用。
 */
data class ProfileUiState(
    val loading: Boolean = true,
    val loaded: Boolean = false,
    /** 只有成功从仓储读取过档案，才允许保存：避免加载失败后用空表单覆盖旧档案。 */
    val loadSucceeded: Boolean = false,
    /** 档案表单是否有未保存的编辑；ON_RESUME 时不覆盖脏表单。 */
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val savingSkill: Boolean = false,
    val loadError: String? = null,
    val actionError: String? = null,
    val message: String? = null,
    val profileId: Long? = null,

    // 基础信息
    val age: String = "",
    val gender: String = "",
    val occupation: String = "",
    val industry: String = "",
    val yearsOfWork: String = "",
    val education: String = "",
    val major: String = "",

    // 健康底座
    val height: String = "",
    val weight: String = "",
    val sleepHabit: String = "",
    val dietPref: String = "",
    val taboo: String = "",
    val allergyOrDisease: String = "",

    // 愿景 / 约束
    val vision: String = "",
    val constraints: String = "",

    // 技能
    val skills: List<Skill> = emptyList(),
    val skillDialogOpen: Boolean = false,
    val editingSkill: Skill? = null,
    val skillError: String? = null,
    val deletingSkill: Skill? = null,
)

class ProfileViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    /** 防止首次进入时 LaunchedEffect 与 ON_RESUME 同时触发重复加载。始终在主线程访问。 */
    private var refreshing = false

    // ---- 读取 ----

    /** 页面恢复时调用：有未保存编辑则不覆盖表单。 */
    fun refreshIfSafe() {
        if (_state.value.dirty) return
        refresh()
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            _state.update {
                it.copy(loading = true, loadError = null, loadSucceeded = false)
            }
            try {
                val (profile, skills) = withContext(Dispatchers.IO) {
                    container.profileRepository.get() to container.skillRepository.list()
                }
                _state.update { s ->
                    s.copy(
                        loading = false,
                        loaded = true,
                        loadSucceeded = true,
                        dirty = false,
                        loadError = null,
                        profileId = profile?.id,
                        age = profile?.age?.toString().orEmpty(),
                        gender = profile?.gender.orEmpty(),
                        occupation = profile?.occupation.orEmpty(),
                        industry = profile?.industry.orEmpty(),
                        yearsOfWork = profile?.yearsOfWork?.toString().orEmpty(),
                        education = profile?.education.orEmpty(),
                        major = profile?.major.orEmpty(),
                        height = profile?.healthInfo?.get(KEY_HEIGHT).orEmpty(),
                        weight = profile?.healthInfo?.get(KEY_WEIGHT).orEmpty(),
                        sleepHabit = profile?.healthInfo?.get(KEY_SLEEP_HABIT).orEmpty(),
                        dietPref = profile?.healthInfo?.get(KEY_DIET_PREF).orEmpty(),
                        taboo = profile?.healthInfo?.get(KEY_TABOO).orEmpty(),
                        allergyOrDisease = profile?.healthInfo?.get(KEY_ALLERGY).orEmpty(),
                        vision = profile?.vision.orEmpty(),
                        constraints = profile?.constraints.orEmpty(),
                        skills = skills,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        loaded = true,
                        loadSucceeded = false,
                        loadError = "读取档案失败：${e.readable()}",
                    )
                }
            } finally {
                refreshing = false
            }
        }
    }

    // ---- 表单 setter（任何编辑都会把表单标记为脏） ----

    fun setAge(value: String) = updateState { it.copy(age = value, dirty = true) }
    fun setGender(value: String) = updateState { it.copy(gender = value, dirty = true) }
    fun setOccupation(value: String) = updateState { it.copy(occupation = value, dirty = true) }
    fun setIndustry(value: String) = updateState { it.copy(industry = value, dirty = true) }
    fun setYearsOfWork(value: String) = updateState { it.copy(yearsOfWork = value, dirty = true) }
    fun setEducation(value: String) = updateState { it.copy(education = value, dirty = true) }
    fun setMajor(value: String) = updateState { it.copy(major = value, dirty = true) }
    fun setHeight(value: String) = updateState { it.copy(height = value, dirty = true) }
    fun setWeight(value: String) = updateState { it.copy(weight = value, dirty = true) }
    fun setSleepHabit(value: String) = updateState { it.copy(sleepHabit = value, dirty = true) }
    fun setDietPref(value: String) = updateState { it.copy(dietPref = value, dirty = true) }
    fun setTaboo(value: String) = updateState { it.copy(taboo = value, dirty = true) }
    fun setAllergyOrDisease(value: String) = updateState { it.copy(allergyOrDisease = value, dirty = true) }
    fun setVision(value: String) = updateState { it.copy(vision = value, dirty = true) }
    fun setConstraints(value: String) = updateState { it.copy(constraints = value, dirty = true) }

    private fun updateState(block: (ProfileUiState) -> ProfileUiState) {
        _state.update { block(it) }
    }

    // ---- 保存档案 ----

    fun saveProfile() {
        val s = _state.value
        if (s.saving) return
        // 加载失败时表单是空的：绝不允许把它写回仓储（save 会整行 REPLACE）。
        if (!s.loadSucceeded || s.loading) {
            _state.update {
                it.copy(actionError = "档案尚未成功加载，暂时无法保存，请先重试加载")
            }
            return
        }

        val age = parseOptionalInt(s.age, "年龄", 0..150)
        val years = parseOptionalInt(s.yearsOfWork, "工作年限", 0..80)
        val healthError = validateDecimal(s.height, "身高", 300)
            ?: validateDecimal(s.weight, "体重", 500)
        val error = age.error ?: years.error ?: healthError
        if (error != null) {
            _state.update { it.copy(actionError = error) }
            return
        }

        val health = linkedMapOf<String, String>()
        fun putHealth(key: String, value: String) {
            val text = value.trim()
            if (text.isNotEmpty()) health[key] = text
        }
        putHealth(KEY_HEIGHT, s.height)
        putHealth(KEY_WEIGHT, s.weight)
        putHealth(KEY_SLEEP_HABIT, s.sleepHabit)
        putHealth(KEY_DIET_PREF, s.dietPref)
        putHealth(KEY_TABOO, s.taboo)
        putHealth(KEY_ALLERGY, s.allergyOrDisease)

        val draft = Profile(
            id = s.profileId,
            age = age.value,
            gender = s.gender.trim().ifBlank { null },
            occupation = s.occupation.trim().ifBlank { null },
            industry = s.industry.trim().ifBlank { null },
            yearsOfWork = years.value,
            education = s.education.trim().ifBlank { null },
            major = s.major.trim().ifBlank { null },
            healthInfo = health,
            vision = s.vision.trim().ifBlank { null },
            constraints = s.constraints.trim().ifBlank { null },
            updatedAt = Dates.nowIso(),
        )

        viewModelScope.launch {
            _state.update { it.copy(saving = true, actionError = null) }
            try {
                val saved = withContext(Dispatchers.IO) { container.profileRepository.save(draft) }
                _state.update {
                    it.copy(
                        saving = false,
                        profileId = saved.id,
                        dirty = false,
                        message = "已保存",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, actionError = "保存失败：${e.readable()}") }
            }
        }
    }

    // ---- 技能增删改查 ----

    fun openAddSkill() {
        _state.update {
            it.copy(skillDialogOpen = true, editingSkill = null, skillError = null)
        }
    }

    fun openEditSkill(skill: Skill) {
        _state.update {
            it.copy(skillDialogOpen = true, editingSkill = skill, skillError = null)
        }
    }

    fun closeSkillDialog() {
        if (_state.value.savingSkill) return
        _state.update {
            it.copy(skillDialogOpen = false, editingSkill = null, skillError = null)
        }
    }

    fun saveSkill(name: String, category: String, levelText: String, note: String) {
        val s = _state.value
        if (s.savingSkill) return

        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            _state.update { it.copy(skillError = "技能名称不能为空") }
            return
        }
        val level = levelText.trim().toIntOrNull()
        if (level == null) {
            _state.update { it.copy(skillError = "技能等级必须是 1-10 的整数") }
            return
        }
        if (level !in 1..10) {
            _state.update { it.copy(skillError = "技能等级需在 1-10 之间") }
            return
        }

        val editingId = s.editingSkill?.id
        val draft = Skill(
            id = editingId,
            name = trimmedName,
            category = category.trim().ifBlank { "通用" },
            level = level,
            note = note.trim().ifBlank { null },
        )

        viewModelScope.launch {
            _state.update { it.copy(savingSkill = true, skillError = null) }
            try {
                val skills = withContext(Dispatchers.IO) {
                    if (editingId == null) {
                        container.skillRepository.add(draft)
                    } else {
                        container.skillRepository.update(draft)
                    }
                    container.skillRepository.list()
                }
                _state.update {
                    it.copy(
                        savingSkill = false,
                        skillDialogOpen = false,
                        editingSkill = null,
                        skillError = null,
                        skills = skills,
                        message = if (editingId == null) "技能已新增" else "技能已更新",
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(savingSkill = false, skillError = "保存技能失败：${e.readable()}")
                }
            }
        }
    }

    fun requestDeleteSkill(skill: Skill) {
        _state.update { it.copy(deletingSkill = skill) }
    }

    fun cancelDeleteSkill() {
        _state.update { it.copy(deletingSkill = null) }
    }

    fun confirmDeleteSkill() {
        val skill = _state.value.deletingSkill ?: return
        val id = skill.id ?: return
        _state.update { it.copy(deletingSkill = null, actionError = null) }
        viewModelScope.launch {
            try {
                val skills = withContext(Dispatchers.IO) {
                    container.skillRepository.delete(id)
                    container.skillRepository.list()
                }
                _state.update { it.copy(skills = skills, message = "技能已删除") }
            } catch (e: Exception) {
                _state.update { it.copy(actionError = "删除技能失败：${e.readable()}") }
            }
        }
    }

    // ---- 一次性提示 ----

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    fun clearActionError() {
        _state.update { it.copy(actionError = null) }
    }

    // ---- 校验辅助 ----

    private data class IntField(val value: Int?, val error: String?)

    private fun parseOptionalInt(raw: String, label: String, range: IntRange): IntField {
        val text = raw.trim()
        if (text.isEmpty()) return IntField(null, null)
        val value = text.toIntOrNull() ?: return IntField(null, "${label}必须是整数")
        if (value !in range) {
            return IntField(null, "${label}需在 ${range.first}-${range.last} 之间")
        }
        return IntField(value, null)
    }

    private fun validateDecimal(raw: String, label: String, max: Int): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val value = text.toDoubleOrNull() ?: return "${label}必须是数字"
        if (!value.isFinite()) return "${label}必须是有效数字"
        if (value <= 0.0 || value > max) return "${label}需在 0-$max 之间"
        return null
    }

    private fun Throwable.readable(): String =
        message?.takeIf { it.isNotBlank() } ?: "未知错误（${this::class.simpleName}）"

    private companion object {
        const val KEY_HEIGHT = "height"
        const val KEY_WEIGHT = "weight"
        const val KEY_SLEEP_HABIT = "sleep_habit"
        const val KEY_DIET_PREF = "diet_pref"
        const val KEY_TABOO = "taboo"
        const val KEY_ALLERGY = "allergy_or_disease"
    }
}
