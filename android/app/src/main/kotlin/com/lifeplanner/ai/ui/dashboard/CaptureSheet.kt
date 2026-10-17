package com.lifeplanner.ai.ui.dashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lifeplanner.ai.ui.NavTarget
import com.lifeplanner.ai.ui.components.LpBadge
import com.lifeplanner.ai.ui.components.LpCard
import com.lifeplanner.ai.ui.components.LpDivider
import com.lifeplanner.ai.ui.components.LpErrorBanner
import com.lifeplanner.ai.ui.components.LpGlassCard
import com.lifeplanner.ai.ui.components.LpGradientButton
import com.lifeplanner.ai.ui.components.LpLoading
import com.lifeplanner.ai.ui.components.LpOutlinedField
import com.lifeplanner.ai.ui.components.LpSpacing
import com.lifeplanner.ai.ui.components.lpStaggerIn
import com.lifeplanner.ai.ui.theme.LpColors
import com.lifeplanner.core.ai.CaptureGoalDraft
import com.lifeplanner.core.ai.CaptureLogDraft
import com.lifeplanner.core.ai.CaptureResult
import com.lifeplanner.core.ai.CaptureTaskDraft
import kotlinx.coroutines.delay

/**
 * 「AI 一句话整理」弹窗（DESIGN.md 第 7 节）。
 *
 * 状态全部由 [DashboardViewModel] 持有，这里只负责呈现与回调：
 * 打开弹窗不请求网络，点「让 AI 整理」才调用；结果在用户点「保存到我的计划」前绝不落库。
 * 保存成功由首页统一反馈（Snackbar + 轻震动 + 关闭 + 刷新），弹窗自身不做静默写库。
 */

/** 三个示例，点击后填入输入框（不是直接提交）：给不知道怎么开口的用户一个起点。 */
private val CAPTURE_EXAMPLES = listOf(
    "今日复盘" to "今天学了 1 小时英语，晚上跑了 3 公里，心情不错",
    "明天的安排" to "明天上午 9 点开始写论文，预计 2 小时，晚上想去健身房",
    "随便问问" to "最近总是睡不好，白天没精神，我该怎么调整？",
)

/** 记录标签的取色循环：紫 → 青 → 蓝，三段式而不是单一颜色，避免整片标签糊成一块。 */
private val TAG_COLORS = listOf(0, 1, 2)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureSheet(
    state: DashboardUiState,
    onInputChange: (String) -> Unit,
    onOrganize: () -> Unit,
    onToggleTask: (Int) -> Unit,
    onToggleGoal: (Int) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    onNavigate: (NavTarget) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }
    val input = state.captureInput
    val overLimit = input.length > DashboardViewModel.MAX_CAPTURE_INPUT
    val busy = state.captureBusy || state.captureSaving
    val canOrganize = input.isNotBlank() && !overLimit && !busy

    // 弹窗展开动画约 300ms；直接 requestFocus 会被动画打断，键盘反而弹不出来。
    LaunchedEffect(Unit) {
        delay(320)
        focusRequester.requestFocus()
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = LpColors.SurfaceHigh,
        // 遮罩直接用主题 scrim 令牌（#CC000000），不在页面里另调透明度
        scrimColor = MaterialTheme.colorScheme.scrim,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = LpSpacing.pageHorizontal)
                .padding(bottom = 24.dp),
        ) {
            Column(modifier = Modifier.lpStaggerIn(0)) {
                SheetHeader(onClose = onClose)
            }
            Spacer(Modifier.height(LpSpacing.md))

            Column(modifier = Modifier.lpStaggerIn(1)) {
                InputBlock(
                    state = state,
                    focusRequester = focusRequester,
                    onInputChange = onInputChange,
                    onOrganize = onOrganize,
                    canOrganize = canOrganize,
                    overLimit = overLimit,
                    busy = busy,
                )
            }

            val error = state.captureError
            if (error != null) {
                Spacer(Modifier.height(LpSpacing.md))
                LpErrorBanner(message = error)
                if (state.captureNotConfigured) {
                    Spacer(Modifier.height(LpSpacing.sm))
                    SettingsShortcut(onNavigate = onNavigate, onClose = onClose)
                }
            }

            if (state.captureBusy) {
                LpLoading()
            }

            val result = state.captureResult
            if (result != null) {
                Spacer(Modifier.height(LpSpacing.lg))
                CaptureResultBlock(
                    result = result,
                    selectedTasks = state.captureSelectedTasks,
                    selectedGoals = state.captureSelectedGoals,
                    saving = state.captureSaving,
                    onToggleTask = onToggleTask,
                    onToggleGoal = onToggleGoal,
                )
                Spacer(Modifier.height(LpSpacing.lg))
                Column(modifier = Modifier.lpStaggerIn(2)) {
                    CaptureActions(
                        result = result,
                        selectedTasks = state.captureSelectedTasks,
                        selectedGoals = state.captureSelectedGoals,
                        saving = state.captureSaving,
                        onSave = onSave,
                        onReset = onReset,
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "AI 一句话整理",
                style = MaterialTheme.typography.headlineMedium,
                color = LpColors.TextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "你只管说，AI 把它整理成记录、任务和目标，确认后才保存。",
                style = MaterialTheme.typography.bodySmall,
                color = LpColors.TextMuted,
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "关闭",
                tint = LpColors.TextMuted,
            )
        }
    }
}

