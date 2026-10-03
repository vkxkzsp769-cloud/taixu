package top.wkbin.taixu.core.tools.plugin

/**
 * 扩展点（槽位）：宿主在内置实现之外预留的「可被插件顶掉」的位置。
 *
 * 槽位是**类型化键**：[T] 必须是 [PluginExtension] 的子接口，注册与解析都按槽位 ID 隔离，
 * 插件无法写宿主没预留的槽位（未知 ID 会在校验期被拒），从而把「顶掉内置」限制在显式审计过的范围内。
 *
 * 需要 Compose 类型的界面槽位（如仪表盘面板）不在本模块声明——core:common 不依赖 Compose，
 * 由装配层（feature:navigation / app）自行声明 `val XXX: PluginSlot<其扩展接口>` 即可复用同一套注册表。
 */
class PluginSlot<out T : PluginExtension>(
    /** 稳定 ID，命名约定 `slot.对象.动作`，一经发布不得改名。 */
    val id: String,
    val label: String,
    val description: String,
    /** 是否独占：true 时同时只有一个实现生效（取优先级最高且被用户批准者）。 */
    val exclusive: Boolean,
    /** 宿主是否自带默认实现：为 true 时插件顶掉它需要 UI_SLOT_OVERRIDE 能力 + 用户批准。 */
    val hasBuiltin: Boolean,
) {
    override fun toString(): String = "PluginSlot($id)"

    override fun equals(other: Any?): Boolean = other is PluginSlot<*> && other.id == id

    override fun hashCode(): Int = id.hashCode()
}

/** 扩展实现的种类，用于接管中心的展示与降级策略。 */
enum class PluginExtensionKind {
    /** 界面类：可见的窗口/面板/渲染器。 */
    UI,

    /** 服务类：常驻行为或系统级桥接。 */
    SERVICE,

    /** 能力类：给 Agent 或运行时新增工具与钩子。 */
    BEHAVIOR,
}

/** 插件可提供的扩展实现基接口。 */
interface PluginExtension {
    /** 提供方插件 ID（由宿主在装载时注入，插件不得伪造）。 */
    val pluginId: String

    /** 扩展点显示名，用于接管中心。 */
    val displayName: String get() = pluginId

    /** 实现种类。 */
    val kind: PluginExtensionKind get() = PluginExtensionKind.BEHAVIOR

    /** 被激活（成为槽位生效实现）时调用。 */
    fun onActivate(host: PluginHost) = Unit

    /** 被顶掉 / 卸载 / 撤回授权时调用，必须释放所有系统资源。 */
    fun onDeactivate() = Unit
}

/**
 * 宿主内置扩展点。新增内置槽位必须同时：
 * ① 在此登记；② 在接管中心给出「回退内置」按钮；③ 补一条 PluginSlotRegistry 单测。
 */
object PluginSlots {
    /**
     * 内置悬浮窗（宿主自带实现：feature:chat 的 FloatingChatService）。
     * 插件接管后，宿主的悬浮窗入口会改为调用插件实现，内置实现保留为可一键回退的兜底。
     */
    val FLOATING_WINDOW = PluginSlot<FloatingWindowExtension>(
        id = "slot.floating.window",
        label = "悬浮窗",
        description = "太墟内置的悬浮对话窗；插件可整体顶掉它",
        exclusive = true,
        hasBuiltin = true,
    )

    /** 表情球状态桥：接管后可自定义 Agent 状态在桌面上的呈现方式。 */
    val ORB_STATE_BRIDGE = PluginSlot<PluginExtension>(
        id = "slot.orb.bridge",
        label = "表情球状态桥",
        description = "把 Agent 运行状态映射为桌面表情球/灯效",
        exclusive = true,
        hasBuiltin = true,
    )

    /** 通知渲染器：接管宿主通知的构造与分组策略。 */
    val NOTIFICATION_RENDERER = PluginSlot<PluginExtension>(
        id = "slot.notification.renderer",
        label = "通知渲染器",
        description = "自定义 Agent 通知的样式、分组与静默策略",
        exclusive = true,
        hasBuiltin = true,
    )

    /** Agent 工具挂载点：非独占，允许多个插件同时注册工具。 */
    val AGENT_TOOL = PluginSlot<PluginExtension>(
        id = "slot.agent.tool",
        label = "Agent 工具",
        description = "向 AI Agent 暴露可被模型调用的工具",
        exclusive = false,
        hasBuiltin = false,
    )

    /** 终端命令钩子：非独占，可拦截/改写终端输入行。 */
    val TERMINAL_INPUT_HOOK = PluginSlot<PluginExtension>(
        id = "slot.terminal.hook",
        label = "终端输入钩子",
        description = "在命令送进 PTY 之前观察或改写它",
        exclusive = false,
        hasBuiltin = false,
    )

    val all: List<PluginSlot<PluginExtension>>
        get() = listOf(ORB_STATE_BRIDGE, NOTIFICATION_RENDERER, AGENT_TOOL, TERMINAL_INPUT_HOOK)

    /** 已知槽位 ID 集合（校验插件 extensionPoints 用；含带具体子类型的界面槽位）。 */
    val knownIds: Set<String> = setOf(
        FLOATING_WINDOW.id,
        ORB_STATE_BRIDGE.id,
        NOTIFICATION_RENDERER.id,
        AGENT_TOOL.id,
        TERMINAL_INPUT_HOOK.id,
    )

    fun isKnown(id: String): Boolean = id in knownIds
}

/** 悬浮窗扩展点接口：插件据此顶掉内置悬浮窗。 */
interface FloatingWindowExtension : PluginExtension {
    override val kind: PluginExtensionKind get() = PluginExtensionKind.UI

    /**
     * 展示悬浮窗；返回 false 表示插件未能创建（宿主应立即回退内置实现）。
     * @param context 宿主 Application Context
     * @param host 插件对应的宿主门面；内置实现没有门面，故可为 null（内置不依赖它）
     */
    fun show(context: android.content.Context, host: PluginHost?): Boolean

    /** 收起悬浮窗并释放图层。 */
    fun hide()

    /** 当前是否正在显示（宿主据此决定入口按钮的状态与去重）。 */
    val isShowing: Boolean
}
