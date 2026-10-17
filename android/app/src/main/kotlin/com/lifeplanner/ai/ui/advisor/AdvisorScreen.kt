package com.lifeplanner.ai.ui.advisor

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.lifeplanner.ai.ui.components.LpLoading
import com.lifeplanner.ai.ui.components.LpOutlinedField
import com.lifeplanner.ai.ui.components.LpPrimaryButton
import com.lifeplanner.ai.ui.components.LpSectionTitle
import com.lifeplanner.ai.ui.components.LpSpacing
import com.lifeplanner.ai.ui.theme.LpColors
import com.lifeplanner.core.ai.AdvisorResult
import com.lifeplanner.core.model.AiMessage
import com.lifeplanner.core.model.AiSources
import kotlinx.coroutines.delay

/** 危机热线：名称 + 展示号码 + 拨号号码（去短横线）。 */
private data class Hotline(val name: String, val display: String, val dialNumber: String)

private val HOTLINES = listOf(
    Hotline("全国希望 24 小时生命救助热线", "400-161-9995", "4001619995"),
    Hotline("北京心理危机研究与干预中心", "010-82951332", "01082951332"),
    Hotline("青少年心理援助热线", "12355", "12355"),
    Hotline("紧急报警", "110", "110"),
    Hotline("急救", "120", "120"),
)

@Composable
fun AdvisorScreen(container: AppContainer, onNavigate: (NavTarget) -> Unit) {
    val vm: AdvisorViewModel = appViewModel(container) { AdvisorViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()

    // 从设置（清空/导入历史）或其他页面返回时，重新读取真实历史。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshHistory() }

    val context = LocalContext.current
    var dialError by remember { mutableStateOf<String?>(null) }
    var expandedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    fun dial(number: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${number.filter { it.isDigit() }}"))
        try {
            context.startActivity(intent)
            dialError = null
        } catch (e: ActivityNotFoundException) {
            dialError = "无法打开拨号盘，请手动拨打上面的号码。"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        LpOutlinedField(
            value = state.question,
            onValueChange = vm::onQuestionChange,
            label = "你的问题",
            singleLine = false,
            minLines = 3,
            enabled = !state.asking,
            placeholder = "例如：我该不该在三个月内辞掉现在的工作，全职做副业？",
        )
        Spacer(Modifier.height(LpSpacing.sm))
        LpPrimaryButton(
            text = "咨询",
            onClick = vm::ask,
            enabled = state.question.isNotBlank() && !state.asking,
        )
        // 请求中 / 错误 / 结果 / 空 用 AnimatedContent 做 220ms 淡入上移切换，
        // 不再让结果区瞬间弹出或整块消失。
        val responsePhase = when {
            state.asking -> AdvisorPhase.ASKING
            state.error != null -> AdvisorPhase.ERROR
            state.result != null -> AdvisorPhase.RESULT
            else -> AdvisorPhase.IDLE
        }
        AnimatedContent(
            targetState = responsePhase,
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(tween(220)) { height -> height / 20 }) togetherWith
                    fadeOut(tween(160)) using SizeTransform(clip = false)
            },
            label = "advisor-response",
        ) { phase ->
            when (phase) {
                AdvisorPhase.ASKING -> LpLoading()

                AdvisorPhase.ERROR -> Column {
                    state.error?.let { message ->
                        Spacer(Modifier.height(LpSpacing.md))
                        LpErrorBanner(
                            message = message,
                            onRetry = if (state.question.isNotBlank()) ({ vm.ask() }) else null,
                        )
                        if (state.notConfigured) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = { onNavigate(NavTarget.SETTINGS) }) {
                                    Text(
                                        text = "去设置",
                                        color = LpColors.Accent,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }

                AdvisorPhase.RESULT -> Column {
                    Spacer(Modifier.height(LpSpacing.lg))
                    state.result?.let { result ->
                        if (result.isCrisis || result.crisis != null) {
                            CrisisCard(result = result, dialError = dialError, onDial = ::dial)
                        } else {
                            AdvisorResultCard(result)
                        }
                    }
                }

                AdvisorPhase.IDLE -> Unit
            }
        }

        Spacer(Modifier.height(LpSpacing.xl))
        LpSectionTitle(
            text = "历史记录",
            trailing = { LpBadge("最近 20 条") },
        )
        Spacer(Modifier.height(LpSpacing.sm))

        // 静默刷新历史：只显示 2dp 细进度条，已有记录保持可见。
        AnimatedVisibility(
            visible = state.historyRefreshing,
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
        val historyError = state.historyError
        if (historyError != null && state.history.isNotEmpty()) {
            LpErrorBanner(message = historyError, onRetry = vm::refreshHistory)
            Spacer(Modifier.height(LpSpacing.sm))
        }
        val historyPhase = when {
            state.history.isNotEmpty() -> AdvisorHistoryPhase.CONTENT
            state.historyLoading -> AdvisorHistoryPhase.LOADING
            state.historyError != null -> AdvisorHistoryPhase.ERROR
            else -> AdvisorHistoryPhase.EMPTY
        }
        AnimatedContent(
            targetState = historyPhase,
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(tween(220)) { height -> height / 20 }) togetherWith
                    fadeOut(tween(160))
            },
            label = "advisor-history",
        ) { phase ->
            when (phase) {
                AdvisorHistoryPhase.CONTENT -> Column {
                    state.history.forEachIndexed { index, message ->
                        val key = message.id ?: -(index + 1L)
                        // 前 8 条按 40ms 错峰淡入。
                        StaggerIn(index = index) {
                            HistoryCard(
                                message = message,
                                expanded = key in expandedIds,
                                onToggle = {
                                    expandedIds = if (key in expandedIds) {
                                        expandedIds - key
                                    } else {
                                        expandedIds + key
                                    }
                                },
                                modifier = Modifier.padding(bottom = LpSpacing.sm),
                            )
                        }
                    }
                }

                AdvisorHistoryPhase.LOADING -> LpLoading()

                AdvisorHistoryPhase.ERROR -> LpErrorBanner(
                    message = state.historyError ?: "历史记录读取失败，请重试",
                    onRetry = vm::refreshHistory,
                )

                AdvisorHistoryPhase.EMPTY -> LpEmptyState(
                    title = "还没有咨询记录",
                    subtitle = "提出一个问题，结果会自动保存在本机。",
                )
            }
        }
    }
}

@Composable
private fun AdvisorResultCard(result: AdvisorResult) {
    LpCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "顾问建议",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = LpColors.TextPrimary,
            )
            LpBadge(result.conclusion, conclusionColor(result.conclusion))
        }
        ResultSection("理由", result.reason)
        ResultSection("好处", result.pros)
        ResultSection("坏处与风险", result.consRisks)
        ResultSection("折中优化方案", result.alternative)
        ResultSection("与目标的关系", result.goalRelation)
        if (!result.modelName.isNullOrBlank()) {
            Spacer(Modifier.height(LpSpacing.md))
            Text(
                text = "由 ${result.modelName} 生成",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = LpColors.TextMuted,
            )
        }
    }
}

