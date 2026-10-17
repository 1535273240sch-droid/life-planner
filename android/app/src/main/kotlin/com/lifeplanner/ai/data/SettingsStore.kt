package com.lifeplanner.ai.data

import android.content.Context
import com.lifeplanner.core.ai.AiConfig

/**
 * AI 配置的本地存储。
 *
 * 说明：这是单用户、随身设备上的本地应用，配置只存在本机 SharedPreferences（应用私有目录）。
 * 不做加密：设备已解锁的前提下，任何能读取应用私有目录的进程都能看到，属于已知取舍。
 * 界面上必须让用户明白这里填的是自己的付费 API Key。
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadAiConfig(): AiConfig {
        val defaults = AiConfig()
        return AiConfig(
            apiKey = prefs.getString(KEY_API_KEY, null) ?: "",
            baseUrl = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: defaults.baseUrl,
            model = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: defaults.model,
        )
    }

    fun saveAiConfig(config: AiConfig) {
        prefs.edit()
            .putString(KEY_API_KEY, config.apiKey.trim())
            .putString(KEY_BASE_URL, normalizeBaseUrl(config.baseUrl))
            .putString(KEY_MODEL, config.model.trim())
            .apply()
    }

    /** http(s):// 开头、去掉末尾斜杠；非法则回落到默认地址 */
    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return AiConfig().baseUrl
        }
        return trimmed
    }

    companion object {
        private const val PREFS_NAME = "life_planner_settings"
        private const val KEY_API_KEY = "ai_api_key"
        private const val KEY_BASE_URL = "ai_base_url"
        private const val KEY_MODEL = "ai_model"
    }
}
