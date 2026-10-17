package com.lifeplanner.ai.ui.goals

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeplanner.ai.AppContainer
import com.lifeplanner.ai.ui.NavTarget
import com.lifeplanner.ai.ui.appViewModel
import com.lifeplanner.ai.ui.components.LpBadge
import com.lifeplanner.ai.ui.components.LpGlassCard
import com.lifeplanner.ai.ui.components.LpEmptyState
import com.lifeplanner.ai.ui.components.LpErrorBanner
import com.lifeplanner.ai.ui.components.LpHint
import com.lifeplanner.ai.ui.components.LpLoading
import com.lifeplanner.ai.ui.components.LpOutlinedField
import com.lifeplanner.ai.ui.components.LpSectionHeader
import com.lifeplanner.ai.ui.components.LpSpacing
import com.lifeplanner.ai.ui.components.lpStaggerIn
import com.lifeplanner.ai.ui.theme.LpColors
import com.lifeplanner.core.logic.GoalLogic
import com.lifeplanner.core.model.Goal
import com.lifeplanner.core.model.GoalLevels
import com.lifeplanner.core.model.GoalNode
import com.lifeplanner.core.model.GoalStatuses
import com.lifeplanner.core.util.Dates
import kotlin.math.roundToInt

@Composable
fun GoalsScreen(container: AppContainer, onNavigate: (NavTarget) -> Unit) {
    val vm: GoalsViewModel = appViewModel(container) { GoalsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()

    var showCreate by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Goal?>(null) }
    var deleting by remember { mutableStateOf<Goal?>(null) }
    var progressGoal by remember { mutableStateOf<Goal?>(null) }
    var aiGoal by remember { mutableStateOf<Goal?>(null) }

    // 仅本地 UI 状态：折叠的节点 id。刷新后仍然保留。
    val collapsed = remember { mutableStateMapOf<Long, Boolean>() }
    val collapsedIds = collapsed.filterValues { it }.keys

    LaunchedEffect(Unit) { vm.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(LpSpacing.sm))
        LpSectionHeader(
            title = "目标树",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { vm.refresh() },
                        enabled = !state.loading,
                    ) {
                        Text("刷新", color = LpColors.TextSecondary)
                    }
                    TextButton(onClick = { showCreate = true }) {
                        Text("+ 新建", color = LpColors.Accent)
                    }
                }
            },
        )

        state.error?.let { message ->
            Spacer(Modifier.height(LpSpacing.sm))
            LpErrorBanner(message = message, onRetry = { vm.refresh() })
        }
        state.message?.let { message ->
            Spacer(Modifier.height(LpSpacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LpHint(text = message, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::consumeMessage) {
                    Text("知道了", color = LpColors.TextMuted)
                }
            }
        }

        // 静默刷新时只显示 2dp 细进度条，不打断正在浏览的目标树（避免整块闪成 loading）。
        AnimatedVisibility(
            visible = state.refreshing,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
        ) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = LpSpacing.sm)
                    .height(2.dp),
                color = LpColors.Accent,
                trackColor = LpColors.SurfaceVariant,
            )
        }

        Spacer(Modifier.height(LpSpacing.sm))

        // 加载 → 内容用 220ms 淡入上移过渡；已有数据时保持内容态，不回到加载态。
        val phase = when {
            state.tree.isNotEmpty() -> GoalPhase.CONTENT
            state.loading -> GoalPhase.LOADING
            state.error != null -> GoalPhase.ERROR
            else -> GoalPhase.EMPTY
        }
        AnimatedContent(
            targetState = phase,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(tween(220)) { height -> height / 20 }) togetherWith
                    fadeOut(tween(160))
            },
            label = "goals-content",
        ) { currentPhase ->
            when (currentPhase) {
                // 有内容时保持列表：静默刷新不改写滚动位置与展开状态。
                GoalPhase.CONTENT -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    goalTreeItems(
                        nodes = state.tree,
                        depth = 0,
                        collapsedIds = collapsedIds,
                        counter = ItemCounter(),
                        onToggle = { id -> collapsed[id] = !(collapsed[id] ?: false) },
                        onEdit = { editing = it },
                        onDelete = { deleting = it },
                        onProgress = { progressGoal = it },
                        onBreakdown = { goal ->
                            aiGoal = goal
                            vm.startBreakdown(goal)
                        },
                    )
                }

                GoalPhase.LOADING -> LpLoading()

                // 错误态由上方 LpErrorBanner（含重试）承担，这里不再重复文案。
                GoalPhase.ERROR -> Unit

                GoalPhase.EMPTY -> LpEmptyState(
                    title = "还没有目标",
                    subtitle = "先创建一个愿景或长期目标",
                )
            }
        }
    }

    if (showCreate) {
        GoalEditDialog(
            editing = null,
            allGoals = state.goals,
            onDismiss = { showCreate = false },
            onSubmit = { goal, isNew, onResult -> vm.saveGoal(goal, isNew, onResult) },
        )
    }

    editing?.let { goal ->
        GoalEditDialog(
            editing = goal,
            allGoals = state.goals,
            onDismiss = { editing = null },
            onSubmit = { edited, isNew, onResult -> vm.saveGoal(edited, isNew, onResult) },
        )
    }

    deleting?.let { goal ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除目标") },
            text = {
                Text(
                    "将删除「${goal.title}」及其全部下级目标（标记删除）。" +
                        "历史任务会保留，不会一并删除。",
                    color = LpColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = goal.id
                        deleting = null
                        if (id != null) vm.deleteGoal(id, goal.title)
                    },
                ) {
                    Text("确认删除", color = LpColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }

    progressGoal?.let { goal ->
        ProgressDialog(
            goal = goal,
            onDismiss = { progressGoal = null },
            onConfirm = { progress ->
                val id = goal.id
                progressGoal = null
                if (id != null) vm.confirmProgress(id, progress)
            },
        )
    }

    aiGoal?.let { goal ->
        BreakdownDialog(
            goal = goal,
            state = state,
            onDismiss = {
                aiGoal = null
                vm.clearBreakdown()
            },
            onRetry = { vm.startBreakdown(goal) },
            onGoSettings = {
                aiGoal = null
                vm.clearBreakdown()
                onNavigate(NavTarget.SETTINGS)
            },
            onConfirm = { selections ->
                // 必须使用发起 AI 请求时记录的目标 id，避免旧结果被确认到当前目标下。
                val requestGoalId = state.aiGoalId
                if (requestGoalId != null && requestGoalId == goal.id) {
                    vm.createFromBreakdown(requestGoalId, selections) {
                        // 弹窗可能已被关闭并切到别的目标；只清理仍属于本次请求的弹窗。
                        if (aiGoal?.id == goal.id) {
                            aiGoal = null
                            vm.clearBreakdown()
                        }
                    }
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 目标树
// ---------------------------------------------------------------------------

/** 列表项序号计数器：用于前 8 项的 40ms 错峰入场。 */
private class ItemCounter(var value: Int = 0)

/** 内容区状态：加载 / 错误 / 空 / 内容。用于 AnimatedContent 的平滑切换。 */
private enum class GoalPhase { LOADING, ERROR, EMPTY, CONTENT }

private fun LazyListScope.goalTreeItems(
    nodes: List<GoalNode>,
    depth: Int,
    collapsedIds: Set<Long>,
    counter: ItemCounter,
    onToggle: (Long) -> Unit,
    onEdit: (Goal) -> Unit,
    onDelete: (Goal) -> Unit,
    onProgress: (Goal) -> Unit,
    onBreakdown: (Goal) -> Unit,
) {
    nodes.forEach { node ->
        val goal = node.goal
        val id = goal.id
        val enterIndex = counter.value++
        val showChildren = node.children.isNotEmpty() && (id == null || id !in collapsedIds)
        item(key = "goal-${id ?: goal.hashCode()}") {
            val breakdownAction: (() -> Unit)? =
                if ((goal.level == GoalLevels.VISION || goal.level == GoalLevels.LONG) && goal.id != null) {
                    { onBreakdown(goal) }
                } else {
                    null
                }
            GoalRow(
                node = node,
                depth = depth,
                enterIndex = enterIndex,
                collapsed = !showChildren,
                onToggle = { id?.let(onToggle) },
                onEdit = { onEdit(goal) },
                onDelete = { onDelete(goal) },
                onProgress = { onProgress(goal) },
                onBreakdown = breakdownAction,
            )
        }
        if (showChildren) {
            goalTreeItems(
                nodes = node.children,
                depth = depth + 1,
                collapsedIds = collapsedIds,
                counter = counter,
                onToggle = onToggle,
                onEdit = onEdit,
                onDelete = onDelete,
                onProgress = onProgress,
                onBreakdown = onBreakdown,
            )
        }
    }
}

@Composable
private fun GoalRow(
    node: GoalNode,
    depth: Int,
    enterIndex: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onProgress: () -> Unit,
    onBreakdown: (() -> Unit)?,
) {
    val goal = node.goal
    // 前 8 项按 40ms 错峰淡入（设计系统动效令牌）；进度条/百分比用 600ms 平滑过渡。
    Box(
        modifier = Modifier
            .padding(start = (depth * 14).dp)
            .lpStaggerIn(enterIndex),
    ) {
        val animatedProgress by animateFloatAsState(
            targetValue = goal.progress.coerceIn(0, 100) / 100f,
            animationSpec = tween(durationMillis = 600),
            label = "goal-progress",
        )
        LpGlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (node.children.isNotEmpty()) {
                    Text(
                        text = if (collapsed) "▸" else "▾",
                        modifier = Modifier
                            .width(28.dp)
                            .clickable(onClick = onToggle)
                            .padding(vertical = 4.dp),
                        color = LpColors.TextSecondary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                } else {
                    Spacer(Modifier.width(28.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LpBadge(text = levelLabel(goal.level), color = levelColor(goal.level))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = goal.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = LpColors.TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp),
                            color = LpColors.Accent,
                            trackColor = LpColors.SurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${(animatedProgress * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = LpColors.TextSecondary,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LpBadge(
                            text = statusLabel(goal.status),
                            color = statusColor(goal.status),
                        )
                        if (goal.category.isNotBlank()) {
                            Spacer(Modifier.width(6.dp))
                            LpBadge(text = goal.category, color = LpColors.TextMuted)
                        }
                        goal.dueDate?.let { due ->
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "截止 ${Dates.format(due)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = LpColors.TextMuted,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                TextButton(onClick = onEdit) {
                    Text("编辑", color = LpColors.TextSecondary)
                }
                TextButton(onClick = onProgress) {
                    Text("改进度", color = LpColors.Info)
                }
                if (onBreakdown != null) {
                    TextButton(onClick = onBreakdown) {
                        Text("AI 拆解", color = LpColors.Accent)
                    }
                }
                TextButton(onClick = onDelete) {
                    Text("删除", color = LpColors.Danger)
                }
            }
        }
    }
}

private fun levelLabel(level: String): String = when (level) {
    GoalLevels.VISION -> "愿景"
    GoalLevels.LONG -> "长期"
    GoalLevels.STAGE -> "阶段"
    GoalLevels.WEEKLY -> "周"
    else -> level
}

private fun levelColor(level: String): Color = when (level) {
    GoalLevels.VISION -> LpColors.Accent
    GoalLevels.LONG -> LpColors.Info
    GoalLevels.STAGE -> LpColors.Warning
    GoalLevels.WEEKLY -> LpColors.TextSecondary
    else -> LpColors.TextMuted
}

private fun statusLabel(status: String): String = when (status) {
    GoalStatuses.IN_PROGRESS -> "进行中"
    GoalStatuses.COMPLETED -> "已完成"
    GoalStatuses.PAUSED -> "已暂停"
    else -> status
}

private fun statusColor(status: String): Color = when (status) {
    GoalStatuses.IN_PROGRESS -> LpColors.Info
    GoalStatuses.COMPLETED -> LpColors.Accent
    GoalStatuses.PAUSED -> LpColors.TextMuted
    else -> LpColors.TextMuted
}

// ---------------------------------------------------------------------------
// 新建 / 编辑
// ---------------------------------------------------------------------------

@Composable
private fun GoalEditDialog(
    editing: Goal?,
    allGoals: List<Goal>,
    onDismiss: () -> Unit,
    onSubmit: (goal: Goal, isNew: Boolean, onResult: (String?) -> Unit) -> Unit,
) {
    var title by remember(editing) { mutableStateOf(editing?.title.orEmpty()) }
    var level by remember(editing) { mutableStateOf(editing?.level ?: GoalLevels.LONG) }
    var parentId by remember(editing) { mutableStateOf(editing?.parentId) }
    var category by remember(editing) { mutableStateOf(editing?.category ?: "职业") }
    var description by remember(editing) { mutableStateOf(editing?.description.orEmpty()) }
    var metric by remember(editing) { mutableStateOf(editing?.metric.orEmpty()) }
    var dueText by remember(editing) {
        mutableStateOf(editing?.dueDate?.let { Dates.format(it) }.orEmpty())
    }
    var parentExpanded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 只允许选择比当前层级更靠前的目标；编辑时排除自己。
    val allowedParents = remember(allGoals, editing?.id, level) {
        GoalLogic.allowedParentIds(allGoals, editing?.id, level)
    }
    val parentLabel = if (parentId == null) {
        "无（作为根目标）"
    } else {
        val parent = allGoals.firstOrNull { it.id == parentId }
        if (parent == null) "无（原父级已不可用）" else "${levelLabel(parent.level)} · ${parent.title}"
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (editing == null) "新建目标" else "编辑目标") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                LpOutlinedField(
                    value = title,
                    onValueChange = {
                        title = it
                        error = null
                    },
                    label = "标题 *",
                )
                Spacer(Modifier.height(10.dp))

                Text(
                    text = "层级",
                    style = MaterialTheme.typography.bodySmall,
                    color = LpColors.TextMuted,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LevelChip("愿景", level == GoalLevels.VISION) {
                        level = GoalLevels.VISION
                        parentId = null
                        error = null
                    }
                    LevelChip("长期", level == GoalLevels.LONG) {
                        level = GoalLevels.LONG
                        if (parentId != null && GoalLogic.allowedParentIds(allGoals, editing?.id, GoalLevels.LONG).none { it.id == parentId }) {
                            parentId = null
                        }
                        error = null
                    }
                    LevelChip("阶段", level == GoalLevels.STAGE) {
                        level = GoalLevels.STAGE
                        if (parentId != null && GoalLogic.allowedParentIds(allGoals, editing?.id, GoalLevels.STAGE).none { it.id == parentId }) {
                            parentId = null
                        }
                        error = null
                    }
                    LevelChip("周目标", level == GoalLevels.WEEKLY) {
                        level = GoalLevels.WEEKLY
                        if (parentId != null && GoalLogic.allowedParentIds(allGoals, editing?.id, GoalLevels.WEEKLY).none { it.id == parentId }) {
                            parentId = null
                        }
                        error = null
                    }
                }
                Spacer(Modifier.height(10.dp))

                Text(
                    text = "父级目标",
                    style = MaterialTheme.typography.bodySmall,
                    color = LpColors.TextMuted,
                )
                Spacer(Modifier.height(4.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { parentExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = parentLabel,
                            color = LpColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(
                        expanded = parentExpanded,
                        onDismissRequest = { parentExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("无（作为根目标）") },
                            onClick = {
                                parentId = null
                                parentExpanded = false
                            },
                        )
                        allowedParents.forEach { candidate ->
                            DropdownMenuItem(
                                text = {
                                    Text("${levelLabel(candidate.level)} · ${candidate.title}")
                                },
                                onClick = {
                                    parentId = candidate.id
                                    parentExpanded = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                LpOutlinedField(
                    value = category,
                    onValueChange = { category = it },
                    label = "分类",
                    placeholder = "如：职业、健康、学习",
                )
                Spacer(Modifier.height(10.dp))
                LpOutlinedField(
                    value = description,
                    onValueChange = { description = it },
                    label = "描述",
                    singleLine = false,
                    minLines = 3,
                )
                Spacer(Modifier.height(10.dp))
                LpOutlinedField(
                    value = metric,
                    onValueChange = { metric = it },
                    label = "衡量指标",
                    placeholder = "可空，如：每周 3 次",
                )
                Spacer(Modifier.height(10.dp))
                LpOutlinedField(
                    value = dueText,
                    onValueChange = {
                        dueText = it
                        error = null
                    },
                    label = "截止日期（YYYY-MM-DD，可空）",
                    placeholder = "2026-12-31",
                )

                error?.let {
                    Spacer(Modifier.height(10.dp))
                    LpErrorBanner(message = it)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val trimmedTitle = title.trim()
                    val dueDate = Dates.parse(dueText)
                    when {
                        trimmedTitle.isEmpty() -> error = "标题不能为空"
                        dueText.isNotBlank() && dueDate == null ->
                            error = "截止日期格式应为 YYYY-MM-DD"
                        parentId != null && allowedParents.none { it.id == parentId } ->
                            error = "父级目标不可用，或层级不低于当前目标"
                        else -> {
                            saving = true
                            error = null
                            val toSave = (editing ?: Goal(title = trimmedTitle)).copy(
                                parentId = parentId,
                                level = level,
                                title = trimmedTitle,
                                category = category.trim().ifBlank { "职业" },
                                description = description.trim().ifBlank { null },
                                metric = metric.trim().ifBlank { null },
                                dueDate = dueDate,
                            )
                            onSubmit(toSave, editing == null) { problem ->
                                saving = false
                                if (problem == null) {
                                    onDismiss()
                                } else {
                                    error = problem
                                }
                            }
                        }
                    }
                },
            ) {
                Text(if (saving) "保存中…" else "保存")
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun LevelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) LpColors.Accent.copy(alpha = 0.16f) else LpColors.SurfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = if (selected) LpColors.Accent else LpColors.TextSecondary,
    )
}

// ---------------------------------------------------------------------------
// 改进度
// ---------------------------------------------------------------------------

@Composable
private fun ProgressDialog(
    goal: Goal,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var value by remember(goal.id, goal.progress) {
        mutableStateOf(goal.progress.coerceIn(0, 100).toFloat())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("改进度 · ${goal.title}") },
        text = {
            Column {
                Text(
                    text = "当前进度：${goal.progress}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LpColors.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${value.roundToInt()}%",
                    style = MaterialTheme.typography.titleLarge,
                    color = LpColors.Accent,
                )
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 0f..100f,
                )
                Text(
                    text = "确认后立即写入本地数据库；达到 100% 会自动标记为已完成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = LpColors.TextMuted,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.roundToInt().coerceIn(0, 100)) },
            ) {
                Text("确认写入", color = LpColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ---------------------------------------------------------------------------
// AI 拆解：结果只在这里被勾选，用户点“创建”之前绝不落库
// ---------------------------------------------------------------------------

@Composable
private fun BreakdownDialog(
    goal: Goal,
    state: GoalsUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onGoSettings: () -> Unit,
    onConfirm: (List<ConfirmedStage>) -> Unit,
) {
    // 只接受与当前弹窗目标匹配的请求结果，防止 A 的迟到结果串到 B。
    val matchesGoal = state.aiGoalId == goal.id
    val breakdown = if (matchesGoal) state.breakdown else null
    val aiError = if (matchesGoal) state.aiError else null
    val aiNotConfigured = matchesGoal && state.aiNotConfigured
    val aiLoading = matchesGoal && state.aiLoading
    val creating = state.creating
    val selectedStages = remember(breakdown) { mutableStateMapOf<Int, Boolean>() }
    val selectedWeeks = remember(breakdown) { mutableStateMapOf<String, Boolean>() }

    var selectionCount = 0
    if (breakdown != null) {
        breakdown.stages.forEachIndexed { stageIndex, stage ->
            if (selectedStages[stageIndex] == true) {
                selectionCount += 1
                stage.weeklyGoals.indices.forEach { weekIndex ->
                    if (selectedWeeks["$stageIndex-$weekIndex"] == true) selectionCount += 1
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI 拆解 · ${goal.title}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    aiLoading -> {
                        LpLoading()
                        Text(
                            text = "AI 正在拆解目标，请稍候…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LpColors.TextSecondary,
                        )
                    }

                    aiError != null -> {
                        LpErrorBanner(message = aiError)
                        if (!aiNotConfigured) {
                            TextButton(onClick = onRetry) {
                                Text("重试", color = LpColors.Accent)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (aiNotConfigured) {
                                "请先在设置中填写 API Key，再回来重试。"
                            } else {
                                "AI 结果不会自动保存；请重试或稍后再试。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = LpColors.TextMuted,
                        )
                    }

                    breakdown != null -> {
                        Text(
                            text = "勾选要创建为「阶段 / 周目标」的条目。确认前不会写入数据库。",
                            style = MaterialTheme.typography.bodySmall,
                            color = LpColors.TextMuted,
                        )
                        Spacer(Modifier.height(8.dp))
                        breakdown.stages.forEachIndexed { stageIndex, stage ->
                            val stageChecked = selectedStages[stageIndex] == true
                            LpGlassCard(modifier = Modifier.padding(bottom = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = stageChecked,
                                        onCheckedChange = { selectedStages[stageIndex] = it },
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stage.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = LpColors.TextPrimary,
                                        )
                                        stage.description?.takeIf { it.isNotBlank() }?.let {
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                text = it,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = LpColors.TextSecondary,
                                            )
                                        }
                                        stage.metric?.takeIf { it.isNotBlank() }?.let {
                                            Spacer(Modifier.height(4.dp))
                                            LpBadge(text = "指标：$it", color = LpColors.Info)
                                        }
                                    }
                                }

                                stage.weeklyGoals.forEachIndexed { weekIndex, week ->
                                    val key = "$stageIndex-$weekIndex"
                                    val weekChecked = selectedWeeks[key] == true
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 28.dp, top = 2.dp)
                                            .clickable(enabled = stageChecked) {
                                                selectedWeeks[key] = !weekChecked
                                            },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = weekChecked,
                                            onCheckedChange = null,
                                            enabled = stageChecked,
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = week.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (stageChecked) {
                                                    LpColors.TextPrimary
                                                } else {
                                                    LpColors.TextMuted
                                                },
                                            )
                                            week.description?.takeIf { it.isNotBlank() }?.let {
                                                Text(
                                                    text = it,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = LpColors.TextMuted,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        Text(
                            text = "暂无拆解结果。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LpColors.TextMuted,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (breakdown != null && aiError == null && !aiLoading) {
                TextButton(
                    enabled = selectionCount > 0 && !creating,
                    onClick = {
                        val selections = breakdown.stages.mapIndexedNotNull { stageIndex, stage ->
                            if (selectedStages[stageIndex] == true) {
                                ConfirmedStage(
                                    stage = stage,
                                    weeks = stage.weeklyGoals.mapIndexedNotNull { weekIndex, week ->
                                        if (selectedWeeks["$stageIndex-$weekIndex"] == true) week else null
                                    },
                                )
                            } else {
                                null
                            }
                        }
                        if (selections.isNotEmpty()) onConfirm(selections)
                    },
                ) {
                    Text(if (creating) "创建中…" else "创建选中项（$selectionCount）")
                }
            } else if (aiNotConfigured) {
                TextButton(onClick = onGoSettings) {
                    Text("去设置", color = LpColors.Accent)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

