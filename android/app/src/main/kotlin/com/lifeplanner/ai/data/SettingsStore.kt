package com.lifeplanner.ai.data

import android.content.Context
import com.lifeplanner.core.ai.AiConfig
import com.lifeplanner.core.daily.PushConfig

/**
 * 本地配置存储（AI 配置 + 每日推送配置）。
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

    /**
     * 读取每日推送配置，缺省值来自 [PushConfig] 的默认构造（早 07:30 / 晚 21:30 都开）。
     * 读出后统一 [PushConfig.sanitized]，避免历史脏数据（越界时分）导致排不出闹钟。
     */
    fun loadPushConfig(): PushConfig {
        val defaults = PushConfig()
        return PushConfig(
            morningEnabled = prefs.getBoolean(KEY_PUSH_MORNING_ENABLED, defaults.morningEnabled),
            morningHour = prefs.getInt(KEY_PUSH_MORNING_HOUR, defaults.morningHour),
            morningMinute = prefs.getInt(KEY_PUSH_MORNING_MINUTE, defaults.morningMinute),
            eveningEnabled = prefs.getBoolean(KEY_PUSH_EVENING_ENABLED, defaults.eveningEnabled),
            eveningHour = prefs.getInt(KEY_PUSH_EVENING_HOUR, defaults.eveningHour),
            eveningMinute = prefs.getInt(KEY_PUSH_EVENING_MINUTE, defaults.eveningMinute),
        ).sanitized()
    }

    /** 保存推送配置。只写偏好，重排闹钟由调用方（ViewModel / 启动流程）负责，避免存储层依赖调度器。 */
    fun savePushConfig(config: PushConfig) {
        val safe = config.sanitized()
        prefs.edit()
            .putBoolean(KEY_PUSH_MORNING_ENABLED, safe.morningEnabled)
            .putInt(KEY_PUSH_MORNING_HOUR, safe.morningHour)
            .putInt(KEY_PUSH_MORNING_MINUTE, safe.morningMinute)
            .putBoolean(KEY_PUSH_EVENING_ENABLED, safe.eveningEnabled)
            .putInt(KEY_PUSH_EVENING_HOUR, safe.eveningHour)
            .putInt(KEY_PUSH_EVENING_MINUTE, safe.eveningMinute)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "life_planner_settings"
        private const val KEY_API_KEY = "ai_api_key"
        private const val KEY_BASE_URL = "ai_base_url"
        private const val KEY_MODEL = "ai_model"

        private const val KEY_PUSH_MORNING_ENABLED = "push_morning_enabled"
        private const val KEY_PUSH_MORNING_HOUR = "push_morning_hour"
        private const val KEY_PUSH_MORNING_MINUTE = "push_morning_minute"
        private const val KEY_PUSH_EVENING_ENABLED = "push_evening_enabled"
        private const val KEY_PUSH_EVENING_HOUR = "push_evening_hour"
        private const val KEY_PUSH_EVENING_MINUTE = "push_evening_minute"
    }
}
