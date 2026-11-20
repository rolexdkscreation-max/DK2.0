package com.dk.assistant

import com.dk.assistant.backend.BackendException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 20 नवंबर 2026 (मालिक): इमेज = सिर्फ़ DK सर्वर का Cloudflare FLUX - चैट, DK लाइव, DK कंप्यूटर तीनों में */
class FluxImageTest {
    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()
    private fun res(p: String) = listOf(File("src/main/res/$p"), File("app/src/main/res/$p")).first { it.exists() }.readText()

    @Test fun ownerWordsExact() {
        assertEquals("आज की इमेज लिमिट पूरी हो गई, कल सुबह 5:30 बजे के बाद फिर बनेगी।", FluxImage.LIMIT_MSG)
        assertEquals("इमेज बन गई है, चैट खोलकर देख लीजिए।", FluxImage.LIVE_DONE)
        assertEquals(FluxImage.LIMIT_MSG, FluxImage.errorHi(BackendException(429, "IMAGE_LIMIT", "limit")))
        assertTrue(FluxImage.isLimit(BackendException(429, FluxImage.LIMIT_CODE, "x")))
        assertFalse(FluxImage.isLimit(BackendException(502, "UPSTREAM", "x")))
    }

    @Test fun wantedImageAsks() {
        for (t in listOf("एक शेर की तस्वीर बनाओ", "समुद्र किनारे सूर्यास्त की इमेज बना दो", "मेरे चैनल के लिए लोगो बनाइए",
            "make an image of a red car", "generate a picture of mountains", "ek photo banao bike ki", "draw a sketch of a cat"))
            assertTrue(t, FluxImage.wanted(t))
    }

    @Test fun notImageAsks() {
        for (t in listOf("फोटो खींचो", "इस फोटो में क्या है", "बिक्री का चार्ट बनाओ", "ChatGPT में इमेज बनाओ", "Gemini से फोटो बनाओ",
            "गैलरी की फोटो दिखाओ", "एक वीडियो बनाओ", "नमस्ते", "मेरी फोटो एडिट करो", "PDF बनाओ"))
            assertFalse(t, FluxImage.wanted(t))
    }

    @Test fun warnHindiOrLongText() {
        assertEquals(FluxImage.TEXT_WARN, FluxImage.textWarning("एक पोस्टर बनाओ जिस पर \"दिवाली की शुभकामनाएँ\" लिखा हो"))
        assertEquals(FluxImage.TEXT_WARN, FluxImage.textWarning("poster with text \"Welcome to our grand family function tonight\""))
        assertNull(FluxImage.textWarning("logo with the text \"DK\""))
        assertNull(FluxImage.textWarning("एक शेर की तस्वीर बनाओ"))
    }

    @Test fun cleanPromptStripsWrappers() {
        assertEquals("A lion at sunset", FluxImage.cleanPrompt("Prompt: \"A lion   at sunset\""))
        assertEquals("A cat", FluxImage.cleanPrompt("```\nA cat\n```"))
        assertTrue(FluxImage.cleanPrompt("x".repeat(5000)).length <= FluxImage.PROMPT_MAX)
        assertTrue(FluxImage.hasHindi("शेर")); assertFalse(FluxImage.hasHindi("lion"))
    }

    @Test fun tagRoundTrip() {
        val t = FluxImage.tag("dk_img_123.jpg", "एक शेर की   तस्वीर")
        val p = FluxImage.parse(t)
        assertNotNull(p)
        assertEquals("dk_img_123.jpg", p!!.first)
        assertEquals("एक शेर की तस्वीर", p.second)
        assertNull(FluxImage.parse("सादा जवाब"))
        assertNull(FluxImage.parse("[dkimg:../../etc/passwd]"))
    }

    @Test fun leftLineAndUtcDay() {
        assertTrue(FluxImage.leftText(12).contains("12"))
        assertTrue(FluxImage.leftText(0).contains("पूरी"))
        assertTrue(FluxImage.leftText(-1).contains("${FluxImage.PER_DAY}"))
        // भारत 05:29 = पिछला UTC दिन, 05:30 = नया दिन
        val ist0529 = java.time.ZonedDateTime.of(2026, 11, 21, 5, 29, 0, 0, java.time.ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
        assertEquals("2026-11-20", FluxImage.utcDay(ist0529))
        assertEquals("2026-11-21", FluxImage.utcDay(ist0529 + 60_000))
    }

    @Test fun wiredInAllThreePlaces() {
        assertTrue(src("dkai/DkaiScreen.kt").contains("FluxImage.wanted(t)"))
        assertTrue(src("dkai/DkaiScreen.kt").contains("imageCard("))
        assertTrue(src("GeminiLiveSetup.kt").contains("\"generate_image\""))
        assertTrue(src("GeminiLiveTools.kt").contains("\"generate_image\" ->"))
        assertTrue(src("GeminiLiveTools.kt").contains("FluxImage.liveTool(svc, args)"))
        assertTrue(LeakedCall.TOOLS.contains("generate_image"))
        assertTrue(ComputerAgent.TOOL_IDS.contains("make_image"))
        assertTrue(src("ComputerAgent.kt").contains("\"make_image\" ->"))
        assertTrue(src("SettingsCoder.kt").contains("\"make_image\" to"))
        assertTrue(res("xml/computer_files.xml").contains("dk_images/"))
    }

    @Test fun oldApiKeyOptionGone() {
        val s = src("SettingsExtra.kt")
        assertFalse(s.contains("Provider.values()"))
        assertFalse(s.contains("ImageGen.PROVIDER"))
        assertFalse(s.contains("keyPref"))
        val g = src("plus/ImageGen.kt")
        assertTrue(g.contains("FluxImage.make("))
        assertFalse(g.contains("aihorde.net/api"))
        assertFalse(g.contains("OkHttpClient"))
    }
}
