package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v5 (मालिक की माँग):
 *  1) DK लाइव 1 सेकंड में जुड़े / टूटे तो 1 सेकंड में फिर जुड़े / न जुड़ पाए तो यूज़र को बताए
 *  2) "बेस्ट AI कौन" - असली नाम; इमेज/वीडियो/म्यूज़िक में अपना नाम नहीं, बाकी में आखिर में DK भी; "ऑल-राउंडर"
 *  3) खर्चा: 1 मिलियन डॉलर अभी तक का अनुमान - आगे बढ़ सकता है
 *  4) DK सारी सेटिंग्स जाने, ऑन-ऑफ करे - दो बार पक्का करके ("ठीक है बॉस" + "सर, क्या सच में...")
 *  5) टीचर मोड अपने-आप नहीं खुलता - यूज़र खुद माँगे तब; अंदर हर विषय / हर भाषा प्रोफेशनल तरीके से
 *  6) Extra API: key से कंपनी अपने-आप + मॉडल चुनें; DK कंप्यूटर: Gemini -> Groq -> OpenRouter -> Extra; गलतियाँ / रिपोर्ट नहीं दिखती
 */
class DkLiveSettingsExtraApiTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    // ---- 1) Live जुड़ना
    @Test fun live_reconnectsWithinOneSecond() {
        assertEquals(1_000L, LiveRetry.FAST_MAX_MS)
        assertTrue((0..9).all { LiveRetry.fast(true, LiveRetry.liveOnMs(true, it)) <= 1_000L })
        assertTrue(src("DKAccessibilityService.kt").contains("if (userLive) LiveRetry.FAST_MAX_MS else 2 * 60_000L"))
    }

    @Test fun live_tellsUserOnceWhenItCannotConnect() {
        assertFalse(LiveConnectNotice.shouldTell(1, false))
        assertTrue(LiveConnectNotice.shouldTell(2, false))
        assertFalse(LiveConnectNotice.shouldTell(3, true))          // एक रुकावट में एक ही बार
        assertTrue(LiveConnectNotice.TROUBLE.contains("जुड़ नहीं पा रही"))
        assertTrue(LiveConnectNotice.BACK.contains("बोलिए"))
        assertTrue(LiveConnectNotice.hard("429 quota").contains("लिमिट"))
        assertTrue(LiveConnectNotice.hard("API key not valid").contains("key"))
        assertFalse(LiveConnectNotice.hard("weird").contains("weird"))  // तकनीकी गड़बड़ यूज़र को नहीं
        assertEquals(LiveConnectNotice.MIC, LiveConnectNotice.forStop("माइक नहीं खुला", true))
        assertEquals(LiveConnectNotice.TROUBLE, LiveConnectNotice.forStop("Gemini Live से जुड़ नहीं पाया: timeout", true))
        assertTrue(LiveConnectNotice.forStop("Gemini Live की फ्री लिमिट खत्म", false).contains("लिमिट"))
        val s = src("DKAccessibilityService.kt")
        assertTrue(s.contains("if (userLive) liveTroubleSay("))
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("LiveConnectNotice.shouldTell(fails, svc.liveTroubleShown)"))
        assertTrue(g.contains("DKState.reply(LiveConnectNotice.BACK)"))
    }

    // ---- 2) + 3) बेस्ट AI और खर्चा
    @Test fun bestAi_andCost_inEveryPrompt() {
        val b = SpeakingStyle.block("धीरज")
        assertTrue(b.contains("1 million dollars"))
        assertTrue(b.contains("और भी बढ़ सकता है"))
        assertTrue(b.contains("अनुमानित"))
        assertTrue(b.contains("BEST AI QUESTION"))
        assertTrue(b.contains("NEVER put your own name"))
        assertTrue(b.contains("Video:") && b.contains("Music") && b.contains("Images:"))
        assertTrue(b.contains("Coding:") && b.contains("Writing") && b.contains("Research"))
        assertTrue(b.contains("DK भी यह काम बहुत अच्छे से कर सकती है"))
        assertTrue(b.contains("ऑल-राउंडर काम करने में सबसे बेस्ट"))
    }

    // ---- 4) सेटिंग्स
    @Test fun settings_findByUsersWords() {
        assertEquals("wake", DkSettings.find("हमेशा सुनो वाला बंद कर दो")?.id)
        assertEquals("calls", DkSettings.find("आने वाली कॉल बोलकर बताना")?.id)
        assertEquals("computer_ask", DkSettings.find("कंप्यूटर पूछे")?.id)
        assertEquals("computer", DkSettings.find("DK कंप्यूटर")?.id)
        assertEquals("duck", DkSettings.find("गाना धीमा")?.id)
        assertNull(DkSettings.find(""))
        assertEquals(DkSettings.ITEMS.size, DkSettings.ITEMS.map { it.id }.toSet().size)
    }

    @Test fun settings_needTwoConfirmations() {
        val it = DkSettings.find("wake")!!
        DkSettings.pending = null
        assertFalse(DkSettings.confirmOk(it, true, 1_000L))                // पहले पूछे बिना "confirmed" नहीं चलता
        val ask = DkSettings.ask(it, true, 1_000L)
        assertTrue(ask.contains("ठीक है बॉस"))
        assertTrue(ask.contains("सर, क्या आप सच में"))
        assertTrue(ask.contains("DO NOT change it yet"))
        assertFalse(DkSettings.confirmOk(it, false, 2_000L))               // उल्टा (ऑफ) = नहीं
        assertTrue(DkSettings.confirmOk(it, true, 5_000L))                 // वही सेटिंग, वही ऑन, समय के अंदर
        assertFalse(DkSettings.confirmOk(it, true, 6_000L))                // एक हाँ = एक बदलाव
        DkSettings.ask(it, true, 0L)
        assertFalse(DkSettings.confirmOk(it, true, DkSettings.CONFIRM_WINDOW_MS + 1))   // बहुत देर बाद = फिर पूछो
    }

    @Test fun settings_invertedAndCatalog() {
        val barge = DkSettings.find("barge")!!
        assertTrue(DkSettings.isOn(barge, false))
        assertTrue(DkSettings.prefFor(barge, true).not())
        val cat = DkSettings.catalog { false }
        assertTrue(cat.contains("[wake]: OFF"))
        assertTrue(cat.contains("AI दिमाग • Extra API"))
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("put(\"name\", \"dk_settings\")"))
        assertTrue(g.contains("\"dk_settings\" -> DkSettingsApply.run(svc, args)"))
        assertTrue("dk_settings" in LeakedCall.TOOLS)
        assertTrue(LiveConnect.features(false, emptyList(), false).contains("dk_settings"))
    }

    // ---- 5) टीचर मोड
    @Test fun teacher_opensOnlyWhenAsked() {
        assertTrue(TeacherBoard.isOpenCommand("टीचर मोड खोलो"))
        assertTrue(TeacherBoard.isOpenCommand("teacher mode on karo"))
        assertTrue(TeacherBoard.isOpenCommand("पढ़ाई मोड चालू करो"))
        assertFalse(TeacherBoard.isOpenCommand("प्रकाश संश्लेषण क्या होता है"))
        assertFalse(TeacherBoard.isOpenCommand("2 + 2 कितना होता है"))
        val link = src("TeacherLink.kt")
        assertTrue(link.contains("if (TeacherBoard.isOpenCommand(t)) open(c,"))
        val blk = TeacherBoard.block()
        assertTrue(blk.contains("NEVER open it by yourself"))
        assertTrue(blk.contains("Marathi") && blk.contains("English"))
        assertFalse(LiveConnect.features(false, emptyList(), false).contains("opens by itself"))
    }

    // ---- 6) Extra API + DK कंप्यूटर
    @Test fun extraApi_detectsCompanyFromKey() {
        assertEquals("anthropic", ApiCompanies.sure("sk-ant-api03-abcdefghijklmnopqrstuvwxyz")?.id)
        assertEquals("openrouter", ApiCompanies.sure("sk-or-v1-abcdefghijklmnopqrstuvwxyz")?.id)
        assertEquals("xai", ApiCompanies.sure("xai-abcdefghijklmnopqrstuvwxyz0123")?.id)
        assertEquals("perplexity", ApiCompanies.sure("pplx-abcdefghijklmnopqrstuvwxyz")?.id)
        assertEquals("groq", ApiCompanies.sure("gsk_abcdefghijklmnopqrstuvwxyz")?.id)
        assertEquals("gemini", ApiCompanies.sure("AIzaSyAbcdefghijklmnopqrstuvwxyz")?.id)
        assertEquals("openai", ApiCompanies.sure("sk-proj-abcdefghijklmnopqrstuvwxyz")?.id)
        // "sk-" अकेला = OpenAI पहले, फिर DeepSeek / Kimi / Qwen (फ़ोन /models से जाँचता है)
        val sk = ApiCompanies.candidates("sk-abcdefghijklmnopqrstuvwxyz0123").map { it.id }
        assertEquals("openai", sk.first()); assertTrue("deepseek" in sk)
        assertNull(ApiCompanies.sure("sk-abcdefghijklmnopqrstuvwxyz0123"))
        assertTrue(ApiCompanies.candidates("short").isEmpty())
        assertEquals("zhipu", ApiCompanies.candidates("abcdef0123456789abcdef.ABCDEFGHIJ").first().id)
        assertTrue(ApiCompanies.ALL.all { it.base.startsWith("https://") && it.models.isNotEmpty() })
        assertEquals("anthropic", ApiCompanies.byBase("https://api.anthropic.com/v1/")?.id)
        assertTrue(ApiCompanies.label(ApiCompanies.byId("openai")).contains("OpenAI"))
    }

    @Test fun extraApi_noBaseUrlField_companyAndModelPicker() {
        val s = src("SettingsActivity.kt")
        assertFalse(s.contains("field(box, \"Base URL\""))
        assertTrue(s.contains("ModelLister.detectCompany(k)"))
        assertTrue(s.contains("button(\"कंपनी बदलें\")"))
        assertTrue(s.contains("menuRow(\"brain_extra\""))
        assertFalse(Providers.byId("custom")!!.name.contains("Custom API"))
        assertTrue(Providers.byId("custom")!!.isCustom)
    }

    @Test fun computer_fallbackChain_andCleanScreen() {
        assertEquals(listOf("gemini", "openrouter", "custom", "groq"), CoderAi.PC_CHAIN)
        // खोज: Gemini → Tavily → OpenRouter → Exa
        assertEquals(listOf("gemini", "tavily", "openrouter", "exa"), ComputerTools.SEARCH_CHAIN)
        assertEquals(listOf("gemini", "tavily", "openrouter", "exa"), ComputerTools.searchChain("auto", true, true, true, true))
        assertEquals(listOf("tavily", "exa"), ComputerTools.searchChain("auto", false, true, false, true))
        assertEquals(listOf("exa", "gemini", "openrouter"), ComputerTools.searchChain("exa", true, false, true, true))
        assertTrue(ComputerTools.searchChain("auto", false, false, false, false).isEmpty())
        val or = ComputerTools.parseOpenRouter("""{"choices":[{"message":{"content":"जवाब","annotations":[{"type":"url_citation","url_citation":{"url":"https://a.com","title":"A","content":"x"}}]}}]}""")
        assertEquals("OpenRouter", or.via); assertEquals("https://a.com", or.hits.single().url); assertEquals("जवाब", or.summary)
        assertEquals("Groq (openai/gpt-oss-120b)", ComputerVoice.switchedTo("अपने आप Groq (openai/gpt-oss-120b) पर स्विच किया, काम बिना रुके जारी रहा"))
        assertNull(ComputerVoice.switchedTo("अपने-आप दोबारा कोशिश की (2/3) — सफल"))
        val f = ComputerVoice.failShort("HTTP 429: quota exceeded for model gemini-x")
        assertFalse(f.contains("429")); assertFalse(f.contains("gemini-x"))
        assertTrue(ComputerVoice.failShort("AI key नहीं मिली").contains("Settings"))
        val a = src("ComputerAgent.kt")
        assertTrue(a.contains("add(\"d\", out.text)"))
        assertFalse(a.contains("add(\"d\", out.text + \"\\n\\n\" + out.report)"))
        assertFalse(a.contains("add(\"e\", \"गलती: \$m"))
        assertFalse(src("ComputerActivity.kt").contains("DKStatus.fresh(busySince)"))
    }
}
