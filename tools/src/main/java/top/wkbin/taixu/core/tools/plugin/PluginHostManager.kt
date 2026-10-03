package top.wkbin.taixu.core.tools.plugin

import android.content.Context
import android.util.Log
import top.wkbin.taixu.core.common.plugin.PluginAuditAction
import top.wkbin.taixu.core.common.plugin.PluginAuditEvent
import top.wkbin.taixu.core.common.plugin.PluginCapability
import top.wkbin.taixu.core.common.plugin.PluginExtension
import top.wkbin.taixu.core.common.plugin.PluginGrantState
import top.wkbin.taixu.core.common.plugin.PluginGrantStore
import top.wkbin.taixu.core.common.plugin.PluginRecord
import top.wkbin.taixu.core.common.plugin.PluginRegistration
import top.wkbin.taixu.core.common.plugin.PluginRegistry
import top.wkbin.taixu.core.common.plugin.PluginSafetyPolicy
import top.wkbin.taixu.core.common.plugin.PluginSlot
import top.wkbin.taixu.core.model.plugin.PluginManifest

/** 内置实现的保留插件 ID：宿主自带实现也走同一个槽位仲裁，只是优先级最低。 */
const val BUILTIN_PLUGIN_ID = "builtin"

/**
 * 宿主能力插件的运行期管理器：装载、仲裁、崩溃降级。
 *
 * 关键不变量：
 * 1. 只有「已启用 + 未被自动停用 + 该扩展点被用户批准 + （顶掉内置时）已授予 UI_SLOT_OVERRIDE」
 *    的注册项才会生效，四条件缺一不可；
 * 2. 任何插件代码抛异常，都被 [guard] 捕获、计入崩溃、必要时自动停用并回退内置，
 *    绝不让一次接管失败变成宿主崩溃；
 * 3. 授权状态实时读快照（[PluginHostFacade] 用 provider 取记录），撤回授权立即生效。
 */
