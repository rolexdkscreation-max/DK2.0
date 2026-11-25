package com.dk.assistant.translate

import android.app.Application
import com.dk.assistant.QuickCommands
import com.dk.assistant.plus.models.ModelCatalog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 25 नवंबर 2026 (मालिक: "DK ट्रांसलेटर - सीधा दुभाषिया"): भाषा पहचान (language filter), लिपि बदलना, बोलकर चालू / बंद,
 * Live setup, ऑफ़लाइन मॉडल (APK में नहीं - इंटरनेट से), और बाय डिफ़ॉल्ट बंद.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DkTranslatorTest {
    private fun src(rel: String): String = listOf(File("src/main/java/com/dk/assistant/$rel"), File("app/src/main/java/com/dk/assistant/$rel")).first { it.exists() }.readText()

    @Test fun guess_marathiHindiAndScripts() {
        assertEquals("mr", TrLang.guess("मला स्टेशनला जायचं आहे"))
        assertEquals("mr", TrLang.guess("तुम्ही कुठे जात आहात"))
        assertEquals(TrLang.HINDI, TrLang.guess("मुझे स्टेशन जाना है"))
        assertEquals(TrLang.HINDI, TrLang.guess("आप कहाँ जा रहे हैं"))
        assertEquals("ta", TrLang.guess("நீங்கள் எங்கே போகிறீர்கள்"))
        assertEquals("te", TrLang.guess("మీరు ఎక్కడికి వెళ్తున్నారు"))
        assertEquals("en", TrLang.guess("Where are you going"))
        assertEquals(TrLang.HINDI, TrLang.guess("aap kahan ja rahe ho bhai"))
        assertEquals(TrLang.UNKNOWN, TrLang.guess("123 ..."))
    }

    @Test fun verdict_myHindiIsIgnored_chosenLanguagePlays() {
        assertEquals(TrLang.Verdict.DROP, TrLang.verdict("mr", "मुझे स्टेशन जाना है", false))
        assertEquals(TrLang.Verdict.PLAY, TrLang.verdict("mr", "मला स्टेशनला जायचं आहे", false))
        assertEquals(TrLang.Verdict.PLAY, TrLang.verdict(TrLang.AUTO, "Where are you going", false))
        assertEquals(TrLang.Verdict.PLAY, TrLang.verdict("ta", "நீங்கள் எங்கே போகிறீர்கள்", false))
        // चुनी तमिल, सुनी तेलुगु = पूरी बात पर "समझ नहीं आई"
        assertEquals(TrLang.Verdict.WAIT, TrLang.verdict("ta", "మీరు ఎక్కడికి", false))
        assertEquals(TrLang.Verdict.UNSURE, TrLang.verdict("ta", "మీరు ఎక్కడికి", true))
        // देवनागरी बराबरी: मराठी चुनी = सुनाओ; तमिल चुनी = यह हिंदी ही है
        assertEquals(TrLang.Verdict.WAIT, TrLang.verdict("mr", "स्टेशन", false))
        assertEquals(TrLang.Verdict.PLAY, TrLang.verdict("mr", "स्टेशन", true))
        assertEquals(TrLang.Verdict.DROP, TrLang.verdict("ta", "स्टेशन", true))
    }

    @Test fun sameAsHeard_detectsEcho() {
        assertTrue(TrLang.sameAsHeard("मुझे स्टेशन जाना है", "मुझे स्टेशन जाना है।"))
        assertFalse(TrLang.sameAsHeard("मला स्टेशनला जायचं आहे", "मुझे स्टेशन जाना है"))
    }

    @Test fun scripts_roundTrip() {
        val deva = "नमस्ते"
        val ta = TrLang.fromDeva(deva, "te")
        assertTrue(ta.isNotEmpty() && ta != deva)
        assertEquals(deva, TrLang.toDeva(ta))
        assertEquals(deva, TrLang.fromDeva(deva, "mr"))
        assertEquals("", TrLang.readInHindi("मुझे"))
        assertEquals(deva, TrLang.readInHindi(ta))
    }

    @Test fun languages_marathiFirst_andAutoChoice() {
        assertEquals("mr", TrLang.ALL.first().code)
        assertEquals(TrLang.AUTO, TrLang.CHOICES.first())
        listOf("mr", "ta", "te", "bn", "gu", "pa", "en").forEach { assertNotNull(it, TrLang.of(it)) }
        assertTrue(TrLang.offlineTo("mr")); assertFalse(TrLang.offlineTo("en"))
    }

    @Test fun commands_onOff_andNotSentenceTranslation() {
        fun on(t: String) = TrCommands.parse(t)?.action?.optBoolean("on")
        assertEquals(true, on("डीके ट्रांसलेट को चालू करो"))
        assertEquals(true, on("ट्रांसलेटर चालू करो"))
        assertEquals(true, on("translator mode on"))
        assertEquals(true, on("दुभाषिया शुरू करो"))
        assertEquals(false, on("ट्रांसलेटर बंद करो"))
        assertEquals(false, on("translator off"))
        assertNull(TrCommands.parse("इसे अंग्रेज़ी में ट्रांसलेट करो"))
        assertNull(TrCommands.parse("translate this to english"))
        assertNull(TrCommands.parse("लाइट चालू करो"))
        assertEquals(TrCommands.TYPE, TrCommands.parse("ट्रांसलेटर चालू करो")?.action?.optString("type"))
    }

    @Test fun quickCommands_routesTranslator() {
        val q = QuickCommands.parse("DK, ट्रांसलेटर चालू करो", listOf("dk", "डीके"))
        assertEquals(TrCommands.TYPE, q?.action?.optString("type"))
        assertTrue(src("QuickCommands.kt").contains("com.dk.assistant.translate.TrCommands.parse(t)"))
        assertTrue(src("DKAccessibilityService.kt").contains("com.dk.assistant.translate.TrEngine.voice(this"))
    }

    @Test fun prompts_onlyTranslate() {
        assertTrue(TrPrompt.toHindi("mr").contains(TrPrompt.CORE))
        assertTrue(TrPrompt.fromHindi("mr").contains(TrPrompt.CORE))
        assertTrue(TrPrompt.toHindi("mr").contains("Marathi"))
        assertTrue(TrPrompt.fromHindi("ta").contains("Tamil"))
    }

    @Test fun liveSetup_transcription_noInterruption_zephyr() {
        val s = JSONObject(TrLive.setupJson("gemini-3.1-flash-live-preview", "p", TrEngine.VOICE)).toString()
        assertTrue(s.contains("NO_INTERRUPTION"))
        assertTrue(s.contains("inputAudioTranscription"))
        assertTrue(s.contains("outputAudioTranscription"))
        assertTrue(s.contains("Zephyr"))
        assertFalse(s.contains("thinkingBudget"))
        assertTrue(TrLive.setupJson("gemini-2.5-flash-native-audio", "p", "Zephyr").contains("thinkingBudget"))
    }

    @Test fun bpe_encodeDecode() {
        val vocab = mapOf("\u2581a" to 5, "\u2581ab" to 6, "b" to 7, "\u2581" to 8, "a" to 9)
        val merges = mapOf("\u2581 a" to 0, "\u2581a b" to 1)
        val tags = mapOf("mar_Deva" to 100, "hin_Deva" to 101)
        val tgt = arrayOfNulls<String>(12).apply { this[6] = "\u2581आप"; this[7] = "\u2581कहाँ"; this[8] = "\u2581?"; this[9] = "<x>" }
        val b = TrBpe(vocab, merges, tags, tgt, special = setOf(9))
        assertEquals(listOf(100L, 101L, 6L, 2L), b.encode("ab", "mar_Deva", "hin_Deva").toList())
        assertEquals(listOf(100L, 101L, 6L, 8L, 3L, 2L), b.encode("ab zz", "mar_Deva", "hin_Deva").toList())
        assertEquals("आप कहाँ?", b.decode(listOf(0, 6, 7, 9, 8, 2)))
    }

    @Test fun offlineModel_downloadedNotBundled() {
        assertEquals(7, ModelCatalog.MT_INDIC.size)
        ModelCatalog.MT_INDIC.forEach { a ->
            assertEquals(ModelCatalog.Kind.MT, a.kind)
            assertTrue(a.urls.all { it.startsWith("https://") })
            assertEquals(64, a.sha256.length)
            assertTrue(ModelCatalog.wifiOnly(a))
        }
        assertTrue(ModelCatalog.problems().toString(), ModelCatalog.problems().isEmpty())
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.exists() }
        assertTrue(assets.walk().none { it.name.endsWith(".onnx") && it.name.contains("encoder") })
    }

    @Test fun offByDefault_onlyOpenedByUserOrVoice() {
        assertFalse(TrEngine.running)
        // इंजन सिर्फ़ ट्रांसलेटर स्क्रीन से शुरू होता है (स्क्रीन = Settings बटन या "DK ट्रांसलेटर चालू करो")
        val dir = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val starters = dir.walk().filter { it.isFile && it.name.endsWith(".kt") && it.readText().contains("TrEngine.start(") }.map { it.name }.toList()
        assertEquals(listOf("TranslatorActivity.kt"), starters)
        assertTrue(src("translate/TrEngine.kt").contains("DKState.setMicMuted(true)"))
        assertTrue(src("translate/TrEngine.kt").contains("DKState.setMicMuted(prevMuted)"))
    }

    @Test fun wav_parse_resamplesTo24k() {
        // 16 kHz, 100 samples -> 150 samples @24 kHz
        val n = 100
        val data = ByteArray(n * 2) { if (it % 2 == 0) 0x10 else 0 }
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + data.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(16000); h.putInt(32000); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(data.size)
        val out = TrTts.Wav.parse(h.array() + data)
        assertNotNull(out)
        assertEquals(300, requireNotNull(out).size)
        assertEquals(0x10, requireNotNull(out)[0].toInt())
    }
}
