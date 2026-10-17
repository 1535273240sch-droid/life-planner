package com.lifeplanner.ai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lifeplanner.ai.ui.theme.LpColors
import com.lifeplanner.ai.ui.theme.LpGradients

/**
 * 全应用共用的基础组件（Aurora Ink）。
 *
 * 约定：页面里不要重复实现卡片 / 空状态 / 错误条 / 按钮 / 徽章。
 * 既有组件的「签名」保持完全不变（其余 6 个页面仍在调用），只重绘内部：
 * 圆角变大、面变亮一档、描边变柔、按压有回弹 —— 这些是本次视觉升级的全部手段。
 */

/** 卡片圆角：外层 20dp，内层小卡 16dp（DESIGN.md §4）。 */
private val CardShape = RoundedCornerShape(20.dp)
private val InnerShape = RoundedCornerShape(16.dp)

/**
 * 标准卡片：Surface 面 + 一层极淡的白色顶光。
 *
 * 为什么不用 elevation：深色底上 Material 阴影只会糊成一团灰；
 * 用「面亮度差 + 1px 柔边」表达层级在深色主题下更干净，也不吃性能。
 */
@Composable
fun LpCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val base = modifier
        .fillMaxWidth()
        .lpPressScale(interactionSource = interaction, enabled = onClick != null)
        .clip(CardShape)
        .background(LpColors.Surface)
        .background(LpGradients.Glass)
        .border(1.dp, LpColors.Border, CardShape)
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClick = onClick,
                )
            } else {
                Modifier
            },
        )
        .padding(18.dp)
    Column(modifier = base, content = content)
}

/**
 * 玻璃卡：比 LpCard 更透、更亮的浮起面，用于首页概览、每日一句等「被光照到」的区域。
 * 与 LpCard 的区别只在材质，不改变内容布局，二者可以互相替换。
 */
@Composable
fun LpGlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .lpPressScale(interactionSource = interaction, enabled = onClick != null)
            .clip(CardShape)
            .background(LpColors.Surface.copy(alpha = 0.72f))
            .background(LpGradients.Glass)
            .border(1.dp, LpColors.Border, CardShape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(18.dp),
        content = content,
    )
}

/**
 * 极光背景：墨色底 + 两团极低透度的径向光晕（左上紫、右下青）。
 *
 * 为什么用 Canvas 画而不是贴图：光晕要跟着屏幕尺寸走，图片会在大屏上拉糊；
 * 两团 radialGradient 的绘制成本几乎为零，也不会随滚动重绘。
 * 光晕透明度刻意压到 0.16/0.10 —— 再高就会在纯色卡片边缘露出"脏"色。
 */
@Composable
fun LpBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LpColors.Background),
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            // 左上紫光：从屏幕顶部偏左漫出来
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(LpColors.Accent.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(size.width * 0.10f, size.height * 0.02f),
                    radius = size.width * 0.95f,
                ),
            )
            // 右下青光：压在中段，避免和底部胶囊导航撞色
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(LpColors.AccentAlt.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(size.width * 0.92f, size.height * 0.46f),
                    radius = size.width * 0.85f,
                ),
            )
        }
        content()
    }
}

/** 区块标题：主标题 + 可选副标题 + 右侧操作（首页/各页统一用这个）。 */
@Composable
fun LpSectionHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = LpColors.TextPrimary,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = LpColors.TextMuted,
                )
            }
        }
        trailing?.invoke()
    }
}

/** 兼容既有页面：无副标题的区块标题。 */
@Composable
fun LpSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            color = LpColors.TextPrimary,
        )
        trailing?.invoke()
    }
}

@Composable
fun LpEmptyState(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = LpColors.TextSecondary,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = LpColors.TextMuted,
            )
        }
    }
}

@Composable
fun LpErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LpColors.Danger.copy(alpha = 0.10f))
            .border(1.dp, LpColors.Danger.copy(alpha = 0.28f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = LpColors.Danger,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) {
                Text(
                    text = "重试",
                    style = MaterialTheme.typography.titleMedium,
                    color = LpColors.Danger,
                )
            }
        }
    }
}

