package com.lifeplanner.ai.net

import android.content.Context
import android.content.Intent
import android.net.Uri

/** 读取当前安装包的版本名（不依赖 BuildConfig，AGP 8 默认不生成它）。 */
fun appVersionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
} catch (e: Exception) {
    ""
}

/** 打开更新：优先直接下载 APK，没有资产时退回 Release 页面。 */
fun openUpdate(context: Context, info: UpdateInfo) {
    val url = info.apkUrl?.takeIf { it.isNotBlank() } ?: info.releaseUrl
    if (url.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
