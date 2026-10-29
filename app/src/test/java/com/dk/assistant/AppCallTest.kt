package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v4: WhatsApp / Instagram / Facebook / Messenger की आने वाली कॉल पहचानना (notification से) */
class AppCallTest {
    @Test fun callNotification_detected_forAnyApp() {
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b", "com.instagram.android", "com.facebook.katana", "com.facebook.orca", "org.telegram.messenger"))
            assertTrue(pkg, AppCall.isCall(pkg, "call", false, true, listOf("Decline", "Answer")))
        assertTrue(AppCall.isCall("com.whatsapp", null, true, false, emptyList()))                    // CallStyle
        assertTrue(AppCall.isCall("com.instagram.android", null, false, true, listOf("Decline", "Answer")))  // category नहीं, पर पूरी-स्क्रीन + बटन
    }

    @Test fun simCall_andNormalNotif_notThis() {
        assertFalse(AppCall.isCall("com.google.android.dialer", "call", false, true, listOf("Decline", "Answer")))
        assertFalse(AppCall.isCall("com.samsung.android.incallui", "call", false, true, listOf("Decline", "Answer")))
        assertFalse(AppCall.isCall("com.whatsapp", "msg", false, false, listOf("Reply", "Mark as read")))
        assertFalse(AppCall.isCall("com.whatsapp", null, false, true, listOf("Reply")))
    }

    @Test fun buttons() {
        assertEquals(1, AppCall.pickAnswer(listOf("Decline", "Answer")))
        assertEquals(0, AppCall.pickDecline(listOf("Decline", "Answer")))
        assertEquals(1, AppCall.pickAnswer(listOf("अस्वीकार करें", "उत्तर दें")))
        assertEquals(0, AppCall.pickDecline(listOf("अस्वीकार करें", "उत्तर दें")))
        assertEquals(1, AppCall.pickAnswer(listOf("Reject", "Accept")))
        assertEquals(0, AppCall.pickHangup(listOf("Hang up", "Speaker")))
        assertEquals(-1, AppCall.pickAnswer(listOf("Hang up", "Mute")))
        assertEquals(-1, AppCall.pickAnswer(listOf("Reply")))
    }

    @Test fun whoAndVideo() {
        assertEquals("Rahul", AppCall.caller("Rahul", "Incoming voice call", "WhatsApp"))
        assertEquals("Priya", AppCall.caller("Instagram", "Priya is calling you…", "Instagram"))
        assertEquals("Amit", AppCall.caller("Messenger", "Amit is video calling you", "Messenger"))
        assertEquals("", AppCall.caller("Incoming call", "", "Facebook"))
        assertTrue(AppCall.isVideo("Rahul", "Incoming video call"))
        assertTrue(AppCall.isVideo("राहुल", "वीडियो कॉल आ रहा है"))
        assertFalse(AppCall.isVideo("Rahul", "Incoming voice call"))
    }

    @Test fun ask_text() {
        assertEquals("WhatsApp पर Rahul का वीडियो कॉल आ रहा है। उठाऊँ या काट दूँ?", AppCall.announce("WhatsApp", "Rahul", true))
        assertEquals("Instagram पर किसी का कॉल आ रहा है। उठाऊँ या काट दूँ?", AppCall.announce("Instagram", "", false))
    }

    @Test fun noCall_nothingActive() {
        AppCall.reset()
        assertFalse(AppCall.ringing()); assertFalse(AppCall.ongoing()); assertEquals("", AppCall.who())
    }

    @Test fun wired_in() {
        fun src(n: String) = listOf(java.io.File("src/main/java/com/dk/assistant/$n"), java.io.File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()
        assertTrue(src("DKNotifListener.kt").contains("AppCall.posted(") && src("DKNotifListener.kt").contains("AppCall.removed("))
        val cm = src("CallManager.kt")
        assertTrue(cm.contains("val ringing get() = simRinging || AppCall.ringing()"))
        assertTrue(cm.contains("AppCall.answer(ctx)") && cm.contains("AppCall.end(ctx)"))
        assertTrue(src("DKAccessibilityService.kt").contains("fun onAppCall("))
    }
}
