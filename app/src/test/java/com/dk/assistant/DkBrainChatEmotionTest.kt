package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 14 नवंबर 2026 (मालिक): चैट "जवाब नहीं आ पाया / मॉडल ने सवाल नहीं लिया" पर अटकता था; हर हिस्से के अंदर मॉडल चुनना;
 * वेबसाइट कोडिंग एजेंट की key से; DK लाइव Whisper से सटीक सुने; DK के दिल में भावनाएँ; key डालते ही हरा ✓ + आवाज़;
 * लाइट थीम भी रंगीन; अपडेट सिर्फ़ ऊपर.
 */
class DkBrainChatEmotionTest {
    private fun src(path: String) = listOf(File("src/main/java/com/dk/assistant/$path"), File("app/src/main/java/com/dk/assistant/$path")).first { it.exists() }.readText()

    @Test fun chatNeverUsesWhisperOrTtsModels() {
        assertFalse(ApiScopes.chatCapable("whisper-large-v3"))
        assertFalse(ApiScopes.chatCapable("playai-tts"))
        assertFalse(ApiScopes.chatCapable("canopylabs/orpheus-v1-english"))
        assertFalse(ApiScopes.chatCapable("compound-beta"))
        assertFalse(ApiScopes.chatCapable(""))
        assertTrue(ApiScopes.chatCapable("llama-3.3-70b-versatile"))
        assertEquals("llama-3.1-8b-instant", ApiScopes.chatModel("whisper-large-v3-turbo", "llama-3.1-8b-instant"))
        assertEquals("openai/gpt-oss-120b", ApiScopes.chatModel(" openai/gpt-oss-120b ", "x"))
        assertTrue(src("BrainPin.kt").contains("ApiScopes.chatModel(Prefs.scopeModel(c, ApiScopes.CHAT, p.id).takeIf { ApiScopes.chatCapable(it) }"))
    }

    @Test fun chatFallsThroughOnAny400AndPinHasSpares() {
        val a = src("AiClients.kt")
        assertEquals(2, Regex("""\(e\.code == 400 && !\(e\.message \?: ""\)\.contains\("API key", (ignoreCase = )?true\)\)""").findAll(a).count())
        val b = src("BrainManager.kt")
        assertTrue(b.contains("private fun chatSpares(): List<Pair<Provider, AiClient>>"))
        assertTrue(b.contains("(rankedForChat().mapNotNull { p -> clientFor(p)?.let { p to it } } + chatSpares())"))
        assertTrue(b.contains("सेटिंग्स → AI दिमाग → DK चैट में दूसरा मॉडल चुनें"))
    }

    // 21 नवंबर 2026 (मालिक: Whisper पूरी तरह हटा): वही नियम अब LiveRecover.stuckText में - सुना वाक्य, वरना caption, DK की गूँज कभी नहीं
    @Test fun heardPicksAccurateWordsButNeverDkEcho() {
        assertEquals("मेरा अलार्म लगा दो", LiveRecover.stuckText(null, "मेरा अलार्म लगा दो"))
        assertEquals("मेरा अलार्म लगा दो", LiveRecover.stuckText("  ", "मेरा अलार्म लगा दो"))
        assertEquals("मेरा अलार्म सुबह छह बजे लगा दो", LiveRecover.stuckText("मेरा अलार्म सुबह छह बजे लगा दो.", "मेरा अलार्म सुबह बजे"))
        assertEquals("मेरा अलार्म लगा दो", LiveRecover.stuckText("आज मौसम बहुत अच्छा है सर", "मेरा अलार्म लगा दो", dkSaid = "आज मौसम बहुत अच्छा है सर"))
        val t = src("GeminiLiveTurnEnd.kt")
        assertFalse(t.contains("LiveWhisper"))
        assertTrue(t.indexOf("runLockNow(PhoneLockVoice.quick(act), b, \"बोलना खत्म\")") < t.indexOf("val q = LiveFast.pick(b"))
    }

    @Test fun dkHasAHeart() {
        val h = DkPersona.HEART_RULES
        for (w in listOf("{sir}")) assertTrue(h.contains(w))
        val p = src("DkPersona.kt")
        assertTrue(p.contains("append(HEART_RULES.replace(\"{sir}\", sir))"))
        assertTrue(h.length in 200..2500)
    }

    @Test fun keyTickKnowsTheCompanyAndIsWired() {
        assertEquals("gemini", KeyTick.provider("AIzaSyA1234567890abcdefghijklmnopqrstu"))
        assertEquals("groq", KeyTick.provider("gsk_1234567890abcdefghijklmnopqrstuvwxyz"))
        assertNull(KeyTick.provider("short"))
        assertNull(KeyTick.provider("hello there this is not a key at all"))
        assertTrue(KeyTick.OK_TEXT.startsWith("✓"))
        assertTrue(src("SettingsWidgets.kt").contains("if (secret) keyTick(box, e)"))
        val k = src("KeyTick.kt")
        assertTrue(k.contains("PhoneVoice.say"))
        assertTrue(k.contains("KeyTick.SHOW_MS"))
    }

    /**
     * 15 नवंबर 2026 (मालिक का फ़ैसला बदला: "light mode के pastel gradient और गुलाबी-नीले glass कार्ड हटाओ,
     * light में dark वाला ही design - हर कार्ड solid"). पहले यह test light के रंगीन (pastel) कार्ड माँगता था;
     * अब जाँच यह कि pastel/रंगीन कार्ड का code ही न बचे और light कार्ड ठोस सफ़ेद हो.
     */
    @Test fun lightThemeIsSameDesignSolidCards() {
        val pl = src("PlainLook.kt")
        assertFalse(pl.contains("fun pastel("))
        assertFalse(pl.contains("LIGHT_TINTS ="))
        assertFalse(pl.contains("fun colorCard("))
        assertFalse(pl.contains("colorCard(tintFor(v)"))
        assertEquals(0xFFFFFFFF.toInt(), PlainLook.L_CARD)
        val a = src("SettingsActivity.kt")
        assertFalse(a.contains("PlainLook.colorCard("))
        assertFalse(a.contains("PlainLook.deep(menuTint(icon))"))
    }

    @Test fun brainMenuHasNoTopPinAndOneKeyHidesParts() {
        val b = src("SettingsBrain.kt")
        val menu = b.substring(b.indexOf("fun SettingsActivity.secBrainMenu()"), b.indexOf("fun SettingsActivity.secBrainLive()"))
        assertFalse(menu.contains("हर हिस्से की अपनी API"))
        assertFalse(menu.contains("अलग-अलग key वाले हिस्से — बंद"))
        assertTrue(menu.indexOf("if (one) return") < menu.indexOf("menuRow(\"brain_live\""))
    }
}
