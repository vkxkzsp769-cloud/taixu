package top.wkbin.taixu.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CPU jiffies 解析与差分占用率单测（纯函数，不触碰 /proc，跨设备可复现）。
 */
class HomeUsageSamplerTest {

    @Test
    fun parsesAggregateCpuLine() {
        val line = "cpu  24478 0 15221 1656537 1445 0 531 0 0 0"

        val jiffies = HomeUsageSampler.parseCpuJiffies(line)

        requireNotNull(jiffies)
        // idle = idle + iowait，total = 全部字段之和
        assertEquals(1656537L + 1445L, jiffies.idle)
        assertEquals(24478L + 15221L + 1656537L + 1445L + 531L, jiffies.total)
    }

    @Test
    fun rejectsLinesWithoutEnoughFields() {
        assertNull(HomeUsageSampler.parseCpuJiffies("cpu  1 2 3"))
        assertNull(HomeUsageSampler.parseCpuJiffies("cpu  a b c d e"))
        assertNull(HomeUsageSampler.parseCpuJiffies("intr 12345"))
    }

    @Test
    fun computesBusyPercentBetweenSamples() {
        val previous = CpuJiffies(idle = 9L, total = 55L)
        val current = CpuJiffies(idle = 20L, total = 100L)

        // (1 - 11/45) * 100 ≈ 75.56 -> 76
        assertEquals(76, HomeUsageSampler.deltaPercent(previous, current))
    }

    @Test
    fun fullyIdleIntervalReportsZero() {
        val previous = CpuJiffies(idle = 100L, total = 200L)
        val current = CpuJiffies(idle = 300L, total = 400L)

        assertEquals(0, HomeUsageSampler.deltaPercent(previous, current))
    }

    @Test
    fun fullyBusyIntervalReportsHundred() {
        val previous = CpuJiffies(idle = 10L, total = 100L)
        val current = CpuJiffies(idle = 10L, total = 200L)

        assertEquals(100, HomeUsageSampler.deltaPercent(previous, current))
    }

    @Test
    fun invalidIntervalsYieldUnavailableSentinel() {
        val base = CpuJiffies(idle = 10L, total = 100L)

        // 计数器未推进（首次采样、读取失败）
        assertEquals(
            HomeDashboardPolicy.METRIC_UNAVAILABLE,
            HomeUsageSampler.deltaPercent(base, base),
        )
        // 时间倒流 / 计数器回绕
        assertEquals(
            HomeDashboardPolicy.METRIC_UNAVAILABLE,
            HomeUsageSampler.deltaPercent(base, CpuJiffies(idle = 5L, total = 90L)),
        )
        // idle 反而减少：内核数据异常，宁可不显示也不伪造
        assertEquals(
            HomeDashboardPolicy.METRIC_UNAVAILABLE,
            HomeUsageSampler.deltaPercent(base, CpuJiffies(idle = 5L, total = 200L)),
        )
    }

    @Test
    fun busyPercentNeverEscapesZeroToOneHundred() {
        val percent = HomeUsageSampler.deltaPercent(CpuJiffies(idle = 0L, total = 1L), CpuJiffies(idle = 0L, total = 10_000L))

        assertTrue(percent in 0..100)
        assertEquals(100, percent)
    }
}
