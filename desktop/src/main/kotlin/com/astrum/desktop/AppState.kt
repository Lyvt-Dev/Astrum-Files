package com.astrum.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lyvt.files.core.storage.SortField
import com.lyvt.files.core.storage.SortOrder
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Desktop
import java.io.File

enum class Screen(val title: String) { HOME("Home"), FILES("Files"), CLEANER("Cleaner"), ANALYTICS("Analytics"), VAULT("Vault"),
    TRANSFER("Transfer"), ORGANIZER("Organizer"), SETTINGS("Settings") }

/** Simple JSON-backed persistence in %USERPROFILE%\.astrum (or ~/.astrum). */
object Store {
    val dir = File(System.getProperty("user.home"), ".astrum").apply { mkdirs() }
    fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()
    fun write(name: String, text: String) { val t = File(dir, "$name.tmp"); t.writeText(text); t.renameTo(File(dir, name)).let { if (!it) { File(dir, name).delete(); t.renameTo(File(dir, name)) } } }
}

data class OpLog(val type: String, val summary: String, val ok: Boolean, val at: Long = System.currentTimeMillis())

object AppState {
    var screen by mutableStateOf(Screen.HOME)
    var openFile by mutableStateOf<File?>(null)
    var pendingExplorerPath by mutableStateOf<String?>(null)
    var pendingSearch by mutableStateOf<String?>(null)

    // Settings
    var themeMode by mutableStateOf("system")
    var accent by mutableStateOf(0)
    var showHidden by mutableStateOf(false)
    var gridView by mutableStateOf(false)
    var sort by mutableStateOf(SortOrder())
    var thumbnails by mutableStateOf(true)
    var useRecycleBin by mutableStateOf(true)
    var reduceMotion by mutableStateOf(false)
    var scanExclusions by mutableStateOf(setOf<String>())

    val favorites = mutableStateListOf<String>()
    val recents = mutableStateListOf<String>()
    val log = mutableStateListOf<OpLog>()
    var clipboard by mutableStateOf<Pair<List<String>, Boolean>?>(null)   // paths, move

    fun load() {
        runCatching {
            val o = JSONObject(Store.read("settings.json") ?: return@runCatching)
            themeMode = o.optString("theme", "system"); accent = o.optInt("accent"); showHidden = o.optBoolean("hidden")
            gridView = o.optBoolean("grid"); thumbnails = o.optBoolean("thumbs", true); useRecycleBin = o.optBoolean("recycle", true)
            reduceMotion = o.optBoolean("reduceMotion")
            sort = SortOrder(runCatching { SortField.valueOf(o.optString("sortField", "NAME")) }.getOrDefault(SortField.NAME), o.optBoolean("sortAsc", true), o.optBoolean("foldersFirst", true))
            scanExclusions = o.optJSONArray("exclusions")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        }
        runCatching { JSONArray(Store.read("favorites.json") ?: "[]").let { a -> favorites.addAll((0 until a.length()).map { a.getString(it) }) } }
        runCatching { JSONArray(Store.read("recents.json") ?: "[]").let { a -> recents.addAll((0 until a.length()).map { a.getString(it) }) } }
    }

    fun save() {
        Store.write("settings.json", JSONObject().put("theme", themeMode).put("accent", accent).put("hidden", showHidden).put("grid", gridView)
            .put("thumbs", thumbnails).put("recycle", useRecycleBin).put("reduceMotion", reduceMotion).put("sortField", sort.field.name)
            .put("sortAsc", sort.ascending).put("foldersFirst", sort.foldersFirst).put("exclusions", JSONArray(scanExclusions.toList())).toString(2))
        Store.write("favorites.json", JSONArray(favorites.toList()).toString())
        Store.write("recents.json", JSONArray(recents.toList()).toString())
    }

    fun toggleFavorite(path: String) { if (path in favorites) favorites.remove(path) else favorites.add(path); save() }
    fun recordOpen(path: String) { recents.remove(path); recents.add(0, path); while (recents.size > 30) recents.removeAt(recents.lastIndex); save() }
    fun log(type: String, summary: String, ok: Boolean) { log.add(0, OpLog(type, summary, ok)); while (log.size > 300) log.removeAt(log.lastIndex) }

    fun openInExplorer(path: String) { pendingExplorerPath = path; screen = Screen.FILES }

    /** Opens a file in the built-in viewer when supported, otherwise with the default Windows app. */
    fun open(f: File) {
        recordOpen(f.absolutePath)
        if (Viewers.supports(f)) openFile = f else systemOpen(f)
    }
    fun systemOpen(f: File): String? = runCatching { Desktop.getDesktop().open(f); null }.getOrElse { "No app is associated with this file type" }
    fun reveal(f: File) {
        runCatching {
            if (System.getProperty("os.name").startsWith("Windows")) ProcessBuilder("explorer.exe", "/select,", f.absolutePath).start()
            else Desktop.getDesktop().open(f.parentFile)
        }
    }

    val home: File get() = File(System.getProperty("user.home"))
    fun knownFolder(name: String) = File(home, name)
}