@Composable
private fun InputBlock(
    state: DashboardUiState,
    focusRequester: FocusRequester,
    onInputChange: (String) -> Unit,
    onOrganize: () -> Unit,
    canOrganize: Boolean,
    overLimit: Boolean,
    busy: Boolean,
) {
    val input = state.captureInput
    LpOutlinedField(
        value = input,
        onValueChange = onInputChange,
        label = "说说今天 / 明天 / 心里的事",
        modifier = Modifier.focusRequester(focusRequester),
        singleLine = false,
        enabled = !busy,
        minLines = 4,
        placeholder = "例：今天学了 1 小时英语，晚上跑了 3 公里，心情不错；明天想把论文初稿写完",
    )

    Spacer(Modifier.height(LpSpacing.sm))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "写得越具体，整理越准",
            style = MaterialTheme.typography.labelSmall,
            color = LpColors.TextMuted,
        )
        Text(
            text = "${input.length} / ${DashboardViewModel.MAX_CAPTURE_INPUT}",
            style = MaterialTheme.typography.labelSmall,
            color = if (overLimit) LpColors.Danger else LpColors.TextMuted,
        )
    }
    if (overLimit) {
        Spacer(Modifier.height(LpSpacing.xs))
        Text(
            text = "超出 ${DashboardViewModel.MAX_CAPTURE_INPUT} 字，请精简后再整理",
            style = MaterialTheme.typography.labelSmall,
            color = LpColors.Danger,
        )
    }

    Spacer(Modifier.height(LpSpacing.sm))
    ExampleChips(enabled = !busy, onPick = { text -> onInputChange(text) })

    Spacer(Modifier.height(LpSpacing.md))
    LpGradientButton(
        text = if (state.captureBusy) "AI 正在整理…" else "让 AI 整理",
        onClick = onOrganize,
        enabled = canOrganize,
        loading = state.captureBusy,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExampleChips(enabled: Boolean, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LpSpacing.sm)) {
        for ((label, text) in CAPTURE_EXAMPLES) {
            val interaction = remember { MutableInteractionSource() }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) LpColors.AccentAlt else LpColors.TextMuted,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(LpColors.SurfaceVariant)
                    .border(1.dp, LpColors.Border, CircleShape)
                    .clickable(
                        interactionSource = interaction,
                        indication = androidx.compose.foundation.LocalIndication.current,
                        enabled = enabled,
                        onClick = { onPick(text) },
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SettingsShortcut(onNavigate: (NavTarget) -> Unit, onClose: () -> Unit) {
    LpCard(
        onClick = {
            onClose()
            onNavigate(NavTarget.SETTINGS)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = null,
                tint = LpColors.Info,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(LpSpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "还没有配置 AI 服务",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LpColors.TextPrimary,
                )
                Text(
                    text = "去设置里填 API Key，回来就能用",
                    style = MaterialTheme.typography.labelSmall,
                    color = LpColors.TextMuted,
                )
            }
            LpBadge(text = "去设置", color = LpColors.Info)
        }
    }
}

@Composable
private fun CaptureResultBlock(
    result: CaptureResult,
    selectedTasks: Set<Int>,
    selectedGoals: Set<Int>,
    saving: Boolean,
    onToggleTask: (Int) -> Unit,
    onToggleGoal: (Int) -> Unit,
) {
    if (result.isCrisis) {
        // 危机短路：只展示关怀文案，不展示任何"可保存"的结构，避免把安全响应当数据落库。
        CrisisCaptureBlock(result = result)
        return
    }

    Column(modifier = Modifier.animateContentSize()) {
        // 1. AI 回应（可折叠，默认只显示前 4 行）
        var replyExpanded by remember(result.reply) { mutableStateOf(false) }
        Column(modifier = Modifier.lpStaggerIn(0)) {
            ReplyCard(
                reply = result.reply,
                modelName = result.modelName,
                expanded = replyExpanded,
                onToggle = { replyExpanded = !replyExpanded },
            )
        }

        // 2. 整理出的记录：胶囊标签
        val tags = logTags(result.log)
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(LpSpacing.md))
            Column(modifier = Modifier.lpStaggerIn(1)) {
                ResultLabel(text = "整理出的记录")
                Spacer(Modifier.height(LpSpacing.sm))
                TagFlow(tags = tags)
            }
        }

        // 3. 今日任务（默认全选，可取消）
        if (result.tasks.isNotEmpty()) {
            Spacer(Modifier.height(LpSpacing.md))
            Column(modifier = Modifier.lpStaggerIn(2)) {
                ResultLabel(text = "今日任务", trailing = "已选 ${selectedTasks.size}/${result.tasks.size}")
                Spacer(Modifier.height(LpSpacing.xs))
                result.tasks.forEachIndexed { index, task ->
                    if (index > 0) LpDivider(modifier = Modifier.padding(vertical = 2.dp))
                    CaptureTaskRow(
                        task = task,
                        checked = index in selectedTasks,
                        enabled = !saving,
                        onCheckedChange = { onToggleTask(index) },
                    )
                }
            }
        }

        // 4. 新目标（默认全选，可取消）
        if (result.goals.isNotEmpty()) {
            Spacer(Modifier.height(LpSpacing.md))
            Column(modifier = Modifier.lpStaggerIn(3)) {
                ResultLabel(text = "新目标", trailing = "已选 ${selectedGoals.size}/${result.goals.size}")
                Spacer(Modifier.height(LpSpacing.xs))
                result.goals.forEachIndexed { index, goal ->
                    if (index > 0) LpDivider(modifier = Modifier.padding(vertical = 2.dp))
                    CaptureGoalRow(
                        goal = goal,
                        checked = index in selectedGoals,
                        enabled = !saving,
                        onCheckedChange = { onToggleGoal(index) },
                    )
                }
            }
        }

        if (tags.isEmpty() && result.tasks.isEmpty() && result.goals.isEmpty()) {
            Spacer(Modifier.height(LpSpacing.md))
            Text(
                text = "这句话里没有识别到可保存的内容。可以直接聊，也可以换个更具体的说法。",
                style = MaterialTheme.typography.bodySmall,
                color = LpColors.TextMuted,
            )
        }
    }
}

