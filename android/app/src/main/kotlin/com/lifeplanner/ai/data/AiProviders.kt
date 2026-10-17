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
    fun baseUrlError(raw: String, allowPublicHttp: Boolean = false): String? {
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
            "http" -> when {
                allowPublicHttp -> null
                isLocalOrPrivateHost(host) -> null
                else ->
                    "该地址是公网 HTTP。为保护 API Key 不被明文传输，默认拒绝；" +
                        "若你确认网络可信，请勾选下方「允许公网 HTTP（不推荐）」后再保存"
            }
            else -> "Base URL 仅支持 http:// 或 https://"
        }
    }

    /** 是否为公网 http（用于界面提示与“允许不安全连接”开关）。 */
    fun isPublicHttp(raw: String): Boolean {
        val normalized = normalizeBaseUrl(raw)
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return false
        if (!uri.scheme.equals("http", ignoreCase = true)) return false
        val host = uri.host ?: return false
        return !isLocalOrPrivateHost(host)
    }

    /**
     * 本机 / 局域网 / 可信私有地址：
     * - 主机名：localhost、*.local（mDNS）
     * - IPv4：10/8、127/8、172.16/12、192.168/16、169.254/16（链路本地）、
     *   100.64/10（CGNAT，Tailscale 等组网工具常用）
     * - IPv6：::1、fc00::/7（ULA）、fe80::/10（链路本地）
     */
    fun isLocalOrPrivateHost(host: String): Boolean {
        val h = host.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .substringBefore('%')   // 去掉 IPv6 zone id
            .lowercase()
            .trimEnd('.')

        if (h == "localhost" || h.endsWith(".local")) return true
        if (h.contains(":")) return isPrivateIpv6(h)

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
            octets[0] == 127 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            octets[0] == 169 && octets[1] == 254 -> true
            octets[0] == 100 && octets[1] in 64..127 -> true
            else -> false
        }
    }

    private fun isPrivateIpv6(h: String): Boolean {
        if (h == "::1") return true
        // fc00::/7：唯一本地地址（ULA），fd00::/8 也在此范围
        if (h.startsWith("fc") || h.startsWith("fd")) return true
        // fe80::/10：链路本地
        if (h.startsWith("fe8") || h.startsWith("fe9") ||
            h.startsWith("fea") || h.startsWith("feb")
        ) {
            return true
        }
        return false
    }
}
