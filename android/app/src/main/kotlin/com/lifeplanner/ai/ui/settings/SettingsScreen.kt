package com.lifeplanner.ai.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeplanner.ai.AppContainer
import com.lifeplanner.ai.data.AiConfigRules
import com.lifeplanner.ai.data.AiProviders
import com.lifeplanner.ai.net.UpdateChecker
import com.lifeplanner.ai.net.appVersionName
import com.lifeplanner.ai.net.openUpdate
import com.lifeplanner.ai.net.openUrl
import com.lifeplanner.ai.notify.PushPermissions
import com.lifeplanner.ai.ui.NavTarget
import com.lifeplanner.ai.ui.appViewModel
import com.lifeplanner.ai.ui.components.LpCard
import com.lifeplanner.ai.ui.components.LpDivider
import com.lifeplanner.ai.ui.components.LpErrorBanner
import com.lifeplanner.ai.ui.components.LpHint
import com.lifeplanner.ai.ui.components.LpLoading
import com.lifeplanner.ai.ui.components.LpOutlinedField
import com.lifeplanner.ai.ui.components.LpPrimaryButton
import com.lifeplanner.ai.ui.components.LpSectionTitle
import com.lifeplanner.ai.ui.components.LpSpacing
import com.lifeplanner.ai.ui.theme.LpColors
import com.lifeplanner.core.util.Dates
import kotlinx.coroutines.delay

