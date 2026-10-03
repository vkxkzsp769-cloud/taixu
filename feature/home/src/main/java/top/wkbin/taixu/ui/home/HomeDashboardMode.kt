package top.wkbin.taixu.ui.home

/**
 * 仪表盘的呈现模式。
 *
 * 座舱模式是套在经典界面之上的一层：上半屏只保留运行占用与利用率，下半屏直接是可输入的终端，
 * 底部四标签收进右上角的总开关；点总开关即回到经典模式（原有软件界面原样保留）。
 */
enum class HomeDashboardMode {
    /** 座舱模式：上=运行占用面板，下=终端面板。 */
    COCKPIT,

    /** 经典模式：全量卡片流 + 底部四标签导航。 */
    CLASSIC,
    ;

    /** 右上角总开关切换到的下一态。 */
    val next: HomeDashboardMode
        get() = if (this == COCKPIT) CLASSIC else COCKPIT

    companion object {
        /** 偏好缺省值：默认进入座舱模式。 */
        const val DEFAULT_COCKPIT = true

        /** 由持久化的布尔偏好还原模式。 */
        fun fromPreference(cockpitEnabled: Boolean): HomeDashboardMode =
            if (cockpitEnabled) COCKPIT else CLASSIC
    }
}

/**
 * 仪表盘布局判定（纯 Kotlin）。
 *
 * Compose 侧与单元测试共用同一判定真相，避免「界面渲染一套、测试断言另一套」。
 */
object HomeDashboardPolicy {

    /** 上半屏最小权重（防止面板被拖到不可用）。 */
    const val MIN_PANEL_WEIGHT = 0.4f

    /** 上半屏最大权重。 */
    const val MAX_PANEL_WEIGHT = 1.6f

    /** 默认上下各占一半。 */
    const val DEFAULT_TOP_PANEL_WEIGHT = 1f

    /** 拖拽灵敏度：每拖 1px 改变的权重。 */
    private const val DRAG_WEIGHT_PER_PX = 1f / 900f

    /** 不可用指标的统一哨兵值（宿主未提供时隐藏该行，绝不伪造数字）。 */
    const val METRIC_UNAVAILABLE = -1

    /** 标准底栏仅在经典模式且非玻璃主题（页面自绘）时出现。 */
    fun shouldShowStandardBottomBar(
        mode: HomeDashboardMode,
        liquidGlassActive: Boolean,
    ): Boolean = mode == HomeDashboardMode.CLASSIC && !liquidGlassActive

    /** 玻璃悬浮底栏由导航层绘制，座舱模式下同样需要收起。 */
    fun shouldShowGlassBottomBar(mode: HomeDashboardMode): Boolean = mode == HomeDashboardMode.CLASSIC

    /** 按拖拽像素量调整上半屏权重，结果夹在安全区间内。 */
    fun nextTopWeight(currentTopWeight: Float, dragPx: Float): Float =
        (currentTopWeight + dragPx * DRAG_WEIGHT_PER_PX)
            .coerceIn(MIN_PANEL_WEIGHT, MAX_PANEL_WEIGHT)

    /** 下半屏权重 = 总权重 2 减去上半屏权重，并保证两侧都可用。 */
    fun bottomWeight(topWeight: Float): Float =
        (2f - topWeight.coerceIn(MIN_PANEL_WEIGHT, MAX_PANEL_WEIGHT))
            .coerceAtLeast(MIN_PANEL_WEIGHT)

    /** CPU 占用行是否展示。 */
    fun shouldShowCpuRow(cpuUsagePercent: Int): Boolean = cpuUsagePercent > METRIC_UNAVAILABLE

    /** 电量行是否展示。 */
    fun shouldShowBatteryRow(batteryPercent: Int): Boolean = batteryPercent > METRIC_UNAVAILABLE

    /** 进度条分数（把 0-100 百分比夹到 0f-1f）。 */
    fun fraction(percent: Int): Float = (percent.coerceIn(0, 100) / 100f)

    /** 占用率是否已进入需要提醒的高位。 */
    fun isHighUsage(percent: Int, threshold: Int = 80): Boolean = percent >= threshold
}
