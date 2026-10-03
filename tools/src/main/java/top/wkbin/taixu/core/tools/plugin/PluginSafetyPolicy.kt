package top.wkbin.taixu.core.tools.plugin

import top.wkbin.taixu.core.model.plugin.PluginManifest

/**
 * 插件安全策略（纯函数，无 Android 依赖，可全量单测）。
 *
 * 这是「插件拥有宿主一切权限」这条需求的**唯一闸门**：装载期校验、同意强度、
 * 接管资格判定、崩溃自动降级都集中在这里，避免规则散落到 UI 与装载器中。
 */
object PluginSafetyPolicy {

    private val idPattern = Regex("[a-z0-9][a-z0-9-]{1,63}")
    private val sha256Pattern = Regex("[0-9a-fA-F]{64}")

    /** 时间窗内连续崩溃达到该次数即自动停用插件并回退内置实现。 */
    const val MAX_CRASHES_IN_WINDOW = 3

    /** 崩溃计数窗口：5 分钟。 */
    const val CRASH_WINDOW_MS = 5 * 60 * 1000L

    /**
     * 清单校验。返回违规说明列表，空列表代表通过。
     *
     * @param hostVersionCode 宿主当前 versionCode，用于拒绝声明了更高下限的插件
     * @param knownSlotIds 宿主已知槽位 ID 集合（含装配层新增的 Compose 槽位）
     */
    fun validateManifest(
        manifest: PluginManifest,
        hostVersionCode: Int,
        knownSlotIds: Set<String>,
    ): List<String> {
        val errors = mutableListOf<String>()

        if (manifest.schemaVersion != PluginManifest.CURRENT_SCHEMA_VERSION) {
            errors += "不支持的插件清单 Schema：${manifest.schemaVersion}"
        }
        if (!idPattern.matches(manifest.id)) errors += "非法插件 ID：${manifest.id}"
        if (manifest.name.isBlank()) errors += "插件名称不能为空：${manifest.id}"
        if (manifest.version.isBlank()) errors += "插件版本不能为空：${manifest.id}"
        if (manifest.publisher.length > 128) errors += "插件发布者名称过长：${manifest.id}"
        if (manifest.runtime !in PluginManifest.Runtime.all) {
            errors += "不支持的插件运行形态：${manifest.runtime}"
        }
        if (manifest.source !in setOf(PluginManifest.SOURCE_REMOTE, PluginManifest.SOURCE_LOCAL)) {
            errors += "不支持的插件来源：${manifest.source}"
        }
        if (manifest.source == PluginManifest.SOURCE_LOCAL && !manifest.offlineOnly) {
            errors += "本地插件必须声明 offlineOnly=true：${manifest.id}"
        }

        when (manifest.runtime) {
            PluginManifest.Runtime.NATIVE_DEX -> {
                if (manifest.entryClass.isNullOrBlank()) {
                    errors += "进程内插件必须声明 entryClass：${manifest.id}"
                }
                if (manifest.codeEntry.isNullOrBlank()) {
                    errors += "进程内插件必须声明 codeEntry（payload 内的 dex/jar 路径）：${manifest.id}"
                }
            }
            PluginManifest.Runtime.COMPANION_APK -> {
                if (manifest.companionPackage.isNullOrBlank()) {
                    errors += "伴生 APK 插件必须声明 companionPackage：${manifest.id}"
                }
            }
            else -> Unit
        }

        // 携带可执行代码的插件必须有完整性摘要，防止半包/篡改装载。
        if (!manifest.codeEntry.isNullOrBlank() && manifest.codeSha256?.matches(sha256Pattern) != true) {
            errors += "插件代码包缺少合法的 SHA-256 摘要：${manifest.id}"
        }

        manifest.capabilities.filter { it !in PluginCapability.ids }.forEach {
            errors += "不支持的插件能力：$it"
        }
        if (manifest.capabilities.size != manifest.capabilities.distinct().size) {
            errors += "插件能力声明重复：${manifest.id}"
        }
        manifest.extensionPoints.filter { it !in knownSlotIds }.forEach {
            errors += "未知的扩展点：$it"
        }
        if (manifest.extensionPoints.isNotEmpty() && PluginCapability.UI_SLOT_OVERRIDE.id !in manifest.capabilities) {
            errors += "申请接管扩展点但未声明 ${PluginCapability.UI_SLOT_OVERRIDE.id} 能力：${manifest.id}"
        }
        if (manifest.runtime == PluginManifest.Runtime.COMPANION_APK &&
            PluginCapability.COMPANION_PERMISSION_PROXY.id !in manifest.capabilities &&
            manifest.capabilities.any { PluginCapability.byId(it)?.grantMode == PluginGrantMode.COMPANION_ONLY }
        ) {
            errors += "使用伴生 APK 权限但未声明 companion_permission_proxy：${manifest.id}"
        }
        if (manifest.overridePriority <= PluginManifest.BUILTIN_PRIORITY) {
            errors += "接管优先级非法（不得占用内置保留值）：${manifest.id}"
        }
        if (manifest.minHostVersionCode > hostVersionCode) {
            errors += "插件要求宿主 versionCode ≥ ${manifest.minHostVersionCode}，当前 $hostVersionCode"
        }
        return errors
    }

