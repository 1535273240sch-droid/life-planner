package com.lifeplanner.ai.ui.review

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeplanner.ai.AppContainer
import com.lifeplanner.ai.ui.NavTarget
import com.lifeplanner.ai.ui.appViewModel
import com.lifeplanner.ai.ui.components.LpBadge
import com.lifeplanner.ai.ui.components.LpCard
import com.lifeplanner.ai.ui.components.LpDivider
import com.lifeplanner.ai.ui.components.LpEmptyState
import com.lifeplanner.ai.ui.components.LpErrorBanner
import com.lifeplanner.ai.ui.components.LpHint
import com.lifeplanner.ai.ui.components.LpLoading
import com.lifeplanner.ai.ui.components.LpPrimaryButton
import com.lifeplanner.ai.ui.components.LpSectionTitle
import com.lifeplanner.ai.ui.theme.LpColors
import com.lifeplanner.core.ai.ReviewResult
import com.lifeplanner.core.model.AiSources
import com.lifeplanner.core.util.Dates

/**
 * 复盘页：每日/每周切换、日期选择、生成复盘、危机关怀、进度建议确认、历史记录。
 *
 * 进度建议默认不勾选；只有用户勾选并点击“应用选中进度”才会写入目标进度。
 */
@Composable
fun ReviewScreen(container: AppContainer, onNavigate: (NavTarget) -> Unit) {
    val vm: ReviewViewModel = appViewModel(container) { ReviewViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()

    // 从设置页清空/导入历史后返回本页时重新读取，避免展示已删除的旧记录
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refreshHistory()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LpSectionTitle("复盘类型")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val typeEnabled = !state.generating && !state.applyingProgress
            TypeChip(
                text = "每日复盘",
                selected = state.type == ReviewViewModel.TYPE_DAILY,
                enabled = typeEnabled,
                onClick = { vm.selectType(ReviewViewModel.TYPE_DAILY) },
            )
            TypeChip(
                text = "每周复盘",
                selected = state.type == ReviewViewModel.TYPE_WEEKLY,
                enabled = typeEnabled,
                onClick = { vm.selectType(ReviewViewModel.TYPE_WEEKLY) },
            )
        }

        DateSelector(
            date = Dates.format(state.date),
            isToday = state.date == Dates.today(),
            canGoNext = state.date < Dates.today(),
            enabled = !state.generating && !state.applyingProgress,
            onPrevious = vm::previousDay,
            onNext = vm::nextDay,
        )

        LpHint(
            if (state.type == ReviewViewModel.TYPE_WEEKLY) {
                "每周复盘以所选日期为截止日，回顾其前 7 天的记录与目标推进。"
            } else {
                "每日复盘基于所选日期的任务、日志与目标状态生成。"
            },
        )

        LpPrimaryButton(
            text = if (state.generating) "正在生成…" else "生成复盘",
            onClick = vm::generate,
            enabled = !state.generating && !state.applyingProgress,
        )

        state.message?.let { msg ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LpHint(text = msg, warn = true, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::consumeMessage) {
                    Text("知道了", color = LpColors.Warning)
                }
            }
        }

        state.error?.let { message ->
            LpErrorBanner(message = message, onRetry = vm::generate)
            if (state.notConfigured) {
                TextButton(onClick = { onNavigate(NavTarget.SETTINGS) }) {
                    Text(
                        text = "去设置 AI 服务",
                        color = LpColors.Accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        if (state.generating) {
            LpLoading()
        }

        val result = state.result
        if (result == null && !state.generating && state.error == null) {
            LpEmptyState(
                title = "还没有生成复盘",
                subtitle = "选择日期后点击“生成复盘”，AI 会结合任务、日志与目标给出建议。",
            )
        }

        if (result != null) {
            ResultContent(
                result = result,
                selectedGoalIds = state.selectedGoalIds,
                applyingProgress = state.applyingProgress,
                progressMessage = state.progressMessage,
                progressError = state.progressError,
                onToggleSuggestion = vm::toggleSuggestion,
                onApplyProgress = vm::applySelectedProgress,
                onDialFailed = vm::notify,
            )
        }

        LpDivider(modifier = Modifier.padding(top = 8.dp))
        LpSectionTitle("历史记录")
        HistorySection(
            state = state,
            onToggle = vm::toggleHistory,
            onRetry = vm::refreshHistory,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TypeChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        enabled = enabled,
    )
}

@Composable
private fun DateSelector(
    date: String,
    isToday: Boolean,
    canGoNext: Boolean,
    enabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onPrevious, enabled = enabled) {
            Text("‹ 前一天")
        }
        Text(
            text = if (isToday) "$date（今天）" else date,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            color = LpColors.TextPrimary,
        )
        TextButton(onClick = onNext, enabled = enabled && canGoNext) {
            Text("后一天 ›")
        }
    }
}

@Composable
private fun ResultContent(
    result: ReviewResult,
    selectedGoalIds: Set<Long>,
    applyingProgress: Boolean,
    progressMessage: String?,
    progressError: String?,
    onToggleSuggestion: (Long) -> Unit,
    onApplyProgress: () -> Unit,
    onDialFailed: (String) -> Unit,
) {
    if (result.isCrisis || result.crisis != null) {
        CrisisCard(result = result, onDialFailed = onDialFailed)
    } else {
        LpCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LpBadge(
                    text = if (result.type == ReviewViewModel.TYPE_WEEKLY) "每周复盘" else "每日复盘",
                )
                result.modelName?.takeIf { it.isNotBlank() }?.let { model ->
                    Spacer(Modifier.width(8.dp))
                    LpBadge(text = model, color = LpColors.Info)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = result.title,
                style = MaterialTheme.typography.titleMedium,
                color = LpColors.TextPrimary,
            )
            if (result.summary.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = result.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LpColors.TextSecondary,
                )
            }
        }

        // 空字段不显示对应卡片；每日与每周共用的字段名保持一致
        FieldCard("亮点", result.highlights)
        FieldCard("偏离", result.deviations)
        FieldCard("明日行动", result.nextActions)
        FieldCard("目标推进", result.goalProgress)
        FieldCard("最大偏差", result.biggestDeviation)
        FieldCard("下周策略", result.nextWeekStrategy)
    }

    ProgressSuggestionCard(
        result = result,
        selectedGoalIds = selectedGoalIds,
        applyingProgress = applyingProgress,
        progressMessage = progressMessage,
        progressError = progressError,
        onToggleSuggestion = onToggleSuggestion,
        onApplyProgress = onApplyProgress,
    )
}

