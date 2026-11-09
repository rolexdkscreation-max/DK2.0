package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** मालिक (09 नवंबर 2026): "अपडेट में डाउनलोड की जगह 504 आता है" - खुद दोबारा, दूसरा रास्ता, अपने-आप बाद में */
class UpdateRetryTest {
    private fun src(rel: String): String {
        val f = listOf(File("src/main/java/com/dk/assistant/$rel"), File("app/src/main/java/com/dk/assistant/$rel")).first { it.exists() }
        return f.readText()
    }

    @Test fun gatewayErrorsRetry() {
        for (c in listOf(0, 408, 429, 500, 502, 503, 504)) assertTrue("$c", UpdateRetry.retryable(c))
        for (c in listOf(401, 403, 404, 410)) assertFalse("$c", UpdateRetry.retryable(c))
    }

    @Test fun backoffGrowsAndCaps() {
        var prev = 0L
        for (n in 1..UpdateRetry.MAX_TRIES) { val b = UpdateRetry.backoffMs(n); assertTrue(b >= prev); prev = b }
        assertEquals(30_000L, UpdateRetry.backoffMs(50))
        val total = (1 until UpdateRetry.MAX_TRIES).sumOf { UpdateRetry.backoffMs(it) }
        assertTrue("कुल इंतज़ार 15 मिनट के wake-lock से बहुत कम", total < 3 * 60_000L)
    }

    @Test fun alternatesUrls() {
        val direct = "https://github.com/r/x/releases/download/v637/DK.apk"
        val api = "https://api.github.com/repos/r/x/releases/assets/726427691"
        val urls = UpdateRetry.order(direct, api)
        assertEquals(listOf(api, direct), urls)                       // API पहले (github.com वाला 504 दे रहा था)
        assertEquals(api, UpdateRetry.pick(urls, 0))
        assertEquals(direct, UpdateRetry.pick(urls, 1))
        assertEquals(api, UpdateRetry.pick(urls, 2))
        assertEquals(listOf(direct), UpdateRetry.order(direct, ""))   // feed वाले release में API पता नहीं
        assertEquals(listOf(direct), UpdateRetry.order(direct, "https://evil.example/x.apk"))
        assertEquals(direct, UpdateRetry.pick(listOf(direct, "", direct), 1))   // API पता न हो = सीधा ही
        assertTrue(UpdateRetry.isApiAsset(api)); assertFalse(UpdateRetry.isApiAsset(direct))
    }

    @Test fun apkMagic() {
        assertTrue(UpdateRetry.looksLikeApk(byteArrayOf(0x50, 0x4B, 3, 4, 20)))
        assertFalse(UpdateRetry.looksLikeApk("<html>".toByteArray()))
        assertFalse(UpdateRetry.looksLikeApk(ByteArray(2)))
    }

    @Test fun friendlyNotBareCode() {
        val m = UpdateRetry.friendly(504)
        assertTrue(m.contains("504") && m.contains("दोबारा"))
        assertEquals("GitHub का सर्वर धीमा (504) - दोबारा कोशिश 2/${UpdateRetry.MAX_TRIES}", UpdateRetry.retryNote(504, 2))
    }

    @Test fun wired() {
        val u = src("Updater.kt")
        assertFalse("पुराना एक-बार वाला संदेश", u.contains("Download fail"))
        assertTrue(u.contains("UpdateRetry.pick(urls, attempt)"))
        assertTrue(u.contains("UpdateRetry.order(rel.apkUrl, rel.assetApi)"))
        assertTrue(u.contains("UpdateJob.scheduleResume(c)"))
        assertTrue(u.contains("headIsApk(part)"))
        assertTrue(u.contains("assetApi = if (UpdateRetry.isApiAsset(api)) api else \"\""))
        assertTrue(u.contains("UpdateRetry.CHECK_TRIES"))
        assertTrue(src("UpdateJob.kt").contains("p?.jobId == RESUME_ID"))
        val s = src("SettingsSystem.kt")
        assertTrue(s.contains("Updater.retryNote?.let"))
        assertTrue(s.contains("Updater.cached(this@secUpdate)?.let"))
    }
}
