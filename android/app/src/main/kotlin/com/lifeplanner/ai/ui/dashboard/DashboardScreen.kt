package com.lifeplanner.ai.ui.dashboard

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.lifeplanner.ai.ui.components.LpAskBar
import com.lifeplanner.ai.ui.components.LpBadge
import com.lifeplanner.ai.ui.components.LpCard
import com.lifeplanner.ai.ui.components.LpDivider
import com.lifeplanner.ai.ui.components.LpEmptyState
import com.lifeplanner.ai.ui.components.LpErrorBanner
import com.lifeplanner.ai.ui.components.LpGradientButton
import com.lifeplanner.ai.ui.components.LpHeroCard
import com.lifeplanner.ai.ui.components.LpLineChart
import com.lifeplanner.ai.ui.components.LpLoading
import com.lifeplanner.ai.ui.components.LpPrimaryButton
import com.lifeplanner.ai.ui.components.LpProgressRing
import com.lifeplanner.ai.ui.components.LpQuoteCard
import com.lifeplanner.ai.ui.components.LpSectionHeader
import com.lifeplanner.ai.ui.components.LpSpacing
import com.lifeplanner.ai.ui.components.LpStatTile
import com.lifeplanner.ai.ui.components.lpStaggerIn
import com.lifeplanner.ai.ui.theme.LpGradients
import com.lifeplanner.ai.ui.theme.LpColors
import com.lifeplanner.core.ai.DailyPlanResult
import com.lifeplanner.core.ai.PlannedTask
import com.lifeplanner.core.daily.DailyQuote
import com.lifeplanner.core.model.LifeGap
import com.lifeplanner.core.model.StatsOverview

/** 今日计划危机短路时展示的热线；dialNumber 去掉短横线。 */
private data class PlanHotline(val name: String, val display: String, val dialNumber: String)

private val PLAN_HOTLINES = listOf(
    PlanHotline("全国希望 24 小时生命救助热线", "400-161-9995", "4001619995"),
    PlanHotline("北京心理危机研究与干预中心", "010-82951332", "01082951332"),
    PlanHotline("青少年心理援助热线", "12355", "12355"),
    PlanHotline("紧急报警", "110", "110"),
    PlanHotline("急救", "120", "120"),
)

/** 页面左右留白（DESIGN.md §4）。底部与状态栏内边距由 LifePlannerRoot 的 Scaffold 负责，这里不重复。 */
private val PagePadding = LpSpacing.pageHorizontal

/**
 * 首页：真实汇总 core 数据（统计、今日任务、趋势、生活缺口），
 * 并把「AI 一句话整理」做成第一入口。
 *
 * 布局顺序按 DESIGN.md 第 6 节。顶部是自定义问候头部（含档案 / 设置圆钮），
 * 因为 LifePlannerRoot 在首页隐藏 TopAppBar。
 *
 * 刻意不做的事：
 * - 不调用 statusBarsPadding()：无 TopAppBar 时 Scaffold 已经补了状态栏高度；
 * - 不再套一层 LpBackground：根节点已经铺了极光背景，重复铺会把两团光晕叠到发灰；
 * - 不额外加底部 padding：悬浮胶囊导航已计入 Scaffold 的 bottomBar。
 * 不做任何假数据兜底：失败显示可读错误，无数据展示空状态。
 */
