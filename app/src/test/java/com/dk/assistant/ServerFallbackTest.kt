package com.dk.assistant

import com.dk.assistant.backend.BackendWire
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 21 नवंबर 2026 (मालिक): ऐप में कोई key नहीं = DK सर्वर से जवाब; Live बिना key = "पहले API key डाल लीजिए…";
 * अपनी key फेल = सीधे सर्वर. user को खाली नहीं लौटाना.
 */
class ServerFallbackTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @After fun reset() {
        LiveDkServer.readyOverride = null
        LiveDkServer.clearStop()
        LiveDkServer.clearSpare()
        LiveDkServer.lastRunServer = false
        AiRouter.readyOverride = null
        AiRouter.freeOverride = null
    }

    @Test fun statusReadsServerAi() {
        assertTrue(BackendWire.status("""{"level":"free","server_ai":true}""").serverAi)
        assertFalse(BackendWire.status("""{"level":"free","server_ai":false}""").serverAi)
        // पुराना सर्वर (field नहीं): मुफ़्त = नहीं, लाइसेंस वाला = हाँ
        assertFalse(BackendWire.status("""{"level":"free"}""").serverAi)
        assertTrue(BackendWire.status("""{"level":"basic"}""").serverAi)
    }

    @Test fun serverAiOkUsesServerFlag() {
        val r = src("AiRouter.kt")
        assertTrue(r.contains("Plan.level(it) != Plan.Level.FREE || Plan.serverAi(it)"))
        assertTrue(src("backend/BackendApi.kt").contains("Plan.noteServerAi(c, it.serverAi)"))
        assertTrue(src("backend/Plan.kt").contains("Prefs.bool(c, SERVER_AI, true)"))
    }

    @Test fun spareOnlyForOwnKeyFaults() {
        assertTrue(LiveDkServer.spareKind(LiveFailWhy.Kind.KEY))
        assertTrue(LiveDkServer.spareKind(LiveFailWhy.Kind.QUOTA))
        assertTrue(LiveDkServer.spareKind(LiveFailWhy.Kind.MODEL))
        assertTrue(LiveDkServer.spareKind(LiveFailWhy.Kind.SERVER))
        assertFalse(LiveDkServer.spareKind(LiveFailWhy.Kind.MIC))
        assertFalse(LiveDkServer.spareKind(LiveFailWhy.Kind.NO_KEY))
        assertFalse(LiveDkServer.spareKind(null))
    }

    @Test fun ownKeyFailGoesToServerForAWhile() {
        LiveDkServer.readyOverride = true
        val now = 1_000_000L
        assertFalse(LiveDkServer.spare(now))
        assertTrue(LiveDkServer.noteFail(LiveFailWhy.Kind.QUOTA, serverAi = true, now = now))
        assertTrue(LiveDkServer.spare(now + 1_000L))
        assertFalse(LiveDkServer.spare(now + LiveDkServer.SPARE_MS + 1L))   // फिर अपनी key
        // सर्वर की AI बंद / सर्वर नहीं / सर्वर वाली बात ही फेल = नहीं (कोई चक्कर नहीं)
        LiveDkServer.clearSpare()
        assertFalse(LiveDkServer.noteFail(LiveFailWhy.Kind.KEY, serverAi = false, now = now))
        LiveDkServer.lastRunServer = true
        assertFalse(LiveDkServer.noteFail(LiveFailWhy.Kind.KEY, serverAi = true, now = now))
        LiveDkServer.lastRunServer = false
        LiveDkServer.readyOverride = false
        assertFalse(LiveDkServer.noteFail(LiveFailWhy.Kind.KEY, serverAi = true, now = now))
    }

    @Test fun askKeyNoteOnlyWithoutKey() {
        assertEquals("पहले API key डाल लीजिए एक बार, फिर और अच्छे से बात होगी", LiveDkServer.ASK_KEY_HI)
        assertTrue(LiveDkServer.keyNote(true).contains(LiveDkServer.ASK_KEY_HI))
        assertTrue(LiveDkServer.keyNote(true).contains("once"))
        assertEquals("", LiveDkServer.keyNote(false))
    }

    @Test fun wired() {
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("val spare = mine.isNotEmpty() && LiveDkServer.spare()"))
        assertTrue(g.contains("askKey = dkServer && mine.isEmpty()"))
        assertTrue(g.contains("LiveDkServer.lastRunServer = dkServer"))
        assertTrue(src("GeminiLiveSetup.kt").contains("LiveDkServer.keyNote(askKey).also { if (it.isNotEmpty()) askKey = false }"))
        val s = src("DKAccessibilityService.kt")
        assertTrue(s.contains("val spare = LiveDkServer.noteFail(e.kind)"))
        assertTrue(s.contains("val offMs = if (spare) 1_000L else rawMs.coerceAtMost(60_000L)"))
    }
}
