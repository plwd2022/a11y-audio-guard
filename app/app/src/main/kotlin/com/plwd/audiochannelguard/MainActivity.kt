package com.plwd.audiochannelguard

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MaterialTheme {
                AudioGuardScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioGuardScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var serviceRunning by remember { mutableStateOf(AudioGuardService.isRunning()) }
    var status by remember { mutableStateOf(GuardStatus.NO_HEADSET) }
    var fixLog by remember { mutableStateOf<List<FixEvent>>(emptyList()) }
    var enhancedEnabled by remember { mutableStateOf(AudioGuardApp.isEnhancedModeEnabled(context)) }
    var classicBluetoothSoftGuardEnabled by remember {
        mutableStateOf(AudioGuardApp.isClassicBluetoothSoftGuardEnabled(context))
    }
    var classicBluetoothWidebandEnabled by remember {
        mutableStateOf(AudioGuardApp.isClassicBluetoothWidebandEnabled(context))
    }
    var enhancedStateText by remember {
        mutableStateOf(if (enhancedEnabled) "待启动" else "已关闭")
    }
    var commDeviceName by remember { mutableStateOf("无") }
    var headsetName by remember { mutableStateOf("无") }
    var heldRouteMessage by remember { mutableStateOf<String?>(null) }
    var canManualReleaseHeldRoute by remember { mutableStateOf(false) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var isStartingBuiltinDownload by remember { mutableStateOf(false) }
    var updateCheckResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var pendingAutoUpdateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateActionMessage by remember { mutableStateOf<String?>(null) }
    var showInstallPermissionDialog by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showPermissionGuide by remember { mutableStateOf(false) }
    var showPermissionWarning by remember { mutableStateOf(false) }
    var showFixLogDialog by remember { mutableStateOf(false) }
    var hasScheduledAutoCheck by remember { mutableStateOf(false) }
    var tileAdded by remember { mutableStateOf(AudioGuardApp.isTileAdded(context)) }
    val activity = context as? ComponentActivity
    val contentScrollState = rememberScrollState()
    val currentVersionName = remember(context) { getInstalledVersionName(context) }
    val updateChecker = remember(context) { UpdateChecker(context.applicationContext) }
    val updateManager = remember(context) { AppUpdateManager.getInstance(context.applicationContext) }
    val updateDownloadState by updateManager.downloadState.collectAsState()

    // 检查是否需要显示权限引导
    LaunchedEffect(Unit) {
        val allStatus = PermissionChecker.checkAllPermissions(context)
        val hasMissing = allStatus.any { !it.isGranted }
        showPermissionWarning = hasMissing
        if (hasMissing) {
            showPermissionGuide = true
        }
    }

    val refreshState: () -> Unit = {
        serviceRunning = AudioGuardService.isRunning()
        enhancedEnabled = AudioGuardApp.isEnhancedModeEnabled(context)
        classicBluetoothSoftGuardEnabled = AudioGuardApp.isClassicBluetoothSoftGuardEnabled(context)
        classicBluetoothWidebandEnabled = AudioGuardApp.isClassicBluetoothWidebandEnabled(context)
        val monitor = AudioGuardService.getMonitor()
        if (monitor != null) {
            status = monitor.getStatus()
            fixLog = monitor.fixLog
            enhancedStateText = enhancedStateToText(monitor.getEnhancedState())
            commDeviceName = monitor.getCommunicationDevice()?.productName?.toString() ?: "无"
            headsetName = monitor.findConnectedHeadset()?.productName?.toString() ?: "未连接"
            heldRouteMessage = monitor.getHeldRouteMessage()
            canManualReleaseHeldRoute = monitor.canManuallyReleaseHeldRoute()
        } else {
            status = GuardStatus.NO_HEADSET
            fixLog = emptyList()
            enhancedStateText = if (enhancedEnabled) "待启动" else "已关闭"
            commDeviceName = "无"
            headsetName = "未连接"
            heldRouteMessage = null
            canManualReleaseHeldRoute = false
        }
    }

    fun probeExistingTileIfNeeded() {
        if (tileAdded) return
        AudioFixTile.requestTileRefresh(context)
        scope.launch {
            delay(350L)
            tileAdded = AudioGuardApp.isTileAdded(context)
        }
    }

    LaunchedEffect(Unit) {
        probeExistingTileIfNeeded()
    }

    fun canShowAutoUpdateDialog(): Boolean {
        return updateCheckResult == null &&
            updateActionMessage == null &&
            !showInstallPermissionDialog &&
            !showAbout &&
            !showPermissionGuide &&
            !showFixLogDialog
    }

    fun showAutoUpdateDialogIfPossible(updateInfo: UpdateInfo) {
        if (!updateChecker.shouldPromptAutoUpdate(updateInfo.latestVersionName)) {
            pendingAutoUpdateInfo = null
            return
        }
        if (updateDownloadState !is UpdateDownloadState.Idle ||
            updateCheckResult != null ||
            updateActionMessage != null ||
            showInstallPermissionDialog
        ) {
            pendingAutoUpdateInfo = null
            return
        }
        if (showAbout || showPermissionGuide || showFixLogDialog) {
            pendingAutoUpdateInfo = updateInfo
            return
        }

        updateChecker.markAutoUpdatePrompted(updateInfo.latestVersionName)
        pendingAutoUpdateInfo = null
        updateCheckResult = UpdateCheckResult.HasUpdate(updateInfo)
    }

    fun launchUpdateCheck(isAutomatic: Boolean = false) {
        if (isCheckingUpdate) return
        scope.launch {
            if (isAutomatic && !updateChecker.shouldAutoCheckNow()) {
                return@launch
            }
            isCheckingUpdate = true
            try {
                val result = updateChecker.checkForUpdates()
                if (isAutomatic) {
                    if (result !is UpdateCheckResult.NoNetwork) {
                        updateChecker.recordAutoCheckAttempt()
                    }
                    if (result is UpdateCheckResult.HasUpdate) {
                        showAutoUpdateDialogIfPossible(result.updateInfo)
                    }
                } else {
                    updateCheckResult = result
                }
            } finally {
                isCheckingUpdate = false
            }
        }
    }

    fun openUpdateUrl(url: String) {
        if (!openExternalUrl(context, url)) {
            updateCheckResult = UpdateCheckResult.Error("无法打开浏览器，请稍后重试")
        }
    }

    fun startBuiltInDownload(updateInfo: UpdateInfo) {
        if (isStartingBuiltinDownload) return
        scope.launch {
            isStartingBuiltinDownload = true
            try {
                updateManager.startDownload(updateInfo)
                updateCheckResult = null
            } catch (exception: Exception) {
                updateActionMessage = "启动下载失败: ${exception.message}"
            } finally {
                isStartingBuiltinDownload = false
            }
        }
    }

    fun retryBuiltInDownload() {
        if (isStartingBuiltinDownload) return
        scope.launch {
            isStartingBuiltinDownload = true
            try {
                if (!updateManager.retryDownload()) {
                    updateActionMessage = "当前没有可重试的下载任务"
                }
            } catch (exception: Exception) {
                updateActionMessage = "重试下载失败: ${exception.message}"
            } finally {
                isStartingBuiltinDownload = false
            }
        }
    }

    fun cancelBuiltInDownload() {
        scope.launch {
            updateManager.cancelDownload()
        }
    }

    fun installBuiltInDownloadedPackage() {
        scope.launch {
            when (val result = updateManager.installDownloadedApk()) {
                UpdateInstallResult.Started -> {
                    updateActionMessage = "已打开系统安装界面。安装成功后，旧安装包会自动清理。"
                }

                UpdateInstallResult.PermissionRequired -> {
                    showInstallPermissionDialog = true
                }

                is UpdateInstallResult.Error -> {
                    updateActionMessage = result.message
                }
            }
        }
    }

    LaunchedEffect(updateDownloadState) {
        if (hasScheduledAutoCheck) return@LaunchedEffect
        if (updateDownloadState !is UpdateDownloadState.Idle) return@LaunchedEffect

        hasScheduledAutoCheck = true
        delay(1500L)
        launchUpdateCheck(isAutomatic = true)
    }

    LaunchedEffect(
        pendingAutoUpdateInfo,
        updateDownloadState,
        updateCheckResult,
        updateActionMessage,
        showInstallPermissionDialog,
        showAbout,
        showPermissionGuide,
        showFixLogDialog
    ) {
        val pendingUpdateInfo = pendingAutoUpdateInfo ?: return@LaunchedEffect
        if (updateDownloadState !is UpdateDownloadState.Idle) return@LaunchedEffect
        if (!canShowAutoUpdateDialog()) return@LaunchedEffect
        showAutoUpdateDialogIfPossible(pendingUpdateInfo)
    }

    DisposableEffect(activity) {
        if (activity == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    showPermissionWarning = PermissionChecker.checkAllPermissions(context).any { !it.isGranted }
                    tileAdded = AudioGuardApp.isTileAdded(context)
                    probeExistingTileIfNeeded()
                    refreshState()
                }
            }
            activity.lifecycle.addObserver(observer)
            onDispose {
                activity.lifecycle.removeObserver(observer)
            }
        }
    }

    DisposableEffect(Unit) {
        refreshState()
        val statusListener: (GuardStatus) -> Unit = { _ -> refreshState() }
        val fixLogListener: () -> Unit = { refreshState() }
        val enhancedStateListener: (EnhancedState) -> Unit = { refreshState() }
        val listener = object : AudioGuardService.OnServiceRebindListener {
            override fun onRebind(monitor: AudioRouteMonitor) {
                monitor.addStatusListener(statusListener)
                monitor.addFixLogListener(fixLogListener)
                monitor.addEnhancedStateListener(enhancedStateListener)
            }
        }
        val monitor = AudioGuardService.getMonitor()
        monitor?.let { listener.onRebind(it) }
        AudioGuardService.addRebindListener(listener)
        onDispose {
            AudioGuardService.removeRebindListener(listener)
            AudioGuardService.getMonitor()?.removeStatusListener(statusListener)
            AudioGuardService.getMonitor()?.removeFixLogListener(fixLogListener)
            AudioGuardService.getMonitor()?.removeEnhancedStateListener(enhancedStateListener)
        }
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    if (showAbout) {
        AboutDialog(
            currentVersionName = currentVersionName,
            onDismiss = { showAbout = false }
        )
    }

    if (showFixLogDialog) {
        FixLogDialog(
            fixLog = fixLog,
            timeFormat = timeFormat,
            onDismiss = { showFixLogDialog = false }
        )
    }
    
    if (showPermissionGuide) {
        PermissionGuideDialog(
            onDismiss = { showPermissionGuide = false },
            onAllPermissionsGranted = { showPermissionGuide = false }
        )
    }

    if (showInstallPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showInstallPermissionDialog = false },
            title = { Text("需要安装权限") },
            text = {
                Text("请先允许本应用安装未知来源应用，授权后再回到这里点击“立即安装”。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showInstallPermissionDialog = false
                        context.startActivity(updateManager.createInstallPermissionSettingsIntent())
                    }
                ) {
                    Text("去设置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInstallPermissionDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    updateActionMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { updateActionMessage = null },
            title = { Text("更新提示") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { updateActionMessage = null }) {
                    Text("关闭")
                }
            }
        )
    }

    updateCheckResult?.let { result ->
        UpdateCheckDialog(
            result = result,
            isStartingBuiltinDownload = isStartingBuiltinDownload,
            onDismiss = { updateCheckResult = null },
            onOpenUrl = { url ->
                updateCheckResult = null
                openUpdateUrl(url)
            },
            onStartBuiltInDownload = { updateInfo ->
                startBuiltInDownload(updateInfo)
            }
        )
    }

    val guardToggleAction: () -> Unit = {
        val enabled = !serviceRunning
        serviceRunning = enabled
        AudioGuardApp.setGuardEnabled(context, enabled)
        if (enabled) {
            AudioGuardService.start(context)
            ServiceGuard.schedulePeriodicCheck(context)
        } else {
            AudioGuardService.stop(context)
        }
        scope.launch {
            delay(500)
            refreshState()
        }
    }
    val statusTitle = guardStatusTitle(serviceRunning, status)
    val statusSummary = guardStatusSummary(serviceRunning, status, headsetName)
    val showQuickFixAction = serviceRunning &&
        (status == GuardStatus.HIJACKED || status == GuardStatus.FIXED_BUT_SPEAKER_ROUTE)
    val showTilePrompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !tileAdded
    val runtimeHintMessage = if (canManualReleaseHeldRoute) null else heldRouteMessage
    val toolsSubtitle = if (tileAdded) {
        "控制中心磁贴已添加，可在通知栏快捷开启或关闭保护。"
    } else {
        null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PermissionWarningBar(
                showWarning = showPermissionWarning,
                onClick = { showPermissionGuide = true }
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(contentScrollState)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusSummaryCard(
                    serviceRunning = serviceRunning,
                    statusTitle = statusTitle,
                    statusSummary = statusSummary,
                    showQuickFixAction = showQuickFixAction,
                    onToggle = guardToggleAction,
                    onQuickFix = {
                        AudioGuardService.getMonitor()?.fixNow()
                        refreshState()
                    }
                )

                if (showTilePrompt) {
                    SectionSurface(
                        title = "控制中心磁贴"
                    ) {
                        MergedTextBlock {
                            Text(
                                "还没有添加控制中心磁贴。添加后可在通知栏快捷开启或关闭保护，无需打开应用。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                val statusBarManager = context.getSystemService(StatusBarManager::class.java)
                                statusBarManager.requestAddTileService(
                                    ComponentName(context, AudioFixTile::class.java),
                                    context.getString(R.string.tile_label),
                                    Icon.createWithResource(context, R.drawable.ic_headset),
                                    context.mainExecutor
                                ) { resultCode ->
                                    if (resultCode == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                                        resultCode == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                                    ) {
                                        tileAdded = true
                                        AudioGuardApp.setTileAdded(context, true)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("添加控制中心磁贴")
                        }
                    }
                }

                if (serviceRunning) {
                    SectionSurface(
                        title = "当前情况"
                    ) {
                        MergedTextBlock(
                            modifier = Modifier.fillMaxWidth(),
                            verticalSpacing = 6.dp
                        ) {
                            Text(
                                "当前耳机：$headsetName",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "增强保护：$enhancedStateText",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "系统当前设备：$commDeviceName",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            runtimeHintMessage?.let { message ->
                                Text(
                                    message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    if (canManualReleaseHeldRoute) {
                        SectionSurface(
                            title = "进一步处理",
                            subtitle = "仅在自动恢复不理想时再尝试"
                        ) {
                            heldRouteMessage?.let { message ->
                                MergedTextBlock {
                                    Text(
                                        message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    AudioGuardService.requestReleaseHeldRoute(context)
                                    scope.launch {
                                        delay(700)
                                        refreshState()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("尝试解除外放占用")
                            }
                        }
                    }
                }

                SectionSurface(
                    title = "高级与实验功能",
                    subtitle = "一般情况下无需调整，只有普通保护不够时再尝试"
                ) {
                    SettingsToggleRow(
                        checked = enhancedEnabled,
                        title = "增强保护（实验性）",
                        summary = "开启后会更主动地接管可能影响读屏播报的声音路径，尽量不让其他应用抢走。这样通常更稳，但可能影响外放、部分语音场景，并可能造成蓝牙音质持续处于窄带音质或者出现音质损失",
                        onToggle = { enabled ->
                            enhancedEnabled = enabled
                            AudioGuardApp.setEnhancedModeEnabled(context, enabled)
                            AudioGuardService.getMonitor()?.setEnhancedModeEnabled(enabled)
                            scope.launch {
                                delay(300)
                                refreshState()
                            }
                        }
                    )

                    HorizontalDivider()

                    SettingsToggleRow(
                        checked = classicBluetoothSoftGuardEnabled,
                        title = "经典蓝牙保真保护（实验性）",
                        summary = "仅对经典蓝牙耳机生效。会短时用静默无障碍音频确认真实出声设备，尽量减少误判和锁屏干扰",
                        onToggle = { enabled ->
                            classicBluetoothSoftGuardEnabled = enabled
                            AudioGuardApp.setClassicBluetoothSoftGuardEnabled(context, enabled)
                            AudioGuardService.getMonitor()?.setClassicBluetoothSoftGuardEnabled(enabled)
                            scope.launch {
                                delay(300)
                                refreshState()
                            }
                        }
                    )

                    HorizontalDivider()

                    SettingsToggleRow(
                        checked = classicBluetoothWidebandEnabled,
                        title = "经典蓝牙更清晰通话音质（实验性）",
                        summary = "仅对经典蓝牙耳机生效。第三方应用不释放蓝牙占用时，为了持续保护读屏声音，系统可能退到通话音质。开启后会尽量让这段通话音质更清晰；解除持续保护后会恢复正常",
                        onToggle = { enabled ->
                            classicBluetoothWidebandEnabled = enabled
                            AudioGuardApp.setClassicBluetoothWidebandEnabled(context, enabled)
                            AudioGuardService.getMonitor()?.setClassicBluetoothWidebandEnabled(enabled)
                            scope.launch {
                                delay(300)
                                refreshState()
                            }
                        }
                    )
                }

                SectionSurface(
                    title = "工具与信息",
                    subtitle = toolsSubtitle
                ) {
                    OutlinedButton(
                        onClick = { showFixLogDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("查看保护记录")
                    }

                    OutlinedButton(
                        onClick = { launchUpdateCheck() },
                        enabled = !isCheckingUpdate,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isCheckingUpdate) "检查更新中..." else "检查更新")
                    }
                    MergedTextBlock {
                        Text(
                            "应用启动后会静默自动检查更新，发现新版本时会自动提示。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    UpdateDownloadSection(
                        state = updateDownloadState,
                        isStartingDownload = isStartingBuiltinDownload,
                        onRetry = { retryBuiltInDownload() },
                        onCancelOrClear = { cancelBuiltInDownload() },
                        onInstall = { installBuiltInDownloadedPackage() },
                        onOpenReleasePage = { url -> openUpdateUrl(url) },
                        context = context
                    )

                    OutlinedButton(
                        onClick = { showPermissionGuide = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("权限设置")
                    }

                    OutlinedButton(
                        onClick = { showAbout = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("关于本软件")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusSummaryCard(
    serviceRunning: Boolean,
    statusTitle: String,
    statusSummary: String,
    showQuickFixAction: Boolean,
    onToggle: () -> Unit,
    onQuickFix: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = serviceRunning,
                        role = Role.Switch,
                        onValueChange = { onToggle() }
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text("保护读屏声音", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "误外放时自动收回耳机",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = serviceRunning,
                    onCheckedChange = null
                )
            }

            HorizontalDivider()

            MergedTextBlock(verticalSpacing = 6.dp) {
                Text(statusTitle, style = MaterialTheme.typography.titleLarge)
                Text(
                    statusSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (showQuickFixAction) {
                Button(
                    onClick = onQuickFix,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("立即修复")
                }
            }
        }
    }
}

@Composable
private fun MergedTextBlock(
    modifier: Modifier = Modifier,
    verticalSpacing: Dp = 4.dp,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(verticalSpacing)
    ) {
        content()
    }
}

@Composable
private fun SectionSurface(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MergedTextBlock(verticalSpacing = 2.dp) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    checked: Boolean,
    title: String,
    summary: String,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onToggle
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null
        )
    }
}

private fun guardStatusTitle(serviceRunning: Boolean, status: GuardStatus): String {
    return when {
        !serviceRunning -> "保护已关闭"
        status == GuardStatus.HIJACKED -> "检测到读屏声音可能外放"
        status == GuardStatus.FIXED || status == GuardStatus.FIXED_BUT_SPEAKER_ROUTE -> "已将读屏声音收回耳机"
        status == GuardStatus.NO_HEADSET -> "当前未接入耳机"
        else -> "读屏声音正在耳机中"
    }
}

private fun guardStatusSummary(
    serviceRunning: Boolean,
    status: GuardStatus,
    headsetName: String,
): String {
    if (!serviceRunning) {
        return "开启后，如果读屏声音误外放，应用会自动把声音收回耳机。"
    }

    return when (status) {
        GuardStatus.NORMAL -> {
            if (headsetName != "无" && headsetName != "未连接") {
                "当前读屏声音正在 $headsetName 中，可继续正常使用。"
            } else {
                "保护已开启，正在后台观察读屏声音是否误外放。"
            }
        }

        GuardStatus.FIXED -> {
            if (headsetName != "无" && headsetName != "未连接") {
                "最近一次异常已经把读屏声音收回到 $headsetName。"
            } else {
                "最近一次异常已经把读屏声音收回耳机。"
            }
        }

        GuardStatus.FIXED_BUT_SPEAKER_ROUTE ->
            "读屏声音已经收回耳机，如当前听起来正常请忽略。"

        GuardStatus.HIJACKED ->
            "当前读屏声音可能仍在扬声器外放，如你现在确实听到外放，可立即修复。"

        GuardStatus.NO_HEADSET ->
            "接入耳机后会自动开始保护。"
    }
}

private fun enhancedStateToText(state: EnhancedState): String {
    return when (state) {
        EnhancedState.DISABLED -> "已关闭"
        EnhancedState.WAITING_HEADSET -> "等待耳机"
        EnhancedState.ACTIVE -> "增强保护中"
        EnhancedState.CLEAR_PROBE -> "观察中"
        EnhancedState.SUSPENDED_BY_CALL -> "通话暂停"
    }
}

@Composable
private fun AboutDialog(
    currentVersionName: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("问题背景", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Android 13 起，部分应用（如抖音、微信）在播放语音消息时" +
                    "调用 setSpeakerphoneOn(true)，播放结束后未释放，" +
                    "导致读屏语音被错误切到内置扬声器。TalkBack 等屏幕阅读器的声音因此可能误外放，" +
                    "严重影响依赖耳机的视障用户。"
                )

                Text("工作原理", style = MaterialTheme.typography.titleSmall)
                Text(
                    "通过 OnCommunicationDeviceChangedListener 实时监听通信设备变更，" +
                    "当检测到读屏声音可能被异常切换到内置扬声器且有耳机连接时，" +
                    "自动调用 setCommunicationDevice() 将读屏声音优先拉回耳机。\n\n" +
                    "支持设备类型：USB 耳机、有线耳机/耳麦、蓝牙 A2DP/SCO、BLE 音频设备。"
                )

                HorizontalDivider()

                Text("制作信息", style = MaterialTheme.typography.titleSmall)
                Text(
                    "本软件由平行世界plwd与AI编程软件共同制作\n" +
                    "测试设备：Redmi K80 至尊版\n" +
                    "当前版本：$currentVersionName"
                )

                Text("开源协议", style = MaterialTheme.typography.titleSmall)
                Text(
                    "本项目基于 MIT License 开源。" +
                    "您可以自由使用、复制、修改和分发，仅需保留版权声明。\n" +
                    "本安装包已启用签名校验，未经授权的修改将无法运行。"
                )

                HorizontalDivider()

                OutlinedButton(
                    onClick = {
                        openExternalUrl(context, "https://github.com/plwd2022/a11y-audio-guard")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("GitHub 开源仓库")
                }

                OutlinedButton(
                    onClick = {
                        openExternalUrl(
                            context,
                            "https://qun.qq.com/universal-share/share?ac=1&authKey=sdEDI3L1gxwftS%2Faw0L%2FSZLavRZ0bNvqtz3UXYzNgRSSXgK%2FKNJZAYRxHmGKp2Pi&busi_data=eyJncm91cENvZGUiOiIxMDMxNTY2MzEwIiwidG9rZW4iOiI3OCtzMnlrbld6K3V0WUZocVdmTjRhZnFxR3J2bk1ybkJvMEFaQ3RjN1lINXo1azA0U01MSFhyaFhhVE1MS0FPIiwidWluIjoiMjg0MTkwNTI2NSJ9&data=vC3VZm0hSM3jcAOYSLpTZLieWHPLyHSNFnv90CtY_Z4je4_eE3XfSiYiz0bYXfzMQuTyRjIRLhgyfCQptV-ARg&svctype=4&tempid=h5_group_info"
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("加入交流群")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun FixLogDialog(
    fixLog: List<FixEvent>,
    timeFormat: SimpleDateFormat,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保护记录") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (fixLog.isEmpty()) {
                    Text(
                        "暂时没有保护记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    for (event in fixLog) {
                        val time = timeFormat.format(Date(event.timestamp))
                        Text("$time ${event.message}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun UpdateCheckDialog(
    result: UpdateCheckResult,
    isStartingBuiltinDownload: Boolean,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onStartBuiltInDownload: (UpdateInfo) -> Unit,
) {
    val context = LocalContext.current

    when (result) {
        is UpdateCheckResult.HasUpdate -> {
            val updateInfo = result.updateInfo
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("发现新版本") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("当前版本：${updateInfo.currentVersionName}")
                        Text("最新版本：${updateInfo.latestVersionName}")
                        Text("发布名称：${updateInfo.releaseName}")
                        Text("安装包：${updateInfo.apkFileName}")
                        Text("大小：${Formatter.formatFileSize(context, updateInfo.apkSizeBytes)}")

                        HorizontalDivider()

                        Text("更新说明", style = MaterialTheme.typography.titleSmall)
                        Text(updateInfo.releaseNotes)

                        OutlinedButton(
                            onClick = { onOpenUrl(updateInfo.releaseUrl) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("查看发布页")
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { onStartBuiltInDownload(updateInfo) },
                        enabled = !isStartingBuiltinDownload
                    ) {
                        Text(if (isStartingBuiltinDownload) "准备中..." else "内置下载")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onOpenUrl(updateInfo.apkDownloadUrl) }) {
                        Text("浏览器下载")
                    }
                }
            )
        }

        is UpdateCheckResult.UpToDate -> {
            val updateInfo = result.updateInfo
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("已是最新版本") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("当前已是最新版本：${updateInfo.currentVersionName}")
                        Text("如需覆盖安装，可继续下载当前版本安装包。")

                        OutlinedButton(
                            onClick = { onOpenUrl(updateInfo.releaseUrl) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("查看发布页")
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { onStartBuiltInDownload(updateInfo) },
                        enabled = !isStartingBuiltinDownload
                    ) {
                        Text(if (isStartingBuiltinDownload) "准备中..." else "内置下载")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onOpenUrl(updateInfo.apkDownloadUrl) }) {
                        Text("浏览器下载")
                    }
                }
            )
        }

        UpdateCheckResult.NoNetwork -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("检查更新") },
                text = { Text("当前网络不可用，请连接网络后重试") },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            )
        }

        is UpdateCheckResult.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("检查更新失败") },
                text = { Text(result.message) },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            )
        }
    }
}

@Composable
private fun UpdateDownloadSection(
    state: UpdateDownloadState,
    isStartingDownload: Boolean,
    onRetry: () -> Unit,
    onCancelOrClear: () -> Unit,
    onInstall: () -> Unit,
    onOpenReleasePage: (String) -> Unit,
    context: Context,
) {
    if (state is UpdateDownloadState.Idle) return

    HorizontalDivider()

    when (state) {
        is UpdateDownloadState.Pending -> {
            MergedTextBlock(verticalSpacing = 6.dp) {
                Text("更新下载：准备中", style = MaterialTheme.typography.titleMedium)
                Text(
                    "正在准备下载 ${state.updateInfo.latestVersionName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = onCancelOrClear,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("取消下载")
            }
        }

        is UpdateDownloadState.Downloading -> {
            val percent = (state.progress * 100).toInt().coerceIn(0, 100)
            MergedTextBlock(verticalSpacing = 6.dp) {
                Text("更新下载中", style = MaterialTheme.typography.titleMedium)
                Text(
                    "版本：${state.updateInfo.latestVersionName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${formatSize(context, state.downloadedBytes)} / ${formatSizeOrUnknown(context, state.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "进度：$percent%  速度：${formatSpeed(context, state.speedBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = onCancelOrClear,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("取消下载")
            }
        }

        is UpdateDownloadState.Completed -> {
            MergedTextBlock(verticalSpacing = 6.dp) {
                Text("更新包已下载", style = MaterialTheme.typography.titleMedium)
                Text(
                    "版本：${state.updateInfo.latestVersionName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "安装包：${state.updateInfo.apkFileName}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "大小：${Formatter.formatFileSize(context, state.updateInfo.apkSizeBytes)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "安装成功后会自动清理旧安装包。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onInstall,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("立即安装")
            }
            OutlinedButton(
                onClick = { onOpenReleasePage(state.updateInfo.releaseUrl) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("查看发布页")
            }
            OutlinedButton(
                onClick = onCancelOrClear,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("删除安装包")
            }
        }

        is UpdateDownloadState.Failed -> {
            MergedTextBlock(verticalSpacing = 6.dp) {
                Text("更新下载失败", style = MaterialTheme.typography.titleMedium)
                state.updateInfo?.let { updateInfo ->
                    Text(
                        "版本：${updateInfo.latestVersionName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onRetry,
                enabled = !isStartingDownload,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isStartingDownload) "准备中..." else "重试下载")
            }
            state.updateInfo?.let { updateInfo ->
                OutlinedButton(
                    onClick = { onOpenReleasePage(updateInfo.apkDownloadUrl) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("浏览器下载")
                }
            }
            OutlinedButton(
                onClick = onCancelOrClear,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("清除记录")
            }
        }

        UpdateDownloadState.Idle -> Unit
    }
}

private fun openExternalUrl(context: Context, url: String): Boolean {
    return try {
        val chooserIntent = Intent.createChooser(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            "选择浏览器"
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooserIntent)
        true
    } catch (_: Exception) {
        false
    }
}

private fun getInstalledVersionName(context: Context): String {
    return try {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            0
        )
        packageInfo.versionName?.ifBlank { "未知版本" } ?: "未知版本"
    } catch (_: Exception) {
        "未知版本"
    }
}

private fun formatSize(context: Context, bytes: Long): String {
    return Formatter.formatFileSize(context, bytes.coerceAtLeast(0L))
}

private fun formatSizeOrUnknown(context: Context, bytes: Long): String {
    return if (bytes > 0L) {
        Formatter.formatFileSize(context, bytes)
    } else {
        "未知大小"
    }
}

private fun formatSpeed(context: Context, bytesPerSecond: Long): String {
    return if (bytesPerSecond > 0L) {
        "${Formatter.formatFileSize(context, bytesPerSecond)}/s"
    } else {
        "计算中"
    }
}
