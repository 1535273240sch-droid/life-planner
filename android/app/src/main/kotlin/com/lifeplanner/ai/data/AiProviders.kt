package com.lifeplanner.ai.data

import java.net.URI

/**
 * 常用 OpenAI 兼容服务商预设。baseUrl 已包含各家要求的版本路径。
 * 选择预设只会填入表单，不会自动联网；模型列表可在设置页点「获取模型」拉取。
 */
data class AiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val note: String? = null,
)

object AiProviders {
    val all = listOf(
        AiProvider("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        AiProvider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
        AiProvider("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3"),
        AiProvider("moonshot", "Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        AiProvider("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
        AiProvider("dashscope", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        AiProvider(
            id = "ollama",
            name = "本地 Ollama",
            baseUrl = "http://192.168.1.100:11434/v1",
            defaultModel = "qwen2.5:7b",
            note = "示例地址，请改成运行 Ollama 的电脑局域网 IP（手机上的 127.0.0.1 指手机自己）。",
        ),
        AiProvider("custom", "自定义", "", ""),
    )
}

/** AI 配置的规范化与校验（纯函数，可单测）。 */
object AiConfigRules {

    private val ENDPOINT_SUFFIXES = listOf("/chat/completions", "/completions", "/models")

    /**
     * 把用户输入整理成可直接拼接 `/chat/completions` 的 Base URL：
     * - 去掉首尾空白、引号、换行；
     * - 缺协议时自动补 https://（用户常直接粘贴 api.xxx.com/v1）；
     * - 去掉尾斜杠、查询串和片段；
     * - 如果误粘贴了完整的 `/chat/completions` 端点，自动截回 Base URL。
     */
    fun normalizeBaseUrl(raw: String): String {
        var value = raw.trim().trim('"').trim()
        if (value.isEmpty()) return ""
        value = value.substringBefore('?').substringBefore('#')
        if (!value.contains("://")) value = "https://$value"
        value = value.trimEnd('/')
        for (suffix in ENDPOINT_SUFFIXES) {
            if (value.endsWith(suffix, ignoreCase = true)) {
                value = value.dropLast(suffix.length).trimEnd('/')
                break
            }
        }
        return value
    }

    fun normalizeApiKey(raw: String): String = raw.trim()

    /** 返回 null 表示可用；否则返回给用户看的中文说明。 */
    fun baseUrlError(raw: String): String? {
        val normalized = normalizeBaseUrl(raw)
        if (normalized.isEmpty()) {
            return "请填写 Base URL，例如 https://api.deepseek.com/v1"
        }
        val uri = try {
            URI(normalized)
        } catch (e: Exception) {
            return "Base URL 格式不正确，请检查是否有空格或多余字符"
        }
        val scheme = uri.scheme?.lowercase()
            ?: return "Base URL 格式不正确，例如 https://api.deepseek.com/v1"
        val host = uri.host
            ?: return "Base URL 缺少主机名，例如 https://api.deepseek.com/v1"
        if (host.isBlank()) return "Base URL 缺少主机名"
        return when (scheme) {
            "https" -> null
            "http" ->
                if (isLocalOrPrivateHost(host)) {
                    null
                } else {
                    "http 仅支持本机/局域网地址（localhost、127.0.0.1、10.x.x.x、" +
                        "172.16-31.x.x、192.168.x.x、::1）；公网请使用 https"
                }
            else -> "Base URL 仅支持 http:// 或 https://"
        }
    }

    /** 只接受明确列出的本机/私有地址，不做域名后缀或字符串包含判断。 */
    fun isLocalOrPrivateHost(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (h == "localhost" || h == "127.0.0.1" || h == "::1") return true
        val parts = h.split(".")
        if (parts.size != 4) return false
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val value = parts[i].toIntOrNull() ?: return false
            if (value !in 0..255) return false
            octets[i] = value
        }
        return when {
            octets[0] == 10 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            else -> false
        }
    }
}
