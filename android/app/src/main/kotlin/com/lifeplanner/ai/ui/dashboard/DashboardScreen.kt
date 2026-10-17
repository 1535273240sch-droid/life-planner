package com.lifeplanner.ai.ui.dashboard

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeplanner.ai.AppContainer
import com.lifeplanner.ai.ui.NavTarget
import com.lifeplanner.ai.ui.appViewModel
import com.lifeplanner.ai.ui.components.ChartSeries
import com.lifeplanner.ai.ui.components.LpBadge
import com.lifeplanner.ai.ui.components.LpCard
import com.lifeplanner.ai.ui.components.LpDivider
import com.lifeplanner.ai.ui.components.LpEmptyState
import com.lifeplanner.ai.ui.components.LpErrorBanner
import com.lifeplanner.ai.ui.components.LpLineChart
import com.lifeplanner.ai.ui.components.LpLoading
import com.lifeplanner.ai.ui.components.LpPrimaryButton
import com.lifeplanner.ai.ui.components.LpSectionTitle
import com.lifeplanner.ai.ui.components.LpSpacing
import com.lifeplanner.ai.ui.theme.LpColors
import com.lifeplanner.core.ai.DailyPlanResult
import com.lifeplanner.core.ai.PlannedTask

/** 今日计划危机短路时展示的热线；dialNumber 去掉短横线。 */
private data class PlanHotline(val name: String, val display: String, val dialNumber: String)

private val PLAN_HOTLINES = listOf(
    PlanHotline("全国希望 24 小时生命救助热线", "400-161-9995", "4001619995"),
    PlanHotline("北京心理危机研究与干预中心", "010-82951332", "01082951332"),
    PlanHotline("青少年心理援助热线", "12355", "12355"),
    PlanHotline("紧急报警", "110", "110"),
    PlanHotline("急救", "120", "120"),
)

/**
 * 总览页：真实汇总 core 数据（统计、今日任务、趋势、生活缺口）。
 * 不做任何假数据兜底：失败显示可读错误，无数据展示空状态。
 */
