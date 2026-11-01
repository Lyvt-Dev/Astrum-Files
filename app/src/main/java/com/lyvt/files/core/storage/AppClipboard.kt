package com.lyvt.files.core.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class ClipEntry(val paths: List<String>, val move: Boolean, val at: Long = System.currentTimeMillis())

/** In-app clipboard with a short history (copy/cut sets). */
@Singleton
class AppClipboard @Inject constructor() {
    private val _current = MutableStateFlow<ClipEntry?>(null)
    val current: StateFlow<ClipEntry?> = _current.asStateFlow()
    private val _history = MutableStateFlow<List<ClipEntry>>(emptyList())
    val history: StateFlow<List<ClipEntry>> = _history.asStateFlow()

    fun set(paths: List<String>, move: Boolean) {
        val e = ClipEntry(paths, move)
        _current.value = e
        _history.value = (listOf(e) + _history.value).distinctBy { it.paths to it.move }.take(10)
    }
    fun restore(e: ClipEntry) { _current.value = e }
    fun clear() { _current.value = null }
}
