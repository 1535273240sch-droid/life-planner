package com.lifeplanner.ai

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
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
import com.lifeplanner.ai.ui.LifePlannerRoot
import com.lifeplanner.ai.ui.theme.LifePlannerTheme

/**
 * 唯一的 Activity：原生 Compose 单页应用。
 * 业务流程与数据全部在本地（core 模块 + SQLDelight），不依赖电脑端服务。
 */
class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as LifePlannerApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
}
