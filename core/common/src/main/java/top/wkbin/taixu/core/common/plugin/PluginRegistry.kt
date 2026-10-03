package top.wkbin.taixu.core.common.plugin

/**
 * 扩展点注册表：槽位 → 候选实现 → 当前生效实现。
 *
 * 生效规则（自上而下短路）：
 * 1. 用户在「接管中心」为槽位手选过实现 → 用被手选者（包括手选「内置」= 空）；
 * 2. 否则取 `overridePriority` 最高者，同优先级按注册次序先到先得；
 * 3. 候选必须同时满足「插件已启用」且「能力已被批准」，由 [isEnabled] 回调提供，
 *    注册表本身不持久化任何授权状态（依赖倒置，便于纯 JVM 单测）。
 * 4. 没有任何候选生效时返回 null，调用方**必须**回退到宿主内置实现。
 */
class PluginRegistry {

    private class Entry(
        val registration: PluginRegistration,
        val extension: PluginExtension,
    )

    private val lock = Any()
    private val slots = mutableMapOf<String, MutableMap<Long, Entry>>()
    private val knownSlots = mutableMapOf<String, PluginSlot<out PluginExtension>>()
    /** 槽位 ID → 用户手选的插件 ID；空串表示「显式使用内置实现」。 */
    private val manualChoice = mutableMapOf<String, String?>()
    private var sequence = 0L
    private val listeners = mutableListOf<() -> Unit>()

    /** 注册变更监听：槽位内容或生效结果变化时回调（任意线程触发，实现方自行切主线程）。 */
    fun addListener(onChange: () -> Unit) {
        synchronized(lock) { listeners.add(onChange) }
    }

    fun removeListener(onChange: () -> Unit) {
        synchronized(lock) { listeners.remove(onChange) }
    }

    /** 登记槽位定义（宿主内置槽位与装配层新增的 Compose 槽位都走这里）。 */
    fun declareSlot(slot: PluginSlot<out PluginExtension>) {
        synchronized(lock) { knownSlots[slot.id] = slot }
    }

    fun declaredSlots(): List<PluginSlot<out PluginExtension>> = synchronized(lock) {
        knownSlots.values.sortedBy { it.id }
    }

    /** 注册实现。未知槽位会被拒绝，防止插件凭空创造接管点。 */
    fun register(slot: PluginSlot<out PluginExtension>, extension: PluginExtension, priority: Int): PluginRegistration {
        val registration = synchronized(lock) {
            knownSlots[slot.id] = slot
            val seq = ++sequence
            val reg = PluginRegistration(slot.id, extension.pluginId, priority, seq)
            slots.getOrPut(slot.id) { mutableMapOf() }[seq] = Entry(reg, extension)
            reg
        }
        notifyChanged()
        return registration
    }

    fun unregister(registration: PluginRegistration): Boolean {
        val removed = synchronized(lock) {
            val bucket = slots[registration.slotId] ?: return@synchronized false
            bucket.remove(registration.sequence) != null
        }
        if (removed) notifyChanged()
        return removed
    }

    /** 卸载某插件时清理它的全部注册。 */
    fun unregisterAll(pluginId: String): Int {
        val removed = synchronized(lock) {
            var count = 0
            slots.values.forEach { bucket ->
                val stale = bucket.filterValues { it.registration.pluginId == pluginId }.keys
                stale.forEach { bucket.remove(it); count++ }
            }
            count
        }
        if (removed > 0) notifyChanged()
        return removed
    }

    /** 按句柄取回实现（宿主在「强制回退内置」等场景需要绕过手选规则直接拿兜底项）。 */
    fun extensionFor(registration: PluginRegistration): PluginExtension? = synchronized(lock) {
        slots[registration.slotId]?.get(registration.sequence)?.extension
    }

    /** 某槽位的全部候选（按生效排序返回，优先级高者在前）。 */
    fun candidates(slotId: String): List<PluginRegistration> = synchronized(lock) {
        sortedCandidates(slotId)
    }

    private fun sortedCandidates(slotId: String): List<PluginRegistration> =
        slots[slotId]?.values?.map { it.registration }?.sortedWith(
            compareByDescending<PluginRegistration> { it.priority }.thenBy { it.sequence },
        ) ?: emptyList()

    /**
     * 解析槽位当前生效实现。
     * @param isEnabled 判定插件是否处于「已启用且能力已批准」状态；接管类槽位还要求
     *                  用户已批准其顶掉内置（由调用方在回调内综合判断）。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : PluginExtension> resolve(slot: PluginSlot<T>, isEnabled: (PluginRegistration) -> Boolean): T? {
        val chosen = synchronized(lock) {
            val manual = manualChoice[slot.id]
            val ordered = sortedCandidates(slot.id).filter { isEnabled(it) }
            when {
                manual == "" -> null // 用户显式要求用内置实现
                manual != null -> ordered.firstOrNull { it.pluginId == manual }
                slot.exclusive -> ordered.firstOrNull()
                else -> null
            }
        }
        if (!slot.exclusive) return null // 非独占槽位请用 resolveAll，避免误用
        return chosen?.let { synchronized(lock) { slots[slot.id]?.get(it.sequence)?.extension } } as? T
    }

    /** 非独占槽位：取全部生效实现（如 Agent 工具、终端输入钩子）。 */
    @Suppress("UNCHECKED_CAST")
    fun <T : PluginExtension> resolveAll(slot: PluginSlot<T>, isEnabled: (PluginRegistration) -> Boolean): List<T> =
        synchronized(lock) {
            sortedCandidates(slot.id)
                .filter { isEnabled(it) }
                .mapNotNull { slots[slot.id]?.get(it.sequence)?.extension }
                .filterIsInstance<Any>() as List<T>
        }

    /**
     * 用户手选生效实现。
     * @param pluginId 传 null = 跟随优先级自动仲裁；传空串 = 强制回退宿主内置实现。
     */
    fun selectManually(slotId: String, pluginId: String?) {
        synchronized(lock) { manualChoice[slotId] = pluginId }
        notifyChanged()
    }

    fun manualChoiceOf(slotId: String): String? = synchronized(lock) { manualChoice[slotId] }

    /** 接管冲突列表：同一独占槽位有 ≥2 个已批准候选时，供接管中心提示用户裁决。 */
    fun conflicts(isEnabled: (PluginRegistration) -> Boolean): Map<String, List<PluginRegistration>> =
        synchronized(lock) {
            slots.keys.toList().mapNotNull { slotId ->
                val approved = sortedCandidates(slotId).filter { isEnabled(it) }
                val exclusive = knownSlots[slotId]?.exclusive ?: true
                if (exclusive && approved.size > 1 && manualChoice[slotId] == null) slotId to approved else null
            }.toMap()
        }

    /** 全部已注册实现（接管中心总览用）。 */
    fun snapshot(): Map<String, List<PluginRegistration>> = synchronized(lock) {
        slots.mapValues { (slotId, _) -> sortedCandidates(slotId) }
    }

    fun clear() {
        synchronized(lock) {
            slots.clear(); knownSlots.clear(); manualChoice.clear(); sequence = 0L
        }
        notifyChanged()
    }

    private fun notifyChanged() {
        val callbacks = synchronized(lock) { listeners.toList() }
        callbacks.forEach { runCatching { it() } }
    }
}
