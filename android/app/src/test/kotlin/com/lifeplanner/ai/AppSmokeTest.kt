package com.lifeplanner.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lifeplanner.core.ai.AiConfig
import com.lifeplanner.core.ai.AiErrorCodes
import com.lifeplanner.core.ai.AiException
import com.lifeplanner.core.json.ImportMode
import com.lifeplanner.core.model.DailyLog
import com.lifeplanner.core.model.Goal
import com.lifeplanner.core.model.GoalLevels
import com.lifeplanner.core.model.GoalStatuses
import com.lifeplanner.core.model.Task
import com.lifeplanner.core.util.Dates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android 运行时冒烟测试（Robolectric）：
 * 验证 AppContainer 能真正建库、建表，仓储与 Service 在 Android SQLite 驱动下可用，
 * 以及未配置 Key 时 AI 明确报错、危机输入不触网。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppSmokeTest {

    private fun newContainer(): AppContainer {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return AppContainer(context)
    }

    @Test
    fun `首次启动可建库并完成目标记录任务与备份往返`() {
        val c = newContainer()

        // 空库
        assertTrue(c.goalService.list().isEmpty())
        assertEquals(0, c.logService.overview(Dates.today()).totalGoals)

        // 目标树 + 进度写入
        val visionId = c.goalService.createGoal(
            Goal(level = GoalLevels.VISION, title = "拥有健康的身体"),
        )
        val longId = c.goalService.createGoal(
            Goal(level = GoalLevels.LONG, parentId = visionId, title = "完成半程马拉松"),
        )
        val tree = c.goalService.tree()
        assertEquals(1, tree.size)
        assertEquals(1, tree.first().children.size)

        c.goalService.confirmProgress(listOf(longId to 100))
        val completed = c.goalService.list().first { it.id == longId }
        assertEquals(100, completed.progress)
        assertEquals(GoalStatuses.COMPLETED, completed.status)

        // 任务打卡
        val taskId = c.taskRepository.insert(
            Task(goalId = longId, date = Dates.today(), title = "跑 5 公里", estMinutes = 30),
        )
        c.taskRepository.setDone(taskId, true, Dates.nowIso())
        assertEquals(
            1,
            c.taskRepository.countDoneSince(longId, Dates.minusDays(Dates.today(), 7)),
        )

        // 每日记录 upsert + 统计
        c.logService.upsertTodayOrDate(
            DailyLog(
                date = Dates.today(),
                studyMinutes = 45,
                sleepStart = "23:00",
                sleepEnd = "07:00",
                mood = 4,
                energy = 4,
            ),
        )
        val overview = c.logService.overview(Dates.today())
        assertEquals(1, overview.streak)
        assertEquals(2, overview.totalGoals)
        assertEquals(1, overview.completedGoals)

        // 备份导出 → 覆盖导入 → 数据仍在
        val json = c.backupService.exportJson()
        assertTrue(json.contains("拥有健康的身体"))
        val summary = c.backupService.importJson(json, ImportMode.REPLACE)
        assertEquals(2, summary.goals)
        assertEquals(1, summary.tasks)
        assertEquals(1, summary.dailyLogs)

        val reloaded = c.goalService.list()
        assertEquals(2, reloaded.size)
        val newLong = reloaded.first { it.level == GoalLevels.LONG }
        assertEquals(GoalStatuses.COMPLETED, newLong.status)
        assertEquals(1, c.dailyLogRepository.listAll().size)
    }

    @Test
    fun `未配置 Key 时 AI 明确报错而不是伪造结果`() {
        val c = newContainer()
        c.settings.saveAiConfig(AiConfig(apiKey = "", baseUrl = "https://example.com/v1", model = "test-model"))
        assertFalse(c.settings.loadAiConfig().isConfigured)

        try {
            c.aiService().askAdvisor("我该怎么安排今天的学习？")
            fail("未配置 Key 时必须抛错")
        } catch (e: AiException) {
            assertEquals(AiErrorCodes.NOT_CONFIGURED, e.code)
            assertTrue(e.message.isNotBlank())
        }
    }

    @Test
    fun `危机输入在未配置 Key 时也短路返回热线`() {
        val c = newContainer()
        val result = c.aiService().askAdvisor("我真的不想活了")
        assertTrue(result.isCrisis)
        assertTrue(result.fullText.contains("400-161-9995"))
        assertTrue(result.fullText.contains("010-82951332"))
        assertTrue(result.fullText.contains("12355"))
    }
}
