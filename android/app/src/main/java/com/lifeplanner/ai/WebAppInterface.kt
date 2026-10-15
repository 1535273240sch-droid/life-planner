package com.lifeplanner.ai

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.webkit.JavascriptInterface
import android.widget.Toast

class WebAppInterface(private val context: Context) {

    /** 原生轻微触觉反馈（振动） */
    @JavascriptInterface
    fun hapticFeedback(milliseconds: Long = 20) {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(
                            milliseconds,
                            VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(milliseconds)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 弹出原生 Android Toast 提示 */
    @JavascriptInterface
    fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /** 检查是否运行在原生 Android 容器中 */
    @JavascriptInterface
    fun isNativeAndroid(): Boolean {
        return true
    }

    /** 获取原生版本号 */
    @JavascriptInterface
    fun getAppVersion(): String {
        return "1.0.0-Native"
    }
}
