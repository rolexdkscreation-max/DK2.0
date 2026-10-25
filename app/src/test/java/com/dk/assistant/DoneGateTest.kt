package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.67: काम होने से पहले "हो गया" नहीं बजता; फ़ोटो मिटाना = सिर्फ़ "आखिरी" वाली, Android की Allow के साथ */
class DoneGateTest {
    @Test fun actionVsQuestion() {
        assertTrue(DoneGate.isAction("यूट्यूब पर गाना चलाओ"))
        assertTrue(DoneGate.isAction("राहुल को मैसेज भेजो"))
        assertTrue(DoneGate.isAction("आखिरी फोटो डिलीट करो"))
        assertFalse(DoneGate.isAction("क्या तुमने भेजा?"))
        assertFalse(DoneGate.isAction("आज मौसम कैसा है"))
        assertFalse(DoneGate.isAction("तुम्हारा नाम बताओ यार"))
    }

    @Test fun claimBeforeToolIsDropped() {
        assertEquals(DoneGate.Verdict.DROP, DoneGate.beforeTool("हो गया बॉस, चला दिया"))
        assertEquals(DoneGate.Verdict.PLAY, DoneGate.beforeTool("ठीक है बॉस"))
        assertEquals(DoneGate.Verdict.DROP, DoneGate.decide("हाँ बॉस, कर दिया हूँ", evidence = false))
        assertEquals(DoneGate.Verdict.PLAY, DoneGate.decide("हाँ बॉस, कर दिया हूँ", evidence = true))
        assertEquals(DoneGate.Verdict.PLAY, DoneGate.decide("ठीक है बॉस, देखता हूँ", evidence = false))
    }

    @Test fun standaloneHoGaya() {
        assertTrue(HonestGuard.claimsDone("हो गया"))
        assertTrue(HonestGuard.claimsDone("हो गया बॉस"))
        assertFalse(HonestGuard.claimsDone("क्या हो गया?"))
    }

    @Test fun startedIsNotDone() {
        assertTrue(DoneGate.notDoneYet("{\"status\":\"started\",\"task_id\":\"social-1\"}"))
        assertTrue(DoneGate.notDoneYet("{\"status\":\"error\",\"error\":\"busy\"}"))
        assertTrue(DoneGate.notDoneYet("STILL RUNNING in the background - it is NOT finished yet."))
        assertTrue(DoneGate.notDoneYet("FAILED: could not do it"))
        assertFalse(DoneGate.notDoneYet("DONE: playing \"x\" on YouTube. Phone says: \"चल रहा है\"."))
    }

    private fun act(t: String) = LocalRouter.deleteMedia(t)?.action

    @Test fun deleteLastPhoto() {
        val a = act("आखिरी फोटो डिलीट करो")!!
        assertEquals("delete_media", a.optString("type")); assertEquals("photo", a.optString("kind")); assertEquals(1, a.optInt("count"))
        val b = act("आखिरी दो फोटो डिलीट कर दो")!!
        assertEquals(2, b.optInt("count"))
        assertEquals(1, act("last photo delete kar do")!!.optInt("count"))           // "do" = कर दो, गिनती नहीं
        val c = act("last 3 screenshot delete karo")!!
        assertEquals("screenshot", c.optString("kind")); assertEquals(3, c.optInt("count"))
    }

    @Test fun vagueDeleteNotLocal() {
        assertNull(act("सारी फोटो डिलीट करो"))
        assertNull(act("फोटो डिलीट करो"))                        // कौन-सी? -> AI पूछे
        assertNull(act("आखिरी फोटो डिलीट मत करो"))
        assertNull(act("last 50 photo delete karo"))
        // तुरंत वाला रास्ता (बात खत्म होते ही) कभी नहीं मिटाता
        assertNull(LocalRouter.route("आखिरी फोटो डिलीट करो", listOf("डीके"), ""))
        assertNull(LiveFast.pick("आखिरी फोटो डिलीट करो", listOf("डीके"), "", fast = true))
    }

    @Test fun deleteResultsAreHonest() {
        assertTrue(LiveFast.ok("delete_media", "DELETED: आखिरी फ़ोटो मिटा दी"))
        assertFalse(LiveFast.ok("delete_media", "ERROR_DENIED: आपने Allow नहीं दबाया"))
        assertFalse(LiveFast.ok("delete_media", "DELETE_UNVERIFIED: पक्का नहीं"))
        assertTrue(LocalRouter.final("ERROR_DENIED: x"))
    }
}
