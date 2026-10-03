package top.wkbin.taixu.core.tools.plugin

import android.content.Context
import top.wkbin.taixu.core.common.plugin.FloatingWindowExtension
import top.wkbin.taixu.core.common.plugin.PluginSlots

/** 悬浮窗接管结果，供 UI 反馈与日志归因。 */
enum class FloatingWindowOutcome {
    /** 已展示（provider 见 currentProvider）。 */
    SHOWN,

    /** 已收起。 */
    HIDDEN,

    /** 没有任何实现（内置也未登记）。 */
    NO_PROVIDER,

    /** 插件接管失败，已自动回退到内置实现。 */
    FALLBACK_TO_BUILTIN,

    /** 实现方明确拒绝展示（例如缺少悬浮窗权限）。 */
    REFUSED,
}

/**
 * 悬浮窗接管点：UI 层只调用它，由它决定「用插件实现还是用内置实现」。
 *
 * 这是「插件顶掉软件自带功能」的第一个落地样本：
 * ① 仲裁在 [PluginHostManager.resolve]（四条件：启用/未自动停用/槽位获批/已授予接管能力）；
 * ② 插件代码在 [PluginHostManager.guard] 内执行，抛异常即计入崩溃并回退内置；
 * ③ 内置实现由宿主装配层以最低优先级登记，因此永远存在兜底路径。
 */
class FloatingWindowDelegate(private val manager: PluginHostManager) {

    /** 当前生效的实现提供方（插件 ID，未接管时为内置保留 ID）。 */
    fun currentProvider(): String =
        manager.resolve(PluginSlots.FLOATING_WINDOW)?.pluginId ?: BUILTIN_PLUGIN_ID

    suspend fun toggle(context: Context): FloatingWindowOutcome {
        val extension = manager.resolve(PluginSlots.FLOATING_WINDOW) ?: return FloatingWindowOutcome.NO_PROVIDER
        if (extension.isShowing) return hide()
        return show(extension)
    }

    suspend fun show(context: Context): FloatingWindowOutcome {
        val extension = manager.resolve(PluginSlots.FLOATING_WINDOW) ?: return FloatingWindowOutcome.NO_PROVIDER
        return show(extension)
    }

    suspend fun hide(): FloatingWindowOutcome {
        val extension = manager.resolve(PluginSlots.FLOATING_WINDOW) ?: return FloatingWindowOutcome.NO_PROVIDER
        manager.guard(extension.pluginId, PluginSlots.FLOATING_WINDOW.id) { extension.hide() }
        return FloatingWindowOutcome.HIDDEN
    }

    private suspend fun show(extension: FloatingWindowExtension): FloatingWindowOutcome {
        val isBuiltin = extension.pluginId == BUILTIN_PLUGIN_ID
        val accepted = manager.guard(extension.pluginId, PluginSlots.FLOATING_WINDOW.id) {
            extension.show(manager.applicationContext(), manager.facadeOf(extension.pluginId))
        }
        if (accepted == true) return FloatingWindowOutcome.SHOWN
        if (accepted == false && !isBuiltin) {
            // 插件自己承认没建起来：立刻回退内置，避免用户点了没反应。
            manager.resolve(PluginSlots.FLOATING_WINDOW)
                ?.takeIf { it.pluginId == BUILTIN_PLUGIN_ID }
                ?.let { builtin ->
                    manager.guard(BUILTIN_PLUGIN_ID, PluginSlots.FLOATING_WINDOW.id) {
                        builtin.show(manager.applicationContext(), null)
                    }
                }
            return FloatingWindowOutcome.FALLBACK_TO_BUILTIN
        }
        if (accepted == false) return FloatingWindowOutcome.REFUSED
        return FloatingWindowOutcome.FALLBACK_TO_BUILTIN // guard 捕获到异常，已计入崩溃
    }
}
