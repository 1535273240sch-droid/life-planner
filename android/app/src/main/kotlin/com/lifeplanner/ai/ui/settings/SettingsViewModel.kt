package com.lifeplanner.ai.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeplanner.ai.AppContainer
import com.lifeplanner.ai.data.AiConfigRules
import com.lifeplanner.ai.data.AiProvider
import com.lifeplanner.ai.data.UpdatePrefs
import com.lifeplanner.ai.net.ModelListClient
import com.lifeplanner.ai.net.ModelListException
import com.lifeplanner.ai.net.UpdateChecker
import com.lifeplanner.ai.net.UpdateInfo
import com.lifeplanner.ai.net.appVersionName
import com.lifeplanner.ai.notify.PushAlarmScheduler
import com.lifeplanner.ai.notify.PushNotifier
import com.lifeplanner.core.ai.AiConfig
import com.lifeplanner.core.daily.PushConfig
import com.lifeplanner.core.daily.PushKind
import com.lifeplanner.core.json.ImportMode
import com.lifeplanner.core.json.ImportSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

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
    /** 当前选中的服务商预设 id */
    val providerId: String? = null,
    /** 正在从服务商拉取模型列表 */
    val fetchingModels: Boolean = false,
    /** 拉取到的模型 id；点击后填入模型名称 */
    val modelOptions: List<String> = emptyList(),
    /** 允许把 API Key 以明文发往公网 http（需用户显式勾选） */
    val allowInsecureHttp: Boolean = false,
    /** 已读入内存、等待用户二次确认的备份文本；非空时界面弹确认框 */
    val pendingImportText: String? = null,
    /** 最近一次导入的统计 */
    val importSummary: ImportSummary? = null,
    /** 每日推送配置（两个时段是否开启、各自的时分） */
    val pushConfig: PushConfig = PushConfig(),
    /** 手动检查更新进行中 */
    val updateChecking: Boolean = false,
    /** 手动检查发现的新版本（非空时设置页弹更新对话框） */
    val updateInfo: UpdateInfo? = null,
    /** 手动检查的一次性提示（已是最新 / 检查失败） */
    val updateMessage: String? = null,
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

    private val modelListClient = ModelListClient()

    /** 串行化推送配置的写盘与重排，避免连续操作时 IO 乱序。 */
    private val pushWriteMutex = Mutex()

    fun load() {
        viewModelScope.launch {
            try {
                val (config, push) = withContext(Dispatchers.IO) {
                    container.settings.loadAiConfig() to container.settings.loadPushConfig()
                }
                _state.update {
                    it.copy(
                        loading = false,
                        apiKey = config.apiKey,
                        baseUrl = config.baseUrl,
                        model = config.model,
                        // 已保存的是公网 http 时，保持开关打开并显示警告，避免用户困惑。
                        allowInsecureHttp = AiConfigRules.isPublicHttp(config.baseUrl),
                        pushConfig = push,
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
        // 地址改了，旧的模型列表不再可信。
        _state.update { it.copy(baseUrl = value, modelOptions = emptyList()) }
    }

    fun onModelChange(value: String) {
        _state.update { it.copy(model = value) }
    }

    fun onAllowInsecureHttpChange(value: Boolean) {
        _state.update { it.copy(allowInsecureHttp = value, error = null) }
    }

    /** 选择服务商预设：只填表单，不发网络请求。 */
    fun selectProvider(provider: AiProvider) {
        _state.update {
            it.copy(
                providerId = provider.id,
                baseUrl = if (provider.baseUrl.isNotBlank()) provider.baseUrl else it.baseUrl,
                model = if (provider.defaultModel.isNotBlank()) provider.defaultModel else it.model,
                modelOptions = emptyList(),
                error = null,
                message = null,
            )
        }
    }

    /**
     * 拉取 `GET {baseUrl}/models`，同时起到“测试连接”的作用。
     * 成功后可点模型名直接填入；失败显示服务端返回的具体原因（401/404/超时等）。
     */
    fun fetchModels() {
        val current = _state.value
        if (current.fetchingModels || current.busy) return
        val baseUrl = AiConfigRules.normalizeBaseUrl(current.baseUrl)
        val urlError = AiConfigRules.baseUrlError(current.baseUrl, current.allowInsecureHttp)
        if (urlError != null) {
            _state.update { it.copy(error = urlError, message = null) }
            return
        }
        if (current.apiKey.isBlank()) {
            _state.update { it.copy(error = "请先填写 API Key", message = null) }
            return
        }

        viewModelScope.launch {
            _state.update {
                it.copy(
                    fetchingModels = true,
                    error = null,
                    message = null,
                    modelOptions = emptyList(),
                    baseUrl = baseUrl,
                )
            }
            try {
                val models = withContext(Dispatchers.IO) {
                    modelListClient.fetchModels(baseUrl, current.apiKey, current.allowInsecureHttp)
                }
                _state.update {
                    it.copy(
                        fetchingModels = false,
                        modelOptions = models,
                        model = if (it.model.isBlank() || it.model !in models) models.first() else it.model,
                        message = "已获取 ${models.size} 个模型，点下方模型名可切换",
                    )
                }
            } catch (e: ModelListException) {
                _state.update { it.copy(fetchingModels = false, error = e.message) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(fetchingModels = false, error = "获取模型列表失败：${readable(e)}")
                }
            }
        }
    }

    /** 校验并保存 AI 配置。AppContainer.aiService() 每次调用都读取设置，因此保存后立即生效。 */
    fun saveConfig() {
        val current = _state.value
        val baseUrl = AiConfigRules.normalizeBaseUrl(current.baseUrl)
        val apiKey = AiConfigRules.normalizeApiKey(current.apiKey)
        val model = current.model.trim()
        val baseUrlError = AiConfigRules.baseUrlError(current.baseUrl, current.allowInsecureHttp)
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
                        AiConfig(apiKey = apiKey, baseUrl = baseUrl, model = model),
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

    // ---------------------------------------------------------------- 版本与更新

    /**
     * 手动检查更新（设置页按钮）。
     * 自动检查在 UpdateHost 里；这里只负责给用户一个"我现在就想确认"的入口。
     */
    fun checkUpdate() {
        if (_state.value.updateChecking) return
        _state.update { it.copy(updateChecking = true, updateInfo = null, updateMessage = null) }
        viewModelScope.launch {
            try {
                val current = appVersionName(container.appContext)
                val info = withContext(Dispatchers.IO) { UpdateChecker.check(current) }
                // 手动检查过就顺带重置 24 小时计时；发现新版本也标记已提示，避免下次启动再弹。
                UpdatePrefs(container.appContext).apply {
                    markChecked()
                    if (info != null) markPrompted(info.version)
                }
                _state.update {
                    it.copy(
                        updateChecking = false,
                        updateInfo = info,
                        updateMessage = if (info == null) "已是最新版本（v" + current + "）" else null,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(updateChecking = false, updateMessage = "检查更新失败，请检查网络后重试")
                }
            }
        }
    }

    fun dismissUpdate() {
        _state.update { it.copy(updateInfo = null) }
    }

    fun consumeUpdateMessage() {
        _state.update { it.copy(updateMessage = null) }
    }

    // ---------------------------------------------------------------- 每日推送

    /**
     * 推送配置的每次改动都走这里：先更新界面状态，再把配置写盘并立刻重排闹钟。
     *
     * “保存即重排”是硬要求：用户把早间从 07:30 改成 08:00 后，必须马上生效，
     * 否则要等到下一次触发才更新，用户会以为设置没保存。
     * 用 Mutex 串行化写盘：连续拖两次开关时，避免两次 IO 乱序导致最终落盘的是旧配置。
     */
    private fun updatePushConfig(transform: (PushConfig) -> PushConfig) {
        val next = transform(_state.value.pushConfig).sanitized()
        _state.update { it.copy(pushConfig = next, error = null) }
        viewModelScope.launch {
            try {
                pushWriteMutex.withLock {
                    withContext(Dispatchers.IO) {
                        container.settings.savePushConfig(next)
                        // 重排放在 IO：读 SharedPreferences 可能触发一次磁盘加载。
                        // 两个时段都关时 schedule 内部会 cancel 掉已有闹钟。
                        PushAlarmScheduler(container.appContext).schedule(next)
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = "保存推送设置失败：${readable(e)}") }
            }
        }
    }

    fun onPushMorningEnabled(enabled: Boolean) {
        updatePushConfig { it.copy(morningEnabled = enabled) }
    }

    fun onPushEveningEnabled(enabled: Boolean) {
        updatePushConfig { it.copy(eveningEnabled = enabled) }
    }

    fun onPushMorningTime(hour: Int, minute: Int) {
        updatePushConfig { it.copy(morningHour = hour, morningMinute = minute) }
    }

    fun onPushEveningTime(hour: Int, minute: Int) {
        updatePushConfig { it.copy(eveningHour = hour, eveningMinute = minute) }
    }

    /**
     * 立即发一条早间样式的通知，用来验证权限与渠道是否正常。
     * 不修改配置、不排闹钟，只验证“能不能弹出来”。
     */
    fun sendTestPush() {
        if (_state.value.busy) return
        viewModelScope.launch {
            try {
                val date = withContext(Dispatchers.IO) {
                    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
                }
                val shown = withContext(Dispatchers.IO) {
                    PushNotifier(container.appContext).show(PushKind.MORNING, date)
                }
                _state.update {
                    if (shown) {
                        it.copy(message = "测试通知已发送；若没看到，请检查系统通知权限", error = null)
                    } else {
                        it.copy(
                            error = "系统未允许通知，测试通知无法显示。请先在系统设置中允许本应用的通知。",
                            message = null,
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = "发送测试通知失败：${readable(e)}") }
            }
        }
    }

    /**
     * 通知权限被拒绝。
     * 不再重复申请（系统会直接拒绝且不再弹窗），改为在设置页给出明确的去授权入口。
     */
    fun onNotificationPermissionDenied() {
        _state.update {
            it.copy(
                error = "通知权限被拒绝，推送不会显示。可点「去允许通知」在系统设置里开启。",
                message = null,
            )
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun readable(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
}