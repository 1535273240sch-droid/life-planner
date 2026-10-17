package com.lifeplanner.ai.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lifeplanner.ai.AppContainer
import com.lifeplanner.ai.notify.PushNavigation
import com.lifeplanner.ai.ui.advisor.AdvisorScreen
import com.lifeplanner.ai.ui.components.LpBackground
import com.lifeplanner.ai.ui.components.LpMotion
import com.lifeplanner.ai.ui.dashboard.DashboardScreen
import com.lifeplanner.ai.ui.goals.GoalsScreen
import com.lifeplanner.ai.ui.log.LogScreen
import com.lifeplanner.ai.ui.profile.ProfileScreen
import com.lifeplanner.ai.ui.review.ReviewScreen
import com.lifeplanner.ai.ui.settings.SettingsScreen
import com.lifeplanner.ai.ui.theme.LpColors
import com.lifeplanner.ai.ui.theme.LpGradients
import kotlinx.coroutines.flow.StateFlow

/**
 * 应用根：极光背景 + NavHost + 悬浮胶囊底部导航。
 *
 * 页面统一签名：`XxxScreen(container: AppContainer, onNavigate: (NavTarget) -> Unit)`。
 * 底部只放 5 个高频页面；档案与设置在各页顶栏进入。
 *
 * 导航壳负责三件事，页面自己不要再做：
 * 1. 状态栏/导航栏 insets —— Scaffold 在没有 TopAppBar 时会自动给内容补状态栏高度，
 *    所以首页（自带头部）不需要也不应该再加 statusBarsPadding()，否则双倍留白；
 * 2. 键盘让位 —— 根节点 imePadding()，底部胶囊随键盘上移，不遮挡输入框；
 * 3. 页面转场 —— fade + 16dp 上移，进入 260ms / 退出 160ms。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifePlannerRoot(
    container: AppContainer,
    // 通知点击路由信箱。默认接 PushNavigation；抽成参数是为了可测/可替换，
    // 不改变 MainActivity 侧的调用方式（LifePlannerRoot(container) 依然成立）。
    pushRoutes: StateFlow<String?> = PushNavigation.pending,
    consumePushRoute: () -> String? = PushNavigation::consume,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: NavTarget.DASHBOARD.route
    val currentTarget = NavTarget.values().firstOrNull { it.route == currentRoute } ?: NavTarget.DASHBOARD
    val bottomItems = remember {
        listOf(
            NavTarget.DASHBOARD,
            NavTarget.GOALS,
            NavTarget.LOG,
            NavTarget.ADVISOR,
            NavTarget.REVIEW,
        )
    }

    fun navigate(target: NavTarget) {
        if (target.route == currentRoute) return
        val isBottom = target in bottomItems
        navController.navigate(target.route) {
            if (isBottom) {
                // 底部 tab 之间切换要保留各自的滚动位置；重复点同一个 tab 不重建页面
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            } else {
                launchSingleTop = true
            }
        }
    }

    // 通知点击路由：pending 非空时跳转，然后立刻 consume。
    // 为什么 effect 只依赖 pendingRoute：consume 会把值清成 null，
    // effect 以 null 重跑一次直接返回，因此配置变化/重组都不会重复导航。
    val pendingRoute by pushRoutes.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        NavTarget.values().firstOrNull { it.route == route }?.let { navigate(it) }
        consumePushRoute()
    }

    // 页面入场位移按屏幕密度换算，避免在 2x/3x 屏上「比 16dp 更大」
    val density = LocalDensity.current
    val enterOffsetPx = with(density) { LpMotion.EnterOffsetDp.dp.roundToPx() }

    LpBackground(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            // edge-to-edge 下窗口不会自动为输入法让位；不加这段时键盘会盖住底部胶囊，
            // 用户点「总览/目标」实际点到的是键盘，看起来像卡在设置页。
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            containerColor = Color.Transparent,
            topBar = {
                // 首页自带头部（问候 + 档案/设置圆钮），不再叠一个 TopAppBar
                if (currentTarget != NavTarget.DASHBOARD) {
                    TopAppBar(
                        title = {
                            Text(
                                text = currentTarget.label,
                                style = MaterialTheme.typography.headlineMedium,
                                color = LpColors.TextPrimary,
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                        ),
                        actions = {
                            IconButton(onClick = { navigate(NavTarget.PROFILE) }) {
                                Icon(
                                    imageVector = Icons.Outlined.Person,
                                    contentDescription = "个人档案",
                                    tint = if (currentTarget == NavTarget.PROFILE) {
                                        LpColors.Accent
                                    } else {
                                        LpColors.TextMuted
                                    },
                                )
                            }
                            IconButton(onClick = { navigate(NavTarget.SETTINGS) }) {
                                Icon(
                                    imageVector = Icons.Outlined.Settings,
                                    contentDescription = "设置",
                                    tint = if (currentTarget == NavTarget.SETTINGS) {
                                        LpColors.Accent
                                    } else {
                                        LpColors.TextMuted
                                    },
                                )
                            }
                        },
                    )
                }
            },
            bottomBar = {
                LpFloatingBottomBar(
                    items = bottomItems,
                    selected = currentTarget,
                    onSelect = { navigate(it) },
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                NavHost(
                    navController = navController,
                    startDestination = NavTarget.DASHBOARD.route,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = {
                        fadeIn(animationSpec = LpMotion.fadeInSpec) +
                            slideInVertically(animationSpec = LpMotion.slideSpec) { enterOffsetPx }
                    },
                    exitTransition = {
                        fadeOut(animationSpec = LpMotion.fadeOutSpec)
                    },
                    popEnterTransition = {
                        fadeIn(animationSpec = LpMotion.fadeInSpec)
                    },
                    popExitTransition = {
                        fadeOut(animationSpec = LpMotion.fadeOutSpec) +
                            slideOutVertically(animationSpec = LpMotion.slideSpec) { enterOffsetPx }
                    },
                ) {
                    composable(NavTarget.DASHBOARD.route) { DashboardScreen(container, ::navigate) }
                    composable(NavTarget.GOALS.route) { GoalsScreen(container, ::navigate) }
                    composable(NavTarget.LOG.route) { LogScreen(container, ::navigate) }
                    composable(NavTarget.ADVISOR.route) { AdvisorScreen(container, ::navigate) }
                    composable(NavTarget.REVIEW.route) { ReviewScreen(container, ::navigate) }
                    composable(NavTarget.PROFILE.route) { ProfileScreen(container, ::navigate) }
                    composable(NavTarget.SETTINGS.route) { SettingsScreen(container, ::navigate) }
                }
            }
        }
    }
}

/**
 * 悬浮胶囊底部导航：左右 16dp、距底 12dp、高 64dp、圆角 28dp（DESIGN.md §4）。
 *
 * 选中指示器是一个独立色块，用 animateDpAsState 平移（220ms），
 * 而不是每个图标各自变色的「硬切」—— 后者在快速切换时会有闪烁感。
 */
