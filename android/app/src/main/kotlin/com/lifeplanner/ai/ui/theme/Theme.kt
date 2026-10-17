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
 * 设计令牌（Aurora Ink · v2.1），唯一色值来源，页面里禁止写死 Color(0x...)。
 *
 * 为什么从「纯黑 + 霓虹绿」换成「墨色 + 紫青渐变」：
 * 纯黑打底 + 高饱和荧光绿在长时间使用下压迫感太强，且绿色同时承担
 * 「品牌 / 完成 / 健康」三种语义，用户无法靠颜色区分状态。
 * 现在把「情绪色」交给紫→青渐变，「状态色」交给 Positive/Danger/Warning/Info，
 * 并用略亮的墨色面 + 玻璃质感替代纯黑，层次靠透明度而不是描边堆出来。
 *
 * 兼容约束：下列既有字段被其余 6 个页面直接引用，只能改值、不能删改名字。
 */
object LpColors {
    val Background = Color(0xFF07080C)      // 近黑偏蓝的墨色：比纯黑多一点空气感
    val Surface = Color(0xFF12141C)         // 卡片默认面
    val SurfaceHigh = Color(0xFF191D28)     // 浮起面：底部导航、Sheet、输入框
    val SurfaceVariant = Color(0xFF222838)  // 次级底、进度轨道
    val Border = Color(0x1FFFFFFF)          // 白 12%：卡片描边 / 分割线
    val BorderStrong = Color(0x33FFFFFF)    // 白 20%：聚焦、强调描边
    val Accent = Color(0xFF8B7BFF)          // 品牌主色（紫）：关键动作、选中
    val AccentDim = Color(0xFF6D5CFF)       // 主色深一档，用于渐变落点
    val AccentAlt = Color(0xFF2DD4BF)       // 副色（青）：渐变终点、次要强调
    val Positive = Color(0xFF34D399)        // 完成 / 正向
    val Danger = Color(0xFFFB7185)          // 风险 / 危险
    val Warning = Color(0xFFFBBF24)         // 注意
    val Info = Color(0xFF60A5FA)            // 说明性内容
    val TextPrimary = Color(0xFFF5F7FB)
    val TextSecondary = Color(0xFFC3C9D9)
    val TextMuted = Color(0xFF8A93A8)
}

/**
 * 渐变令牌。
 *
 * 为什么是 Brush 而不是「色值对」：Gradient 需要按 View 尺寸铺开，
 * 定义成 Brush 才能直接交给 background/border/drawPath，页面侧不再重复写方向与透明度。
 * 135° 用 (0,0) → Infinite 表达左上到右下的对角；
 * 160°/180° 这类接近垂直的角度用纵向渐变近似 —— Brush 在定义时拿不到尺寸，
 * 只能按「像素质感」取舍，视觉上仍然是同一种柔和光晕。
 */
object LpGradients {
    /** 品牌渐变 135°：紫 → 青。按钮、进度、选中指示统一用它，避免各处色值漂移。 */
    val Brand: Brush = Brush.linearGradient(
        colors = listOf(LpColors.Accent, LpColors.AccentAlt),
        start = Offset.Zero,
        end = Offset.Infinite,
    )

    /** 温度渐变 135°：暖橙 → 玫红。连续记录 / Streak 等需要「被鼓励」的地方。 */
    val Warm: Brush = Brush.linearGradient(
        colors = listOf(Color(0xFFFFB86B), LpColors.Danger),
        start = Offset.Zero,
        end = Offset.Infinite,
    )

    /** Hero 卡底色 160°：品牌紫 22% → 青 8%，压得很低，避免抢标题文字。 */
    val Hero: Brush = Brush.verticalGradient(
        colors = listOf(
            LpColors.Accent.copy(alpha = 0.22f),
            LpColors.AccentAlt.copy(alpha = 0.08f),
        ),
    )

    /** 玻璃卡面 180°：白 8% → 白 2%，叠在 Surface 上形成「被光照到」的错觉。 */
    val Glass: Brush = Brush.verticalGradient(
        colors = listOf(
            Color(0x14FFFFFF),
            Color(0x05FFFFFF),
        ),
    )

    /** Hero 渐变描边：青色端更淡，避免整卡被一圈高亮框住。 */
    val HeroBorder: Brush = Brush.linearGradient(
        colors = listOf(
            LpColors.Accent.copy(alpha = 0.55f),
            LpColors.AccentAlt.copy(alpha = 0.30f),
        ),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
}

/**
 * 字号令牌。中文排版优先：正文行高 1.5~1.6，标题 SemiBold/Bold。
 * 行高写进 TextStyle（而不是让行高自适应）才能让多行中文的呼吸感一致。
 */
val LpTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 30.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Bold,
    ),
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
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
        letterSpacing = 0.4.sp,
    ),
)

private val LpColorScheme = darkColorScheme(
    primary = LpColors.Accent,
    // 紫底上用近黑文字，对比度足够；用白字反而发灰
    onPrimary = LpColors.Background,
    primaryContainer = LpColors.AccentDim,
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
    // 遮罩是规范里唯一允许出现的固定色值
    scrim = Color(0xCC000000),
)

/** 应用主题：只做深色（墨色底），忽略系统亮色设置。signature 保持不变。 */
@Composable
fun LifePlannerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LpColorScheme,
        typography = LpTypography,
        content = content,
    )
}
