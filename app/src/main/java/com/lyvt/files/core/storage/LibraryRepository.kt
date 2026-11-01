package com.lyvt.files.core.storage

import android.os.Environment
import com.lyvt.files.core.database.CollectionDao
import com.lyvt.files.core.database.CollectionEntity
import com.lyvt.files.core.database.CollectionItemEntity
import com.lyvt.files.core.database.NoteEntity
import com.lyvt.files.core.database.NotesDao
import com.lyvt.files.core.database.TagDao
import com.lyvt.files.core.database.TagEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notes, tags and collections keyed by absolute path. Paths are updated when files are renamed/moved inside the app;
 * entries whose file disappeared are shown as missing and can be re-linked by name+size via [relink].
 */
@Singleton
class LibraryRepository @Inject constructor(private val notes: NotesDao, private val tags: TagDao, private val collections: CollectionDao) {
    fun note(path: String) = notes.note(path)
    val allNotes = notes.all()
    fun tagsFor(path: String) = tags.tagsFor(path)
    val allTags = tags.allTags()
    fun pathsForTag(tag: String) = tags.pathsFor(tag)
    val allCollections = collections.all()
    fun items(id: Long) = collections.items(id)

    suspend fun setNote(path: String, text: String) =
        if (text.isBlank()) notes.delete(path) else notes.upsert(NoteEntity(path, text.trim(), File(path).length(), System.currentTimeMillis()))
    suspend fun addTag(path: String, tag: String) = tags.add(TagEntity(path, tag.trim().lowercase()))
    suspend fun removeTag(path: String, tag: String) = tags.remove(path, tag)
    suspend fun createCollection(name: String, color: Int = 0) = collections.create(CollectionEntity(name = name.trim(), color = color))
    suspend fun deleteCollection(id: Long) { collections.clear(id); collections.delete(id) }
    suspend fun addToCollection(id: Long, paths: List<String>) = paths.forEach { collections.add(CollectionItemEntity(id, it, File(it).length())) }
    suspend fun removeFromCollection(id: Long, path: String) = collections.remove(id, path)

    /** Called by file operations so metadata follows the file. */
    suspend fun onMoved(from: String, to: String) {
        notes.move(from, to); tags.move(from, to); collections.move(from, to)
        // Children of moved folders.
        notes.allNow().filter { it.path.startsWith("$from/") }.forEach { notes.move(it.path, to + it.path.removePrefix(from)) }
        tags.allNow().filter { it.path.startsWith("$from/") }.forEach { tags.move(it.path, to + it.path.removePrefix(from)) }
        collections.allItems().filter { it.path.startsWith("$from/") }.forEach { collections.move(it.path, to + it.path.removePrefix(from)) }
    }

    /** Tries to find a missing file by name and size under [root] and re-links it. */
    suspend fun relink(oldPath: String, size: Long, root: File = Environment.getExternalStorageDirectory()): String? = withContext(Dispatchers.IO) {
        val name = File(oldPath).name
        root.walkTopDown().onEnter { !it.name.startsWith(".") && it.name != "Android" }.firstOrNull { it.isFile && it.name == name && (size <= 0 || it.length() == size) }
            ?.absolutePath?.also { onMoved(oldPath, it) }
    }

    suspend fun exportJson(): String {
        val o = JSONObject()
        o.put("notes", JSONArray().apply { notes.allNow().forEach { put(JSONObject().put("path", it.path).put("note", it.note).put("size", it.size)) } })
        o.put("tags", JSONArray().apply { tags.allNow().forEach { put(JSONObject().put("path", it.path).put("tag", it.tag)) } })
        return o.toString(2)
    }

    suspend fun importJson(json: String): Int {
        val o = JSONObject(json); var n = 0
        o.optJSONArray("notes")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { notes.upsert(NoteEntity(it.getString("path"), it.getString("note"), it.optLong("size"), System.currentTimeMillis())); n++ } }
        o.optJSONArray("tags")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { tags.add(TagEntity(it.getString("path"), it.getString("tag"))); n++ } }
        return n
    }
}
