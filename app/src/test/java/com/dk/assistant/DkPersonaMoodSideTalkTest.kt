package com.dk.assistant

import com.dk.assistant.avatar.Mood
import com.dk.assistant.voice.engine.HumanVoice
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (मालिक): मूड समझकर बात, स्वभाव चुनना (डिफ़ॉल्ट दोस्ताना), Piper में साँस/ठहराव,
 * बॉस बनाम कोई और, बगल की बातचीत = सुनेगी पर जवाब नहीं.
 */
class DkPersonaMoodSideTalkTest {

    // ------------------------------------------------------------------ स्वभाव
    @Test fun persona_defaultIsFriendly() {
        assertEquals(DkPersona.P.FRIENDLY, DkPersona.DEFAULT)
        assertEquals(DkPersona.P.FRIENDLY, DkPersona.of(null))
        assertEquals(DkPersona.P.FRIENDLY, DkPersona.of("कुछ भी"))
        assertEquals(DkPersona.P.NATKHAT, DkPersona.of("natkhat"))
        assertEquals(5, DkPersona.P.values().size)
    }

    @Test fun persona_parseVoiceCommands() {
        assertEquals(DkPersona.P.NATKHAT, DkPersona.parse("नटखट मोड ऑन करो"))
        assertEquals(DkPersona.P.NATKHAT, DkPersona.parse("डीके शरारती मोड चालू करो"))
        assertEquals(DkPersona.P.GIRLFRIEND, DkPersona.parse("गर्लफ्रेंड मोड ऑन करो"))
        assertEquals(DkPersona.P.PRO, DkPersona.parse("प्रोफ़ेशनल मोड चालू करो"))
        assertEquals(DkPersona.P.PRO, DkPersona.parse("professional mode on karo"))
        assertEquals(DkPersona.P.FRIENDLY, DkPersona.parse("नॉर्मल मोड ऑन करो"))
        assertEquals(DkPersona.P.FRIENDLY, DkPersona.parse("दोस्ताना स्वभाव रखो"))
        assertEquals(DkPersona.P.CARING, DkPersona.parse("शांत मोड ऑन करो"))
        // दूसरी बातें नहीं
        assertNull(DkPersona.parse("डार्क मोड ऑन करो"))
        assertNull(DkPersona.parse("साइलेंट मोड ऑन करो"))
        assertNull(DkPersona.parse("नटखट मोड मत करो"))
        assertNull(DkPersona.parse("नटखट का मतलब क्या होता है"))
        assertNull(DkPersona.parse("दोस्त को मैसेज भेजो"))
        // JARVIS / DK मोड JarvisMode का - वह पहले चलता है और यह उसे नहीं छूता
        assertEquals(true, JarvisMode.parse("जार्विस मोड ऑन करो"))
        assertNull(DkPersona.parse("जार्विस मोड ऑन करो"))
        assertNull(DkPersona.parse("डीके मोड ऑन करो"))
    }

    @Test fun persona_quick() {
        val q = DkPersona.quick(DkPersona.P.NATKHAT)
        assertEquals(DkPersona.TYPE, q.action.optString("type"))
        assertEquals("natkhat", q.action.optString("persona"))
    }

    @Test fun persona_pickAndBlocks() {
        val gf = GirlfriendPersona.block("राज")
        assertEquals(gf, DkPersona.pickFor(DkPersona.P.GIRLFRIEND, gf))
        for (p in DkPersona.P.values().filter { it != DkPersona.P.GIRLFRIEND }) {
            val b = DkPersona.pickFor(p, gf)
            assertFalse(p.name, b == gf)
            assertTrue(p.name, b.contains("feminine Hindi"))
            assertTrue(p.name, b.contains(p.title))
        }
        assertTrue(DkPersona.block(DkPersona.P.FRIENDLY).contains("NO romance"))
        assertTrue(DkPersona.block(DkPersona.P.NATKHAT).contains("never insulting"))
        assertTrue(DkPersona.block(DkPersona.P.PRO).contains("no teasing"))
    }