@Composable
fun DashboardScreen(container: AppContainer, onNavigate: (NavTarget) -> Unit) {
    val vm: DashboardViewModel = appViewModel(container) { DashboardViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
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

    // 首次进入加载；回到前台时 refresh() 命中当天缓存，不再整块重载（跨午夜除外）。
    LaunchedEffect(Unit) { vm.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    // 保存成功：弹窗已关闭，反馈落在首页 —— 轻震动 + Snackbar，消费后清空避免重复提示。
    val captureMessage = state.captureMessage
    LaunchedEffect(captureMessage) {
        if (captureMessage != null) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            snackbarHostState.showSnackbar(captureMessage)
            vm.consumeCaptureMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PagePadding),
        ) {
            StaggerSection(index = 0) {
                GreetingHeader(state = state, onNavigate = onNavigate)
            }

            Spacer(Modifier.height(LpSpacing.lg))

            // AI 一句话整理：首页第一入口
            StaggerSection(index = 1) {
                HeroSection(onOpenCapture = { vm.openCapture() })
            }

            Spacer(Modifier.height(LpSpacing.md))

            val quote = state.quote
            if (quote != null) {
                StaggerSection(index = 2) {
                    QuoteSection(quote = quote)
                }
            }

            val error = state.error
            if (error != null) {
                Spacer(Modifier.height(LpSpacing.md))
                LpErrorBanner(message = error, onRetry = { vm.forceRefresh() })
            }

            Spacer(Modifier.height(LpSpacing.xl))

            val overview = state.overview
            if (overview == null) {
                if (state.loading) {
                    LpLoading()
                } else {
                    LpCard {
                        LpEmptyState(
                            title = "还没有今天的数据",
                            subtitle = "先记录今天的状态，或让 AI 帮你整理一句话",
                        )
                        LpPrimaryButton(text = "重试", onClick = { vm.forceRefresh() })
                    }
                }
            } else {
                StaggerSection(index = 3) {
                    OverviewSection(overview = overview, tasks = state.tasks)
                }

                Spacer(Modifier.height(LpSpacing.section))
                StaggerSection(index = 4) {
                    TasksSection(
                        state = state,
                        onToggle = { id, done -> vm.setTaskDone(id, done) },
                        onNavigate = onNavigate,
                    )
                }

                Spacer(Modifier.height(LpSpacing.section))
                StaggerSection(index = 5) {
                    PlanSection(
                        state = state,
                        dialError = dialError,
                        onGenerate = { vm.generateDailyPlan() },
                        onToggle = { vm.togglePlannedTask(it) },
                        onApply = { vm.applyPlannedTasks() },
                        onNavigate = onNavigate,
                        onDial = ::dial,
                    )
                }

                Spacer(Modifier.height(LpSpacing.section))
                StaggerSection(index = 6) {
                    TrendSection(state = state, onSelect = { vm.setTrendDays(it) })
                }

                Spacer(Modifier.height(LpSpacing.section))
                StaggerSection(index = 7) {
                    GapsSection(gaps = state.gaps)
                }
            }

            Spacer(Modifier.height(LpSpacing.xl))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = PagePadding, vertical = 12.dp),
        )
    }

    if (state.captureOpen) {
        CaptureSheet(
            state = state,
            onInputChange = { vm.onCaptureInputChange(it) },
            onOrganize = { vm.organizeCapture() },
            onToggleTask = { vm.toggleCaptureTask(it) },
            onToggleGoal = { vm.toggleCaptureGoal(it) },
            onSave = { vm.saveCapture() },
            onReset = { vm.resetCapture() },
            onClose = { vm.closeCapture() },
            onNavigate = onNavigate,
        )
    }
}

// ------------------------------------------------------------------
// 1. 问候头部（含档案 / 设置入口）
// ------------------------------------------------------------------

@Composable
private fun GreetingHeader(state: DashboardUiState, onNavigate: (NavTarget) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = LpSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.greeting.ifBlank { "你好" },
                style = MaterialTheme.typography.displaySmall,
                color = LpColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.dateLabel.ifBlank { "今天" },
                    style = MaterialTheme.typography.bodySmall,
                    color = LpColors.TextMuted,
                )
                if (state.refreshing) {
                    Spacer(Modifier.width(LpSpacing.sm))
                    Text(
                        text = "刷新中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = LpColors.TextMuted,
                    )
                }
            }
        }
        RoundIconButton(
            icon = Icons.Outlined.Person,
            description = "个人档案",
            onClick = { onNavigate(NavTarget.PROFILE) },
        )
        Spacer(Modifier.width(LpSpacing.sm))
        RoundIconButton(
            icon = Icons.Outlined.Settings,
            description = "设置",
            onClick = { onNavigate(NavTarget.SETTINGS) },
        )
    }
}

