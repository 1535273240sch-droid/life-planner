package com.lifeplanner.core.daily

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 推送排期的纯逻辑测试。
 *
 * 用 UTC 固定时区，避免测试机时区/夏令时影响断言；
 * 时间点全部写死，不依赖“现在”，所以任何机器、任何时间跑结果都一样。
 */
class PushScheduleTest {

    private val zone = TimeZone.UTC

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Instant =
        LocalDateTime(year, month, day, hour, minute).toInstant(zone)

    private fun local(year: Int, month: Int, day: Int, hour: Int, minute: Int): LocalDateTime =
        LocalDateTime(year, month, day, hour, minute)

    // ------------------------------------------------------------ nextOccurrence

    @Test
    fun `今天还没到点 就排今天`() {
        val now = local(2026, 10, 17, 6, 0)
        assertEquals(local(2026, 10, 17, 7, 30), PushSchedule.nextOccurrence(now, 7, 30))
    }

    @Test
    fun `今天已经过了 排到明天`() {
        val now = local(2026, 10, 17, 8, 0)
        assertEquals(local(2026, 10, 18, 7, 30), PushSchedule.nextOccurrence(now, 7, 30))
    }

    @Test
    fun `刚好等于当前时刻 也要排到明天`() {
        // 到点那一秒闹钟会触发，触发后再算下一次必须是明天，
        // 否则同一个时段会被重复排期，形成连续弹窗。
        val now = local(2026, 10, 17, 7, 30)
        assertEquals(local(2026, 10, 18, 7, 30), PushSchedule.nextOccurrence(now, 7, 30))
    }

    @Test
    fun `nextOccurrence 会把越界时分收敛`() {
        val now = local(2026, 10, 17, 0, 0)
        assertEquals(local(2026, 10, 17, 23, 59), PushSchedule.nextOccurrence(now, 25, 90))
    }

    @Test
    fun `跨年时排到下一年`() {
        val now = local(2026, 12, 31, 23, 0)
        assertEquals(local(2027, 1, 1, 7, 30), PushSchedule.nextOccurrence(now, 7, 30))
    }

    // ------------------------------------------------------------------- next

    @Test
    fun `两段都关闭 返回 null`() {
        val config = PushConfig(morningEnabled = false, eveningEnabled = false)
        assertNull(PushSchedule.next(at(2026, 10, 17, 8, 0), config, zone))
    }

    @Test
    fun `只开早间 就取早间`() {
        val config = PushConfig(morningEnabled = true, eveningEnabled = false)
        val slot = PushSchedule.next(at(2026, 10, 17, 8, 0), config, zone)!!
        assertEquals(PushKind.MORNING, slot.kind)
        assertEquals(at(2026, 10, 18, 7, 30), slot.at)
        assertEquals(local(2026, 10, 18, 7, 30), slot.local)
    }

    @Test
    fun `只开晚间 就取晚间`() {
        val config = PushConfig(morningEnabled = false, eveningEnabled = true)
        val slot = PushSchedule.next(at(2026, 10, 17, 8, 0), config, zone)!!
        assertEquals(PushKind.EVENING, slot.kind)
        assertEquals(at(2026, 10, 17, 21, 30), slot.at)
    }

    @Test
    fun `两段都开 取最近的一次`() {
        // 早上 6 点：今天 07:30 比今天 21:30 更近
        val config = PushConfig()
        val beforeMorning = PushSchedule.next(at(2026, 10, 17, 6, 0), config, zone)!!
        assertEquals(PushKind.MORNING, beforeMorning.kind)
        assertEquals(at(2026, 10, 17, 7, 30), beforeMorning.at)

        // 上午 8 点：早间已过，取今天 21:30
        val afterMorning = PushSchedule.next(at(2026, 10, 17, 8, 0), config, zone)!!
        assertEquals(PushKind.EVENING, afterMorning.kind)
        assertEquals(at(2026, 10, 17, 21, 30), afterMorning.at)

        // 晚上 23 点：两段都已过，取明天 07:30（早于明天 21:30）
        val afterEvening = PushSchedule.next(at(2026, 10, 17, 23, 0), config, zone)!!
        assertEquals(PushKind.MORNING, afterEvening.kind)
        assertEquals(at(2026, 10, 18, 7, 30), afterEvening.at)
    }

    @Test
    fun `自定义时间 按用户设置排`() {
        val config = PushConfig(
            morningEnabled = true,
            morningHour = 6,
            morningMinute = 45,
            eveningEnabled = true,
            eveningHour = 22,
            eveningMinute = 10,
        )
        val slot = PushSchedule.next(at(2026, 10, 17, 9, 0), config, zone)!!
        assertEquals(PushKind.EVENING, slot.kind)
        assertEquals(at(2026, 10, 17, 22, 10), slot.at)
    }

    @Test
    fun `越界配置不会抛异常 而是收敛后排期`() {
        val config = PushConfig(
            morningEnabled = true,
            morningHour = 99,
            morningMinute = -5,
            eveningEnabled = false,
        )
        // 99:-5 → 23:00（小时收敛为 23，分钟收敛为 0）
        val slot = PushSchedule.next(at(2026, 10, 17, 10, 0), config, zone)!!
        assertEquals(PushKind.MORNING, slot.kind)
        assertEquals(at(2026, 10, 17, 23, 0), slot.at)
    }

    // --------------------------------------------------------------- sanitized

    @Test
    fun `sanitized 会把时分压回合法范围`() {
        val safe = PushConfig(
            morningHour = 30,
            morningMinute = 99,
            eveningHour = -1,
            eveningMinute = -30,
        ).sanitized()
        assertEquals(23, safe.morningHour)
        assertEquals(59, safe.morningMinute)
        assertEquals(0, safe.eveningHour)
        assertEquals(0, safe.eveningMinute)
    }
}
