package com.lifeplanner.ai.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeplanner.ai.AppContainer
import com.lifeplanner.core.ai.AiConfig
import com.lifeplanner.core.json.ImportMode
import com.lifeplanner.core.json.ImportSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

data class SettingsUiState(
    val loading: Boolean = true,
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
    /** 保存配置 / 导入 / 导出 / 清空历史进行中 */
    val busy: Boolean = false,
    /** 一次性提示（成功后显示并自动清除） */
    val message: String? = null,
    /** 可读错误，保留到下一次操作 */
    val error: String? = null,
    /** 已读入内存、等待用户二次确认的备份文本；非空时界面弹确认框 */
    val pendingImportText: String? = null,
    /** 最近一次导入的统计 */
    val importSummary: ImportSummary? = null,
)

/**
 * 设置页 ViewModel。
 *
 * 所有 SharedPreferences / 文件 / 数据库操作都在 Dispatchers.IO 上执行；
 * 任何异常都转成中文错误写进 state，不吞异常、不打印 Key。
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            try {
                val config = withContext(Dispatchers.IO) { container.settings.loadAiConfig() }
                _state.update {
                    it.copy(
                        loading = false,
                        apiKey = config.apiKey,
                        baseUrl = config.baseUrl,
                        model = config.model,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = "读取设置失败：${readable(e)}")
                }
            }
        }
    }

    fun onApiKeyChange(value: String) {
        _state.update { it.copy(apiKey = value) }
    }

    fun onBaseUrlChange(value: String) {
        _state.update { it.copy(baseUrl = value) }
    }

    fun onModelChange(value: String) {
        _state.update { it.copy(model = value) }
    }

    /** 校验并保存 AI 配置。AppContainer.aiService() 每次调用都读取设置，因此保存后立即生效。 */
    fun saveConfig() {
        val current = _state.value
        val baseUrl = current.baseUrl.trim()
        val model = current.model.trim()
        val baseUrlError = validateBaseUrl(baseUrl)
        val validation = when {
            baseUrlError != null -> baseUrlError
            model.isEmpty() -> "模型名称不能为空"
            else -> null
        }
        if (validation != null) {
            _state.update { it.copy(error = validation, message = null) }
            return
        }
        if (current.busy) return

        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, message = null) }
            try {
                // 保存后重新读取，反映 SettingsStore 的规范化结果（去尾斜杠 / 空值回落默认）
                val saved = withContext(Dispatchers.IO) {
                    container.settings.saveAiConfig(
                        AiConfig(apiKey = current.apiKey, baseUrl = baseUrl, model = model),
                    )
                    container.settings.loadAiConfig()
                }
                _state.update {
                    it.copy(
                        busy = false,
                        apiKey = saved.apiKey,
                        baseUrl = saved.baseUrl,
                        model = saved.model,
                        message = "已保存，立即生效",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = "保存失败：${readable(e)}") }
            }
        }
    }

    /** 导出到 SAF 选中的文件（UTF-8）。 */
    fun exportTo(uri: Uri) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, message = null) }
            try {
                withContext(Dispatchers.IO) {
                    val json = container.backupService.exportJson()
                    val output = container.appContext.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("无法写入所选文件")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
                }
                _state.update { it.copy(busy = false, message = "导出成功") }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = "导出失败：${readable(e)}") }
            }
        }
    }

    /** 读取 SAF 选中的备份文件，成功后进入待确认状态（界面上弹二次确认框）。 */
    fun prepareImport(uri: Uri) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update {
                it.copy(busy = true, error = null, message = null, importSummary = null)
            }
            try {
                val text = withContext(Dispatchers.IO) {
                    val input = container.appContext.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("无法读取所选文件")
                    input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                if (text.isBlank()) throw IllegalArgumentException("备份文件内容为空")
                _state.update { it.copy(busy = false, pendingImportText = text) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = "读取备份失败：${readable(e)}") }
            }
        }
    }

    /** 用户确认后执行 REPLACE 导入。 */
    fun confirmImport() {
        val text = _state.value.pendingImportText ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    pendingImportText = null,
                    error = null,
                    message = null,
                    importSummary = null,
                )
            }
            try {
                val summary = withContext(Dispatchers.IO) {
                    container.backupService.importJson(text, ImportMode.REPLACE)
                }
                _state.update {
                    it.copy(busy = false, importSummary = summary, message = "导入完成")
                }
            } catch (e: IllegalArgumentException) {
                _state.update {
                    it.copy(busy = false, error = "导入失败：${readable(e)}")
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        error = "导入失败（写入不是事务性的，可能有部分数据已写入，建议重新导入或从备份恢复）：${readable(e)}",
                    )
                }
            }
        }
    }

    fun cancelImport() {
        _state.update { it.copy(pendingImportText = null) }
    }

    fun clearAiHistory() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, message = null) }
            try {
                withContext(Dispatchers.IO) { container.aiMessageRepository.clear() }
                _state.update { it.copy(busy = false, message = "AI 历史已清空") }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = "清空失败：${readable(e)}") }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun readable(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    /**
     * Base URL 安全校验：
     * - https:// 允许任意合法主机（Key 走 TLS）；
     * - http:// 只允许本机 / 私有网段，避免 API Key 以 Bearer 明文发往公网；
     * - 空串、非法 URL、其他 scheme 一律拒绝。返回 null 表示通过。
     */
    private fun validateBaseUrl(raw: String): String? {
        if (raw.isEmpty()) return "Base URL 不能为空"
        val uri = try {
            URI(raw)
        } catch (e: Exception) {
            return "Base URL 格式不正确"
        }
        val scheme = uri.scheme?.lowercase()
            ?: return "Base URL 必须以 https:// 或 http:// 开头"
        val host = uri.host
            ?: return "Base URL 缺少主机名或格式不正确"
        return when (scheme) {
            "https" ->
                if (host.isNotBlank()) null else "Base URL 缺少主机名"
            "http" ->
                if (isLocalOrPrivateHost(host)) {
                    null
                } else {
                    "http 仅支持本机/局域网地址（localhost、127.0.0.1、10.x.x.x、" +
                        "172.16-31.x.x、192.168.x.x、::1）；公网请使用 https"
                }
            else -> "Base URL 仅支持 http:// 或 https://"
        }
    }

    /** 只接受明确列出的本机/私有地址，不做域名后缀或字符串包含判断。 */
    private fun isLocalOrPrivateHost(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (h == "localhost" || h == "127.0.0.1" || h == "::1") return true
        val parts = h.split(".")
        if (parts.size != 4) return false
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val value = parts[i].toIntOrNull() ?: return false
            if (value !in 0..255) return false
            octets[i] = value
        }
        return when {
            octets[0] == 10 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            else -> false
        }
    }
}
