package top.wkbin.taixu.core.tools.plugin

import top.wkbin.taixu.core.tools.plugin.PluginCapability
import top.wkbin.taixu.core.tools.plugin.PluginConsentLevel
import top.wkbin.taixu.core.tools.plugin.PluginRisk
import top.wkbin.taixu.core.tools.plugin.PluginSafetyPolicy
import top.wkbin.taixu.core.tools.plugin.PluginSlot
import top.wkbin.taixu.core.tools.plugin.PluginSlots
import top.wkbin.taixu.core.tools.plugin.SlotVerdict
import top.wkbin.taixu.core.model.plugin.PluginManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安装期安全闸门的行为契约。
 * 「插件拥有宿主一切权限」这条能力，全靠这里的校验与同意强度兜住，因此逐条钉死。
 */
class PluginSafetyPolicyTest {

    private fun manifest(
        id: String = "demo-overlay",
        runtime: String = PluginManifest.Runtime.NATIVE_DEX,
        capabilities: List<String> = listOf(PluginCapability.OVERLAY_WINDOW.id, PluginCapability.UI_SLOT_OVERRIDE.id),
        extensionPoints: List<String> = listOf(PluginSlots.FLOATING_WINDOW.id),
        entryClass: String? = "com.example.Plugin",
        codeEntry: String? = "plugin.dex",
        codeSha256: String? = "a".repeat(64),
        schemaVersion: Int = PluginManifest.CURRENT_SCHEMA_VERSION,
        source: String = PluginManifest.SOURCE_LOCAL,
        offlineOnly: Boolean = true,
        priority: Int = 10,
    ) = PluginManifest(
        schemaVersion = schemaVersion,
        id = id,
        name = "演示插件",
        description = "顶掉内置悬浮窗",
        runtime = runtime,
        entryClass = entryClass,
        codeEntry = codeEntry,
        codeSha256 = codeSha256,
        capabilities = capabilities,
        extensionPoints = extensionPoints,
        overridePriority = priority,
        source = source,
        offlineOnly = offlineOnly,
    )

    private fun validate(
        m: PluginManifest,
        hostVersionCode: Int = 100,
        slots: Set<String> = PluginSlots.knownIds,
    ) = PluginSafetyPolicy.validateManifest(m, hostVersionCode, slots)

    @Test
    fun `合法清单零违规`() {
        assertEquals(emptyList<String>(), validate(manifest()))
    }

    @Test
    fun `拒绝非 v2 schema`() {
        assertTrue(validate(manifest(schemaVersion = 1)).any { it.contains("Schema") })
    }

    @Test
    fun `拒绝非法 ID`() {
        assertTrue(validate(manifest(id = "Bad_ID")).any { it.contains("非法插件 ID") })
    }

    @Test
    fun `进程内插件必须同时声明 entryClass 与 codeEntry`() {
        val errors = validate(manifest(entryClass = null, codeEntry = null))
        assertTrue(errors.any { it.contains("entryClass") })
        assertTrue(errors.any { it.contains("codeEntry") })
    }

    @Test
    fun `携带代码却缺 SHA-256 直接拒绝`() {
        assertTrue(validate(manifest(codeSha256 = null)).any { it.contains("SHA-256") })
        assertTrue(validate(manifest(codeSha256 = "not-hex")).any { it.contains("SHA-256") })
    }

    @Test
    fun `拒绝未知能力与未知扩展点`() {
        val errors = validate(
            manifest(
                capabilities = listOf("read_soul"),
                extensionPoints = listOf("slot.not.exist"),
            ),
        )
        assertTrue(errors.any { it.contains("不支持的插件能力") })
        assertTrue(errors.any { it.contains("未知的扩展点") })
    }

    @Test
    fun `接管内置却没有 ui_slot_override 能力被拒`() {
        val errors = validate(manifest(capabilities = listOf(PluginCapability.OVERLAY_WINDOW.id)))
        assertTrue(errors.any { it.contains(PluginCapability.UI_SLOT_OVERRIDE.id) })
    }

    @Test
    fun `伴生 APK 形态必须声明包名`() {
        val errors = validate(
            manifest(
                runtime = PluginManifest.Runtime.COMPANION_APK,
                entryClass = null,
                codeEntry = null,
                codeSha256 = null,
            ),
        )
        assertTrue(errors.any { it.contains("companionPackage") })
    }

