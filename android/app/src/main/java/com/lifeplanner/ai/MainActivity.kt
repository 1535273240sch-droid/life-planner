package com.lifeplanner.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
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
        enableEdgeToEdge()
        setContent {
            LifePlannerTheme {
                LifePlannerRoot(container)
            }
        }
    }
}
