package com.lifeplanner.core.daily

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * 每日推送的纯逻辑（不依赖 Android，方便在 JVM 上做单元测试）。
 *
 * 定时策略：每次只登记“下一次”的闹钟；触发后再算下一次。
 * 这样不需要 WorkManager，也能在跨天、改时间、重启后自愈。
 */

/** 推送时段。 */
enum class PushKind { MORNING, EVENING }

/** 用户在设置页配置的推送偏好。 */
data class PushConfig(
    val morningEnabled: Boolean = true,
    val morningHour: Int = 7,
    val morningMinute: Int = 30,
    val eveningEnabled: Boolean = true,
    val eveningHour: Int = 21,
    val eveningMinute: Int = 30,
) {
    /** 越界配置一律收敛到合法范围，避免在设置页输入异常时排不出闹钟。 */
    fun sanitized(): PushConfig = copy(
        morningHour = morningHour.coerceIn(0, 23),
        morningMinute = morningMinute.coerceIn(0, 59),
        eveningHour = eveningHour.coerceIn(0, 23),
        eveningMinute = eveningMinute.coerceIn(0, 59),
    )
}

/** 一次待触发的推送。 */
data class PushSlot(
    val kind: PushKind,
    val at: Instant,
    val local: LocalDateTime,
)

object PushSchedule {

    /** 严格晚于 [now] 的下一个 hh:mm（今天没到就今天，过了就明天）。 */
    fun nextOccurrence(now: LocalDateTime, hour: Int, minute: Int): LocalDateTime {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val today = LocalDateTime(now.date, LocalTime(h, m))
        if (today > now) return today
        return LocalDateTime(now.date.plusDays(1), LocalTime(h, m))
    }

    /**
     * 计算下一次要登记的推送：在启用的时段里取时间最近的一个。
     * 两个时段都关闭时返回 null（调用方应取消已有闹钟）。
     */
    fun next(
        now: Instant,
        config: PushConfig,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): PushSlot? {
        val safe = config.sanitized()
        val localNow = now.toLocalDateTime(zone)
        val candidates = ArrayList<PushSlot>(2)

        if (safe.morningEnabled) {
            val local = nextOccurrence(localNow, safe.morningHour, safe.morningMinute)
            candidates += PushSlot(PushKind.MORNING, local.toInstant(zone), local)
        }
        if (safe.eveningEnabled) {
            val local = nextOccurrence(localNow, safe.eveningHour, safe.eveningMinute)
            candidates += PushSlot(PushKind.EVENING, local.toInstant(zone), local)
        }

        // 时间相同时早间优先；其余按时间先后。
        return candidates.sortedWith(compareBy<PushSlot> { it.at }.thenBy { it.kind.ordinal }).firstOrNull()
    }
}

/** LocalDate 加天数：kotlinx-datetime 0.4.x 没有直接的 plusDays，用 epoch 日换算。 */
private fun LocalDate.plusDays(days: Int): LocalDate = LocalDate.fromEpochDays(toEpochDays() + days)
