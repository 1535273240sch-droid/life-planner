package com.lifeplanner.ai.ui.interview

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeplanner.ai.AppContainer
import com.lifeplanner.ai.ui.NavTarget
import com.lifeplanner.ai.ui.appViewModel
import com.lifeplanner.ai.ui.components.LpCard
import com.lifeplanner.ai.ui.components.LpDivider
import com.lifeplanner.ai.ui.components.LpErrorBanner
import com.lifeplanner.ai.ui.components.LpSpacing
import com.lifeplanner.ai.ui.theme.LpColors
import com.lifeplanner.core.ai.CrisisPayload
import com.lifeplanner.core.ai.InterviewDraft
import com.lifeplanner.core.ai.InterviewFields
import com.lifeplanner.core.ai.InterviewGoalDraft
import kotlinx.coroutines.delay

/** 页面左右留白（DESIGN.md §4）。顶部状态栏 / 底部导航内边距由 LifePlannerRoot 的 Scaffold 负责。 */
private val PagePadding = 20.dp

/**
 * 「认识你」：聊天式建档访谈。
 *
 * 视觉方向（用户反馈「太 AI 味」后的新版）：暖调、低装饰、像真人访谈。
 * 因此这里刻意不用渐变卡 / 光晕 / sparkle / 「AI」前缀，
 * 只用 LpCard + LpColors 的实色与一层浅描边；气泡的差异只靠位置和底色深浅表达。
 *
 * 页面自带 header（返回 / 标题 / 重新开始）—— root 会在本页隐藏 TopAppBar。
 * 数据与流程全部在 [InterviewViewModel]：这里只负责渲染、自动滚动与输入法交互。
 */
@Composable
fun InterviewScreen(
    container: AppContainer,
    onNavigate: (NavTarget) -> Unit,
    onSaved: () -> Unit = {},
) {
    val vm: InterviewViewModel = appViewModel(container) { InterviewViewModel(it, onSaved) }
    val state by vm.state.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showRestartDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.start() }

    // 有新气泡 / 结束 / 保存成功时滚到底部：访谈是线性对话，停在中间会让人以为断了。
    LaunchedEffect(state.messages.size, state.busy, state.finished, state.saved, state.error) {
        delay(60)
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    val savedMessage = state.savedMessage
    LaunchedEffect(savedMessage) {
        if (savedMessage != null) {
            snackbarHostState.showSnackbar(savedMessage)
            vm.consumeMessage()
        }
    }

    val showInput = !state.finished && state.crisis == null && !state.saved && !state.notConfigured

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            InterviewHeader(
                canRestart = state.messages.isNotEmpty() && !state.saving && !state.busy,
                onBack = { onNavigate(NavTarget.DASHBOARD) },
                onRestart = { showRestartDialog = true },
            )
            ProgressLine(fields = state.fields)
            LpDivider()

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = PagePadding, end = PagePadding, top = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 不设 key：两条内容完全相同的气泡 hash 会撞车，位置即身份已经够用
                items(state.messages) { bubble ->
                    MessageBubble(bubble = bubble)
                }

                if (state.busy) {
                    item(key = "typing") { TypingBubble() }
                }

                if (state.notConfigured) {
                    item(key = "not_configured") {
                        NotConfiguredCard(
                            onGoSettings = { onNavigate(NavTarget.SETTINGS) },
                            onBack = { onNavigate(NavTarget.DASHBOARD) },
                        )
                    }
                }

                val error = state.error
                // 未配置时已有专门的说明卡，再叠一条错误横幅只是重复同一句话
                if (error != null && !state.notConfigured) {
                    item(key = "error") {
                        LpErrorBanner(
                            message = error,
                            onRetry = if (state.notConfigured) null else {
                                { vm.retry() }
                            },
                        )
                    }
                }

                val crisis = state.crisis
                if (crisis != null) {
                    item(key = "crisis") {
                        CrisisCard(
                            crisis = crisis,
                            onBack = { onNavigate(NavTarget.DASHBOARD) },
                        )
                    }
                }

                if (state.finished && crisis == null) {
                    item(key = "summary") {
                        if (state.saved) {
                            SavedCard(
                                onBackHome = { onNavigate(NavTarget.DASHBOARD) },
                                onRestart = { showRestartDialog = true },
                            )
                        } else {
                            val draft = state.draft
                            if (draft != null) {
                                SummaryCard(
                                    draft = draft,
                                    selectedGoals = state.selectedGoals,
                                    saving = state.saving,
                                    onToggleGoal = { vm.toggleGoal(it) },
                                    onSave = { vm.save() },
                                    onRestart = { showRestartDialog = true },
                                )
                            }
                        }
                    }
                }
            }

            if (showInput) {
                InputBar(
                    state = state,
                    onInputChange = { vm.onInputChange(it) },
                    onSend = { vm.submit() },
                    onSkip = { vm.skip() },
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = PagePadding, vertical = 12.dp),
        )
    }

    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            containerColor = LpColors.SurfaceHigh,
            title = {
                Text(
                    text = "重新开始访谈？",
                    style = MaterialTheme.typography.titleMedium,
                    color = LpColors.TextPrimary,
                )
            },
            text = {
                Text(
                    text = "这次聊到的内容会清空，已经保存的档案不受影响。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LpColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestartDialog = false
                    vm.restart()
                }) {
                    Text("重新开始", color = LpColors.Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }) {
                    Text("继续聊", color = LpColors.TextMuted)
                }
            },
        )
    }
}