/** 圆形图标按钮：玻璃质感 + 40dp 触控区，首页头部专用。 */
@Composable
private fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(LpColors.SurfaceHigh.copy(alpha = 0.9f))
            .background(LpGradients.Glass)
            .border(1.dp, LpColors.Border, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.foundation.LocalIndication.current,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = LpColors.TextSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

// ------------------------------------------------------------------
// 2. AI 一句话整理入口（Hero）
// ------------------------------------------------------------------

@Composable
private fun HeroSection(onOpenCapture: () -> Unit) {
    LpHeroCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = LpColors.Accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(LpSpacing.sm))
            Text(
                text = "AI 一句话整理",
                style = MaterialTheme.typography.titleMedium,
                color = LpColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            LpBadge(text = "试试", color = LpColors.AccentAlt)
        }
        Spacer(Modifier.height(LpSpacing.md))
        Text(
            text = "说一句话，AI 帮你整理今天",
            style = MaterialTheme.typography.titleLarge,
            color = LpColors.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "学习 · 睡眠 · 运动 · 任务 · 目标，一次说清",
            style = MaterialTheme.typography.bodySmall,
            color = LpColors.TextSecondary,
        )
        Spacer(Modifier.height(LpSpacing.lg))
        LpAskBar(
            placeholder = "今天学了 1 小时英语，晚上跑了 3 公里…",
            hint = "只整理你真正说过的内容，确认后才保存",
            onClick = onOpenCapture,
        )
    }
}

// ------------------------------------------------------------------
// 3. 每日一句（本地内容，离线可用）
// ------------------------------------------------------------------

@Composable
private fun QuoteSection(quote: DailyQuote) {
    LpQuoteCard(
        quote = quote.quote,
        author = quote.author,
        tips = listOf(
            "生活" to quote.lifeTip,
            "学习" to quote.studyTip,
            "工作" to quote.workTip,
        ),
        modifier = Modifier.animateContentSize(),
    )
}

// ------------------------------------------------------------------
// 4. 今日概览（三枚玻璃小卡）
// ------------------------------------------------------------------

@Composable
private fun OverviewSection(overview: StatsOverview, tasks: List<DashboardTaskItem>) {
    val total = tasks.size
    val done = tasks.count { it.task.done }
    val rate = if (total == 0) 0 else (done * 100) / total

    LpSectionHeader(
        title = "今日概览",
        subtitle = "连续记录 · 完成率 · 今日任务",
    )
    Spacer(Modifier.height(LpSpacing.md))
    Row(horizontalArrangement = Arrangement.spacedBy(LpSpacing.md)) {
        LpStatTile(
            label = "连续记录",
            value = overview.streak.toString(),
            unit = "天",
            color = LpColors.Warning,
            modifier = Modifier.weight(1f),
        )
        LpStatTile(
            label = "完成率",
            value = if (total == 0) "—" else rate.toString(),
            unit = if (total == 0) "" else "%",
            color = LpColors.Accent,
            modifier = Modifier.weight(1f),
        )
        LpStatTile(
            label = "今日任务",
            value = total.toString(),
            unit = "项",
            color = LpColors.Info,
            modifier = Modifier.weight(1f),
        )
    }
}

// ------------------------------------------------------------------
// 5. 今日任务（保留原有勾选逻辑）
// ------------------------------------------------------------------

@Composable
private fun TasksSection(
    state: DashboardUiState,
    onToggle: (Long, Boolean) -> Unit,
    onNavigate: (NavTarget) -> Unit,
) {
    val total = state.tasks.size
    val done = state.tasks.count { it.task.done }
    val progress = if (total == 0) 0f else done.toFloat() / total.toFloat()

    LpSectionHeader(
        title = "今日任务",
        subtitle = state.todayLabel.takeIf { it.isNotBlank() },
        trailing = {
            if (total > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LpProgressRing(
                        progress = progress,
                        size = 30.dp,
                        strokeWidth = 3.dp,
                        color = if (done == total) LpColors.Positive else LpColors.Accent,
                    )
                    Spacer(Modifier.width(LpSpacing.sm))
                    LpBadge(text = "$done/$total", color = LpColors.Accent)
                }
            }
        },
    )
    Spacer(Modifier.height(LpSpacing.md))
    LpCard(modifier = Modifier.animateContentSize()) {
        if (state.tasks.isEmpty()) {
            LpEmptyState(
                title = "今天还没有任务",
                subtitle = "可以让 AI 从一句话里整理，或去目标页建立目标。",
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
                    onToggle = { doneNow ->
                        item.task.id?.let { id -> onToggle(id, doneNow) }
                    },
                )
            }
        }
    }
}

