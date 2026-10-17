package com.lifeplanner.ai.ui.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeplanner.ai.AppContainer
import com.lifeplanner.ai.ui.NavTarget
import com.lifeplanner.ai.ui.appViewModel
import com.lifeplanner.ai.ui.components.LpBadge
import com.lifeplanner.ai.ui.components.LpCard
import com.lifeplanner.ai.ui.components.LpEmptyState
import com.lifeplanner.ai.ui.components.LpErrorBanner
import com.lifeplanner.ai.ui.components.LpHint
import com.lifeplanner.ai.ui.components.LpLoading
import com.lifeplanner.ai.ui.components.LpOutlinedField
import com.lifeplanner.ai.ui.components.LpPrimaryButton
import com.lifeplanner.ai.ui.components.LpSectionTitle
import com.lifeplanner.ai.ui.components.LpSpacing
import com.lifeplanner.ai.ui.theme.LpColors
import com.lifeplanner.core.model.Skill
import kotlinx.coroutines.delay

/**
 * 个人档案 + 技能管理。
 *
 * 档案是 AI 编排的输入底座：基础信息、健康底座、愿景与约束都会进入 AI 上下文。
 * 健康数据外发说明必须始终可见（用户已同意，需明示）。
 */
@Composable
fun ProfileScreen(container: AppContainer, onNavigate: (NavTarget) -> Unit) {
    val vm: ProfileViewModel = appViewModel(container) { ProfileViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    // 加载失败时表单为空，禁止编辑与保存，防止空档案覆盖磁盘上的真实数据。
    val fieldsEnabled = state.loadSucceeded && !state.saving

    // 首次进入负责加载；ON_RESUME 负责从设置页返回时按需刷新。
    // 两者都走 refreshIfSafe：有未保存编辑时不会被磁盘值覆盖，重复触发由 VM 合并。
    LaunchedEffect(Unit) { vm.refreshIfSafe() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshIfSafe() }
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(2500)
            vm.consumeMessage()
        }
    }
    LaunchedEffect(state.actionError) {
        if (state.actionError != null) {
            delay(5000)
            vm.clearActionError()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // 静默刷新（从设置页返回等）：只显示 2dp 细进度条，表单保持可见可读。
        AnimatedVisibility(
            visible = state.refreshing,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
        ) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = LpColors.Accent,
                trackColor = LpColors.SurfaceVariant,
            )
        }

        // 首次进入：加载态 → 内容做 220ms 淡入上移；已有内容时不再回到加载态。
        AnimatedContent(
            targetState = state.loading && !state.loaded,
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(tween(220)) { height -> height / 20 }) togetherWith
                    fadeOut(tween(160)) using SizeTransform(clip = false)
            },
            label = "profile-content",
        ) { isLoading ->
            if (isLoading) {
                LpLoading()
            } else {

        state.message?.let { message ->
            LpBadge(text = message, color = LpColors.Accent)
            Spacer(Modifier.height(LpSpacing.md))
        }

        state.loadError?.let { error ->
            LpErrorBanner(message = error, onRetry = { vm.refresh() })
            Spacer(Modifier.height(LpSpacing.md))
        }
        state.actionError?.let { error ->
            LpErrorBanner(message = error)
            Spacer(Modifier.height(LpSpacing.md))
        }

        // 建档访谈入口：档案是 AI 编排的底座，访谈是最省力的填写方式。
        LpCard(onClick = { onNavigate(NavTarget.INTERVIEW) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AI 访谈建档",
                        style = MaterialTheme.typography.titleMedium,
                        color = LpColors.TextPrimary,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "不想逐项填？聊 3 分钟，自动整理成档案与目标。",
                        style = MaterialTheme.typography.bodySmall,
                        color = LpColors.TextMuted,
                    )
                }
                Text(
                    text = "→",
                    style = MaterialTheme.typography.titleMedium,
                    color = LpColors.Accent,
                )
            }
        }
        Spacer(Modifier.height(LpSpacing.md))

        // ---- 基础信息 ----
        LpSectionTitle("基础信息")
        Spacer(Modifier.height(LpSpacing.sm))
        LpCard {
            ProfileField(fieldsEnabled, state.age, vm::setAge, "年龄", "例如 28")
            Spacer(Modifier.height(LpSpacing.sm))
            ProfileField(fieldsEnabled, state.gender, vm::setGender, "性别", "例如 女")
            Spacer(Modifier.height(LpSpacing.sm))
            ProfileField(fieldsEnabled, state.occupation, vm::setOccupation, "职业", "例如 产品经理")
            Spacer(Modifier.height(LpSpacing.sm))
            ProfileField(fieldsEnabled, state.industry, vm::setIndustry, "行业", "例如 互联网")
            Spacer(Modifier.height(LpSpacing.sm))
            ProfileField(fieldsEnabled, state.yearsOfWork, vm::setYearsOfWork, "工作年限", "例如 5")
            Spacer(Modifier.height(LpSpacing.sm))
            ProfileField(fieldsEnabled, state.education, vm::setEducation, "学历", "例如 本科")
            Spacer(Modifier.height(LpSpacing.sm))
            ProfileField(fieldsEnabled, state.major, vm::setMajor, "专业", "例如 计算机科学")
        }

        Spacer(Modifier.height(LpSpacing.lg))

        // ---- 健康底座 ----
        LpSectionTitle("健康底座")
        Spacer(Modifier.height(LpSpacing.sm))
        LpCard {
            ProfileField(fieldsEnabled, state.height, vm::setHeight, "身高（cm）", "例如 170")
            Spacer(Modifier.height(LpSpacing.sm))
            ProfileField(fieldsEnabled, state.weight, vm::setWeight, "体重（kg）", "例如 65")
            Spacer(Modifier.height(LpSpacing.sm))
            ProfileField(fieldsEnabled, state.sleepHabit, vm::setSleepHabit, "睡眠习惯", "例如 23:30 入睡，7:00 起床")
            Spacer(Modifier.height(LpSpacing.sm))
            ProfileField(fieldsEnabled, state.dietPref, vm::setDietPref, "饮食偏好", "例如 清淡、少油")
            Spacer(Modifier.height(LpSpacing.sm))
            ProfileField(fieldsEnabled, state.taboo, vm::setTaboo, "禁忌", "例如 不吃辣")
            Spacer(Modifier.height(LpSpacing.sm))
            ProfileField(fieldsEnabled, state.allergyOrDisease, vm::setAllergyOrDisease, "过敏 / 疾病", "例如 花粉过敏")
            Spacer(Modifier.height(LpSpacing.md))
            LpHint(
                text = "健康数据（身高、体重、睡眠、饮食、禁忌、过敏/疾病）会随 AI 请求发送给你在设置中配置的模型服务商。请确认你已同意这一方式。",
                warn = true,
            )
            TextButton(onClick = { onNavigate(NavTarget.SETTINGS) }) {
                Text("去设置模型服务商", color = LpColors.Info)
            }
        }

        Spacer(Modifier.height(LpSpacing.lg))

        // ---- 愿景与约束 ----
        LpSectionTitle("愿景与约束")
        Spacer(Modifier.height(LpSpacing.sm))
        LpCard {
            ProfileField(
                enabled = fieldsEnabled,
                value = state.vision,
                onValueChange = vm::setVision,
                label = "人生愿景",
                placeholder = "你希望成为什么样的人、过什么样的生活",
                singleLine = false,
                minLines = 3,
            )
            Spacer(Modifier.height(LpSpacing.sm))
            ProfileField(
                enabled = fieldsEnabled,
                value = state.constraints,
                onValueChange = vm::setConstraints,
                label = "现实约束",
                placeholder = "时间、金钱、家庭、健康等限制",
                singleLine = false,
                minLines = 3,
            )
        }

        Spacer(Modifier.height(LpSpacing.lg))

        LpPrimaryButton(
            text = if (state.saving) "保存中…" else "保存档案",
            onClick = vm::saveProfile,
            enabled = state.loadSucceeded && !state.saving,
        )

        Spacer(Modifier.height(LpSpacing.xl))

        // ---- 技能 ----
        LpSectionTitle(
            text = "技能",
            trailing = {
                TextButton(onClick = vm::openAddSkill, enabled = state.loadSucceeded) {
                    Text("新增", color = LpColors.Accent)
                }
            },
        )
        Spacer(Modifier.height(LpSpacing.sm))
        if (state.skills.isEmpty()) {
            LpEmptyState(
                title = "还没有技能",
                subtitle = "添加技能后，AI 顾问会更了解你的能力结构",
            )
        } else {
            state.skills.forEachIndexed { index, skill ->
                // 前 8 条按 40ms 错峰淡入（DESIGN.md §5）。
                StaggerIn(index = index) {
                    SkillRow(
                        skill = skill,
                        enabled = state.loadSucceeded,
                        onEdit = { vm.openEditSkill(skill) },
                        onDelete = { vm.requestDeleteSkill(skill) },
                    )
                    Spacer(Modifier.height(LpSpacing.sm))
                }
            }
        }

        Spacer(Modifier.height(LpSpacing.xl))
            }
        }
    }

    if (state.skillDialogOpen) {
        SkillEditorDialog(
            editing = state.editingSkill,
            error = state.skillError,
            saving = state.savingSkill,
            onDismiss = vm::closeSkillDialog,
            onConfirm = vm::saveSkill,
        )
    }

    state.deletingSkill?.let { skill ->
        AlertDialog(
            onDismissRequest = vm::cancelDeleteSkill,
            title = { Text("删除技能") },
            text = { Text("确定删除「${skill.name}」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = vm::confirmDeleteSkill) {
                    Text("删除", color = LpColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelDeleteSkill) {
                    Text("取消", color = LpColors.TextMuted)
                }
            },
            containerColor = LpColors.Surface,
            titleContentColor = LpColors.TextPrimary,
            textContentColor = LpColors.TextSecondary,
        )
    }
}

