package com.dk.assistant

import com.dk.assistant.voice.engine.VoiceTune
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

/** v3.80: लाइव जानकारी (कई स्रोत, सरकारी पहले, स्रोत बताना), सही समय, फ़िक्र, गति/पिच, Live में आवाज़/मॉडल तुरंत */
class LiveInfoTest {
    private val ist = TimeZone.getTimeZone("Asia/Kolkata")
    /** 27 अक्टूबर 2026, मंगलवार, 23:42 IST */
    private val lateNight = 1793124720000L

    private fun src(name: String): String = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()
    private fun worker(): String = listOf(File("../worker/dk-brain/src/index.js"), File("worker/dk-brain/src/index.js"))
        .first { it.exists() }.readText()

    // ------------------------------------------------------------------ समय
    @Test fun clock_lineIsExactHindiAndEnglish() {
        val l = NowClock.line(lateNight, ist)
        assertTrue(l, l.startsWith("CURRENT DATE AND TIME (exact, Asia/Kolkata, UTC+05:30): Tuesday, 27 October 2026, 23:42 (night)"))
        assertTrue(l, l.contains("मंगलवार, 27 अक्टूबर 2026, रात 11:42 बजे"))
        assertEquals("रात 11:42 बजे", NowClock.spoken(lateNight, ist))
        assertEquals("दोपहर 12:05 बजे", NowClock.spoken(lateNight + (12 * 60 + 23) * 60_000L, ist))   // अगले दिन 12:05
        assertTrue(NowClock.toolAnswer(lateNight, ist).contains("exactly as given"))
    }

    @Test fun clock_partOfDay() {
        assertEquals("देर रात", NowClock.partOfDay(1))
        assertEquals("सुबह-सुबह", NowClock.partOfDay(5))
        assertEquals("सुबह", NowClock.partOfDay(9))
        assertEquals("दोपहर", NowClock.partOfDay(13))
        assertEquals("शाम", NowClock.partOfDay(18))
        assertEquals("रात", NowClock.partOfDay(22))
    }

    @Test fun clock_internetOffset() {
        // फ़ोन 90 सेकंड पीछे, जवाब 200ms में
        assertEquals(90_000L, NowClock.offsetFrom(1_000_000L, 1_000_200L, 1_090_100L))
        // छोटा फ़र्क = फ़ोन ठीक
        assertEquals(0L, NowClock.offsetFrom(1_000_000L, 1_000_200L, 1_000_900L))
        // बहुत देर से आया जवाब / ग़लत = भरोसा नहीं
        assertNull(NowClock.offsetFrom(1_000_000L, 1_009_000L, 1_090_000L))
        assertNull(NowClock.offsetFrom(1_000_200L, 1_000_000L, 1_090_000L))
        assertEquals(1729999999123L, NowClock.parseTrace("fl=1\nh=cloudflare.com\nts=1729999999.123\nvisit_scheme=https"))
        assertEquals(1729999999000L, NowClock.parseTrace("ts=1729999999"))
        assertNull(NowClock.parseTrace("no time here"))
        assertEquals(784111777000L, NowClock.parseHttpDate("Sun, 06 Nov 1994 08:49:37 GMT"))
    }

    @Test fun care_followsTimeOfDay() {
        val night = CareTime.block(lateNight, ist)
        assertTrue(night.contains("रात 11:42 बजे"))
        assertTrue(night.contains("rest"))
        assertTrue(night.contains("at most ONE caring touch"))
        assertTrue(night.contains("never say good morning at night"))
        assertTrue(CareTime.hintFor(13).contains("lunch"))
        assertTrue(CareTime.hintFor(8).contains("morning"))
        assertTrue(CareTime.hintFor(2).contains("past midnight"))
    }

    @Test fun prompts_useExactClockAndCare() {
        val bm = src("BrainManager.kt")
        assertFalse("पुरानी अंग्रेज़ी 'Today:' वाली तारीख़ नहीं", bm.contains("\"\\nToday: \""))
        assertEquals(3, Regex("CareTime\\.block\\(\\)").findAll(bm).count())
        assertTrue(bm.contains("call current_time"))
        val live = src("GeminiLive.kt")
        assertTrue(live.contains("\"current_time\" -> { NowClock.syncAsync(); NowClock.toolAnswer() }"))
        assertTrue(live.contains("put(\"name\", \"current_time\")"))
    }

