package com.plwd.audiochannelguard

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
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
    var showAbout by remember { mutableStateOf(false) }
    var showPermissionGuide by remember { mutableStateOf(false) }
    var showPermissionWarning by remember { mutableStateOf(false) }
    var showFixLogDialog by remember { mutableStateOf(false) }
    var tileAdded by remember { mutableStateOf(AudioGuardApp.isTileAdded(context)) }
    val activity = context as? ComponentActivity
    val contentScrollState = rememberScrollState()

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

    DisposableEffect(activity) {
        if (activity == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    showPermissionWarning = PermissionChecker.checkAllPermissions(context).any { !it.isGranted }
                    tileAdded = AudioGuardApp.isTileAdded(context)
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
        AboutDialog(onDismiss = { showAbout = false })
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
            // 权限警告条
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
            val toggleDesc = "启用守护。开启并配置好权限后，放到后台即可自动守护。即使后台被清理，也会自动恢复运行"
            val guardToggleAction = {
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = toggleDesc
                        role = Role.Switch
                        toggleableState = ToggleableState(serviceRunning)
                        stateDescription = if (serviceRunning) "已开启" else "已关闭"
                    }
                    .toggleable(
                        value = serviceRunning,
                        role = Role.Switch,
                        onValueChange = { guardToggleAction() }
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("启用守护", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "开启并配置好权限后，放到后台即可自动守护。即使后台被清理，也会自动恢复运行",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = serviceRunning,
                    onCheckedChange = null
                )
            }

            val enhancedToggleDesc = "增强守护（实验性）。一般情况下无需开启。仅当普通守护无法解决问题时尝试，开启后可能影响外放和通话音量行为"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = enhancedToggleDesc
                        role = Role.Switch
                        toggleableState = ToggleableState(enhancedEnabled)
                        stateDescription = if (enhancedEnabled) "已开启" else "已关闭"
                    }
                    .toggleable(
                        value = enhancedEnabled,
                        role = Role.Switch,
                        onValueChange = { enabled ->
                            enhancedEnabled = enabled
                            AudioGuardApp.setEnhancedModeEnabled(context, enabled)
                            AudioGuardService.getMonitor()?.setEnhancedModeEnabled(enabled)
                            scope.launch {
                                delay(300)
                                refreshState()
                            }
                        }
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("增强守护（实验性）", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "一般情况下无需开启。仅当普通守护无法解决问题时尝试，开启后可能影响外放和通话音量行为",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enhancedEnabled,
                    onCheckedChange = null
                )
            }

            val classicBluetoothWidebandToggleDesc =
                "经典蓝牙更清晰通话音质（实验性）。仅对经典蓝牙耳机生效。修复通信路由后，会尝试争取更清晰的通话音质；不等于音乐播放音质，部分机型可能无效"
            val classicBluetoothSoftGuardToggleDesc =
                "经典蓝牙保真守护（实验性）。仅对经典蓝牙耳机生效。检测到疑似劫持或手动解除接管时，会短时用静默无障碍音频确认真实出声设备，尽量减少误判和锁屏干扰"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = classicBluetoothSoftGuardToggleDesc
                        role = Role.Switch
                        toggleableState = ToggleableState(classicBluetoothSoftGuardEnabled)
                        stateDescription = if (classicBluetoothSoftGuardEnabled) "已开启" else "已关闭"
                    }
                    .toggleable(
                        value = classicBluetoothSoftGuardEnabled,
                        role = Role.Switch,
                        onValueChange = { enabled ->
                            classicBluetoothSoftGuardEnabled = enabled
                            AudioGuardApp.setClassicBluetoothSoftGuardEnabled(context, enabled)
                            AudioGuardService.getMonitor()?.setClassicBluetoothSoftGuardEnabled(enabled)
                            scope.launch {
                                delay(300)
                                refreshState()
                            }
                        }
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("经典蓝牙保真守护（实验性）", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "仅对经典蓝牙耳机生效。检测到疑似劫持或手动解除接管时，会短时用静默无障碍音频确认真实出声设备，尽量减少误判和锁屏干扰",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = classicBluetoothSoftGuardEnabled,
                    onCheckedChange = null
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = classicBluetoothWidebandToggleDesc
                        role = Role.Switch
                        toggleableState = ToggleableState(classicBluetoothWidebandEnabled)
                        stateDescription = if (classicBluetoothWidebandEnabled) "已开启" else "已关闭"
                    }
                    .toggleable(
                        value = classicBluetoothWidebandEnabled,
                        role = Role.Switch,
                        onValueChange = { enabled ->
                            classicBluetoothWidebandEnabled = enabled
                            AudioGuardApp.setClassicBluetoothWidebandEnabled(context, enabled)
                            AudioGuardService.getMonitor()?.setClassicBluetoothWidebandEnabled(enabled)
                            scope.launch {
                                delay(300)
                                refreshState()
                            }
                        }
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("经典蓝牙更清晰通话音质（实验性）", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "仅对经典蓝牙耳机生效。修复通信路由后，会尝试争取更清晰的通话音质；不等于音乐播放音质，部分机型可能无效",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = classicBluetoothWidebandEnabled,
                    onCheckedChange = null
                )
            }

            HorizontalDivider()

            val statusText = when (status) {
                GuardStatus.NORMAL -> "正常"
                GuardStatus.FIXED -> "已修复"
                GuardStatus.FIXED_BUT_SPEAKER_ROUTE -> "已修复（其他应用可能仍占用扬声器路由）"
                GuardStatus.HIJACKED -> "待修复"
                GuardStatus.NO_HEADSET -> "无耳机"
            }
            Text("当前状态：$statusText", style = MaterialTheme.typography.bodyLarge)
            if (status == GuardStatus.FIXED_BUT_SPEAKER_ROUTE) {
                Text(
                    "如使用正常请忽略，如仍有异常请点击手动触发",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("增强状态：$enhancedStateText", style = MaterialTheme.typography.bodyLarge)
            Text("输出设备：$headsetName", style = MaterialTheme.typography.bodyLarge)
            Text("通信设备：$commDeviceName", style = MaterialTheme.typography.bodyLarge)
            heldRouteMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (canManualReleaseHeldRoute) {
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
                    Text("尝试解除限制")
                }
            }

            Button(
                onClick = {
                    AudioGuardService.getMonitor()?.fixNow()
                    refreshState()
                },
                enabled = serviceRunning
            ) {
                Text("手动触发")
            }
            Text(
                "通常无需手动操作，仅在声道未自动恢复时点击",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            OutlinedButton(
                onClick = { showFixLogDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("查看日志")
            }

            // 快捷设置磁贴
            if (tileAdded) {
                Text(
                    "已添加控制中心磁贴，可在通知栏快捷设置中点击「声道修复」快速恢复",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                OutlinedButton(
                    onClick = {
                        val statusBarManager = context.getSystemService(StatusBarManager::class.java)
                        statusBarManager.requestAddTileService(
                            ComponentName(context, AudioFixTile::class.java),
                            context.getString(R.string.tile_label),
                            Icon.createWithResource(context, R.drawable.ic_headset),
                            context.mainExecutor
                        ) { resultCode ->
                            if (resultCode == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                                tileAdded = true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("添加控制中心磁贴")
                }
                Text(
                    "添加后可在通知栏快捷设置中点击「声道修复」快速恢复，无需打开应用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 权限设置按钮
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

private fun enhancedStateToText(state: EnhancedState): String {
    return when (state) {
        EnhancedState.DISABLED -> "已关闭"
        EnhancedState.WAITING_HEADSET -> "等待耳机"
        EnhancedState.ACTIVE -> "增强中"
        EnhancedState.CLEAR_PROBE -> "观察中"
        EnhancedState.SUSPENDED_BY_CALL -> "通话暂停"
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
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
                    "导致通信音频流被锁定在内置扬声器。TalkBack 等屏幕阅读器的语音输出因此被劫持，" +
                    "严重影响依赖耳机的视障用户。"
                )

                Text("工作原理", style = MaterialTheme.typography.titleSmall)
                Text(
                    "通过 OnCommunicationDeviceChangedListener 实时监听通信设备变更，" +
                    "当检测到通信设备被异常切换至内置扬声器且有耳机连接时，" +
                    "自动调用 setCommunicationDevice() 将音频路由恢复至耳机。\n\n" +
                    "支持设备类型：USB 耳机、有线耳机/耳麦、蓝牙 A2DP/SCO、BLE 音频设备。"
                )

                HorizontalDivider()

                Text("制作信息", style = MaterialTheme.typography.titleSmall)
                Text(
                    "本软件由平行世界plwd与AI编程软件共同制作\n" +
                    "测试设备：Redmi K80 至尊版"
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
                        context.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/plwd2022/a11y-audio-guard")))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("GitHub 开源仓库")
                }

                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://qun.qq.com/universal-share/share?ac=1&authKey=sdEDI3L1gxwftS%2Faw0L%2FSZLavRZ0bNvqtz3UXYzNgRSSXgK%2FKNJZAYRxHmGKp2Pi&busi_data=eyJncm91cENvZGUiOiIxMDMxNTY2MzEwIiwidG9rZW4iOiI3OCtzMnlrbld6K3V0WUZocVdmTjRhZnFxR3J2bk1ybkJvMEFaQ3RjN1lINXo1azA0U01MSFhyaFhhVE1MS0FPIiwidWluIjoiMjg0MTkwNTI2NSJ9&data=vC3VZm0hSM3jcAOYSLpTZLieWHPLyHSNFnv90CtY_Z4je4_eE3XfSiYiz0bYXfzMQuTyRjIRLhgyfCQptV-ARg&svctype=4&tempid=h5_group_info")))
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
        title = { Text("修复记录") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (fixLog.isEmpty()) {
                    Text(
                        "暂无记录",
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