@Composable
fun DashboardScreen(container: AppContainer, onNavigate: (NavTarget) -> Unit) {
    val vm: DashboardViewModel = appViewModel(container) { DashboardViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var dialError by remember { mutableStateOf<String?>(null) }

    fun dial(number: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${number.filter { it.isDigit() }}"))
        try {
            context.startActivity(intent)
            dialError = null
        } catch (e: ActivityNotFoundException) {
            dialError = "无法打开拨号盘，请手动拨打上面的号码。"
        }
    }

    // 首次进入加载；回到前台（含后台过夜后跨午夜）再次刷新，确保“今日”取的是新日期。
    LaunchedEffect(Unit) { vm.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(LpSpacing.md),
    ) {
        val error = state.error
        if (error != null) {
            LpErrorBanner(message = error, onRetry = { vm.refresh() })
        }

        val overview = state.overview
        if (overview == null) {
            if (state.loading) {
                LpLoading()
            } else {
                LpEmptyState(title = "暂无总览数据", subtitle = "重试，或先去记录今天的状态")
                LpPrimaryButton(text = "重试", onClick = { vm.refresh() })
            }
            return@Column
        }

        // 1. 顶部统计卡（2x2）
        Row(horizontalArrangement = Arrangement.spacedBy(LpSpacing.md)) {
            StatCard(
                label = "连续记录",
                value = overview.streak,
                color = LpColors.Accent,
                modifier = Modifier.weight(1f),
            )
            StatCard(
                label = "目标总数",
                value = overview.totalGoals,
                color = LpColors.Info,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(LpSpacing.md)) {
            StatCard(
                label = "已完成",
                value = overview.completedGoals,
                color = LpColors.AccentDim,
                modifier = Modifier.weight(1f),
            )
            StatCard(
                label = "进行中",
                value = overview.inProgressGoals,
                color = LpColors.Warning,
                modifier = Modifier.weight(1f),
            )
        }

        // 快捷入口
        QuickActions(onNavigate = onNavigate)

        // AI 今日计划：先展示，用户点“加入今日任务”后才写库
        DailyPlanSection(
            state = state,
            dialError = dialError,
            onGenerate = { vm.generateDailyPlan() },
            onToggle = { vm.togglePlannedTask(it) },
            onApply = { vm.applyPlannedTasks() },
            onNavigate = onNavigate,
            onDial = ::dial,
        )

        // 2. 今日任务
        LpSectionTitle(
            text = if (state.todayLabel.isNotBlank()) "今日任务 · ${state.todayLabel}" else "今日任务",
            trailing = {
                if (state.tasks.isNotEmpty()) {
                    val doneCount = state.tasks.count { it.task.done }
                    LpBadge(text = "$doneCount/${state.tasks.size}", color = LpColors.Accent)
                }
            },
        )
        LpCard {
            if (state.tasks.isEmpty()) {
                LpEmptyState(
                    title = "今天还没有任务",
                    subtitle = "可以先在目标页建立目标，或在记录页写下今天完成的事。",
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(LpSpacing.md),
                ) {
                    NavActionButton(
                        text = "去目标页",
                        color = LpColors.Accent,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(NavTarget.GOALS) },
                    )
                    NavActionButton(
                        text = "去记录",
                        color = LpColors.Info,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(NavTarget.LOG) },
                    )
                }
            } else {
                state.tasks.forEachIndexed { index, item ->
                    if (index > 0) LpDivider(modifier = Modifier.padding(vertical = 4.dp))
                    TaskRow(
                        item = item,
                        onToggle = { done ->
                            item.task.id?.let { id -> vm.setTaskDone(id, done) }
                        },
                    )
                }
            }
        }

        // 3. 趋势图（近 7 / 30 天）
        LpSectionTitle(
            text = "趋势",
            trailing = {
                TrendWindowSelector(selected = state.trendDays, onSelect = { vm.setTrendDays(it) })
            },
        )
        LpCard {
            Text(
                text = "学习时长",
                style = MaterialTheme.typography.bodyMedium,
                color = LpColors.TextSecondary,
            )
            Spacer(Modifier.height(LpSpacing.sm))
            LpLineChart(
                labels = state.labels,
                series = listOf(
                    ChartSeries(
                        label = "学习(分钟)",
                        values = state.studyMinutes.map { it?.toFloat() },
                        color = LpColors.Info,
                    ),
                ),
                valueSuffix = "分钟",
            )
        }
        LpCard {
            Text(
                text = "睡眠时长",
                style = MaterialTheme.typography.bodyMedium,
                color = LpColors.TextSecondary,
            )
            Spacer(Modifier.height(LpSpacing.sm))
            LpLineChart(
                labels = state.labels,
                series = listOf(
                    ChartSeries(
                        label = "睡眠(小时)",
                        values = state.sleepHours.map { it?.toFloat() },
                        color = LpColors.Accent,
                    ),
                ),
                valueSuffix = "h",
            )
        }

        // 4. 生活缺口
        LpSectionTitle(text = "生活缺口")
        if (state.gaps.isEmpty()) {
            LpCard {
                LpEmptyState(
                    title = "暂未发现明显缺口",
                    subtitle = "继续保持记录，系统会每天检查",
                )
            }
        } else {
            state.gaps.groupBy { it.category }.forEach { (category, gaps) ->
                LpCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = category,
                            style = MaterialTheme.typography.titleMedium,
                            color = LpColors.TextPrimary,
                        )
                        Spacer(Modifier.weight(1f))
                        LpBadge(text = "${gaps.size} 项", color = LpColors.TextSecondary)
                    }
                    Spacer(Modifier.height(LpSpacing.sm))
                    gaps.forEachIndexed { index, gap ->
                        if (index > 0) LpDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            LpBadge(text = gap.level, color = gapColor(gap.level))
                            Spacer(Modifier.width(LpSpacing.sm))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = gap.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = LpColors.TextPrimary,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = gap.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LpColors.TextSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(LpSpacing.sm))
    }
}

@Composable
private fun StatCard(
    label: String,
    value: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    LpCard(modifier = modifier) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = color,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = LpColors.TextMuted,
        )
    }
}

@Composable
private fun QuickActions(onNavigate: (NavTarget) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(LpSpacing.md)) {
        NavActionButton(
            text = "去记录",
            color = LpColors.Accent,
            modifier = Modifier.weight(1f),
            onClick = { onNavigate(NavTarget.LOG) },
        )
        NavActionButton(
            text = "问顾问",
            color = LpColors.Info,
            modifier = Modifier.weight(1f),
            onClick = { onNavigate(NavTarget.ADVISOR) },
        )
    }
}