    @Test
    fun `本地包必须 offlineOnly 且不得占用内置保留优先级`() {
        assertTrue(validate(manifest(offlineOnly = false)).any { it.contains("offlineOnly") })
        assertTrue(
            validate(manifest(priority = PluginManifest.BUILTIN_PRIORITY)).any { it.contains("优先级") },
        )
    }

    @Test
    fun `宿主版本过低时拒绝装载`() {
        val m = manifest().copy(minHostVersionCode = 999)
        assertTrue(validate(m, hostVersionCode = 100).any { it.contains("versionCode") })
    }

    @Test
    fun `CRITICAL 能力要求逐条明示的完全权限同意`() {
        val level = PluginSafetyPolicy.consentLevel(manifest())
        assertEquals(PluginConsentLevel.EXPLICIT_FULL_PRIVILEGE, level)

        val lowRisk = manifest(
            capabilities = listOf(PluginCapability.NETWORK.id),
            extensionPoints = emptyList(),
        )
        assertEquals(PluginConsentLevel.NONE, PluginSafetyPolicy.consentLevel(lowRisk))

        val onlyHigh = manifest(
            capabilities = listOf(PluginCapability.OVERLAY_WINDOW.id),
            extensionPoints = emptyList(),
        )
        assertEquals(PluginConsentLevel.GENERAL, PluginSafetyPolicy.consentLevel(onlyHigh))
    }

    @Test
    fun `风险清单只含 HIGH 与 CRITICAL 且高危在前`() {
        val risky = PluginSafetyPolicy.riskyCapabilities(
            manifest(
                capabilities = listOf(
                    PluginCapability.NETWORK.id,
                    PluginCapability.LINUX_EXEC.id,
                    PluginCapability.OVERLAY_WINDOW.id,
                ),
                extensionPoints = emptyList(),
            ),
        )
        assertEquals(
            listOf(PluginCapability.LINUX_EXEC, PluginCapability.OVERLAY_WINDOW),
            risky,
        )
        assertTrue(risky.all { it.risk != PluginRisk.NORMAL })
    }

    @Test
    fun `接管资格判定覆盖未申请与缺能力两种拒绝`() {
        val slot: PluginSlot<*> = PluginSlots.FLOATING_WINDOW
        assertEquals(
            SlotVerdict.NotRequested,
            PluginSafetyPolicy.canClaimSlot(manifest(extensionPoints = emptyList()), slot),
        )
        assertTrue(
            PluginSafetyPolicy.canClaimSlot(
                manifest(capabilities = listOf(PluginCapability.OVERLAY_WINDOW.id)),
                slot,
            ) is SlotVerdict.Denied,
        )
        assertEquals(SlotVerdict.Allowed, PluginSafetyPolicy.canClaimSlot(manifest(), slot))
    }

    @Test
    fun `时间窗内三次崩溃触发自动停用`() {
        val t = 1_000_000L
        assertFalse(PluginSafetyPolicy.shouldAutoDisable(listOf(t, t + 1000), t + 1000))
        assertTrue(
            PluginSafetyPolicy.shouldAutoDisable(
                listOf(t, t + 1000, t + 2000),
                nowMs = t + 2000,
            ),
        )
    }

    @Test
    fun `超出时间窗的旧崩溃不计入`() {
        val old = 0L
        val now = PluginSafetyPolicy.CRASH_WINDOW_MS * 3
        val retained = PluginSafetyPolicy.retainedCrashes(listOf(old, now - 10), now)
        assertEquals(listOf(now - 10), retained)
        assertFalse(PluginSafetyPolicy.shouldAutoDisable(listOf(old, old + 1, now), now))
    }

    @Test
    fun `isEffective 四条件缺一不可`() {
        assertTrue(PluginSafetyPolicy.isEffective(true, true, false, true))
        assertFalse("被自动停用即失效", PluginSafetyPolicy.isEffective(true, true, true, true))
        assertFalse("未批准接管即失效", PluginSafetyPolicy.isEffective(true, false, false, true))
        assertFalse("能力未授予即失效", PluginSafetyPolicy.isEffective(true, true, false, false))
        assertFalse("插件停用即失效", PluginSafetyPolicy.isEffective(false, true, false, true))
    }
}
