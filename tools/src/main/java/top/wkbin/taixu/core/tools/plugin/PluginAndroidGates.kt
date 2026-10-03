package top.wkbin.taixu.core.tools.plugin

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import top.wkbin.taixu.core.tools.plugin.PluginCapability
import top.wkbin.taixu.core.tools.plugin.PluginGrantState
import top.wkbin.taixu.core.tools.plugin.PluginRecord

/**
 * 用真实 Android API 探测能力状态。
 *
 * 只读探测，不写任何系统状态；探测失败一律按「未授予」处理（保守降级）。
 */
class SystemCapabilityProbe(private val context: Context) : PluginCapabilityProbe {

    override fun isSystemGranted(capability: PluginCapability): Boolean = when (capability) {
        PluginCapability.OVERLAY_WINDOW -> runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
        PluginCapability.ACCESSIBILITY_CONTROL -> accessibilityEnabled()
        PluginCapability.USAGE_STATS -> runCatching {
            context.getSystemService(android.app.usage.UsageStatsManager::class.java) != null &&
                context.checkSelfPermission("android.permission.PACKAGE_USAGE_STATS") == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        PluginCapability.POST_NOTIFICATIONS -> runCatching {
            context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
        }.getOrDefault(false)
        PluginCapability.PRIVILEGE_BRIDGE -> false // 由宿主的 Shizuku/Root 通道单独判定，不在此擅自放行
        else -> true // HOST_INTERNAL 类能力不依赖系统状态，用户批准即为已授予
    }

    override fun isCompanionInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    /** 宿主的无障碍服务是否已启用（用于判断 ACCESSIBILITY_CONTROL 是否真的可用）。 */
    private fun accessibilityEnabled(): Boolean = runCatching {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        enabled.any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
    }.getOrDefault(false)
}

/**
 * 以「跳转系统设置页」为主要手段的权限申请器。
 *
 * 为什么不用 Activity 弹窗：插件的宿主门面可能在任意线程/无 Activity 时被调用，
 * 用 NEW_TASK 跳设置页是唯一处处可用的通用路径；能自动判定的能力（如运行时权限已授）
 * 直接回调 GRANTED，不再打扰用户。
 */
class SettingsPermissionRequester : PluginPermissionRequester {

    override fun request(
        capability: PluginCapability,
        record: PluginRecord,
        callback: (PluginGrantState) -> Unit,
    ) {
        val context = AppContextHolder.context ?: return callback(PluginGrantState.UNKNOWN)
        val probe = SystemCapabilityProbe(context)
        if (probe.isSystemGranted(capability)) return callback(PluginGrantState.GRANTED)

        val pair: Pair<String?, String?> = when (capability) {
            PluginCapability.OVERLAY_WINDOW ->
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION to "package:${context.packageName}"
            PluginCapability.ACCESSIBILITY_CONTROL ->
                Settings.ACTION_ACCESSIBILITY_SETTINGS to null
            PluginCapability.USAGE_STATS ->
                Settings.ACTION_USAGE_ACCESS_SETTINGS to null
            PluginCapability.POST_NOTIFICATIONS ->
                Settings.ACTION_APP_NOTIFICATION_SETTINGS to context.packageName
            else -> null to null
        }

        val opened = pair.first?.let { action -> runCatching { openSettings(context, action, pair.second) }.getOrDefault(false) } ?: false
        callback(if (opened) PluginGrantState.NEEDS_SYSTEM_SETTINGS else PluginGrantState.UNKNOWN)
    }


    private fun openSettings(context: Context, action: String, packageOrNothing: String?): Boolean {
        val intent = android.content.Intent(action).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            when {
                action == Settings.ACTION_APP_NOTIFICATION_SETTINGS ->
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageOrNothing)
                !packageOrNothing.isNullOrBlank() && !TextUtils.isEmpty(packageOrNothing) ->
                    data = android.net.Uri.parse(packageOrNothing)
            }
        }
        // 部分 ROM 的无障碍页不接受 data，兜底用组件名再试一次。
        return runCatching { context.startActivity(intent); true }
            .getOrElse {
                runCatching {
                    context.startActivity(
                        android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    true
                }.getOrDefault(false)
            }
    }
}

/** 极简的全局 Context 持有者：装配层在启动时写入，避免把 Context 穿透进每个门面。 */
object AppContextHolder {
    @Volatile
    var context: Context? = null

    /** 无障碍服务组件是否声明存在（用于 ROM 差异兜底判断）。 */
    fun hasAccessibilityService(context: Context, component: ComponentName): Boolean = runCatching {
        context.packageManager.getServiceInfo(component, 0) != null
    }.getOrDefault(false)

    val isAtLeastTiramisu: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}
