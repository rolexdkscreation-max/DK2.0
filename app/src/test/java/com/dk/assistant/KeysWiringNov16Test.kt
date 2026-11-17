package com.dk.assistant

import com.dk.assistant.plus.FeatureFlags
import com.dk.assistant.plus.KeyPool
import com.dk.assistant.voice.engine.WordStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 16 नवंबर 2026 (मालिक): "सारी keys इस्तेमाल करो" स्विच सच में काम करे, हिंग्लिश उच्चारण और भावना/स्टाइल वाला कोड
 * बोलने से जुड़े, और हर API key का अपना कार्ड (ताला, सेव + टेस्ट, हरा ✓) - बैकअप keys समेत.
 */
class KeysWiringNov16Test {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    // ---------------- सारी keys इस्तेमाल करो
    @Test fun rotate_offKeepsOrder_onStartsFromNextKey() {
        val k = listOf("k1", "k2", "k3", "k4", "k5")
        assertEquals(k, KeyPool.rotate(k, false, 3))
        assertEquals(k, KeyPool.rotate(k, true, 0))
        assertEquals(listOf("k2", "k3", "k4", "k5", "k1"), KeyPool.rotate(k, true, 1))
        assertEquals(listOf("k5", "k1", "k2", "k3", "k4"), KeyPool.rotate(k, true, 4))
        assertEquals(k, KeyPool.rotate(k, true, 5))
        assertEquals(listOf("k3", "k4", "k5", "k1", "k2"), KeyPool.rotate(k, true, -3))
        // हर key बची रहती है (बचाव/fallback नहीं टूटता)
        for (t in 0..9) assertEquals(k.toSet(), KeyPool.rotate(k, true, t).toSet())
        assertEquals(listOf("only"), KeyPool.rotate(listOf("only"), true, 7))
        assertEquals(emptyList<String>(), KeyPool.rotate(emptyList(), true, 1))
    }

    @Test fun switchIsWiredIntoCoderKeys_andVisible() {
        assertTrue(src("Prefs.kt").contains("KeyPool.spread(c, \"coder\""))
        assertTrue(src("plus/KeyPool.kt").contains("FeatureFlags.on(c, FeatureFlags.KEY_POOL_ALL)"))
        assertTrue(FeatureFlags.ready(FeatureFlags.KEY_POOL_ALL))
        assertFalse(FeatureFlags.defaultOf(FeatureFlags.KEY_POOL_ALL))
        assertTrue(src("SettingsBrain.kt").contains("FeatureFlags.KEY_POOL_ALL"))
        assertEquals("key_pool_all", DkSettings.find("सारी keys इस्तेमाल करो")?.id)
    }

    // ---------------- हिंग्लिश उच्चारण + भावना/स्टाइल
    @Test fun phoneVoiceUsesHinglishAndWordStyle() {
        val pv = src("PhoneVoice.kt")
        assertTrue(pv.contains("HinglishText.toSpeakable(text)"))
        assertTrue(pv.contains("WordStyle.phone("))
        assertTrue(pv.contains("setSpeechRate(rate)") && pv.contains("setPitch(pitch)"))
        assertTrue(src("plus/PlusVoice.kt").contains("HinglishText.toSpeakable"))
        assertTrue(src("TeacherActivity.kt").contains("HinglishText.toSpeakable"))
    }

    @Test fun wordStylePhone_slidersTimesBox() {
        assertEquals(1f to 1f, WordStyle.phone(100, 100, ""))
        val (r, p) = WordStyle.phone(100, 100, "धीमी और कोमल आवाज़")
        assertTrue(r < 1f && p < 1f)
        val (r2, _) = WordStyle.phone(100, 100, "तेज़ और जोश में")
        assertTrue(r2 > 1f)
        assertEquals(2f, WordStyle.phone(200, 100, "तेज़ जल्दी जोश").first, 0.001f)
        assertEquals(0.5f, WordStyle.phone(50, 50, "धीमी भावुक").first, 0.001f)
        assertEquals(1.5f, WordStyle.phone(150, 100, "").first, 0.001f)
    }

    // ---------------- हर key का कार्ड
    @Test fun keyTitles_cardOnlyForRealKeys() {
        assertTrue(KeyTick.isKeyTitle("Gemini API Key 2 (बैकअप, ज़रूरी नहीं)"))
        assertTrue(KeyTick.isKeyTitle("GitHub Token"))
        assertTrue(KeyTick.isKeyTitle("मज़बूत मॉडल की API key"))
        assertFalse(KeyTick.isKeyTitle("App Password (सामान्य पासवर्ड नहीं)"))
        assertFalse(KeyTick.isKeyTitle("गुप्त शब्द (कम से कम 4 अक्षर)"))
        assertFalse(KeyTick.isKeyTitle("Repo (owner/naam)"))
    }

    @Test fun mask_neverShowsWholeKey() {
        assertEquals("", KeyTick.mask("  "))
        val fake = "AI" + "zaSyA1234567890abcdefghijklmnopqrstuvwxyz"   // टुकड़ों में: GitHub इसे असली key न समझे
        assertEquals("••••••••wxyz", KeyTick.mask(fake))
        assertEquals("•••••", KeyTick.mask("abcde"))
        assertFalse(KeyTick.mask("AIzaSyA1234567890abcdef").contains("AIza"))
    }

