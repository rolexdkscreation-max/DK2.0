package com.dk.assistant.voice.st3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 20 नवंबर 2026 (मालिक: "उतार-चढ़ाव, ठहराव, बीच में साँस - जैसे Gemini"): फ़ोन वाली आवाज़ की लय */
class St3ProsodyTest {
    @Test fun pauses_humanLike_andStable() {
        assertTrue(St3Prosody.pauseMs("ठीक है।") in 270..330)
        assertTrue(St3Prosody.pauseMs("आप कैसे हैं?") in 350..410)
        assertTrue(St3Prosody.pauseMs("अरे वाह!") in 290..350)
        assertTrue(St3Prosody.pauseMs("सुनिए,") in 120..180)
        assertEquals(40, St3Prosody.pauseMs("और फिर"))
        assertEquals(0, St3Prosody.pauseMs("   "))
        // सवाल के बाद वाक्य से ज़्यादा, अल्पविराम से वाक्य ज़्यादा
        assertTrue(St3Prosody.pauseMs("क्या?") > St3Prosody.pauseMs("हाँ।") - 60 + 30)
        assertEquals(St3Prosody.pauseMs("ठीक है।"), St3Prosody.pauseMs("ठीक है।"))      // एक ही text = एक ही
        // पुराने एक-से ठहराव (140 / 60) से लंबे
        assertTrue(St3Prosody.pauseMs("ठीक है।") > 140 && St3Prosody.pauseMs("सुनिए,") > 60)
    }

    @Test fun softEnd_noFallingToneMidSentence() {
        assertEquals("मैं आपको बताती हूँ,", St3Prosody.softEnd("मैं आपको बताती हूँ"))
        assertEquals("ठीक है।", St3Prosody.softEnd("ठीक है।"))
        assertEquals("क्या?", St3Prosody.softEnd("क्या?"))
        assertEquals("हाँ <laugh>", St3Prosody.softEnd("हाँ <laugh>"))
        assertEquals("", St3Prosody.softEnd(""))
        // मॉडल का नियम अब "." नहीं लगाता
        assertFalse(St3Text.preprocess(St3Prosody.softEnd("मैं आपको बताती हूँ")).contains("हूँ."))
    }

    @Test fun speed_questionsSlower_exclaimFaster() {
        assertEquals(0.97f, St3Prosody.speedMul("आपका नाम क्या है?"), 0f)
        assertEquals(1.03f, St3Prosody.speedMul("बहुत बढ़िया काम हुआ है!"), 0f)
        assertEquals(0.97f, St3Prosody.speedMul("हाँ, ठीक है।"), 0f)
        assertEquals(1f, St3Prosody.speedMul("आज मौसम साफ़ रहेगा और शाम को हल्की हवा चलेगी।"), 0f)
    }

    @Test fun breath_onlyInLongAnswers_atSentenceStart() {
        val s = St3Prosody.Shaper()
        val a = "आज सुबह से मौसम बहुत अच्छा है, हल्की धूप निकली है और हवा भी ठंडी चल रही है।"
        val b = "शाम तक आसमान साफ़ रहेगा, बारिश की कोई संभावना नहीं है, तो आप आराम से बाहर जा सकते हैं।"
        val c = "अगर आप चाहें तो मैं कल का मौसम भी बता सकती हूँ, बस एक बार बोल दीजिए।"
        val out = listOf(a, b, c).map { s.shape(it) }
        assertFalse(out[0].startsWith(St3Prosody.BREATH))                          // पहले वाक्य पर कभी नहीं
        assertEquals(1, out.count { it.startsWith(St3Prosody.BREATH) })            // ~170 अक्षर बाद एक
        assertTrue(out[2].startsWith(St3Prosody.BREATH))
        s.reset()
        assertFalse(s.shape(a).startsWith("<"))                                   // नया जवाब = फिर से गिनती
        // छोटा जवाब: कभी साँस नहीं
        val t = St3Prosody.Shaper()
        assertTrue(listOf("हाँ जी।", "ठीक है।", "बताइए।").map { t.shape(it) }.none { it.contains("<breath>") })
        // बंद = नहीं
        val off = St3Prosody.Shaper()
        assertTrue(listOf(a, b, c).map { off.shape(it, breath = false) }.none { it.contains("<breath>") })
        // बीच से कटे टुकड़े के बाद साँस नहीं (वाक्य के बीच साँस अटपटी)
        val m = St3Prosody.Shaper()
        m.shape(a); m.shape(b.dropLast(1) + " और")
        assertFalse(m.shape(c).startsWith("<breath>"))
    }

    @Test fun wiredIntoConversation() {
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
        val eng = java.io.File(root, "com/dk/assistant/voice/engine/St3VoiceEngine.kt").readText()
        assertTrue(eng.contains("shaper.shape(speakable(text))"))
        assertTrue(eng.contains("St3Prosody.speedMul(said)"))
        assertTrue(eng.contains("shaper.reset()"))
        val pipe = java.io.File(root, "com/dk/assistant/voice/engine/SpeechPipeline.kt").readText()
        assertTrue(pipe.contains("St3Prosody.pauseMs(s.text)"))
        // दूसरे इंजन का ठहराव पहले जैसा
        assertEquals(140, com.dk.assistant.voice.engine.SpeechPipeline.pauseMs("ठीक है।"))
    }
}
