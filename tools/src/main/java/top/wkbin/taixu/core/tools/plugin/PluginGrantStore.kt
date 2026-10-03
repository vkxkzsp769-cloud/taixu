package top.wkbin.taixu.core.tools.plugin

import top.wkbin.taixu.core.model.plugin.PluginManifest

/**
 * 插件授权与接管状态的持久化契约。
 *
 * 注册表与装载器只依赖本接口（依赖倒置）：
 * core:common 不引入 DataStore/数据库，实现放在 tools 模块，便于纯 JVM 单测与替换。
 */
interface PluginGrantStore {

    /** 已安装插件记录（含清单与运行期状态）。 */
    suspend fun installed(): List<PluginRecord>

    suspend fun find(pluginId: String): PluginRecord?

    suspend fun save(record: PluginRecord)

    suspend fun remove(pluginId: String)

    /** 用户批准/拒绝某项能力。 */
    suspend fun setCapabilityGranted(pluginId: String, capabilityId: String, granted: Boolean)

    /** 用户批准/撤回某插件对某扩展点的接管。 */
    suspend fun setOverrideApproved(pluginId: String, slotId: String, approved: Boolean)

    /** 记录一次插件崩溃，返回时间窗内的累计次数。 */
    suspend fun recordCrash(pluginId: String, timestampMs: Long): Int

    suspend fun setAutoDisabled(pluginId: String, disabled: Boolean, reason: String? = null)

    /** 追加一条接管审计事件（供「接管中心」展示与追溯）。 */
    suspend fun appendAudit(event: PluginAuditEvent)

    suspend fun auditHistory(limit: Int = 100): List<PluginAuditEvent>
}

/** 已安装插件的完整状态快照。 */
data class PluginRecord(
    val manifest: PluginManifest,
    val installedAtMs: Long,
    /** 代码包在设备上的绝对路径（NATIVE_DEX 用）。 */
    val codePath: String? = null,
    val enabled: Boolean = true,
    /** 已批准的能力 ID。 */
    val grantedCapabilities: Set<String> = emptySet(),
    /** 已批准接管的扩展点 ID。 */
    val approvedSlots: Set<String> = emptySet(),
    /** 时间窗内崩溃时间戳。 */
    val crashTimestamps: List<Long> = emptyList(),
    /** 因连续崩溃被宿主强制停用。 */
    val autoDisabled: Boolean = false,
    val autoDisabledReason: String? = null,
) {
    val isLoaded: Boolean get() = enabled && !autoDisabled

    fun hasCapability(capability: PluginCapability): Boolean =
        capability.id in grantedCapabilities

    fun hasSlotApproval(slotId: String): Boolean = slotId in approvedSlots
}

/** 接管审计事件：谁在什么时候顶掉了什么、由谁裁决、结果如何。 */
data class PluginAuditEvent(
    val timestampMs: Long,
    val pluginId: String,
    val action: PluginAuditAction,
    val slotId: String? = null,
    val detail: String = "",
)

enum class PluginAuditAction {
    INSTALL,
    UNINSTALL,
    ENABLE,
    DISABLE,
    CAPABILITY_GRANTED,
    CAPABILITY_REVOKED,
    OVERRIDE_APPROVED,
    OVERRIDE_REVOKED,
    OVERRIDE_ACTIVATED,
    OVERRIDE_FALLBACK_BUILTIN,
    AUTO_DISABLED_BY_CRASH,
    LOAD_FAILED,
}
