package top.wkbin.taixu.ui.chat.floating

import android.content.Context
import org.koin.core.component.KoinComponent
import top.wkbin.taixu.core.tools.plugin.FloatingWindowDelegate
import top.wkbin.taixu.core.tools.plugin.FloatingWindowOutcome

/**
 * 悬浮窗接管入口。
 *
 * UI 层不再直接 `FloatingChatService.start()`，而是走这里：由宿主能力插件体系仲裁
 * 「这个槽位现在归谁」——获批插件优先，否则内置实现，插件抛异常则计入崩溃并回退内置。
 *
 * 拿不到 delegate（Koin 未就绪、单元测试、老流程）时**行为与改造前完全一致**，直接起内置服务。
 */
object ChatFloatingWindow : KoinComponent {

    private val delegate: FloatingWindowDelegate?
        get() = runCatching { get<FloatingWindowDelegate>() }.getOrNull()

    /** 收起会话到悬浮窗。返回接管结果，供 UI 决定是否退到后台。 */
    suspend fun collapse(context: Context): FloatingWindowOutcome {
        val current = delegate ?: run {
            FloatingChatService.start(context)
            return FloatingWindowOutcome.NO_PROVIDER
        }
        val outcome = current.show(context)
        if (outcome == FloatingWindowOutcome.NO_PROVIDER) FloatingChatService.start(context)
        return outcome
    }

    /** 从悬浮窗回到主界面：收起插件或内置图层。 */
    suspend fun hide(): FloatingWindowOutcome {
        val current = delegate ?: run {
            top.wkbin.taixu.core.tools.plugin.AppContextHolder.context?.let { FloatingChatService.stop(it) }
            return FloatingWindowOutcome.NO_PROVIDER
        }
        return current.hide()
    }

    /** 当前由谁提供悬浮窗（内置 ID 或插件 ID），供设置页/接管中心展示。 */
    fun currentProvider(): String = delegate?.currentProvider() ?: "builtin"
}
