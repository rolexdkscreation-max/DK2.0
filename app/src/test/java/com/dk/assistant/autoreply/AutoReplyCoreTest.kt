package com.dk.assistant.autoreply

import com.dk.assistant.SetSrc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक): सेटिंग → ऑटो रिप्लाई - फ़ोन के मैसेज ऐप, हर ऐप का स्विच, मालिक के ढंग में सिर्फ़ उसी बात का जवाब */
class AutoReplyCoreTest {
    private val C = AutoReplyCore
    private fun d(master: Boolean = true, app: Boolean = true, pkg: String = "com.whatsapp", text: String = "Kal aa rahe ho?", mine: Boolean = false,
                  group: Boolean = false, reply: Boolean = true, busy: Boolean = false, st: AutoReplyCore.ChatState? = null, now: Long = 100_000L) =
        C.decide(master, app, "com.dk.assistant", pkg, text, mine, group, reply, busy, st, now)

    @Test fun mainMessagingApps_known() {
        val pk = C.APPS.map { it.pkg }
        for (p in listOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.facebook.orca", "com.instagram.android",
                "com.google.android.apps.messaging", "org.thoughtcrime.securesms")) assertTrue(p, p in pk)
        assertEquals("WhatsApp Business", C.known("com.whatsapp.w4b")?.name)
        assertEquals(pk.size, pk.toSet().size)
    }

    @Test fun onlyInstalledAppsShown_plusDiscovered_neverOwn() {
        val inst = setOf("com.whatsapp", "org.telegram.messenger", "com.new.chat", "com.dk.assistant")
        val l = C.available({ it in inst }, listOf(AutoReplyCore.App("com.new.chat", "New Chat"), AutoReplyCore.App("com.gone", "Gone"),
            AutoReplyCore.App("com.dk.assistant", "DK")), "com.dk.assistant")
        assertEquals(listOf("com.whatsapp", "org.telegram.messenger", "com.new.chat"), l.map { it.pkg })
    }

    @Test fun repliesOnlyWhenEverythingOn() {
        assertEquals(AutoReplyCore.Decision.Reply, d())
        assertTrue(d(master = false) is AutoReplyCore.Decision.Skip)
        assertTrue(d(app = false) is AutoReplyCore.Decision.Skip)          // ऐप का स्विच बंद = कुछ नहीं
        assertTrue(d(pkg = "com.dk.assistant") is AutoReplyCore.Decision.Skip)
        assertTrue(d(group = true) is AutoReplyCore.Decision.Skip)
        assertTrue(d(mine = true) is AutoReplyCore.Decision.Skip)
        assertTrue(d(reply = false) is AutoReplyCore.Decision.Skip)
        assertTrue(d(text = "  ") is AutoReplyCore.Decision.Skip)
    }

    @Test fun whatsappAutoChatBusy_noDoubleReply() {
        assertEquals(AutoReplyCore.Decision.Skip("other mode busy"), d(busy = true))
    }

    @Test fun moneyOtp_askOwner() {
        assertEquals(AutoReplyCore.Decision.AskOwner, d(text = "Bhai 500 paise bhej do"))
        assertEquals(AutoReplyCore.Decision.AskOwner, d(text = "OTP batao jaldi"))
    }

    @Test fun sameMessageOnce_gap_and_hourLimit() {
        val st = AutoReplyCore.ChatState()
        C.sent(st, "Kal aa rahe ho?", 100_000L)
        assertEquals(AutoReplyCore.Decision.Skip("already answered"), d(st = st, now = 200_000L))
        assertEquals(AutoReplyCore.Decision.Skip("too soon"), d(text = "Aur?", st = st, now = 102_000L))
        assertEquals(AutoReplyCore.Decision.Reply, d(text = "Aur?", st = st, now = 120_000L))
        repeat(AutoReplyCore.MAX_PER_HOUR) { C.sent(st, "m$it", 130_000L + it) }
        assertEquals(AutoReplyCore.Decision.Skip("hour limit"), d(text = "naya", st = st, now = 400_000L))
        assertEquals(AutoReplyCore.Decision.Reply, d(text = "naya", st = st, now = 130_000L + 3_700_000L))
    }

    @Test fun ownerStyle_learned_newestFirst_noDupes_noPrivate() {
        var s = C.addStyle(emptyList(), listOf("haan bhai", "ok", "OTP 123456 hai"))
        assertEquals(listOf("ok", "haan bhai"), s)
        s = C.addStyle(s, listOf("aa raha hu 10 min me", "ok"))
        assertEquals(listOf("ok", "aa raha hu 10 min me", "haan bhai"), s)
        assertEquals(30, C.addStyle(emptyList(), (1..50).map { "msg $it" }).size)
    }

    @Test fun systemPrompt_ownerStyle_onlyWhatAsked_neverInvent() {
        val sys = C.system("Telegram", listOf("haan bhai aa raha hu", "thik h"))
        assertTrue(sys.contains("Telegram"))
        assertTrue(sys.contains("haan bhai aa raha hu") && sys.contains("owner's own texting style"))
        assertTrue(sys.contains("ONLY what the other person last said or asked"))
        assertTrue(sys.contains("Never invent"))
        assertTrue(sys.contains("never start a new topic"))
        assertTrue(C.system("WhatsApp", emptyList()).contains("Hinglish"))
        val p = C.prompt("Ramesh", listOf(AutoReplyCore.Msg("kab aaoge", false), AutoReplyCore.Msg("shaam ko", true), AutoReplyCore.Msg("kitne baje?", false)))
        assertTrue(p.contains("Ramesh: kab aaoge") && p.contains("Me: shaam ko") && p.trim().endsWith("last message only."))
    }

    @Test fun clean_and_unsafe() {
        assertEquals("Haan aa raha hu", C.clean("Reply: \"Haan aa raha hu\""))
        assertNull(C.clean("  "))
        assertNull(C.clean("As an AI language model I cannot"))
        assertTrue(C.unsafe("mera pin 4455 hai") && C.unsafe("card 4111 1111 1111"))
        assertFalse(C.unsafe("5 baje milte hain"))
    }

    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    @Test fun wiring_notification_settings_search() {
        assertTrue(src("DKNotifListener.kt").contains("com.dk.assistant.autoreply.AutoReply.onPosted(this, sbn.key, sbn.packageName, title.trim(), ex, r != null)"))
        val s = SetSrc.all()
        assertTrue(s.contains("\"autoreply\" -> { title.text = \"ऑटो रिप्लाई\"; secAutoReply() }"))
        assertTrue(s.contains("Triple(\"autoreply\", \"ऑटो रिप्लाई\""))
        assertTrue(s.contains("AutoReply.apps(this)") && s.contains("AutoReply.setApp(this, a.pkg, on)"))
        assertTrue(com.dk.assistant.SettingsSearch.CATEGORIES.first { it.first.first == "cat_auto" }.second.contains("autoreply"))
        assertEquals("autoreply", com.dk.assistant.SettingsSearch.search("ऑटो रिप्लाई").first().section)
        assertTrue(com.dk.assistant.SettingsSearch.search("telegram").any { it.section == "autoreply" })
        // जवाब notification से - कोई ऐप नहीं खुलता, स्क्रीन नहीं छीनता
        val a = src("autoreply/AutoReply.kt")
        assertTrue(a.contains("DKNotifListener.reply(c, key, text)"))
        assertFalse(a.contains("AppLauncher") || a.contains("performGlobalAction"))
        assertTrue(a.contains("WaAutoChat.active()"))
    }
}
