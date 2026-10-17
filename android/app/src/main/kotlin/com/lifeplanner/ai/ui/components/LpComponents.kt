package com.lifeplanner.ai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeplanner.ai.ui.theme.LpColors

/**
 * 全应用共用的基础组件。页面里不要重复实现卡片、空状态、错误条、加载态。
 * 所有间距/圆角/颜色都从这里取，保证 7 个页面视觉一致。
 */

@Composable
fun LpCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(LpColors.Surface)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(16.dp)
    Column(modifier = base, content = content)
}

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
            style = MaterialTheme.typography.titleMedium,
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
            .padding(vertical = 32.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = LpColors.TextSecondary)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = LpColors.TextMuted)
        }
    }
}

@Composable
fun LpErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LpColors.Danger.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
                Text("重试", color = LpColors.Danger, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun LpLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = LpColors.Accent,
            strokeWidth = 2.dp,
        )
    }
}

@Composable
fun LpBadge(text: String, color: Color = LpColors.Accent, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

@Composable
fun LpDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = LpColors.Border, thickness = 1.dp)
}

@Composable
fun LpPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = LpColors.Accent,
            contentColor = Color.Black,
            disabledContainerColor = LpColors.SurfaceVariant,
            disabledContentColor = LpColors.TextMuted,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

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
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = LpColors.TextPrimary,
            unfocusedTextColor = LpColors.TextPrimary,
            focusedBorderColor = LpColors.Accent,
            unfocusedBorderColor = LpColors.Border,
            focusedLabelColor = LpColors.Accent,
            unfocusedLabelColor = LpColors.TextMuted,
            cursorColor = LpColors.Accent,
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
}

/** 提示条：普通说明（info）或警告（warn） */
@Composable
fun LpHint(text: String, warn: Boolean = false, modifier: Modifier = Modifier) {
    val color = if (warn) LpColors.Warning else LpColors.Info
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(10.dp),
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}
