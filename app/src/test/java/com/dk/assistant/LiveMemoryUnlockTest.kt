package com.dk.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v3.151 (मालिक): DK लाइव बातचीत में बताई बात भूल जाता था -
 * "धीरज कुमार का मैसेज करो" -> "क्या मैसेज करना है?" -> "उसे कल घूमने जाना है, चलो करके मैसेज करना" -> फिर "कॉन्टैक्ट का नाम बताओ"।
 * और "DK फोन अनलॉक करो" पर फ़ोन नहीं खुलता था (PIN सेव न हो तो भी "हो गया" गिना जाता था)।
 */
class LiveMemoryUnlockTest {
    private val now = 10_000_000L
    private fun u(t: String, ago: Long = 0) = MsgDraft.Turn("u", t, now - ago)
    private fun d(t: String, ago: Long = 0) = MsgDraft.Turn("d", t, now - ago)

    private val chat = listOf(
        u("धीरज कुमार का मैसेज करो", 30_000),
        d("धीरज कुमार को क्या मैसेज करना है?", 25_000),
        u("उसे कल घूमने जाना है, चलो करके मैसेज करना", 10_000),
    )

    @Test fun contactFromSpokenRequest() {
        assertEquals("धीरज कुमार", MsgDraft.contactOf("धीरज कुमार का मैसेज करो"))
        assertEquals("धीरज कुमार", MsgDraft.contactOf("डीके, धीरज कुमार को मैसेज करो"))
        assertEquals("धीरज", MsgDraft.contactOf("धीरज को व्हाट्सएप पर मैसेज भेजो"))
        assertEquals("", MsgDraft.contactOf("उसे कल घूमने जाना है, चलो करके मैसेज करना"))
        assertEquals("", MsgDraft.contactOf("आज मौसम कैसा है"))
    }

    @Test fun pronounIsNotAName() {
        assertTrue(MsgDraft.isPronoun(""))
        assertTrue(MsgDraft.isPronoun("उसे"))
        assertTrue(MsgDraft.isPronoun("him"))
        assertFalse(MsgDraft.isPronoun("धीरज कुमार"))
    }

    @Test fun messageCleaned() {
        assertEquals("कल घूमने जाना है, चलो", MsgDraft.cleanMessage("उसे कल घूमने जाना है, चलो करके मैसेज करना"))
        assertEquals("मैं आ रहा हूँ", MsgDraft.cleanMessage("बोलो कि मैं आ रहा हूँ"))
    }

    /** मालिक का असली मामला: मॉडल ने दूसरी बार नाम खाली भेजा -> बातचीत से धीरज कुमार */
    @Test fun ownerCase_contactRemembered() {
        val f = MsgDraft.fill("", "कल घूमने जाना है, चलो", chat, now)
        assertEquals("धीरज कुमार", f.contact)
        assertEquals("कल घूमने जाना है, चलो", f.message)
        assertTrue(f.fromHistory)
        assertNull(MsgDraft.missingNote(f))
    }

    @Test fun pronounContactAndEmptyMessage_bothFromChat() {
        val f = MsgDraft.fill("उसे", "", chat, now)
        assertEquals("धीरज कुमार", f.contact)
        assertEquals("कल घूमने जाना है, चलो", f.message)
    }

    @Test fun modelValuesKept() {
        val f = MsgDraft.fill("राहुल", "हेलो", chat, now)
        assertEquals("राहुल", f.contact); assertEquals("हेलो", f.message); assertFalse(f.fromHistory)
    }

    @Test fun oldChatNotUsed() {
        val old = listOf(u("धीरज कुमार का मैसेज करो", MsgDraft.WINDOW_MS + 60_000))
        assertEquals("", MsgDraft.fill("", "हेलो", old, now).contact)
    }

    @Test fun missingNote_neverAsksKnownThingAgain() {
        val n = MsgDraft.missingNote(MsgDraft.Filled("धीरज कुमार", "", true))!!
        assertTrue(n.contains("ALREADY KNOWN") && n.contains("धीरज कुमार") && n.contains("ONLY the message text"))
        assertTrue(n.contains("do NOT ask for it again"))
    }

