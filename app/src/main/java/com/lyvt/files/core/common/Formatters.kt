package com.lyvt.files.core.common

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

object Formatters {
    private val units = arrayOf("B", "KB", "MB", "GB", "TB")

    /** Human-readable size using base-1024 units. */
    fun size(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val group = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
        val value = bytes / 1024.0.pow(group)
        return if (group == 0) "$bytes B" else String.format(Locale.getDefault(), "%.1f %s", value, units[group])
    }

    fun date(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

    fun shortDate(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

    fun percent(part: Long, total: Long): Int = if (total <= 0) 0 else ((part * 100.0) / total).toInt().coerceIn(0, 100)
}
