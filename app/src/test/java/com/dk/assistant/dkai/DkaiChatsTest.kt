package com.dk.assistant.dkai

import com.dk.assistant.DKState
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.TimeZone

/** 13 नवंबर 2026 (मालिक): पुरानी चैट की सूची + Light mode में icons के असली रंग + होम से मोड-पंक्ति हटी */
class DkaiChatsTest {
    private fun src(p: String) = File("src/main/java/com/dk/assistant/$p").let { if (it.exists()) it else File("app/src/main/java/com/dk/assistant/$p") }.readText()
    private fun tmp(): File = Files.createTempDirectory("dkai_chats").toFile()
    private fun m(r: String, t: String, w: Long) = DKState.Msg(r, t, w)

    @Test fun savesAndLoadsWholeChat() {
        val d = tmp()
        val msgs = listOf(m("u", "मेरा ऐप बनाओ\nदूसरी लाइन", 1000), m("d", "ठीक है", 2000), DKState.Msg("d", "स्रोत", 3000, listOf("Wiki" to "https://w.org")))
        DkaiChats.save(d, 3, msgs)
        val back = DkaiChats.load(d, 3)
        assertEquals(msgs, back)                       // पूरी बातचीत (स्रोत भी) वापस
        assertEquals(emptyList<DKState.Msg>(), DkaiChats.load(d, 9))
        DkaiChats.save(d, 4, emptyList())              // खाली चैट फ़ाइल नहीं बनाती
        assertFalse(DkaiChats.file(d, 4).exists())
        assertFalse(File(d, "chat_3.json.tmp").exists())
    }

    @Test fun listNewestFirstWithCurrentFromScreen() {
        val d = tmp()
        DkaiChats.save(d, 1, listOf(m("u", "पहली चैट", 100)))
        DkaiChats.save(d, 2, listOf(m("u", "दूसरी", 200), m("d", "जवाब", 250)))
        DkaiChats.save(d, 5, listOf(m("u", "पुरानी पाँचवीं", 500)))
        // अभी चैट #5 खुली है - सूची में स्क्रीन वाली (ताज़ा) बातचीत
        val cur = listOf(m("u", "पुरानी पाँचवीं", 500), m("d", "नया जवाब", 900))
        val e = DkaiChats.entries(d, 5, cur, now = 1000)
        assertEquals(listOf(5, 2, 1), e.map { it.no })
        assertEquals(2, e[0].count); assertEquals(900L, e[0].time)
        assertEquals("दूसरी", e[1].title)
        // अभी वाली खाली चैट भी दिखे (सबसे ऊपर)
        val e2 = DkaiChats.entries(d, 6, emptyList(), now = 1234)
        assertEquals(6, e2.first().no); assertEquals("", e2.first().title); assertEquals(1234L, e2.first().time)
    }

    @Test fun newChatNumberNeverCollides() {
        val d = tmp()
        assertEquals(12, DkaiChats.nextNo(d, 11))
        DkaiChats.save(d, 11, listOf(m("u", "x", 1)))
        DkaiChats.save(d, 3, listOf(m("u", "y", 1)))
        assertEquals(12, DkaiChats.nextNo(d, 3))        // #3 खोलकर "+ New" = #12, #4 नहीं
    }

    @Test fun titleIsFirstLineOfFirstUserMessage() {
        assertEquals("मेरा ऐप बनाओ", DkaiChats.title(listOf(m("d", "नमस्ते", 1), m("u", "  मेरा ऐप बनाओ\nदूसरी", 2))))
        assertEquals("नमस्ते", DkaiChats.title(listOf(m("d", "नमस्ते", 1))))
        assertEquals("", DkaiChats.title(emptyList()))
        assertTrue(DkaiChats.title(listOf(m("u", "a".repeat(200), 1))).length <= 70)
    }

    @Test fun whenTextTodayYesterdayDate() {
        val tz = TimeZone.getTimeZone("Asia/Kolkata")
        val now = 1_794_571_200_000L                     // 13 Nov 2026, 5:30 PM IST
        assertEquals("आज, 5:00 PM", DkaiChats.whenText(now - 30 * 60_000L, now, tz))
        assertTrue(DkaiChats.whenText(now - 24 * 3_600_000L, now, tz).startsWith("कल, "))
        assertEquals("8 Nov, 5:30 PM", DkaiChats.whenText(now - 5 * 86_400_000L, now, tz))
        assertEquals("13 Nov 2025, 5:30 PM", DkaiChats.whenText(now - 365 * 86_400_000L, now, tz))
        assertEquals("", DkaiChats.whenText(0, now, tz))
    }

    @Test fun iconColorsSameInBothThemes() {
        // (DkaiTheme का object Android Typeface छूता है - इसलिए यहाँ सिर्फ़ const + source जाँच)
        val th = src("dkai/DkaiTheme.kt")
        assertTrue(th.contains("val BG get() = t(0xFF0B101D, 0xFFF4F6FB)"))                 // पृष्ठभूमि बदलती है
        assertTrue(th.contains("val CARD get() = t(0xFF161E31, 0xFFFFFFFF)"))
        assertEquals(0xFF00E5FF.toInt(), DkaiTheme.ICON_CYAN)
        assertEquals(0xFF161E31.toInt(), DkaiTheme.LOGO_TILE)
        val sc = src("dkai/DkaiScreen.kt")
        assertTrue(sc.contains("DkaiTheme.round(c, DkaiTheme.LOGO_TILE, 10f"))     // लोगो के पीछे सफ़ेद डिब्बा नहीं
        assertTrue(sc.contains("DkaiIcon.Kind.SEND, \"भेजें\", DkaiTheme.ICON_CYAN"))
        assertTrue(sc.contains("DkaiIcon(DkaiIcon.Kind.MIC, DkaiTheme.ON)"))
        assertFalse(sc.contains("0xFF0D1324"))                                    // नीचे की पट्टी अब थीम से
        val v = src("dkai/DkaiViews.kt")
        assertTrue(v.contains("intArrayOf(DkaiTheme.BADGE_CORE, DkaiTheme.LOGO_TILE)"))
        assertFalse(v.contains("0xFFE8EEFF"))
    }

    @Test fun modeRowGoneButDropdownKeepsAllModes() {
        val sc = src("dkai/DkaiScreen.kt")
        assertFalse(sc.contains("cardRow"))
        assertFalse(sc.contains("for (m in DkaiMode.CARDS)"))
        assertTrue(sc.contains("DkaiSheets.modes(this@DkaiScreen, v)"))
        for (m in listOf(DkaiMode.CHAT, DkaiMode.BUILDER, DkaiMode.AGENT, DkaiMode.PIPELINE)) assertTrue(m.label, m in DkaiKit.HOME_MODES)
        assertTrue(src("dkai/DkaiSheets.kt").contains("for (m in DkaiKit.HOME_MODES)"))
    }

    @Test fun chatLabelOpensHistory() {
        val sc = src("dkai/DkaiScreen.kt")
        assertTrue(sc.contains("setOnClickListener { DkaiSheets.history(this@DkaiScreen) }"))
        assertTrue(sc.contains("DkaiChats.save(DkaiChats.dir(c), Dkai.chatNo(c), DKState.messages.toList())"))
        val sh = src("dkai/DkaiSheets.kt")
        assertTrue(sh.contains("s.openChat(e.no)")); assertTrue(sh.contains("s.startNewChat()"))
        assertTrue(src("DKState.kt").contains("fun replaceChat(list: List<Msg>)"))
    }
}