    // ------------------------------------------------------------------ लाइव खोज
    @Test fun search_governmentQuestionGoesToOfficialSitesFirst() {
        val p = LiveSearch.plan("लाडकी बहीण योजना की नई किस्त कब आएगी")
        assertEquals("official", p.first().id)
        assertTrue(p.first().instruction.contains("gov.in"))
        assertTrue(p.size >= 2)
        assertTrue(LiveSearch.isGov("PM Kisan की 21वीं किस्त"))
        assertTrue(LiveSearch.isGov("MPSC result kab aayega"))
    }

    @Test fun search_normalQuestionUsesWebAndNews() {
        val p = LiveSearch.plan("आज सोने का भाव क्या है")
        assertEquals(listOf("web", "news"), p.map { it.id })
        assertFalse(LiveSearch.isGov("आज सोने का भाव क्या है"))
        val sys = LiveSearch.angleSystem(p[0], NowClock.line(lateNight, ist), "DK")
        assertTrue(sys.contains("ALWAYS use Google Search"))
        assertTrue(sys.contains("website name and the date"))
        assertTrue(sys.contains("27 October 2026"))
    }

    @Test fun search_mergeCrossChecksAndStaysHonest() {
        val m = LiveSearch.mergeSystem("NOW", "DK", "राज")
        assertTrue(m.contains("official/government > reputed news"))
        assertTrue(m.contains("If reports disagree"))
        assertTrue(m.contains("Never invent"))
        assertTrue(m.contains("how fresh"))
        val inp = LiveSearch.mergeInput("सवाल", listOf("web" to "A", "news" to "B"))
        assertTrue(inp.contains("REPORT 1 (web search):\nA"))
        assertTrue(inp.contains("REPORT 2 (news search):\nB"))
        assertTrue(LiveSearch.empty("NOTHING RELIABLE FOUND"))
        assertFalse(LiveSearch.empty("सोना 1,20,000 रुपये (ibja.co, 27 Oct 2026)"))
    }

    @Test fun search_sourcesNamedOnlyWhenAsked() {
        val redirect = "https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc"
        val srcs = LiveSearch.mergeSources(listOf(
            listOf("pib.gov.in" to redirect + "1", "ndtv.com" to redirect + "2"),
            listOf("ndtv.com" to redirect + "3", "Reuters" to "https://www.reuters.com/x")))
        assertEquals(listOf("pib.gov.in", "ndtv.com", "reuters.com"), srcs.map { LiveSearch.site(it.first, it.second) })
        val line = LiveSearch.sourcesLine(srcs)
        assertTrue(line.contains("pib.gov.in, ndtv.com, reuters.com"))
        assertTrue(line.contains("if the user asks where this information came from"))
        assertEquals("", LiveSearch.sourcesLine(emptyList()))

        LiveSearch.remember("सवाल", srcs, at = 1_000L)
        assertTrue(LiveSearch.memoryBlock(at = 2_000L).contains("pib.gov.in"))
        assertEquals("", LiveSearch.memoryBlock(at = 1_000L + 31 * 60_000L))
        assertTrue(LiveSearch.sourceReply(at = 2_000L)!!.contains("ndtv.com"))
        assertTrue(LiveSearch.asksSource("यह जानकारी कहाँ से ली"))
        assertTrue(LiveSearch.asksSource("कौन सी वेबसाइट से पता किया"))
        assertFalse(LiveSearch.asksSource("आज मौसम कैसा है"))
    }

    @Test fun search_policyAlwaysSearchesChangingThings() {
        val r = SearchPolicy.rules("web_search")
        assertTrue(r.contains("ALWAYS use web_search"))
        assertTrue(r.contains("government schemes"))
        assertTrue(r.contains("official government websites"))
        assertTrue(r.contains("never invent a source"))
        listOf("PM किसान योजना की किस्त", "रेलवे भर्ती का एडमिट कार्ड", "आईपीएल में कल कौन जीता", "नया iPhone कब लॉन्च होगा")
            .forEach { assertTrue(it, SearchPolicy.needsSearch(it)) }
        // बिना बदलने वाली बात पर अब भी खोज नहीं
        listOf("भारत की राजधानी क्या है", "न्यूटन का दूसरा नियम क्या है").forEach { assertFalse(it, SearchPolicy.needsSearch(it)) }
        assertTrue(src("BrainManager.kt").contains("liveSearch(question) else searchOnce("))
    }

