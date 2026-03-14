package com.plwd.audiochannelguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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

    LaunchedEffect(Unit) {
        if (AudioGuardApp.isGuardEnabled(context) && !AudioGuardService.isRunning()) {
            AudioGuardService.start(context)
            delay(500)
            refreshState()
        }
    }

    DisposableEffect(Unit) {
        refreshState()
        val listener = object : AudioGuardService.OnServiceRebindListener {
            override fun onRebind(monitor: AudioRouteMonitor) {
                monitor.onStatusChanged = { _ -> refreshState() }
                monitor.onFixLogUpdated = { refreshState() }
            }
        }
        val monitor = AudioGuardService.getMonitor()
        monitor?.let { listener.onRebind(it) }
        AudioGuardService.addRebindListener(listener)
        onDispose {
            AudioGuardService.removeRebindListener(listener)
            AudioGuardService.getMonitor()?.onStatusChanged = null
            AudioGuardService.getMonitor()?.onFixLogUpdated = null
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
            val toggleDesc = if (serviceRunning) "启用守护，已开启" else "启用守护，已关闭"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = toggleDesc },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("启用守护", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = serviceRunning,
                    onCheckedChange = { enabled ->
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
                )
            }

            HorizontalDivider()

            val statusText = when (status) {
                GuardStatus.NORMAL -> "正常"
                GuardStatus.FIXED -> "已修复"
                GuardStatus.NO_HEADSET -> "无耳机"
            }
            Text("当前状态：$statusText", style = MaterialTheme.typography.bodyLarge)
            Text("输出设备：$headsetName", style = MaterialTheme.typography.bodyLarge)
            Text("通信设备：$commDeviceName", style = MaterialTheme.typography.bodyLarge)

            Button(
                onClick = {
                    AudioGuardService.getMonitor()?.fixNow()
                    refreshState()
                },
                enabled = serviceRunning
            ) {
                Text("立即修复")
            }

            HorizontalDivider()

            Text("修复记录", style = MaterialTheme.typography.titleMedium)

            if (fixLog.isEmpty()) {
                Text(
                    "暂无记录",
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
                        Text("$time ${event.message}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            HorizontalDivider()

            OutlinedButton(
                onClick = { showAbout = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("关于本软件")
            }
        }
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
                    "Android 14 起，系统以 setCommunicationDevice() 取代了旧的 " +
                    "setSpeakerphoneOn() 来管理通信音频路由。但抖音、微信等应用在播放语音消息时" +
                    "仍调用 setSpeakerphoneOn(true)，播放结束后未释放，" +
                    "导致通信音频流被锁定在内置扬声器。TalkBack 等屏幕阅读器的语音输出因此被劫持，" +
                    "严重影响依赖耳机的视障用户。"
                )

                Text("工作原理", style = MaterialTheme.typography.titleSmall)
                Text(
                    "通过 OnCommunicationDeviceChangedListener 实时监听通信设备变更，" +
                    "当检测到通信设备被异常切换至内置扬声器且有耳机连接时，" +
                    "自动调用 setCommunicationDevice() 将音频路由恢复至耳机。\n\n" +
                    "支持设备类型：USB 耳机、有线耳机/耳麦、蓝牙 A2DP、BLE 音频设备。"
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