    /** 该清单声明的高风险能力（HIGH/CRITICAL），用于安装期逐条明示。 */
    fun riskyCapabilities(manifest: PluginManifest): List<PluginCapability> =
        manifest.capabilities.mapNotNull { PluginCapability.byId(it) }
            .filter { it.risk != PluginRisk.NORMAL }
            .sortedByDescending { it.risk == PluginRisk.CRITICAL }

    /** 安装期需要的同意强度。 */
    fun consentLevel(manifest: PluginManifest): PluginConsentLevel = when {
        manifest.runtime != PluginManifest.Runtime.SANDBOX_TOOL &&
            manifest.capabilities.mapNotNull { PluginCapability.byId(it) }.any { it.risk == PluginRisk.CRITICAL } ->
            PluginConsentLevel.EXPLICIT_FULL_PRIVILEGE

        riskyCapabilities(manifest).isNotEmpty() -> PluginConsentLevel.GENERAL
        else -> PluginConsentLevel.NONE
    }

    /** 判定插件能否接管某槽位。 */
    fun canClaimSlot(manifest: PluginManifest, slot: PluginSlot<*>): SlotVerdict {
        if (slot.id !in manifest.extensionPoints) return SlotVerdict.NotRequested
        val caps = manifest.capabilities.mapNotNull { PluginCapability.byId(it) }.toSet()
        if (slot.hasBuiltin && PluginCapability.UI_SLOT_OVERRIDE !in caps) {
            return SlotVerdict.Denied("该扩展点存在内置实现，需要 ${PluginCapability.UI_SLOT_OVERRIDE.id} 能力")
        }
        return SlotVerdict.Allowed
    }

    /** 只保留时间窗内的崩溃记录。 */
    fun retainedCrashes(timestamps: List<Long>, nowMs: Long, windowMs: Long = CRASH_WINDOW_MS): List<Long> =
        timestamps.filter { it > nowMs - windowMs }.sorted()

    /** 时间窗内崩溃次数是否已达到自动停用阈值。 */
    fun shouldAutoDisable(timestamps: List<Long>, nowMs: Long, windowMs: Long = CRASH_WINDOW_MS): Boolean =
        retainedCrashes(timestamps, nowMs, windowMs).size >= MAX_CRASHES_IN_WINDOW

    /**
     * 某插件实现能否作为槽位生效实现：既要「用户批准过接管」，也要「未处于自动停用状态」。
     * 纯函数便于单测，实际状态由 [PluginGrantStore] 提供。
     */
    fun isEffective(
        pluginEnabled: Boolean,
        overrideApproved: Boolean,
        autoDisabled: Boolean,
        capabilitiesGranted: Boolean,
    ): Boolean = pluginEnabled && overrideApproved && capabilitiesGranted && !autoDisabled
}

/** 安装期同意强度。 */
enum class PluginConsentLevel {
    /** 无需额外同意（纯沙箱工具形态）。 */
    NONE,

    /** 展示能力清单并请求一次确认。 */
    GENERAL,

    /** 涉及 CRITICAL 能力/顶掉内置：必须逐条明示「插件将拥有宿主全部权限」并要求二次确认。 */
    EXPLICIT_FULL_PRIVILEGE,
}

/** 扩展点接管资格判定结果。 */
sealed class SlotVerdict {
    data object Allowed : SlotVerdict()
    data object NotRequested : SlotVerdict()
    data class Denied(val reason: String) : SlotVerdict()
}
