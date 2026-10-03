package top.wkbin.taixu.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 「仪表盘座舱模式」偏好。
 *
 * 单独成文件的原因：承载偏好的 SettingsDataStore.kt 已触及 architecture-policy.json
 * 的文件尺寸棘轮上限（基线只许下调、不许上涨），因此新增偏好不再堆进该文件，
 * 而是以 Context 扩展形式独立存放，由调用方直接使用（与 ChatRoundCollapsePreferences 同一范式）。
 *
 * 语义：默认 true —— 仪表盘进入即上下分半的座舱视图（上=运行占用，下=终端）；
 * 关闭后完整回退到既有的全量卡片流 + 底部四标签界面，行为与历史版本一致。
 */
internal val homeCockpitModeKey = booleanPreferencesKey("home_cockpit_mode")

/** 座舱模式开关（缺省开启）。 */
val Context.homeCockpitModePreference: Flow<Boolean>
    get() = settingsDataStore.data.map { it[homeCockpitModeKey] ?: true }

/** 写入座舱模式开关。 */
suspend fun Context.setHomeCockpitModePreference(value: Boolean) {
    settingsDataStore.edit { it[homeCockpitModeKey] = value }
}
