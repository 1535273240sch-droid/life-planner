package com.lifeplanner.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 设计令牌（Warm Journal · v2.2），唯一色值来源，页面里禁止写死 Color(0x...)。
 *
 * 为什么从上一版的"紫青极光 + 玻璃"换成暖色手账：
 * - 极光渐变、发光光晕、玻璃卡片与星芒图标叠在一起，会让私人生活工具看起来像
 *   一个 AI 生成器（用户原话："太 AI 味"）；工具应该是安静的，内容才是主角。
 * - 暖灰底 + 陶土色强调更接近纸张与台灯下的阅读感，长时间记录不刺眼。
 * - 状态色（完成/风险/注意/信息）与品牌色彻底分离，颜色只表达状态，不再当装饰。
 *
 * 兼容约束：下列字段被 7 个页面直接引用，只能改值、不能删改名字。
 */
object LpColors {
    val Background = Color(0xFF121110)      // 暖调近黑（像台灯关掉后的纸）
    val Surface = Color(0xFF1B1917)         // 卡片/输入面
    val SurfaceHigh = Color(0xFF24211E)     // 浮起面（弹窗、底部栏）
    val SurfaceVariant = Color(0xFF2C2824)  // 次级底、输入框、进度轨道
    val Border = Color(0x1FFFFFFF)          // 白 12% 细边
    val BorderStrong = Color(0x2EFFFFFF)    // 白 18% 聚焦边
    val Accent = Color(0xFFD98E63)          // 陶土色：唯一强调色，只给关键动作
    val AccentDim = Color(0xFFC4764B)
    val AccentAlt = Color(0xFF8FAE8B)       // 鼠尾草绿：次级正向
    val Positive = Color(0xFF9DBF9E)        // 完成
    val Danger = Color(0xFFD97A6C)          // 风险
    val Warning = Color(0xFFD9A441)         // 注意
    val Info = Color(0xFF8FA8B8)            // 说明
    val TextPrimary = Color(0xFFF3EDE4)     // 暖白正文
    val TextSecondary = Color(0xFFC9C0B4)
    val TextMuted = Color(0xFF948B80)
}

/**
 * 渐变令牌。
 *
 * 2.2 起只保留几处"几乎看不出来"的渐变用于层次过渡：按钮、背景、卡片顶光。
 * 不再出现发光/霓虹/高饱和对角渐变——那是上一版"AI 味"的主要来源。
 */
object LpGradients {
    /** 品牌色：同色系深浅，几乎是一条实色（保留名字兼容旧调用）。 */
    val Brand: Brush = Brush.linearGradient(
        colors = listOf(LpColors.Accent, LpColors.AccentDim),
        start = Offset.Zero,
        end = Offset.Infinite,
    )

    /** 暖色：仅在 Streak 等"被鼓励"的位置少量使用。 */
    val Warm: Brush = Brush.linearGradient(
        colors = listOf(LpColors.Accent, Color(0xFFB86B44)),
        start = Offset.Zero,
        end = Offset.Infinite,
    )

    /** Hero 底色：2.2 起首页不再有 Hero 卡，保留为透明以免旧调用出错。 */
    val Hero: Brush = Brush.verticalGradient(
        colors = listOf(Color.Transparent, Color.Transparent),
    )

    /** 玻璃高光：压到几乎不可见，只给浮起面一点空气感。 */
    val Glass: Brush = Brush.verticalGradient(
        colors = listOf(Color(0x0AFFFFFF), Color(0x00FFFFFF)),
    )

    /** Hero 描边：保留名字兼容，实际为细边框色。 */
    val HeroBorder: Brush = Brush.linearGradient(
        colors = listOf(LpColors.Border, LpColors.Border),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
}

/**
 * 字号令牌。
 *
 * 2.2 起整体降一档、字重更轻：私人工具不需要"大字报"，
 * 靠间距和层级区分信息，而不是靠加粗与放大。
 */
val LpTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 27.sp,
        lineHeight = 35.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    headlineMedium = TextStyle(
        fontSize = 21.sp,
        lineHeight = 29.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 15.5.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 12.5.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
    ),
)

private val LpColorScheme = darkColorScheme(
    primary = LpColors.Accent,
    onPrimary = LpColors.Background,
    primaryContainer = LpColors.SurfaceVariant,
    onPrimaryContainer = LpColors.TextPrimary,
    secondary = LpColors.AccentAlt,
    onSecondary = LpColors.Background,
    tertiary = LpColors.Info,
    onTertiary = LpColors.Background,
    background = LpColors.Background,
    onBackground = LpColors.TextPrimary,
    surface = LpColors.Surface,
    onSurface = LpColors.TextPrimary,
    surfaceVariant = LpColors.SurfaceVariant,
    onSurfaceVariant = LpColors.TextSecondary,
    error = LpColors.Danger,
    onError = LpColors.Background,
    outline = LpColors.Border,
    outlineVariant = LpColors.Border,
    scrim = Color(0xCC000000),
)

/** 应用主题：只做深色（暖调），忽略系统亮色设置。signature 保持不变。 */
@Composable
fun LifePlannerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LpColorScheme,
        typography = LpTypography,
        content = content,
    )
}