/**
 * 设置页：AI 配置、备份导入导出、清空 AI 历史、关于与隐私。
 *
 * 所有耗时操作（SharedPreferences 写入、JSON 读写、数据库清空）都在 ViewModel 的 IO 线程执行；
 * 这里只负责收集状态与 SAF 文件选择。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SettingsScreen(container: AppContainer, onNavigate: (NavTarget) -> Unit) {
    val vm: SettingsViewModel = appViewModel(container) { SettingsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showApiKey by rememberSaveable { mutableStateOf(false) }
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }
    var showMorningPicker by rememberSaveable { mutableStateOf(false) }
    var showEveningPicker by rememberSaveable { mutableStateOf(false) }

    // 系统权限可能在任何时候变化（用户去设置页开关通知、厂商撤销精确闹钟），
    // 所以在每次 ON_RESUME 时重新读取，而不是只在进入页面时读一次。
    var permissionVersion by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionVersion++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationsGranted = remember(permissionVersion) {
        PushPermissions.notificationsGranted(context)
    }
    val exactAlarmsGranted = remember(permissionVersion) {
        PushPermissions.canScheduleExactAlarms(context)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionVersion++
        if (!granted) vm.onNotificationPermissionDenied()
    }

    /** 开启推送开关时顺带申请通知权限：已授权或系统低于 Android 13 时什么都不做。 */
    val ensureNotificationPermission: () -> Unit = {
        if (PushPermissions.shouldRequestNotificationPermission() && !notificationsGranted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) vm.exportTo(uri)
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.prepareImport(uri)
    }

    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "未知" }
    }

    LaunchedEffect(Unit) { vm.load() }

    val message = state.message
    LaunchedEffect(message) {
        if (message != null) {
            delay(3000)
            vm.consumeMessage()
        }
    }

    // 导入二次确认：读文件成功后才会出现
    state.pendingImportText?.let {
        AlertDialog(
            onDismissRequest = vm::cancelImport,
            title = { Text("确认导入？") },
            text = {
                Text(
                    "将覆盖本机全部数据，且导入过程不是事务性的，" +
                        "失败可能留下部分数据。建议先导出当前数据作为备份。",
                )
            },
            confirmButton = {
                TextButton(onClick = vm::confirmImport) {
                    Text("确认导入", color = LpColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelImport) {
                    Text("取消", color = LpColors.TextMuted)
                }
            },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空 AI 历史？") },
            text = { Text("将删除本机保存的全部 AI 对话与复盘记录，且不可恢复。目标、任务、每日记录不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        vm.clearAiHistory()
                    },
                ) {
                    Text("确认清空", color = LpColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消", color = LpColors.TextMuted)
                }
            },
        )
    }

    if (showMorningPicker) {
        LpTimePickerDialog(
            title = "早间推送时间",
            initialHour = state.pushConfig.morningHour,
            initialMinute = state.pushConfig.morningMinute,
            onDismiss = { showMorningPicker = false },
            onConfirm = { hour, minute ->
                showMorningPicker = false
                vm.onPushMorningTime(hour, minute)
            },
        )
    }

    if (showEveningPicker) {
        LpTimePickerDialog(
            title = "晚间推送时间",
            initialHour = state.pushConfig.eveningHour,
            initialMinute = state.pushConfig.eveningMinute,
            onDismiss = { showEveningPicker = false },
            onConfirm = { hour, minute ->
                showEveningPicker = false
                vm.onPushEveningTime(hour, minute)
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(LpSpacing.lg),
    ) {
        state.error?.let { LpErrorBanner(message = it) }

        state.message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = LpColors.Accent,
            )
        }

        if (state.loading) {
            LpLoading()
        } else {
            if (state.busy) {
                LpLoading()
            }

            // ---- AI 配置 ----
            LpCard {
                LpSectionTitle("AI 配置")
                Spacer(Modifier.height(LpSpacing.sm))
                Text(
                    text = "服务商（点选后自动填入地址和常用模型）",
                    style = MaterialTheme.typography.bodySmall,
                    color = LpColors.TextMuted,
                )
                Spacer(Modifier.height(LpSpacing.xs))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(LpSpacing.sm)) {
                    items(AiProviders.all) { provider ->
                        FilterChip(
                            selected = state.providerId == provider.id,
                            onClick = { vm.selectProvider(provider) },
                            enabled = !state.busy && !state.fetchingModels,
                            label = { Text(provider.name) },
                        )
                    }
                }
                AiProviders.all.firstOrNull { it.id == state.providerId }?.note?.let { note ->
                    Spacer(Modifier.height(LpSpacing.xs))
                    LpHint(note)
                }
                Spacer(Modifier.height(LpSpacing.md))
                SecretField(
                    value = state.apiKey,
                    onValueChange = vm::onApiKeyChange,
                    visible = showApiKey,
                    onToggleVisible = { showApiKey = !showApiKey },
                    enabled = !state.busy,
                )
                Spacer(Modifier.height(LpSpacing.md))
                LpOutlinedField(
                    value = state.baseUrl,
                    onValueChange = vm::onBaseUrlChange,
                    label = "Base URL",
                    enabled = !state.busy,
                    placeholder = "https://api.openai.com/v1",
                )
                if (AiConfigRules.isPublicHttp(state.baseUrl)) {
                    Spacer(Modifier.height(LpSpacing.sm))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.allowInsecureHttp,
                            onCheckedChange = vm::onAllowInsecureHttpChange,
                            enabled = !state.busy,
                        )
                        Text(
                            text = "允许公网 HTTP（不推荐）",
                            style = MaterialTheme.typography.bodySmall,
                            color = LpColors.Warning,
                        )
                    }
                    LpHint(
                        "开启后 API Key 会以明文经过网络传输。只有在你完全信任该网络与服务端时才使用；" +
                            "能改用 https 就优先用 https。",
                        warn = true,
                    )
                }
                Spacer(Modifier.height(LpSpacing.md))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        LpOutlinedField(
                            value = state.model,
                            onValueChange = vm::onModelChange,
                            label = "模型名称",
                            enabled = !state.busy,
                            placeholder = "gpt-4o-mini",
                        )
                    }
                    Spacer(Modifier.width(LpSpacing.sm))
                    OutlinedButton(
                        onClick = vm::fetchModels,
                        enabled = !state.busy && !state.fetchingModels,
                    ) {
                        if (state.fetchingModels) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = LpColors.Accent,
                            )
                        } else {
                            Text("获取模型")
                        }
                    }
                }
                if (state.modelOptions.isNotEmpty()) {
                    Spacer(Modifier.height(LpSpacing.sm))
                    Text(
                        text = "点选模型即可填入：",
                        style = MaterialTheme.typography.bodySmall,
                        color = LpColors.TextMuted,
                    )
                    Spacer(Modifier.height(LpSpacing.xs))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(LpSpacing.sm)) {
                        items(state.modelOptions) { modelId ->
                            AssistChip(
                                onClick = { vm.onModelChange(modelId) },
                                label = { Text(modelId) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(LpSpacing.md))
                LpHint(
                    "可只填域名（如 api.deepseek.com/v1），会自动补 https://。" +
                        "Key 仅保存在本机应用私有目录（SharedPreferences），不内置任何 Key。" +
                        "AI 请求会直接发往上面填写的 Base URL，请确认你信任该服务商。" +
                        "http 仅支持本机/局域网地址；公网请使用 https。",
                )
                Spacer(Modifier.height(LpSpacing.md))
                LpPrimaryButton(
                    text = "保存并立即生效",
                    onClick = vm::saveConfig,
                    enabled = !state.busy,
                )
            }

            // ---- 每日推送 ----
            LpCard {
                LpSectionTitle("每日推送")
                Spacer(Modifier.height(LpSpacing.sm))
                LpHint(
                    "每天两次固定提醒：每日一句 + 生活 / 学习 / 工作提醒，晚间换成复盘提醒。" +
                        "内容全部在本地生成，不联网、不消耗 AI 额度；修改后立即生效。",
                )
                Spacer(Modifier.height(LpSpacing.md))

                PushTimeRow(
                    title = "早间能量",
                    subtitle = "带着今天的提醒开始",
                    enabled = state.pushConfig.morningEnabled,
                    onEnabledChange = { on ->
                        vm.onPushMorningEnabled(on)
                        if (on) ensureNotificationPermission()
                    },
                    hour = state.pushConfig.morningHour,
                    minute = state.pushConfig.morningMinute,
                    onTimeClick = { showMorningPicker = true },
                )
                Spacer(Modifier.height(LpSpacing.md))
                PushTimeRow(
                    title = "晚间复盘",
                    subtitle = "回顾今天，定下明天的第一步",
                    enabled = state.pushConfig.eveningEnabled,
                    onEnabledChange = { on ->
                        vm.onPushEveningEnabled(on)
                        if (on) ensureNotificationPermission()
                    },
                    hour = state.pushConfig.eveningHour,
                    minute = state.pushConfig.eveningMinute,
                    onTimeClick = { showEveningPicker = true },
                )

                // 权限引导：精确说明“为什么收不到”，并给一个能直接点的出口，
                // 而不是只写一句“请检查权限”。
                if (!notificationsGranted) {
                    Spacer(Modifier.height(LpSpacing.md))
                    LpHint(
                        "系统通知权限未开启，推送不会被显示。允许后无需重启应用，返回本页即生效。",
                        warn = true,
                    )
                    Spacer(Modifier.height(LpSpacing.sm))
                    SecondaryButton(
                        text = "去允许通知",
                        onClick = {
                            runCatching {
                                context.startActivity(PushPermissions.notificationSettingsIntent(context))
                            }
                        },
                    )
                }

                if (!exactAlarmsGranted) {
                    Spacer(Modifier.height(LpSpacing.md))
                    LpHint(
                        "系统未允许「闹钟与提醒」，推送可能延迟几分钟。允许后可按设定时间准点提醒。",
                        warn = true,
                    )
                    Spacer(Modifier.height(LpSpacing.sm))
                    SecondaryButton(
                        text = "允许精确提醒",
                        onClick = {
                            runCatching {
                                PushPermissions.exactAlarmSettingsIntent(context)?.let { context.startActivity(it) }
                            }
                        },
                    )
                }

                Spacer(Modifier.height(LpSpacing.md))
                SecondaryButton(
                    text = "发送测试通知",
                    onClick = vm::sendTestPush,
                    enabled = !state.busy,
                )
            }

            // ---- 版本与更新 ----
            LpCard {
                LpSectionTitle("关于")
                Spacer(Modifier.height(LpSpacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "人生智能规划 v" + versionName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LpColors.TextPrimary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "新版本发布后，打开应用会自动提示更新",
                            style = MaterialTheme.typography.bodySmall,
                            color = LpColors.TextMuted,
                        )
                    }
                    SecondaryButton(
                        text = if (state.updateChecking) "检查中…" else "检查更新",
                        onClick = vm::checkUpdate,
                        enabled = !state.updateChecking && !state.busy,
                    )
                }
                state.updateMessage?.let { message ->
                    Spacer(Modifier.height(LpSpacing.sm))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = LpColors.TextMuted,
                    )
                }

                Spacer(Modifier.height(LpSpacing.md))
                LpDivider()
                Spacer(Modifier.height(LpSpacing.sm))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openUrl(context, UpdateChecker.REPO_URL) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "开源仓库",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LpColors.TextPrimary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = UpdateChecker.REPO_URL,
                            style = MaterialTheme.typography.bodySmall,
                            color = LpColors.TextMuted,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = "↗",
                        style = MaterialTheme.typography.titleMedium,
                        color = LpColors.Accent,
                    )
                }
            }

            // ---- 导入导出 ----
            LpCard {
                LpSectionTitle("导入 / 导出")
                Spacer(Modifier.height(LpSpacing.md))
                LpHint(
                    "导出会把档案、技能、目标、任务、每日记录和 AI 历史写入你选择的 JSON 文件（UTF-8）。",
                )
                Spacer(Modifier.height(LpSpacing.sm))
                LpHint(
                    "导入采用整体替换：先清空本机全部数据再写入。导入过程不是事务性的，" +
                        "中途失败可能留下部分数据，建议先导出备份。",
                    warn = true,
                )
                Spacer(Modifier.height(LpSpacing.md))
                SecondaryButton(
                    text = "导出备份（.json）",
                    onClick = {
                        exportLauncher.launch("life-planner-backup-${Dates.today()}.json")
                    },
                    enabled = !state.busy,
                )
                Spacer(Modifier.height(LpSpacing.sm))
                SecondaryButton(
                    text = "从备份文件导入",
                    onClick = {
                        importLauncher.launch(
                            arrayOf("application/json", "text/plain", "application/octet-stream"),
                        )
                    },
                    enabled = !state.busy,
                )

                state.importSummary?.let { summary ->
                    Spacer(Modifier.height(LpSpacing.md))
                    LpDivider()
                    Spacer(Modifier.height(LpSpacing.sm))
                    Text(
                        text = "上次导入结果",
                        style = MaterialTheme.typography.titleMedium,
                        color = LpColors.TextPrimary,
                    )
                    Spacer(Modifier.height(LpSpacing.xs))
                    Text(
                        text = buildString {
                            append(if (summary.profileImported) "档案已导入；" else "档案：无；")
                            append("技能 ${summary.skills} 条；")
                            append("目标 ${summary.goals} 个；")
                            append("任务 ${summary.tasks} 条；")
                            append("每日记录 ${summary.dailyLogs} 条；")
                            append("AI 历史 ${summary.aiMessages} 条")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = LpColors.TextSecondary,
                    )
                }
            }

            // ---- 清空 AI 历史 ----
            LpCard {
                LpSectionTitle("AI 历史")
                Spacer(Modifier.height(LpSpacing.md))
                Text(
                    text = "清空后无法恢复；已通过导出保存的备份不受影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = LpColors.TextSecondary,
                )
                Spacer(Modifier.height(LpSpacing.md))
                SecondaryButton(
                    text = "清空 AI 历史记录",
                    onClick = { showClearConfirm = true },
                    enabled = !state.busy,
                    danger = true,
                )
            }

            // ---- 关于 / 隐私 ----
            LpCard {
                LpSectionTitle("关于与隐私")
                Spacer(Modifier.height(LpSpacing.sm))
                Text(
                    text = "人生智能编排体  v$versionName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LpColors.TextSecondary,
                )
                Spacer(Modifier.height(LpSpacing.md))
                LpHint(
                    "全部数据（档案、目标、任务、每日记录、AI 历史）都保存在本机应用私有目录，" +
                        "不会上传到开发者服务器。导出的备份文件由你自行保管。",
                )
                Spacer(Modifier.height(LpSpacing.sm))
                LpHint(
                    "使用 AI 功能时，你的问题、目标、记录以及健康信息等上下文会发送给" +
                        "你在设置中配置的模型服务商（Base URL 指向的服务）。请勿填写你不信任的地址。",
                )
                Spacer(Modifier.height(LpSpacing.sm))
                LpHint(
                    "AI 输出仅供自我管理参考，不构成医疗、法律或投资建议。" +
                        "如有严重健康或心理问题，请及时就医。",
                    warn = true,
                )
                Spacer(Modifier.height(LpSpacing.md))
                Text(
                    text = "心理危机支持",
                    style = MaterialTheme.typography.titleMedium,
                    color = LpColors.TextPrimary,
                )
                Spacer(Modifier.height(LpSpacing.xs))
                Text(
                    text = "如果你或身边的人出现自伤、自杀念头，请立即联系可信任的人或专业人员：\n" +
                        "全国希望 24 小时热线：400-161-9995\n" +
                        "北京心理危机干预中心：010-82951332\n" +
                        "青少年心理援助热线：12355\n" +
                        "紧急情况：110 / 120",
                    style = MaterialTheme.typography.bodySmall,
                    color = LpColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text("API Key") },
        placeholder = { Text("sk-...", color = LpColors.TextMuted) },
        singleLine = true,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            TextButton(onClick = onToggleVisible, enabled = enabled) {
                Text(
                    text = if (visible) "隐藏" else "显示",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) LpColors.Accent else LpColors.TextMuted,
                )
            }
        },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = LpColors.TextPrimary,
            unfocusedTextColor = LpColors.TextPrimary,
            disabledTextColor = LpColors.TextMuted,
            focusedBorderColor = LpColors.Accent,
            unfocusedBorderColor = LpColors.Border,
            disabledBorderColor = LpColors.Border,
            focusedLabelColor = LpColors.Accent,
            unfocusedLabelColor = LpColors.TextMuted,
            disabledLabelColor = LpColors.TextMuted,
            cursorColor = LpColors.Accent,
        ),
    )
}