@Composable
private fun ProfileField(
    enabled: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    LpOutlinedField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        singleLine = singleLine,
        minLines = minLines,
        enabled = enabled,
    )
}

@Composable
private fun SkillRow(
    skill: Skill,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    LpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = LpColors.TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    LpBadge(text = skill.category.ifBlank { "通用" })
                    Spacer(Modifier.width(6.dp))
                    LpBadge(text = "Lv.${skill.level}", color = LpColors.Info)
                }
            }
            TextButton(onClick = onEdit, enabled = enabled) {
                Text("编辑", color = LpColors.Accent)
            }
            TextButton(onClick = onDelete, enabled = enabled) {
                Text("删除", color = LpColors.Danger)
            }
        }
        skill.note?.takeIf { it.isNotBlank() }?.let { note ->
            Spacer(Modifier.height(LpSpacing.sm))
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = LpColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun SkillEditorDialog(
    editing: Skill?,
    error: String?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, category: String, level: String, note: String) -> Unit,
) {
    val key = editing?.id ?: -1L
    var name by remember(key) { mutableStateOf(editing?.name.orEmpty()) }
    var category by remember(key) { mutableStateOf(editing?.category ?: "通用") }
    var level by remember(key) { mutableStateOf((editing?.level ?: 5).toString()) }
    var note by remember(key) { mutableStateOf(editing?.note.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing == null) "新增技能" else "编辑技能") },
        text = {
            Column {
                LpOutlinedField(
                    value = name,
                    onValueChange = { name = it },
                    label = "技能名称",
                    placeholder = "例如 Kotlin",
                )
                Spacer(Modifier.height(LpSpacing.sm))
                LpOutlinedField(
                    value = category,
                    onValueChange = { category = it },
                    label = "分类",
                    placeholder = "例如 编程",
                )
                Spacer(Modifier.height(LpSpacing.sm))
                LpOutlinedField(
                    value = level,
                    onValueChange = { level = it },
                    label = "等级（1-10）",
                    placeholder = "5",
                )
                Spacer(Modifier.height(LpSpacing.sm))
                LpOutlinedField(
                    value = note,
                    onValueChange = { note = it },
                    label = "备注",
                    placeholder = "可选",
                    singleLine = false,
                    minLines = 2,
                )
                if (error != null) {
                    Spacer(Modifier.height(LpSpacing.sm))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = LpColors.Danger,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, category, level, note) },
                enabled = !saving,
            ) {
                Text(if (saving) "保存中…" else "保存", color = LpColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text("取消", color = LpColors.TextMuted)
            }
        },
        containerColor = LpColors.Surface,
        titleContentColor = LpColors.TextPrimary,
        textContentColor = LpColors.TextSecondary,
    )
}

/**
 * 列表项错峰进入：前 8 项每项 40ms 延迟，淡入并上移 12dp；超过后一次性显示（性能）。
 * 设计系统提供公共动效（ui/components/LpMotion.kt 的 lpStaggerIn）后可直接替换。
 */
@Composable
private fun StaggerIn(
    index: Int,
    maxAnimated: Int = 8,
    content: @Composable () -> Unit,
) {
    val animated = index < maxAnimated
    val progress = remember { Animatable(if (animated) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animated) {
            delay(index * 40L)
            progress.animateTo(1f, tween(durationMillis = 220))
        }
    }
    Column(
        modifier = Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 12.dp.toPx()
        },
    ) { content() }
}