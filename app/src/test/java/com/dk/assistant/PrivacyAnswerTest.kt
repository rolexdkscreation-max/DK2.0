package com.dk.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 20 नवंबर 2026 (मालिक: "कोई पूछे कि मेरा डेटा कहाँ रखती हो या लीक होता है, तो DK सख़्ती से बताए कि पूरी तरह सुरक्षित है,
 * फ़ोन पर ही रहता है - और privacy में भी अच्छे से लिखा हो"). जवाब पक्का पर code के हिसाब से सच.
 */
class PrivacyAnswerTest {
    @Test fun detectsPrivacyQuestions() {
        for (q in listOf("मेरा डेटा कहाँ रखते हो", "डेटा लीक होता है क्या", "तुम मेरा data कहाँ save करती हो", "is my data safe",
                "मेरी बातें किसी server पर जाती हैं क्या", "तुम्हारी privacy कैसी है", "मेरा डाटा सुरक्षित है क्या", "क्या तुम मेरा डेटा बेचते हो"))
            assertTrue(q, PrivacyText.asks(q))
        for (q in listOf("मोबाइल डेटा ऑन करो", "data off kar do", "आज मौसम कैसा है", "व्हाट्सएप खोलो", "डेटा साइंस क्या है"))
            assertFalse(q, PrivacyText.asks(q))
    }

    @Test fun answerFirmAndTrue() {
        val a = PrivacyText.ANSWER
        assertTrue(a.startsWith("आपका डेटा पूरी तरह सुरक्षित है"))
        assertTrue(a.contains("सिर्फ़ आपके इसी फ़ोन में"))
        assertTrue(a.contains("किसी सर्वर पर सेव नहीं करता"))
        assertTrue(a.contains("सवाल उसी पल इंटरनेट वाली AI सेवा को जाता है"))      // सच: जवाब बनाने को सवाल जाता है
        assertFalse(a.contains("कुछ भी बाहर नहीं जाता"))
        val r = AssistantBrand.PRIVACY_RULE
        assertTrue(r.contains("stored only on this phone") && r.contains("Never claim that nothing ever leaves the phone"))
        assertTrue(AssistantBrand.IDENTITY_RULE.endsWith(r))                     // हर system prompt (chat + Live) में
    }

    @Test fun settingsAndDocsUpdated() {
        val all = PrivacyText.LINES.joinToString("\n")
        assertTrue(all.startsWith("• आपका डेटा आपका है"))
        assertFalse(all.contains("DK का अपना कोई server नहीं"))                 // 17 नवंबर से सच नहीं था
        assertTrue(all.contains("DK सर्वर आपकी बातचीत सेव नहीं करता"))
        val root = listOf(java.io.File("."), java.io.File("..")).first { java.io.File(it, "docs/PRIVACY.md").exists() }
        val doc = java.io.File(root, "docs/PRIVACY.md").readText()
        assertTrue(doc.contains("**आपका डेटा आपका है।**") && doc.contains("DK सर्वर आपकी बातचीत सेव नहीं करता"))
        assertFalse(doc.contains("DK का कोई अपना server नहीं है"))
        val src = java.io.File(root, "app/src/main/java/com/dk/assistant/AgentRunner.kt").readText()
        assertTrue(src.contains("if (PrivacyText.asks(lower)) { say(PrivacyText.ANSWER); return }"))
    }
}
