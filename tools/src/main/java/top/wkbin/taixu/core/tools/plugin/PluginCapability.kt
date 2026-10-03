package top.wkbin.taixu.core.tools.plugin

/**
 * 宿主能力词表（插件可申请的权限）。
 *
 * 设计要点：
 * 1. 能力是**宿主侧的白名单**，插件只能申请、不能自授；未列入本表的能力 ID 一律校验失败。
 * 2. [grantMode] 说明该能力靠什么满足：Android 运行时权限、系统特殊访问页、宿主内部开关，
 *    还是必须由「伴生 APK」自带清单权限才能拿到（宿主清单安装期已冻结，无法动态加权限）。
 * 3. [risk] 决定安装期的同意强度：CRITICAL 必须逐条明示同意，且可在「接管中心」一键撤回。
 */
enum class PluginCapability(
    val id: String,
    val label: String,
    val description: String,
    val risk: PluginRisk,
    val grantMode: PluginGrantMode,
    /** 对应的 Android 权限名（无对应系统权限时为 null，纯宿主内部门禁）。 */
    val androidPermission: String? = null,
) {
    OVERLAY_WINDOW(
        id = "overlay_window",
        label = "悬浮窗",
        description = "在其它应用之上创建悬浮图层（接管内置悬浮窗时需要）",
        risk = PluginRisk.HIGH,
        grantMode = PluginGrantMode.HOST_SPECIAL_ACCESS,
        androidPermission = "android.permission.SYSTEM_ALERT_WINDOW",
    ),
    BACKGROUND_SERVICE(
        id = "background_service",
        label = "后台常驻",
        description = "由宿主托管插件的常驻服务生命周期，使其在后台继续运行",
        risk = PluginRisk.NORMAL,
        grantMode = PluginGrantMode.HOST_INTERNAL,
    ),
    POST_NOTIFICATIONS(
        id = "post_notifications",
        label = "发送通知",
        description = "在通知栏展示插件的通知与前台服务提醒",
        risk = PluginRisk.NORMAL,
        grantMode = PluginGrantMode.HOST_RUNTIME_PERMISSION,
        androidPermission = "android.permission.POST_NOTIFICATIONS",
    ),
    ACCESSIBILITY_CONTROL(
        id = "accessibility_control",
        label = "无障碍控制",
        description = "读取并操作其它应用界面，可模拟点击；能力极强",
        risk = PluginRisk.CRITICAL,
        grantMode = PluginGrantMode.HOST_SPECIAL_ACCESS,
    ),
    USAGE_STATS(
        id = "usage_stats",
        label = "使用情况读取",
        description = "读取前台应用与屏幕使用统计数据",
        risk = PluginRisk.HIGH,
        grantMode = PluginGrantMode.HOST_SPECIAL_ACCESS,
        androidPermission = "android.permission.PACKAGE_USAGE_STATS",
    ),
    LINUX_EXEC(
        id = "linux_exec",
        label = "沙箱命令执行",
        description = "在 Linux PRoot 沙箱内执行任意 Shell 命令",
        risk = PluginRisk.CRITICAL,
        grantMode = PluginGrantMode.HOST_INTERNAL,
    ),
    SHARED_STORAGE_READ(
        id = "shared_storage_read",
        label = "共享存储读取",
        description = "读取 /sdcard 共享存储中的文件",
        risk = PluginRisk.HIGH,
        grantMode = PluginGrantMode.HOST_INTERNAL,
    ),
    SHARED_STORAGE_WRITE(
        id = "shared_storage_write",
        label = "共享存储写入",
        description = "创建/修改 /sdcard 共享存储中的文件",
        risk = PluginRisk.HIGH,
        grantMode = PluginGrantMode.HOST_INTERNAL,
    ),
    NETWORK(
        id = "network",
        label = "网络访问",
        description = "向外部主机发起网络请求",
        risk = PluginRisk.NORMAL,
        grantMode = PluginGrantMode.HOST_INTERNAL,
    ),
    AGENT_TOOL_REGISTER(
        id = "agent_tool_register",
        label = "注册 Agent 工具",
        description = "向 AI Agent 暴露可被模型自动调用的工具，模型可能在无人值守时调用它",
        risk = PluginRisk.CRITICAL,
        grantMode = PluginGrantMode.HOST_INTERNAL,
    ),
    UI_SLOT_OVERRIDE(
        id = "ui_slot_override",
        label = "顶掉内置界面",
        description = "接管宿主内置功能（悬浮窗、面板、渲染器等）",
        risk = PluginRisk.CRITICAL,
        grantMode = PluginGrantMode.HOST_INTERNAL,
    ),
    PRIVILEGE_BRIDGE(
        id = "privilege_bridge",
        label = "提权桥",
        description = "通过 Shizuku / Root 通道执行特权操作",
        risk = PluginRisk.CRITICAL,
        grantMode = PluginGrantMode.HOST_INTERNAL,
    ),
    HOST_DATA_READ_WRITE(
        id = "host_data_read_write",
        label = "宿主数据读写",
        description = "读写宿主的会话、设置与数据库记录",
        risk = PluginRisk.CRITICAL,
        grantMode = PluginGrantMode.HOST_INTERNAL,
    ),
    COMPANION_PERMISSION_PROXY(
        id = "companion_permission_proxy",
        label = "伴生 APK 代理权限",
        description = "宿主清单未声明的 Android 权限，必须由插件自带的伴生 APK 申请并代为执行",
        risk = PluginRisk.HIGH,
        grantMode = PluginGrantMode.COMPANION_ONLY,
    ),
    ;

    companion object {
        fun byId(id: String): PluginCapability? = entries.firstOrNull { it.id == id }

        val ids: Set<String> get() = entries.map { it.id }.toSet()
    }
}

/** 能力风险等级：决定安装期同意强度与运行期审计粒度。 */
enum class PluginRisk {
    NORMAL,
    HIGH,
    CRITICAL,
}

/** 能力靠什么途径满足。 */
enum class PluginGrantMode {
    /** 宿主清单已声明的 Android 运行时权限，可由宿主代发授权弹窗。 */
    HOST_RUNTIME_PERMISSION,

    /** 需要跳转系统特殊访问设置页（悬浮窗/无障碍/使用情况/通知使用权）。 */
    HOST_SPECIAL_ACCESS,

    /** 不涉及 Android 权限，纯宿主内部门禁（如沙箱执行、顶掉内置界面）。 */
    HOST_INTERNAL,

    /** 宿主清单无法承载，只能由伴生 APK 自带权限实现。 */
    COMPANION_ONLY,
}
