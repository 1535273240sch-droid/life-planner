package com.lifeplanner.ai.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 检查 GitHub 上是否有新版本。
 *
 * 为什么用 Releases 而不是仓库里的版本文件：
 * - 仓库的 CI 会在推送 main 时自动打 tag 并发布 Release（见 .github/workflows），
 *   所以"上传到仓库"自然等于"产生一个可被 App 发现的版本"；
 * - Release 自带 APK 资产和更新说明，不需要再维护第二份版本清单。
 *
 * 这个检查是**只读、无鉴权**的：公开仓库的 latest release 接口不需要 token，
 * 应用里也绝不内置任何 GitHub 凭据。
 */
data class UpdateInfo(
    /** 去掉 v 前缀的版本号，例如 "2.3.0" */
    val version: String,
    /** Release 页面地址（浏览器打开） */
    val releaseUrl: String,
    /** 直接下载 APK 的地址；没有 APK 资产时为 null */
    val apkUrl: String?,
    /** Release 说明（Markdown 原文） */
    val notes: String,
    val publishedAt: String,
)

object UpdateChecker {

    private const val OWNER = "1535273240sch-droid"
    private const val REPO = "life-planner"

    /** 仓库地址：设置页「关于」里展示并可点击打开。 */
    const val REPO_URL = "https://github.com/1535273240sch-droid/life-planner"

    /** Release 列表页。 */
    const val RELEASES_URL = REPO_URL + "/releases"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 查询最新 Release。返回 null 表示没有更新（或仓库暂无 Release）。
     * 网络异常会抛出 IOException，由调用方决定是否提示（默认静默）。
     */
    fun check(currentVersion: String): UpdateInfo? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest")
            .header("Accept", "application/vnd.github+json")
            // GitHub API 要求带 User-Agent，否则 403
            .header("User-Agent", "LifePlannerAI-Android")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val root = Json.parseToJsonElement(body).jsonObject

            fun str(key: String): String =
                root[key]?.jsonPrimitive?.content?.trim().orEmpty()

            // 预发布/draft 不参与更新提示：它们可能还没构建完 APK。
            if (root["draft"]?.jsonPrimitive?.content == "true") return null
            if (root["prerelease"]?.jsonPrimitive?.content == "true") return null

            val tag = str("tag_name")
            if (tag.isBlank()) return null
            val latest = tag.removePrefix("v").removePrefix("V")
            if (!isNewer(latest, currentVersion)) return null

            var apkUrl: String? = null
            val assets = root["assets"]?.let { element ->
                element as? kotlinx.serialization.json.JsonArray
            }
            assets?.forEach { asset ->
                val obj = asset.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
                if (apkUrl == null && name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = obj["browser_download_url"]?.jsonPrimitive?.content
                }
            }

            return UpdateInfo(
                version = latest,
                releaseUrl = str("html_url"),
                apkUrl = apkUrl,
                notes = str("body"),
                publishedAt = str("published_at"),
            )
        }
    }

    /** 版本号逐段比较；非数字段忽略（"2.3.0-beta" 视作 2.3.0）。 */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parse(latest)
        val b = parse(current)
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parse(version: String): List<Int> =
        version.removePrefix("v").removePrefix("V")
            .split('.', '-', '+', ' ')
            .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }

    /** 给界面显示的版本名（不含 v）。 */
    fun normalize(version: String): String =
        version.trim().removePrefix("v").removePrefix("V")
}