    @Test fun persona_finalBlockHasMoodBreathAndOverride() {
        val f = DkPersona.finalFor(DkPersona.P.FRIENDLY, "राज")
        assertTrue(f.contains("MOOD FIRST"))
        assertTrue(f.contains("drop ALL teasing"))
        assertTrue(f.contains("Happy or excited"))
        assertTrue(f.contains("soft breath"))
        assertTrue(f.contains("IGNORE every earlier girlfriend"))
        assertTrue(f.contains("राज"))
        val g = DkPersona.finalFor(DkPersona.P.GIRLFRIEND)
        assertFalse(g.contains("IGNORE every earlier girlfriend"))
        assertTrue(g.contains("MOOD FIRST"))
    }

    @Test fun persona_ttsHint() {
        val t = DkPersona.ttsFor(DkPersona.P.NATKHAT, "base")
        assertTrue(t.startsWith("base; "))
        assertTrue(t.contains("playful"))
        assertTrue(t.contains("natural breaths"))
        assertTrue(DkPersona.ttsFor(DkPersona.P.PRO, "").contains("professional"))
    }

    // ------------------------------------------------------------------ Piper: इंसान जैसी आवाज़
    @Test fun human_moodTune() {
        assertTrue(HumanVoice.tune(Mood.SAD).speedMul < 1f)
        assertTrue(HumanVoice.tune(Mood.SAD).gapPct > 100)
        assertTrue(HumanVoice.tune(Mood.HAPPY).speedMul > 1f)
        assertEquals(HumanVoice.Tune(1f, 1f, 100), HumanVoice.tune(Mood.NORMAL))
        assertEquals(560, HumanVoice.gap(400, Mood.SAD))
        assertEquals(400, HumanVoice.gap(400, Mood.NORMAL))
    }

    @Test fun human_jitterSmallAndStable() {
        for (s in listOf("नमस्ते", "आप कैसे हैं?", "आज मौसम अच्छा है", "")) {
            val j = HumanVoice.jitter(s)
            assertTrue(j in 0.97f..1.03f)
            assertEquals(j, HumanVoice.jitter(s))
        }
        assertTrue(HumanVoice.questionLift("कैसे हैं?") > 1f)
        assertEquals(1f, HumanVoice.questionLift("ठीक है।"))
    }

    @Test fun human_breathOnlyInLongReplies() {
        val b = HumanVoice.Breather()
        val long = "आज मैंने आपके लिए मौसम देखा और बाहर हल्की बारिश हो रही है तो छाता लेकर जाइए"
        assertFalse(b.next(long, 1_000))                  // पहला वाक्य - साँस नहीं
        assertFalse(b.next(long, 1_300))                  // अभी 110 अक्षर नहीं हुए
        assertFalse(b.next("हाँ", 1_500))                  // छोटा वाक्य - नहीं
        assertTrue(b.next(long, 2_000))                    // काफ़ी बोल चुकी + लंबा वाक्य = साँस
        assertFalse(b.next(long, 2_500))                   // अभी-अभी ली
        assertFalse(b.next(long, 20_000))                  // नई बात - गिनती फिर से
    }

    @Test fun human_breathPcmIsQuietAndFaded() {
        val p = HumanVoice.breath(220, 3)
        assertEquals(HumanVoice.RATE * 220 / 1000 * 2, p.size)
        fun s(i: Int) = ((p[2 * i + 1].toInt() shl 8) or (p[2 * i].toInt() and 0xff)).toShort().toInt()
        val n = p.size / 2
        var max = 0
        for (i in 0 until n) max = maxOf(max, kotlin.math.abs(s(i)))
        assertTrue("बहुत ज़ोर की साँस: $max", max < 32767 * 0.2)
        assertTrue("साँस सुनाई ही न दे: $max", max > 50)
        assertTrue(kotlin.math.abs(s(0)) < 10 && kotlin.math.abs(s(n - 1)) < 10)
        val ms = HumanVoice.breathMs("कुछ भी")
        assertTrue(ms in 180..260)
    }

    // ------------------------------------------------------------------ बगल की बातचीत
    @Test fun side_parse() {
        assertEquals(true, SideTalk.parse("डीके मैं किसी से बात कर रहा हूँ"))
        assertEquals(true, SideTalk.parse("थोड़ी देर चुप रहो"))
        assertEquals(true, SideTalk.parse("DK थोड़ा रुको, सुनती रहो"))
        assertEquals(false, SideTalk.parse("DK वापस आओ"))
        assertEquals(false, SideTalk.parse("डीके अब बात करो"))
        assertNull(SideTalk.parse("मौसम कैसा है"))
        assertNull(SideTalk.parse("गाना चलाओ"))
    }

