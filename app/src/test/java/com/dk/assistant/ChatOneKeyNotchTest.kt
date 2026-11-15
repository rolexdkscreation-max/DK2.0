package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 15 नवंबर 2026 (मालिक): एक key (ऑल-राउंडर) पर भी चैट में \"Groq: मॉडल का नाम गलत है\" आता था; अपडेट सबसे नीचे + होम पर
 * चमक / popup; DK Island का नॉच (गोल / चौकोर, कैमरे जितना छोटा, सूचना पर पूरा चौड़ा).
 */
class ChatOneKeyNotchTest {
    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    // ---------------------------------------------------------------- चैट: एक-key मोड
    @Test fun oneKey_onlyGemini_inChat() {
        assertTrue(ApiScopes.oneKeySkip(true, "groq"))
        assertTrue(ApiScopes.oneKeySkip(true, "openrouter"))
        assertFalse(ApiScopes.oneKeySkip(true, "gemini"))
        assertFalse(ApiScopes.oneKeySkip(false, "groq"))      // अलग-अलग keys वाला मोड = पहले जैसा
    }

    @Test fun oneKey_hiddenOldKeys_neverReachChat() {
        val b = src("BrainManager.kt")
        assertTrue(b.contains("if (scope == ApiScopes.CHAT && ApiScopes.oneKeySkip(Prefs.oneKeyOn(ctx), p.id)) return null"))
        assertTrue(b.contains("Providers.all.filter { !ApiScopes.oneKeySkip(one, it.id) }"))
        val pin = src("BrainPin.kt")
        assertTrue(pin.contains("if (ApiScopes.oneKeySkip(Prefs.oneKeyOn(c), p.id)) return null"))
        assertTrue(pin.contains("if (ApiScopes.oneKeySkip(Prefs.oneKeyOn(c), prov.id)) return null"))
    }

    @Test fun loneStuckKey_stillTriedOnce() {
        data class K(val id: String, val retry: Boolean, val daily: Boolean)
        val gem = K("gemini", retry = false, daily = false)    // एक बार का 403 = पहले 30 मिनट तक छोड़ दी जाती थी
        val day = K("groq", retry = false, daily = true)
        assertEquals(listOf(gem), ProviderHealth.spareTry(true, listOf(gem, day), { it.retry }, { it.daily }))
        // कोई चलती key है = पहले जैसा (अटकी बिना-retry वाली नहीं)
        assertEquals(emptyList<K>(), ProviderHealth.spareTry(false, listOf(gem), { it.retry }, { it.daily }))
        val r = K("zai", retry = true, daily = false)
        assertEquals(listOf(r), ProviderHealth.spareTry(true, listOf(gem, r), { it.retry }, { it.daily }))
    }

    @Test fun gemini403_forOneModel_triesNextModel_butRealKeyErrorDoesNot() {
        assertTrue(GeminiModels.modelDenied(403, "Permission denied on model gemini-4-argon"))
        assertFalse(GeminiModels.modelDenied(403, "API key not valid. Please pass a valid API key."))
        assertFalse(GeminiModels.modelDenied(403, "The bound service account is deleted or disabled"))
        assertFalse(GeminiModels.modelDenied(403, "Generative Language API has not been used in project 1 before or it is disabled"))
        assertFalse(GeminiModels.modelDenied(400, "model"))
        assertEquals(2, Regex("GeminiModels\\.modelDenied\\(e\\.code").findAll(src("AiClients.kt")).count())
    }

    @Test fun everyChatPath_usesOwnMind_beforeError() {
        assertTrue(src("ChatActivity.kt").contains("OwnMind.answer(this@ChatActivity, t)?.let { DKState.reply(it) } ?: DKState.replyFailed(b.explainAll(e))"))
        assertTrue(src("dkai/DkaiScreen.kt").contains("com.dk.assistant.OwnMind.answer(c, t)?.let { DKState.reply(it) } ?: DKState.replyFailed(b.explainAll(e))"))
        assertTrue(src("AgentRunner.kt").contains("ownMind(goal)?.let { say(it); return ChatOut.DONE }"))
        // खोज / ताज़ा बात पर अंदाज़ा नहीं
        assertTrue(src("OwnMind.kt").contains("if (q.isBlank() || OfflineTalk.needsNet(q)) return null"))
    }