@Composable
private fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val color = if (danger) LpColors.Danger else LpColors.Accent
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) color.copy(alpha = 0.6f) else LpColors.Border,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = color,
            disabledContentColor = LpColors.TextMuted,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** 一行推送设置：开关 + 可点击的时间。时间按钮在关闭时禁用，避免误以为改时间就能收到推送。 */
@Composable
private fun PushTimeRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    hour: Int,
    minute: Int,
    onTimeClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = LpColors.TextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = LpColors.TextMuted,
            )
        }
        TextButton(onClick = onTimeClick, enabled = enabled) {
            Text(
                text = formatPushTime(hour, minute),
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) LpColors.Accent else LpColors.TextMuted,
            )
        }
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
}

/** 07:30 这种展示格式；用补零保证时间列宽度稳定，不会因为 9:05 左右跳。 */
private fun formatPushTime(hour: Int, minute: Int): String =
    "%02d:%02d".format(hour.coerceIn(0, 23), minute.coerceIn(0, 59))

/**
 * 时间选择对话框。
 *
 * Material3 没有现成的 TimePicker 对话框，这里用 Dialog + Surface 包一层；
 * 平台的默认对话框宽度比时钟表盘窄，会把表盘裁掉，所以关掉 usePlatformDefaultWidth
 * 并只留 16dp 外边距。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LpTimePickerDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
) {
    val pickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true,
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = LpColors.Surface,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = LpColors.TextPrimary,
                )
                Spacer(Modifier.height(LpSpacing.md))
                TimePicker(state = pickerState)
                Spacer(Modifier.height(LpSpacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = LpColors.TextMuted)
                    }
                    TextButton(onClick = { onConfirm(pickerState.hour, pickerState.minute) }) {
                        Text("确定", color = LpColors.Accent)
                    }
                }
            }
        }
    }
}