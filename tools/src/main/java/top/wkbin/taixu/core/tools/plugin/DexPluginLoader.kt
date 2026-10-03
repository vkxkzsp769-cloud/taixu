package top.wkbin.taixu.core.tools.plugin

import android.content.Context
import dalvik.system.DexClassLoader
import top.wkbin.taixu.core.tools.plugin.PluginRecord
import top.wkbin.taixu.core.tools.plugin.TaiXuPlugin
import java.io.File

/** 插件代码装载失败（缺文件、类不存在、未实现 SPI 等）。 */
class PluginLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 把插件代码包装载成 [TaiXuPlugin] 实例的抽象。
 *
 * 独立成接口的原因：装载逻辑与 Android 的 DexClassLoader 强绑定，无法在纯 JVM 单测里跑；
 * 抽出 seams 后，注册表 / 安全策略 / 崩溃降级这些真正需要验证的规则可以用假装载器覆盖。
 */
interface PluginCodeLoader {
    /** 是否支持该插件的运行形态。 */
    fun supports(record: PluginRecord): Boolean

    /** 装载并返回插件实例；失败抛 [PluginLoadException]，由管理器记录审计并降级。 */
    fun load(record: PluginRecord): TaiXuPlugin
}

/**
 * 进程内插件装载器：用 [DexClassLoader] 加载插件包内的 dex/jar/apk。
 *
 * 安全含义（必须让使用者知情）：被装载的代码运行在**宿主进程、宿主 UID** 下，
 * 因此它天然拥有宿主的全部权限——这也是本体系把「能力同意 + 接管审计 + 崩溃自动降级」
 * 当作强制闸门的原因。父加载器设为宿主 ClassLoader，插件因此能解析到宿主 SPI 类型。
 */
class DexPluginLoader(
    private val context: Context,
    /** 实例化 seams：单测可注入假构造器，避免依赖真实 dex。 */
    private val instantiate: (ClassLoader, String) -> Any = { loader, className ->
        loader.loadClass(className).getDeclaredConstructor().newInstance()
    },
) : PluginCodeLoader {

    override fun supports(record: PluginRecord): Boolean =
        record.manifest.runtime == top.wkbin.taixu.core.model.plugin.PluginManifest.Runtime.NATIVE_DEX

    override fun load(record: PluginRecord): TaiXuPlugin {
        val manifest = record.manifest
        val entryClass = manifest.entryClass?.takeIf { it.isNotBlank() }
            ?: throw PluginLoadException("插件 ${manifest.id} 未声明 entryClass")
        val codeFile = record.codePath?.let(::File)?.takeIf { it.isFile }
            ?: codeDir(manifest.id).listFiles()?.firstOrNull { it.extension in CODE_EXTENSIONS }
            ?: throw PluginLoadException("插件 ${manifest.id} 找不到代码包文件")

        val optimizedDir = File(context.code_cacheDir, "plugin-${manifest.id}").apply { mkdirs() }
        val classLoader = try {
            DexClassLoader(codeFile.absolutePath, optimizedDir.absolutePath, null, javaClass.classLoader)
        } catch (e: Exception) {
            throw PluginLoadException("插件 ${manifest.id} 类加载器创建失败：${e.message}", e)
        }
        val instance = try {
            instantiate(classLoader, entryClass)
        } catch (e: Throwable) {
            throw PluginLoadException("插件 ${manifest.id} 实例化 ${entryClass} 失败：${e.message}", e)
        }
        if (instance !is TaiXuPlugin) {
            throw PluginLoadException("插件 ${manifest.id} 的 $entryClass 未实现 TaiXuPlugin")
        }
        if (instance.id != manifest.id) {
            throw PluginLoadException("插件实例 ID(${instance.id}) 与清单 ID(${manifest.id}) 不一致")
        }
        return instance
    }

    private fun codeDir(pluginId: String): File =
        File(File(context.filesDir, "plugin-code"), pluginId).apply { mkdirs() }

    private companion object {
        val CODE_EXTENSIONS = setOf("dex", "jar", "apk")
    }
}
