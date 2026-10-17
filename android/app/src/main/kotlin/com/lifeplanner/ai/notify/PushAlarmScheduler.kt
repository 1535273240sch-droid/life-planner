package com.lifeplanner.ai.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.lifeplanner.ai.data.SettingsStore
import com.lifeplanner.core.daily.PushConfig
import com.lifeplanner.core.daily.PushKind
import com.lifeplanner.core.daily.PushSchedule
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

/**
 * 每日推送的闹钟调度。
 *
 * 策略：任何时刻只登记“下一次”一次闹钟，触发后由 [PushAlarmReceiver] 重新计算并登记下一次。
 *
 * 为什么不用 WorkManager 周期任务：
 * 周期任务没有“每天固定 07:30”的语义，门限一到可能晚几分钟到几小时，
 * 而闹钟（尤其 setExactAndAllowWhileIdle）能贴近用户设定的时间；
 * 代价是必须自己处理重启 / 改时间 / 改时区后的重排（见 [BootReceiver]），
 * 这部分成本远小于多引入一个依赖。
 */
class PushAlarmScheduler(context: Context) {

    private val appContext = context.applicationContext
    private val alarmManager: AlarmManager? = appContext.getSystemService(AlarmManager::class.java)

    /** 按配置登记下一次推送；两个时段都关闭时取消已有闹钟。 */
    fun schedule(config: PushConfig, now: Instant = Clock.System.now()) {
        val manager = alarmManager ?: return
        val slot = PushSchedule.next(now, config, TimeZone.currentSystemDefault())
        if (slot == null) {
            cancelAll()
            return
        }

        // 先撤销另一个时段的旧闹钟，保证队列里始终只有一个待触发项。
        for (kind in PushKind.values()) {
            if (kind != slot.kind) cancelKind(kind)
        }

        val triggerAt = slot.at.toEpochMilliseconds()
        val pending = alarmIntent(slot.kind, triggerAt)
        try {
            if (canScheduleExact(manager)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        } catch (e: SecurityException) {
            // Android 12+ 精确闹钟权限可能在运行中被撤销；退化成非精确闹钟，
            // 宁可晚几分钟，也不能让定时逻辑抛异常导致后续推送全丢。
            Log.w(TAG, "精确闹钟不可用，退化为非精确闹钟", e)
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    /** 从本地设置读取配置后再排；重启、改时间、改时区后统一走这里。 */
    fun rescheduleFromSettings(now: Instant = Clock.System.now()) {
        val config = SettingsStore(appContext).loadPushConfig()
        schedule(config, now)
    }

    /** 关闭推送 / 修改配置导致无时段可用时，必须显式取消，否则旧闹钟仍会触发。 */
    fun cancelAll() {
        for (kind in PushKind.values()) cancelKind(kind)
    }

    private fun cancelKind(kind: PushKind) {
        val manager = alarmManager ?: return
        val pending = alarmIntent(kind, 0L)
        manager.cancel(pending)
        // cancel 之后 PendingIntent 仍存在，再 cancel() 一次可释放系统里的记录。
        pending.cancel()
    }

    private fun canScheduleExact(manager: AlarmManager): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return manager.canScheduleExactAlarms()
    }

    private fun alarmIntent(kind: PushKind, triggerAt: Long): PendingIntent {
        val intent = Intent(appContext, PushAlarmReceiver::class.java).apply {
            action = ACTION_PUSH
            putExtra(EXTRA_KIND, kind.name)
            putExtra(EXTRA_TRIGGER_AT, triggerAt)
        }
        return PendingIntent.getBroadcast(
            appContext,
            requestCodeOf(kind),
            intent,
            // extras 被忽略，同一 requestCode 的 PendingIntent 会被复用，FLAG_UPDATE_CURRENT 保证里面的时刻是最新的。
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "PushAlarmScheduler"

        const val ACTION_PUSH = "com.lifeplanner.ai.notify.action.PUSH"
        const val EXTRA_KIND = "push_kind"
        const val EXTRA_TRIGGER_AT = "push_trigger_at"

        /** 两类推送必须用不同 requestCode，否则后登记的会覆盖前一个。 */
        fun requestCodeOf(kind: PushKind): Int = 1000 + kind.ordinal
    }
}
