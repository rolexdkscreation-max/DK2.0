package com.dk.assistant

import com.dk.assistant.backend.BackendConfig
import com.dk.assistant.backend.BackendException
import com.dk.assistant.backend.BackendWire
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 17 नवंबर 2026 (मालिक का "DK बैकएंड मास्टर प्रॉम्प्ट"): router, लाइसेंस, backend का जवाब पढ़ना, और "ऐप में कोई Cloudflare बॉक्स नहीं" */
class BackendLicenseTest {
    @After fun reset() { AiRouter.readyOverride = null; BackendConfig.override = null }

    private fun src(): File {
        var d: File? = File("").absoluteFile
        while (d != null) {
            listOf(File(d, "src/main/java/com/dk/assistant"), File(d, "app/src/main/java/com/dk/assistant"))
                .firstOrNull { File(it, "AiRouter.kt").exists() }?.let { return it }
            d = d.parentFile
        }
        error("source नहीं मिला")
    }
    private fun read(n: String) = File(src(), n).readText()
    private fun allMain(): List<File> {
        val main = src().parentFile.parentFile.parentFile.parentFile   // src/main
        return main.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.toList()
    }

    // ---------------- router: अपनी key → ऑलराउंड → backend
    @Test fun backendOnlyWhenOwnKeysFail() {
        AiRouter.readyOverride = false
        assertFalse(AiRouter.fallback(AiException(429, "limit")))
        AiRouter.readyOverride = true
        assertTrue(AiRouter.fallback(AiException(429, "limit")))
        assertTrue(AiRouter.fallback(null))   // key ही नहीं
        assertFalse(AiRouter.fallback(kotlinx.coroutines.CancellationException("x")))
        assertFalse(AiRouter.fallback(BackendException(403, BackendWire.LICENSE_REQUIRED, "x", "y")))
        assertFalse(AiRouter.backendFirst(AiRouter.Task.CHAT))
    }

    @Test fun pickPrefersLicenseThenOwn() {
        val lic = BackendException(403, BackendWire.LICENSE_REQUIRED, "m", BackendWire.VOICE_DONE)
        val own = AiException(500, "own")
        assertEquals(lic, AiRouter.pick(own, lic))
        val other = BackendException(502, "ALL_FAILED", "m", "m")
        assertEquals(own, AiRouter.pick(own, other))
        assertEquals(other, AiRouter.pick(null, other))
        assertEquals(other, AiRouter.pick(AiException(-1, "NO_KEY"), other))
    }

    @Test fun routerIsTheOnlyPlaceForOrder() {
        val bm = read("BrainManager.kt"); val ca = read("CoderAi.kt"); val tr = read("Transcriber.kt")
        assertTrue(bm.contains("AiRouter.")); assertTrue(ca.contains("AiRouter.")); assertTrue(tr.contains("SherpaStt.transcribe"))
        // backend को ऐप की कोई key नहीं भेजी जाती
        val api = read("backend/BackendApi.kt")
        assertFalse(api.contains("Prefs.getKey")); assertFalse(api.contains("key_gemini"))
    }

    @Test fun voiceTicketOnce() {
        AiRouter.noteTicket("t1")
        assertEquals("t1", AiRouter.takeTicket())
        assertNull(AiRouter.takeTicket())
    }

    // ---------------- backend के जवाब
    @Test fun licenseRequiredMessage() {
        val e = BackendWire.error(402, """{"ok":false,"code":"LICENSE_REQUIRED","error":"x","say":"${BackendWire.VOICE_DONE}"}""")
        assertTrue(e.stopsTalk); assertTrue(e.final)
        assertEquals("आज की मुफ़्त बातचीत पूरी हो गई। इससे ज़्यादा बात करने के लिए पहले लाइसेंस की लें।", AiRouter.say(e))
        val p = BackendWire.error(403, """{"ok":false,"code":"NEED_PREMIUM","error":"x","need":"premium","level":"basic"}""")
        assertFalse(p.stopsTalk); assertTrue(p.final); assertEquals(BackendWire.PREMIUM_HI, p.say)
        assertEquals(BackendWire.AUTH_INVALID, BackendWire.error(401, "nope").reason)
    }

    @Test fun sseAndQuota() {
        val t = BackendWire.sse("""data: {"t":"नमस्ते"}""")
        assertTrue(t is BackendWire.Ev.Text)
        val d = BackendWire.sse("""data: {"done":true,"provider":"groq","model":"m","quota":{"voice_left":50,"chat_left":3,"warn":true}}""")
        assertTrue(d is BackendWire.Ev.Done)
        assertEquals(50, (d as BackendWire.Ev.Done).quota!!.voiceLeft)
        assertNull(BackendWire.sse(": ping"))
    }

    @Test fun searchKeepsOldChipShape() {
        for (p in listOf("tavily", "exa", "linkup", "firecrawl", "google")) {
            val (prov, raw) = BackendWire.searchRaw("""{"provider":"$p","results":[{"title":"DK","url":"https://dk.example/a","snippet":"पाठ"}]}""")
            val srcs = WebCite.normalizeResults(prov, raw)
            assertEquals(p, 1, srcs.size)
            assertEquals("https://dk.example/a", srcs[0].url)
        }
    }

