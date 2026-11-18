package com.dk.assistant

import com.dk.assistant.backend.BackendException
import com.dk.assistant.backend.BackendWire
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** 18 नवंबर 2026: DK लाइव बिना अपनी key - सर्वर का छोटा टिकट, मुफ़्त समय पूरा = आज बंद, अपनी key वाला रास्ता वैसा ही */
class LiveDkServerTest {
    @After fun reset() { LiveDkServer.readyOverride = null; LiveDkServer.clearStop(); LiveDkServer.forget() }

    private val ws = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"

    @Test fun ticket_parse() {
        val t = BackendWire.liveTicket("""{"ok":true,"token":"auth_tokens/abc","ws_url":"$ws","expires_at":1000,"level":"free","quota":{"voice_seconds_used":60,"voice_seconds_limit":600}}""")
        assertEquals("auth_tokens/abc", t.token); assertEquals(ws, t.wsUrl); assertEquals(1000L, t.expiresAt)
        assertEquals("Token auth_tokens/abc", LiveDkServer.auth(t))
    }

    @Test fun ticket_rejectsOtherHostOrBlank() {
        for (b in listOf("""{"token":"x","ws_url":"wss://evil.example/ws"}""", """{"token":"","ws_url":"$ws"}""", """{"ws_url":"$ws"}""")) {
            try { BackendWire.liveTicket(b); fail(b) } catch (e: BackendException) { assertEquals("BAD_TICKET", e.reason) }
        }
    }

    @Test fun freshAndExpired() {
        val t = BackendWire.LiveTicket("t", ws, 100_000L, null)
        assertTrue(LiveDkServer.fresh(t, 90_000L))
        assertFalse(LiveDkServer.fresh(t, 96_000L))          // 5 सेकंड से कम बचा = नया
        assertFalse(LiveDkServer.fresh(null, 0L))
        assertTrue(LiveDkServer.expired())                    // टिकट है ही नहीं
    }

    @Test fun markerAndOn() {
        assertTrue(LiveDkServer.isServer(LiveDkServer.KEY)); assertFalse(LiveDkServer.isServer("AIzaXYZ")); assertFalse(LiveDkServer.isServer(null))
        LiveDkServer.readyOverride = false; assertFalse(LiveDkServer.on())
        LiveDkServer.readyOverride = true; assertTrue(LiveDkServer.on())
        val now = 1_763_400_000_000L
        LiveDkServer.stopToday(now)
        assertFalse(LiveDkServer.on(now)); assertFalse(LiveDkServer.on(now + 60_000L))
        assertTrue(LiveDkServer.on(now + 25 * 3_600_000L))    // अगले दिन फिर
        LiveDkServer.stopToday(now); LiveDkServer.clearStop(); assertTrue(LiveDkServer.on(now))
    }

    @Test fun wiring() {
        val g = LiveSrc.all()
        assertTrue(g.contains("else try { LiveDkServer.serverRequest(svc) }"))
        assertTrue(g.contains("if (LiveDkServer.on()) listOf(LiveDkServer.KEY)"))
        assertTrue(g.contains("if (LiveDkServer.isServer(key)) return GeminiLive.FALLBACK_MODELS"))
        assertTrue(g.contains("LiveDkServer.stopToday(); stopped = true"))
        assertTrue(g.contains("if (dkServer && LiveDkServer.expired())"))
        val warm = liveFile("GeminiLiveWakeWarm.kt")
        assertTrue(warm.contains("keys.all { LiveDkServer.isServer(it) }"))
        val s = AccSrc.all()
        assertTrue(s.contains("GeminiLive.key(this).isBlank() && !LiveDkServer.on()"))
        assertTrue(s.contains("GeminiLive.canRun(this) && System.currentTimeMillis() > liveOffUntil"))
        // टिकट URL में नहीं, header में; असली key कभी नहीं
        val srv = liveFile("LiveDkServer.kt")
        assertTrue(srv.contains(".header(\"Authorization\", auth(it))"))
        assertFalse(srv.contains("access_token"))
    }

    private fun liveFile(n: String): String {
        var d: File? = File("").absoluteFile
        while (d != null) {
            listOf(File(d, "src/main/java/com/dk/assistant/$n"), File(d, "app/src/main/java/com/dk/assistant/$n")).firstOrNull { it.exists() }?.let { return it.readText() }
            d = d.parentFile
        }
        error("$n नहीं मिला")
    }
}
