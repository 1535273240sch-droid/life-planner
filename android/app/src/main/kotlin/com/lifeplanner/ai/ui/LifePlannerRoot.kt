package com.lifeplanner.ai.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
import com.lifeplanner.ai.ui.interview.InterviewScreen
import com.lifeplanner.ai.ui.log.LogScreen
import com.lifeplanner.ai.ui.profile.ProfileScreen
import com.lifeplanner.ai.ui.review.ReviewScreen
import com.lifeplanner.ai.ui.settings.SettingsScreen
import com.lifeplanner.ai.ui.theme.LpColors
import kotlinx.coroutines.flow.StateFlow

/**
 * 应用根：背景 + NavHost + 底部导航。
 *
 * 2.2 的关键变化：
 * 1. 底部导航从"悬浮胶囊"改成贴着底部的整条栏（含导航栏 insets）。
 *    悬浮胶囊在滚动页面里很容易让人误以为内容被盖住（截断感/重叠感），
 *    整条栏是系统级的标准做法，内容边界更清楚。
 * 2. 首页与访谈页不显示底部栏：首页自带头部；访谈是沉浸式对话，需要整屏。
 * 3. 转场更短更轻（210ms 淡入 + 8dp 上移），只保留"页与页之间有过渡"，
 *    不再做大幅位移与按压回弹。
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

    // 首页自带头部；访谈是沉浸式对话——两者都不显示顶栏与底栏。
    val immersive = currentTarget == NavTarget.DASHBOARD || currentTarget == NavTarget.INTERVIEW

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
    // effect 只依赖 pendingRoute：consume 置 null 后重跑直接返回，重建时不会重复导航。
    val pendingRoute by pushRoutes.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        NavTarget.values().firstOrNull { it.route == route }?.let { navigate(it) }
        consumePushRoute()
    }

    val density = LocalDensity.current
    val enterOffsetPx = with(density) { LpMotion.EnterOffsetDp.dp.roundToPx() }

    LpBackground(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            // edge-to-edge 下窗口不会自动为输入法让位；不加这段时键盘会盖住输入框。
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = {
                if (!immersive) {
                    TopAppBar(
                        title = {
                            Text(
                                text = currentTarget.label,
                                style = MaterialTheme.typography.headlineMedium,
                                color = LpColors.TextPrimary,
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
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
                if (!immersive) {
                    LpBottomBar(
                        items = bottomItems,
                        selected = currentTarget,
                        onSelect = { navigate(it) },
                    )
                }
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
                    composable(NavTarget.INTERVIEW.route) {
                        InterviewScreen(
                            container = container,
                            onNavigate = ::navigate,
                            // 保存成功后回到首页；首页 ON_RESUME 会刷新档案/目标。
                            onSaved = {
                                navController.popBackStack(NavTarget.INTERVIEW.route, inclusive = true)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 标准底部导航栏：整条贴底、顶部一条细分割线、带导航栏 insets。
 *
 * 为什么不再做悬浮胶囊：滚动内容从胶囊两侧露出来时，视觉上像"内容被切断了"，
 * 这也是用户反馈"页面重叠、混乱"的来源之一。整条栏把内容与导航彻底分开。
 */
@Composable
private fun LpBottomBar(
    items: List<NavTarget>,
    selected: NavTarget,
    onSelect: (NavTarget) -> Unit,
) {
    val density = LocalDensity.current
    // 键盘弹出时外层已经 imePadding()；此时不叠加导航栏高度，避免多出一条空隙。
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val bottomInset = if (imeVisible) {
        0.dp
    } else {
        with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(LpColors.SurfaceHigh)
            .padding(bottom = bottomInset),
    ) {
        HorizontalDivider(color = LpColors.Border, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            items.forEach { item ->
                val active = item == selected
                val tint = if (active) LpColors.Accent else LpColors.TextMuted
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(role = Role.Tab) { onSelect(item) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = item.icon(),
                        contentDescription = item.label,
                        tint = tint,
                        modifier = Modifier.size(21.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

private fun NavTarget.icon(): ImageVector = when (this) {
    NavTarget.DASHBOARD -> Icons.Outlined.Home
    NavTarget.GOALS -> Icons.Outlined.Flag
    NavTarget.LOG -> Icons.Outlined.EditNote
    NavTarget.ADVISOR -> Icons.Outlined.ChatBubbleOutline
    NavTarget.REVIEW -> Icons.Outlined.Insights
    NavTarget.PROFILE -> Icons.Outlined.Person
    NavTarget.SETTINGS -> Icons.Outlined.Settings
    NavTarget.INTERVIEW -> Icons.Outlined.RecordVoiceOver
}