/**
 * 加载态：小尺寸内联转圈。
 * 为什么不用整屏骨架：首页已有内容时把内容整块换成 loading 会闪屏（DESIGN.md §5 明令禁止）。
 */
@Composable
fun LpLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(26.dp),
            color = LpColors.Accent,
            strokeWidth = 2.5.dp,
        )
    }
}

/** 徽章：胶囊 + 14% 同色底 + 22% 同色描边。 */
@Composable
fun LpBadge(text: String, color: Color = LpColors.Accent, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        text = text,
        modifier = modifier
            .clip(shape)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.22f), shape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

@Composable
fun LpDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = LpColors.Border, thickness = 1.dp)
}

/** 表单主按钮（既有签名）：渐变底 + 16dp 圆角 + 按压回弹。 */
@Composable
fun LpPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .lpPressScale(interactionSource = interaction, enabled = enabled)
            .clip(InnerShape)
            .background(if (enabled) LpGradients.Brand else SolidColor(LpColors.SurfaceVariant))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) LpColors.Background else LpColors.TextMuted,
        )
    }
}

/**
 * 渐变主按钮：与 LpPrimaryButton 同一套视觉，额外支持 loading 态。
 * 为什么 loading 不换文案：文案突然变成"处理中…"会让按钮宽度跳动；
 * 加一个内联 spinner、保留原文案，按钮尺寸稳定，用户也知道点的是什么。
 */
@Composable
fun LpGradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val active = enabled && !loading
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .lpPressScale(interactionSource = interaction, enabled = active)
            .clip(InnerShape)
            .background(if (enabled) LpGradients.Brand else SolidColor(LpColors.SurfaceVariant))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = active,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = LpColors.Background,
                    strokeWidth = 2.dp,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    !enabled -> LpColors.TextMuted
                    // 处理中时正文压暗，让 spinner 成为视觉焦点，同时保留可读性
                    loading -> LpColors.Background.copy(alpha = 0.65f)
                    else -> LpColors.Background
                },
            )
        }
    }
}

/**
 * 首页 Hero 卡：渐变描边大卡。
 * 为什么描边用渐变 Brush：整卡铺满渐变会压掉正文对比度，
 * 只在 1px 边缘做渐变，既有"发光"感又不影响阅读。
 */
@Composable
fun LpHeroCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LpColors.Surface.copy(alpha = 0.92f))
            .background(LpGradients.Hero)
            .border(width = 1.dp, brush = LpGradients.HeroBorder, shape = shape)
            .padding(20.dp),
        content = content,
    )
}

/**
 * AI 提问条：玻璃胶囊 + 右侧渐变发送钮。
 * 整条可点（不是只有按钮可点）—— 用户的第一反应是点文字区域，
 * 只让 44dp 的小圆可点会让人觉得"没反应"。
 */
