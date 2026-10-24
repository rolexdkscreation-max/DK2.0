package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.59: Live की पक्की आवाज़ (Sulafat -> Aoede), hi-IN, affective dialog, और मालिक का "करीबी दोस्त" prompt */
class LiveVoiceTest {
    private val m38 = "gemini-3.8-live"
    private val m31 = "gemini-3.1-flash-live-preview"

    @Test fun firstRunIsSulafat() {
        assertEquals("Sulafat", LiveVoice.pick("", "female"))
        assertEquals("Sulafat", LiveVoice.pick(null, "female"))
        assertEquals("Sulafat", LiveVoice.pick("बेकार", "female"))
        assertTrue(LiveVoice.FALLBACK in VoiceCatalog.GEMINI_FEMALE)
        assertTrue(LiveVoice.DEFAULT in VoiceCatalog.GEMINI_FEMALE)
    }

    @Test fun savedChoiceStays() {
        assertEquals("Kore", LiveVoice.pick("Kore", "female"))
        assertEquals("Leda", LiveVoice.pick("Leda", "female"))
        // पुरुष आवाज़ चुनी हो तो पुरुष सूची से (पहले जैसा)
        assertEquals("Charon", LiveVoice.pick("Charon", "male"))
        assertEquals(VoiceCatalog.GEMINI_MALE.first(), LiveVoice.pick("", "male"))
    }

    @Test fun startHasHindiAndAffective() {
        val e = LiveVoice.start(m38, "Sulafat", emptySet())
        assertEquals("Sulafat", e.voice); assertTrue(e.lang); assertTrue(e.affective)
        assertEquals("hi-IN", LiveVoice.LANGUAGE)
        // 3.1 Flash Live affective dialog नहीं लेता
        assertFalse(LiveVoice.start(m31, "Sulafat", emptySet()).affective)
    }

    @Test fun rememberedOffIsSkipped() {
        val off = setOf("$m38|aff", "$m38|lang", "$m38|voice:Sulafat")
        val e = LiveVoice.start(m38, "Sulafat", off)
        assertEquals("Aoede", e.voice); assertFalse(e.lang); assertFalse(e.affective)
        // दूसरे मॉडल पर असर नहीं
        val o = LiveVoice.start("gemini-2.5-flash-native-audio-preview-12-2025", "Sulafat", off)
        assertEquals("Sulafat", o.voice); assertTrue(o.lang); assertTrue(o.affective)
        // दूसरी चुनी आवाज़ पर असर नहीं
        assertEquals("Kore", LiveVoice.start(m38, "Kore", off).voice)
    }

    @Test fun namedErrorDropsThatOnly() {
        val e = LiveVoice.start(m38, "Sulafat", emptySet())
        val v = LiveVoice.degrade(m38, e, "Voice name Sulafat is not supported", 1007)!!
        assertEquals("Aoede", v.next.voice); assertTrue(v.next.lang); assertTrue(v.next.affective)
        assertEquals("$m38|voice:Sulafat", v.offEntry)
        val l = LiveVoice.degrade(m38, e, "language_code is not supported for this model", 1007)!!
        assertFalse(l.next.lang); assertEquals("Sulafat", l.next.voice)
        val a = LiveVoice.degrade(m38, e, "Unknown name \"enableAffectiveDialog\"", 1007)!!
        assertFalse(a.next.affective); assertTrue(a.next.lang)
    }

    @Test fun genericErrorStepsDownThenGivesUp() {
        var e = LiveVoice.start(m38, "Sulafat", emptySet())
        val seen = mutableListOf<String>()
        while (true) {
            val st = LiveVoice.degrade(m38, e, "Request contains an invalid argument.", 1007) ?: break
            seen += st.offEntry; e = st.next
        }
        assertEquals(listOf("$m38|aff", "$m38|lang", "$m38|voice:Sulafat"), seen)
        assertEquals("Aoede", e.voice)
    }

    @Test fun otherErrorsUntouched() {
        val e = LiveVoice.start(m38, "Sulafat", emptySet())
        assertNull(LiveVoice.degrade(m38, e, "models/x is not found for API version v1beta", 1008))
        assertNull(LiveVoice.degrade(m38, e, "API key not valid", 1007))
        assertNull(LiveVoice.degrade(m38, e, "You exceeded your current quota", 1011))
        assertNull(LiveVoice.degrade(m38, e, "setup का जवाब 12 सेकंड में नहीं आया", 0))
    }

    @Test fun offListRoundTrip() {
        val s = setOf("a|aff", "b|voice:Sulafat")
        assertEquals(s, LiveVoice.parseOff(LiveVoice.joinOff(s)))
        assertTrue(LiveVoice.parseOff(null).isEmpty())
    }

    @Test fun friendPromptAndHindiRule() {
        assertTrue(TalkStyle.FRIEND_HI.contains("तुम D.K हो, यूज़र की सबसे करीबी दोस्त"))
        assertTrue(TalkStyle.FRIEND_HI.contains("भावुक बात कहने से पहले थोड़ा रुको"))
        assertTrue(TalkStyle.FRIEND_HI.contains("सच में समझ न आए तभी प्यार से पूछो"))
        val hi = Lang.sys("x", Lang.HINDI)
        assertTrue(hi.contains("pure, simple Hindi"))
        assertTrue(hi.contains("only when the user clearly asks"))
        assertNotNull(Lang.ALL.firstOrNull { it.first == Lang.HINDI && it.second.contains("शुरुआती") })
    }
}
