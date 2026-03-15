package com.plwd.audiochannelguard

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private fun hasMissingCriticalPermissions(statusList: List<PermissionStatus>): Boolean {
    return statusList.any { !it.isGranted }
}

/**
 * 权限引导对话框
 */
@Composable
fun PermissionGuideDialog(
    onDismiss: () -> Unit,
    onAllPermissionsGranted: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val initialStatus = remember(context) { PermissionChecker.checkAllPermissions(context) }
    var permissionStatus by remember {
        mutableStateOf(initialStatus)
    }
    var hadMissingCritical by remember {
        mutableStateOf(hasMissingCriticalPermissions(initialStatus))
    }
    var showManufacturerGuide by remember { mutableStateOf(false) }
    // 记录用户正在配置哪个权限（跳转到系统设置后，返回时用来判断是否需要手动确认）
    var pendingManualConfirmType by remember { mutableStateOf<PermissionType?>(null) }
    var showManualConfirmDialog by remember { mutableStateOf(false) }

    fun allCriticalGranted(statusList: List<PermissionStatus>): Boolean {
        return !hasMissingCriticalPermissions(statusList)
    }

    LaunchedEffect(permissionStatus) {
        if (!allCriticalGranted(permissionStatus)) {
            hadMissingCritical = true
        }
    }

    DisposableEffect(activity, context) {
        if (activity == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val latestStatus = PermissionChecker.checkAllPermissions(context)
                    permissionStatus = latestStatus
                    // 如果用户刚从自启动或后台限制设置页返回，弹出手动确认
                    val pending = pendingManualConfirmType
                    if (pending == PermissionType.AUTO_START || pending == PermissionType.BACKGROUND_RESTRICT) {
                        showManualConfirmDialog = true
                    } else {
                        if (hadMissingCritical && allCriticalGranted(latestStatus)) {
                            onAllPermissionsGranted()
                        }
                    }
                }
            }
            activity.lifecycle.addObserver(observer)
            onDispose {
                activity.lifecycle.removeObserver(observer)
            }
        }
    }

    // 手动确认对话框
    if (showManualConfirmDialog && pendingManualConfirmType != null) {
        val confirmTitle = when (pendingManualConfirmType) {
            PermissionType.AUTO_START -> "自启动权限"
            PermissionType.BACKGROUND_RESTRICT -> "后台运行权限"
            else -> ""
        }
        AlertDialog(
            onDismissRequest = {
                showManualConfirmDialog = false
                pendingManualConfirmType = null
            },
            title = { Text(confirmTitle) },
            text = { Text("是否已在系统设置中完成配置？") },
            confirmButton = {
                TextButton(onClick = {
                    when (pendingManualConfirmType) {
                        PermissionType.AUTO_START ->
                            AudioGuardApp.setAutoStartConfirmed(context, true)
                        PermissionType.BACKGROUND_RESTRICT ->
                            AudioGuardApp.setBgRestrictConfirmed(context, true)
                        else -> {}
                    }
                    showManualConfirmDialog = false
                    pendingManualConfirmType = null
                    val latestStatus = PermissionChecker.checkAllPermissions(context)
                    permissionStatus = latestStatus
                    if (hadMissingCritical && allCriticalGranted(latestStatus)) {
                        onAllPermissionsGranted()
                    }
                }) { Text("已完成") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showManualConfirmDialog = false
                    pendingManualConfirmType = null
                }) { Text("尚未完成") }
            }
        )
    }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .wrapContentHeight(),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                // 标题
                Text(
                    text = "权限设置",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "为确保声道守护功能正常运行，需要以下权限：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 权限列表
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(permissionStatus) { status ->
                        val isManualConfirmType = status.type == PermissionType.AUTO_START ||
                            status.type == PermissionType.BACKGROUND_RESTRICT
                        PermissionItem(
                            status = status,
                            // 手动确认类型即使已确认也允许点击重新设置
                            clickable = !status.isGranted || isManualConfirmType,
                            onClick = {
                                if (status.actionIntent != null) {
                                    // 记录正在配置的权限类型
                                    if (isManualConfirmType) {
                                        pendingManualConfirmType = status.type
                                    }
                                    try {
                                        context.startActivity(status.actionIntent)
                                    } catch (e: Exception) {
                                        val fallbackIntent = Intent(
                                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                        ).apply {
                                            data = android.net.Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(fallbackIntent)
                                    }
                                }
                            }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 厂商特定设置说明
                OutlinedButton(
                    onClick = { showManufacturerGuide = !showManufacturerGuide },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (showManufacturerGuide) "隐藏详细设置说明" 
                        else "查看详细设置说明"
                    )
                }
                
                if (showManufacturerGuide) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            text = PermissionChecker.getManufacturerGuide(context),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // 底部按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        // 将自启动和后台限制标记为已手动确认
                        AudioGuardApp.setAutoStartConfirmed(context, true)
                        AudioGuardApp.setBgRestrictConfirmed(context, true)
                        val latestStatus = PermissionChecker.checkAllPermissions(context)
                        permissionStatus = latestStatus
                        if (hadMissingCritical && allCriticalGranted(latestStatus)) {
                            onAllPermissionsGranted()
                        }
                        onDismiss()
                    }) {
                        Text("手动确认")
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Button(
                        onClick = {
                            // 刷新权限状态
                            val latestStatus = PermissionChecker.checkAllPermissions(context)
                            permissionStatus = latestStatus
                            if (hadMissingCritical && allCriticalGranted(latestStatus)) {
                                onAllPermissionsGranted()
                            }
                        }
                    ) {
                        Text("刷新状态")
                    }
                }
            }
        }
    }
}

/**
 * 单个权限项
 */
@Composable
private fun PermissionItem(
    status: PermissionStatus,
    clickable: Boolean = !status.isGranted,
    onClick: () -> Unit
) {
    val backgroundColor = when {
        status.isGranted -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }

    val contentColor = when {
        status.isGranted -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = clickable, onClick = onClick),
        color = backgroundColor,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态图标
            Icon(
                imageVector = if (status.isGranted) {
                    Icons.Default.CheckCircle
                } else {
                    Icons.Default.Warning
                },
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // 文字内容
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = status.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor
                )
                Text(
                    text = status.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 操作提示
            if (clickable) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (status.isGranted) "重新设置" else "去设置",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (status.isGranted) contentColor.copy(alpha = 0.7f) else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * 权限提示条（用于主界面显示）
 */
@Composable
fun PermissionWarningBar(
    showWarning: Boolean,
    onClick: () -> Unit
) {
    if (showWarning) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            color = MaterialTheme.colorScheme.errorContainer,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Text(
                    text = "缺少必要权限，点击配置",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                
                Text(
                    text = "去设置",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
