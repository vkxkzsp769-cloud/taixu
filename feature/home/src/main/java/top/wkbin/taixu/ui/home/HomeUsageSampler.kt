package top.wkbin.taixu.ui.home

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File
import kotlin.math.roundToInt

/** 一次 `/proc/stat` cpu 汇总行采样，单位为 jiffies。 */
internal data class CpuJiffies(val idle: Long, val total: Long)

/**
 * 宿主运行占用采样器：整机 CPU 利用率与电量。
 *
 * 纯解析函数（[parseCpuJiffies] / [deltaPercent]）与系统调用分离，便于 JVM 单元测试覆盖。
 * 任何不可用的指标统一返回 [HomeDashboardPolicy.METRIC_UNAVAILABLE] / null，由 UI 隐藏对应行，
 * 绝不伪造数字。
 */
internal object HomeUsageSampler {

    private const val PROC_STAT_PATH = "/proc/stat"
    private val WHITESPACE = Regex("\\s+")

    /** 差分基线：CPU 利用率是「两次采样之间」的占用率，无需额外定时器。 */
    private var previousCpu: CpuJiffies? = null

    /**
     * 解析 cpu 汇总行：`cpu  user nice system idle iowait irq softirq steal guest guest_nice`。
     * idle 取 idle + iowait，total 取全部字段之和；字段不足时返回 null。
     */
    fun parseCpuJiffies(cpuLine: String): CpuJiffies? {
        val fields = cpuLine.trim().split(WHITESPACE).drop(1).mapNotNull { it.toLongOrNull() }
        if (fields.size < 4) return null
        return CpuJiffies(
            idle = fields[3] + fields.getOrElse(4) { 0L },
            total = fields.sum(),
        )
    }

    /** 两次采样之间的 CPU 占用百分比；区间无效（首轮、计数器回绕、时间倒流）时返回不可用哨兵。 */
    fun deltaPercent(previous: CpuJiffies, current: CpuJiffies): Int {
        val totalDelta = current.total - previous.total
        val idleDelta = current.idle - previous.idle
        if (totalDelta <= 0L || idleDelta < 0L) return HomeDashboardPolicy.METRIC_UNAVAILABLE
        return ((1f - idleDelta.toFloat() / totalDelta.toFloat()) * 100f)
            .roundToInt()
            .coerceIn(0, 100)
    }

    /**
     * 采样整机 CPU 占用率（相对上一次调用）。
     * 首次调用只建立基线，返回不可用哨兵，避免开机瞬间给出无意义的 100%。
     */
    fun sampleCpuUsagePercent(): Int {
        val line = runCatching {
            File(PROC_STAT_PATH).useLines { lines -> lines.firstOrNull { it.startsWith("cpu") } }
        }.getOrNull() ?: return HomeDashboardPolicy.METRIC_UNAVAILABLE
        val current = parseCpuJiffies(line) ?: return HomeDashboardPolicy.METRIC_UNAVAILABLE
        val previous = previousCpu
        previousCpu = current
        if (previous == null) return HomeDashboardPolicy.METRIC_UNAVAILABLE
        return deltaPercent(previous, current)
    }

    /** 丢弃差分基线（停止监控或指标长时间未刷新时调用，避免陈旧区间产生错误读数）。 */
    fun resetCpuBaseline() {
        previousCpu = null
    }

    /** 电量读数（百分比 + 是否正在充电）。 */
    data class BatteryReading(val percent: Int, val charging: Boolean)

    /**
     * 读取电量。使用 sticky 广播查询（`registerReceiver(null, filter)`），
     * 无需注册常驻接收器，也不需要任何运行时权限。
     */
    fun sampleBattery(context: Context): BatteryReading? = runCatching {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        BatteryReading(
            percent = (level * 100f / scale).roundToInt().coerceIn(0, 100),
            charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
        )
    }.getOrNull()
}