// ------------------------------------------------------------------
// 顶部：返回 / 标题 / 重新开始 + 细进度条
// ------------------------------------------------------------------

@Composable
private fun InterviewHeader(
    canRestart: Boolean,
    onBack: () -> Unit,
    onRestart: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = LpColors.TextSecondary,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "认识你",
                style = MaterialTheme.typography.titleLarge,
                color = LpColors.TextPrimary,
            )
            Text(
                text = "先聊几个问题，把档案一点点补全",
                style = MaterialTheme.typography.labelSmall,
                color = LpColors.TextMuted,
            )
        }
        TextButton(onClick = onRestart, enabled = canRestart) {
            Text(
                text = "重新开始",
                style = MaterialTheme.typography.labelSmall,
                color = if (canRestart) LpColors.TextMuted else LpColors.TextMuted.copy(alpha = 0.5f),
            )
        }
    }
}

/** 细进度条：只说明「聊到哪了」，不做庆祝式动效。 */
@Composable
private fun ProgressLine(fields: InterviewFields) {
    val covered = fields.coreCovered
    val total = InterviewFields.CORE_TOPICS
    val target = (covered.toFloat() / total).coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(400),
        label = "interviewProgress",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PagePadding)
            .padding(bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "已了解 $covered / $total 个话题",
                style = MaterialTheme.typography.labelSmall,
                color = LpColors.TextMuted,
                modifier = Modifier.weight(1f),
            )
            if (covered >= total) {
                Text(
                    text = "差不多可以收尾了",
                    style = MaterialTheme.typography.labelSmall,
                    color = LpColors.Positive,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(CircleShape)
                .background(LpColors.SurfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .fillMaxHeight()
                    .background(LpColors.Accent),
            )
        }
    }
}

// ------------------------------------------------------------------
// 消息气泡
// ------------------------------------------------------------------

private fun bubbleShape(fromUser: Boolean): RoundedCornerShape =
    if (fromUser) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
    }

