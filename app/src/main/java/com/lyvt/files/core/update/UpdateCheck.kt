package com.lyvt.files.core.update

import com.lyvt.files.core.storage.Hashing
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(val version: String, val notes: String, val url: String, val size: Long, val sha256: String?, val page: String)

/**
 * Checks GitHub Releases of [REPO] for a newer build (shared by phone and PC).
 *  - Phone releases are tagged `android-v<version>` and carry an .apk; PC releases `desktop-v<version>` with a Setup .exe.
 *  - Only HTTPS to api.github.com / github.com. No data about the device is sent.
 *  - Downloads are checked against the SHA-256 digest GitHub publishes for the asset (when available);
 *    on Android the system additionally refuses an APK not signed with the same key as the installed app.
 */
object UpdateCheck {
    const val REPO = "Lyvt-Dev/Astrum-Files"

    /** true when version [a] is newer than [b] (numeric, dot separated; "1.10" > "1.9"). */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    private fun get(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000; readTimeout = 30_000
        setRequestProperty("Accept", "application/vnd.github+json"); setRequestProperty("User-Agent", "AstrumFiles-Updater")
    }

    /** Parses a GitHub releases JSON array; returns the newest matching release newer than [current], or null. */
    fun pick(json: String, tagPrefix: String, assetExt: String, current: String): UpdateInfo? {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") && it.optString("tag_name").startsWith(tagPrefix) }
            .mapNotNull { r ->
                val v = r.getString("tag_name").removePrefix(tagPrefix)
                val assets = r.optJSONArray("assets") ?: return@mapNotNull null
                val a = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(assetExt, true) } ?: return@mapNotNull null
                UpdateInfo(v, r.optString("body"), a.getString("browser_download_url"), a.optLong("size"),
                    a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"), r.optString("html_url"))
            }
            .filter { isNewer(it.version, current) }
            .maxWithOrNull { p, q -> if (isNewer(p.version, q.version)) 1 else if (isNewer(q.version, p.version)) -1 else 0 }
    }

    fun latest(tagPrefix: String, assetExt: String, current: String): UpdateInfo? {
        val c = get("https://api.github.com/repos/$REPO/releases?per_page=30")
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode != 200) throw IOException("GitHub answered ${c.responseCode}")
            return pick(c.inputStream.bufferedReader().readText(), tagPrefix, assetExt, current)
        } finally { c.disconnect() }
    }

    /** Downloads [info] to [dest] (via a .part file) and verifies the SHA-256 digest when GitHub provides one. */
    fun download(info: UpdateInfo, dest: File, onProgress: (Long, Long) -> Unit): File {
        require(info.url.startsWith("https://github.com/") || info.url.startsWith("https://objects.githubusercontent.com/")) { "Unexpected download host" }
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        val c = get(info.url).apply { instanceFollowRedirects = true; readTimeout = 60_000 }
        try {
            if (c.responseCode != 200) throw IOException("Download failed (${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: info.size
            val md = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { i -> part.outputStream().use { o ->
                val buf = ByteArray(256 * 1024); var done = 0L; var last = 0L
                while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); md.update(buf, 0, n); done += n
                    if (done - last > 512 * 1024) { last = done; onProgress(done, total) } }
                onProgress(done, total)
            } }
            val got = Hashing.hex(md.digest())
            if (info.sha256 != null && !got.equals(info.sha256, true)) { part.delete(); throw IOException("Download is corrupted (checksum mismatch) — not installed") }
            dest.delete(); if (!part.renameTo(dest)) throw IOException("Could not save update")
            return dest
        } finally { c.disconnect() }
    }
}
