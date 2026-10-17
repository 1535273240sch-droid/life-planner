package com.lifeplanner.ai.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lifeplanner.ai.data.UpdatePrefs
import com.lifeplanner.ai.net.UpdateInfo
import com.lifeplanner.ai.net.UpdateChecker
import com.lifeplanner.ai.net.appVersionName
import com.lifeplanner.ai.net.openUpdate
import com.lifeplanner.ai.ui.theme.LpColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 启动时的更新提示。
 *
 * 行为：
 * - 24 小时最多查一次；同一个版本只自动提示一次（用户点"以后再说"就不再打扰）；
 * - 网络失败完全静默——更新检查不能影响打开应用的体验；
 * - 只做提示与跳转下载，不在应用内静默安装（避免请求安装未知来源权限）。
 *
 * 新版本从哪来：仓库 CI 在每次推送 main 时会读取 versionName 自动创建
 * vX.Y.Z Release 并上传 APK，所以"上传新版本到仓库"即会被这里发现。
 */
@Composable
fun UpdateHost() {
    val context = LocalContext.current
    val prefs = remember { UpdatePrefs(context) }
    var info by remember { mutableStateOf<UpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        if (!prefs.shouldAutoCheck()) return@LaunchedEffect
        // 先标记检查时间：失败也不要在同一天反复打 GitHub API。
        prefs.markChecked()
        val current = appVersionName(context)
        val found = withContext(Dispatchers.IO) {
            runCatching { UpdateChecker.check(current) }.getOrNull()
        } ?: return@LaunchedEffect
        if (prefs.lastPromptedVersion() == found.version) return@LaunchedEffect
        prefs.markPrompted(found.version)
        info = found
    }

    val update = info ?: return
    AlertDialog(
        onDismissRequest = { info = null },
        containerColor = LpColors.SurfaceHigh,
        titleContentColor = LpColors.TextPrimary,
        textContentColor = LpColors.TextSecondary,
        title = {
            Text(
                text = "发现新版本 v" + update.version,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column {
                Text(
                    text = "当前版本 v" + appVersionName(context) + "，可以更新到最新版。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (update.notes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = update.notes.take(500),
                        style = MaterialTheme.typography.bodySmall,
                        color = LpColors.TextMuted,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                openUpdate(context, update)
                info = null
            }) {
                Text("去下载", color = LpColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = { info = null }) {
                Text("以后再说", color = LpColors.TextMuted)
            }
        },
    )
}