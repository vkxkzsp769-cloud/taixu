package top.wkbin.taixu.core.tools.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.tools.plugin.PluginAuditAction
import top.wkbin.taixu.core.tools.plugin.PluginAuditEvent
import top.wkbin.taixu.core.tools.plugin.PluginGrantStore
import top.wkbin.taixu.core.tools.plugin.PluginRecord
import top.wkbin.taixu.core.tools.plugin.PluginSafetyPolicy
import top.wkbin.taixu.core.datastore.PluginStateRepository

/**
 * 基于 DataStore 的插件授权存储。
 *
 * 内存缓存 + 写穿：插件装载发生在启动早期且读多写少，避免每次解析槽位都打 DataStore。
 */
class DataStorePluginGrantStore(
    private val repository: PluginStateRepository,
) : PluginGrantStore {

    private val lock = Any()
    private val cache = mutableMapOf<String, PluginRecord>()
    private var loaded = false

    override suspend fun installed(): List<PluginRecord> {
        ensureLoaded()
        return synchronized(lock) { cache.values.sortedBy { it.manifest.name.lowercase() } }
    }

    override suspend fun find(pluginId: String): PluginRecord? {
        ensureLoaded()
        return synchronized(lock) { cache[pluginId] }
    }

    override suspend fun save(record: PluginRecord) {
        ensureLoaded()
        synchronized(lock) { cache[record.manifest.id] = record }
        repository.write(record.manifest.id, PluginStateCodec.encode(record))
    }

    override suspend fun remove(pluginId: String) {
        ensureLoaded()
        synchronized(lock) { cache.remove(pluginId) }
        repository.delete(pluginId)
    }

    override suspend fun setCapabilityGranted(pluginId: String, capabilityId: String, granted: Boolean) {
        mutate(pluginId) { current ->
            val caps = current.grantedCapabilities.toMutableSet()
            if (granted) caps += capabilityId else caps -= capabilityId
            current.copy(grantedCapabilities = caps)
        }
    }

    override suspend fun setOverrideApproved(pluginId: String, slotId: String, approved: Boolean) {
        mutate(pluginId) { current ->
            val slots = current.approvedSlots.toMutableSet()
            if (approved) slots += slotId else slots -= slotId
            current.copy(approvedSlots = slots)
        }
    }

    override suspend fun recordCrash(pluginId: String, timestampMs: Long): Int {
        var retained: List<Long> = emptyList()
        mutate(pluginId) { current ->
            retained = PluginSafetyPolicy.retainedCrashes(current.crashTimestamps + timestampMs, timestampMs)
            current.copy(crashTimestamps = retained)
        }
        return retained.size
    }

    override suspend fun setAutoDisabled(pluginId: String, disabled: Boolean, reason: String?) {
        mutate(pluginId) { current ->
            current.copy(
                autoDisabled = disabled,
                autoDisabledReason = reason,
                crashTimestamps = if (disabled) current.crashTimestamps else emptyList(),
            )
        }
    }

    override suspend fun appendAudit(event: PluginAuditEvent) = repository.appendAudit(AuditCodec.encode(event))

    override suspend fun auditHistory(limit: Int): List<PluginAuditEvent> =
        AuditCodec.decodeList(repository.readAudit()).take(limit)

    private suspend fun mutate(pluginId: String, transform: (PluginRecord) -> PluginRecord) {
        val current = find(pluginId) ?: return
        save(transform(current))
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val stored = repository.readAll()
        val decoded = stored.mapNotNull { (id, raw) -> PluginStateCodec.decode(raw)?.let { id to it } }.toMap()
        synchronized(lock) {
            cache.clear()
            cache.putAll(decoded)
        }
        loaded = true
    }

    /** 审计行编解码：整条日志按行存 JSON，避免为审计单独建表。 */
    private object AuditCodec {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(event: PluginAuditEvent): String = json.encodeToString(
            event.toLine(),
        )

        fun decodeList(raw: String): List<PluginAuditEvent> = raw.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { json.decodeFromString<AuditLine>(line) }.getOrNull() }
            .map { it.toEvent() }
            .reversed()

        private fun PluginAuditEvent.toLine() = AuditLine(timestampMs, pluginId, action.name, slotId, detail)

        @Serializable
        private data class AuditLine(
            val t: Long,
            val p: String,
            val a: String,
            val s: String? = null,
            val d: String = "",
        ) {
            fun toEvent() = PluginAuditEvent(
                timestampMs = t,
                pluginId = p,
                action = runCatching { PluginAuditAction.valueOf(a) }.getOrDefault(PluginAuditAction.LOAD_FAILED),
                slotId = s,
                detail = d,
            )
        }
    }
}
