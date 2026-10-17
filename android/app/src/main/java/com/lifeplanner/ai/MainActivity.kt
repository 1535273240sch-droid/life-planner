package com.lifeplanner.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.lifeplanner.ai.data.SettingsStore
import com.lifeplanner.ai.notify.PushAlarmScheduler
import com.lifeplanner.ai.notify.PushNavigation
import com.lifeplanner.ai.notify.PushNotifier
import com.lifeplanner.ai.notify.PushPermissions
import com.lifeplanner.ai.ui.LifePlannerRoot
import com.lifeplanner.ai.ui.theme.LifePlannerTheme

/**
 * 唯一的 Activity：原生 Compose 单页应用。
 * 业务流程与数据全部在本地（core 模块 + SQLDelight），不依赖电脑端服务。
 *
 * 同时承担每日推送的两个“应用侧”职责：
 * 1. 启动时按本地设置重排闹钟（进程可能被系统回收过，上次排的闹钟不一定还在）；
 * 2. 接收通知点击带来的 push_route，交给 [PushNavigation] 由界面消费。
 */
class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as LifePlannerApp).container

    /**
     * 通知权限申请器。放在字段初始化里注册：Activity 的 onStart 之前必须完成注册。
     * 申请结果不在这里处理——设置页会在返回前台时重新读取权限状态。
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 结果由界面重新查询 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 渠道、闹钟、权限都必须在 UI 之前处理：
        // 从通知冷启动时用户直接看到首屏，此时另一次推送的闹钟应当已经排好。
        PushNotifier(this).ensureChannel()
        reschedulePushAlarms()
        requestPushPermissionQuietly()
        handlePushIntent(intent)

        // 上次启动崩溃过：先用原生 View 展示堆栈（不经过 Compose），方便用户复制反馈。
        val crash = CrashLogger.read(this)
        if (crash != null) {
            showCrashReport(crash)
            return
        }

        enableEdgeToEdge()
        setContent {
            LifePlannerTheme {
                LifePlannerRoot(container)
            }
        }
    }

    /**
     * 应用已在前台时点通知会走这里（Intent 带 CLEAR_TOP + SINGLE_TOP）。
     * 必须重新 setIntent，否则后续 getIntent() 拿到的仍是旧 Intent。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePushIntent(intent)
    }

    /** 把通知里的 push_route 投递给 PushNavigation，由 LifePlannerRoot 导航。 */
    private fun handlePushIntent(intent: Intent?) {
        val safeIntent = intent ?: return
        val route = safeIntent.getStringExtra(PushNavigation.EXTRA_PUSH_ROUTE)
            ?.takeIf { it.isNotBlank() }
            ?: return
        // 取出后立刻清掉：Activity 因崩溃恢复等原因重建时，同一 intent 会被再次读取，
        // 若不清理会在每次重建时重复跳转，覆盖用户自己的导航选择。
        safeIntent.removeExtra(PushNavigation.EXTRA_PUSH_ROUTE)
        PushNavigation.post(route)
    }

    /**
     * 启动时重排闹钟。
     * 失败（例如厂商 ROM 限制精确闹钟）不应该阻塞启动，所以吞掉异常：
     * 最坏情况是这一次推送不准，而不是应用起不来。
     */
    private fun reschedulePushAlarms() {
        runCatching { PushAlarmScheduler(this).rescheduleFromSettings() }
    }

    /**
     * API 33+ 首次进入时请求一次通知权限。
     *
     * 不做自定义前置说明弹窗（“静默”= 不打断用户操作），
     * 且只在推送开启时请求；两个时段都关闭时完全没有必要打扰。
     * 用户拒绝后不再反复弹：设置页有明确的权限引导入口。
     */
    private fun requestPushPermissionQuietly() {
        if (!PushPermissions.shouldRequestNotificationPermission()) return
        if (PushPermissions.notificationsGranted(this)) return
        if (permissionAskedThisProcess) return

        val config = SettingsStore(this).loadPushConfig()
        if (!config.morningEnabled && !config.eveningEnabled) return

        permissionAskedThisProcess = true
        runCatching { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    /** 只使用系统原生控件，确保即使 Compose 本身出问题也能显示。 */
    @SuppressLint("SetTextI18n")
    private fun showCrashReport(crash: String) {
        val body = TextView(this).apply {
            text = "上次启动发生崩溃。请点下方「复制错误信息」，把内容发给开发者：\n\n$crash"
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(32, 32, 32, 32)
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(body)
        }

        val copyButton = Button(this).apply {
            text = "复制错误信息"
            setOnClickListener {
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("崩溃信息", body.text))
                Toast.makeText(this@MainActivity, "已复制", Toast.LENGTH_SHORT).show()
            }
        }

        val clearButton = Button(this).apply {
            text = "清除记录并重启"
            setOnClickListener {
                CrashLogger.clear(this@MainActivity)
                recreate()
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(copyButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(clearButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
    }

    private companion object {
        /**
         * 进程内只主动申请一次通知权限。
         * 用进程级标记而不是持久化标记：用户清缓存/重装后重新申请是合理的，
         * 但同一次使用里不要反复弹系统弹窗。
         */
        var permissionAskedThisProcess = false
    }
}
