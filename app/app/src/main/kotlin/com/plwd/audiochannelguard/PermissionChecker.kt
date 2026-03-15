package com.plwd.audiochannelguard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

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
    XIAOMI, HUAWEI, OPPO, ONEPLUS, VIVO, SAMSUNG, MEIZU, GOOGLE, OTHER;

    companion object {
        fun current(): Manufacturer {
            return when (Build.MANUFACTURER.lowercase()) {
                "xiaomi", "redmi", "poco", "blackshark" -> XIAOMI
                "huawei", "honor" -> HUAWEI
                "oppo", "realme" -> OPPO
                "oneplus" -> ONEPLUS
                "vivo", "iqoo" -> VIVO
                "samsung" -> SAMSUNG
                "meizu" -> MEIZU
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
     * 无法通过 API 检测，结合厂商引导和用户手动确认
     */
    fun checkAutoStart(context: Context): PermissionStatus {
        val manufacturer = Manufacturer.current()
        val confirmed = AudioGuardApp.isAutoStartConfirmed(context)
        val description = if (confirmed) {
            "已配置（手动确认）"
        } else {
            when (manufacturer) {
                Manufacturer.XIAOMI -> "需要开启自启动权限，否则开机后无法自动启动"
                Manufacturer.HUAWEI -> "需要手动允许自启动和后台活动"
                Manufacturer.OPPO -> "需要允许自启动和后台运行"
                Manufacturer.ONEPLUS -> "需要允许自启动和后台运行"
                Manufacturer.VIVO -> "需要允许自启动"
                Manufacturer.SAMSUNG -> "建议在电池设置中将应用设为不受限"
                Manufacturer.MEIZU -> "需要允许自启动和后台管理"
                Manufacturer.GOOGLE -> "建议关闭自适应电池对本应用的限制"
                Manufacturer.OTHER -> "建议检查系统设置中的自启动权限"
            }
        }

        return PermissionStatus(
            type = PermissionType.AUTO_START,
            isGranted = confirmed,
            title = "自启动权限",
            description = description,
            actionIntent = getAutoStartIntent(manufacturer, context)
        )
    }

    /**
     * 检测后台限制状态
     * 无法通过 API 检测，结合厂商引导和用户手动确认
     */
    fun checkBackgroundRestrict(context: Context): PermissionStatus {
        val manufacturer = Manufacturer.current()
        val confirmed = AudioGuardApp.isBgRestrictConfirmed(context)
        val description = if (confirmed) {
            "已配置（手动确认）"
        } else {
            when (manufacturer) {
                Manufacturer.XIAOMI -> "需要将省电策略设置为「无限制」"
                Manufacturer.HUAWEI -> "需要允许后台活动"
                Manufacturer.OPPO -> "需要允许后台运行"
                Manufacturer.ONEPLUS -> "需要允许后台运行"
                Manufacturer.VIVO -> "需要允许后台高耗电"
                Manufacturer.SAMSUNG -> "建议将应用电池设置为「不受限制」"
                Manufacturer.MEIZU -> "需要在电池管理中允许后台运行"
                Manufacturer.GOOGLE -> "建议在电池设置中取消对本应用的限制"
                Manufacturer.OTHER -> "建议检查系统后台管理设置"
            }
        }

        return PermissionStatus(
            type = PermissionType.BACKGROUND_RESTRICT,
            isGranted = confirmed,
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
     * 从候选 intent 列表中找到第一个可用的，全部不可用则回退到应用详情页
     */
    private fun resolveFirstAvailable(
        context: Context,
        candidates: List<Intent>
    ): Intent {
        for (intent in candidates) {
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                return intent
            }
        }
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    private fun componentIntent(pkg: String, cls: String): Intent {
        return Intent().apply { component = ComponentName(pkg, cls) }
    }

    /**
     * 获取自启动设置意图（多版本兼容）
     */
    private fun getAutoStartIntent(manufacturer: Manufacturer, context: Context): Intent {
        val candidates = when (manufacturer) {
            Manufacturer.XIAOMI -> listOf(
                // MIUI & HyperOS: 均在 securitycenter 下
                componentIntent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            )
            Manufacturer.HUAWEI -> listOf(
                // HarmonyOS / EMUI 10+
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                // EMUI 9 及以下
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                // 旧版 EMUI 后台保护页（部分设备自启动在此）
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            )
            Manufacturer.OPPO -> listOf(
                // ColorOS 13+ (oplus 新包名)
                componentIntent("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
                // ColorOS 11-12
                componentIntent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                // ColorOS 旧版
                componentIntent("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                // 更旧的 OPPO (Color OS 5-)
                componentIntent("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            )
            Manufacturer.ONEPLUS -> listOf(
                // OxygenOS 独立安全中心
                componentIntent("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
                // 一加合并 ColorOS 后使用 oplus 包名
                componentIntent("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
                // 部分旧版一加走 coloros 路径
                componentIntent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            )
            Manufacturer.VIVO -> listOf(
                // vivo OriginOS
                componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                // iQOO / Funtouch OS 白名单
                componentIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                // iQOO 旧版
                componentIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            )
            Manufacturer.SAMSUNG -> listOf(
                // One UI 4+ (Device care → Battery)
                componentIntent("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                // One UI 旧版 (Device maintenance)
                componentIntent("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                // 更旧版 Samsung Smart Manager
                componentIntent("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                // 休眠应用列表
                componentIntent("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity"),
            )
            Manufacturer.MEIZU -> listOf(
                componentIntent("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"),
            )
            else -> emptyList()
        }
        return resolveFirstAvailable(context, candidates)
    }

    /**
     * 获取后台限制设置意图（多版本兼容）
     */
    private fun getBackgroundRestrictIntent(manufacturer: Manufacturer, context: Context): Intent {
        val candidates = when (manufacturer) {
            Manufacturer.XIAOMI -> listOf(
                // HyperOS / MIUI: 直接跳转到本应用的电池详情页（可设置省电策略为「无限制」）
                Intent().apply {
                    component = ComponentName("com.miui.securitycenter", "com.miui.powercenter.legacypowerrank.PowerDetailActivity")
                    putExtra("package_name", context.packageName)
                },
                // HyperOS: 电池设置主页
                componentIntent("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
                // MIUI 旧版 powerkeeper
                Intent().apply {
                    component = ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                    putExtra("package_name", context.packageName)
                },
                Intent().apply {
                    component = ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity")
                    putExtra("package_name", context.packageName)
                },
                // 万能回退: 电池使用情况页
                Intent("android.intent.action.POWER_USAGE_SUMMARY"),
            )
            Manufacturer.HUAWEI -> listOf(
                // HarmonyOS / EMUI 10+ 后台保护
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                // 电池管理
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"),
                // 启动管理（部分设备后台限制在此页）
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            )
            Manufacturer.OPPO -> listOf(
                // ColorOS 13+ (oplus 新包名)
                componentIntent("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
                // ColorOS 11-12
                componentIntent("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity"),
                componentIntent("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
            )
            Manufacturer.ONEPLUS -> listOf(
                // 一加：优先使用标准后台优化 action
                Intent("com.android.settings.action.BACKGROUND_OPTIMIZE"),
                // 回退到 oplus 电池管理
                componentIntent("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
                componentIntent("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
            )
            Manufacturer.VIVO -> listOf(
                // OriginOS 后台高耗电
                componentIntent("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerUsageActivity"),
                // iQOO 电源管理
                componentIntent("com.iqoo.powermanager", "com.iqoo.powermanager.PowerManagerActivity"),
                // iQOO 白名单
                componentIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            )
            Manufacturer.SAMSUNG -> listOf(
                // One UI 4+ (Device care → Battery)
                componentIntent("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                // One UI 旧版
                componentIntent("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                // 更旧版 Samsung Smart Manager
                componentIntent("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                // 休眠应用列表（深度休眠管理）
                componentIntent("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity"),
            )
            Manufacturer.MEIZU -> listOf(
                componentIntent("com.meizu.safe", "com.meizu.safe.powerui.PowerAppPermissionActivity"),
            )
            else -> emptyList()
        }
        return resolveFirstAvailable(context, candidates)
    }

    /**
     * 获取厂商特定的设置说明
     */
    fun getManufacturerGuide(context: Context): String {
        val appName = context.getString(R.string.app_name)
        return when (Manufacturer.current()) {
            Manufacturer.XIAOMI -> """
                小米/Redmi 设置步骤：
                1. 设置 → 应用设置 → 应用管理 → $appName → 自启动 → 开启
                2. 设置 → 省电与电池 → 右上角设置 → 应用智能省电 → $appName → 无限制
                3. 最近任务 → 长按应用卡片 → 锁定
            """.trimIndent()

            Manufacturer.HUAWEI -> """
                华为/荣耀 设置步骤：
                1. 设置 → 应用和服务 → 应用启动管理 → $appName → 手动管理 → 允许自启动 + 允许关联启动 + 允许后台活动
                2. 设置 → 搜索「电池优化」→ 所有应用 → $appName → 不允许
                3. 最近任务 → 下拉锁定应用
            """.trimIndent()

            Manufacturer.OPPO -> """
                OPPO/realme 设置步骤：
                1. 设置 → 应用管理 → 自启动管理 → 开启 $appName
                2. 设置 → 电池 → 应用耗电管理 → $appName → 允许后台运行 + 允许完全后台行为
                3. 最近任务 → 下拉锁定
            """.trimIndent()

            Manufacturer.ONEPLUS -> """
                一加 设置步骤：
                1. 设置 → 应用管理 → 自启动管理 → 开启 $appName
                2. 设置 → 电池 → 电池优化 → $appName → 不优化
                3. 最近任务 → 下拉锁定
            """.trimIndent()

            Manufacturer.VIVO -> """
                vivo/iQOO 设置步骤：
                1. 设置 → 应用与权限 → 权限管理 → 自启动 → 开启 $appName
                2. 设置 → 电池 → 后台高耗电 → 允许 $appName
                3. 最近任务 → 下拉锁定
            """.trimIndent()

            Manufacturer.SAMSUNG -> """
                三星 设置步骤：
                1. 设置 → 常规管理（或电池和设备维护）→ 电池 → 后台使用限制 → 确认 $appName 不在「深度休眠应用」中
                2. 设置 → 应用程序 → $appName → 电池 → 不受限制
                3. 最近任务 → 长按应用图标 → 锁定
            """.trimIndent()

            Manufacturer.MEIZU -> """
                魅族 设置步骤：
                1. 设置 → 应用管理 → $appName → 权限管理 → 后台管理 → 允许后台运行
                2. 设置 → 电池 → 电池优化 → $appName → 不优化
                3. 最近任务 → 下拉锁定
            """.trimIndent()

            Manufacturer.GOOGLE -> """
                Pixel 设置步骤：
                1. 设置 → 电池 → 电池用量 → $appName → 不受限制
                2. 设置 → 应用 → $appName → 电池 → 不受限制
                3. 如开启了自适应电池，建议在电池设置中关闭或将本应用排除
            """.trimIndent()

            Manufacturer.OTHER -> """
                通用设置步骤：
                1. 设置 → 应用 → $appName → 电池 → 无限制/不优化
                2. 设置 → 应用 → 自启动管理 → 允许 $appName
                3. 最近任务界面锁定应用
            """.trimIndent()
        }
    }
}
