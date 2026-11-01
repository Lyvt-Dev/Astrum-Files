package com.lyvt.files.core.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lyvt.files.core.storage.SortField
import com.lyvt.files.core.storage.SortOrder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val accent: Int = 0,
    val fontScale: Float = 1f,
    val gridView: Boolean = false,
    val compactList: Boolean = false,
    val reduceMotion: Boolean = false,
    val showHidden: Boolean = false,
    val showThumbnails: Boolean = true,
    val sort: SortOrder = SortOrder(),
    val trashRetentionDays: Int = 30,
    val trashLimitMb: Int = 2048,
    val useTrash: Boolean = true,
    val vaultAutoLockSeconds: Int = 60,
    val compactDashboard: Boolean = false,
    val dashboardCards: List<String> = DEFAULT_CARDS,
    val scanExclusions: Set<String> = emptySet(),
    val protectedDirs: Set<String> = setOf("DCIM/Camera"),
    val startPage: String = "home",
    val appLock: Boolean = false,
) {
    companion object {
        val DEFAULT_CARDS = listOf("storage", "categories", "quick", "recent", "favorites", "cleanup")
    }
}

private val Context.dataStore by preferencesDataStore("settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic")
        val accent = intPreferencesKey("accent")
        val fontScale = intPreferencesKey("font_scale_pct")
        val grid = booleanPreferencesKey("grid")
        val compactList = booleanPreferencesKey("compact_list")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val hidden = booleanPreferencesKey("hidden")
        val thumbs = booleanPreferencesKey("thumbs")
        val sortField = stringPreferencesKey("sort_field")
        val sortAsc = booleanPreferencesKey("sort_asc")
        val foldersFirst = booleanPreferencesKey("folders_first")
        val retention = intPreferencesKey("trash_retention")
        val trashLimit = intPreferencesKey("trash_limit")
        val useTrash = booleanPreferencesKey("use_trash")
        val autoLock = intPreferencesKey("vault_autolock")
        val compactDash = booleanPreferencesKey("compact_dash")
        val cards = stringPreferencesKey("dash_cards")
        val exclusions = stringPreferencesKey("scan_exclusions")
        val protected = stringPreferencesKey("protected_dirs")
        val startPage = stringPreferencesKey("start_page")
        val appLock = booleanPreferencesKey("app_lock")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings() = AppSettings(
        themeMode = this[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        dynamicColor = this[K.dynamic] ?: false,
        accent = this[K.accent] ?: 0,
        fontScale = (this[K.fontScale] ?: 100) / 100f,
        gridView = this[K.grid] ?: false,
        compactList = this[K.compactList] ?: false,
        reduceMotion = this[K.reduceMotion] ?: false,
        showHidden = this[K.hidden] ?: false,
        showThumbnails = this[K.thumbs] ?: true,
        sort = SortOrder(
            field = this[K.sortField]?.let { runCatching { SortField.valueOf(it) }.getOrNull() } ?: SortField.NAME,
            ascending = this[K.sortAsc] ?: true,
            foldersFirst = this[K.foldersFirst] ?: true,
        ),
        trashRetentionDays = this[K.retention] ?: 30,
        trashLimitMb = this[K.trashLimit] ?: 2048,
        useTrash = this[K.useTrash] ?: true,
        vaultAutoLockSeconds = this[K.autoLock] ?: 60,
        compactDashboard = this[K.compactDash] ?: false,
        dashboardCards = this[K.cards]?.split(',')?.filter { it.isNotBlank() } ?: AppSettings.DEFAULT_CARDS,
        scanExclusions = this[K.exclusions]?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
        protectedDirs = this[K.protected]?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: setOf("DCIM/Camera"),
        startPage = this[K.startPage] ?: "home",
        appLock = this[K.appLock] ?: false,
    )

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val s = transform(p.toSettings())
            p[K.theme] = s.themeMode.name
            p[K.dynamic] = s.dynamicColor
            p[K.accent] = s.accent
            p[K.fontScale] = (s.fontScale * 100).toInt()
            p[K.grid] = s.gridView
            p[K.compactList] = s.compactList
            p[K.reduceMotion] = s.reduceMotion
            p[K.hidden] = s.showHidden
            p[K.thumbs] = s.showThumbnails
            p[K.sortField] = s.sort.field.name
            p[K.sortAsc] = s.sort.ascending
            p[K.foldersFirst] = s.sort.foldersFirst
            p[K.retention] = s.trashRetentionDays
            p[K.trashLimit] = s.trashLimitMb
            p[K.useTrash] = s.useTrash
            p[K.autoLock] = s.vaultAutoLockSeconds
            p[K.compactDash] = s.compactDashboard
            p[K.cards] = s.dashboardCards.joinToString(",")
            p[K.exclusions] = s.scanExclusions.joinToString("\n")
            p[K.protected] = s.protectedDirs.joinToString("\n")
            p[K.startPage] = s.startPage
            p[K.appLock] = s.appLock
        }
    }

    /** Settings backup as JSON (contains no secrets). */
    suspend fun exportJson(): String {
        val p = context.dataStore.data.first()
        val o = JSONObject()
        p.asMap().forEach { (k, v) -> o.put(k.name, v) }
        return o.toString(2)
    }

    suspend fun importJson(json: String) {
        val o = JSONObject(json)
        context.dataStore.edit { p ->
            o.keys().forEach { name ->
                when (val v = o.get(name)) {
                    is Boolean -> p[booleanPreferencesKey(name)] = v
                    is Int -> p[intPreferencesKey(name)] = v
                    is String -> p[stringPreferencesKey(name)] = v
                }
            }
        }
    }
}
