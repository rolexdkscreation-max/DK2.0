package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 15 नवंबर 2026 (मालिक): "चैट पूरी तरह अच्छे से चेक करो, जो गड़बड़ी है सब ठीक करो" + island: चलता काम / कॉल / बिना देखा
 * notification दिखे, देख लिया तो हटे.
 */
class ChatIslandFixTest {
    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    // ---------------------------------------------------------------- चैट
    @Test fun waitLimit_longerWhileWorking() {
        assertEquals(ChatFlow.WAIT_IDLE_MS, ChatFlow.waitLimit(working = false, slow = true))
        assertEquals(ChatFlow.WAIT_WORKING_MS, ChatFlow.waitLimit(working = true, slow = false))
        assertEquals(ChatFlow.WAIT_SLOW_MS, ChatFlow.waitLimit(working = true, slow = true))
        assertTrue(ChatFlow.WAIT_WORKING_MS > 55_000L)            // AI की अपनी रोक (55 सेकंड) पहले साफ़ कारण दे
        val a = src("ChatActivity.kt")
        assertTrue(a.contains("val limit = ChatFlow.waitLimit(working, slowOk)"))
        assertFalse(a.contains("if (slowOk) 90_000L else 30_000L"))
    }

    @Test fun race_sameKeyNotTwiceByTimer() {
        assertFalse(ChatFlow.raceOk(byTimer = true, nextId = "gemini", runningIds = listOf("gemini")))
        assertTrue(ChatFlow.raceOk(byTimer = true, nextId = "groq", runningIds = listOf("gemini")))
        assertTrue(ChatFlow.raceOk(byTimer = false, nextId = "gemini", runningIds = listOf("gemini")))   // फ़ेल होने पर अगली
        val b = src("BrainManager.kt")
        assertTrue(b.contains("ChatFlow.raceOk(byTimer, cands[next].first.id, runningIds)"))
        assertTrue(b.contains("launch { delay(1000); start(byTimer = true); delay(2000); start(byTimer = true) }"))
    }

    @Test fun phoneTaskPath_triesLoneKey_plainMessage_ownMind() {
        val b = src("BrainManager.kt")
        assertTrue(b.contains("val tryList = good + ProviderHealth.spareTry(good.none { clientFor(it) != null }, bad,"))
        assertFalse(b.contains("(गलती \$code)"))
        val r = src("AgentRunner.kt")
        assertTrue(r.contains("if (chatMode) ownMind(goal)?.let { say(it); return }"))
        assertTrue(r.contains("val msg = ChatFlow.plain(brain.explainAll(e))"))
        assertFalse(r.contains("DKState.replyFailed(msg + \"\\n(\" + (e.message"))
        assertFalse(src("dkai/DkaiScreen.kt").contains("e.message?.take(120) ?: \"गड़बड़ी\""))
    }

    @Test fun plain_dropsTechnicalTail() {
        assertEquals("यह मॉडल नहीं मिला।", ChatFlow.plain("यह मॉडल नहीं मिला। (HTTP 404 models/x not found)"))
        assertEquals("नेट नहीं है।", ChatFlow.plain("नेट नहीं है। (SocketTimeoutException: timeout)"))
        assertEquals("ठीक (2 कदम)", ChatFlow.plain("ठीक (2 कदम)"))
    }

    @Test fun slowNet_andCutOff() {
        assertTrue(ChatFlow.slowNet(java.net.SocketTimeoutException("x")))
        assertTrue(ChatFlow.slowNet(java.io.InterruptedIOException("timeout")))
        assertTrue(ChatFlow.slowNet(java.io.IOException("Connection reset")))
        assertFalse(ChatFlow.slowNet(java.net.UnknownHostException("x")))
        assertFalse(ChatFlow.slowNet(AiException(429, "limit")))
        assertTrue(ChatFlow.cutOff("MAX_TOKENS")); assertFalse(ChatFlow.cutOff("STOP")); assertFalse(ChatFlow.cutOff(null))
        val c = src("AiClients.kt")
        assertTrue(c.contains("sse(req, if (search) groundHttp else fastHttp)"))
        assertTrue(c.contains("if (got || !ChatFlow.slowNet(e)) throw e"))
        assertEquals(2, Regex("continueCut\\(m, system, history, prompt, full, onDelta\\)").findAll(c).count())
        assertTrue(c.contains("streamRaw(m, system, h, ChatFlow.CONTINUE_PROMPT, false, false)"))
    }

    // ---------------------------------------------------------------- island
    private val now = 10_000_000L
    private fun n(key: String, at: Long, ongoing: Boolean = false, call: Boolean = false) =
        IslandCore.Notif(key, "com.whatsapp", "WhatsApp", "राहुल", "हाय", at, ongoing, call)

    @Test fun unseen_hidesSeenAndOngoing_newOneComesBack() {
        val a = n("a", now); val b = n("b", now)
        assertEquals(listOf("a", "b"), IslandCore.unseen(listOf(a, b), emptyMap()).map { it.key })
        assertEquals(listOf("b"), IslandCore.unseen(listOf(a, b), mapOf("a" to now)).map { it.key })       // देख लिया = हटा
        assertEquals(listOf("a"), IslandCore.unseen(listOf(a.copy(at = now + 5)), mapOf("a" to now)).map { it.key })   // उसी पर नया = फिर
        assertTrue(IslandCore.unseen(listOf(n("dl", now, ongoing = true)), emptyMap()).isEmpty())          // download / VPN नहीं
        assertEquals(1, IslandCore.unseen(listOf(n("c", now, ongoing = true, call = true)), emptyMap()).size) // कॉल रहे
        assertTrue(IslandCore.viewed(IslandCore.SEEN_VIEW_MS)); assertFalse(IslandCore.viewed(300))
    }

    @Test fun notch_showsPillWhenSomethingRuns() {
        assertTrue(IslandCore.notchIdle(IslandCore.Snap(), now))
        val task = IslandCore.Task("pc:1", "pc", "DK कंप्यूटर", "काम", "चल रहा है", "", 1, now - 1000, 0L, active = true)
        assertFalse(IslandCore.notchIdle(IslandCore.Snap(tasks = listOf(task)), now))
        assertFalse(IslandCore.notchIdle(IslandCore.Snap(call = IslandCore.Call("राहुल", false, now - 5000)), now))
        assertFalse(IslandCore.notchIdle(IslandCore.Snap(notifs = listOf(n("a", now))), now))
    }

    @Test fun island_wiring() {
        val d = src("DKIsland.kt")
        assertTrue(d.contains("val notifs = if (notifsOn(s)) IslandFeed.shown() else emptyList()"))
        assertTrue(d.contains("IslandFeed.seenApp(fg)"))
        assertTrue(d.contains("if (expanded && IslandCore.viewed(SystemClock.uptimeMillis() - expandedAt)) IslandFeed.seenKeys(lastNotifKeys)"))
        assertTrue(d.contains("private fun notchEmpty(s: Context) = notchOn(s) && notchIdle"))
        assertTrue(d.contains("if (!wide && notchEmpty(s)) {"))
        val f = src("IslandFeed.kt")
        assertTrue(f.contains("fun shown(): List<IslandCore.Notif> = IslandCore.unseen(list(), seen)"))
        assertTrue(f.contains("seenKeys(listOf(key))      // island से खोला = देख लिया"))
    }
}