class PluginHostManager(
    private val context: Context,
    private val store: PluginGrantStore,
    val registry: PluginRegistry = PluginRegistry(),
    private val loaders: List<PluginCodeLoader> = emptyList(),
    private val probe: PluginCapabilityProbe = DenyAllCapabilityProbe,
    private val requester: PluginPermissionRequester = DefaultPermissionRequester,
    private val bridgeProvider: PluginBridgeProvider = EmptyBridgeProvider,
) {

    /** 已装载并 onAttach 成功的插件实例。 */
    private val live = mutableMapOf<String, TaiXuPluginHandle>()
    private var records: Map<String, PluginRecord> = emptyMap()

    /** 内置实现的注册句柄，宿主装配层用它登记自己的默认实现。 */
    private val builtinHandles = mutableMapOf<String, PluginRegistration>()

    val installedRecords: List<PluginRecord> get() = records.values.sortedBy { it.manifest.name }

    fun recordOf(pluginId: String): PluginRecord? = records[pluginId]

    /** 取某插件的宿主门面（仅对已装载插件有效），供接管点回调插件时使用。 */
    fun facadeOf(pluginId: String): PluginHostFacade? = synchronized(live) { live[pluginId]?.facade }

    /** 宿主 Application Context：接管点用它创建窗口/启动服务。 */
    fun applicationContext(): Context = context

    /** 启动装配：拉取状态 → 逐个装载 → 汇报生效接管。 */
    suspend fun bootstrap() {
        refresh()
        registry.declaredSlots()
        records.values.forEach { record ->
            if (record.isLoaded) runCatching { attach(record) }
                .onFailure { Log.w(TAG, "插件 ${record.manifest.id} 装载失败", it) }
        }
    }

    suspend fun refresh() {
        records = store.installed().associateBy { it.manifest.id }
    }

    /** 宿主登记内置实现（优先级为内置保留值，任何获批插件都能顶掉它）。 */
    fun registerBuiltin(slot: PluginSlot<out PluginExtension>, extension: PluginExtension): PluginRegistration {
        val registration = registry.register(slot, extension, PluginManifest.BUILTIN_PRIORITY)
        builtinHandles[slot.id] = registration
        return registration
    }

    /** 装载并激活一个插件。 */
    suspend fun attach(pluginId: String): Boolean {
        val record = records[pluginId] ?: return false
        return attach(record)
    }

    private suspend fun attach(record: PluginRecord): Boolean {
        val pluginId = record.manifest.id
        if (live.containsKey(pluginId)) return true
        val loader = loaders.firstOrNull { it.supports(record) }
        if (loader == null) {
            audit(pluginId, PluginAuditAction.LOAD_FAILED, detail = "没有支持 ${record.manifest.runtime} 的装载器")
            return false
        }
        return try {
            val instance = loader.load(record)
            val facade = PluginHostFacade(
                context = context,
                recordProvider = { records[pluginId] ?: record },
                registry = registry,
                probe = probe,
                requester = requester,
                bridgeProvider = bridgeProvider,
            )
            instance.onAttach(facade)
            synchronized(live) { live[pluginId] = TaiXuPluginHandle(instance, facade) }
            audit(pluginId, PluginAuditAction.ENABLE, detail = "已装载 ${record.manifest.version}")
            true
        } catch (e: Throwable) {
            audit(pluginId, PluginAuditAction.LOAD_FAILED, detail = e.message.orEmpty())
            Log.w(TAG, "attach $pluginId failed", e)
            false
        }
    }

    /** 停用插件：调用 onDetach、清注册、必要时回退内置。 */
    suspend fun detach(pluginId: String, reason: String = "用户停用") {
        val handle = synchronized(live) { live.remove(pluginId) }
        if (handle != null) {
            runCatching { handle.plugin.onDetach() }
                .onFailure { Log.w(TAG, "onDetach $pluginId 异常", it) }
        }
        // 先算出「该插件当前正接管着哪些槽位」，注销后才能准确宣告回退内置。
        val takenSlots = registry.declaredSlots().filter { slot ->
            registry.resolve(slot) { isApproved(it, slot) }?.pluginId == pluginId
        }.map { it.id }
        registry.unregisterAll(pluginId)
        takenSlots.forEach { slotId ->
            if (builtinHandles.containsKey(slotId)) {
                audit(pluginId, PluginAuditAction.OVERRIDE_FALLBACK_BUILTIN, slotId = slotId, detail = reason)
            }
        }
        audit(pluginId, PluginAuditAction.DISABLE, detail = reason)
    }

    suspend fun setEnabled(pluginId: String, enabled: Boolean) {
        val record = records[pluginId] ?: return
        store.save(record.copy(enabled = enabled, autoDisabled = false, autoDisabledReason = null, crashTimestamps = emptyList()))
        refresh()
        if (enabled) attach(record.manifest.id) else detach(pluginId, reason = "用户停用")
    }

    /**
     * 以受保护方式执行插件代码：捕获任何异常，累计崩溃次数，达阈值自动停用并回退内置。
     * @return 成功时返回插件的返回值；失败返回 null，调用方应立即回退内置实现。
     */
    suspend fun <R> guard(pluginId: String, slotId: String? = null, block: suspend () -> R): R? = try {
        block()
    } catch (e: Throwable) {
        Log.w(TAG, "插件 $pluginId 执行异常", e)
        onPluginFailure(pluginId, slotId, e)
        null
    }

    /** 插件运行期失败：崩溃计数 + 阈值判定 + 自动停用。 */
    suspend fun onPluginFailure(pluginId: String, slotId: String?, error: Throwable) {
        val now = System.currentTimeMillis()
        val count = store.recordCrash(pluginId, now)
        val record = records[pluginId]
        if (record != null && PluginSafetyPolicy.shouldAutoDisable(record.crashTimestamps + now, now)) {
            store.setAutoDisabled(pluginId, true, reason = "${count} 次连续异常，已自动停用并回退内置实现")
            detach(pluginId, reason = "崩溃自动停用")
            audit(pluginId, PluginAuditAction.AUTO_DISABLED_BY_CRASH, slotId = slotId, detail = error.message.orEmpty())
        }
        refresh()
    }

    /** 解析槽位当前生效实现；插件未获批或用户强制回退时返回内置实现。 */
    @Suppress("UNCHECKED_CAST")
    fun <T : PluginExtension> resolve(slot: PluginSlot<T>): T? =
        (registry.resolve(slot) { isApproved(it, slot) } ?: builtinFor(slot)) as? T

    private fun builtinFor(slot: PluginSlot<*>): PluginExtension? =
        registry.candidates(slot.id)
            .firstOrNull { it.pluginId == BUILTIN_PLUGIN_ID }
            ?.let { registry.extensionFor(it) }

    /** 四条件仲裁（纯函数见 [isRegistrationApproved]）。 */
    fun isApproved(registration: PluginRegistration, slot: PluginSlot<*>): Boolean =
        isRegistrationApproved(registration, slot, records[registration.pluginId])

    /** 当前生效实现的提供方（接管中心展示「谁顶掉了内置」）。 */
    fun activeProvider(slotId: String): String? {
        val slot = registry.declaredSlots().firstOrNull { it.id == slotId } ?: return null
        return resolve(slot)?.pluginId
    }

    /** 某插件对某槽位的可申请状态，供接管中心渲染。 */
    suspend fun slotState(pluginId: String, slotId: String): PluginSlotUiState {
        val record = records[pluginId] ?: return PluginSlotUiState.Unknown
        val slot = registry.declaredSlots().firstOrNull { it.id == slotId } ?: return PluginSlotUiState.Unknown
        val verdict = PluginSafetyPolicy.canClaimSlot(record.manifest, slot)
        val overrideCapability = if (slot.hasBuiltin) {
            resolveGrantState(record, PluginCapability.UI_SLOT_OVERRIDE, probe, companionInstalled = true)
        } else {
            PluginGrantState.GRANTED
        }
        return PluginSlotUiState.Resolved(
            verdict = verdict,
            approved = record.approvedSlots.contains(slotId),
            capabilityState = overrideCapability,
            autoDisabled = record.autoDisabled,
            enabled = record.isLoaded,
        )
    }

    private suspend fun audit(pluginId: String, action: PluginAuditAction, slotId: String? = null, detail: String = "") {
        store.appendAudit(PluginAuditEvent(System.currentTimeMillis(), pluginId, action, slotId, detail))
    }

    private class TaiXuPluginHandle(val plugin: top.wkbin.taixu.core.common.plugin.TaiXuPlugin, val facade: PluginHostFacade)

    private companion object {
        const val TAG = "TaiXuPluginHost"
    }
}

/** 接管中心的槽位状态视图模型（纯数据，不依赖 UI 框架）。 */
sealed class PluginSlotUiState {
    data object Unknown : PluginSlotUiState()
    data class Resolved(
        val verdict: top.wkbin.taixu.core.common.plugin.SlotVerdict,
        val approved: Boolean,
        val capabilityState: PluginGrantState,
        val autoDisabled: Boolean,
        val enabled: Boolean,
    ) : PluginSlotUiState() {
        /** 综合结论：现在这个槽位到底会被插件接管吗。 */
        val willOverride: Boolean
            get() = verdict is top.wkbin.taixu.core.common.plugin.SlotVerdict.Allowed &&
                approved && capabilityState == PluginGrantState.GRANTED && enabled && !autoDisabled
    }
}

/** 未注入真实探针/申请人时的保守实现：一律视为未授予，插件只能走宿主内部门禁。 */
private object DefaultPermissionRequester : PluginPermissionRequester {
    override fun request(
        capability: PluginCapability,
        record: PluginRecord,
        callback: (PluginGrantState) -> Unit,
    ) = callback(PluginGrantState.UNKNOWN)
}

private object EmptyBridgeProvider : PluginBridgeProvider
