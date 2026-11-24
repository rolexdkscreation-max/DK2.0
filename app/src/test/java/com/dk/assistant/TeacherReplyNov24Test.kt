package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 24 नवंबर 2026 (मालिक: "टीचर मोड में टाइप / स्केच से पूछा तो ब्रैकेट, 'response' लिखा, फिर जवाब, फिर ब्रैकेट;
 * आवाज़ रोबोट जैसी फ़ोन की TTS - ऑफ़लाइन वाली TTS से आए, जवाब साफ़ हो").
 */
class TeacherReplyNov24Test {
    private fun src(name: String): String = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Test fun jsonWrapIsOpened() {
        assertEquals("पानी 100 डिग्री पर उबलता है।", TeacherReply.clean("{\"response\": \"पानी 100 डिग्री पर उबलता है।\"}"))
        assertEquals("पानी उबलता है।", TeacherReply.clean("```json\n{ \"answer\": \"पानी उबलता है।\" }\n```"))
        // अंदर [WRITE] और नई लाइनें - सब बचे
        val j = TeacherReply.clean("{\"response\": \"चरण 1: 2+2\\n[WRITE]2 + 2 = 4[/WRITE]\\nसमझ आया?\"}")
        assertEquals("चरण 1: 2+2\n[WRITE]2 + 2 = 4[/WRITE]\nसमझ आया?", j)
    }

    @Test fun tagAndLabelWrapsRemoved() {
        assertEquals("प्रकाश संश्लेषण में पत्ती खाना बनाती है।", TeacherReply.clean("[RESPONSE] प्रकाश संश्लेषण में पत्ती खाना बनाती है। [/RESPONSE]"))
        assertEquals("यह त्रिभुज है।", TeacherReply.clean("<response>यह त्रिभुज है।</response>"))
        assertEquals("यह त्रिभुज है।", TeacherReply.clean("Response: यह त्रिभुज है।"))
        assertEquals("यह त्रिभुज है।", TeacherReply.clean("**Answer:** यह त्रिभुज है।"))
        assertEquals("यह त्रिभुज है।", TeacherReply.clean("[Response]{\"response\": \"यह त्रिभुज है।\"}[/Response]"))
        assertEquals("यह त्रिभुज है।", TeacherReply.clean("\"यह त्रिभुज है।\""))
        assertEquals("ठीक है", TeacherReply.clean("(response) ठीक है"))
    }

    @Test fun boardTagsAndNormalTextKept() {
        val a = "देखो:\n[DRAW]circle 50 30 10[/DRAW]\n[WRITE]r = 10[/WRITE]\nउत्तर: 314"
        assertEquals(a, TeacherReply.clean(a))
        assertEquals("सीधा जवाब, कोई लपेट नहीं।", TeacherReply.clean("सीधा जवाब, कोई लपेट नहीं।"))
        assertEquals("{\"say\":\"ठीक\",\"actions\":[]}", TeacherReply.clean("{\"say\":\"ठीक\",\"actions\":[]}"))   // फ़ोन-काम JSON को नहीं छेड़ते
    }

    @Test fun spokenIsCleanSpeech() {
        val s = TeacherReply.spoken("**चरण 1:** गोला बनाओ।\n[DRAW]circle 50 30 10 red[/DRAW]\n[WRITE]r = 10[/WRITE]\n- # समझ आया?")
        assertFalse(s.contains("circle")); assertFalse(s.contains("[")); assertFalse(s.contains("*")); assertFalse(s.contains("#"))
        assertFalse(s.contains("r = 10"))                                                  // बोर्ड के बिंदु दोबारा नहीं
        assertTrue(s.contains("गोला बनाओ") && s.contains("समझ आया?"))
        // सिर्फ़ [WRITE] में जवाब = वही बोलो (चुप नहीं)
        assertEquals("2 + 2 = 4", TeacherReply.spoken("[WRITE]2 + 2 = 4[/WRITE]"))
        assertEquals("", TeacherReply.spoken("[DRAW]circle 50 30 10[/DRAW]"))
    }

    @Test fun teacherSpeaksWithOfflineVoice_notPhoneTts() {
        val t = src("TeacherActivity.kt")
        assertFalse(t.contains("android.speech.tts.TextToSpeech"))
        assertFalse(t.contains("tts?.speak("))
        assertTrue(t.contains("PhoneVoice.say(applicationContext, say)"))
        assertTrue(t.contains("TeacherReply.clean(raw)"))
        assertTrue(t.contains("TeacherReply.spoken("))
        assertTrue(t.contains("HinglishText.toSpeakable"))
        assertTrue(t.contains("stopSpeaking()"))
        // PhoneVoice.say = पहले ऑफ़लाइन (Supertonic), वह न चले तभी फ़ोन की आवाज़
        val pv = src("PhoneVoice.kt")
        val say = pv.substringAfter("suspend fun say(c: Context, text: String): Boolean {").substringBefore("\n    }")
        assertTrue(say.indexOf("OfflineVoice.say") in 0 until say.indexOf("android(c, text)"))
    }
}
