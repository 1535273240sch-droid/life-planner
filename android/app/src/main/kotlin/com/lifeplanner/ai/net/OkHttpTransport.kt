package com.lifeplanner.ai.net

import com.lifeplanner.core.port.HttpTransport
import com.lifeplanner.core.port.HttpTransportException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 用 OkHttp 实现 core 的 HttpTransport。
 *
 * 只做一件事：POST 一段 JSON 并返回响应文本。超时与重试策略保持简单：
 * 单次请求超时由调用方（AiService）指定，不自动重试——避免重复计费。
 */
class OkHttpTransport : HttpTransport {

    private val baseClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .build()

    override fun postJson(url: String, bearerToken: String, body: String, timeoutMs: Long): String {
        val client = baseClient.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $bearerToken")
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // 只带状态码，响应体可能含敏感信息，不向上抛
                throw HttpTransportException(response.code, "HTTP ${response.code}")
            }
            return text
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
