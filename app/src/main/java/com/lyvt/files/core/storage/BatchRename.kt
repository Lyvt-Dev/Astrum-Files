package com.lyvt.files.core.storage

/**
 * Batch rename planner. Pattern tokens: {name} original base name, {ext} extension, {n} counter (padded by [pad]),
 * {date} yyyyMMdd of modification. Optional regex find/replace applied to the base name first.
 */
data class RenamePlan(val from: FileItem, val to: String, val error: String? = null)

object BatchRename {
    fun plan(
        items: List<FileItem>,
        pattern: String,
        start: Int = 1,
        pad: Int = 0,
        find: String = "",
        replace: String = "",
        useRegex: Boolean = false,
    ): List<RenamePlan> {
        val regex = if (useRegex && find.isNotEmpty()) runCatching { Regex(find) }.getOrNull() else null
        if (useRegex && find.isNotEmpty() && regex == null) return items.map { RenamePlan(it, it.name, "Invalid regular expression") }
        val fmt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT)
        val seen = HashSet<String>()
        return items.mapIndexed { i, item ->
            val base = if (item.isDirectory) item.name else item.name.substringBeforeLast('.', item.name)
            val ext = if (item.isDirectory) "" else item.name.substringAfterLast('.', "")
            val replaced = when {
                find.isEmpty() -> base
                regex != null -> base.replace(regex, replace)
                else -> base.replace(find, replace)
            }
            val counter = (start + i).toString().padStart(pad, '0')
            var newName = pattern.ifBlank { "{name}" }
                .replace("{name}", replaced)
                .replace("{n}", counter)
                .replace("{date}", fmt.format(java.util.Date(item.lastModified)))
                .replace("{ext}", ext)
            if (!pattern.contains("{ext}") && ext.isNotEmpty()) newName = "$newName.$ext"
            val err = FileOperations.validateName(newName) ?: if (!seen.add(newName.lowercase())) "Duplicate name in batch" else null
            RenamePlan(item, newName, err)
        }
    }
}
