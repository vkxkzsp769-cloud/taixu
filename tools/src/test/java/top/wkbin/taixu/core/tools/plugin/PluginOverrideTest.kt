package top.wkbin.taixu.core.tools.plugin

import top.wkbin.taixu.core.common.plugin.FloatingWindowExtension
import top.wkbin.taixu.core.common.plugin.PluginCapability
import top.wkbin.taixu.core.common.plugin.PluginHost
import top.wkbin.taixu.core.common.plugin.PluginRecord
import top.wkbin.taixu.core.common.plugin.PluginRegistry
import top.wkbin.taixu.core.common.plugin.PluginRisk
import top.wkbin.taixu.core.common.plugin.PluginSlots
import top.wkbin.taixu.core.model.plugin.PluginManifest
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「顶掉内置」的语义契约：优先级仲裁、用户手选、冲突识别、四条件授权。
 * 这条链路决定了「装一个悬浮窗插件就能取代软件自带悬浮窗」是否真的成立。
 */
class PluginOverrideTest {

    private class FakeFloatWindow(
        override val pluginId: String,
        private var showing: Boolean = false,
    ) : FloatingWindowExtension {
        override val displayName: String get() = "fake:$pluginId"
        override fun show(context: Context, host: PluginHost?): Boolean {
            showing = true
            return true
        }

        override fun hide() { showing = false }
        override val isShowing: Boolean get() = showing
    }

    private val registry = PluginRegistry()
    private val slot = PluginSlots.FLOATING_WINDOW

    @Test
    fun `内置实现登记后成为兜底`() {
        registry.clear()
        registry.registerBuiltinForTest(FakeFloatWindow(BUILTIN_PLUGIN_ID))
        val active = registry.resolve(slot) { true }
        assertEquals(BUILTIN_PLUGIN_ID, active?.pluginId)
    }

    @Test
    fun `插件优先级高于内置即接管`() {
        registry.clear()
        registry.registerBuiltinForTest(FakeFloatWindow(BUILTIN_PLUGIN_ID))
        val plugin = FakeFloatWindow("overlay-pro")
        val reg = registry.register(slot, plugin, priority = 10)
        val active = registry.resolve(slot) { it.pluginId == "overlay-pro" || it.pluginId == BUILTIN_PLUGIN_ID }
        assertEquals("overlay-pro", active?.pluginId)
        registry.unregister(reg)
        assertEquals(BUILTIN_PLUGIN_ID, registry.resolve(slot) { true }?.pluginId)
    }

    @Test
    fun `同优先级先到先得，高优先级抢占`() {
        registry.clear()
        registry.register(slot, FakeFloatWindow("a"), priority = 5)
        registry.register(slot, FakeFloatWindow("b"), priority = 5)
        registry.register(slot, FakeFloatWindow("c"), priority = 9)
        assertEquals("c", registry.resolve(slot) { true }?.pluginId)
        assertEquals(listOf("c", "a", "b"), registry.candidates(slot.id).map { it.pluginId })
    }

    @Test
    fun `未获批的候选不参与生效`() {
        registry.clear()
        registry.register(slot, FakeFloatWindow("approved"), priority = 1)
        registry.register(slot, FakeFloatWindow("rejected"), priority = 99)
        val active = registry.resolve(slot) { it.pluginId == "approved" }
        assertEquals("approved", active?.pluginId)
    }

    @Test
    fun `用户手选可越过优先级，空串表示强制回退内置`() {
        registry.clear()
        registry.registerBuiltinForTest(FakeFloatWindow(BUILTIN_PLUGIN_ID))
        registry.register(slot, FakeFloatWindow("high"), priority = 50)
        val low = registry.register(slot, FakeFloatWindow("low"), priority = 1)
        registry.selectManually(slot.id, "low")
        assertEquals("low", registry.resolve(slot) { true }?.pluginId)
        registry.selectManually(slot.id, "")
        assertNull("强制回退内置时注册表返回 null，由宿主兜底", registry.resolve(slot) { true })
        assertNotNull(registry.extensionFor(low))
        registry.selectManually(slot.id, null)
        assertEquals("high", registry.resolve(slot) { true }?.pluginId)
    }