@Composable
private fun CrisisCard(
    result: AdvisorResult,
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
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = LpColors.Danger,
                fontWeight = FontWeight.Bold,
            )
            LpBadge("安全保护", LpColors.Danger)
        }
        Spacer(Modifier.height(LpSpacing.sm))
        Text(
            text = crisis?.conclusion ?: result.conclusion,
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
            color = LpColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(LpSpacing.xs))
        Text(
            text = "你并不孤单。以下热线 24 小时可联系，请优先照顾自己的安全。",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = LpColors.TextSecondary,
        )
        ResultSection("理由", crisis?.reason ?: result.reason)
        ResultSection("好处", crisis?.pros ?: result.pros)
        ResultSection("坏处与风险", crisis?.consRisks ?: result.consRisks)
        ResultSection("折中优化方案", crisis?.alternative ?: result.alternative)
        ResultSection("与目标的关系", crisis?.goalRelation ?: result.goalRelation)

        Spacer(Modifier.height(LpSpacing.md))
        LpDivider()
        Spacer(Modifier.height(LpSpacing.sm))
        Text(
            text = "心理援助热线",
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = LpColors.TextMuted,
        )
        for (hotline in HOTLINES) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = hotline.name,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = LpColors.TextPrimary,
                    )
                    Text(
                        text = hotline.display,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
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
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = LpColors.Danger,
            )
        }
    }
}

@Composable
private fun ResultSection(title: String, text: String) {
    if (text.isBlank()) return
    Spacer(Modifier.height(LpSpacing.md))
    Text(
        text = title,
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        color = LpColors.TextMuted,
    )
    Spacer(Modifier.height(3.dp))
    Text(
        text = text,
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        color = LpColors.TextSecondary,
    )
}

@Composable
private fun HistoryCard(
    message: AiMessage,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 展开/收起用 220ms 高度动画，避免长回答瞬间撑开列表。
    LpCard(modifier = modifier.animateContentSize(tween(220)), onClick = onToggle) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message.createdAt.take(16).replace('T', ' '),
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = LpColors.TextMuted,
            )
            if (message.source == AiSources.CRISIS) {
                LpBadge("安全保护", LpColors.Danger)
            }
        }
        Spacer(Modifier.height(LpSpacing.xs))
        Text(
            text = message.question ?: "（未记录问题）",
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
            color = LpColors.TextPrimary,
        )
        Spacer(Modifier.height(LpSpacing.sm))
        if (expanded) {
            LpDivider()
            Spacer(Modifier.height(LpSpacing.sm))
            Text(
                text = message.answer,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = LpColors.TextSecondary,
            )
        } else {
            Text(
                text = "点击展开回答",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = LpColors.TextMuted,
            )
        }
    }
}

private fun conclusionColor(conclusion: String): Color = when (conclusion) {
    "建议做" -> LpColors.Accent
    "不建议做" -> LpColors.Danger
    else -> LpColors.Warning
}

/** 咨询结果区状态：请求中 / 错误 / 结果 / 空，用于 AnimatedContent 平滑切换。 */
private enum class AdvisorPhase { ASKING, ERROR, RESULT, IDLE }

/** 历史区状态：加载 / 错误 / 空 / 内容。 */
private enum class AdvisorHistoryPhase { LOADING, ERROR, EMPTY, CONTENT }

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
