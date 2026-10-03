package top.wkbin.taixu.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 座舱/经典双模式的布局判定单测。
 *
 * 这些判定决定「底部四标签是否出现」「上下分屏比例」「指标行是否渲染」，
 * 是纯 Kotlin 真相，必须在 JVM 层锁定，不依赖真机。
 */
class HomeDashboardPolicyTest {

    @Test
    fun defaultModeIsCockpit() {
        assertTrue(HomeDashboardMode.DEFAULT_COCKPIT)
        assertEquals(HomeDashboardMode.COCKPIT, HomeDashboardMode.fromPreference(true))
        assertEquals(HomeDashboardMode.CLASSIC, HomeDashboardMode.fromPreference(false))
    }

    @Test
    fun masterToggleAlternatesBetweenCockpitAndClassic() {
        assertEquals(HomeDashboardMode.CLASSIC, HomeDashboardMode.COCKPIT.next)
        assertEquals(HomeDashboardMode.COCKPIT, HomeDashboardMode.CLASSIC.next)
        // 连点两次必须回到原态（总开关是可逆的，不会把用户困在座舱层）
        assertEquals(HomeDashboardMode.COCKPIT, HomeDashboardMode.COCKPIT.next.next)
    }

    @Test
    fun standardBottomBarOnlyInClassicNonGlassTheme() {
        assertFalse(
            HomeDashboardPolicy.shouldShowStandardBottomBar(HomeDashboardMode.COCKPIT, liquidGlassActive = false),
        )
        assertTrue(
            HomeDashboardPolicy.shouldShowStandardBottomBar(HomeDashboardMode.CLASSIC, liquidGlassActive = false),
        )
        // 玻璃主题下底栏由导航层绘制，页面自绘分支必须让位
        assertFalse(
            HomeDashboardPolicy.shouldShowStandardBottomBar(HomeDashboardMode.CLASSIC, liquidGlassActive = true),
        )
    }

    @Test
    fun glassBottomBarIsCollapsedInCockpitMode() {
        assertFalse(HomeDashboardPolicy.shouldShowGlassBottomBar(HomeDashboardMode.COCKPIT))
        assertTrue(HomeDashboardPolicy.shouldShowGlassBottomBar(HomeDashboardMode.CLASSIC))
    }

    @Test
    fun defaultSplitIsExactlyHalf() {
        val top = HomeDashboardPolicy.DEFAULT_TOP_PANEL_WEIGHT
        assertEquals(1f, top, 0f)
        assertEquals(top, HomeDashboardPolicy.bottomWeight(top), 0f)
    }

    @Test
    fun draggingKeepsBothPanelsUsable() {
        var top = HomeDashboardPolicy.DEFAULT_TOP_PANEL_WEIGHT

        repeat(200) { top = HomeDashboardPolicy.nextTopWeight(top, -60f) }
        assertEquals(HomeDashboardPolicy.MIN_PANEL_WEIGHT, top, 0f)
        assertTrue(HomeDashboardPolicy.bottomWeight(top) >= HomeDashboardPolicy.MIN_PANEL_WEIGHT)

        repeat(400) { top = HomeDashboardPolicy.nextTopWeight(top, 60f) }
        assertEquals(HomeDashboardPolicy.MAX_PANEL_WEIGHT, top, 0f)
        assertTrue(HomeDashboardPolicy.bottomWeight(top) >= HomeDashboardPolicy.MIN_PANEL_WEIGHT)
    }

    @Test
    fun bottomWeightClampsAgainstOutOfRangeTopWeight() {
        val bottom = HomeDashboardPolicy.bottomWeight(99f)
        assertTrue(bottom >= HomeDashboardPolicy.MIN_PANEL_WEIGHT)
        assertTrue(HomeDashboardPolicy.bottomWeight(-99f) <= 2f - HomeDashboardPolicy.MIN_PANEL_WEIGHT)
    }

    @Test
    fun unavailableMetricsHideTheirRows() {
        assertFalse(HomeDashboardPolicy.shouldShowCpuRow(HomeDashboardPolicy.METRIC_UNAVAILABLE))
        assertFalse(HomeDashboardPolicy.shouldShowBatteryRow(HomeDashboardPolicy.METRIC_UNAVAILABLE))
        assertTrue(HomeDashboardPolicy.shouldShowCpuRow(0))
        assertTrue(HomeDashboardPolicy.shouldShowBatteryRow(100))
    }

    @Test
    fun fractionAndHighUsageAreClamped() {
        assertEquals(0f, HomeDashboardPolicy.fraction(-5), 0f)
        assertEquals(0.42f, HomeDashboardPolicy.fraction(42), 0.001f)
        assertEquals(1f, HomeDashboardPolicy.fraction(250), 0f)
        assertTrue(HomeDashboardPolicy.isHighUsage(80))
        assertFalse(HomeDashboardPolicy.isHighUsage(79))
        assertTrue(HomeDashboardPolicy.isHighUsage(50, threshold = 50))
    }
}
