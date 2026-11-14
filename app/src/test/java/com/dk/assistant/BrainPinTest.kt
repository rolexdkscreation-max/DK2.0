package com.dk.assistant

import com.dk.assistant.research.DeepResearch
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 14 नवंबर 2026 (मालिक): हर काम का मॉडल खुद चुनें (खाली = fallback, चुना = सिर्फ़ वही), सारी keys पर fallback,
 * तेज़ + तारीख़ वाली डीप रिसर्च (बिना कहे भी), "जी <नाम>", और मालिक की बात पहले.
 */
class BrainPinTest {
    private fun src(path: String) = listOf(File("src/main/java/com/dk/assistant/$path"), File("app/src/main/java/com/dk/assistant/$path")).first { it.exists() }.readText()

    // ---- चुनने वाला (pin)
    @Test fun pinSavesOnlyWhereTheKeyIsNeverTheKey() {
        val p = BrainPin.Pin("gemini", "coder:gemini3", "gemini-3.9-flash")
        val s = BrainPin.encode(p)
        assertEquals("gemini|coder:gemini3|gemini-3.9-flash", s)
        assertFalse(s.contains("AIza"))
        assertEquals(p, BrainPin.decode(s))
        // OpenRouter जैसे मॉडल नाम में ":" / "/" भी चलें
        assertEquals("google/gemma-4-31b-it:free", BrainPin.decode("openrouter|chat:openrouter|google/gemma-4-31b-it:free")!!.model)
        // डिफ़ॉल्ट खाली = कुछ नहीं चुना (= fallback)
        assertNull(BrainPin.decode("")); assertNull(BrainPin.decode(null)); assertNull(BrainPin.decode("gemini||x"))
        assertEquals("", BrainPin.encode(null))
        assertTrue(BrainPin.line(BrainPin.CHAT, null, null).contains("fallback"))
        assertTrue(BrainPin.line(BrainPin.CHAT, p, "Google Gemini • कोडिंग एजेंट key 3 • …abcd").contains("fallback बंद"))
    }

    @Test fun everyEnteredKeyFromEveryCompanyIsOfferedOnce() {
        val slots = listOf(
            Triple("chat:gemini", "gemini", "AIzaAAAA1111"),
            Triple("coder:gemini", "gemini", "AIzaAAAA1111"),      // वही key दोबारा = एक बार
            Triple("coder:gemini3", "gemini", "AIzaBBBB2222"),
            Triple("pc:zai", "zai", "zzzz9999"),
            Triple("chat:nvidia", "nvidia", "nvapi-7777"),
            Triple("live:groq", "groq", ""))                       // खाली = नहीं
        val o = BrainPin.options(slots, { it }, { it })
        assertEquals(listOf("chat:gemini", "coder:gemini3", "pc:zai", "chat:nvidia"), o.map { it.ref })
        assertTrue(o.none { it.label.contains("AIzaAAAA1111") })    // पूरी key कभी नहीं दिखती
        assertTrue(o[0].label.endsWith("…1111"))
        // Live / वेबसाइट = सिर्फ़ Gemini keys
        assertEquals(listOf("gemini"), BrainPin.allowed(BrainPin.LIVE, o).map { it.provider }.distinct())
        assertEquals(4, BrainPin.allowed(BrainPin.CODER, o).size)
        assertEquals(6, BrainPin.FEATURES.size)
        assertEquals("कोडिंग एजेंट key 3", BrainPin.whereLabel("coder:gemini3"))
    }

    @Test fun pinnedMeansOnlyThatModelEverywhere() {
        val b = src("BrainManager.kt")
        assertTrue(b.contains("BrainPin.chatClient(ctx)?.let { (id, c) -> return if (p.id == id) c else null }"))
        assertTrue(b.contains("clientFor(p, scope, pin = false)"))      // Settings का "टेस्ट" चुने हुए से नहीं बदलता
        val pin = src("BrainPin.kt")
        assertTrue(pin.contains("GeminiClient(r.key, r.model).apply { only = true; backups = emptyList() }"))
        assertTrue(pin.contains("CoderAi(r.provider, r.key, r.model, r.base, emptyList()"))
        val coder = src("CoderAi.kt")
        assertTrue(coder.contains("?: BrainPin.coder(c, BrainPin.CODER)"))
        assertTrue(coder.contains("BrainPin.coder(c, BrainPin.PC, sink)?.let { return it }"))
        assertTrue(coder.contains("internal fun pinOnly(): CoderAi { pinned = true; chain = emptyList(); return this }"))
        assertTrue(coder.contains("if (backup?.pinned == true) return null"))
        val live = src("GeminiLive.kt")
        assertTrue(live.contains("livePin?.let { listOf(it.key) }"))
        assertTrue(live.contains("if (livePin != null) { models = listOf(livePin.model.removePrefix(\"models/\")); break }"))
        assertTrue(src("forge/ForgeController.kt").contains("WebsiteForgeClient.streamPinned(pin.key, pin.model, prompt)"))
        assertTrue(src("StoryWriter.kt").contains("BrainPin.resolve(c, BrainPin.STORY)"))
        // GeminiModels: खाली chain = सिर्फ़ चुना मॉडल
        assertEquals(listOf("gemini-x"), GeminiModels.orderSkippingGone("gemini-x", emptyList()) { false })
    }

    @Test fun settingsCardAndSearch() {
        val s = src("SettingsBrain.kt")
        assertTrue(s.contains("card(\"हर काम का मॉडल — खुद चुनें\""))
        assertTrue(s.contains("ModelLister.liveModels(o.key)"))
        assertTrue(s.contains("BrainPin.set(this, f, null)"))
        assertTrue(src("SettingsSearch.kt").contains("E(\"brain\", \"हर काम का मॉडल — खुद चुनें\""))
    }

