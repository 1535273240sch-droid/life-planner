package com.lifeplanner.ai.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 系统事件后重排闹钟。
 *
 * 三种情况都会让已有的闹钟失效或算错时间：
 * - 重启：闹钟被系统全部清空；
 * - 手动改系统时间：原来的绝对时刻不再对应 07:30 / 21:30；
 * - 改时区：本地时间整体平移，必须按新时区重算。
 * 三者都统一“重新读设置 + 排下一次”，不依赖之前登记的状态。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> PushAlarmScheduler(context).rescheduleFromSettings()
        }
    }
}
