package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 22 नवंबर 2026 (मालिक): "क्या हाल है" पर NVIDIA वाली लाइनें बार-बार - loop वहीं रुके, कोई AI कंपनी DK की पहचान में नहीं */
class AnswerGuardTest {
    private val s1 = "नहीं, मैं यहाँ आपके लिये हूँ।"
    private val s2 = " मुझे यह समझ में नहीं आया, मगर मुझे मालूम है आप मुझे DK कहकर बुलाते हैं।"
    private val s3 = " मैं NVIDIA के शोधकर्ताओं द्वारा बनाया गया एक भाषा मॉडल हूँ।"
    private val c = " मुझे नेमोट्रॉन या डॉल-आई जैसे चित्र-जनरेशन मॉडल के रूप में प्रशिक्षित नहीं किया गया है।"
    private val b = " मुझे भाषा के कार्यों, प्रश्नों के उत्तर और वार्तालाप में मदद करने के लिये बनाया गया है।"
    private val a = " मुझे NVIDIA के शोधकर्ताओं द्वारा प्रशिक्षित किया गया है।"
    /** मालिक को आया असली जवाब (900 token पर "मुझे" में कटा) */
    private val owner = s1 + s2 + s3 + c + b + a + (b + c + a).repeat(14) + " मुझे"
    private val want = s1 + s2 + b

    @Test fun ownerLoop_cutAndNoAiName() {
        val r = AnswerGuard.clean(owner)
        assertEquals(want, r)
        assertFalse(r.contains("NVIDIA")); assertFalse(r.contains("नेमोट्रॉन")); assertFalse(r.contains("भाषा मॉडल"))
    }

    @Test fun ownerLoop_streamStopsEarlyAndNeverShowsIt() {
        val pieces = owner.chunked(5)
        var used = 0
        val shown = StringBuilder()
        val full = AnswerGuard.stream({ shown.append(it) }) { d -> for (p in pieces) { used++; d(p) }; owner }
        assertEquals(want, full)
        assertEquals(want, shown.toString())
        assertTrue("loop पकड़ते ही stream बंद", used < pieces.size / 3)
    }

    @Test fun anyAiCompany_identityRemoved() {
        assertEquals("आज मौसम अच्छा है।", AnswerGuard.clean("मैं Google द्वारा प्रशिक्षित एक बड़ा भाषा मॉडल हूँ। आज मौसम अच्छा है।"))
        assertEquals("How can I help?", AnswerGuard.clean("I am a large language model trained by Google. How can I help?"))
        assertEquals("हाँ बोलिए।", AnswerGuard.clean("main Gemini model hoon. हाँ बोलिए।"))
        for (x in listOf("OpenAI", "Meta", "Llama", "Groq", "Mistral", "DeepSeek", "Qwen", "Claude", "Cohere", "Z.ai", "एनवीडिया", "गूगल"))
            assertEquals(x, "ठीक हूँ।", AnswerGuard.clean("मुझे $x ने बनाया है। ठीक हूँ।"))
    }

    @Test fun onlyIdentity_friendlyFallback() {
        assertEquals(AnswerGuard.FALLBACK, AnswerGuard.clean("मैं NVIDIA का भाषा मॉडल हूँ।"))
    }

    @Test fun creatorAnswerAndNormalNamesKept() {
        assertEquals(AssistantBrand.CREATOR_ANSWER, AnswerGuard.clean(AssistantBrand.CREATOR_ANSWER))
        val t = "मैंने Google Maps खोल दिया है। NVIDIA का शेयर आज ऊपर है। मैं Google Maps से रास्ता बताती हूँ।"
        assertEquals(t, AnswerGuard.clean(t))
    }

    @Test fun normalAnswerUntouched_streamToo() {
        val t = "मैं ठीक हूँ, धन्यवाद! आप बताइए, आज का दिन कैसा रहा? कोई काम हो तो बोलिए।\nठीक है। ठीक है। ठीक है।"
        assertEquals(t, AnswerGuard.clean(t))
        val g = AnswerGuard(); val s = StringBuilder()
        for (p in t.chunked(3)) s.append(g.feed(p))
        s.append(g.flush())
        assertEquals(t, s.toString()); assertFalse(g.stopped)
    }

    @Test fun oneLegitRepeatKept() {
        val t = "पहला कदम: ऐप खोलिए और सेटिंग देखिए। फिर नीचे जाइए। पहला कदम: ऐप खोलिए और सेटिंग देखिए। बस इतना।"
        assertEquals(t, AnswerGuard.clean(t))
    }

    @Test fun numbersLinksCodeJsonSafe() {
        val n = "वर्शन 3.150 आ गया है, google.com खोलिए और 2.5 GB जगह रखिए।"
        assertEquals(n, AnswerGuard.clean(n))
        val code = "ये लो:\n```kotlin\nval a = listOf(1, 2, 3, 4, 5)\nval a = listOf(1, 2, 3, 4, 5)\nval a = listOf(1, 2, 3, 4, 5)\n```"
        assertEquals(code, AnswerGuard.clean(code))
        val js = "{\"say\": \"नमस्ते! मैं NVIDIA द्वारा बनाया गया मॉडल हूँ। आप कैसे हैं?\", \"done\": true}"
        assertEquals("{\"say\": \"नमस्ते! आप कैसे हैं?\", \"done\": true}", AnswerGuard.clean(js))
    }

    @Test fun firstWordsComeImmediately() {
        val g = AnswerGuard()
        assertEquals("नमस्ते ", g.feed("नमस्ते "))
        assertEquals("आज ", g.feed("आज "))
    }

    @Test fun identityRuleCoversEveryAi() {
        val r = AssistantBrand.IDENTITY_RULE
        for (w in listOf("ANY other AI company", "NVIDIA", "Nemotron", "Groq", "भाषा मॉडल", "क्या हाल है")) assertTrue(w, r.contains(w))
        assertTrue(LiveTextBrain.SYSTEM.contains(AssistantBrand.IDENTITY_RULE))
        for (w in listOf("NVIDIA", "Groq", "OpenRouter", "Z.ai", "Gemini")) assertFalse(w, LiveTextBrain.DK_BRIEF.contains(w))
    }
}
