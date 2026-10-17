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
            apiKey = AiConfigRules.normalizeApiKey(prefs.getString(KEY_API_KEY, null) ?: ""),
            baseUrl = AiConfigRules.normalizeBaseUrl(prefs.getString(KEY_BASE_URL, null).orEmpty())
                .ifBlank { defaults.baseUrl },
            model = prefs.getString(KEY_MODEL, null)?.trim()?.takeIf { it.isNotBlank() } ?: defaults.model,
        )
    }

    fun saveAiConfig(config: AiConfig) {
        val baseUrl = AiConfigRules.normalizeBaseUrl(config.baseUrl)
            .ifBlank { AiConfig().baseUrl }
        prefs.edit()
            .putString(KEY_API_KEY, AiConfigRules.normalizeApiKey(config.apiKey))
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_MODEL, config.model.trim())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "life_planner_settings"
        private const val KEY_API_KEY = "ai_api_key"
        private const val KEY_BASE_URL = "ai_base_url"
        private const val KEY_MODEL = "ai_model"
    }
}
