package com.lifeplanner.ai.notify

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 推送相关的系统权限判断与引导入口。
 *
 * 集中一处的原因：Android 13 的通知权限、Android 12 的精确闹钟授权判断逻辑不同，
 * 散落在 Activity / 页面 / 调度器里很容易各写一份且漏掉版本判断。
 */
object PushPermissions {

    /** 当前是否真的能弹出通知（API 33+ 看运行时权限，其它版本看通知总开关）。 */
    fun notificationsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** 只有 API 33+ 才有 POST_NOTIFICATIONS 运行时权限，更低版本不用申请。 */
    fun shouldRequestNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * 是否能排精确闹钟。
     * Android 12+ 用户或厂商可以撤销 SCHEDULE_EXACT_ALARM；撤销后只能退化为非精确闹钟
     * （可能延迟几分钟），此时设置页需要给出引导，而不是静默降级。
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return manager.canScheduleExactAlarms()
    }

    /** 跳到本应用的通知设置页。 */
    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** 跳到“允许精确闹钟”的授权页；API 31 以下没有这个开关，返回 null。 */
    fun exactAlarmSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:" + context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