@Composable
private fun LpFloatingBottomBar(
    items: List<NavTarget>,
    selected: NavTarget,
    onSelect: (NavTarget) -> Unit,
) {
    val density = LocalDensity.current
    // 键盘弹出时外层已经 imePadding() 让出了输入法高度，此时手势条被键盘盖住，
    // 再叠一次导航栏高度会让胶囊凭空浮起一条空隙。
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val navBarInset = if (imeVisible) {
        0.dp
    } else {
        with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = navBarInset + 12.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(BarShape)
                .background(LpColors.SurfaceHigh.copy(alpha = 0.92f))
                .background(LpGradients.Glass)
                .border(1.dp, LpColors.Border, BarShape)
                .padding(6.dp),
        ) {
            val itemWidth = maxWidth / items.size
            val selectedIndex = items.indexOf(selected).coerceAtLeast(0)
            val indicatorOffset by animateDpAsState(
                targetValue = itemWidth * selectedIndex.toFloat(),
                animationSpec = LpMotion.indicatorSpec,
                label = "lpNavIndicator",
            )

            // 选中指示：品牌渐变压到 22%/16%，只做「被点亮」的暗示，不抢图标颜色
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .clip(IndicatorShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                LpColors.Accent.copy(alpha = 0.22f),
                                LpColors.AccentAlt.copy(alpha = 0.16f),
                            ),
                            start = Offset.Zero,
                            end = Offset.Infinite,
                        ),
                    ),
            )

            Row(modifier = Modifier.fillMaxSize()) {
                items.forEach { item ->
                    val active = item == selected
                    val tint = if (active) LpColors.TextPrimary else LpColors.TextMuted
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(IndicatorShape)
                            .clickable(
                                interactionSource = interaction,
                                indication = LocalIndication.current,
                                role = Role.Tab,
                                onClick = { onSelect(item) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = item.icon(),
                                contentDescription = item.label,
                                tint = tint,
                                modifier = Modifier.size(21.dp),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = tint,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val BarShape = RoundedCornerShape(28.dp)
private val IndicatorShape = RoundedCornerShape(22.dp)

private fun NavTarget.icon(): ImageVector = when (this) {
    NavTarget.DASHBOARD -> Icons.Outlined.Home
    NavTarget.GOALS -> Icons.Outlined.Flag
    NavTarget.LOG -> Icons.Outlined.EditNote
    NavTarget.ADVISOR -> Icons.Outlined.ChatBubbleOutline
    NavTarget.REVIEW -> Icons.Outlined.Insights
    NavTarget.PROFILE -> Icons.Outlined.Person
    NavTarget.SETTINGS -> Icons.Outlined.Settings
}