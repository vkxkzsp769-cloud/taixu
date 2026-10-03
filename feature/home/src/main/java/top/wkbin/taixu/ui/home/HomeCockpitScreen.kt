package top.wkbin.taixu.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.wkbin.taixu.core.model.RuntimeState
import top.wkbin.taixu.feature.home.R
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeLinearProgressIndicator
import top.wkbin.taixu.ui.components.StatusBadge

/**
 * 座舱模式仪表盘：上下分半。
 *
 * 上半屏 = 运行占用与利用率面板（[HomeResourceUsagePanel]），下半屏 = 可直接输入命令的终端面板
 * （由装配层 feature:navigation 通过 [terminalPane] 注入，feature:home 不横向依赖 feature:terminal）。
 * 中间的拖拽条用于微调上下比例，默认各占一半；收起座舱模式的入口是顶栏右上角总开关。
 */
@Composable
internal fun HomeCockpitContent(
    state: RuntimeState,
    metrics: SystemResourceMetrics,
    modeStatus: ExecutionModeStatus,
    terminalPane: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var topWeight by rememberSaveable { mutableFloatStateOf(HomeDashboardPolicy.DEFAULT_TOP_PANEL_WEIGHT) }
    Column(modifier = modifier) {
        HomeResourceUsagePanel(
            state = state,
            metrics = metrics,
            modeStatus = modeStatus,
            modifier = Modifier
                .weight(topWeight)
                .fillMaxWidth(),
        )
        HomeCockpitSplitHandle(
            onDrag = { dragPx ->
                topWeight = HomeDashboardPolicy.nextTopWeight(topWeight, dragPx)
            },
        )
        Box(
            modifier = Modifier
                .weight(HomeDashboardPolicy.bottomWeight(topWeight))
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp)),
        ) {
            terminalPane()
        }
    }
}

/** 上下分屏的拖拽调节条。 */
@Composable
private fun HomeCockpitSplitHandle(onDrag: (Float) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount -> onDrag(dragAmount) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        )
    }
}

/**
 * 运行占用面板：内存 / 沙箱存储 / CPU 利用率 / 电量 / 进程与服务 / 运行时长。
 *
 * CPU 与电量由 [HomeUsageSampler] 采样；宿主未提供该指标时整行隐藏，不展示占位数字。
 */
@Composable
internal fun HomeResourceUsagePanel(
    state: RuntimeState,
    metrics: SystemResourceMetrics,
    modeStatus: ExecutionModeStatus,
    modifier: Modifier = Modifier,
) {
    val ready = state is RuntimeState.Ready
    val initializing = state is RuntimeState.Initializing
    val statusColor = when {
        ready -> MaterialTheme.colorScheme.primary
        initializing -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    val statusLabel = stringResource(
        when {
            ready -> R.string.home_runtime_ready
            initializing -> R.string.home_runtime_initializing
            else -> R.string.home_runtime_uninitialized
        },
    )

    RuntimeCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingStatusDot(color = statusColor, isPulsing = !ready)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                StatusBadge(
                    text = modeStatus.mode.name,
                    color = if (modeStatus.active) statusColor else MaterialTheme.colorScheme.error,
                    pulsing = modeStatus.checking,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${metrics.linuxDistro} · ${metrics.cpuArch}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }

            UsageBarRow(
                label = stringResource(R.string.home_memory),
                valueText = "${metrics.memoryUsedMb} / ${metrics.memoryTotalMb} MB",
                percent = metrics.memoryUsagePercent,
                accentColor = MaterialTheme.colorScheme.primary,
                icon = RuntimeIconName.Server,
            )

            if (HomeDashboardPolicy.shouldShowCpuRow(metrics.cpuUsagePercent)) {
                UsageBarRow(
                    label = stringResource(R.string.home_cpu_usage),
                    valueText = "${metrics.cpuUsagePercent}%",
                    percent = metrics.cpuUsagePercent,
                    accentColor = MaterialTheme.colorScheme.tertiary,
                    icon = RuntimeIconName.Cpu,
                )
            }

            if (HomeDashboardPolicy.shouldShowBatteryRow(metrics.batteryPercent)) {
                UsageBarRow(
                    label = if (metrics.batteryCharging) {
                        stringResource(R.string.home_battery_charging)
                    } else {
                        stringResource(R.string.home_battery)
                    },
                    valueText = "${metrics.batteryPercent}%",
                    percent = metrics.batteryPercent,
                    accentColor = MaterialTheme.colorScheme.secondary,
                    icon = RuntimeIconName.Battery,
                )
            }

            UsageBarRow(
                label = stringResource(R.string.home_storage),
                valueText = "${metrics.storageUsedGb} / ${metrics.storageTotalGb} GB",
                percent = metrics.storageUsagePercent,
                accentColor = MaterialTheme.colorScheme.secondary,
                icon = RuntimeIconName.Storage,
            )

            Spacer(Modifier.height(2.dp))

            SpecRow(
                label = stringResource(R.string.home_background_processes),
                value = metrics.activeProcessCount.toString(),
            )
            SpecRow(
                label = stringResource(R.string.home_service_count),
                value = metrics.runningServicesCount.toString(),
            )
            SpecRow(
                label = stringResource(R.string.home_uptime_label),
                value = metrics.uptimeFormatted,
            )
        }
    }
}

/** 单条占用率：标题 + 数值 + 进度条（≥80% 转为告警色）。 */
@Composable
private fun UsageBarRow(
    label: String,
    valueText: String,
    percent: Int,
    accentColor: Color,
    icon: RuntimeIconName,
) {
    val barColor = if (HomeDashboardPolicy.isHighUsage(percent)) {
        MaterialTheme.colorScheme.error
    } else {
        accentColor
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RuntimeIcon(
                    name = icon,
                    modifier = Modifier.size(14.dp),
                    tint = barColor,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(4.dp))
        RuntimeLinearProgressIndicator(
            progress = { HomeDashboardPolicy.fraction(percent) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = barColor,
        )
    }
}

/**
 * 规格键值行（自 HomeScreen.kt 迁入：该文件受 architecture-policy.json 行号棘轮约束，
 * 只许下调不许上涨；迁入后经典布局与座舱面板共用同一实现）。
 */
@Composable
internal fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.58f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 状态呼吸灯圆点（自 HomeScreen.kt 迁入，原因同上）。
 */
@Composable
internal fun PulsingStatusDot(color: Color, isPulsing: Boolean) {
    val transition = rememberInfiniteTransition(label = "status_dot_pulse")
    val alpha by if (isPulsing) {
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse_alpha",
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }

    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}