@Composable
private fun ReplyCard(
    reply: String,
    modelName: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    LpGlassCard(modifier = Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "AI 回应",
                style = MaterialTheme.typography.labelSmall,
                color = LpColors.TextMuted,
            )
            Spacer(Modifier.weight(1f))
            LpBadge(text = modelName ?: "AI", color = LpColors.Accent)
        }
        Spacer(Modifier.height(LpSpacing.xs))
        Text(
            text = reply.ifBlank { "（AI 没有额外说明）" },
            style = MaterialTheme.typography.bodyMedium,
            color = LpColors.TextSecondary,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (reply.length > 80) {
            TextButton(onClick = onToggle) {
                Text(
                    text = if (expanded) "收起" else "展开全部",
                    style = MaterialTheme.typography.labelSmall,
                    color = LpColors.AccentAlt,
                )
            }
        }
    }
}

@Composable
private fun CrisisCaptureBlock(result: CaptureResult) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LpColors.Danger.copy(alpha = 0.12f))
            .border(1.dp, LpColors.Danger.copy(alpha = 0.42f), shape)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "安全提示",
                style = MaterialTheme.typography.titleMedium,
                color = LpColors.Danger,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            LpBadge(text = "安全保护", color = LpColors.Danger)
        }
        Spacer(Modifier.height(LpSpacing.sm))
        Text(
            text = result.reply,
            style = MaterialTheme.typography.bodyLarge,
            color = LpColors.TextPrimary,
        )
        Spacer(Modifier.height(LpSpacing.sm))
        Text(
            text = "这一次不会保存任何内容。请优先联系身边可信赖的人，必要时拨打 120 / 110。",
            style = MaterialTheme.typography.bodySmall,
            color = LpColors.TextSecondary,
        )
    }
}

