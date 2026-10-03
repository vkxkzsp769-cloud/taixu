package top.wkbin.taixu.core.tools.plugin

import android.content.Context
import android.util.Log
import top.wkbin.taixu.core.common.plugin.PluginAgentBridge
import top.wkbin.taixu.core.common.plugin.PluginCapability
import top.wkbin.taixu.core.common.plugin.PluginExtension
import top.wkbin.taixu.core.common.plugin.PluginGrantState
import top.wkbin.taixu.core.common.plugin.PluginHost
import top.wkbin.taixu.core.common.plugin.PluginLinuxBridge
import top.wkbin.taixu.core.common.plugin.PluginLogLevel
import top.wkbin.taixu.core.common.plugin.PluginRecord
import top.wkbin.taixu.core.common.plugin.PluginRegistration
import top.wkbin.taixu.core.common.plugin.PluginRegistry
import top.wkbin.taixu.core.common.plugin.PluginSlot
import top.wkbin.taixu.core.common.plugin.PluginStorageBridge
import top.wkbin.taixu.core.model.plugin.PluginManifest

/**
 * 交给单个插件的宿主门面：一个插件一个实例，注册与审计天然按 pluginId 隔离。
 *
 * 这里就是「插件拥有宿主一切权限」的落点：[context] 直接是宿主 Application Context，
 * 插件因此可以使用宿主已声明/已授予的一切 Android 能力；
 * 代价是本类同时承担门禁职责——未获批的能力既拿不到桥对象，[require] 也会抛异常。
 */
class PluginHostFacade(
    override val context: Context,
    /** 实时读取插件状态：用户撤回授权/崩溃停用后，门禁必须立刻生效，不能停留在装载期快照。 */
    private val recordProvider: () -> PluginRecord,
    private val registry: PluginRegistry,
    private val probe: PluginCapabilityProbe,
    private val requester: PluginPermissionRequester,
    private val bridgeProvider: PluginBridgeProvider,
    private val onRegistered: (PluginRegistration) -> Unit = {},
) : PluginHost {

    private val record: PluginRecord get() = recordProvider()

    override val pluginId: String = record.manifest.id

    override val grantedCapabilities: Set<PluginCapability>
        get() = record.manifest.capabilities
            .mapNotNull { PluginCapability.byId(it) }
            .filter { capabilityState(it) == PluginGrantState.GRANTED }
            .toSet()

    override fun has(capability: PluginCapability): Boolean = capabilityState(capability) == PluginGrantState.GRANTED

    override fun capabilityState(capability: PluginCapability): PluginGrantState = resolveGrantState(
        record = record,
        capability = capability,
        probe = probe,
        companionInstalled = companionInstalled(),
    )

    override fun requestCapability(capability: PluginCapability, callback: (PluginGrantState) -> Unit) {
        if (capability.id !in record.manifest.capabilities) {
            // 清单没声明的能力不允许运行期临时加申请——否则「安装期同意」形同虚设。
            log(PluginLogLevel.WARN, "能力 ${capability.id} 未在清单中声明，拒绝代为申请")
            callback(PluginGrantState.DENIED)
            return
        }
        requester.request(capability, record, callback)
    }

    override fun register(slot: PluginSlot<out PluginExtension>, impl: PluginExtension, priority: Int): PluginRegistration {
        if (impl.pluginId != pluginId) {
            throw IllegalArgumentException("扩展实现的 pluginId(${impl.pluginId}) 与当前插件($pluginId) 不一致")
        }
        if (slot.id !in record.manifest.extensionPoints) {
            throw IllegalStateException("插件 $pluginId 未声明扩展点 ${slot.id}，拒绝注册")
        }
        if (priority <= PluginManifest.BUILTIN_PRIORITY) {
            throw IllegalArgumentException("接管优先级非法：不得占用内置保留值")
        }
        val registration = registry.register(slot, impl, priority)
        onRegistered(registration)
        return registration
    }

    override fun unregister(registration: PluginRegistration) {
        if (registration.pluginId != pluginId) return
        registry.unregister(registration)
    }

    override fun log(level: PluginLogLevel, message: String) = writeLog(level, message)

    override val linux: PluginLinuxBridge?
        get() = if (has(PluginCapability.LINUX_EXEC)) bridgeProvider.linux(record) else null

    override val storage: PluginStorageBridge?
        get() {
            val allowed = has(PluginCapability.SHARED_STORAGE_READ) ||
                has(PluginCapability.SHARED_STORAGE_WRITE) ||
                has(PluginCapability.HOST_DATA_READ_WRITE)
            return if (allowed) bridgeProvider.storage(record) else null
        }

    override val agent: PluginAgentBridge?
        get() = if (has(PluginCapability.AGENT_TOOL_REGISTER)) bridgeProvider.agent(record) else null

    private fun companionInstalled(): Boolean {
        val companion = record.manifest.companionPackage ?: return true // 不依赖伴生 APK
        return probe.isCompanionInstalled(companion)
    }

    private fun log(level: PluginLogLevel, message: String) {
        val tag = "$TAG/${record.manifest.id}"
        when (level) {
            PluginLogLevel.DEBUG -> Log.d(tag, message)
            PluginLogLevel.INFO -> Log.i(tag, message)
            PluginLogLevel.WARN -> Log.w(tag, message)
            PluginLogLevel.ERROR -> Log.e(tag, message)
        }
    }

    private companion object {
        const val TAG = "TaiXuPlugin"
    }
}
