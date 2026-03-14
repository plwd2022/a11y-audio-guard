package com.plwd.audiochannelguard

import android.content.Intent
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

/**
 * 权限引导对话框
 */
@Composable
fun PermissionGuideDialog(
    onDismiss: () -> Unit,
    onAllPermissionsGranted: () -> Unit = {}
) {
    val context = LocalContext.current
    var permissionStatus by remember { 
        mutableStateOf(PermissionChecker.checkAllPermissions(context)) 
    }
    var showManufacturerGuide by remember { mutableStateOf(false) }
    
    // 检查是否所有关键权限都已授予
    val allCriticalGranted = permissionStatus
        .filter { it.type == PermissionType.BATTERY_OPTIMIZATION || it.type == PermissionType.NOTIFICATION }
        .all { it.isGranted }
    
    if (allCriticalGranted) {
        onAllPermissionsGranted()
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
                        PermissionItem(
                            status = status,
                            onClick = {
                                if (!status.isGranted && status.actionIntent != null) {
                                    try {
                                        context.startActivity(status.actionIntent)
                                    } catch (e: Exception) {
                                        // 如果无法打开特定设置，跳转到应用详情
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
                    TextButton(onClick = onDismiss) {
                        Text("稍后再说")
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Button(
                        onClick = {
                            // 刷新权限状态
                            permissionStatus = PermissionChecker.checkAllPermissions(context)
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
            .clickable(enabled = !status.isGranted, onClick = onClick),
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
            if (!status.isGranted) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "去设置",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
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
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var showWarning by remember { mutableStateOf(false) }
    
    // 检查关键权限
    LaunchedEffect(Unit) {
        val batteryStatus = PermissionChecker.checkBatteryOptimization(context)
        showWarning = !batteryStatus.isGranted
    }
    
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
