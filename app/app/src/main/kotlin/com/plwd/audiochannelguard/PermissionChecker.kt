package com.plwd.audiochannelguard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService

/**
 * 权限检测结果
 */
data class PermissionStatus(
    val type: PermissionType,
    val isGranted: Boolean,
    val title: String,
    val description: String,
    val actionIntent: Intent? = null
)

enum class PermissionType {
    BATTERY_OPTIMIZATION,    // 电池优化白名单
    AUTO_START,              // 自启动权限
    BACKGROUND_RESTRICT,     // 后台限制
    NOTIFICATION,            // 通知权限 (Android 13+)
}

/**
 * 手机厂商类型
 */
enum class Manufacturer {
    XIAOMI, HUAWEI, OPPO, VIVO, SAMSUNG, GOOGLE, OTHER;
    
    companion object {
        fun current(): Manufacturer {
            return when (Build.MANUFACTURER.lowercase()) {
                "xiaomi", "redmi", "poco", "blackshark" -> XIAOMI
                "huawei", "honor" -> HUAWEI
                "oppo", "oneplus", "realme" -> OPPO
                "vivo", "iqoo" -> VIVO
                "samsung" -> SAMSUNG
                "google" -> GOOGLE
                else -> OTHER
            }
        }
    }
}

/**
 * 权限检测工具类
 * 用于检测和引导用户配置各项系统权限
 */
object PermissionChecker {
    
    /**
     * 检测所有权限状态
     */
    fun checkAllPermissions(context: Context): List<PermissionStatus> {
        return listOf(
            checkBatteryOptimization(context),
            checkNotificationPermission(context),
            checkAutoStart(context),
            checkBackgroundRestrict(context)
        )
    }
    
    /**
     * 检测电池优化白名单状态
     */
    fun checkBatteryOptimization(context: Context): PermissionStatus {
        val isGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
        
        return PermissionStatus(
            type = PermissionType.BATTERY_OPTIMIZATION,
            isGranted = isGranted,
            title = "电池优化",
            description = if (isGranted) "已允许后台运行" else "需要关闭电池优化，否则应用可能被系统杀死",
            actionIntent = if (isGranted) null else getBatteryOptimizationIntent(context)
        )
    }
    
    /**
     * 检测通知权限 (Android 13+)
     */
    fun checkNotificationPermission(context: Context): PermissionStatus {
        val isGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        
        return PermissionStatus(
            type = PermissionType.NOTIFICATION,
            isGranted = isGranted,
            title = "通知权限",
            description = if (isGranted) "已授权" else "需要通知权限来显示前台服务状态",
            actionIntent = if (isGranted) null else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        )
    }
    
    /**
     * 检测自启动权限状态
     * 注意：这只是基于厂商的推测，实际状态无法直接获取
     */
    fun checkAutoStart(context: Context): PermissionStatus {
        // 自启动权限无法直接检测，只能基于厂商给出引导
        val manufacturer = Manufacturer.current()
        val description = when (manufacturer) {
            Manufacturer.XIAOMI -> "需要开启自启动权限，否则开机后无法自动启动"
            Manufacturer.HUAWEI -> "需要手动允许自启动和后台活动"
            Manufacturer.OPPO -> "需要允许自启动和后台运行"
            Manufacturer.VIVO -> "需要允许自启动"
            Manufacturer.SAMSUNG -> "建议关闭电池优化"
            else -> "建议检查系统设置中的自启动权限"
        }
        
        return PermissionStatus(
            type = PermissionType.AUTO_START,
            isGranted = false, // 无法直接检测，默认显示为需配置
            title = "自启动权限",
            description = description,
            actionIntent = getAutoStartIntent(manufacturer, context)
        )
    }
    
    /**
     * 检测后台限制状态
     */
    fun checkBackgroundRestrict(context: Context): PermissionStatus {
        val manufacturer = Manufacturer.current()
        val description = when (manufacturer) {
            Manufacturer.XIAOMI -> "需要将后台限制设置为\"无限制\""
            Manufacturer.HUAWEI -> "需要允许后台活动"
            Manufacturer.OPPO -> "需要允许后台运行"
            Manufacturer.VIVO -> "需要允许后台高耗电"
            else -> "建议检查系统后台管理设置"
        }
        
        return PermissionStatus(
            type = PermissionType.BACKGROUND_RESTRICT,
            isGranted = false, // 无法直接检测
            title = "后台运行权限",
            description = description,
            actionIntent = getBackgroundRestrictIntent(manufacturer, context)
        )
    }
    
