package com.plwd.audiochannelguard

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
    var commDeviceName by remember { mutableStateOf("无") }
    var headsetName by remember { mutableStateOf("无") }
    var showAbout by remember { mutableStateOf(false) }

    val refreshState: () -> Unit = {
        serviceRunning = AudioGuardService.isRunning()
        val monitor = AudioGuardService.getMonitor()
        if (monitor != null) {
            status = monitor.getStatus()
            fixLog = monitor.fixLog
            commDeviceName = monitor.getCommunicationDevice()?.productName?.toString() ?: "无"
            headsetName = monitor.findConnectedHeadset()?.productName?.toString() ?: "未连接"
        } else {
            status = GuardStatus.NO_HEADSET
            fixLog = emptyList()
            commDeviceName = "无"
            headsetName = "未连接"
        }
    }

    // Auto-restart: if preference says enabled but service died
    LaunchedEffect(Unit) {
        if (AudioGuardApp.isGuardEnabled(context) && !AudioGuardService.isRunning()) {
            AudioGuardService.start(context)
            delay(500)
            refreshState()
        }
    }

    DisposableEffect(serviceRunning) {
        refreshState()
        val monitor = AudioGuardService.getMonitor()
        monitor?.onStatusChanged = { _ -> refreshState() }
        monitor?.onFixLogUpdated = { refreshState() }
        onDispose {
            monitor?.onStatusChanged = null
            monitor?.onFixLogUpdated = null
        }
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
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
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Service toggle
            val toggleDesc = if (serviceRunning) "启用守护，已开启" else "启用守护，已关闭"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = toggleDesc },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "启用守护",
                    style = MaterialTheme.typography.titleMedium
                )
                Switch(
                    checked = serviceRunning,
                    onCheckedChange = { enabled ->
                        AudioGuardApp.setGuardEnabled(context, enabled)
                        if (enabled) {
                            AudioGuardService.start(context)
                        } else {
                            AudioGuardService.stop(context)
                        }
                        scope.launch {
                            delay(500)
                            refreshState()
                        }
                    }
                )
            }

            HorizontalDivider()

            // Status info
            val statusText = when (status) {
                GuardStatus.NORMAL -> "正常"
                GuardStatus.FIXED -> "已修复"
                GuardStatus.NO_HEADSET -> "无耳机"
            }

            Text(
                text = "当前状态：$statusText",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics {
                    contentDescription = "当前状态：$statusText"
                }
            )

            Text(
                text = "输出设备：$headsetName",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics {
                    contentDescription = "输出设备：$headsetName"
                }
            )

            Text(
                text = "通信设备：$commDeviceName",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics {
                    contentDescription = "通信设备：$commDeviceName"
                }
            )

            // Fix now button
            Button(
                onClick = {
                    AudioGuardService.getMonitor()?.fixNow()
                    refreshState()
                },
                enabled = serviceRunning,
                modifier = Modifier.semantics {
                    contentDescription = "立即修复声道"
                }
            ) {
                Text("立即修复")
            }

            HorizontalDivider()

            // Fix log
            Text(
                text = "修复记录",
                style = MaterialTheme.typography.titleMedium
            )

            if (fixLog.isEmpty()) {
                Text(
                    text = "暂无记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(fixLog) { event ->
                        val time = timeFormat.format(Date(event.timestamp))
                        Text(
                            text = "$time ${event.message}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.semantics {
                                contentDescription = "$time ${event.message}"
                            }
                        )
                    }
                }
            }

            HorizontalDivider()

            // About button
            OutlinedButton(
                onClick = { showAbout = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "关于本软件" }
            ) {
                Text("关于本软件")
            }
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "关于本软件",
                modifier = Modifier.semantics { contentDescription = "关于本软件" }
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("功能说明", style = MaterialTheme.typography.titleSmall)
                Text(
                    "在 Android 14 及以上版本中，抖音、微信等应用播放语音消息时会调用 " +
                    "setSpeakerphoneOn(true) 强制启用扬声器，但播放结束后未正确释放，" +
                    "导致 TalkBack 等屏幕阅读器的语音输出也被劫持到扬声器，" +
                    "使依赖耳机收听的视障用户受到严重影响。\n\n" +
                    "本应用通过监听系统通信设备变化，在检测到音频被异常路由到扬声器时，" +
                    "自动调用 setCommunicationDevice() 将音频恢复到已连接的耳机设备" +
                    "（支持 USB、有线、蓝牙、BLE 耳机）。"
                )

                HorizontalDivider()

                Text("制作信息", style = MaterialTheme.typography.titleSmall)
                Text(
                    "本软件由平行世界plwd与AI编程软件共同制作\n" +
                    "测试设备：Redmi K80 至尊版 (redmik80u)\n" +
                    "如有任何问题，随时交流"
                )

                HorizontalDivider()

                Text("使用条款", style = MaterialTheme.typography.titleSmall)
                Text(
                    "禁止修改本安装包到处宣发。\n" +
                    "可以基于本项目开源的相关技术实现继续研究和分发。"
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
