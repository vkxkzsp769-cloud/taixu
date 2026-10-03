package top.wkbin.taixu.core.model.plugin

import kotlinx.serialization.Serializable

/**
 * 宿主能力插件清单（schema v2）。
 *
 * 与 [top.wkbin.taixu.core.model.ToolManifest]（schema v1，沙箱工具）的本质区别：
 * v1 是「纯数据、不带代码、由宿主按 id 白名单适配」的安全模型；
 * v2 允许插件携带**运行时代码**、声明**宿主能力**、并**顶掉内置实现**，
 * 因此安装期必须走显式同意 + 完整性校验 + 可回退的接管审计。
 *
 * 本文件保持 JVM-only：只描述数据，不出现任何 android.* 类型。
 */
@Serializable
data class PluginManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,

    /** 插件唯一 ID，规则沿用工具生态：[a-z0-9][a-z0-9-]{1,63}。 */
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "0.1.0",
    val publisher: String = "",

    /** 运行形态，取值见 [Runtime]。 */
    val runtime: String = Runtime.NATIVE_DEX,
    /**
     * 进程内插件入口类的全限定名（必须实现宿主 SPI 的 TaiXuPlugin）。
     * 仅 NATIVE_DEX 形态需要；COMPANION_APK 用 [companionPackage]。
     */
    val entryClass: String? = null,
    /** 伴生 APK 的包名：插件自带 Android 权限时用它向系统申请宿主没有的权限。 */
    val companionPackage: String? = null,

    /** 申请的宿主能力 ID 列表（见 core:common 的 PluginCapability 词表）。 */
    val capabilities: List<String> = emptyList(),
    /** 想要接管的扩展点 ID 列表（见 core:common 的 PluginSlot 词表）。 */
    val extensionPoints: List<String> = emptyList(),
    /**
     * 接管优先级：同一扩展点允许多个竞争者时，优先级高者生效。
     * 只有用户明确批准接管的插件才会参与排序，内置实现永远保底。
     */
    val overridePriority: Int = DEFAULT_OVERRIDE_PRIORITY,

    /** 载荷内插件代码包（dex/jar/apk）的相对路径，相对 payload/。 */
    val codeEntry: String? = null,
    /** 插件代码包的 SHA-256，第三方来源强制要求。 */
    val codeSha256: String? = null,

    /** 兼容的最低宿主 versionCode，低于此值不装载。 */
    val minHostVersionCode: Int = 0,

    /** 来源：REMOTE（签名注册表）/ LOCAL（本地 .txplugin 导入）。 */
    val source: String = SOURCE_REMOTE,
    /** 是否允许在离线状态下装载（本地包必须为 true，沿用 v1 语义）。 */
    val offlineOnly: Boolean = false,
    /** 插件是否需要宿主在后台常驻（装载后由宿主托管其前台服务生命周期）。 */
    val persistent: Boolean = false,
) {

    /** 运行形态。 */
    object Runtime {
        /** 携带 dex/jar，由宿主进程内 DexClassLoader 装载：拥有宿主全部权限。 */
        const val NATIVE_DEX = "NATIVE_DEX"

        /** 纯沙箱工具（等价 v1），不进入宿主进程。 */
        const val SANDBOX_TOOL = "SANDBOX_TOOL"

        /** 伴生 APK：独立进程与独立清单，可持有宿主没有声明的 Android 权限。 */
        const val COMPANION_APK = "COMPANION_APK"

        val all = setOf(NATIVE_DEX, SANDBOX_TOOL, COMPANION_APK)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        const val SOURCE_REMOTE = "REMOTE"
        const val SOURCE_LOCAL = "LOCAL"
        const val DEFAULT_OVERRIDE_PRIORITY = 0

        /** 内置实现所在的保留优先级：任何插件都要高于它才能顶掉。 */
        const val BUILTIN_PRIORITY = Int.MIN_VALUE
    }
}