@Composable
private fun MessageBubble(bubble: InterviewBubble) {
    val fromUser = bubble.fromUser
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 316.dp)
                .clip(bubbleShape(fromUser))
                .background(if (fromUser) LpColors.SurfaceVariant else LpColors.Surface)
                .border(1.dp, LpColors.Border, bubbleShape(fromUser))
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            if (bubble.text.isNotBlank()) {
                Text(
                    text = bubble.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (fromUser) LpColors.TextPrimary else LpColors.TextSecondary,
                )
            }
            if (!bubble.question.isNullOrBlank()) {
                if (bubble.text.isNotBlank()) Spacer(Modifier.height(8.dp))
                Text(
                    text = bubble.question,
                    style = MaterialTheme.typography.bodyLarge,
                    color = LpColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** 「对方正在输入…」占位：与 AI 气泡同侧、更淡，避免整块替换成 loading。 */
@Composable
private fun TypingBubble() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = "对方正在输入…",
            style = MaterialTheme.typography.bodySmall,
            color = LpColors.TextMuted,
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(LpColors.Surface)
                .border(1.dp, LpColors.Border, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

// ------------------------------------------------------------------
// 底部输入区
// ------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputBar(
    state: InterviewUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onSkip: () -> Unit,
) {
    val input = state.input
    val overLimit = input.length > InterviewViewModel.MAX_ANSWER
    val canSend = input.isNotBlank() && !overLimit && !state.busy && !state.saving

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PagePadding)
            .padding(top = 6.dp, bottom = 10.dp),
    ) {
        LpDivider()
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                enabled = !state.busy,
                placeholder = {
                    Text(
                        text = if (state.currentQuestion != null) "用你自己的话说就好" else "接着说…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LpColors.TextMuted,
                    )
                },
                minLines = 1,
                maxLines = 4,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = LpColors.TextPrimary,
                    unfocusedTextColor = LpColors.TextPrimary,
                    disabledTextColor = LpColors.TextMuted,
                    focusedContainerColor = LpColors.SurfaceHigh,
                    unfocusedContainerColor = LpColors.SurfaceHigh,
                    disabledContainerColor = LpColors.Surface,
                    cursorColor = LpColors.Accent,
                    focusedBorderColor = LpColors.Accent.copy(alpha = 0.6f),
                    unfocusedBorderColor = LpColors.Border,
                    disabledBorderColor = LpColors.Border,
                ),
            )
            Spacer(Modifier.width(LpSpacing.sm))
            SendButton(enabled = canSend, onClick = onSend)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (overLimit) "超出 ${InterviewViewModel.MAX_ANSWER} 字，请精简" else "",
                style = MaterialTheme.typography.labelSmall,
                color = LpColors.Danger,
                modifier = Modifier.weight(1f),
            )
            if (state.currentQuestion != null) {
                TextButton(onClick = onSkip, enabled = !state.busy) {
                    Text(
                        text = "先跳过这个问题",
                        style = MaterialTheme.typography.labelSmall,
                        color = LpColors.TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(if (enabled) LpColors.Accent else LpColors.SurfaceVariant)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.ArrowUpward,
            contentDescription = "发送",
            tint = if (enabled) LpColors.Background else LpColors.TextMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

// ------------------------------------------------------------------
// 结束 / 保存 / 危机 / 未配置
// ------------------------------------------------------------------

@Composable
private fun SummaryCard(
    draft: InterviewDraft,
    selectedGoals: Set<Int>,
    saving: Boolean,
    onToggleGoal: (Int) -> Unit,
    onSave: () -> Unit,
    onRestart: () -> Unit,
) {
    LpCard {
        Text(
            text = "先聊到这里",
            style = MaterialTheme.typography.titleLarge,
            color = LpColors.TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = draft.summary.ifBlank { "谢谢你愿意聊聊这些，我把听到的内容整理在下面了。" },
            style = MaterialTheme.typography.bodyMedium,
            color = LpColors.TextSecondary,
        )

        Spacer(Modifier.height(LpSpacing.lg))
        LpDivider()
        Spacer(Modifier.height(LpSpacing.md))

        Text(
            text = "已了解的信息",
            style = MaterialTheme.typography.labelSmall,
            color = LpColors.TextMuted,
        )
        Spacer(Modifier.height(LpSpacing.sm))
        val rows = fieldRows(draft.fields)
        if (rows.isEmpty()) {
            Text(
                text = "这次没有聊到具体信息，可以重新开始，或者直接去档案页自己填。",
                style = MaterialTheme.typography.bodySmall,
                color = LpColors.TextMuted,
            )
        } else {
            rows.forEach { (label, value) ->
                InfoRow(label = label, value = value)
                Spacer(Modifier.height(6.dp))
            }
        }

        val vision = draft.vision.ifBlank { draft.fields.vision.orEmpty() }
        val constraints = draft.constraints.ifBlank { draft.fields.constraints.orEmpty() }
        if (vision.isNotBlank()) {
            Spacer(Modifier.height(LpSpacing.sm))
            Blockquote(label = "你想要的未来", text = vision)
        }
        if (constraints.isNotBlank()) {
            Spacer(Modifier.height(LpSpacing.sm))
            Blockquote(label = "现实约束", text = constraints)
        }

        Spacer(Modifier.height(LpSpacing.lg))
        Text(
            text = "可以一起立的目标",
            style = MaterialTheme.typography.labelSmall,
            color = LpColors.TextMuted,
        )
        Spacer(Modifier.height(LpSpacing.sm))
        if (draft.goals.isEmpty()) {
            Text(
                text = "这次没有生成建议目标，可以稍后在目标页自己加。",
                style = MaterialTheme.typography.bodySmall,
                color = LpColors.TextMuted,
            )
        } else {
            draft.goals.forEachIndexed { index, goal ->
                if (index > 0) LpDivider(modifier = Modifier.padding(vertical = 4.dp))
                GoalRow(
                    goal = goal,
                    checked = index in selectedGoals,
                    enabled = !saving,
                    onCheckedChange = { onToggleGoal(index) },
                )
            }
        }

        Spacer(Modifier.height(LpSpacing.lg))
        SolidButton(
            text = if (saving) "正在保存…" else "保存到我的档案",
            enabled = !saving,
            onClick = onSave,
        )
        Spacer(Modifier.height(2.dp))
        TextButton(onClick = onRestart, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "重新聊一次",
                style = MaterialTheme.typography.bodyMedium,
                color = LpColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun SavedCard(onBackHome: () -> Unit, onRestart: () -> Unit) {
    LpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = LpColors.Positive,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(LpSpacing.sm))
            Text(
                text = "已保存到你的档案",
                style = MaterialTheme.typography.titleMedium,
                color = LpColors.TextPrimary,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "目标和档案已更新，首页与档案页会显示最新内容。",
            style = MaterialTheme.typography.bodyMedium,
            color = LpColors.TextSecondary,
        )
        Spacer(Modifier.height(LpSpacing.lg))
        SolidButton(text = "回首页", enabled = true, onClick = onBackHome)
        Spacer(Modifier.height(2.dp))
        TextButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "再做一次访谈",
                style = MaterialTheme.typography.bodyMedium,
                color = LpColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun CrisisCard(crisis: CrisisPayload, onBack: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LpColors.Danger.copy(alpha = 0.10f))
            .border(1.dp, LpColors.Danger.copy(alpha = 0.32f), shape)
            .padding(18.dp),
    ) {
        Text(
            text = "先说一句",
            style = MaterialTheme.typography.titleMedium,
            color = LpColors.Danger,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(LpSpacing.sm))
        Text(
            text = crisis.conclusion,
            style = MaterialTheme.typography.bodyLarge,
            color = LpColors.TextPrimary,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(LpSpacing.xs))
        Text(
            text = crisis.reason,
            style = MaterialTheme.typography.bodySmall,
            color = LpColors.TextSecondary,
        )
        if (crisis.alternative.isNotBlank()) {
            Spacer(Modifier.height(LpSpacing.md))
            Text(
                text = crisis.alternative,
                style = MaterialTheme.typography.bodySmall,
                color = LpColors.TextSecondary,
            )
        }
        Spacer(Modifier.height(LpSpacing.lg))
        Text(
            text = "档案可以以后再建，你的安全最重要。这次不会保存任何内容。",
            style = MaterialTheme.typography.bodySmall,
            color = LpColors.TextMuted,
        )
        Spacer(Modifier.height(LpSpacing.md))
        SolidButton(text = "回首页", enabled = true, onClick = onBack)
    }
}

@Composable
private fun NotConfiguredCard(onGoSettings: () -> Unit, onBack: () -> Unit) {
    LpCard {
        Text(
            text = "还差一步：接入模型",
            style = MaterialTheme.typography.titleMedium,
            color = LpColors.TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "访谈需要调用你自己配置的模型服务。去设置里填好 API Key 和模型名，回来就能开始聊。",
            style = MaterialTheme.typography.bodyMedium,
            color = LpColors.TextSecondary,
        )
        Spacer(Modifier.height(LpSpacing.lg))
        SolidButton(text = "去设置", enabled = true, onClick = onGoSettings)
        Spacer(Modifier.height(2.dp))
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "先回首页",
                style = MaterialTheme.typography.bodyMedium,
                color = LpColors.TextSecondary,
            )
        }
    }
}

// ------------------------------------------------------------------
// 小件
// ------------------------------------------------------------------

/** 实色主按钮：不用渐变，避免「AI 魔法感」，也方便暖色主题直接换 Accent 值。 */
@Composable
private fun SolidButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) LpColors.Accent else LpColors.SurfaceVariant)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) LpColors.Background else LpColors.TextMuted,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = LpColors.TextMuted,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = LpColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Blockquote(label: String, text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LpColors.SurfaceVariant.copy(alpha = 0.6f))
            .padding(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = LpColors.TextMuted,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = LpColors.TextPrimary,
        )
    }
}

