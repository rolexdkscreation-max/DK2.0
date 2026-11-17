package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 16 नवंबर 2026 (मालिक): बीटा / स्टेबल अपडेट, मर्ज़ी वाली crash रिपोर्ट, सच्चा निजता पेज */
class UpdateChannelCrashPrivacyTest {
    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()
    private fun root(p: String) = listOf(File("../$p"), File(p)).first { it.exists() }.readText()

    @Test fun channelAndSha() {
        val head = listOf("TYPE: software", "VERSION: 3.9", "CHANNEL: beta", "SHA256: " + "A".repeat(64))
        assertTrue(UpdateChannel.isBetaBody(head))
        assertFalse(UpdateChannel.isBetaBody(listOf("TYPE: software", "CHANNEL: stable")))
        assertFalse(UpdateChannel.isBetaBody(listOf("TYPE: software")))           // पुरानी release = स्टेबल
        assertEquals("a".repeat(64), UpdateChannel.sha(head))
        assertEquals("", UpdateChannel.sha(listOf("SHA256: @@SHA256@@")))          // भरा नहीं = जाँच नहीं
        assertTrue(UpdateChannel.shaMatches("ab".repeat(32), "AB".repeat(32)))
        assertFalse(UpdateChannel.shaMatches("ab".repeat(32), "cd".repeat(32)))
        assertFalse(UpdateChannel.shaMatches("", "cd"))
    }

    @Test fun feedAllEntries() {
        val xml = """<feed><entry><link href="https://github.com/r/x/releases/tag/v12"/><title>DK 3.2</title><content>CHANNEL: beta</content></entry>
            <entry><link href="https://github.com/r/x/releases/tag/v11"/><title>DK 3.1</title><content>CHANNEL: stable</content></entry></feed>"""
        val all = ReleaseFeed.parseAtomAll(xml)
        assertEquals(listOf("v12", "v11"), all.map { it.tag })
        assertEquals("v12", ReleaseFeed.parseAtom(xml)?.tag)
        val stable = all.first { !UpdateChannel.isBetaBody(it.body.lines().map { l -> l.trim() }) }
        assertEquals("v11", stable.tag)
    }

    @Test fun updaterWiring() {
        val u = src("Updater.kt")
        assertTrue(u.contains("latest(Prefs.ghToken(c), Prefs.betaUpdates(c))"))
        assertTrue(u.contains("releases?per_page=15"))
        assertTrue(u.contains("UpdateChannel.shaMatches(rel.sha256, sha256Of(part))"))
        val wf = root(".github/workflows/build.yml")
        // मालिक: main में merge = सीधे स्टेबल ("Latest"); बीटा सिर्फ़ हाथ से ("beta" ✓) = Pre-release
        assertTrue(wf.contains("echo \"CHANNEL: \$CHANNEL\""))
        assertTrue(wf.contains("inputs.beta) && 'beta' || 'stable'"))
        assertTrue(wf.contains("--prerelease"))
        assertTrue(wf.contains("--latest"))
        assertTrue(wf.contains("sha256sum DK.apk"))
        val b = wf.indexOf("if [ \"\$CHANNEL\" = \"beta\" ]; then"); val e = wf.indexOf("else", b)
        assertTrue(b > 0 && wf.indexOf("--prerelease", b) in b..e && wf.indexOf("--latest", e) > e)
        assertTrue(listOf(File("../.github/workflows/promote.yml"), File(".github/workflows/promote.yml")).none { it.exists() })
        assertTrue(src("SettingsSystem.kt").contains("Prefs.setBetaUpdates(this, on)"))
    }

    @Test fun crashReportOptIn() {
        assertFalse(CrashReport.shouldAsk(false, 100, 0))          // शुरू से बंद
        assertTrue(CrashReport.shouldAsk(true, 100, 0))
        assertFalse(CrashReport.shouldAsk(true, 100, 100))         // यही crash पूछ चुके
        assertFalse(CrashReport.shouldAsk(true, 0, 0))
        // 17 नवंबर 2026: नकली key टुकड़ों में - पूरा "AIza…" रूप repo में लिखा हो तो GitHub उसे असली key मानकर चेतावनी देता है
        val fakeKey = "AI" + "za" + "TEST_ONLY_NOT_A_REAL_KEY".padEnd(35, '0')
        val b = CrashReport.body("12 Nov main version 3.1\njava.lang.IllegalStateException: key $fakeKey", "3.1", 34, "Moto")
        assertFalse(b.contains(fakeKey))
        val url = CrashReport.issueUrl("rolexdkscreation-max/DK2.0", "Crash: x", b)
        assertTrue(url.startsWith("https://github.com/rolexdkscreation-max/DK2.0/issues/new?"))
        assertTrue(src("Prefs.kt").contains("getBoolean(CrashReport.ASK_KEY, false)"))
        assertTrue(src("MainActivity.kt").contains("CrashUi.maybeAsk(this)"))
    }

    @Test fun privacyIsTrue() {
        val all = PrivacyText.LINES.joinToString("\n")
        assertFalse(all.contains("कैमरा permission सिर्फ़ टॉर्च के लिए"))      // पहले गलत लिखा था
        assertTrue(all.contains("Gemini"))
        assertTrue(all.contains("crash"))
        val doc = root("docs/PRIVACY.md")
        for (k in listOf("Keystore", "VoiceVault", "Notifications", "open-meteo", "GitHub", "crash")) assertTrue(k, doc.contains(k))
        assertTrue(root("README.md").contains("docs/PRIVACY.md"))
    }
}