@Composable
private fun FieldCard(title: String, body: String?) {
    val text = body?.trim().orEmpty()
    if (text.isEmpty()) return
    LpCard {
        LpSectionTitle(title)
        Spacer(Modifier.height(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = LpColors.TextSecondary,
        )
    }
}

@Composable
private fun ProgressSuggestionCard(
    result: ReviewResult,
    selectedGoalIds: Set<Long>,
    applyingProgress: Boolean,
    progressMessage: String?,
    progressError: String?,
    onToggleSuggestion: (Long) -> Unit,
    onApplyProgress: () -> Unit,
) {
    if (result.progressSuggestions.isEmpty()) return
    LpCard {
        LpSectionTitle("目标进度建议")
        Spacer(Modifier.height(8.dp))
        LpHint("AI 建议不会自动生效。请勾选你确认的条目，再点击下方按钮应用。")
        Spacer(Modifier.height(8.dp))
        result.progressSuggestions.forEachIndexed { index, suggestion ->
            if (index > 0) {
                LpDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !applyingProgress) { onToggleSuggestion(suggestion.goalId) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(
                    checked = suggestion.goalId in selectedGoalIds,
                    onCheckedChange = { onToggleSuggestion(suggestion.goalId) },
                    enabled = !applyingProgress,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                ) {
                    Text(
                        text = suggestion.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = LpColors.TextPrimary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${suggestion.currentProgress}% → ${suggestion.suggestedProgress}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LpColors.Accent,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = suggestion.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = LpColors.TextSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        LpPrimaryButton(
            text = "应用选中进度（${selectedGoalIds.size}）",
            onClick = onApplyProgress,
            enabled = selectedGoalIds.isNotEmpty() && !applyingProgress,
        )
        if (applyingProgress) {
            LpLoading()
        }
        progressMessage?.let {
            Spacer(Modifier.height(8.dp))
            LpHint(text = it)
        }
        progressError?.let {
            Spacer(Modifier.height(8.dp))
            LpErrorBanner(message = it)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "未勾选的建议不会写入；已写入的进度可在“目标”页查看。",
            style = MaterialTheme.typography.labelSmall,
            color = LpColors.TextMuted,
        )
    }
}

private data class Hotline(val label: String, val number: String)

private val HOTLINES = listOf(
    Hotline("全国希望24小时热线", "400-161-9995"),
    Hotline("北京心理危机干预中心", "010-82951332"),
    Hotline("青少年心理援助", "12355"),
    Hotline("紧急报警", "110"),
    Hotline("急救", "120"),
)

@Composable
private fun CrisisCard(result: ReviewResult, onDialFailed: (String) -> Unit) {
    val context = LocalContext.current
    val crisis = result.crisis
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LpColors.Danger.copy(alpha = 0.12f))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LpBadge(text = "安全保护", color = LpColors.Danger)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "危机关怀",
                style = MaterialTheme.typography.titleMedium,
                color = LpColors.Danger,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = crisis?.conclusion?.takeIf { it.isNotBlank() } ?: result.title,
            style = MaterialTheme.typography.bodyLarge,
            color = LpColors.TextPrimary,
        )
        CrisisSection("理由", crisis?.reason ?: result.summary)
        CrisisSection("好处", crisis?.pros ?: result.highlights)
        CrisisSection("坏处与风险", crisis?.consRisks ?: result.deviations)
        CrisisSection("折中优化方案", crisis?.alternative ?: result.nextActions)
        CrisisSection("与目标的关系", crisis?.goalRelation ?: result.goalProgress)

        Spacer(Modifier.height(12.dp))
        Text(
            text = "你并不孤单，请优先联系专业帮助：",
            style = MaterialTheme.typography.bodySmall,
            color = LpColors.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        HOTLINES.forEach { hotline ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = hotline.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = LpColors.TextSecondary,
                )
                Text(
                    text = hotline.number,
                    style = MaterialTheme.typography.bodySmall,
                    color = LpColors.TextPrimary,
                )
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { dial(context, hotline.number, onDialFailed) }) {
                    Text(
                        text = "拨打",
                        color = LpColors.Danger,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "如果生命正受到威胁，请立即拨打 110 或 120。",
            style = MaterialTheme.typography.bodySmall,
            color = LpColors.Danger,
        )
    }
}

@Composable
private fun CrisisSection(title: String, text: String?) {
    val body = text?.trim().orEmpty()
    if (body.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = LpColors.Danger,
    )
    Spacer(Modifier.height(2.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = LpColors.TextSecondary,
    )
}

private fun dial(context: Context, rawNumber: String, onFailed: (String) -> Unit) {
    val number = rawNumber.replace("-", "")
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
    } catch (e: ActivityNotFoundException) {
        onFailed("无法打开拨号盘，请手动拨打 $rawNumber")
    }
}

@Composable
private fun HistorySection(
    state: ReviewUiState,
    onToggle: (Long) -> Unit,
    onRetry: () -> Unit,
) {
    val error = state.historyError
    when {
        state.historyLoading -> LpLoading()
        error != null -> LpErrorBanner(message = error, onRetry = onRetry)
        state.history.isEmpty() -> LpEmptyState(
            title = "暂无复盘历史",
            subtitle = "生成复盘后会自动保存在这里",
        )
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.history.forEach { message ->
                val id = message.id ?: 0L
                val expanded = id in state.expandedHistoryIds
                val isCrisis = message.source == AiSources.CRISIS
                LpCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LpBadge(
                            text = if (isCrisis) "安全保护" else "AI 复盘",
                            color = if (isCrisis) LpColors.Danger else LpColors.Accent,
                        )
                        Text(
                            text = message.createdAt.take(16).replace('T', ' '),
                            style = MaterialTheme.typography.labelSmall,
                            color = LpColors.TextMuted,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = message.question ?: "复盘记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LpColors.TextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = message.answer,
                        style = MaterialTheme.typography.bodySmall,
                        color = LpColors.TextSecondary,
                        maxLines = if (expanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (message.answer.length > 80) {
                        TextButton(onClick = { onToggle(id) }) {
                            Text(
                                text = if (expanded) "收起" else "展开",
                                color = LpColors.Accent,
                            )
                        }
                    }
                }
            }
        }
    }
}
