package top.wkbin.taixu.core.tools.plugin

import android.content.Context
import top.wkbin.taixu.core.tools.plugin.PluginAuditAction
import top.wkbin.taixu.core.tools.plugin.PluginAuditEvent
import top.wkbin.taixu.core.tools.plugin.PluginCapability
import top.wkbin.taixu.core.tools.plugin.PluginConsentLevel
import top.wkbin.taixu.core.tools.plugin.PluginGrantStore
import top.wkbin.taixu.core.tools.plugin.PluginRecord
import top.wkbin.taixu.core.tools.plugin.PluginRisk
import top.wkbin.taixu.core.tools.plugin.PluginSafetyPolicy
import top.wkbin.taixu.core.model.plugin.PluginManifest
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** 安装结果。 */
sealed class PluginInstallResult {
    /** 校验通过并已落库为「待授权」状态，需要用户逐条批准能力后才会装载。 */
    data class PendingConsent(
        val record: PluginRecord,
        val consentLevel: PluginConsentLevel,
        val riskyCapabilities: List<PluginCapability>,
    ) : PluginInstallResult()

    /** 清单不合法或完整性校验失败，未写入任何状态。 */
    data class Rejected(val reasons: List<String>) : PluginInstallResult()
}

/**
 * 宿主能力插件安装包（.txplugin，schema v2）的安装器。
 *
 * 与沙箱工具安装的区别：这里不执行任何脚本，只做「解包 + 完整性校验 + 清单校验 + 落库」。
 * 装载代码必须等用户显式批准能力（见 [approve]），这是「插件拥有宿主一切权限」的前置闸门。
 */
class PluginInstaller(
    private val context: Context,
    private val store: PluginGrantStore,
    private val hostVersionCode: () -> Int,
    private val knownSlotIds: () -> Set<String> = { top.wkbin.taixu.core.tools.plugin.PluginSlots.knownIds },
) {

    suspend fun install(packageFile: File, nowMs: Long = System.currentTimeMillis()): PluginInstallResult {
        if (!packageFile.isFile) return PluginInstallResult.Rejected(listOf("安装包不存在：${packageFile.name}"))

        val zip = runCatching { ZipFile(packageFile) }
            .getOrElse { return PluginInstallResult.Rejected(listOf("无法打开安装包：${it.message}")) }

        try {
            val manifestEntry = zip.getEntry(MANIFEST_PATH)
                ?: return PluginInstallResult.Rejected(listOf("缺少 $MANIFEST_PATH"))
            val manifest = zip.getInputStream(manifestEntry).reader().use { reader ->
                PluginStateCodec.decodeManifest(reader.readText())
            } ?: return PluginInstallResult.Rejected(listOf("manifest.json 解析失败或不是合法的插件清单"))

            val errors = PluginSafetyPolicy.validateManifest(
                manifest = manifest,
                hostVersionCode = hostVersionCode(),
                knownSlotIds = knownSlotIds(),
            )
            if (errors.isNotEmpty()) return PluginInstallResult.Rejected(errors)

            // 只解包清单声明的代码文件，且强制路径校验，避免 zip slip。
            val targetDir = codeDir(manifest.id)
            targetDir.mkdirs()
            var extracted: File? = null
            val codeEntryPath = manifest.codeEntry
            if (!codeEntryPath.isNullOrBlank()) {
                val entry = zip.getEntry(PAYLOAD_PREFIX + codeEntryPath) ?: zip.getEntry(codeEntryPath)
                    ?: return PluginInstallResult.Rejected(listOf("清单声明的代码包不存在：$codeEntryPath"))
                val name = File(codeEntryPath).name
                if (name.isBlank() || name.contains("..")) {
                    return PluginInstallResult.Rejected(listOf("非法的代码包路径：$codeEntryPath"))
                }
                val out = File(targetDir, name)
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                val digest = sha256(out)
                if (!manifest.codeSha256.isNullOrBlank() && !digest.equals(manifest.codeSha256, ignoreCase = true)) {
                    out.delete()
                    return PluginInstallResult.Rejected(listOf("代码包 SHA-256 校验失败：${manifest.id}"))
                }
                extracted = out
            }

            val record = PluginRecord(
                manifest = manifest,
                installedAtMs = nowMs,
                codePath = extracted?.absolutePath,
                enabled = false, // 未获用户批准前不装载
            )
            store.save(record)
            store.appendAudit(
                PluginAuditEvent(nowMs, manifest.id, PluginAuditAction.INSTALL, detail = "v${manifest.version} ${manifest.runtime}"),
            )
            return PluginInstallResult.PendingConsent(
                record = record,
                consentLevel = PluginSafetyPolicy.consentLevel(manifest),
                riskyCapabilities = PluginSafetyPolicy.riskyCapabilities(manifest),
            )
        } finally {
            runCatching { zip.close() }
        }
    }

    /**
     * 用户批准：写入能力授权并把插件置为可装载。
     * CRITICAL 能力必须逐条出现在 [grantedCapabilityIds] 中，缺失即拒绝启用（防「一键全选」绕过）。
     */
    suspend fun approve(
        pluginId: String,
        grantedCapabilityIds: Set<String>,
        approvedSlotIds: Set<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<String> {
        val record = store.find(pluginId) ?: return listOf("插件未安装：$pluginId")
        val declared = record.manifest.capabilities.mapNotNull { PluginCapability.byId(it) }
        val missingCritical = declared.filter { it.risk == PluginRisk.CRITICAL }.map { it.id } - grantedCapabilityIds
        val errors = declared.filter { it.id !in grantedCapabilityIds }.map { "未授予能力：${it.id}" }
        if (missingCritical.isNotEmpty()) {
            return (errors + missingCritical.map { "CRITICAL 能力必须显式勾选：$it" }).distinct()
        }

        declared.forEach { capability ->
            store.setCapabilityGranted(pluginId, capability.id, capability.id in grantedCapabilityIds)
            store.appendAudit(
                PluginAuditEvent(
                    nowMs, pluginId,
                    if (capability.id in grantedCapabilityIds) PluginAuditAction.CAPABILITY_GRANTED else PluginAuditAction.CAPABILITY_REVOKED,
                    detail = capability.label,
                ),
            )
        }
        record.manifest.extensionPoints.forEach { slotId ->
            val approved = slotId in approvedSlotIds
            store.setOverrideApproved(pluginId, slotId, approved)
            store.appendAudit(
                PluginAuditEvent(
                    nowMs, pluginId,
                    if (approved) PluginAuditAction.OVERRIDE_APPROVED else PluginAuditAction.OVERRIDE_REVOKED,
                    slotId = slotId,
                ),
            )
        }
        store.save(record.copy(enabled = true, autoDisabled = false, autoDisabledReason = null, crashTimestamps = emptyList()))
        store.appendAudit(PluginAuditEvent(nowMs, pluginId, PluginAuditAction.ENABLE, detail = "用户批准授权"))
        return emptyList()
    }

    /** 卸载：清状态、删代码包与解压目录。 */
    suspend fun uninstall(pluginId: String, nowMs: Long = System.currentTimeMillis()) {
        store.remove(pluginId)
        runCatching { codeDir(pluginId).deleteRecursively() }
        runCatching { File(context.code_cacheDir, "plugin-$pluginId").deleteRecursively() }
        store.appendAudit(PluginAuditEvent(nowMs, pluginId, PluginAuditAction.UNINSTALL))
    }

    private fun codeDir(pluginId: String): File =
        File(File(context.filesDir, "plugin-code"), pluginId)

    private fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MANIFEST_PATH = "manifest.json"
        const val PAYLOAD_PREFIX = "payload/"
    }
}