@Composable
fun LpAskBar(
    placeholder: String,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(999.dp)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .lpPressScale(interactionSource = interaction)
            .clip(shape)
            .background(LpColors.SurfaceHigh.copy(alpha = 0.90f))
            .background(LpGradients.Glass)
            .border(1.dp, LpColors.Border, shape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = LpColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = LpColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(LpGradients.Brand),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Send,
                contentDescription = "发送",
                tint = LpColors.Background,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 每日一句卡：短语 + 作者 + 生活/学习/工作三条提醒（默认展开，可折叠）。
 *
 * 为什么默认展开：这三条提醒是首页"今天该怎么过"的核心信息，
 * 藏起来就等于没有；折叠只是给已经看过的用户一个压缩版面的选择。
 */
@Composable
fun LpQuoteCard(
    quote: String,
    author: String,
    tips: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(true) }
    val tipColors = listOf(LpColors.Accent, LpColors.AccentAlt, LpColors.Info)

    LpGlassCard(modifier = modifier) {
        Text(
            text = quote,
            style = MaterialTheme.typography.bodyLarge,
            color = LpColors.TextPrimary,
        )
        if (author.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "—— " + author,
                style = MaterialTheme.typography.labelSmall,
                color = LpColors.TextMuted,
            )
        }
        if (tips.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            LpDivider()
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(InnerShape)
                    .clickable { expanded = !expanded }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (expanded) "收起提醒" else "展开提醒",
                    style = MaterialTheme.typography.labelSmall,
                    color = LpColors.Accent,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = tips.size.toString() + " 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = LpColors.TextMuted,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(animationSpec = LpMotion.contentSpec) +
                    expandVertically(animationSpec = LpMotion.sizeSpec),
                exit = fadeOut(animationSpec = LpMotion.fadeOutSpec) +
                    shrinkVertically(animationSpec = LpMotion.sizeSpec),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    tips.forEachIndexed { index, tip ->
                        Row(verticalAlignment = Alignment.Top) {
                            LpBadge(
                                text = tip.first,
                                color = tipColors[index % tipColors.size],
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = tip.second,
                                style = MaterialTheme.typography.bodySmall,
                                color = LpColors.TextSecondary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 概览小卡：数字大、标签弱，用于连续记录 / 完成率 / 今日任务。 */
@Composable
fun LpStatTile(
    label: String,
    value: String,
    unit: String = "",
    color: Color = LpColors.Accent,
    modifier: Modifier = Modifier,
) {
    LpGlassCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = color,
            )
            if (unit.isNotBlank()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = LpColors.TextMuted,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = LpColors.TextMuted,
        )
    }
}

/**
 * 环形进度：600ms 补间，避免数据刷新时"跳"一下。
 *
 * 为什么不做扫角渐变：环形只有 6dp 宽，渐变在这么窄的弧上几乎看不出来，
 * 反而增加一层 Shader 开销；用纯色 + 圆头端点已经足够精致。
 */
@Composable
fun LpProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    strokeWidth: Dp = 6.dp,
    color: Color = LpColors.Accent,
    trackColor: Color = LpColors.SurfaceVariant,
) {
    val target = progress.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = LpMotion.numberSpec,
        label = "lpProgressRing",
    )
    Canvas(modifier = modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        val arcSize = androidx.compose.ui.geometry.Size(
            width = this.size.width - stroke,
            height = this.size.height - stroke,
        )
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        if (animated > 0f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** 输入框（既有签名）：底色抬到 SurfaceHigh，聚焦时只换描边颜色，不做缩放。 */
@Composable
fun LpOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    placeholder: String? = null,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = LpColors.TextMuted) } },
        singleLine = singleLine,
        enabled = enabled,
        minLines = minLines,
        shape = InnerShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = LpColors.TextPrimary,
            unfocusedTextColor = LpColors.TextPrimary,
            disabledTextColor = LpColors.TextMuted,
            focusedContainerColor = LpColors.SurfaceHigh,
            unfocusedContainerColor = LpColors.SurfaceHigh,
            disabledContainerColor = LpColors.Surface,
            cursorColor = LpColors.Accent,
            focusedBorderColor = LpColors.Accent,
            unfocusedBorderColor = LpColors.Border,
            disabledBorderColor = LpColors.Border,
            focusedLabelColor = LpColors.Accent,
            unfocusedLabelColor = LpColors.TextMuted,
            disabledLabelColor = LpColors.TextMuted,
        ),
    )
}

/** 竖向间距糖：`Spacer(LpSpacing.sm)` */
object LpSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp

    /** 页面左右留白（由各页面自行取用，保持 20dp 一致）。 */
    val pageHorizontal = 20.dp

    /** 区块之间。 */
    val section = 24.dp

    /** 卡片内边距。 */
    val cardPadding = 18.dp
}

/** 提示条：普通说明（info）或警告（warn），弱底色 + 同色描边，不做整块高饱和。 */
@Composable
fun LpHint(text: String, warn: Boolean = false, modifier: Modifier = Modifier) {
    val color = if (warn) LpColors.Warning else LpColors.Info
    val shape = RoundedCornerShape(14.dp)
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.18f), shape)
            .padding(12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}