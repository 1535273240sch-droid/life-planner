package com.lifeplanner.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
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
        // 错误说明文字较长，用 LENGTH_LONG 才来得及读完
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    /** 检查是否运行在原生 Android 容器中 */
    @JavascriptInterface
    fun isNativeAndroid(): Boolean {
        return true
    }

    /** 获取原生版本号 */
    @JavascriptInterface
    fun getAppVersion(): String {
        return "1.1.0-Native"
    }

    /**
     * 打开系统拨号盘并填入号码。危机干预热线需要能从 App 内一键拨打。
     * 只使用 ACTION_DIAL（只填号，不直接呼出），因此不需要任何通话权限。
     */
    @JavascriptInterface
    fun dialPhone(number: String) {
        val normalized = number.trim().replace(" ", "").replace("-", "")
        // 只接受数字（可带开头 +），避免把任意 URI 交给系统去解析
        if (!Regex("^\\+?\\d{3,20}$").matches(normalized)) {
            Toast.makeText(context, "号码格式不正确，未能拨打", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$normalized"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开拨号盘，请手动拨打 $normalized", Toast.LENGTH_LONG).show()
        }
    }
}