    @Test fun side_listenModeAndTimeout() {
        val s = SideTalk.State()
        assertFalse(s.listening(0))
        s.start(1_000)
        assertTrue(s.listening(2_000))
        assertEquals("सुनने वाला मोड - DK का नाम नहीं", s.muteReason(2_000, "हाँ भाई कल मिलते हैं"))
        // मोड वाली बात ही (DK नाम के साथ) = मोड चलता रहे
        assertNull(s.muteReason(2_100, "डीके मैं किसी से बात कर रहा हूँ"))
        assertTrue(s.listening(2_200))
        // नाम लिया = जवाब + मोड खत्म
        assertNull(s.muteReason(3_000, "डीके टाइम क्या हुआ"))
        assertFalse(s.listening(3_100))
        // 3 मिनट शांति = खत्म
        s.start(10_000); s.touch(60_000)
        assertTrue(s.listening(60_000 + SideTalk.QUIET_MS - 1))
        assertFalse(s.listening(60_000 + SideTalk.QUIET_MS + 1))
        // बात चलती रहे तब भी 30 मिनट से ज़्यादा नहीं
        s.start(0)
        var t = 0L
        while (t < SideTalk.MAX_MS) { t += 60_000; s.touch(t) }
        assertFalse(s.listening(t + 1))
    }

    @Test fun side_addressed() {
        assertTrue(SideTalk.addressed("डीके, लाइट बंद करो"))
        assertTrue(SideTalk.addressed("अच्छा तो DK तुम बताओ"))
        assertTrue(SideTalk.addressed("hey dk what time"))
        assertTrue(SideTalk.addressed("ओ जानू सुनो", listOf("जानू")))
        assertFalse(SideTalk.addressed("कल ऑफिस जल्दी आना"))
        assertFalse(SideTalk.addressed("डीकेट वाला सामान"))
        assertFalse(SideTalk.addressed(""))
    }

    @Test fun side_classify() {
        val th = 0.33f
        assertEquals(SideTalk.Who.BOSS, SideTalk.classify(0.5f, th, 1500))
        assertEquals(SideTalk.Who.OTHER, SideTalk.classify(0.1f, th, 1500))
        assertEquals(SideTalk.Who.UNKNOWN, SideTalk.classify(0.28f, th, 1500))
        assertEquals(SideTalk.Who.UNKNOWN, SideTalk.classify(0.1f, th, 200))
        assertEquals(SideTalk.Who.UNKNOWN, SideTalk.classify(null, th, 1500))
    }

    @Test fun side_autoConversationMute() {
        val s = SideTalk.State()
        // बॉस DK से बात कर रहे
        var q = s.begin(1_000); s.voice(SideTalk.Who.BOSS, 2_000, q)
        assertNull(s.muteReason(2_100, "आज का मौसम"))
        // कोई और बोला (बॉस अभी बोले थे) = आपस की बात
        q = s.begin(3_000); s.voice(SideTalk.Who.OTHER, 4_000, q)
        assertEquals("कोई और आपसे बात कर रहा है", s.muteReason(4_100, "भैया चाय पियोगे"))
        // बॉस ने उसे जवाब दिया
        q = s.begin(5_000); s.voice(SideTalk.Who.BOSS, 6_000, q)
        assertEquals("आप किसी और को जवाब दे रहे हैं", s.muteReason(6_100, "हाँ पी लेंगे"))
        // बॉस ने नाम लेकर DK से कहा = जवाब
        assertNull(s.muteReason(6_200, "डीके दो चाय का ऑर्डर कर दो"))
        // पुरानी बात का देर से आया नतीजा नई बात पर नहीं लगता
        val old = s.begin(7_000); val cur = s.begin(7_500)
        s.voice(SideTalk.Who.OTHER, 7_600, old)
        assertEquals(SideTalk.Who.UNKNOWN, s.current(7_700))
        s.voice(SideTalk.Who.BOSS, 7_800, cur)
        assertEquals(SideTalk.Who.BOSS, s.current(7_900))
        // अकेला कोई और (बॉस बहुत पहले) = रोकना नहीं (मेहमान DK से छोटी बात पूछ सकते हैं)
        val t = SideTalk.State()
        val z = t.begin(100_000); t.voice(SideTalk.Who.OTHER, 101_000, z)
        assertNull(t.muteReason(101_100, "मौसम कैसा है"))
    }

