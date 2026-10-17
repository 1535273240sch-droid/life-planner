package com.lifeplanner.ai.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lifeplanner.ai.MainActivity
import com.lifeplanner.ai.R
import com.lifeplanner.core.daily.PushKind
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 本地通知的展示与渠道管理（渠道 id 固定为 [CHANNEL_ID]，用户可在系统里单独关闭）。
 *
 * 不使用推送服务：内容离线生成，所以不需要 FCM、不需要联网、也不会把用户数据发出去。
 */
class PushNotifier(context: Context) {

    private val appContext = context.applicationContext

    /**
     * 渠道应该在首次推送之前就建好：
     * 从通知栏冷启动应用时，渠道若不存在用户会先看到“应用未创建渠道”的异常表现；
     * 提前建好也让用户能更早地在系统设置里看到并单独管理它。
     */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "每天早晚各一次的本地提醒（内容离线生成，不联网）"
            enableVibration(true)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 展示一条推送。
     *
     * @return false 表示系统没有授予通知权限，通知被静默丢弃（Android 13+ 不抛异常），
     *         调用方需要据此提示用户去开启权限。
     */
    fun show(kind: PushKind, date: LocalDate = today()): Boolean {
        ensureChannel()
        if (!PushPermissions.notificationsGranted(appContext)) return false

        val content = DailyPush.forKind(kind, date)
        val open = Intent(appContext, MainActivity::class.java).apply {
            // NEW_TASK：从广播接收器启动 Activity 必须带；
            // CLEAR_TOP + SINGLE_TOP：应用已在前台时复用同一个 Activity，走 onNewIntent。
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(PushNavigation.EXTRA_PUSH_ROUTE, DailyPush.routeOf(kind))
        }
        val contentIntent = PendingIntent.getActivity(
            appContext,
            requestCodeOf(kind),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_life)
            .setContentTitle(content.title)
            .setContentText(content.body.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        return try {
            NotificationManagerCompat.from(appContext).notify(notificationIdOf(kind), notification)
            true
        } catch (e: SecurityException) {
            // 权限可能在检查之后被撤销，这里兜底，避免定时任务因异常崩掉。
            false
        }
    }

    private fun today(): LocalDate =
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    companion object {
        const val CHANNEL_ID = "daily_push"
        const val CHANNEL_NAME = "每日推送"

        /** 每类推送一个固定通知 id：新的一天会覆盖旧通知，通知栏不会越堆越多。 */
        private fun notificationIdOf(kind: PushKind): Int = 2000 + kind.ordinal

        /** 两类推送用不同 requestCode，否则后建的 PendingIntent 会覆盖前一个。 */
        private fun requestCodeOf(kind: PushKind): Int = 3000 + kind.ordinal
    }
}
