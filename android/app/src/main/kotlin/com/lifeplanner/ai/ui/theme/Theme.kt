package com.lifeplanner.ai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 设计令牌：沿用 v1 手机端的纯黑 + 霓虹配色（OLED 省电、暗环境不刺眼）。
 * 所有页面统一使用这里的颜色，不要在页面里写死色值。
 */
object LpColors {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF12161C)
    val SurfaceVariant = Color(0xFF1A1F27)
    val Border = Color(0x14FFFFFF)          // 白色 8%
    val Accent = Color(0xFF00F5A0)          // 翡翠霓虹绿：健康 / 推进 / 完成
    val AccentDim = Color(0xFF00D284)
    val Danger = Color(0xFFFF4D5E)          // 霓虹绯红：偏差 / 风险
    val Warning = Color(0xFFF59E0B)         // 琥珀：注意
    val Info = Color(0xFF38BDF8)            // 天蓝：说明性内容
    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFFCBD5E1)
    val TextMuted = Color(0xFF7C8798)
}

private val LpColorScheme = darkColorScheme(
    primary = LpColors.Accent,
    onPrimary = Color.Black,
    secondary = LpColors.Info,
    background = LpColors.Background,
    onBackground = LpColors.TextPrimary,
    surface = LpColors.Surface,
    onSurface = LpColors.TextPrimary,
    surfaceVariant = LpColors.SurfaceVariant,
    onSurfaceVariant = LpColors.TextSecondary,
    error = LpColors.Danger,
    onError = Color.Black,
    outline = LpColors.Border,
)

private val LpTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 14.sp),
    bodyMedium = TextStyle(fontSize = 13.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

/** 应用主题：本应用只做深色（手机端纯黑），因此忽略系统亮色设置。 */
@Composable
fun LifePlannerTheme(content: @Composable () -> Unit) {
    // isSystemInDarkTheme() 保留调用点，便于将来支持亮色；当前始终使用深色方案
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = LpColorScheme,
        typography = LpTypography,
        content = content,
    )
}