@Composable
private fun ResultLabel(text: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = LpColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = LpColors.TextMuted,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagFlow(tags: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(LpSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(LpSpacing.sm),
    ) {
        tags.forEachIndexed { index, tag ->
            val color = when (TAG_COLORS[index % TAG_COLORS.size]) {
                0 -> LpColors.Accent
                1 -> LpColors.AccentAlt
                else -> LpColors.Info
            }
            LpBadge(text = tag, color = color)
        }
    }
}

@Composable
private fun CaptureTaskRow(
    task: CaptureTaskDraft,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onCheckedChange() },
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = LpColors.Accent,
                uncheckedColor = LpColors.TextMuted,
                checkmarkColor = LpColors.Background,
            ),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        ) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (checked) LpColors.TextPrimary else LpColors.TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append("预计 ${task.estMinutes} 分钟")
                    if (task.goalName.isNotBlank()) append(" · ${task.goalName}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = LpColors.TextMuted,
            )
        }
    }
}

@Composable
private fun CaptureGoalRow(
    goal: CaptureGoalDraft,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onCheckedChange() },
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = LpColors.Accent,
                uncheckedColor = LpColors.TextMuted,
                checkmarkColor = LpColors.Background,
            ),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        ) {
            Text(
                text = goal.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (checked) LpColors.TextPrimary else LpColors.TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${levelLabel(goal.level)} · ${goal.category}",
                style = MaterialTheme.typography.labelSmall,
                color = LpColors.TextMuted,
            )
        }
    }
}

@Composable
private fun CaptureActions(
    result: CaptureResult,
    selectedTasks: Set<Int>,
    selectedGoals: Set<Int>,
    saving: Boolean,
    onSave: () -> Unit,
    onReset: () -> Unit,
) {
    val hasContent = result.log?.isEmpty == false || selectedTasks.isNotEmpty() || selectedGoals.isNotEmpty()
    Column(modifier = Modifier.fillMaxWidth()) {
        LpGradientButton(
            text = "保存到我的计划",
            onClick = onSave,
            enabled = !saving && !result.isCrisis && hasContent,
            loading = saving,
        )
        Spacer(Modifier.height(LpSpacing.xs))
        TextButton(
            onClick = onReset,
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "重新整理",
                style = MaterialTheme.typography.bodyMedium,
                color = LpColors.TextSecondary,
            )
        }
    }
}

/** 把结构化草稿转成给人看的中文胶囊标签；只显示模型真的说了的字段。 */
private fun logTags(log: CaptureLogDraft?): List<String> {
    if (log == null || log.isEmpty) return emptyList()
    val tags = ArrayList<String>(8)
    log.studyMinutes?.let { tags.add("学习 $it 分钟") }
    val sleepStart = log.sleepStart?.takeIf { it.isNotBlank() }
    val sleepEnd = log.sleepEnd?.takeIf { it.isNotBlank() }
    if (sleepStart != null || sleepEnd != null) {
        tags.add("睡眠 ${sleepStart ?: "?"}-${sleepEnd ?: "?"}")
    }
    val exerciseType = log.exerciseType
    val exercise = buildString {
        if (!exerciseType.isNullOrBlank()) append(exerciseType)
        if (log.exerciseMinutes != null) {
            if (isNotEmpty()) append(" ")
            append("${log.exerciseMinutes} 分钟")
        }
    }
    if (exercise.isNotBlank()) tags.add("运动 $exercise")
    log.mood?.let { tags.add("心情 $it/5") }
    log.energy?.let { tags.add("精力 $it/5") }
    for ((key, value) in log.meals) {
        if (value.isBlank()) continue
        tags.add("${mealLabel(key)}：$value")
    }
    // 跨模块属性无法 smart cast，先取到局部变量再判空。
    val doneItems = log.doneItems
    if (!doneItems.isNullOrBlank()) tags.add("完成 ${doneItems.take(18)}")
    if (!log.reflection.isNullOrBlank()) tags.add("反思")
    if (!log.studyNote.isNullOrBlank()) tags.add("学习笔记")
    return tags
}

private fun mealLabel(key: String): String = when (key) {
    "breakfast" -> "早餐"
    "lunch" -> "午餐"
    "dinner" -> "晚餐"
    "snack" -> "加餐"
    else -> key
}

private fun levelLabel(level: String): String = when (level) {
    "vision" -> "愿景"
    "long" -> "长期"
    "stage" -> "阶段"
    "weekly" -> "周目标"
    else -> level
}