    // ------------------------------------------------------------------ "सुनकर चुप" नहीं + सोच दिखे
    @Test fun live_remindsToAnswerAfterToolAndShowsThinking() {
        val live = src("GeminiLive.kt")
        assertTrue(live.contains("private fun watchToolAnswer("))
        assertTrue(live.contains("else if (name in ANSWER_TOOLS) watchToolAnswer(name, result, cur)"))
        assertTrue(live.contains("setMode(if (toolJobs.isNotEmpty()) Mode.THINKING else Mode.LISTENING)"))
        assertTrue(live.contains("if (!turnActive && DKState.mode == Mode.SPEAKING) idleMode()"))
    }

    // ------------------------------------------------------------------ आवाज़: तुरंत बदले, मॉडल दिखे/बदले, गति-पिच
    @Test fun live_voiceAndModelChangeApplyDuringCall() {
        val live = src("GeminiLive.kt")
        assertTrue(live.contains("connLiveVer != com.dk.assistant.voice.engine.VoiceEngines.liveVersion"))
        assertTrue(live.contains("pk.liveVer == connLiveVer"))
        assertTrue(live.contains("LiveModelPick.order(LiveModelPick.get(svc), discoverModels(key))"))
        val native = src("voice/engine/LiveNativeEngine.kt")
        assertTrue(native.contains("VoiceEngines.liveChanged()"))
        assertFalse(native.contains("अगली बार जुड़ने पर लगेगा"))
    }

    @Test fun modelPick_putsChosenFirst() {
        assertEquals(listOf("a", "b", "c"), LiveModelPick.order("", listOf("a", "b", "c")))
        assertEquals(listOf("b", "a", "c"), LiveModelPick.order("b", listOf("a", "b", "c")))
        assertEquals(listOf("x", "a", "b"), LiveModelPick.order("x", listOf("a", "b")))
    }

    @Test fun tune_speedAndPitch() {
        assertEquals(70, VoiceTune.clampSpeed(10)); assertEquals(130, VoiceTune.clampSpeed(400))
        assertEquals(80, VoiceTune.clampPitch(0)); assertEquals(120, VoiceTune.clampPitch(999))
        // 1 सेकंड की 24 kHz आवाज़: सामान्य 1000ms, 125% गति पर 800ms, 80% पर 1250ms
        assertEquals(1000L, VoiceTune.playMs(48_000, 24_000, 100))
        assertEquals(800L, VoiceTune.playMs(48_000, 24_000, 125))
        assertEquals(1250L, VoiceTune.playMs(48_000, 24_000, 80))
        assertEquals("गति 95% • पिच 102%", VoiceTune.label(95, 102))
        assertTrue(src("voice/engine/SpeechPipeline.kt").contains("VoiceTune.apply(tr, tuneVer)"))
        assertTrue(src("GeminiLive.kt").contains("VoiceTune.apply(tr, tuneVer)"))
        val card = src("voice/engine/VoiceEngineCard.kt")
        assertTrue(card.contains("अभी कौन बोल रहा है"))
        assertTrue(card.contains("DK लाइव का मॉडल"))
        assertTrue(card.contains("Gemini TTS का मॉडल"))
        assertTrue(card.contains("VoiceTune.setSpeed(a, v)"))
        assertTrue(card.contains("VoiceTune.setPitch(a, v)"))
        // Edge के अलग गति/पिच स्लाइडर नहीं (एक ही जगह)
        assertFalse(src("voice/engine/EdgeTtsEngine.kt").contains("putInt(\"edge_rate\""))
    }

    @Test fun voice_softerAndHumanLike() {
        assertTrue(CalmPersona.VOICE_STYLE.contains("never robotic"))
        assertTrue(CalmPersona.block().contains("never like a robot"))
        val tts = src("voice/engine/GeminiTtsEngine.kt")
        assertTrue(tts.contains("const val DEFAULT_VOICE = \"Achernar\""))
        assertTrue(tts.contains("put(\"model\", it)"))
        assertTrue(src("GeminiLive.kt").contains("LiveNativeEngine.style(svc).ifBlank { CalmPersona.VOICE_STYLE }"))
        val w = worker()
        assertTrue(w.contains("if (want && list.includes(want))"))
        assertTrue(w.contains("slice(0, 600)"))
    }
}
