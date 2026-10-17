package com.lifeplanner.ai

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动期崩溃记录：把未捕获异常的堆栈写到应用私有文件，下次启动时由 MainActivity
 * 用原生 View 展示（不经过 Compose，避免崩溃本身导致界面也渲染失败）。
 * 内容只包含设备型号、系统版本和堆栈，不包含用户数据或 API Key。
 */
object CrashLogger {

    private const val FILE_NAME = "crash_last.txt"
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val whenText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val text = buildString {
                    appendLine("时间：$whenText")
                    appendLine("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    appendLine("系统：Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                    appendLine("线程：${thread.name}")
                    appendLine()
                    append(Log.getStackTraceString(throwable))
                }
                app.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).use { it.write(text.toByteArray()) }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun read(context: Context): String? = runCatching {
        context.openFileInput(FILE_NAME).bufferedReader().use { it.readText() }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        context.deleteFile(FILE_NAME)
    }
}
