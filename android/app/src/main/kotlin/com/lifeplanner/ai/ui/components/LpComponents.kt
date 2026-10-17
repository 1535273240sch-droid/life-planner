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
import androidx.compose.material.icons.rounded.Add
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lifeplanner.ai.ui.theme.LpColors

/**
 * 全应用共用的基础组件（Warm Journal · 2.2）。
 *
 * 2.2 的视觉原则：
 * - 面是实色，层级靠"亮一档 + 一条细边"，不用发光、玻璃叠加与高饱和渐变；
 * - 一张卡片最多承载一件事，能不用卡片就不用（例如统计改成一行数字）；
 * - 动效只做"淡入 + 极小位移"，不做错落弹跳与过度按压。
 *
 * 兼容约束：既有组件的签名保持不变（7 个页面仍在调用），只重绘内部。
 */

/** 卡片圆角：外层 16dp，内层 12dp。 */
private val CardShape = RoundedCornerShape(16.dp)
private val InnerShape = RoundedCornerShape(12.dp)

/** 标准卡片：实色面 + 1px 细边。 */
@Composable
fun LpCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val base = modifier
        .fillMaxWidth()
        .clip(CardShape)
        .background(LpColors.Surface)
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
        .padding(16.dp)
    Column(modifier = base, content = content)
}

/**
 * 旧版"玻璃卡"的兼容别名：2.2 去掉了半透明叠层，现在与 LpCard 一致。
 * 保留函数名是为了不再改 7 个页面的调用点。
 */
@Composable
fun LpGlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    LpCard(modifier = modifier, onClick = onClick, content = content)
}

/**
 * 应用背景：暖色近黑 + 极轻的纵向明暗过渡。
 * 不绘制光晕——发光圆斑是"AI 生成器"视觉的典型符号，与手账的安静感冲突。
 */
@Composable
fun LpBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF141210), LpColors.Background),
                ),
            ),
    ) {
        content()
    }
}

/** 区块标题：主标题 + 可选副标题 + 右侧操作。 */
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
            .padding(vertical = 26.dp, horizontal = 16.dp),
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
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LpColors.Danger.copy(alpha = 0.10f))
            .border(1.dp, LpColors.Danger.copy(alpha = 0.26f), shape)
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

/** 加载态：小尺寸内联转圈，不整屏替换内容。 */
@Composable
fun LpLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            color = LpColors.Accent,
            strokeWidth = 2.dp,
        )
    }
}

/** 徽章：低饱和小胶囊。 */
@Composable
fun LpBadge(text: String, color: Color = LpColors.Accent, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        text = text,
        modifier = modifier
            .clip(shape)
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

@Composable
fun LpDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = LpColors.Border, thickness = 1.dp)
}

/** 表单主按钮：实色陶土，不用渐变。 */
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
            .height(46.dp)
            .clip(InnerShape)
            .background(if (enabled) LpColors.Accent else LpColors.SurfaceVariant)
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
/** 主按钮（带 loading）：与 LpPrimaryButton 同一套视觉。 */
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
            .height(46.dp)
            .clip(InnerShape)
            .background(if (enabled) LpColors.Accent else LpColors.SurfaceVariant)
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    color = LpColors.Background,
                    strokeWidth = 2.dp,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    !enabled -> LpColors.TextMuted
                    loading -> LpColors.Background.copy(alpha = 0.7f)
                    else -> LpColors.Background
                },
            )
        }
    }
}

/**
 * 旧版 Hero 卡的兼容别名：现在是普通浮起面。
 * 首页不再使用它，保留是为了不影响仍调用它的页面。
 */
@Composable
fun LpHeroCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LpColors.SurfaceHigh)
            .border(1.dp, LpColors.Border, CardShape)
            .padding(18.dp),
        content = content,
    )
}

/**
 * 记录入口条：整条可点，右侧只有一个小的"＋"。
 * 2.2 去掉玻璃胶囊与渐变发送钮——那套视觉更像聊天机器人，而不是记事本。
 */
@Composable
fun LpAskBar(
    placeholder: String,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LpColors.Surface)
            .border(1.dp, LpColors.Border, shape)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
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
            Spacer(Modifier.height(3.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = LpColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(LpColors.Accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = "记录",
                tint = LpColors.Accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 每日一句：引文 + 作者 + 生活/学习/工作提醒。
 * 一眼能读完，所以提醒默认展开；"收起"只用于已经看过的用户压缩版面。
 */
@Composable
fun LpQuoteCard(
    quote: String,
    author: String,
    tips: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(true) }
    val tipColors = listOf(LpColors.Positive, LpColors.Info, LpColors.Warning)

    LpCard(modifier = modifier) {
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
            Spacer(Modifier.height(12.dp))
            LpDivider()
            Spacer(Modifier.height(4.dp))
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
                    color = LpColors.TextMuted,
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
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
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

/**
 * 概览数字：不再包卡片，只留"数字 + 标签"，一行三列时靠分隔线分组。
 * 私人工具里的统计应该像手账边注，而不是三块发光仪表盘。
 */
@Composable
fun LpStatTile(
    label: String,
    value: String,
    unit: String = "",
    color: Color = LpColors.TextPrimary,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = color,
            )
            if (unit.isNotBlank()) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = LpColors.TextMuted,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = LpColors.TextMuted,
        )
    }
}

/** 环形进度：600ms 补间。 */
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

/** 输入框：实色底 + 细边，聚焦只换描边颜色。 */
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
            focusedContainerColor = LpColors.SurfaceVariant,
            unfocusedContainerColor = LpColors.SurfaceVariant,
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

/** 竖向间距糖：Spacer(LpSpacing.sm) */
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
    val cardPadding = 16.dp
}

/** 提示条：普通说明（info）或警告（warn）。 */
@Composable
fun LpHint(text: String, warn: Boolean = false, modifier: Modifier = Modifier) {
    val color = if (warn) LpColors.Warning else LpColors.Info
    val shape = RoundedCornerShape(10.dp)
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color.copy(alpha = 0.07f))
            .padding(12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}
