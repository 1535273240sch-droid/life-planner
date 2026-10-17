package com.lifeplanner.ai.data

import android.content.Context

/**
 * 更新检查的本地状态（与 AI 配置分开存，避免混在同一个设置对象里）。
 *
 * 规则：
 * - 启动时最多每 24 小时检查一次，避免每次冷启动都打 GitHub API；
 * - 同一个版本只自动提示一次，用户忽略后不再反复弹（可在设置里手动检查）。
 */
class UpdatePrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun lastCheckedAt(): Long = prefs.getLong(KEY_LAST_CHECK, 0L)

    fun markChecked(now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
    }

    fun lastPromptedVersion(): String? = prefs.getString(KEY_LAST_PROMPTED, null)

    fun markPrompted(version: String) {
        prefs.edit().putString(KEY_LAST_PROMPTED, version).apply()
    }

    /** 是否到了可以自动检查的时间（24 小时一次）。 */
    fun shouldAutoCheck(now: Long = System.currentTimeMillis()): Boolean =
        now - lastCheckedAt() >= CHECK_INTERVAL_MS

    companion object {
        private const val PREFS_NAME = "life_planner_update"
        private const val KEY_LAST_CHECK = "last_check_at"
        private const val KEY_LAST_PROMPTED = "last_prompted_version"
        private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    }
}
