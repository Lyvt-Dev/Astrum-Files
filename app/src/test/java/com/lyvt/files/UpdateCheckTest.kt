package com.lyvt.files

import com.lyvt.files.core.update.UpdateCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test fun versions() {
        assertTrue(UpdateCheck.isNewer("0.2.10", "0.2.9")); assertTrue(UpdateCheck.isNewer("1.3", "1.2.9"))
        assertFalse(UpdateCheck.isNewer("1.2.0", "1.2")); assertFalse(UpdateCheck.isNewer("0.2.7", "0.2.8"))
    }
    private val json = """[
      {"tag_name":"desktop-v1.4.0","draft":false,"prerelease":false,"body":"pc","html_url":"h","assets":[{"name":"AstrumFiles-Setup-1.4.0.exe","browser_download_url":"https://github.com/x/1.exe","size":5}]},
      {"tag_name":"android-v0.3.0","draft":false,"prerelease":true,"assets":[{"name":"a.apk","browser_download_url":"https://github.com/x/pre.apk","size":1}]},
      {"tag_name":"android-v0.2.9","draft":false,"prerelease":false,"body":"notes","html_url":"h","assets":[{"name":"AstrumFiles-0.2.9.apk","browser_download_url":"https://github.com/x/a.apk","size":9,"digest":"sha256:abc"}]},
      {"tag_name":"android-v0.2.8","draft":false,"prerelease":false,"assets":[{"name":"old.apk","browser_download_url":"https://github.com/x/o.apk","size":1}]}
    ]"""
    @Test fun picksNewestStableMatchingRelease() {
        val u = UpdateCheck.pick(json, "android-v", ".apk", "0.2.8")!!
        assertEquals("0.2.9", u.version); assertEquals("abc", u.sha256); assertEquals("https://github.com/x/a.apk", u.url)
        assertNull(UpdateCheck.pick(json, "android-v", ".apk", "0.2.9"))
        assertEquals("1.4.0", UpdateCheck.pick(json, "desktop-v", ".exe", "1.3.0")!!.version)
    }
}
