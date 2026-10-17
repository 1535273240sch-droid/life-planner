package com.lifeplanner.ai.ui.log

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeplanner.ai.AppContainer
import com.lifeplanner.ai.ui.NavTarget
import com.lifeplanner.ai.ui.appViewModel
import com.lifeplanner.ai.ui.components.LpBadge
import com.lifeplanner.ai.ui.components.LpGlassCard
import com.lifeplanner.ai.ui.components.LpEmptyState
import com.lifeplanner.ai.ui.components.LpErrorBanner
import com.lifeplanner.ai.ui.components.LpLoading
import com.lifeplanner.ai.ui.components.LpOutlinedField
import com.lifeplanner.ai.ui.components.LpGradientButton
import com.lifeplanner.ai.ui.components.LpSectionHeader
import com.lifeplanner.ai.ui.components.LpSpacing
import com.lifeplanner.ai.ui.components.lpStaggerIn
import com.lifeplanner.ai.ui.theme.LpColors
import com.lifeplanner.core.logic.LogStatsLogic
import com.lifeplanner.core.model.DailyLog
import com.lifeplanner.core.util.Dates
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate

/**
 * 今日记录：按日期加载/编辑/保存每日日志，并展示最近 7 天。
 * 这里不调用任何 AI 能力。
 */
