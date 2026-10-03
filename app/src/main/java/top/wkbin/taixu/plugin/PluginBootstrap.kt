package top.wkbin.taixu.plugin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import top.wkbin.taixu.core.common.plugin.FloatingWindowExtension
import top.wkbin.taixu.core.common.plugin.PluginHost
import top.wkbin.taixu.core.common.plugin.PluginSlots
import top.wkbin.taixu.core.tools.plugin.AppContextHolder
import top.wkbin.taixu.core.tools.plugin.BUILTIN_PLUGIN_ID
import top.wkbin.taixu.core.tools.plugin.PluginHostManager
import top.wkbin.taixu.ui.chat.floating.FloatingChatService

/**
 * 宿主能力插件的启动装配。
 *
 * 做三件事，顺序有讲究：
 * 1. 写入 [AppContextHolder]，让非 UI 线程也能拿到 Application Context；
 * 2. **先把内置实现登记进槽位**（最低优先级），保证任何时刻都有兜底；
 * 3. 再异步装载已获批插件——装载失败/权限不足只会留下审计，不影响第一帧与既有功能。
 */
class PluginBootstrap(
    context: Context,
    private val manager: PluginHostManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        AppContextHolder.context = context.applicationContext
        manager.registerBuiltin(PluginSlots.FLOATING_WINDOW, BuiltinFloatingWindow())
        scope.launch {
            runCatching { manager.bootstrap() }
                .onFailure { Log.w(TAG, "插件装载异常（已忽略，内置功能照常）", it) }
        }
    }

    private companion object {
        const val TAG = "TaiXuPluginBootstrap"
    }
}

/**
 * 内置悬浮窗实现：以 `builtin` 身份参与同一套槽位仲裁。
 *
 * 意义在于「顶掉内置」不是靠 if/else 硬编码，而是让内置成为优先级最低的候选——
 * 任何获批插件注册同槽位即自然接管，撤回授权后无需改代码就自动回到这里。
 */
private class BuiltinFloatingWindow : FloatingWindowExtension {

    override val pluginId: String = BUILTIN_PLUGIN_ID

    override val displayName: String = "太墟内置悬浮窗"

    override fun show(context: android.content.Context, host: PluginHost?): Boolean = runCatching {
        FloatingChatService.start(context)
        true
    }.getOrDefault(false)

    override fun hide() {
        AppContextHolder.context?.let { FloatingChatService.stop(it) }
    }

    override val isShowing: Boolean get() = FloatingChatService.isRunning
}
