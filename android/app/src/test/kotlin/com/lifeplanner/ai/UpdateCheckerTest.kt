package com.lifeplanner.ai

import com.lifeplanner.ai.net.UpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本比较是更新提示的核心逻辑：比错会导致永远不提示（或每次都提示）。
 * 只测纯函数，不发网络。
 */
class UpdateCheckerTest {

    @Test
    fun `新版本大于旧版本`() {
        assertTrue(UpdateChecker.isNewer("2.3.0", "2.2.0"))
        assertTrue(UpdateChecker.isNewer("3.0.0", "2.99.99"))
        assertTrue(UpdateChecker.isNewer("2.3.1", "2.3.0"))
    }

    @Test
    fun `数字按段比较而不是字符串比较`() {
        // 字符串比较会认为 "2.10.0" < "2.9.0"，这正是要防的 bug
        assertTrue(UpdateChecker.isNewer("2.10.0", "2.9.9"))
        assertTrue(UpdateChecker.isNewer("10.0.0", "9.9.9"))
    }

    @Test
    fun `相同版本与更低版本不提示`() {
        assertFalse(UpdateChecker.isNewer("2.3.0", "2.3.0"))
        assertFalse(UpdateChecker.isNewer("2.2.0", "2.3.0"))
        assertFalse(UpdateChecker.isNewer("v2.2.0", "v2.3.0"))
    }

    @Test
    fun `忽略前缀与预发布后缀`() {
        assertTrue(UpdateChecker.isNewer("v2.3.0", "2.2.9"))
        assertFalse(UpdateChecker.isNewer("2.3.0-beta", "2.3.0"))
        assertFalse(UpdateChecker.isNewer("2.3", "2.3.0"))
    }
}
