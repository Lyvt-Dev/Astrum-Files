package com.lyvt.files.core.pdf

import android.content.Context
import com.lyvt.files.core.storage.FileOperations
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

/** PDF manipulation with PdfBox-Android. Uses temp-file backed memory to keep large documents out of RAM. Outputs never overwrite. */
object PdfTools {
    @Volatile private var ready = false
    fun init(ctx: Context) { if (!ready) { PDFBoxResourceLoader.init(ctx.applicationContext); ready = true } }
    private fun mem() = MemoryUsageSetting.setupMixed(32L * 1024 * 1024)
    private fun load(f: File, password: String?) = try { PDDocument.load(f, password ?: "", mem()) } catch (e: Exception) {
        throw IOException(if (e.javaClass.simpleName.contains("InvalidPassword")) "This PDF is password-protected" else "Cannot read PDF: ${e.message}")
    }

    fun merge(inputs: List<File>, output: File) {
        if (output.exists()) throw IOException("${output.name} already exists")
        val m = PDFMergerUtility(); inputs.forEach { m.addSource(it) }; m.destinationFileName = output.absolutePath
        try { m.mergeDocuments(mem()) } catch (e: Exception) { output.delete(); throw IOException("Merge failed: ${e.message}") }
    }

    /** pages are 1-based, e.g. parsePages("1-3,5", 10). */
    fun parsePages(spec: String, count: Int): List<Int> = spec.split(',').flatMap { part ->
        val p = part.trim(); if (p.isEmpty()) return@flatMap emptyList()
        if ('-' in p) { val (a, b) = p.split('-', limit = 2).map { it.trim().toInt() }; (a..b).toList() } else listOf(p.toInt())
    }.filter { it in 1..count }.distinct()

    fun extract(input: File, pages: List<Int>, output: File, password: String? = null) {
        if (output.exists()) throw IOException("${output.name} already exists")
        load(input, password).use { src ->
            PDDocument().use { out -> pages.forEach { out.importPage(src.getPage(it - 1)) }; out.save(output) }
        }
    }

    suspend fun split(input: File, password: String? = null): List<File> {
        val res = mutableListOf<File>()
        load(input, password).use { src ->
            for (i in 0 until src.numberOfPages) {
                currentCoroutineContext().ensureActive()
                val out = FileOperations.uniqueTarget(input.parentFile!!, "${input.nameWithoutExtension}_p${i + 1}.pdf")
                PDDocument().use { d -> d.importPage(src.getPage(i)); d.save(out) }
                res += out
            }
        }
        return res
    }

    /** Rotates pages (degrees multiple of 90) and writes a copy. */
    fun rotate(input: File, degrees: Int, output: File, password: String? = null) {
        if (output.exists()) throw IOException("${output.name} already exists")
        load(input, password).use { d -> d.pages.forEach { it.rotation = (it.rotation + degrees) % 360 }; d.save(output) }
    }

    fun metadata(input: File, password: String? = null): List<Pair<String, String>> = load(input, password).use { d ->
        val i = d.documentInformation
        listOfNotNull(
            "Pages" to d.numberOfPages.toString(), "PDF version" to d.version.toString(), "Encrypted" to d.isEncrypted.toString(),
            i.title?.let { "Title" to it }, i.author?.let { "Author" to it }, i.subject?.let { "Subject" to it },
            i.keywords?.let { "Keywords" to it }, i.creator?.let { "Creator" to it }, i.producer?.let { "Producer" to it },
            i.creationDate?.time?.let { "Created" to it.toString() }, i.modificationDate?.time?.let { "Modified" to it.toString() },
        )
    }

    /** Returns 0-based page indexes containing [query] (text-based PDFs only; scanned images have no text). */
    suspend fun search(input: File, query: String, password: String? = null): List<Pair<Int, String>> {
        val hits = mutableListOf<Pair<Int, String>>()
        load(input, password).use { d ->
            val stripper = PDFTextStripper()
            for (p in 1..d.numberOfPages) {
                currentCoroutineContext().ensureActive()
                stripper.startPage = p; stripper.endPage = p
                val t = stripper.getText(d)
                val idx = t.indexOf(query, ignoreCase = true)
                if (idx >= 0) hits += (p - 1) to t.substring((idx - 30).coerceAtLeast(0), (idx + query.length + 30).coerceAtMost(t.length)).replace('\n', ' ')
            }
        }
        return hits
    }
}
