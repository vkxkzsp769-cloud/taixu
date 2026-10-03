package top.wkbin.taixu.core.tools.plugin

import top.wkbin.taixu.core.tools.plugin.PluginCapability
import top.wkbin.taixu.core.tools.plugin.PluginRecord
import top.wkbin.taixu.core.tools.plugin.PluginRegistration
import top.wkbin.taixu.core.tools.plugin.PluginSafetyPolicy
import top.wkbin.taixu.core.tools.plugin.PluginSlot
import top.wkbin.taixu.core.model.plugin.PluginManifest

/**
 * 槽位生效仲裁（纯函数，无 Android 依赖，可全量单测）。
 *
 * 四条硬条件，缺一不可：
 * 1. 插件记录存在且处于「已启用 + 未被崩溃自动停用」；
 * 2. 用户为该插件批准了**这个**扩展点（不是笼统地批准插件）；
 * 3. 若该扩展点有内置实现，插件必须已获得 `ui_slot_override` 能力——
 *    「顶掉软件自带功能」是一条独立于「使用插件」的授权；
 * 4. 接管资格还须通过 [PluginSafetyPolicy.canClaimSlot] 的清单校验（未声明的槽位直接否）。
 *
 * 内置实现（[BUILTIN_PLUGIN_ID]）永远通过：它是兜底路径，不该被任何授权状态卡死。
 */
fun isRegistrationApproved(
    registration: PluginRegistration,
    slot: PluginSlot<*>,
    record: PluginRecord?,
): Boolean {
    if (registration.pluginId == BUILTIN_PLUGIN_ID) return true
    if (registration.slotId != slot.id) return false
    val current = record ?: return false
    if (!current.isLoaded) return false
    if (registration.slotId !in current.approvedSlots) return false
    if (!current.manifest.extensionPoints.contains(slot.id)) return false
    if (PluginSafetyPolicy.canClaimSlot(current.manifest, slot) is
        top.wkbin.taixu.core.tools.plugin.SlotVerdict.Denied
    ) return false
    if (slot.hasBuiltin && !current.hasCapability(PluginCapability.UI_SLOT_OVERRIDE)) return false
    return true
}

/** 该记录是否有资格参与任何接管（用于接管中心渲染灰色状态）。 */
fun canEverOverride(record: PluginRecord): Boolean =
    record.isLoaded &&
        record.manifest.extensionPoints.isNotEmpty() &&
        record.hasCapability(PluginCapability.UI_SLOT_OVERRIDE) &&
        record.manifest.overridePriority > PluginManifest.BUILTIN_PRIORITY
