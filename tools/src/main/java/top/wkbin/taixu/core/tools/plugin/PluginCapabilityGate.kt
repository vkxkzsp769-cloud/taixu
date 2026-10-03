package top.wkbin.taixu.core.tools.plugin

import top.wkbin.taixu.core.common.plugin.PluginCapability
import top.wkbin.taixu.core.common.plugin.PluginGrantState
import top.wkbin.taixu.core.common.plugin.PluginGrantMode
import top.wkbin.taixu.core.common.plugin.PluginRecord

/**
 * 读取 Android 侧真实授权状态的门面（实现放在 app 装配层，那里能安全地拿到 Context 与系统 API）。
 * core/tools 层只依赖本接口，避免把 android 权限 API 散插进插件框架。
 */
interface PluginCapabilityProbe {
    /** 该能力在系统层面当前是否已可用（运行时权限已授 / 特殊访问已开 / 伴生 APK 已装）。 */
    fun isSystemGranted(capability: PluginCapability): Boolean

    /** 伴生 APK 是否已安装。 */
    fun isCompanionInstalled(packageName: String): Boolean = false
}

/** 由宿主代发权限申请（运行时弹窗 / 跳系统设置页 / 引导安装伴生 APK）。 */
interface PluginPermissionRequester {
    fun request(
        capability: PluginCapability,
        record: PluginRecord,
        callback: (PluginGrantState) -> Unit,
    )
}

/** 能力门禁下的宿主服务桥提供者。 */
interface PluginBridgeProvider {
    fun linux(record: PluginRecord): top.wkbin.taixu.core.common.plugin.PluginLinuxBridge? = null
    fun storage(record: PluginRecord): top.wkbin.taixu.core.common.plugin.PluginStorageBridge? = null
    fun agent(record: PluginRecord): top.wkbin.taixu.core.common.plugin.PluginAgentBridge? = null
}

/** 默认探针：什么都不认为已授予（保守），真实实现由 app 注入。 */
object DenyAllCapabilityProbe : PluginCapabilityProbe {
    override fun isSystemGranted(capability: PluginCapability): Boolean = false
}

/**
 * 计算某能力的生效状态：用户批准 + 系统状态双条件。
 *
 * 单独成函数是为了让「谁在什么时候因为什么被挡住」这条规则可被纯 JVM 单测覆盖。
 */
fun resolveGrantState(
    record: PluginRecord,
    capability: PluginCapability,
    probe: PluginCapabilityProbe,
    companionInstalled: Boolean,
): PluginGrantState {
    if (capability.id !in record.grantedCapabilities) return PluginGrantState.DENIED
    return when (capability.grantMode) {
        PluginGrantMode.COMPANION_ONLY ->
            if (companionInstalled) PluginGrantState.GRANTED else PluginGrantState.REQUIRES_COMPANION
        PluginGrantMode.HOST_SPECIAL_ACCESS ->
            if (probe.isSystemGranted(capability)) PluginGrantState.GRANTED else PluginGrantState.NEEDS_SYSTEM_SETTINGS
        PluginGrantMode.HOST_RUNTIME_PERMISSION ->
            if (probe.isSystemGranted(capability)) PluginGrantState.GRANTED else PluginGrantState.NEEDS_SYSTEM_SETTINGS
        PluginGrantMode.HOST_INTERNAL -> PluginGrantState.GRANTED
    }
}
