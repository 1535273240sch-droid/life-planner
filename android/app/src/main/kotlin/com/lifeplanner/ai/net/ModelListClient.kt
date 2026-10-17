package com.lifeplanner.ai.net

import com.lifeplanner.ai.data.AiConfigRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 获取模型列表失败时抛出，message 是给用户看的中文说明。 */
class ModelListException(override val message: String) : Exception(message)

/**
 * 调用 OpenAI 兼容端点的 `GET {baseUrl}/models`。
 * 只用于设置页的“获取模型 / 测试连接”，不参与业务链路；失败时抛可读中文错误。
 * 响应体可能包含服务商信息，这里只解析 data[].id，不记录、不上传。
 */
class ModelListClient {

    private val json = Json { ignoreUnknownKeys = true }

    fun fetchModels(baseUrl: String, apiKey: String, allowPublicHttp: Boolean = false): List<String> {
        val normalized = AiConfigRules.normalizeBaseUrl(baseUrl)
        AiConfigRules.baseUrlError(normalized, allowPublicHttp)?.let { throw ModelListException(it) }
        if (apiKey.isBlank()) throw ModelListException("请先填写 API Key")

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        val request = Request.Builder()
            .url("$normalized/models")
            .header("Authorization", "Bearer ${aiKey(apiKey)}")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ModelListException(statusMessage(response.code))
                val root = try {
                    json.parseToJsonElement(body) as? JsonObject
                } catch (e: Exception) {
                    null
                } ?: throw ModelListException("服务返回的不是 JSON，请检查 Base URL 是否正确")

                val array = root["data"] as? JsonArray
                    ?: throw ModelListException("该服务没有返回模型列表（可能不支持 /models），请手动填写模型名称")

                val ids = array.mapNotNull { item ->
                    ((item as? JsonObject)?.get("id") as? JsonPrimitive)?.content
                }.filter { it.isNotBlank() }.distinct().sorted()

                if (ids.isEmpty()) {
                    throw ModelListException("模型列表为空，请手动填写模型名称")
                }
                return ids
            }
        } catch (e: ModelListException) {
            throw e
        } catch (e: IOException) {
            throw ModelListException("网络不通或超时，请检查 Base URL、网络与代理")
        } catch (e: Exception) {
            throw ModelListException("获取模型列表失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun aiKey(raw: String): String = AiConfigRules.normalizeApiKey(raw)

    private fun statusMessage(code: Int): String = when (code) {
        401, 403 -> "API Key 无效或没有权限（HTTP $code）"
        404 -> "接口不存在（HTTP 404），请检查 Base URL 是否包含 /v1 或正确路径"
        429 -> "请求过于频繁（HTTP 429），请稍后再试"
        in 500..599 -> "服务端错误（HTTP $code），可稍后重试"
        else -> "请求失败（HTTP $code）"
    }
}
