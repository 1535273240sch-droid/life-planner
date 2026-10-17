package com.lifeplanner.core.logic

import com.lifeplanner.core.model.DailyLog
import com.lifeplanner.core.model.Goal
import com.lifeplanner.core.model.GoalStatuses
import com.lifeplanner.core.util.Dates
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GapDetectorTest {

    private val today = LocalDate(2026, 6, 15)

    private fun log(
        daysAgo: Int,
        doneItems: String? = null,
        studyMinutes: Int = 0,
        studyNote: String? = null,
        meals: Map<String, String> = emptyMap(),
        sleepStart: String? = null,
        sleepEnd: String? = null,
        reflection: String? = null,
    ): DailyLog = DailyLog(
        date = Dates.minusDays(today, daysAgo),
        doneItems = doneItems,
        studyMinutes = studyMinutes,
        studyNote = studyNote,
        meals = meals,
        sleepStart = sleepStart,
        sleepEnd = sleepEnd,
        reflection = reflection,
    )

    private fun goal(id: Long, title: String): Goal =
        Goal(id = id, title = title, status = GoalStatuses.IN_PROGRESS)

    // ---------------------------------------------------------------- 系统记录

    @Test
    fun `无任何记录时只给系统记录提示`() {
        val gaps = GapDetector.detect(emptyList(), emptyList(), emptyMap())
        assertEquals(listOf("系统记录"), gaps.map { it.category })
    }

    @Test
    fun `仅 1-2 条记录时不触发需要 3 天数据量的营养与学习规则`() {
        // 与 v1 对齐：营养/学习规则要求近 14 天至少 3 条记录；睡眠规则要求近 7 天至少 3 条。
        // 这里两条记录睡眠充足、饮食健康，因此不应产生任何缺口。
        val logs = listOf(
            log(0, studyMinutes = 90, meals = mapOf("lunch" to "青菜鸡胸肉"), sleepStart = "23:00", sleepEnd = "07:00"),
            log(1, studyMinutes = 90, meals = mapOf("lunch" to "青菜鸡胸肉"), sleepStart = "23:00", sleepEnd = "07:00"),
        )
        val gaps = GapDetector.detect(logs, emptyList(), emptyMap())
        assertTrue(gaps.isEmpty())
    }

    @Test
    fun `仅 1 条记录时仍可给出目标停滞提醒`() {
        // v1 在 recent_logs 非空时会执行目标停滞检测，不要求 3 天。
        val logs = listOf(log(0, doneItems = "看书", studyMinutes = 200))
        val gaps = GapDetector.detect(logs, listOf(goal(1, "雅思备考")), emptyMap())
        assertEquals(listOf("目标停滞"), gaps.map { it.category })
    }

    // ---------------------------------------------------------------- 睡眠

    @Test
    fun `连续多天未记录睡眠 触发提醒`() {
        val logs = (0..4).map { log(it, doneItems = "工作", studyMinutes = 60, meals = mapOf("lunch" to "青菜鸡胸肉")) }
        val gaps = GapDetector.detect(logs, emptyList(), emptyMap())

        val sleepGaps = gaps.filter { it.category == "睡眠与体能" }
        assertEquals(1, sleepGaps.size)
        assertTrue(sleepGaps.first().title.contains("连续多天未记录作息睡眠"))
    }

    @Test
    fun `有记录睡眠时不报未记录睡眠`() {
        val logs = (0..4).map { log(it, doneItems = "工作", studyMinutes = 60, sleepStart = "23:00", sleepEnd = "07:00") }
        val gaps = GapDetector.detect(logs, emptyList(), emptyMap())

        assertTrue(gaps.none { it.title.contains("连续多天未记录作息睡眠") })
    }

    @Test
    fun `短期睡眠不足 触发睡眠负债预警`() {
        val logs = (0..4).map { log(it, doneItems = "工作", sleepStart = "02:00", sleepEnd = "07:00") }
        val gaps = GapDetector.detect(logs, emptyList(), emptyMap())

        assertTrue(gaps.any { it.category == "睡眠与体能" && it.title.contains("持续性睡眠负债") })
    }

    // ---------------------------------------------------------------- 营养

    @Test
    fun `蔬菜与蛋白质记录偏少 各触发一次营养缺口`() {
        val logs = (0..2).map { log(it, doneItems = "工作", studyMinutes = 90, meals = mapOf("lunch" to "白米饭")) }
        val gaps = GapDetector.detect(logs, emptyList(), emptyMap())

        val nutrition = gaps.filter { it.category == "饮食与营养" }
        assertEquals(2, nutrition.size)
        assertTrue(nutrition.any { it.title.contains("膳食纤维") })
        assertTrue(nutrition.any { it.title.contains("蛋白质") })
    }

    // ---------------------------------------------------------------- 目标停滞

    @Test
    fun `被忽视的目标 触发目标停滞`() {
        val logs = (0..2).map { log(it, doneItems = "写代码", studyMinutes = 90, sleepStart = "23:30", sleepEnd = "07:30") }
        val goals = listOf(goal(1, "学习古希腊哲学原著精读"))
        val gaps = GapDetector.detect(logs, goals, emptyMap())

        assertTrue(gaps.any { it.category == "目标停滞" && it.title.contains("古希腊哲学") })
    }

    @Test
    fun `日志命中目标核心关键词 不报停滞`() {
        val logs = (0..2).map {
            log(it, doneItems = "今天认真做了雅思听力和阅读练习，背了200个核心词汇", studyMinutes = 90, sleepStart = "23:30", sleepEnd = "07:30")
        }
        val goals = listOf(goal(1, "攻坚「雅思7分」核心里程碑拆解与执行"))
        val gaps = GapDetector.detect(logs, goals, emptyMap())

        assertTrue(gaps.none { it.category == "目标停滞" && it.title.contains("雅思7分") })
    }

    @Test
    fun `关联任务已完成 不报停滞`() {
        val logs = (0..2).map { log(it, doneItems = "写代码", studyMinutes = 90, sleepStart = "23:30", sleepEnd = "07:30") }
        val goals = listOf(goal(1, "学习古希腊哲学原著精读"))
        val gaps = GapDetector.detect(logs, goals, mapOf(1L to 1))

        assertTrue(gaps.none { it.category == "目标停滞" })
    }

    // ---------------------------------------------------------------- 学习时长

    @Test
    fun `累计学习不足120分钟 触发学习时长缺口`() {
        val logs = (0..2).map { log(it, doneItems = "工作", studyMinutes = 30, sleepStart = "23:30", sleepEnd = "07:30", meals = mapOf("lunch" to "青菜鸡胸肉")) }
        val gaps = GapDetector.detect(logs, emptyList(), emptyMap())

        assertTrue(gaps.any { it.category == "成长与学习" && it.title.contains("累计学习时长") })
    }

    // ---------------------------------------------------------------- 顺序与误报

    @Test
    fun `多类缺口同时命中时 按睡眠营养目标学习顺序返回`() {
        val logs = (0..2).map { log(it, doneItems = "写代码", studyMinutes = 0) }
        val goals = listOf(goal(1, "学习古希腊哲学原著精读"))
        val gaps = GapDetector.detect(logs, goals, emptyMap())

        assertEquals(
            listOf("睡眠与体能", "饮食与营养", "饮食与营养", "目标停滞", "成长与学习"),
            gaps.map { it.category },
        )
    }

    @Test
    fun `数据正常时不产生任何误报`() {
        val logs = (0..4).map {
            log(
                it,
                doneItems = "阅读古希腊哲学原著，写代码",
                studyMinutes = 90,
                meals = mapOf("lunch" to "青菜鸡胸肉"),
                sleepStart = "23:30",
                sleepEnd = "07:30",
            )
        }
        val goals = listOf(goal(1, "学习古希腊哲学原著精读"))
        val gaps = GapDetector.detect(logs, goals, emptyMap())

        assertEquals(emptyList<String>(), gaps.map { it.category })
    }
}