/** 带霓虹描边的导航按钮，快捷入口与空状态引导共用。 */
@Composable
private fun NavActionButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
    ) {
        Text(text)
    }
}

// ---- AI 今日计划 ----

@Composable
private fun DailyPlanSection(
    state: DashboardUiState,
    dialError: String?,
    onGenerate: () -> Unit,
    onToggle: (Int) -> Unit,
    onApply: () -> Unit,
    onNavigate: (NavTarget) -> Unit,
    onDial: (String) -> Unit,
) {
    val busy = state.planLoading || state.planApplying
    LpSectionTitle(text = "今日计划")
    LpCard {
        NavActionButton(
            text = when {
                state.planLoading -> "正在生成…"
                state.planApplying -> "正在加入…"
                else -> "AI 生成今日计划"
            },
            color = LpColors.Accent,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            onClick = onGenerate,
        )

        if (state.planLoading) {
            LpLoading()
        }

        val error = state.planError
        if (error != null) {
            Spacer(Modifier.height(LpSpacing.sm))
            LpErrorBanner(message = error)
            if (state.planNeedsConfig) {
                Spacer(Modifier.height(LpSpacing.sm))
                NavActionButton(
                    text = "去设置",
                    color = LpColors.Info,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onNavigate(NavTarget.SETTINGS) },
                )
            }
        }

        val result = state.planResult
        if (result != null) {
            Spacer(Modifier.height(LpSpacing.md))
            if (result.isCrisis) {
                PlanCrisisBlock(result = result, dialError = dialError, onDial = onDial)
            } else {
                PlanResultBlock(
                    result = result,
                    selected = state.planSelected,
                    applying = state.planApplying,
                    onToggle = onToggle,
                    onApply = onApply,
                )
            }
        }

        val message = state.planMessage
        if (message != null) {
            Spacer(Modifier.height(LpSpacing.sm))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = LpColors.Accent,
            )
        }
    }
}

@Composable
private fun PlanResultBlock(
    result: DailyPlanResult,
    selected: Set<Int>,
    applying: Boolean,
    onToggle: (Int) -> Unit,
    onApply: () -> Unit,
) {
    if (result.tasks.isEmpty()) {
        LpEmptyState(title = "今天没有生成可执行任务", subtitle = "可以重新生成一次")
    } else {
        Text(
            text = "勾选要加入今天的任务（默认全选）",
            style = MaterialTheme.typography.bodySmall,
            color = LpColors.TextMuted,
        )
        Spacer(Modifier.height(LpSpacing.sm))
        result.tasks.forEachIndexed { index, task ->
            if (index > 0) LpDivider(modifier = Modifier.padding(vertical = 4.dp))
            PlannedTaskRow(
                task = task,
                checked = index in selected,
                onCheckedChange = { onToggle(index) },
            )
        }
    }

    if (result.healthReminder.isNotBlank()) {
        Spacer(Modifier.height(LpSpacing.md))
        Text(
            text = "健康提醒",
            style = MaterialTheme.typography.labelSmall,
            color = LpColors.TextMuted,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = result.healthReminder,
            style = MaterialTheme.typography.bodySmall,
            color = LpColors.TextSecondary,
        )
    }
    if (result.tip.isNotBlank()) {
        Spacer(Modifier.height(LpSpacing.md))
        Text(
            text = "小贴士",
            style = MaterialTheme.typography.labelSmall,
            color = LpColors.TextMuted,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = result.tip,
            style = MaterialTheme.typography.bodySmall,
            color = LpColors.TextSecondary,
        )
    }

    Spacer(Modifier.height(LpSpacing.md))
    LpPrimaryButton(
        text = if (applying) "正在加入…" else "加入今日任务",
        onClick = onApply,
        enabled = !applying,
    )
}