    @Test fun side_guardBlocksBigTasksForOthers() {
        val other = SideTalk.Who.OTHER
        assertNotNull(SideTalk.blockFor("notification_reply", JSONObject(), other))
        assertNotNull(SideTalk.blockFor("phone_call", JSONObject().put("action", "call"), other))
        assertNotNull(SideTalk.blockFor("dk_settings", JSONObject(), other))
        assertNotNull(SideTalk.blockFor("whatsapp_task", JSONObject().put("action", "send_message"), other))
        assertNotNull(SideTalk.blockFor("phone_task", JSONObject().put("request", "राहुल को 500 रुपये पेमेंट करो"), other))
        assertNotNull(SideTalk.blockFor("phone_task", JSONObject().put("request", "सारी फोटो डिलीट करो"), other))
        assertNull(SideTalk.blockFor("phone_task", JSONObject().put("request", "टॉर्च जलाओ"), other))
        assertNull(SideTalk.blockFor("web_search", JSONObject(), other))
        // बॉस / पता नहीं = सब चलता है
        assertNull(SideTalk.blockFor("notification_reply", JSONObject(), SideTalk.Who.BOSS))
        assertNull(SideTalk.blockFor("phone_task", JSONObject().put("request", "पेमेंट करो"), SideTalk.Who.UNKNOWN))
    }

    @Test fun side_quickAndPrompt() {
        assertEquals(SideTalk.TYPE, SideTalk.quick(true).action.optString("type"))
        assertEquals(true, SideTalk.quick(true).action.optBoolean("on"))
        assertTrue(SideTalk.PROMPT.contains("stay completely SILENT"))
    }

    // ------------------------------------------------------------------ wiring
    @Test fun wiring() {
        val bm = src("BrainManager.kt")
        assertEquals(3, Regex(Regex.escape("DkPersona.pick(ctx, GirlfriendPersona.block(")).findAll(bm).count())
        assertEquals(3, Regex(Regex.escape(".append(DkPersona.finalBlock(ctx, Prefs.userName(ctx)))")).findAll(bm).count())
        assertTrue(bm.contains("if (SideTalk.on(ctx)) append(SideTalk.PROMPT)"))
        val lr = src("LocalRouter.kt")
        assertTrue(lr.indexOf("JarvisMode.parse(raw)") < lr.indexOf("DkPersona.parse(raw)"))
        assertTrue(lr.contains("SideTalk.parse(raw)"))
        val acc = src("DKAccessibilityService.kt")
        assertTrue(acc.contains("DkPersona.TYPE ->") && acc.contains("SideTalk.TYPE ->"))
        assertTrue(src("LiveFast.kt").contains("DkPersona.TYPE, SideTalk.TYPE"))
        val srv = src("GeminiLiveServer.kt")
        assertEquals(3, Regex("sideTalkCheck\\(\\)").findAll(srv).count())
        assertTrue(src("GeminiLiveTools.kt").contains("SideTalk.blockFor(name, args, sideWho())"))
        assertTrue(src("GeminiLiveMic.kt").contains("whoCheckAsync(cmdBuf.toByteArray(), now, whoSeq)"))
        assertTrue(src("voice/engine/PiperTtsEngine.kt").contains("HumanVoice.breath("))
        assertTrue(src("voice/engine/GeminiTtsEngine.kt").contains("DkPersona.ttsStyle(c, "))
        assertTrue(src("SettingsVoice.kt").contains("DK का स्वभाव"))
    }

    private fun src(name: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            listOf(File(dir, "src/main/java/com/dk/assistant/$name"), File(dir, "app/src/main/java/com/dk/assistant/$name"))
                .firstOrNull { it.exists() }?.let { return it.readText() }
            dir = dir.parentFile
        }
        error("$name नहीं मिला")
    }
}
