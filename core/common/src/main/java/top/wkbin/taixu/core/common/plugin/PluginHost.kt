package top.wkbin.taixu.core.common.plugin

/**
 * 插件入口接口：进程内插件（runtime=NATIVE_DEX）必须实现它。
 *
 * 装载契约（由宿主 tools 模块的 DexClassLoader 执行）：
 * 1. 宿主按清单 entryClass 反射实例化（必须保留无参构造）；
 * 2. 宿主注入 [PluginHost] 门面，插件由此获得**宿主进程的全部权限**；
 * 3. 宿主在调用 [onAttach] 前已完成能力校验与用户同意，插件越权调用会被 [PluginHost.require] 拒绝；
 * 4. 插件被撤回授权 / 卸载 / 崩溃降级时，宿主调用 [onDetach]，插件必须释放窗口、服务与线程。
 */
interface TaiXuPlugin {
    /** 插件 ID，必须与清单一致；不一致时宿主拒绝装载。 */
    val id: String

    /** 展示名（可与清单 name 不同，最终以清单为准由宿主覆盖）。 */
    val name: String get() = id

    /** 插件自身版本号，仅用于展示与排障。 */
    val version: String get() = "0.1.0"

    /** 装载完成，插件可以开始注册扩展点与启动后台逻辑。 */
    fun onAttach(host: PluginHost)

    /** 卸载/停用；必须幂等，允许被多次调用。 */
    fun onDetach() = Unit
}

/** 插件运行日志级别。 */
enum class PluginLogLevel { DEBUG, INFO, WARN, ERROR }

/** 能力授予状态。 */
enum class PluginGrantState {
    /** 已授予，可直接使用。 */
    GRANTED,

    /** 用户此前明确拒绝。 */
    DENIED,

    /** 需要用户去系统设置页开启（悬浮窗/无障碍/使用情况等）。 */
    NEEDS_SYSTEM_SETTINGS,

    /** 宿主清单无法承载该权限，必须安装伴生 APK。 */
    REQUIRES_COMPANION,

    /** 未知/不可判定。 */
    UNKNOWN,
}

/** 插件越权访问能力时宿主抛出的异常（宿主捕获后记录审计并降级）。 */
class PluginCapabilityDeniedException(
    val pluginId: String,
    val capability: PluginCapability,
) : SecurityException("插件 $pluginId 未获授权能力 ${capability.id}")

/**
 * 宿主交给插件的能力门面。
 *
 * 每个插件实例拿到的是**绑定了自身 pluginId 的独立门面**，注册与审计天然隔离；
 * 门面内部按能力门禁：未授予的能力既拿不到桥对象，调用 [require] 也会抛
 * [PluginCapabilityDeniedException]。
 */
interface PluginHost {
    /** 宿主应用 Context —— 这就是「插件拥有软件一切权限」的技术落点，等同宿主自身 UID/清单权限。 */
    val context: android.content.Context

    /** 当前插件 ID。 */
    val pluginId: String

    /** 已被用户批准的能力集合（只读快照）。 */
    val grantedCapabilities: Set<PluginCapability>

    /** 是否已授予某能力。 */
    fun has(capability: PluginCapability): Boolean

    /** 断言已授予，否则抛 [PluginCapabilityDeniedException]。 */
    fun require(capability: PluginCapability) {
        if (!has(capability)) throw PluginCapabilityDeniedException(pluginId, capability)
    }

    /**
     * 查询能力当前状态（不弹窗）。
     * 宿主据此区分「运行时权限可弹窗」「需跳系统设置页」「必须装伴生 APK」。
     */
    fun capabilityState(capability: PluginCapability): PluginGrantState

    /**
     * 代表插件发起权限申请：运行时权限走系统弹窗，特殊访问跳设置页，
     * 宿主清单未声明的权限则引导安装伴生 APK。结果回调给插件，同时写进宿主授权库。
     */
    fun requestCapability(capability: PluginCapability, callback: (PluginGrantState) -> Unit)

    /** 注册一个扩展实现到指定槽位；返回句柄用于注销。 */
    fun register(slot: PluginSlot<out PluginExtension>, impl: PluginExtension, priority: Int = 0): PluginRegistration

    /** 注销此前注册的扩展实现。 */
    fun unregister(registration: PluginRegistration)

    /** 写宿主统一日志（带插件前缀，便于审计与崩溃归因）。 */
    fun log(level: PluginLogLevel, message: String)

    // ---- 受能力门禁的宿主服务桥；未授予对应能力时返回 null ----

    /** Linux 沙箱执行桥，需要 LINUX_EXEC。 */
    val linux: PluginLinuxBridge? get() = null

    /** 宿主数据/共享存储桥，需要 SHARED_STORAGE_* 或 HOST_DATA_READ_WRITE。 */
    val storage: PluginStorageBridge? get() = null

    /** Agent 工具注册桥，需要 AGENT_TOOL_REGISTER。 */
    val agent: PluginAgentBridge? get() = null
}

/** 一次注册产生的句柄。 */
data class PluginRegistration(
    val slotId: String,
    val pluginId: String,
    val priority: Int,
    /** 注册序号，保证同优先级下的稳定次序。 */
    val sequence: Long,
) {
    val key: String get() = "$slotId#$pluginId#$sequence"
}

/** Linux 沙箱执行桥（实现位于 tools/app，依赖倒置，core:common 不依赖 runtime）。 */
interface PluginLinuxBridge {
    /** 在沙箱内执行一条命令；实现方必须施加超时与输出上限。 */
    suspend fun exec(command: String, args: List<String> = emptyList(), timeoutMs: Long = 30_000L): PluginExecResult
}

data class PluginExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val success: Boolean get() = exitCode == 0
}

/** 存储桥：插件私有 KV + 受门禁的共享文件读写。 */
interface PluginStorageBridge {
    fun readKey(key: String): String?
    fun writeKey(key: String, value: String)
    fun readSharedFile(path: String): ByteArray?
    fun writeSharedFile(path: String, bytes: ByteArray): Boolean
}

/** Agent 工具注册桥。 */
interface PluginAgentBridge {
    /** 向 Agent 注册一个可被模型调用的工具；返回是否注册成功。 */
    fun registerTool(
        name: String,
        description: String,
        handler: suspend (args: Map<String, String>) -> String,
    ): Boolean

    fun unregisterTool(name: String)
}
