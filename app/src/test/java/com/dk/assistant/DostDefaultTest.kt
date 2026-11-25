package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 25 नवंबर 2026 (मालिक का नया DK प्रॉम्प्ट): डिफ़ॉल्ट दोस्ताना = शांत, समझदार दोस्त, सिर्फ़ हिंदी-हिंग्लिश, किताबी हिंदी नहीं,
 * पूरब (UP-बिहार) की हल्की मिठास, "बॉस", 1-3 छोटे वाक्य, कोई निशान नहीं - Live और बिना-Live (offline TTS) दोनों में.
 */
class DostDefaultTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun friendlyIsDefault_andHasOwnerRules() {
        assertEquals(DkPersona.P.FRIENDLY, DkPersona.DEFAULT)
        val f = DkPersona.finalFor(DkPersona.P.FRIENDLY, "धीरज")
        for (k in listOf("DOST DEFAULT", "LANGUAGE LOCK", "never pure English".lowercase(), "सहायता", "कृपया", "निश्चित रूप से", "एक सेकंड रुकिए",
            "बताइए, क्या करना है?", "काहे टेंशन ले रहे हैं", "एकदम बढ़िया", "चलिए, हो जाएगा", "सुनिए ना", "बात तो सही है", "अच्छा एक काम करते हैं",
            "\"बॉस\"", "1 to 3 short sentences", "emojis", "brackets", "subtly witty"))
            assertTrue("नहीं मिला: $k", f.contains(k) || f.lowercase().contains(k))
        assertTrue(f.contains("call the user \"बॉस\""))                          // संबोधन: पहला शब्द बॉस
        assertTrue(f.length < 4_500)                                             // Live के छोटे निर्देश 29,000 से कम रहें
        // बाकी स्वभावों में नहीं
        for (p in DkPersona.P.values().filter { it != DkPersona.P.FRIENDLY }) assertFalse(p.name, DkPersona.finalFor(p, "धीरज").contains("DOST DEFAULT"))
    }

    @Test fun appliedInLiveAndOfflineVoicePrompts() {
        val bm = src("BrainManager.kt")
        // बिना-Live (फ़ोन की आवाज़ / offline TTS) के दोनों निर्देश + Live पूरे + Live छोटे = 4 जगह finalBlock
        assertEquals(4, Regex(Regex.escape("DkPersona.finalBlock(ctx, Prefs.userName(ctx))")).findAll(bm).count())
        assertTrue(DkPersona.ttsFor(DkPersona.P.FRIENDLY, "").contains("calm, soothing"))
        assertTrue(bm.contains("append(DkPersona.sayRule(ctx))"))                 // फ़ोन-काम वाली बोली भी
        assertTrue(DkPersona.SAY_RULE.contains("कृपया") && DkPersona.SAY_RULE.contains("\"बॉस\""))
    }

    @Test fun spokenLinesNotBookish() {
        assertFalse(LiveRecover.ASK_AGAIN_LINE.contains("कृपया"))
        assertFalse(GeminiChatChain.BUSY_HI.contains("कृपया"))
        assertFalse(DkPersona.quick(DkPersona.P.FRIENDLY).ack.contains("सर"))
    }
}