    @Test fun service_fromKeyOrTitle_andLinks() {
        assertEquals("gemini", KeyTick.service("API Key", "AIzaSyA1234567890abcdefghijklmnopqrstu"))
        assertEquals("groq", KeyTick.service("Groq API Key (सिर्फ़ सुनने के लिए)"))
        assertEquals("github", KeyTick.service("GitHub Token"))
        assertEquals("github", KeyTick.service("x", "ghp_abcdefghijklmnopqrstuvwxyz123456"))
        assertEquals("youtube", KeyTick.service("YouTube Data API key (मुफ़्त, वैकल्पिक)"))
        assertEquals("hf", KeyTick.service("Hugging Face key (मुफ़्त)"))
        assertEquals("exa", KeyTick.service("Exa API Key"))
        assertNull(KeyTick.service("Pollinations (FLUX) key"))
        assertEquals("https://aistudio.google.com/apikey", KeyTick.keyUrl("gemini"))
        assertEquals("", KeyTick.keyUrl(null))
        assertTrue(KeyTick.tip("gemini").contains("मुफ़्त"))
    }

    @Test fun verdictColors_greenTickForWorkingKey() {
        assertEquals(KeyTick.Verdict.OK, KeyTick.verdict(KeyTick.OK_TEXT))
        assertEquals(KeyTick.GREEN, KeyTick.color(KeyTick.verdict(KeyTick.fromCode(200))))
        assertEquals(KeyTick.Verdict.BAD, KeyTick.verdict(KeyTick.fromCode(401)))
        assertEquals(KeyTick.Verdict.WARN, KeyTick.verdict(KeyTick.fromCode(429)))
        assertEquals(KeyTick.Verdict.WARN, KeyTick.verdict(KeyTick.fromCode(0)))
        assertEquals(KeyTick.Verdict.WARN, KeyTick.verdict(KeyTick.TESTING))
        assertTrue(KeyTick.OK_TEXT.contains("सही है") && KeyTick.OK_TEXT.contains("चल रही है"))
    }

    @Test fun everyKeyFieldBecomesCard_withSaveAndTest() {
        val w = src("SettingsWidgets.kt")
        assertTrue(w.contains("card: Boolean = secret && KeyTick.isKeyTitle(title)"))
        assertTrue(w.contains("if (card) return keyCard("))
        val k = src("KeyTick.kt")
        for (s in listOf("R.drawable.ic_lock", "R.drawable.ic_bulb", "Key लें →", "KeyTick.SAVE_TEXT", "KeyTick.TEST_TEXT",
                "R.drawable.btn_accent", "R.drawable.key_outline_pill", "KeyTick.mask(k)", "KeyTick.remember(", "KeyTick.last("))
            assertTrue(s, k.contains(s))
        // बैकअप keys (DK लाइव 2-3, कोडिंग एजेंट 2-5) भी उसी field से = हर एक का अपना कार्ड और टेस्ट
        val b = src("SettingsBrain.kt")
        for (n in 2..5) assertTrue(b.contains("\"Gemini API Key $n (बैकअप, ज़रूरी नहीं)\""))
        // तुरंत सेटअप वाले खाने का अपना बटन है - वहाँ कार्ड नहीं
        assertTrue(src("SettingsActivity.kt").contains("secret = true, card = false"))
    }

    // ---------------- DK लाइव की keys पर भी (16 नवंबर 2026, दूसरा हिस्सा)
    @Test fun liveStartKey_rotatesOnlyWhenOn_andOnlyOwnKeys() {
        assertEquals(0, LiveKeys.startKey(false, 3, 5))
        assertEquals(0, LiveKeys.startKey(true, 1, 5))
        assertEquals(listOf(0, 1, 2, 0, 1), (0..4).map { LiveKeys.startKey(true, 3, it) })
        assertEquals(listOf(0, 1, 0), (0..2).map { LiveKeys.startKey(true, 2, it) })
    }

    @Test fun liveNext_wrapsFromStart_everyKeyOnce() {
        // start=0 = पुराना जैसा
        assertEquals(1, LiveKeys.next(0, 3, 0)); assertEquals(2, LiveKeys.next(1, 3, 0)); assertEquals(-1, LiveKeys.next(2, 3, 0))
        for (ki in 0..2) assertEquals(LiveKeys.next(ki, 3), LiveKeys.next(ki, 3, 0))
        // start=1: 1 -> 2 -> 0 -> खत्म
        assertEquals(2, LiveKeys.next(1, 3, 1)); assertEquals(0, LiveKeys.next(2, 3, 1)); assertEquals(-1, LiveKeys.next(0, 3, 1))
        // उधार की keys (size 5, अपनी 3) भी घूमकर सब मिलती हैं
        var ki = 2; val seen = mutableListOf(ki)
        while (true) { ki = LiveKeys.next(ki, 5, 2); if (ki < 0) break; seen += ki }
        assertEquals(listOf(2, 3, 4, 0, 1), seen)
    }

    @Test fun livePreferKey_keepsOrderInsideKey() {
        val q = listOf("a" to 0, "b" to 1, "c" to 0, "d" to 2, "e" to 1)
        assertEquals(listOf("b" to 1, "e" to 1, "a" to 0, "c" to 0, "d" to 2), LiveKeys.preferKey(q, 1) { it.second })
        assertEquals(q, LiveKeys.preferKey(q, -1) { it.second })
    }

    @Test fun liveIsWiredToSwitch() {
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("FeatureFlags.KEY_POOL_ALL") && g.contains("LiveKeys.startKey(") && g.contains("KeyPool.turn(\"live\")"))
        assertTrue(g.contains("LiveKeys.next(ki, keys.size, start)"))
        assertTrue(src("GeminiLiveRace.kt").contains("LiveKeys.preferKey(queue, liveStartKey)"))
        val b = src("SettingsBrain.kt")
        assertTrue(b.contains("हर नई बातचीत अगली key से\", com.dk.assistant.plus.FeatureFlags.KEY_POOL_ALL"))
    }
}