    @Test fun fallbackUsesEveryEnteredKey() {
        assertTrue(src("BrainManager.kt").contains("BrainPin.borrowed(ctx, p)"))
        assertTrue(src("CoderAi.kt").contains("return withSpares(c, own, sink)?.also { it.waitOnLimit = false }"))
        assertTrue(src("GeminiLive.kt").contains("ownKeys + BrainPin.spareGemini(svc, ownKeys)"))
        assertTrue(src("research/DeepResearchRun.kt").contains("BrainPin.spareGemini(c, it)"))
    }

    // ---- डीप रिसर्च: बिना कहे भी, तेज़, आज की तारीख़
    @Test fun deepResearchDecidesByItselfAndKnowsToday() {
        assertTrue(DeepResearch.wanted("कोई बेस्ट TTS बताओ जो ओपन सोर्स हो और फोन पे यूज़ करने लायक हो", auto = true))
        assertTrue(DeepResearch.wanted("जेमिनी के API की थ्री टियर के बारे में बताओ और अभी लांच में कौन सा मॉडल है", auto = true))
        assertFalse(DeepResearch.wanted("कोई बेस्ट TTS बताओ जो ओपन सोर्स हो और फोन पे यूज़ करने लायक हो", auto = false))
        assertFalse(DeepResearch.wanted("नमस्ते", auto = true))
        assertTrue(DeepResearch.fresh("अभी लांच हुआ नया मॉडल"))
        assertTrue(DeepResearch.fresh("latest Gemini models"))
        assertFalse(DeepResearch.fresh("ताजमहल का इतिहास"))
        val p = DeepResearch.planPrompt("Gemini API tiers", "14 November 2026")
        assertTrue(p.contains("Today is 14 November 2026") && p.contains("official source"))
        assertFalse(DeepResearch.planPrompt("x").contains("Today is"))
        assertTrue(src("Prefs.kt").contains("fun deepAutoSwitch(c: Context): Boolean = sp(c).getBoolean(DEEP_AUTO, true)"))
        val run = src("research/DeepResearchRun.kt")
        assertTrue(run.contains("fast = true, limitMs = 25_000"))
        assertTrue(run.contains("if (pages.count { it.full } >= DeepResearch.MIN_PICK) break"))
        assertTrue(run.contains("Semaphore(8)"))
        val net = src("research/DeepResearchNet.kt")
        assertTrue(net.contains("startPublishedDate") && net.contains("time_range") && net.contains("fromDate"))
    }

    // ---- नाम
    @Test fun callsUserJiNameAndUsesAssistantName() {
        assertEquals("जी धीरज", NameRule.call("धीरज"))
        assertEquals("जी मम्मी", NameRule.call(" मम्मी "))
        assertEquals("", NameRule.call(""))
        assertEquals("हाँ जी धीरज", NameRule.swap("हाँ बॉस", "धीरज"))
        assertEquals("जी पापा, कॉल आ रहा है।", NameRule.swap("बॉस, कॉल आ रहा है।", "पापा"))
        assertEquals("ठीक है जी सर", NameRule.swap("ठीक है बॉस", "सर"))
        assertEquals("जी धीरज", NameRule.swap("जी बॉस", "धीरज"))
        // किसी और "बॉस" की बात / नाम खाली = वैसा ही
        assertEquals(AssistantBrand.CREATOR_ANSWER, NameRule.swap(AssistantBrand.CREATOR_ANSWER, "राहुल"))
        assertEquals("बॉस ने कहा था", NameRule.swap("बॉस ने कहा था", "धीरज"))
        assertEquals("हाँ बॉस", NameRule.swap("हाँ बॉस", ""))
        val b = NameRule.block("धीरज", "जार्विस")
        assertTrue(b.contains("\"जी धीरज\"") && b.contains("मैं जार्विस हूँ") && b.contains(AssistantBrand.OWNER_NAME))
        assertTrue(src("BrainManager.kt").contains("append(NameRule.block(Prefs.userName(ctx), Prefs.assistantName(ctx)))"))
        assertTrue(src("DKState.kt").contains("NameRule.swap(t0, Prefs.userName(it))"))
    }

    // ---- मालिक पहले
    @Test fun ownerCommandWinsOverSomeoneElse() {
        val now = 1_000_000L
        val play = JSONObject().put("request", "दूसरा गाना चलाओ")
        assertNotNull(SideTalk.ownerFirst("phone_task", play, SideTalk.Who.OTHER, now - 30_000, now))
        assertTrue(SideTalk.ownerFirst("phone_task", play, SideTalk.Who.OTHER, now - 30_000, now)!!.contains("बॉस की बात ही मानूँगी"))
        // मालिक खुद / आवाज़ पता नहीं / बहुत देर पहले / सिर्फ़ जानकारी = रोकना नहीं
        assertNull(SideTalk.ownerFirst("phone_task", play, SideTalk.Who.BOSS, now - 30_000, now))
        assertNull(SideTalk.ownerFirst("phone_task", play, SideTalk.Who.UNKNOWN, now - 30_000, now))
        assertNull(SideTalk.ownerFirst("phone_task", play, SideTalk.Who.OTHER, now - SideTalk.OWNER_HOLD_MS - 1, now))
        assertNull(SideTalk.ownerFirst("phone_task", play, SideTalk.Who.OTHER, 0L, now))
        assertNull(SideTalk.ownerFirst("web_search", JSONObject(), SideTalk.Who.OTHER, now - 1_000, now))
        assertTrue(SideTalk.PROMPT.contains("The owner always comes first"))
        assertTrue(src("GeminiLiveTools.kt").contains("SideTalk.ownerFirst(name, args, whoNow, SideTalk.global.bossTaskAt, nowMs)"))
    }
}
