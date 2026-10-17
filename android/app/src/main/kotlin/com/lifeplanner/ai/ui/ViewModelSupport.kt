package com.lifeplanner.ai.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifeplanner.ai.AppContainer

/**
 * 页面 ViewModel 的统一创建方式：
 *
 * ```kotlin
 * class FooViewModel(private val container: AppContainer) : ViewModel() { ... }
 *
 * @Composable
 * fun FooScreen(container: AppContainer, onNavigate: (NavTarget) -> Unit) {
 *     val vm: FooViewModel = appViewModel(container) { FooViewModel(it) }
 * }
 * ```
 *
 * ViewModel 内所有仓储调用都要 `withContext(Dispatchers.IO)`。
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    container: AppContainer,
    key: String? = null,
    crossinline builder: (AppContainer) -> VM,
): VM = viewModel(
    modelClass = VM::class.java,
    key = key,
    factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = builder(container) as T
    },
)
