package com.lyvt.files.core.organizer

import com.lyvt.files.core.storage.FileCategory
import com.lyvt.files.core.storage.FileOperations
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rule definition independent of Room so the engine stays unit-testable. */
data class OrganizerRule(
    val name: String,
    val sourceDir: String,
    val destination: String,
    val extensions: Set<String> = emptySet(),
    val namePattern: String = "",
    val minSizeBytes: Long = 0,
    val maxSizeBytes: Long = 0,
    val olderThanDays: Int = 0,
    val recursive: Boolean = false,
)

data class PlannedMove(val from: File, val to: File)

data class OrganizerPlan(val rule: OrganizerRule, val moves: List<PlannedMove>, val problems: List<String>) {
    val totalBytes: Long get() = moves.sumOf { it.from.length() }
}

data class OrganizerResult(val journal: List<Pair<String, String>>, val failed: List<Pair<String, String>>)

object OrganizerEngine {
    private val languages = mapOf(
        "c" to "C", "h" to "C", "cpp" to "C++", "cc" to "C++", "hpp" to "C++", "cs" to "C#", "java" to "Java", "kt" to "Kotlin", "kts" to "Kotlin",
        "py" to "Python", "js" to "JavaScript", "mjs" to "JavaScript", "ts" to "TypeScript", "tsx" to "TypeScript", "html" to "HTML", "htm" to "HTML",
        "css" to "CSS", "json" to "JSON", "xml" to "XML", "yml" to "YAML", "yaml" to "YAML", "md" to "Markdown", "sh" to "Shell", "go" to "Go",
        "rs" to "Rust", "swift" to "Swift", "php" to "PHP", "rb" to "Ruby", "sql" to "SQL", "dart" to "Dart",
    )

    fun languageOf(ext: String): String? = languages[ext.lowercase(Locale.ROOT)]

    /** Built-in templates the user can start from. Folders are still chosen/confirmed by the user. */
    fun templates(storageRoot: String) = listOf(
        OrganizerRule("APKs → Download/APKs", "$storageRoot/Download", "APKs", extensions = setOf("apk", "apks", "xapk")),
        OrganizerRule("Screenshots by month", "$storageRoot/Pictures/Screenshots", "{yyyy}-{MM}", extensions = setOf("png", "jpg", "jpeg", "webp")),
        OrganizerRule("Documents by extension", "$storageRoot/Download", "Documents/{ext}", extensions = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "odt")),
        OrganizerRule("Code by language", "$storageRoot/Download", "Code/{lang}", extensions = languages.keys),
        OrganizerRule("Downloads by category", "$storageRoot/Download", "{category}"),
    )

    fun matches(rule: OrganizerRule, f: File, now: Long = System.currentTimeMillis()): Boolean {
        if (!f.isFile || f.name.startsWith(".") || f.name.endsWith(FileOperations.PART_SUFFIX)) return false
        val ext = f.extension.lowercase(Locale.ROOT)
        if (rule.extensions.isNotEmpty() && ext !in rule.extensions) return false
        if (rule.namePattern.isNotBlank()) {
            val re = runCatching { Regex(rule.namePattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: return false
            if (!re.containsMatchIn(f.name)) return false
        }
        val len = f.length()
        if (rule.minSizeBytes > 0 && len < rule.minSizeBytes) return false
        if (rule.maxSizeBytes > 0 && len > rule.maxSizeBytes) return false
        if (rule.olderThanDays > 0 && now - f.lastModified() < rule.olderThanDays * 86_400_000L) return false
        return true
    }

    fun resolveDestination(rule: OrganizerRule, f: File): File {
        val d = Date(f.lastModified())
        val ext = f.extension.lowercase(Locale.ROOT).ifEmpty { "other" }
        val cat = FileCategory.of(ext).name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
        val path = rule.destination
            .replace("{ext}", ext.uppercase(Locale.ROOT))
            .replace("{category}", if (cat == "Apk") "Apps" else cat + "s")
            .replace("{yyyy}", SimpleDateFormat("yyyy", Locale.ROOT).format(d))
            .replace("{MM}", SimpleDateFormat("MM", Locale.ROOT).format(d))
            .replace("{lang}", languageOf(ext) ?: "Other")
            .split('/').filter { it.isNotBlank() && it != "." && it != ".." }.joinToString("/")
        val base = if (rule.destination.startsWith("/")) File("/") else File(rule.sourceDir)
        return File(base, path)
    }

    /** Dry run: computes exactly what would move. Nothing on disk is touched. */
    suspend fun plan(rule: OrganizerRule): OrganizerPlan {
        val src = File(rule.sourceDir)
        if (!src.isDirectory) return OrganizerPlan(rule, emptyList(), listOf("Source folder is not available: ${rule.sourceDir}"))
        val files = if (rule.recursive) src.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isFile }.toList() else src.listFiles()?.toList().orEmpty()
        val reserved = HashSet<String>()
        val moves = mutableListOf<PlannedMove>()
        for (f in files) {
            currentCoroutineContext().ensureActive()
            if (!matches(rule, f)) continue
            val dir = resolveDestination(rule, f)
            if (dir.canonicalPath == f.parentFile?.canonicalPath) continue // already organized
            var target = File(dir, f.name)
            var i = 1
            while (target.exists() || !reserved.add(target.path)) {
                target = File(dir, "${f.nameWithoutExtension} ($i)${if (f.extension.isNotEmpty()) "." + f.extension else ""}"); i++
            }
            moves += PlannedMove(f, target)
        }
        return OrganizerPlan(rule, moves, emptyList())
    }

    /** Executes an approved plan. Never overwrites: a file that appeared at the target meanwhile is skipped. */
    suspend fun execute(plan: OrganizerPlan, onProgress: (Int) -> Unit = {}): OrganizerResult {
        val journal = mutableListOf<Pair<String, String>>()
        val failed = mutableListOf<Pair<String, String>>()
        plan.moves.forEachIndexed { i, m ->
            currentCoroutineContext().ensureActive()
            try {
                if (!m.from.exists()) throw IOException("No longer exists")
                if (m.to.exists()) throw IOException("Target already exists")
                m.to.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Cannot create ${it.name}") }
                if (!m.from.renameTo(m.to)) {
                    m.from.copyTo(m.to)
                    if (!m.from.delete()) { m.to.delete(); throw IOException("Cannot remove original") }
                }
                journal += m.from.absolutePath to m.to.absolutePath
            } catch (e: Exception) {
                failed += m.from.name to (e.message ?: "Error")
            }
            onProgress(i + 1)
        }
        return OrganizerResult(journal, failed)
    }

    /** Moves files back. Only files still at their organized location and whose original path is free are restored. */
    fun undo(journal: List<Pair<String, String>>): Pair<Int, Int> {
        var ok = 0; var fail = 0
        journal.reversed().forEach { (from, to) ->
            val cur = File(to); val orig = File(from)
            if (cur.exists() && !orig.exists() && (orig.parentFile?.exists() == true || orig.parentFile?.mkdirs() == true) && cur.renameTo(orig)) {
                ok++
                // Remove now-empty folders that the organizer created.
                var p = cur.parentFile
                while (p != null && p.list()?.isEmpty() == true && p != orig.parentFile) { p.delete(); p = p.parentFile }
            } else fail++
        }
        return ok to fail
    }

    fun journalToJson(j: List<Pair<String, String>>) = JSONArray().apply { j.forEach { put(JSONArray().put(it.first).put(it.second)) } }.toString()
    fun journalFromJson(s: String): List<Pair<String, String>> = JSONArray(s).let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } } }
}
