package com.dk.assistant

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** आवाज़ से फ़ोन लॉक / अनलॉक: कौन-सा वाक्य क्या करे (Android के बिना) */
class PhoneLockVoiceTest {
    private val L = PhoneLockVoice.Act.LOCK
    private val U = PhoneLockVoice.Act.UNLOCK
    private fun cfg(lockOn: Boolean = true, unlockOn: Boolean = true, extraUnlock: String = "") = PhoneLockVoice.Cfg(
        lockOn = lockOn, unlockOn = unlockOn,
        names = PhoneLockVoice.list(PhoneLockVoice.DEFAULT_NAMES + ", मायरा, जारवी, श्री, Google, डी के"),
        lock = PhoneLockVoice.list(PhoneLockVoice.DEFAULT_LOCK),
        unlock = PhoneLockVoice.list(PhoneLockVoice.DEFAULT_UNLOCK + extraUnlock),
    )
    private fun m(t: String, needName: Boolean = true, c: PhoneLockVoice.Cfg = cfg()) = PhoneLockVoice.match(t, needName, c)

    @After fun reset() { PhoneLockVoice.cfg = PhoneLockVoice.Cfg() }

    @Test fun ownerExamplesWork() {
        assertEquals(L, m("हे DK फोन लॉक करो"))
        assertEquals(U, m("हे मायरा फोन अनलॉक करो"))
        assertEquals(U, m("हे जारवी फ़ोन अनलॉक करो"))          // फ़ / फ
        assertEquals(L, m("हे श्री फोन लॉक करो"))
        assertEquals(U, m("हे Google अनलॉक करो"))
        assertEquals(U, m("डीके अनलॉक कर दो"))
        assertEquals(L, m("डीके, फोन लॉक कर दो।"))
        assertEquals(U, m("DK unlock"))
        assertEquals(L, m("Hey DK phone lock karo"))
        assertEquals(U, m("ओके डी के फोन अनलॉक करो ना"))
        assertEquals(L, m("हे मायरा जल्दी से मेरा फोन लॉक कर दो"))
    }

    @Test fun nameNeededWhenAlwaysListening() {
        assertNull(m("फोन लॉक करो"))
        assertNull(m("फोन अनलॉक करो"))
        assertNull(m("हे राहुल फोन अनलॉक करो"))                 // यह नाम सूची में नहीं
        // DK पहले से बुलाया गया (LocalRouter) - तब नाम ज़रूरी नहीं
        assertEquals(L, m("फोन लॉक करो", needName = false))
        assertEquals(U, m("मायरा फोन अनलॉक करो", needName = false))
    }

    @Test fun refusalsAndOtherTalkDoNothing() {
        assertNull(m("DK फोन लॉक मत करो"))
        assertNull(m("मायरा फोन अनलॉक नहीं करना"))
        assertNull(m("DK don't lock the phone"))
        assertNull(m("DK राहुल को बोलो कि फोन लॉक करो"))      // लंबी बात
        assertNull(m("DK व्हाट्सएप लॉक करो"))                  // ऐप का लॉक, फ़ोन नहीं
        assertNull(m("DK स्क्रीन लॉक का टाइम बढ़ाओ"))
        assertNull(m("DK"))
        assertNull(m(""))
    }

    @Test fun switchesRespected() {
        assertNull(m("हे DK फोन लॉक करो", c = cfg(lockOn = false)))
        assertNull(m("हे DK फोन अनलॉक करो", c = cfg(unlockOn = false)))
        assertNull(m("हे DK अनलॉक करो", c = cfg(unlockOn = false)))   // "अनलॉक करो" में "लॉक करो" - फिर भी लॉक नहीं
        assertNull(m("हे DK फोन लॉक करो", c = PhoneLockVoice.Cfg()))
    }

    @Test fun ownSentence() {
        val c = cfg(extraUnlock = ", खुल जा सिम सिम")
        assertEquals(U, m("हे मायरा खुल जा सिम सिम", c = c))
        assertNull(m("खुल जा सिम सिम", c = c))
    }

