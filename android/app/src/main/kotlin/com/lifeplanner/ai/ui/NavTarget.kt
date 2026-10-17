package com.lifeplanner.ai.ui

/**
 * 页面导航目标。每个页面一个 Compose 文件，文件名与 route 对应：
 * - dashboard → ui/dashboard/DashboardScreen.kt
 * - goals     → ui/goals/GoalsScreen.kt
 * - log       → ui/log/LogScreen.kt
 * - advisor   → ui/advisor/AdvisorScreen.kt
 * - review    → ui/review/ReviewScreen.kt
 * - profile   → ui/profile/ProfileScreen.kt
 * - settings  → ui/settings/SettingsScreen.kt
 * - interview → ui/interview/InterviewScreen.kt（AI 建档访谈，全屏沉浸，不在底部导航里）
 *
 * 页面 composable 的统一签名：
 *   @Composable fun XxxScreen(container: AppContainer, onNavigate: (NavTarget) -> Unit)
 */
enum class NavTarget(val route: String, val label: String) {
    DASHBOARD("dashboard", "总览"),
    GOALS("goals", "目标"),
    LOG("log", "记录"),
    ADVISOR("advisor", "顾问"),
    REVIEW("review", "复盘"),
    PROFILE("profile", "档案"),
    SETTINGS("settings", "设置"),
    INTERVIEW("interview", "认识你"),
}