// ------------------------------------------------------------------
// 6. AI 今日计划（原有逻辑，视觉降级到中段）
// ------------------------------------------------------------------

@Composable
private fun PlanSection(
    state: DashboardUiState,
    dialError: String?,
    onGenerate: () -> Unit,
    onToggle: (Int) -> Unit,
    onApply: () -> Unit,
    onNavigate: (NavTarget) -> Unit,
    onDial: (String) -> Unit,
) {
    val busy = state.planLoading || state.planApplying
    LpSectionHeader(
        title = "AI 今日计划",
        subtitle = "生成后先确认，再写入今日任务",
    )
    Spacer(Modifier.height(LpSpacing.md))
    LpCard(modifier = Modifier.animateContentSize()) {
        LpGradientButton(
            text = "AI 生成今日计划",
            onClick = onGenerate,
            enabled = !busy,
            loading = state.planLoading,
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
                color = LpColors.AccentAlt,
            )
        }
    }
}

// ------------------------------------------------------------------
// 7. 趋势
// ------------------------------------------------------------------

@Composable
private fun TrendSection(state: DashboardUiState, onSelect: (Int) -> Unit) {
    LpSectionHeader(
        title = "趋势",
        trailing = {
            TrendWindowSelector(selected = state.trendDays, onSelect = onSelect)
        },
    )
    Spacer(Modifier.height(LpSpacing.md))
    LpCard(modifier = Modifier.animateContentSize()) {
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
    Spacer(Modifier.height(LpSpacing.md))
    LpCard(modifier = Modifier.animateContentSize()) {
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
}

// ------------------------------------------------------------------
// 8. 生活缺口
// ------------------------------------------------------------------

@Composable
private fun GapsSection(gaps: List<LifeGap>) {
    LpSectionHeader(title = "生活缺口", subtitle = "系统每天检查一次")
    Spacer(Modifier.height(LpSpacing.md))
    if (gaps.isEmpty()) {
        LpCard {
            LpEmptyState(
                title = "暂未发现明显缺口",
                subtitle = "继续保持记录，系统会每天检查",
            )
        }
        return
    }
    gaps.groupBy { it.category }.forEach { (category, categoryGaps) ->
        LpCard(modifier = Modifier.animateContentSize()) {
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
                LpBadge(text = "${categoryGaps.size} 项", color = LpColors.TextSecondary)
            }
            Spacer(Modifier.height(LpSpacing.sm))
            categoryGaps.forEachIndexed { index, gap ->
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
        Spacer(Modifier.height(LpSpacing.md))
    }
}

// ------------------------------------------------------------------
// 共用小件
// ------------------------------------------------------------------

/**
 * 区块入场：用设计系统的 lpStaggerIn（前 8 项 40ms 错峰、220ms 淡入 + 轻微上移）。
 * 包一层 Column 是为了让「时间差」作用在整段区块上，而不是每个卡片各弹一次。
 */
@Composable
private fun StaggerSection(index: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lpStaggerIn(index),
        content = content,
    )
}

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
        modifier = modifier.height(46.dp),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
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
    LpGradientButton(
        text = "加入今日任务",
        onClick = onApply,
        enabled = !applying,
        loading = applying,
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
            LpBadge(text = "已完成", color = LpColors.Positive)
        }
    }
}

@Composable
private fun TrendWindowSelector(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TrendWindowButton(text = "7 天", selected = selected == 7, onClick = { onSelect(7) })
        TrendWindowButton(text = "30 天", selected = selected == 30, onClick = { onSelect(30) })
    }
}

@Composable
private fun TrendWindowButton(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) LpColors.Accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) LpColors.Accent else LpColors.TextMuted,
        )
    }
}

private fun gapColor(level: String): Color = when (level) {
    "预警" -> LpColors.Danger
    "提醒" -> LpColors.Warning
    "注意" -> LpColors.Info
    else -> LpColors.TextSecondary
}