    /**
     * 获取电池优化设置意图
     */
    private fun getBatteryOptimizationIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
    }
    
    /**
     * 获取自启动设置意图
     */
    private fun getAutoStartIntent(manufacturer: Manufacturer, context: Context): Intent {
        val intent = when (manufacturer) {
            Manufacturer.XIAOMI -> {
                // 小米自启动设置
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                }
            }
            Manufacturer.HUAWEI -> {
                // 华为应用启动管理
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                }
            }
            Manufacturer.OPPO -> {
                // OPPO 自启动管理
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )
                }
            }
            Manufacturer.VIVO -> {
                // VIVO 自启动管理
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                    )
                }
            }
            else -> null
        }
        
        // 如果特定厂商的页面无法打开，则跳转到应用详情页
        return if (intent != null && canStartIntent(context, intent)) {
            intent
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }
    
    /**
     * 获取后台限制设置意图
     */
    private fun getBackgroundRestrictIntent(manufacturer: Manufacturer, context: Context): Intent {
        val intent = when (manufacturer) {
            Manufacturer.XIAOMI -> {
                // 小米省电限制
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.miui.powerkeeper",
                        "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                    )
                    putExtra("package_name", context.packageName)
                }
            }
            Manufacturer.HUAWEI -> {
                // 华为电池优化
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"
                    )
                }
            }
            Manufacturer.OPPO -> {
                // OPPO 后台耗电管理
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.coloros.oppoguardelf",
                        "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity"
                    )
                }
            }
            Manufacturer.VIVO -> {
                // VIVO 高耗电管理
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.vivo.abe",
                        "com.vivo.applicationbehaviorengine.ui.ExcessivePowerUsageActivity"
                    )
                }
            }
            else -> null
        }
        
        return if (intent != null && canStartIntent(context, intent)) {
            intent
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }
    
    /**
     * 检查意图是否可以启动
     */
    private fun canStartIntent(context: Context, intent: Intent): Boolean {
        return context.packageManager.resolveActivity(intent, 0) != null
    }
    
    /**
     * 获取厂商特定的设置说明
     */
    fun getManufacturerGuide(context: Context): String {
        return when (Manufacturer.current()) {
            Manufacturer.XIAOMI -> """
                小米/Redmi 设置步骤：
                1. 设置 → 应用设置 → 应用管理 → ${context.getString(R.string.app_name)} → 自启动 → 开启
                2. 设置 → 省电与电池 → 右上角设置 → 应用智能省电 → ${context.getString(R.string.app_name)} → 无限制
                3. 最近任务 → 长按应用卡片 → 锁定
            """.trimIndent()
            
            Manufacturer.HUAWEI -> """
                华为/荣耀 设置步骤：
                1. 设置 → 应用和服务 → 应用启动管理 → ${context.getString(R.string.app_name)} → 手动管理 → 允许自启动 + 允许关联启动 + 允许后台活动
                2. 设置 → 搜索"电池优化" → 所有应用 → ${context.getString(R.string.app_name)} → 不允许
                3. 最近任务 → 下拉锁定应用
            """.trimIndent()
            
            Manufacturer.OPPO -> """
                OPPO/一加/realme 设置步骤：
                1. 设置 → 应用管理 → 自启动管理 → 开启 ${context.getString(R.string.app_name)}
                2. 设置 → 电池 → 应用耗电管理 → ${context.getString(R.string.app_name)} → 允许后台运行 + 允许完全后台行为
                3. 最近任务 → 下拉锁定
            """.trimIndent()
            
            Manufacturer.VIVO -> """
                vivo/iQOO 设置步骤：
                1. 设置 → 应用与权限 → 权限管理 → 自启动 → 开启 ${context.getString(R.string.app_name)}
                2. 设置 → 电池 → 后台高耗电 → 允许 ${context.getString(R.string.app_name)}
                3. 最近任务 → 下拉锁定
            """.trimIndent()
            
            Manufacturer.SAMSUNG -> """
                三星 设置步骤：
                1. 设置 → 电池和设备维护 → 电池 → 后台使用限制 → 不优化应用 → 添加 ${context.getString(R.string.app_name)}
                2. 设置 → 应用程序 → ${context.getString(R.string.app_name)} → 电池 → 不受限制
            """.trimIndent()
            
            else -> """
                通用设置步骤：
                1. 设置 → 应用 → ${context.getString(R.string.app_name)} → 电池 → 无限制/不优化
                2. 设置 → 应用 → 自启动管理 → 允许 ${context.getString(R.string.app_name)}
                3. 最近任务界面锁定应用
            """.trimIndent()
        }
    }
}
