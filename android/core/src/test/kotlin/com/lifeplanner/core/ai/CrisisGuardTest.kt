package com.lifeplanner.core.ai

import com.lifeplanner.core.model.DailyLog
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrisisGuardTest {

    @Test
    fun 常见中文危机关键词命中() {
        assertNotNull(CrisisGuard.check("我最近不想活了"))
        assertNotNull(CrisisGuard.check("想自杀"))
        assertNotNull(CrisisGuard.check("一了百了"))
        assertNotNull(CrisisGuard.check("生无可恋"))
        assertNotNull(CrisisGuard.check("想不开"))
        assertNotNull(CrisisGuard.check("以死明志"))
        assertNotNull(CrisisGuard.check("感觉活够了"))
    }

    @Test
    fun 英文关键词带空格分大小写也能命中() {
        assertNotNull(CrisisGuard.check("I want to die"))
        assertNotNull(CrisisGuard.check("kill myself"))
        assertNotNull(CrisisGuard.check("SUICIDE"))
    }

    @Test
    fun 空值与空白返回空() {
        assertNull(CrisisGuard.check(null))
        assertNull(CrisisGuard.check(""))
        assertNull(CrisisGuard.check("   \n\t "))
    }

    @Test
    fun 普通文本不误报() {
        assertNull(CrisisGuard.check("今天要完成英语单词背诵，晚上早睡"))
        assertNull(CrisisGuard.check("项目进度有点慢，需要加把劲"))
    }

    @Test
    fun 关怀文案包含三条热线与紧急提示() {
        val p = CrisisGuard.buildPayload()
        assertTrue(p.alternative.contains("400-161-9995"))
        assertTrue(p.alternative.contains("010-82951332"))
        assertTrue(p.alternative.contains("12355"))
        assertTrue(p.alternative.contains("110"))
        assertTrue(p.alternative.contains("120"))
    }

    @Test
    fun 扫描日志依次检查反思产出学习笔记() {
        val d = LocalDate(2026, 10, 16)
        assertNotNull(CrisisGuard.scanLogs(listOf(DailyLog(date = d, reflection = "活着没意义"))))
        assertNotNull(CrisisGuard.scanLogs(listOf(DailyLog(date = d, doneItems = "感觉活够了"))))
        assertNotNull(CrisisGuard.scanLogs(listOf(DailyLog(date = d, studyNote = "好绝望不想活"))))
        assertNull(CrisisGuard.scanLogs(listOf(DailyLog(date = d, reflection = "还不错", doneItems = "看完了书"))))
        assertNull(CrisisGuard.scanLogs(emptyList()))
    }
}
