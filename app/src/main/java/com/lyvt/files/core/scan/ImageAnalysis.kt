package com.lyvt.files.core.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** On-device perceptual hashing and sharpness heuristics. Results are estimates, not facts. */
object ImageAnalysis {
    private fun decodeSmall(f: File, target: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= target && o.outHeight / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun gray(bmp: Bitmap, w: Int, h: Int): IntArray {
        val s = Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h); s.getPixels(px, 0, w, 0, 0, w, h)
        if (s !== bmp) s.recycle()
        return IntArray(px.size) { val c = px[it]; ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000 }
    }

    /** 64-bit difference hash (dHash). */
    fun dHash(f: File): Long? = runCatching {
        val bmp = decodeSmall(f, 64) ?: return null
        val g = gray(bmp, 9, 8); bmp.recycle()
        var h = 0L
        for (y in 0 until 8) for (x in 0 until 8) { h = h shl 1; if (g[y * 9 + x] > g[y * 9 + x + 1]) h = h or 1 }
        h
    }.getOrNull()

    fun hamming(a: Long, b: Long) = java.lang.Long.bitCount(a xor b)

    /** Variance of the Laplacian on a 256px grayscale downscale. Lower = blurrier. */
    fun sharpness(f: File): Double? = runCatching {
        val bmp = decodeSmall(f, 512) ?: return null
        val w = 256; val h = (256.0 * bmp.height / bmp.width).toInt().coerceIn(32, 512)
        val g = gray(bmp, w, h); bmp.recycle()
        var sum = 0.0; var sq = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val lap = (g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4 * g[i]).toDouble()
            sum += lap; sq += lap * lap; n++
        }
        val mean = sum / n; sq / n - mean * mean
    }.getOrNull()
}

/** Groups items whose hashes are within [threshold] bits (single-link clustering). */
fun <T> clusterByHash(items: List<Pair<T, Long>>, threshold: Int): List<List<T>> {
    val parent = IntArray(items.size) { it }
    fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; parent[x] = r; return r }
    for (i in items.indices) for (j in i + 1 until items.size) {
        if (ImageAnalysis.hamming(items[i].second, items[j].second) <= threshold) parent[find(i)] = find(j)
    }
    return items.indices.groupBy { find(it) }.values.filter { it.size > 1 }.map { g -> g.map { items[it].first } }
}