@Composable
@Suppress("UNUSED_PARAMETER") // 页面统一签名保留 onNavigate，本页暂无跨页入口
fun LogScreen(container: AppContainer, onNavigate: (NavTarget) -> Unit) {
    val vm: LogViewModel = appViewModel(container) { LogViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        DateSwitcher(
            state = state,
            onPrevious = vm::previousDay,
            onNext = vm::nextDay,
        )

        // 静默刷新 / 切换日期时的极细进度条：只占 2dp，不推挤内容、不闪成整屏 loading。
        AnimatedVisibility(
            visible = state.refreshing || (state.loading && state.contentDate != null),
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

        state.error?.let { message ->
            Spacer(Modifier.height(LpSpacing.sm))
            LpErrorBanner(
                message = message,
                onRetry = if (state.errorFromLoad) vm::retry else null,
            )
        }

        state.message?.let { message ->
            Spacer(Modifier.height(LpSpacing.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(LpColors.Accent.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LpColors.Accent,
                )
                TextButton(onClick = vm::consumeMessage) {
                    Text("关闭", color = LpColors.Accent)
                }
            }
        }

        if (state.fieldErrors.isNotEmpty()) {
            Spacer(Modifier.height(LpSpacing.sm))
            LpErrorBanner("请先修正表单中标记的字段，再保存。")
        }

        // 内容区按「已加载的日期」切换：旧内容先留着，新数据回来后再 180ms 淡入 + 横向轻移。
        // 首次加载（contentDate 为 null）才显示骨架，避免每一次刷新都整块闪。
        AnimatedContent(
            targetState = state.contentDate,
            transitionSpec = {
                val from = initialState
                val to = targetState
                val forward = from != null && to != null && to > from
                val direction = if (forward) 1 else -1
                (
                    fadeIn(tween(180)) +
                        slideInHorizontally(tween(180)) { width -> width / 16 * direction }
                    ) togetherWith
                    fadeOut(tween(120)) using SizeTransform(clip = false)
            },
            label = "log-date-content",
        ) { contentDate ->
            when {
                contentDate != null -> LogFormContent(
                    state = state,
                    onUpdate = vm::updateForm,
                    onSave = vm::save,
                )

                state.loading -> LpLoading()

                // 加载失败时不展示表单，避免把上一天的旧内容误存到当前日期。
                else -> LpEmptyState(
                    title = "暂时无法加载这一天",
                    subtitle = "请点击上方“重试”，或切换到其他日期",
                )
            }
        }

        Spacer(Modifier.height(LpSpacing.xl))
        LpSectionHeader("最近 7 天")
        Spacer(Modifier.height(LpSpacing.sm))
        RecentLogs(
            logs = state.recentLogs,
            today = state.today,
            selectedDate = state.selectedDate,
            onClick = vm::selectDate,
        )
        Spacer(Modifier.height(LpSpacing.xl))
    }

    state.pendingDate?.let { target ->
        AlertDialog(
            onDismissRequest = vm::cancelDiscard,
            title = { Text("放弃未保存的修改？") },
            text = {
                Text("切换到 ${Dates.format(target)} 会丢失当前表单中尚未保存的修改。")
            },
            confirmButton = {
                TextButton(onClick = vm::confirmDiscard) {
                    Text("放弃并切换", color = LpColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelDiscard) {
                    Text("继续编辑", color = LpColors.TextSecondary)
                }
            },
        )
    }
}

@Composable
private fun DateSwitcher(
    state: LogUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val canGoNext = !state.loading && !state.saving && state.selectedDate < state.today
    LpGlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onPrevious,
                enabled = !state.loading && !state.saving,
            ) {
                Text(
                    text = "< 前一天",
                    color = if (!state.loading && !state.saving) LpColors.Info else LpColors.TextMuted,
                )
            }
            // 日期标题单独做 180ms 淡入 + 轻微横向滑动，和内容切换保持同一节奏。
            AnimatedContent(
                targetState = state.selectedDate,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (
                        fadeIn(tween(180)) +
                            slideInHorizontally(tween(180)) { width -> width / 12 * direction }
                        ) togetherWith (
                        fadeOut(tween(120))
                        )
                },
                label = "log-date-label",
            ) { date ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = Dates.format(date),
                        style = MaterialTheme.typography.titleMedium,
                        color = LpColors.TextPrimary,
                    )
                    Text(
                        text = if (date == state.today) "今天" else "历史记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (date == state.today) LpColors.Accent else LpColors.TextMuted,
                    )
                }
            }
            TextButton(onClick = onNext, enabled = canGoNext) {
                Text(
                    text = "后一天 >",
                    color = if (canGoNext) LpColors.Info else LpColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun LogFormContent(
    state: LogUiState,
    onUpdate: ((LogForm) -> LogForm) -> Unit,
    onSave: () -> Unit,
) {
    val form = state.form
    val errors = state.fieldErrors
    // 保存期间禁用所有输入，避免“已保存”之后的新编辑没有落库；
    // 切换日期加载中也禁用，防止把上一天的内容改动写进新日期。
    val canEdit = !state.saving && !state.loading

    Spacer(Modifier.height(LpSpacing.lg))
    LpSectionHeader("今日完成")
    Spacer(Modifier.height(LpSpacing.sm))
    LpGlassCard {
        LpOutlinedField(
            value = form.doneItems,
            onValueChange = { value -> onUpdate { it.copy(doneItems = value) } },
            label = "今日完成事项",
            enabled = canEdit,
            placeholder = "一行一件，写清楚具体做了什么",
            singleLine = false,
            minLines = 3,
        )
    }

    Spacer(Modifier.height(LpSpacing.lg))
    LpSectionHeader("学习")
    Spacer(Modifier.height(LpSpacing.sm))
    LpGlassCard {
        LpOutlinedField(
            value = form.studyMinutes,
            onValueChange = { value -> onUpdate { it.copy(studyMinutes = value) } },
            label = "学习时长（分钟）",
            enabled = canEdit,
            placeholder = "例如 60",
        )
        Text(
            text = "留空按 0 保存",
            style = MaterialTheme.typography.bodySmall,
            color = LpColors.TextMuted,
            modifier = Modifier.padding(top = LpSpacing.xs, start = LpSpacing.xs),
        )
        FieldError(errors["studyMinutes"])
        Spacer(Modifier.height(LpSpacing.sm))
        LpOutlinedField(
            value = form.studyNote,
            onValueChange = { value -> onUpdate { it.copy(studyNote = value) } },
            label = "学习内容",
            enabled = canEdit,
            placeholder = "学了什么、有什么收获",
            singleLine = false,
            minLines = 2,
        )
    }

    Spacer(Modifier.height(LpSpacing.lg))
    LpSectionHeader("三餐 / 加餐")
    Spacer(Modifier.height(LpSpacing.sm))
    LpGlassCard {
        LpOutlinedField(
            value = form.breakfast,
            onValueChange = { value -> onUpdate { it.copy(breakfast = value) } },
            label = "早餐",
            enabled = canEdit,
            placeholder = "吃了什么",
        )
        Spacer(Modifier.height(LpSpacing.sm))
        LpOutlinedField(
            value = form.lunch,
            onValueChange = { value -> onUpdate { it.copy(lunch = value) } },
            label = "午餐",
            enabled = canEdit,
            placeholder = "吃了什么",
        )
        Spacer(Modifier.height(LpSpacing.sm))
        LpOutlinedField(
            value = form.dinner,
            onValueChange = { value -> onUpdate { it.copy(dinner = value) } },
            label = "晚餐",
            enabled = canEdit,
            placeholder = "吃了什么",
        )
        Spacer(Modifier.height(LpSpacing.sm))
        LpOutlinedField(
            value = form.snack,
            onValueChange = { value -> onUpdate { it.copy(snack = value) } },
            label = "加餐",
            enabled = canEdit,
            placeholder = "吃了什么",
        )
    }

    Spacer(Modifier.height(LpSpacing.lg))
    LpSectionHeader("睡眠")
    Spacer(Modifier.height(LpSpacing.sm))
    LpGlassCard {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                LpOutlinedField(
                    value = form.sleepStart,
                    onValueChange = { value -> onUpdate { it.copy(sleepStart = value) } },
                    label = "入睡时间",
                    enabled = canEdit,
                    placeholder = "23:00",
                )
                FieldError(errors["sleepStart"])
            }
            Spacer(Modifier.width(LpSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                LpOutlinedField(
                    value = form.sleepEnd,
                    onValueChange = { value -> onUpdate { it.copy(sleepEnd = value) } },
                    label = "起床时间",
                    enabled = canEdit,
                    placeholder = "07:00",
                )
                FieldError(errors["sleepEnd"])
            }
        }
        Spacer(Modifier.height(LpSpacing.xs))
        Text(
            text = "格式 HH:mm；跨天（如 23:30 入睡、07:00 起床）会自动按夜间计算。",
            style = MaterialTheme.typography.bodySmall,
            color = LpColors.TextMuted,
        )
    }

    Spacer(Modifier.height(LpSpacing.lg))
    LpSectionHeader("运动")
    Spacer(Modifier.height(LpSpacing.sm))
    LpGlassCard {
        LpOutlinedField(
            value = form.exerciseType,
            onValueChange = { value -> onUpdate { it.copy(exerciseType = value) } },
            label = "运动类型",
            enabled = canEdit,
            placeholder = "例如 跑步 / 力量训练",
        )
        Spacer(Modifier.height(LpSpacing.sm))
        LpOutlinedField(
            value = form.exerciseMinutes,
            onValueChange = { value -> onUpdate { it.copy(exerciseMinutes = value) } },
            label = "运动时长（分钟）",
            enabled = canEdit,
            placeholder = "例如 30",
        )
        Text(
            text = "留空按 0 保存",
            style = MaterialTheme.typography.bodySmall,
            color = LpColors.TextMuted,
            modifier = Modifier.padding(top = LpSpacing.xs, start = LpSpacing.xs),
        )
        FieldError(errors["exerciseMinutes"])
    }

    Spacer(Modifier.height(LpSpacing.lg))
    LpSectionHeader("状态")
    Spacer(Modifier.height(LpSpacing.sm))
    LpGlassCard {
        ScoreSelector(
            label = "心情",
            value = form.mood,
            enabled = canEdit,
            onChange = { value -> onUpdate { it.copy(mood = value) } },
        )
        FieldError(errors["mood"])
        Spacer(Modifier.height(LpSpacing.md))
        ScoreSelector(
            label = "精力",
            value = form.energy,
            enabled = canEdit,
            onChange = { value -> onUpdate { it.copy(energy = value) } },
        )
        FieldError(errors["energy"])
        Spacer(Modifier.height(LpSpacing.xs))
        Text(
            text = "1 = 很差，5 = 很好",
            style = MaterialTheme.typography.bodySmall,
            color = LpColors.TextMuted,
        )
    }

    Spacer(Modifier.height(LpSpacing.lg))
    LpSectionHeader("反思")
    Spacer(Modifier.height(LpSpacing.sm))
    LpGlassCard {
        LpOutlinedField(
            value = form.reflection,
            onValueChange = { value -> onUpdate { it.copy(reflection = value) } },
            label = "今日反思",
            enabled = canEdit,
            placeholder = "今天哪里做得好？哪里可以调整？",
            singleLine = false,
            minLines = 3,
        )
    }

    Spacer(Modifier.height(LpSpacing.xl))
    // 保存中由按钮内联 spinner 表达，文案保持不变，避免按钮宽度跳动。
    LpGradientButton(
        text = "保存记录",
        onClick = onSave,
        enabled = !state.saving && !state.loading,
        loading = state.saving,
    )
}

@Composable
private fun FieldError(message: String?) {
    if (message != null) {
        Text(
            text = message,
            modifier = Modifier.padding(top = LpSpacing.xs, start = LpSpacing.xs),
            style = MaterialTheme.typography.bodySmall,
            color = LpColors.Danger,
        )
    }
}

@Composable
private fun ScoreSelector(
    label: String,
    value: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(48.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = LpColors.TextSecondary,
        )
        for (score in 1..5) {
            val selected = score == value
            val fill = if (selected) {
                if (enabled) LpColors.Accent else LpColors.Accent.copy(alpha = 0.4f)
            } else {
                LpColors.SurfaceVariant
            }
            Box(
                modifier = Modifier
                    .padding(end = LpSpacing.sm)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(fill)
                    .clickable(enabled = enabled) { onChange(score) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = score.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) LpColors.Background else LpColors.TextSecondary,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun RecentLogs(
    logs: List<DailyLog>,
    today: LocalDate,
    selectedDate: LocalDate,
    onClick: (LocalDate) -> Unit,
) {
    if (logs.isEmpty()) {
        LpEmptyState(
            title = "最近 7 天还没有记录",
            subtitle = "保存今天的记录后会显示在这里",
        )
    } else {
        logs.forEachIndexed { index, log ->
        // 前 8 条按 40ms 错峰淡入，超过后一次性显示（设计系统动效令牌）。
        LpGlassCard(
            modifier = Modifier
                .padding(bottom = LpSpacing.sm)
                .lpStaggerIn(index),
            onClick = { onClick(log.date) },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = Dates.format(log.date),
                    style = MaterialTheme.typography.titleMedium,
                    color = LpColors.TextPrimary,
                )
                if (log.date == today) {
                    Spacer(Modifier.width(LpSpacing.sm))
                    LpBadge("今天", LpColors.Accent)
                }
                if (log.date == selectedDate) {
                    Spacer(Modifier.width(LpSpacing.xs))
                    LpBadge("当前", LpColors.Info)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "心情 ${log.mood}/5",
                    style = MaterialTheme.typography.bodySmall,
                    color = LpColors.TextSecondary,
                )
            }
            Spacer(Modifier.height(LpSpacing.xs))
            Row {
                Text(
                    text = "学习 ${log.studyMinutes} 分钟",
                    style = MaterialTheme.typography.bodySmall,
                    color = LpColors.TextSecondary,
                )
                Spacer(Modifier.width(LpSpacing.md))
                val sleepHours = LogStatsLogic.parseSleepHours(log.sleepStart, log.sleepEnd)
                Text(
                    text = if (sleepHours != null) "睡眠 ${formatHours(sleepHours)} 小时" else "睡眠未填写",
                    style = MaterialTheme.typography.bodySmall,
                    color = LpColors.TextSecondary,
                )
            }
        }
    }
    }
}

/**
 * 列表项错峰进入：前 8 项每项 40ms 延迟，淡入并上移 12dp。
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
    Box(
        modifier = Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 12.dp.toPx()
        },
    ) { content() }
}

private fun formatHours(hours: Double): String =
    if (hours % 1.0 == 0.0) hours.toInt().toString() else hours.toString()
