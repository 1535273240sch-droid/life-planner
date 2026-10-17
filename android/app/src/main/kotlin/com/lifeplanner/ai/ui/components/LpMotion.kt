package com.lifeplanner.ai.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay

/**
 * 动效令牌。
 *
 * 为什么集中放在这里：动效最容易在页面各写一套（0.2s / 250ms / 300ms 混用），
 * 结果是「每个动画都挺顺，连起来却显得散」。所有时长、曲线、缩放都从这里取；
 * 页面只表达「我要入场」，不决定快慢。
 *
 * 克制原则（DESIGN.md §5）：
 * - 只做位移 + 淡入 + 缩放，不做旋转/弹性夸张，键盘弹出与列表滚动时不触发整屏动画；
 * - 列表错峰只对前 8 项生效，超过一次性显示，避免长列表滚动时逐项闪烁。
 */
object LpMotion {
    /** 页面进入：260ms（与退出 160ms 形成「进慢出快」的方向感）。 */
    const val PageEnterMs = 260

    /** 页面退出/返回：160ms。 */
    const val PageExitMs = 160

    /** 内容淡入（加载完成）：220ms，避免整屏 loading 闪烁。 */
    const val ContentFadeMs = 220

    /** 选中指示器移动：220ms。 */
    const val IndicatorMs = 220

    /** 进度/统计数字滚动：600ms。 */
    const val NumberMs = 600

    /** 列表错峰步长：40ms。 */
    const val StaggerStepMs = 40

    /** 最多参与错峰的条目数；再往后一次性显示，保证长列表不掉帧。 */
    const val StaggerMaxIndex = 8

    /** 卡片按压缩放。 */
    const val PressScale = 0.97f

    /** 入场位移 16dp 对应的像素由调用方按 density 换算；这里只给 dp 语义。 */
    const val EnterOffsetDp = 16

    /** 界面主曲线：起步快、收尾稳，接近 Material 的 emphasized decelerate。 */
    val EaseOut: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 退出曲线：匀速略减速，短时长下更「干脆」。 */
    val EaseInOut: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** 底色/透明度淡入淡出。 */
    val fadeInSpec: TweenSpec<Float> = tween(PageEnterMs, easing = EaseOut)
    val fadeOutSpec: TweenSpec<Float> = tween(PageExitMs, easing = EaseInOut)

    /** 位移（slideInVertically 需要 IntOffset 的 spec）。 */
    val slideSpec: TweenSpec<IntOffset> = tween(PageEnterMs, easing = EaseOut)

    /** 内容淡入 + 上移。 */
    val contentSpec: TweenSpec<Float> = tween(ContentFadeMs, easing = EaseOut)

    /** 指示器位移（Dp）。 */
    val indicatorSpec: TweenSpec<Dp> = tween(IndicatorMs, easing = EaseOut)

    /** 展开/收起的高度补间（AnimatedVisibility 需要 IntSize 的 spec）。 */
    val sizeSpec: TweenSpec<IntSize> = tween(ContentFadeMs, easing = EaseOut)

    /** 数字/环形进度。 */
    val numberSpec: FiniteAnimationSpec<Float> = tween(NumberMs, easing = EaseOut)

    /** 按压回弹：短、轻，不能有「黏手感」。 */
    val pressSpec: FiniteAnimationSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
}

/**
 * 卡片/按钮按压反馈：按到 0.97，松开回弹。
 *
 * 为什么要传 interactionSource：可点击组件的 clickable 必须和这里共用同一个
 * interactionSource，否则收不到 press 事件。组件内部都会自己 new 一个传进来；
 * 外部调用方不传时只拿到静态 1f，不会有副作用。
 *
 * @param enabled false 时不响应按压（禁用按钮不做缩放，避免"看起来能点"）。
 */
@Composable
fun Modifier.lpPressScale(
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val target = if (pressed && enabled) LpMotion.PressScale else 1f
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = LpMotion.pressSpec,
        label = "lpPressScale",
    )
    return this.scale(scale)
}

/**
 * 首屏/区块入场：前 [LpMotion.StaggerMaxIndex] 项按 40ms 错峰淡入 + 轻微上移。
 *
 * 为什么超过 8 项就直接显示：长列表逐项延迟会让用户以为界面卡住，
 * 而且每次都重新动画在 LazyColumn 里会随滚动反复触发。只在「首帧」做一次，
 * 由 remember 保证（重组不会重播）。
 *
 * @param index 该项在区块内的序号（0 开始）。
 */
@Composable
fun Modifier.lpStaggerIn(index: Int): Modifier {
    if (index >= LpMotion.StaggerMaxIndex) return this

    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(index) {
        delay((index * LpMotion.StaggerStepMs).toLong())
        appeared = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = LpMotion.contentSpec,
        label = "lpStaggerAlpha",
    )
    val offsetY by animateFloatAsState(
        targetValue = if (appeared) 0f else 10f,
        animationSpec = LpMotion.contentSpec,
        label = "lpStaggerOffset",
    )
    return this
        .alpha(alpha)
        .graphicsLayerOffset(offsetY)
}

/** 把「任意像素位移」收敛成一个小工具，避免各处重复写 graphicsLayer 代码块。 */
private fun Modifier.graphicsLayerOffset(offsetY: Float): Modifier =
    this.then(
        Modifier.graphicsLayer {
            translationY = offsetY
        },
    )