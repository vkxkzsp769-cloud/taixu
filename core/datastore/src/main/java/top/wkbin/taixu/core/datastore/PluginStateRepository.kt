package top.wkbin.taixu.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * 宿主能力插件的状态存储门面。
 *
 * 为什么单独成文件：承载偏好的 SettingsDataStore.kt 已触及文件尺寸棘轮上限（基线只许下调）。
 * 为什么只存字符串：core:datastore 不依赖 core:common，无法引用 PluginRecord 类型；
 * 于是这里只提供「插件 ID → 不透明 JSON」的键值存储与一条审计日志，
 * 编解码由 tools 模块负责（依赖倒置，零新增模块依赖边）。
 */
class PluginStateRepository(private val context: Context) {

    /** 全部插件状态：pluginId → JSON。 */
    suspend fun readAll(): Map<String, String> {
        val ids = readIds()
        if (ids.isEmpty()) return emptyMap()
        val prefs = context.settingsDataStore.data.first()
        return ids.mapNotNull { id ->
            val json = prefs[stringPreferencesKey(KEY_PREFIX + id)]
            if (json.isNullOrBlank()) null else id to json
        }.toMap()
    }

    suspend fun read(pluginId: String): String? =
        context.settingsDataStore.data.first()[stringPreferencesKey(KEY_PREFIX + pluginId)]

    /** 写入某插件状态，并维护插件 ID 索引。 */
    suspend fun write(pluginId: String, json: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[stringPreferencesKey(KEY_PREFIX + pluginId)] = json
            val ids = prefs[stringPreferencesKey(IDS_KEY)]?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()
            if (pluginId !in ids) {
                prefs[stringPreferencesKey(IDS_KEY)] = (ids + pluginId).joinToString(SEPARATOR)
            }
        }
    }

    suspend fun delete(pluginId: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[stringPreferencesKey(KEY_PREFIX + pluginId)] = ""
            val ids = prefs[stringPreferencesKey(IDS_KEY)]?.split(SEPARATOR)?.filter { it.isNotBlank() && it != pluginId }.orEmpty()
            prefs[stringPreferencesKey(IDS_KEY)] = ids.joinToString(SEPARATOR)
        }
    }

    /** 审计日志：整段 JSON 数组字符串，环形截断到最近 [AUDIT_LIMIT] 条。 */
    suspend fun readAudit(): String =
        context.settingsDataStore.data.first()[stringPreferencesKey(AUDIT_KEY)].orEmpty()

    suspend fun appendAudit(jsonLine: String) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[stringPreferencesKey(AUDIT_KEY)].orEmpty()
            val merged = if (current.isBlank()) jsonLine else "$current\n$jsonLine"
            prefs[stringPreferencesKey(AUDIT_KEY)] = merged.lines().filter { it.isNotBlank() }.takeLast(AUDIT_LIMIT).joinToString("\n")
        }
    }

    private suspend fun readIds(): List<String> =
        context.settingsDataStore.data.first()[stringPreferencesKey(IDS_KEY)]
            ?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()

    private companion object {
        const val KEY_PREFIX = "plugin_state_"
        const val IDS_KEY = "plugin_state_ids"
        const val AUDIT_KEY = "plugin_audit_log"
        const val SEPARATOR = ","
        const val AUDIT_LIMIT = 200
    }
}