@Composable
private fun PlannedTaskRow(
    task: PlannedTask,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
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
                color = LpColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "预计 ${task.estMinutes} 分钟",
                    style = MaterialTheme.typography.labelSmall,
                    color = LpColors.TextMuted,
                )
                if (task.goalName.isNotBlank()) {
                    Spacer(Modifier.width(LpSpacing.sm))
                    Text(
                        text = "· ${task.goalName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = LpColors.Info,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanCrisisBlock(
    result: DailyPlanResult,
    dialError: String?,
    onDial: (String) -> Unit,
) {
    val crisis = result.crisis
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LpColors.Danger.copy(alpha = 0.12f))
            .border(1.dp, LpColors.Danger.copy(alpha = 0.45f), shape)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "安全提示",
                style = MaterialTheme.typography.titleMedium,
                color = LpColors.Danger,
                fontWeight = FontWeight.Bold,
            )
            LpBadge(text = "安全保护", color = LpColors.Danger)
        }
        Spacer(Modifier.height(LpSpacing.sm))
        Text(
            text = crisis?.conclusion ?: "先照顾好自己的安全",
            style = MaterialTheme.typography.bodyLarge,
            color = LpColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        val crisisReason = crisis?.reason
        if (!crisisReason.isNullOrBlank()) {
            Spacer(Modifier.height(LpSpacing.xs))
            Text(
                text = crisisReason,
                style = MaterialTheme.typography.bodySmall,
                color = LpColors.TextSecondary,
            )
        }
        if (result.healthReminder.isNotBlank()) {
            Spacer(Modifier.height(LpSpacing.md))
            Text(
                text = "健康提醒",
                style = MaterialTheme.typography.labelSmall,
                color = LpColors.TextMuted,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = result.healthReminder,
                style = MaterialTheme.typography.bodySmall,
                color = LpColors.TextSecondary,
            )
        }
        if (result.tip.isNotBlank()) {
            Spacer(Modifier.height(LpSpacing.md))
            Text(
                text = "想对你说",
                style = MaterialTheme.typography.labelSmall,
                color = LpColors.TextMuted,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = result.tip,
                style = MaterialTheme.typography.bodySmall,
                color = LpColors.TextSecondary,
            )
        }

        Spacer(Modifier.height(LpSpacing.md))
        LpDivider()
        Spacer(Modifier.height(LpSpacing.sm))
        Text(
            text = "你并不孤单。以下热线 24 小时可联系，请优先照顾自己的安全。",
            style = MaterialTheme.typography.bodySmall,
            color = LpColors.TextSecondary,
        )
        for (hotline in PLAN_HOTLINES) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = hotline.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = LpColors.TextPrimary,
                    )
                    Text(
                        text = hotline.display,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LpColors.Danger,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                TextButton(
                    onClick = { onDial(hotline.dialNumber) },
                    colors = ButtonDefaults.textButtonColors(contentColor = LpColors.Danger),
                ) {
                    Text("拨打")
                }
            }
        }
        if (dialError != null) {
            Spacer(Modifier.height(LpSpacing.xs))
            Text(
                text = dialError,
                style = MaterialTheme.typography.bodySmall,
                color = LpColors.Danger,
            )
        }
    }
}

@Composable
private fun TaskRow(
    item: DashboardTaskItem,
    onToggle: (Boolean) -> Unit,
) {
    val done = item.task.done
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = done,
            onCheckedChange = onToggle,
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
                text = item.task.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (done) LpColors.TextMuted else LpColors.TextPrimary,
                textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "预计 ${item.task.estMinutes} 分钟",
                    style = MaterialTheme.typography.labelSmall,
                    color = LpColors.TextMuted,
                )
                val goalTitle = item.goalTitle
                if (!goalTitle.isNullOrBlank()) {
                    Spacer(Modifier.width(LpSpacing.sm))
                    Text(
                        text = "· $goalTitle",
                        style = MaterialTheme.typography.labelSmall,
                        color = LpColors.Info,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (done) {
            Spacer(Modifier.width(LpSpacing.sm))
            LpBadge(text = "已完成", color = LpColors.Accent)
        }
    }
}

@Composable
private fun TrendWindowSelector(selected: Int, onSelect: (Int) -> Unit) {
    Row {
        TrendWindowButton(text = "7 天", selected = selected == 7, onClick = { onSelect(7) })
        TrendWindowButton(text = "30 天", selected = selected == 30, onClick = { onSelect(30) })
    }
}

@Composable
private fun TrendWindowButton(text: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = text,
            color = if (selected) LpColors.Accent else LpColors.TextMuted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private fun gapColor(level: String): Color = when (level) {
    "预警" -> LpColors.Danger
    "提醒" -> LpColors.Warning
    "注意" -> LpColors.Info
    else -> LpColors.TextSecondary
}