    @Test
    fun `独占槽位冲突可被枚举`() {
        registry.clear()
        registry.register(slot, FakeFloatWindow("x"), priority = 3)
        registry.register(slot, FakeFloatWindow("y"), priority = 4)
        val conflicts = registry.conflicts { true }
        assertEquals(listOf(slot.id), conflicts.keys.toList())
        assertEquals(listOf("y", "x"), conflicts[slot.id]?.map { it.pluginId })
        registry.selectManually(slot.id, "x")
        assertTrue(registry.conflicts { true }.isEmpty())
    }

    @Test
    fun `卸载插件即清空其全部注册`() {
        registry.clear()
        registry.register(slot, FakeFloatWindow("gone"), priority = 7)
        assertEquals(1, registry.unregisterAll("gone"))
        assertTrue(registry.candidates(slot.id).isEmpty())
    }

    // ---- 四条件仲裁（纯函数） ----

    private fun record(
        enabled: Boolean = true,
        autoDisabled: Boolean = false,
        approvedSlots: Set<String> = setOf(slot.id),
        capabilities: Set<String> = setOf(PluginCapability.UI_SLOT_OVERRIDE.id),
    ) = PluginRecord(
        manifest = PluginManifest(
            id = "overlay-pro",
            name = "接管者",
            runtime = PluginManifest.Runtime.NATIVE_DEX,
            entryClass = "com.example.Plugin",
            codeEntry = "plugin.dex",
            codeSha256 = "b".repeat(64),
            capabilities = capabilities.toList(),
            extensionPoints = listOf(slot.id),
            overridePriority = 10,
        ),
        installedAtMs = 1L,
        enabled = enabled,
        approvedSlots = approvedSlots,
        grantedCapabilities = capabilities,
        autoDisabled = autoDisabled,
    )

    private fun reg(pluginId: String = "overlay-pro") =
        top.wkbin.taixu.core.common.plugin.PluginRegistration(slot.id, pluginId, 10, 1L)

    @Test
    fun `内置实现无条件通过仲裁`() {
        assertTrue(isRegistrationApproved(reg(BUILTIN_PLUGIN_ID), slot, null))
    }

    @Test
    fun `四条件全满足才生效`() {
        assertTrue(isRegistrationApproved(reg(), slot, record()))
    }

    @Test
    fun `缺任一条件即不生效`() {
        assertFalse("无记录", isRegistrationApproved(reg(), slot, null))
        assertFalse("插件被停用", isRegistrationApproved(reg(), slot, record(enabled = false)))
        assertFalse(
            "崩溃自动停用",
            isRegistrationApproved(reg(), slot, record(enabled = false, autoDisabled = true)),
        )
        assertFalse("槽位未获批", isRegistrationApproved(reg(), slot, record(approvedSlots = emptySet())))
        assertFalse("缺接管能力", isRegistrationApproved(reg(), slot, record(capabilities = emptySet())))
    }

    @Test
    fun `跨槽位注册不予承认`() {
        val other = top.wkbin.taixu.core.common.plugin.PluginSlot<FloatingWindowExtension>(
            id = "slot.other", label = "x", description = "y", exclusive = true, hasBuiltin = false,
        )
        assertFalse(isRegistrationApproved(reg(), other, record()))
    }

    @Test
    fun `canEverOverride 要求已启用且优先级合法`() {
        assertTrue(canEverOverride(record()))
        assertFalse(canEverOverride(record(capabilities = emptySet())))
        assertFalse(canEverOverride(record().copy(manifest = record().manifest.copy(overridePriority = PluginManifest.BUILTIN_PRIORITY))))
    }

    /** 测试辅助：以内置保留优先级登记一个实现。 */
    private fun PluginRegistry.registerBuiltinForTest(extension: FloatingWindowExtension) {
        register(slot, extension, PluginManifest.BUILTIN_PRIORITY)
    }

    /** 只为测试保留：确认高危能力词表未被悄悄改动。 */
    @Test
    fun `接管内置所需能力保持高危定级`() {
        assertEquals(PluginRisk.CRITICAL, PluginCapability.UI_SLOT_OVERRIDE.risk)
    }
}