    @Test fun oneKey_errorText_noOtherKeyAdvice() {
        assertTrue(src("BrainManager.kt").contains("c == 400 || c == 404 } && Prefs.oneKeyOn(ctx) -> \"थोड़ी देर बाद फिर भेजिए।\""))
    }

    // ---------------------------------------------------------------- अपडेट
    @Test fun updatePopup_oncePerVersion_thenAfter12h() {
        val h = 60 * 60 * 1000L
        assertTrue(UpdatePrompt.shouldPop(900, 872, 0, 0L, 10 * h))
        assertFalse(UpdatePrompt.shouldPop(900, 872, 900, 10 * h, 11 * h))          // अभी दिखाया
        assertTrue(UpdatePrompt.shouldPop(900, 872, 900, 10 * h, 22 * h))           // 12 घंटे बाद फिर
        assertTrue(UpdatePrompt.shouldPop(901, 872, 900, 10 * h, 11 * h))           // और नया version
        assertFalse(UpdatePrompt.shouldPop(872, 872, 0, 0L, 10 * h))                // पहले से वही version
    }

    @Test fun updateAlert_highlighted_onHome() {
        assertTrue(UpdatePrompt.isUpdateAlert("⬆ सॉफ़्टवेयर अपडेट • DK 3.281"))
        assertFalse(UpdatePrompt.isUpdateAlert("🔑 key बंद है"))
        val m = UpdatePrompt.message("सॉफ़्टवेयर अपडेट", "3.281", "12.0 MB", "चैट ठीक", ready = false)
        assertTrue(m.contains("DK 3.281") && m.contains("12.0 MB") && m.contains("चैट ठीक"))
        assertTrue(src("MainActivity.kt").contains("UpdatePrompt.maybeShow(this, rel)"))
        assertTrue(src("dkai/DkaiScreen.kt").contains("if (upd) com.dk.assistant.UpdatePrompt.pulse(pill)"))
    }

    // ---------------------------------------------------------------- DK Island नॉच
    @Test fun notch_shape_roundToSquare() {
        assertEquals(15f, IslandCore.notchCorner(100, 30, 30), 0.01f)      // पूरा गोल = कैमरे जैसा
        assertEquals(0f, IslandCore.notchCorner(0, 30, 30), 0.01f)         // चौकोर
        assertEquals(10f, IslandCore.notchCorner(100, 120, 20), 0.01f)     // चौड़ी गोली
        assertEquals(22f, IslandCore.corner(100, 22f), 0.01f)              // default = पहले जैसा
        assertEquals(0f, IslandCore.corner(0, 30f), 0.01f)
        assertEquals("चौकोर", IslandCore.roundLabel(0)); assertEquals("पूरा गोल", IslandCore.roundLabel(100))
        assertEquals(IslandCore.NOTCH_W_MIN, IslandCore.notchW(0)); assertEquals(IslandCore.NOTCH_H_MAX, IslandCore.notchH(999))
        assertTrue(IslandCore.SIZE_MIN <= 40 && IslandCore.SIZE_MAX >= 200)
    }

    @Test fun notch_staysEvenWhenNothing_hidesInsideDk() {
        val now = 1_000_000L
        assertTrue(IslandCore.wanted(true, false, IslandCore.Snap(), now, notch = true))
        assertFalse(IslandCore.wanted(true, false, IslandCore.Snap(), now, notch = false))   // पहले जैसा
        assertFalse(IslandCore.wanted(true, true, IslandCore.Snap(), now, notch = true))     // DK ऐप सामने
        assertFalse(IslandCore.wanted(false, false, IslandCore.Snap(), now, notch = true))   // island बंद
    }

    @Test fun notch_wiredInIsland_andSettings() {
        val d = src("DKIsland.kt")
        assertTrue(d.contains("if (notchOn(s)) return View(s).apply {"))
        assertTrue(d.contains("IslandCore.wanted(on(s), AppVisible.visible, sn, now, notchOn(s))"))
        assertTrue(d.contains("g.cornerRadius = IslandCore.notchCorner(round, w, h)"))
        val l = src("SettingsIslandLook.kt")
        assertTrue(l.contains("DKIsland.setNotchOn(this, v)") && l.contains("DKIsland.setRoundPct(this, it)") &&
            l.contains("DKIsland.setNotchW(this, it)") && l.contains("DKIsland.setNotchH(this, it)") && l.contains("DKIsland.previewPeek()"))
    }
}
