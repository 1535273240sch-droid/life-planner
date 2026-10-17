package com.lifeplanner.ai

import com.lifeplanner.ai.data.AiConfigRules
import com.lifeplanner.ai.data.AiProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiConfigRulesTest {

    @Test
    fun `缺少协议时自动补 https`() {
        assertEquals(
            "https://api.deepseek.com/v1",
            AiConfigRules.normalizeBaseUrl("api.deepseek.com/v1"),
        )
    }

    @Test
    fun `误粘贴完整端点时截回 Base URL`() {
        assertEquals(
            "https://api.deepseek.com/v1",
            AiConfigRules.normalizeBaseUrl(" https://api.deepseek.com/v1/chat/completions "),
        )
        assertEquals(
            "https://api.openai.com/v1",
            AiConfigRules.normalizeBaseUrl("https://api.openai.com/v1/models"),
        )
    }

    @Test
    fun `去掉尾斜杠引号查询串`() {
        assertEquals(
            "https://api.moonshot.cn/v1",
            AiConfigRules.normalizeBaseUrl("\"https://api.moonshot.cn/v1/?x=1\""),
        )
    }

    @Test
    fun `https 公网地址通过`() {
        assertNull(AiConfigRules.baseUrlError("https://api.openai.com/v1"))
    }

    @Test
    fun `http 公网地址被拒绝`() {
        assertNotNull(AiConfigRules.baseUrlError("http://api.openai.com/v1"))
    }

    @Test
    fun `http 局域网地址通过`() {
        assertNull(AiConfigRules.baseUrlError("http://192.168.1.5:11434/v1"))
        assertNull(AiConfigRules.baseUrlError("http://127.0.0.1:8000/v1"))
        assertNull(AiConfigRules.baseUrlError("http://[::1]:8000/v1"))
    }

    @Test
    fun `Tailscale 与 IPv6 局域网地址通过`() {
        // Tailscale / CGNAT
        assertNull(AiConfigRules.baseUrlError("http://100.100.1.2:11434/v1"))
        // IPv6 链路本地 / ULA
        assertNull(AiConfigRules.baseUrlError("http://[fe80::1]:8000/v1"))
        assertNull(AiConfigRules.baseUrlError("http://[fd00::1]:8000/v1"))
        // mDNS
        assertNull(AiConfigRules.baseUrlError("http://my-pc.local:8000/v1"))
        // 链路本地 IPv4
        assertNull(AiConfigRules.baseUrlError("http://169.254.1.2:8000/v1"))
    }

    @Test
    fun `公网 http 默认拒绝但允许用户显式放行`() {
        assertNotNull(AiConfigRules.baseUrlError("http://api.example.com/v1"))
        assertNull(AiConfigRules.baseUrlError("http://api.example.com/v1", allowPublicHttp = true))

        assertTrue(AiConfigRules.isPublicHttp("http://api.example.com/v1"))
        assertFalse(AiConfigRules.isPublicHttp("http://192.168.1.5/v1"))
        assertFalse(AiConfigRules.isPublicHttp("https://api.example.com/v1"))
    }

    @Test
    fun `空地址与非法地址报错`() {
        assertNotNull(AiConfigRules.baseUrlError(""))
        assertNotNull(AiConfigRules.baseUrlError("   "))
        assertNotNull(AiConfigRules.baseUrlError("ftp://example.com/v1"))
    }

    @Test
    fun `预设服务商的地址和默认模型都不为空`() {
        AiProviders.all.filter { it.id != "custom" }.forEach { provider ->
            assertTrue(provider.name.isNotBlank())
            assertTrue(provider.baseUrl.startsWith("http"))
            assertTrue(provider.defaultModel.isNotBlank())
        }
    }
}
