package com.dk.assistant

import com.dk.assistant.dkai.ChatSpeak
import com.dk.assistant.dkai.ScanPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 23 नवंबर 2026 (मालिक): Chat का "बोलकर भी" toggle फिर से, बनता जवाब blink न करे,
 * और स्कैन कभी पुरानी आवाज़ वाली विंडो में न खुले (DK जुड़ा हो या नहीं).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ChatFixesNov23Test {
    private fun src(p: String) = File("src/main/java/com/dk/assistant/$p").readText()

    // ---------------------------------------------------------------- 1) "बोलकर भी"
    @Test fun speakToggleIsOffByDefaultAndRemembers() {
        val c = RuntimeEnvironment.getApplication()
        assertFalse("बाय डिफ़ॉल्ट बंद", ChatSpeak.on(c))
        ChatSpeak.set(c, true)
        assertTrue(ChatSpeak.on(c))
        // ChatActivity वाली ही चाबी - एक जगह बदला = दोनों जगह वही
        assertTrue(c.getSharedPreferences("dk_prefs", 0).getBoolean("chat_speak", false))
        ChatSpeak.set(c, false)
        assertFalse(ChatSpeak.on(c))
    }

    @Test fun speakableText() {
        assertEquals("नमस्ते बॉस", ChatSpeak.speakable("**नमस्ते** बॉस"))
        assertNull(ChatSpeak.speakable("   "))
        assertNull(ChatSpeak.speakable("…"))
    }

    @Test fun chatTabHasToggleAndUsesIt() {
        val d = src("dkai/DkaiScreen.kt")
        assertTrue(d.contains("text = ChatSpeak.LABEL"))
        assertEquals("बोलकर भी", ChatSpeak.LABEL)
        assertTrue(d.contains("headF.addView(speakSw"))
        // सिर्फ़ Chat टैब पर दिखे
        assertTrue(d.contains("DkaiTheme.gone(sw, t != com.dk.assistant.ui.HomeTabs.Tab.CHAT)"))
        // लिखा हुआ = toggle के हिसाब से बोलना; DK लाइव से आई बात (chatFromLive) = बोलना नहीं (Live खुद बोलती है)
        assertTrue(d.contains("return sendChat(t, speak = ChatSpeak.on(c))"))
        assertTrue(d.contains("svc.runCommand(t, speak = speak, typed = speak)"))
        assertTrue(d.contains("fun chatFromLive(t: String): Boolean { peek(); return sendChat(t) }"))
        assertTrue(d.contains("if (speak) ChatSpeak.sayIfOn(c, out)"))
        // बिना फ़ोन-कंट्रोल वाले रास्ते पर भी फ़ोन की ऑफ़लाइन आवाज़
        assertTrue(src("dkai/ChatSpeak.kt").contains("com.dk.assistant.PhoneVoice.say(app, s)"))
        assertTrue(src("ChatActivity.kt").contains("com.dk.assistant.dkai.ChatSpeak.sayIfOn(this@ChatActivity, out)"))
    }

    // ---------------------------------------------------------------- 2) blink नहीं
    @Test fun liveReplyUpdatesOneBubbleOnly() {
        val d = src("dkai/DkaiScreen.kt")
        val body = d.substringAfter("private fun renderChat()").substringBefore("private fun shrinkIn")
        val fast = body.substringBefore("chatList.removeAllViews()")
        // सिर्फ़ बनता जवाब बदला = वही बुलबुला, पूरी सूची नहीं
        assertTrue(fast.contains("if (sig == shownMsgs && liveR.isNotBlank() && tail != null && tail.parent === chatList)"))
        assertTrue(fast.contains("tail.text = com.dk.assistant.GroundView.decorate(c, liveR, null, DkaiTheme.WHITE)"))
        assertTrue(fast.contains("keepChatEnd()") && fast.contains("return"))
        assertTrue(body.contains("also { liveTail = it }"))
        // बिना animation वाला नीचे टिकना
        val keep = d.substringAfter("private fun keepChatEnd()").substringBefore("\n    }\n")
        assertTrue(keep.contains("sc.scrollTo(0, bottom)"))
        assertFalse(keep.contains("smoothScrollTo"))
    }

    // ---------------------------------------------------------------- 3) स्कैन = हमेशा नया
    @Test fun scanNeverOpensOldWindow() {
        assertFalse("कोई स्कैन टैब खुला नहीं", ScanPanel.onScreen())
        assertFalse(ScanPanel.again(false))
        val d = src("dkai/DkaiScreen.kt")
        assertFalse(d.contains("VoiceActivity.WANT_CAMERA"))
        assertFalse(d.contains("openLens("))
        assertTrue(d.contains("scanPanel?.leaveTab()"))
        assertTrue(src("LiveVision.kt").contains("!VoiceActivity.visible && !com.dk.assistant.dkai.ScanPanel.onScreen()"))
        val yt = src("DKAccessibilityYt.kt")
        // 23 नवंबर 2026 (मालिक: "DK लेंस ओपन करो" बोलने पर भी पुरानी विंडो): टैब खुला = वहीं, वरना होम का स्कैन टैब - आवाज़ स्क्रीन कभी नहीं
        assertTrue(yt.indexOf("ScanPanel.again(front)") in 1 until yt.indexOf("ScanPanel.openHome(this, front)"))
        assertFalse(yt.contains("VoiceActivity.WANT_CAMERA"))
        val lv = src("LiveVision.kt")
        assertTrue(lv.contains("com.dk.assistant.dkai.ScanPanel.openHome(c, front)"))
        assertFalse(lv.contains("VoiceActivity::class.java"))
        val sp = src("dkai/ScanPanel.kt")
        assertTrue(sp.contains("a.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)"))
        // Chat स्क्रीन से स्कैन = होम का वही नया स्कैन टैब
        assertTrue(src("ChatActivity.kt").contains(".putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SCAN)"))
        val m = src("MainActivity.kt")
        assertTrue(m.contains("else { d.scanFront = front; d.showTab(com.dk.assistant.ui.HomeTabs.Tab.SCAN) }"))
        assertTrue(d.contains("scanPanel?.show(front = f, userTap = true)"))
        assertTrue(m.contains("openPendingTab()"))
    }

    @Test fun voiceLensOpensHomeScanTab() {
        val c = org.robolectric.RuntimeEnvironment.getApplication()
        val i = ScanPanel.homeIntent(c, true)
        assertEquals(com.dk.assistant.MainActivity::class.java.name, i.component?.className)
        assertEquals(com.dk.assistant.MainActivity.TAB_SCAN, i.getStringExtra(com.dk.assistant.MainActivity.EXTRA_TAB))
        assertTrue(i.getBooleanExtra(com.dk.assistant.MainActivity.EXTRA_FRONT, false))
        assertFalse(ScanPanel.homeIntent(c, false).getBooleanExtra(com.dk.assistant.MainActivity.EXTRA_FRONT, true))
        assertTrue(i.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(i.flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }
}