    @Test fun routerUsesOwnersSentences() {
        PhoneLockVoice.cfg = cfg(extraUnlock = ", खुल जा सिम सिम")
        assertEquals("unlock", LocalRouter.route("खुल जा सिम सिम", listOf("dk"))?.action?.optString("type"))
        assertEquals("lock", LocalRouter.route("मायरा फोन लॉक कर दो", listOf("dk"))?.action?.optString("type"))
        PhoneLockVoice.cfg = PhoneLockVoice.Cfg()
        assertNull(LocalRouter.route("खुल जा सिम सिम", listOf("dk")))
    }

    @Test fun failIsNeverReportedAsDone() {
        val r = PhoneLockVoice.UNLOCK_FAIL + " PIN डालने पर भी फ़ोन नहीं खुला।"
        assertFalse(LiveFast.ok("unlock", r))
        assertEquals("PIN डालने पर भी फ़ोन नहीं खुला।", LocalRouter.userText(r))
        assertTrue(LocalRouter.final(r))
        assertTrue(LiveFast.quickResult(PhoneLockVoice.quick(U), r, already = false).startsWith("FAILED"))
    }

    @Test fun secretShapes() {
        assertTrue(PhoneLockVoice.validPin("1234")); assertTrue(PhoneLockVoice.validPin("0000123456"))
        assertFalse(PhoneLockVoice.validPin("123")); assertFalse(PhoneLockVoice.validPin("12a4"))
        assertTrue(PhoneLockVoice.validPattern("14789")); assertTrue(PhoneLockVoice.validPattern("123456789"))
        assertFalse(PhoneLockVoice.validPattern("147")); assertFalse(PhoneLockVoice.validPattern("1471")); assertFalse(PhoneLockVoice.validPattern("1230"))
        assertTrue(PhoneLockVoice.validPassword("abcd")); assertFalse(PhoneLockVoice.validPassword("ab"))
    }

    @Test fun patternMiddleDot() {
        assertEquals('2', PhoneLockVoice.between('1', '3'))
        assertEquals('5', PhoneLockVoice.between('1', '9'))
        assertEquals('5', PhoneLockVoice.between('3', '7'))
        assertEquals('4', PhoneLockVoice.between('1', '7'))
        assertEquals('5', PhoneLockVoice.between('4', '6'))
        assertEquals('5', PhoneLockVoice.between('8', '2'))
        assertNull(PhoneLockVoice.between('1', '2'))
        assertNull(PhoneLockVoice.between('1', '6'))
        assertNull(PhoneLockVoice.between('5', '9'))
    }

    @Test fun patternCells() {
        assertEquals(Pair(50f, 50f), PhoneLockVoice.cell('1', 0, 0, 300, 300))
        assertEquals(Pair(150f, 150f), PhoneLockVoice.cell('5', 0, 0, 300, 300))
        assertEquals(Pair(350f, 1250f), PhoneLockVoice.cell('9', 100, 1000, 400, 1300))
    }

    @Test fun lockScreenKeys() {
        assertEquals('5', PhoneLockVoice.digitKey("com.android.systemui:id/key5", null, null))
        assertEquals('0', PhoneLockVoice.digitKey("com.android.systemui:id/key0", "0", null))
        assertEquals('7', PhoneLockVoice.digitKey(null, "7", null))
        assertEquals('2', PhoneLockVoice.digitKey(null, null, "2 ABC"))
        assertEquals('5', PhoneLockVoice.digitKey(null, "५", null))
        assertNull(PhoneLockVoice.digitKey(null, "10:45", null))
        assertNull(PhoneLockVoice.digitKey(null, "12", null))
        assertNull(PhoneLockVoice.digitKey("com.android.systemui:id/key_enter", null, "Enter"))
        assertTrue(PhoneLockVoice.enterKey("com.android.systemui:id/key_enter", null, null))
        assertTrue(PhoneLockVoice.enterKey(null, null, "Enter"))
        assertTrue(PhoneLockVoice.enterKey(null, "ठीक है", null))
        assertFalse(PhoneLockVoice.enterKey("com.android.systemui:id/delete_button", null, "Delete"))
        assertTrue(PhoneLockVoice.patternView("com.android.internal.widget.LockPatternView", null))
        assertTrue(PhoneLockVoice.patternView("android.view.View", "com.android.systemui:id/lockPatternView"))
        assertFalse(PhoneLockVoice.patternView("android.widget.TextView", "com.android.systemui:id/clock"))
    }

    @Test fun pinIsSecretSetting() {
        assertTrue(SecretNames.isSecret(PhoneLockStore.K_SECRET))
    }
}