@Composable
private fun GoalRow(
    goal: InterviewGoalDraft,
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
                text = buildString {
                    append(levelLabel(goal.level))
                    append(" · ")
                    append(goal.category)
                    val metric = goal.metric
                    if (!metric.isNullOrBlank()) {
                        append(" · ")
                        append(metric)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = LpColors.TextMuted,
            )
        }
    }
}

/** 可读键值：只显示真的聊到的字段（null 一律不显示，绝不补空值）。 */
private fun fieldRows(fields: InterviewFields): List<Pair<String, String>> {
    val rows = ArrayList<Pair<String, String>>(10)
    fields.age?.let { rows.add("年龄" to "$it 岁") }
    fields.gender?.takeIf { it.isNotBlank() }?.let { rows.add("性别" to it) }
    fields.occupation?.takeIf { it.isNotBlank() }?.let { rows.add("职业" to it) }
    fields.industry?.takeIf { it.isNotBlank() }?.let { rows.add("行业" to it) }
    fields.yearsOfWork?.let { rows.add("工作年限" to "$it 年") }
    fields.education?.takeIf { it.isNotBlank() }?.let { rows.add("学历" to it) }
    fields.major?.takeIf { it.isNotBlank() }?.let { rows.add("专业" to it) }
    fields.income?.takeIf { it.isNotBlank() }?.let { rows.add("收入" to it) }
    fields.currentState?.takeIf { it.isNotBlank() }?.let { rows.add("现在" to it) }
    fields.desiredState?.takeIf { it.isNotBlank() }?.let { rows.add("想要" to it) }
    fields.approach?.takeIf { it.isNotBlank() }?.let { rows.add("打算怎么走" to it) }
    fields.futureSelf?.takeIf { it.isNotBlank() }?.let { rows.add("想成为" to it) }
    return rows
}

private fun levelLabel(level: String): String = when (level) {
    "vision" -> "愿景"
    "long" -> "长期"
    "stage" -> "阶段"
    "weekly" -> "周目标"
    else -> level
}
