package com.lifeplanner.ai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lifeplanner.ai.AppContainer
import com.lifeplanner.ai.ui.advisor.AdvisorScreen
import com.lifeplanner.ai.ui.dashboard.DashboardScreen
import com.lifeplanner.ai.ui.goals.GoalsScreen
import com.lifeplanner.ai.ui.log.LogScreen
import com.lifeplanner.ai.ui.profile.ProfileScreen
import com.lifeplanner.ai.ui.review.ReviewScreen
import com.lifeplanner.ai.ui.settings.SettingsScreen
import com.lifeplanner.ai.ui.theme.LpColors

/**
 * 应用根：Scaffold + 底部导航 + 页面路由。
 *
 * 页面统一签名：`XxxScreen(container: AppContainer, onNavigate: (NavTarget) -> Unit)`。
 * 底部只放 5 个高频页面；档案与设置在顶栏进入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifePlannerRoot(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: NavTarget.DASHBOARD.route
    val currentTarget = NavTarget.values().firstOrNull { it.route == currentRoute } ?: NavTarget.DASHBOARD

    val bottomItems = listOf(
        NavTarget.DASHBOARD,
        NavTarget.GOALS,
        NavTarget.LOG,
        NavTarget.ADVISOR,
        NavTarget.REVIEW,
    )

    fun navigate(target: NavTarget) {
        if (target.route == currentRoute) return
        val isBottom = target in bottomItems
        navController.navigate(target.route) {
            if (isBottom) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            } else {
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        containerColor = LpColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(currentTarget.label) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LpColors.Background,
                    titleContentColor = LpColors.TextPrimary,
                ),
                actions = {
                    IconButton(onClick = { navigate(NavTarget.PROFILE) }) {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = "个人档案",
                            tint = if (currentTarget == NavTarget.PROFILE) LpColors.Accent else LpColors.TextMuted,
                        )
                    }
                    IconButton(onClick = { navigate(NavTarget.SETTINGS) }) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "设置",
                            tint = if (currentTarget == NavTarget.SETTINGS) LpColors.Accent else LpColors.TextMuted,
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = LpColors.Surface) {
                for (item in bottomItems) {
                    NavigationBarItem(
                        selected = currentTarget == item,
                        onClick = { navigate(item) },
                        icon = { Icon(item.icon(), contentDescription = item.label) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = LpColors.Accent,
                            selectedTextColor = LpColors.Accent,
                            unselectedIconColor = LpColors.TextMuted,
                            unselectedTextColor = LpColors.TextMuted,
                            indicatorColor = LpColors.SurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            NavHost(navController = navController, startDestination = NavTarget.DASHBOARD.route) {
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

private fun NavTarget.icon(): ImageVector = when (this) {
    NavTarget.DASHBOARD -> Icons.Outlined.Home
    NavTarget.GOALS -> Icons.Outlined.Flag
    NavTarget.LOG -> Icons.Outlined.EditNote
    NavTarget.ADVISOR -> Icons.Outlined.ChatBubbleOutline
    NavTarget.REVIEW -> Icons.Outlined.Insights
    NavTarget.PROFILE -> Icons.Outlined.Person
    NavTarget.SETTINGS -> Icons.Outlined.Settings
}
