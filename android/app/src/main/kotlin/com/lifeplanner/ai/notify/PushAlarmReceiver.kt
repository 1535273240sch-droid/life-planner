package com.lifeplanner.ai.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lifeplanner.ai.data.SettingsStore
import com.lifeplanner.core.daily.PushKind
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * 闹钟到点：展示通知，并立刻登记下一次。
 *
 * 只做两件很轻的事（读一次 SharedPreferences、发一条通知、登记一个闹钟），
 * 所以不需要 goAsync：onReceive 里完成不会触发 ANR。
 */
class PushAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PushAlarmScheduler.ACTION_PUSH) return
        val kind = parseKind(intent.getStringExtra(PushAlarmScheduler.EXTRA_KIND)) ?: return

        val config = SettingsStore(context.applicationContext).loadPushConfig()
        // 闹钟可能在用户关闭推送之前就已登记，触发时再确认一次，避免“已关闭还弹通知”。
        val enabled = when (kind) {
            PushKind.MORNING -> config.morningEnabled
            PushKind.EVENING -> config.eveningEnabled
        }
        if (enabled) {
            // 展示失败（没有通知权限）不影响后面的重排：用户可能过一会儿才去授权。
            PushNotifier(context).show(kind)
        }

        // 用“原定触发时刻”作为计算下一次的锚点：
        // 闹钟有可能被系统提前几毫秒投递，若直接用当前时间，
        // 同一个时段会被重新算成“还没到”，于是几秒后又弹一次。
        val triggerAt = intent.getLongExtra(PushAlarmScheduler.EXTRA_TRIGGER_AT, 0L)
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val anchor = Instant.fromEpochMilliseconds(if (triggerAt > nowMs) triggerAt else nowMs)
        PushAlarmScheduler(context).schedule(config, anchor)
    }

    private fun parseKind(name: String?): PushKind? =
        if (name.isNullOrBlank()) null else runCatching { PushKind.valueOf(name) }.getOrNull()
}
