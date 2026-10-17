package com.lifeplanner.core.util

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** 日期工具：全项目统一走这里，避免各处自行解析字符串。 */
object Dates {

    /** 设备本地时区的今天 */
    fun today(): LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    fun parse(iso: String?): LocalDate? {
        if (iso.isNullOrBlank()) return null
        return try {
            LocalDate.parse(iso.trim())
        } catch (e: Exception) {
            null
        }
    }

    fun format(date: LocalDate): String = date.toString()

    fun plusDays(date: LocalDate, days: Int): LocalDate = date.plus(days, DateTimeUnit.DAY)

    fun minusDays(date: LocalDate, days: Int): LocalDate = date.minus(days, DateTimeUnit.DAY)

    /** 含首尾的天数：from..to 共几天。使 to 早于 from 时返回 0 */
    fun daysInclusive(from: LocalDate, to: LocalDate): Int {
        if (to < from) return 0
        var count = 0
        var cur = from
        while (cur <= to) {
            count++
            cur = plusDays(cur, 1)
        }
        return count
    }

    /** 最近 n 天（含今天）的日期序列，升序。n<=0 时返回空 */
    fun lastDays(n: Int, today: LocalDate = today()): List<LocalDate> {
        if (n <= 0) return emptyList()
        val start = minusDays(today, n - 1)
        val out = ArrayList<LocalDate>(n)
        var cur = start
        while (cur <= today) {
            out.add(cur)
            cur = plusDays(cur, 1)
        }
        return out
    }

    /** ISO 时间戳（记录创建时间等），本地时区 */
    fun nowIso(): String = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()).toString()

    /** MM-DD，图表横轴用 */
    fun monthDay(date: LocalDate): String {
        val m = date.monthNumber.toString().padStart(2, '0')
        val d = date.dayOfMonth.toString().padStart(2, '0')
        return "$m-$d"
    }

    /** 该日期当天 00:00 的 Instant（需要时间戳时使用） */
    fun startOfDayInstant(date: LocalDate): Instant =
        date.atStartOfDayIn(TimeZone.currentSystemDefault())
}