    @Test fun applyToArgs() {
        val a = JSONObject().put("action", "send_message").put("contact", "उसे").put("message", "कल घूमने चलें")
        MsgDraft.applyTo(a, chat, now)
        assertEquals("धीरज कुमार", a.getString("contact"))
    }

    @Test fun phoneTaskWithoutName_goesToWhatsAppWithRememberedName() {
        val r = MsgDraft.redirectPhoneTask("कल घूमने चलना है, मैसेज कर दो", chat, now)
        assertNotNull(r); assertTrue(r!!.contains("whatsapp_task") && r.contains("धीरज कुमार"))
        assertNull("मैसेज ऐप खोलना = मैसेज भेजना नहीं", MsgDraft.redirectPhoneTask("मैसेज ऐप खोलो", chat, now))
        assertNull("SMS अलग", MsgDraft.redirectPhoneTask("उसे SMS मैसेज कर दो", chat, now))
        assertNull("बिना हाल की बातचीत कुछ नहीं", MsgDraft.redirectPhoneTask("मैसेज कर दो", emptyList(), now))
    }

    @Test fun newConnectionGetsThisConversation() {
        val b = MsgDraft.recentBlock(chat, now)
        assertTrue(b.contains("धीरज कुमार का मैसेज करो") && b.contains("never ask again"))
        assertEquals("", MsgDraft.recentBlock(emptyList(), now))
    }

    // ---------------------------------------------------------------- अनलॉक
    @Test fun noPin_isFailureWithSettingsHelp_notDone() {
        val r = PhoneLockVoice.noPinReason(unlockOn = false, hasSecret = false)
        assertTrue(r.startsWith(PhoneLockVoice.UNLOCK_FAIL))
        assertTrue(r.contains("फ़ोन लॉक / अनलॉक") && r.contains("PIN"))
        assertFalse("पहले यह 'हो गया' गिना जाता था", LiveFast.ok("unlock", r))
        assertTrue(PhoneLockVoice.noPinReason(unlockOn = false, hasSecret = true).contains("चालू"))
    }

    @Test fun voiceUnlock_heardEvenWhenSettingsOff() {
        val off = PhoneLockVoice.Cfg()
        assertNull(PhoneLockVoice.match("डीके फोन अनलॉक करो", needName = true, c = off))
        assertEquals(PhoneLockVoice.Act.UNLOCK, PhoneLockVoice.instant("डीके फोन अनलॉक करो", listOf("डीके"), needName = true, c = off))
    }

    private fun src(name: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            listOf(File(dir, "src/main/java/com/dk/assistant/$name"), File(dir, "app/src/main/java/com/dk/assistant/$name"))
                .firstOrNull { it.exists() }?.let { return it.readText() }
            dir = dir.parentFile
        }
        error("$name नहीं मिला")
    }

    @Test fun wiring() {
        assertTrue(src("GeminiLiveTools.kt").contains("MsgDraft.applyTo(args, MsgDraft.turnsFromState(), System.currentTimeMillis())"))
        assertTrue(src("GeminiLiveQueue.kt").contains("MsgDraft.redirectPhoneTask(req, MsgDraft.turnsFromState(), System.currentTimeMillis())"))
        assertTrue(src("BrainManager.kt").contains("Follow-up answers"))
        assertTrue(src("BrainManager.kt").contains("MsgDraft.recentBlock(MsgDraft.turnsFromState()"))
        assertTrue(src("DKAccessibilityScreen.kt").contains("PhoneLockVoice.noPinReason("))
        assertTrue(src("LiveNet.kt").contains("PhoneLockStore.userUnlocked(ctx)"))
        assertTrue(src("DKAccessibilityService.kt").contains("?.takeIf { it == PhoneLockVoice.Act.UNLOCK || offlineNow }"))
        assertTrue(src("PhoneUnlocker.kt").contains("delay(SETTLE_MS)"))
    }
}
