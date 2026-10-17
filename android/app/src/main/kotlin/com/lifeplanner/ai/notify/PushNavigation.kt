package com.lifeplanner.ai.notify

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 通知点击后的跨组件导航信箱。
 *
 * 为什么不用 Intent 直接携带路由：
 * - 通知点击可能发生在进程被回收之后，Activity 是全新的；
 * - 也可能发生在 Activity 已在前台的 onNewIntent。
 * 用一个进程内单例接收路由，由 LifePlannerRoot 订阅消费，
 * 这样 MainActivity 只需要“投递”，不需要知道 UI 的导航结构。
 */
object PushNavigation {

    /** 早间推送打开的页面（与 NavTarget.DASHBOARD.route 一致）。 */
    const val ROUTE_DASHBOARD = "dashboard"

    /** 晚间推送打开的页面（与 NavTarget.REVIEW.route 一致）。 */
    const val ROUTE_REVIEW = "review"

    /** 通知 Intent 里携带路由的 extra 名。 */
    const val EXTRA_PUSH_ROUTE = "push_route"

    /** 待消费的路由；null 表示当前没有待处理的跳转。 */
    val pending = MutableStateFlow<String?>(null)

    /** 投递一条路由，覆盖此前未消费的路由（后到的点击优先）。 */
    @Synchronized
    fun post(route: String) {
        pending.value = route
    }

    /** 取走路由：返回值并清空，避免旋转/重建时重复跳转。 */
    @Synchronized
    fun consume(): String? {
        val route = pending.value
        pending.value = null
        return route
    }
}
