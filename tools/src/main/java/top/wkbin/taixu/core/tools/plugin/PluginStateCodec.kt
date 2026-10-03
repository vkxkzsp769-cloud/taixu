package top.wkbin.taixu.core.tools.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.tools.plugin.PluginRecord
import top.wkbin.taixu.core.model.plugin.PluginManifest

/** 持久化用的插件状态 DTO：与 [PluginRecord] 一一对应，字段变化需保持向后兼容。 */
@Serializable
private data class PluginStateJson(
    val manifest: PluginManifest,
    val installedAtMs: Long,
    val codePath: String? = null,
    val enabled: Boolean = true,
    val grantedCapabilities: List<String> = emptyList(),
    val approvedSlots: List<String> = emptyList(),
    val crashTimestamps: List<Long> = emptyList(),
    val autoDisabled: Boolean = false,
    val autoDisabledReason: String? = null,
)

/** 插件状态 JSON 编解码（放在 tools 层，避免 core:datastore 反向依赖 core:common）。 */
object PluginStateCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun encode(record: PluginRecord): String = json.encodeToString(
        PluginStateJson(
            manifest = record.manifest,
            installedAtMs = record.installedAtMs,
            codePath = record.codePath,
            enabled = record.enabled,
            grantedCapabilities = record.grantedCapabilities.sorted(),
            approvedSlots = record.approvedSlots.sorted(),
            crashTimestamps = record.crashTimestamps.sorted(),
            autoDisabled = record.autoDisabled,
            autoDisabledReason = record.autoDisabledReason,
        ),
    )

    /** 解析失败返回 null：损坏的状态记录不应阻塞其它插件装载。 */
    fun decode(raw: String): PluginRecord? = runCatching {
        val dto = json.decodeFromString<PluginStateJson>(raw)
        PluginRecord(
            manifest = dto.manifest,
            installedAtMs = dto.installedAtMs,
            codePath = dto.codePath,
            enabled = dto.enabled,
            grantedCapabilities = dto.grantedCapabilities.toSet(),
            approvedSlots = dto.approvedSlots.toSet(),
            crashTimestamps = dto.crashTimestamps,
            autoDisabled = dto.autoDisabled,
            autoDisabledReason = dto.autoDisabledReason,
        )
    }.getOrNull()

    /** 解析插件包内的 manifest.json（v1 工具清单会被识别为 schemaVersion=1 并拒绝）。 */
    fun decodeManifest(raw: String): PluginManifest? = runCatching {
        json.decodeFromString<PluginManifest>(raw)
    }.getOrNull()
}
