package com.lifeplanner.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 从模型文本里稳健地取出一个 JSON 对象。
 *
 * 依次尝试：整段直接解析 → 各 ``` 围栏块（带不带 json 标记）→ 从每个 '{' 起
 * 做“括号配对 + 解析”扫描（容忍围栏未闭合、JSON 前后夹杂说明文字）。
 * 顶层不是对象（数组、字符串等）一律视为失败，抛 AiException(BAD_RESPONSE)。
 *
 * 返回的 Map 值已按 JSON 语义转换：对象→Map、数组→List、字符串→String、
 * 整数→Long、小数→Double、真假→Boolean、null→null（便于各模式手工取字段校验）。
 */
object JsonExtract {

    private const val ERR_OBJECT = "AI 返回的内容不是有效的 JSON 对象，未能生成结果，请重试。"
    private const val ERR_FIELD = "AI 返回的内容缺少必需字段，未能生成结果，请重试。"

    /** 与 v1 一致的围栏提取：``` 或 ```json，多个围栏取第一个能解析的。 */
    private val FENCE = Regex(
        "```[ \\t]*(?:json|JSON)?[ \\t]*\\r?\\n?(.*?)```",
        RegexOption.DOT_MATCHES_ALL,
    )

    fun extractObject(text: String?): Map<String, Any?> {
        if (text.isNullOrBlank()) throw badResponse()
        val s = text.trim().removePrefix("\uFEFF")

        parseObject(s)?.let { return it }

        for (block in FENCE.findAll(s)) {
            parseObject(block.groupValues[1])?.let { return it }
        }

        var idx = s.indexOf('{')
        while (idx != -1) {
            val end = matchedBraceEnd(s, idx)
            if (end != -1) {
                parseObject(s.substring(idx, end + 1))?.let { return it }
            }
            idx = s.indexOf('{', idx + 1)
        }
        throw badResponse()
    }

    /** 必需的非空字符串字段；缺失/非字符串/纯空白一律抛 AiException(BAD_RESPONSE)。 */
    fun requireStringField(data: Map<String, Any?>, field: String): String {
        val v = data[field]
        if (v !is String || v.isBlank()) throw AiException(AiErrorCodes.BAD_RESPONSE, ERR_FIELD)
        return v.trim()
    }

    /**
     * 模拟 v1 _as_int：整数或纯数字字符串 → Long；
     * Boolean、小数、含符号或其他类型 → null（与 Python isinstance 判断口径一致）。
     */
    fun asLongOrNull(v: Any?): Long? = when {
        v == null || v is Boolean -> null
        v is Long -> v
        v is Int -> v.toLong()
        v is String -> if (v.isNotEmpty() && v.all { it.isDigit() }) v.toLongOrNull() else null
        else -> null
    }

    /** 安全取 Map（用于遍历 tasks / progress_suggestions 里的元素）。 */
    @Suppress("UNCHECKED_CAST")
    fun asMap(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    // ------------------------------------------------------------------

    private fun badResponse(): AiException = AiException(AiErrorCodes.BAD_RESPONSE, ERR_OBJECT)

    private fun parseObject(candidate: String): Map<String, Any?>? {
        val c = candidate.trim()
        if (c.isEmpty()) return null
        val el: JsonElement = try {
            Json.parseToJsonElement(c)
        } catch (e: Exception) {
            return null
        }
        val obj = el as? JsonObject ?: return null
        return obj.mapValues { toKotlin(it.value) }
    }

    /** 从 start（' { '）起做括号配对，返回匹配的 '}' 下标；不匹配返回 -1。字符串/转义被正确跳过。 */
    private fun matchedBraceEnd(s: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escape = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }

    private fun toKotlin(el: JsonElement): Any? = when (el) {
        is JsonNull -> null
        is JsonObject -> el.mapValues { toKotlin(it.value) }
        is JsonArray -> el.map { toKotlin(it) }
        is JsonPrimitive -> primitiveValue(el)
    }

    private fun primitiveValue(p: JsonPrimitive): Any? {
        if (p.isString) return p.content
        val c = p.content
        return when (c) {
            "true" -> true
            "false" -> false
            "null" -> null
            else -> if (c.contains('.') || c.contains('e') || c.contains('E')) {
                c.toDoubleOrNull() ?: c
            } else {
                c.toLongOrNull() ?: c
            }
        }
    }
}
