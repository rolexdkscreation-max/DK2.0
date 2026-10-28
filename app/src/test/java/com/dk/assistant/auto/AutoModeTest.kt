package com.dk.assistant.auto

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoModeTest {
    private val all = AutoPlan.Kind.values().filter { it != AutoPlan.Kind.CUSTOM }.toSet()

    @Test fun codingGoesToCoder() {
        assertEquals(AutoPlan.Kind.CODING, AutoPlan.classify("मेरे रिपो में लॉगिन वाला बग ठीक करो", all)?.kind)
        assertEquals(AutoPlan.Kind.CODING, AutoPlan.classify("fix the crash bug in my github repo", all)?.kind)
    }

    @Test fun phoneKinds() {
        assertEquals(AutoPlan.Kind.WHATSAPP, AutoPlan.classify("व्हाट्सएप पर राहुल को मैसेज भेजो कि मैं आ रहा हूँ", all)?.kind)
        assertEquals(AutoPlan.Kind.INSTAGRAM, AutoPlan.classify("इंस्टाग्राम पर यह फोटो पोस्ट करो", all)?.kind)
        assertEquals(AutoPlan.Kind.YOUTUBE, AutoPlan.classify("यूट्यूब पर वीडियो अपलोड करो", all)?.kind)
        assertEquals(AutoPlan.Kind.FACEBOOK, AutoPlan.classify("facebook par post karo", all)?.kind)
    }

    @Test fun questionsAndOtherStayNormal() {
        assertNull(AutoPlan.classify("व्हाट्सएप क्या है", all))
        assertNull(AutoPlan.classify("आज मौसम कैसा है", all))
        assertNull(AutoPlan.classify("मेरा OTP कोड देखो", all))
        assertNull(AutoPlan.classify("पिन कोड बताओ", all))
    }

    @Test fun switchedOffKindIsNotAutonomous() {
        assertNull(AutoPlan.classify("व्हाट्सएप पर राहुल को मैसेज भेजो", all - AutoPlan.Kind.WHATSAPP))
        assertNull(AutoPlan.classify("रिपो का बग ठीक करो", all - AutoPlan.Kind.CODING))
    }

    @Test fun customTaskByName() {
        val c = listOf(AutoPlan.CustomTask("1", "सुबह की रिपोर्ट", "WhatsApp पर टीम ग्रुप में आज का प्लान भेजो"),
            AutoPlan.CustomTask("2", "बंद काम", "x", on = false))
        val p = AutoPlan.classify("सुबह की रिपोर्ट चलाओ", emptySet(), c)
        assertEquals(AutoPlan.Kind.CUSTOM, p?.kind)
        assertTrue(AutoPlan.phoneNote(p!!).contains("टीम ग्रुप"))
        assertNull(AutoPlan.classify("बंद काम करो", emptySet(), c))
    }

    @Test fun sensitiveScreensBlocked() {
        assertNotNull(AutoSafety.blockReason("[1] EditText \"Enter OTP\"", "com.whatsapp", false))
        assertNotNull(AutoSafety.blockReason("[1] TextView \"Hello\"", "com.phonepe.app", false))
        assertNotNull(AutoSafety.blockReason("", "com.instagram.android", true))
        assertNotNull(AutoSafety.blockReason("[3] TextView \"UPI PIN डालें\"", "x", false))
        assertNull(AutoSafety.blockReason("[1] TextView \"Rahul\"\n[2] Button \"Send\"", "com.whatsapp", false))
    }

    @Test fun irreversibleSteps() {
        val screen = "[5] ImageButton (Send) {click} @980,2200\n[6] TextView \"Chats\" {click} @200,300"
        assertTrue(AutoSafety.irreversible(JSONObject().put("type", "click").put("id", 5), screen, "com.whatsapp"))
        assertFalse(AutoSafety.irreversible(JSONObject().put("type", "click").put("id", 6), screen, "com.whatsapp"))
        assertTrue(AutoSafety.irreversible(JSONObject().put("type", "tap").put("x", 975).put("y", 2190), screen, "com.whatsapp"))
        assertTrue(AutoSafety.irreversible(JSONObject().put("type", "enter"), screen, "com.whatsapp"))
        assertFalse(AutoSafety.irreversible(JSONObject().put("type", "enter"), screen, "com.android.chrome"))
        assertFalse(AutoSafety.irreversible(JSONObject().put("type", "scroll"), screen, "com.whatsapp"))
        val steps = JSONObject().put("type", "steps").put("steps", org.json.JSONArray().put(JSONObject().put("type", "click").put("id", 5)))
        assertTrue(AutoSafety.irreversible(steps, screen, "com.whatsapp"))
    }

    @Test fun yesNoAnswers() {
        assertTrue(AutoSafety.yes("हाँ"))
        assertTrue(AutoSafety.yes("हाँ भेज दो"))
        assertTrue(AutoSafety.yes("ok"))
        assertFalse(AutoSafety.yes("नहीं"))
        assertFalse(AutoSafety.yes("मत भेजो"))
        assertFalse(AutoSafety.yes("हाँ नहीं रुको"))
        assertFalse(AutoSafety.yes(null))
        assertFalse(AutoSafety.yes("  "))
    }

    @Test fun stuckStopsAfterRepeats() {
        val s = AutoSafety.Stuck()
        assertFalse(s.note("a", true)); assertFalse(s.note("b", true)); assertTrue(s.note("c", true))
        val s2 = AutoSafety.Stuck()
        assertFalse(s2.note("x", false)); assertFalse(s2.note("x", false)); assertTrue(s2.note("x", false))
        val s3 = AutoSafety.Stuck()
        assertFalse(s3.note(AutoSafety.sig(JSONObject().put("type", "scroll"), 1), false))
        assertFalse(s3.note(AutoSafety.sig(JSONObject().put("type", "scroll"), 2), false))
        assertFalse(s3.note(AutoSafety.sig(JSONObject().put("type", "scroll"), 3), false))
    }

    @Test fun logNeverHasKeys() {
        val t = AutoText.clean("key AIzaSyA1234567890abcdefghijKLMNOP और gsk_abcdefghijk12345 token ghp_abcdefghijklmnop1234 OTP is 482913 card 4111 1111 1111 1111")
        assertFalse(t.contains("AIzaSy")); assertFalse(t.contains("gsk_abc")); assertFalse(t.contains("ghp_"))
        assertFalse(t.contains("482913")); assertFalse(t.contains("4111"))
        assertEquals("स्क्रीन पर पासवर्ड वाली जगह है", AutoText.clean("स्क्रीन पर पासवर्ड वाली जगह है"))
    }

    @Test fun describeIsReadable() {
        val screen = "[5] ImageButton (Send) {click} @980,2200"
        assertTrue(AutoSafety.describe(JSONObject().put("type", "click").put("id", 5), screen).contains("Send"))
        assertTrue(AutoSafety.STOP_WORDS.contains("इमरजेंसी स्टॉप"))
    }
}