    @Test fun keyShapeAndStatus() {
        assertTrue(BackendWire.keyShape("DK-ABCD-EF23-XY99"))
        assertTrue(BackendWire.keyShape("dk-abcd-ef23-xy99"))
        assertFalse(BackendWire.keyShape("DK-ABCD-EF23"))
        val s = BackendWire.status("""{"level":"basic","expires_at":null,"owner":false,"quota":{"voice_left":600,"chat_left":30,"warn":false}}""")
        assertEquals("basic", s.level); assertEquals(0L, s.expiresAt); assertEquals(30, s.quota!!.chatLeft)
    }

    // ---------------- एक ही जगह से पता, सिर्फ़ https
    @Test fun configOnlyHttpsAndWhatsapp() {
        assertEquals("", BackendConfig.clean("http://x.workers.dev"))
        assertEquals("https://x.workers.dev", BackendConfig.clean(" https://x.workers.dev/ "))
        BackendConfig.override = BackendConfig.Values("https://x.workers.dev", "p", "k", "p.firebaseapp.com", "+91 98765 43210")
        assertTrue(BackendConfig.ready())
        val w = BackendConfig.whatsappLink()
        assertNotNull(w); assertTrue(w!!.startsWith("https://wa.me/919876543210?text="))
        assertTrue(java.net.URLDecoder.decode(w.substringAfter("text="), "UTF-8") == "नमस्ते, मुझे DK का लाइसेंस की चाहिए")
        BackendConfig.override = BackendConfig.Values("https://x.workers.dev", "p", "k", "d", "")
        assertNull(BackendConfig.whatsappLink())
    }

    @Test fun backendTokensAreSecrets() {
        assertTrue(SecretNames.isSecret("be_id_token")); assertTrue(SecretNames.isSecret("be_refresh_token"))
    }

    // ---------------- मालिक का सख़्त नियम: ऐप में Cloudflare/D1/Worker/WEBHOOK/ALLOWED_EMAILS का कोई बॉक्स नहीं
    @Test fun noCloudflareSecretInputAnywhere() {
        val bad = listOf("CLOUDFLARE_ACCOUNT_ID", "CLOUDFLARE_API_TOKEN", "D1_DATABASE_ID", "WEBHOOK_SECRET", "ALLOWED_EMAILS",
            "Account ID", "accountId", "cf_token", "d1_id", "webhook_secret", "allowed_emails")
        for (f in allMain()) {
            val t = f.readText()
            for (b in bad) assertFalse("${f.name} में \"$b\"", t.contains(b))
        }
        // BackendConfig सिर्फ़ BuildConfig से, कोई Prefs नहीं
        val cfg = read("backend/BackendConfig.kt")
        assertFalse(cfg.contains("Prefs")); assertTrue(cfg.contains("BuildConfig.BACKEND_URL"))
    }

    @Test fun licenseSectionPresent() {
        val s = read("SettingsLicense.kt")
        assertTrue(s.contains("लाइसेंस की")); assertTrue(s.contains("चालू करें")); assertTrue(s.contains("लाइसेंस की पाएँ"))
        assertTrue(s.contains("BackendConfig.whatsappLink()"))
        assertTrue(read("SettingsActivity.kt").contains("\"license\""))
    }

    @Test fun typingMicServerFirstThenPhone() {
        assertTrue(read("DKChatUi.kt").contains("SherpaDictation.pick(act,"))
        assertTrue(read("dkai/DkaiScreen.kt").contains("SherpaDictation.pick(c,"))
        assertTrue(DictationChunks.cut(500, 700, 1200))
        assertFalse(DictationChunks.cut(100, 700, 1200))
        assertTrue(DictationChunks.cut(0, 0, 12_000))
        assertFalse(DictationChunks.worth(100))
        assertEquals(0.0, DictationChunks.rms(ShortArray(4), 4), 0.0)
    }

    // ---------------- कोडिंग काम सर्वर पर
    @Test fun taskSyncBody() {
        val j = TaskStatus.Job(TaskStatus.CODER, "कोडर", "Coder", "ऐप ठीक करो", 1000L, steps = 4, last = "बिल्ड", state = TaskStatus.State.FAILED, result = "टूटा")
        val o: JSONObject = TaskSync.body(j, "feature/dk-x", "https://github.com/a/b/pull/1", "a/b")
        assertEquals("${TaskStatus.CODER}-1000", o.getString("id"))
        assertEquals("failed", o.getString("status")); assertEquals(20, o.getInt("progress"))
        assertEquals("https://github.com/a/b/pull/1", o.getString("pr_url"))
        assertEquals("coder", o.getJSONObject("meta").getString("kind"))
        assertFalse(o.toString().contains("token"))
        assertEquals("stopped", TaskSync.statusOf(TaskStatus.State.CANCELLED))
    }
}
