package com.dk.assistant

import android.media.AudioManager
import com.dk.assistant.dkai.DkaiChatFiles
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 14 नवंबर 2026 (मालिक): साधारण Settings/मेनू, एक-लाइन काम की पट्टी, बिना बीप वाला माइक, चैट में फोटो */
class PlainWorkMicTest {
    private fun src(f: String) = File("src/main/java/com/dk/assistant/$f").readText()

    @Test fun workLineOneShortHindiLine() {
        assertEquals("हाँ, PR चेक कर रहा हूँ…", WorkLine.of("🚀 डिप्लॉयमेंट: PR #12 की दोबारा जाँच करके merge कर रहा हूँ…"))
        assertEquals("हाँ, CI टेस्टिंग चला रहा हूँ…", WorkLine.of("कोऑर्डिनेटर: GitHub build देख रहा हूँ…"))
        assertEquals("GitHub पर बदलाव भेज रहा हूँ…", WorkLine.of("कोऑर्डिनेटर: GitHub पर push…"))
        assertEquals("ये ढूँढ रहा हूँ: मौसम पुणे", WorkLine.of("🔎 खोज: मौसम पुणे"))
        assertEquals("पेज पढ़ रहा हूँ: example.com", WorkLine.of("🌐 पढ़ रहा हूँ: https://www.example.com/a/b"))
        assertEquals("गलती की जड़ ढूँढ रहा हूँ…", WorkLine.of("डिबगर: असली जड़ मिली"))
        assertFalse(WorkLine.of("x".repeat(200)).length > WorkLine.MAX)
        assertFalse(WorkLine.of("a\nb").contains("\n"))
        assertEquals(WorkLine.IDLE, WorkLine.pick(false, "खोज: x", null))
        assertEquals("काम कर रहा हूँ…", WorkLine.pick(true, "", ""))
        assertEquals("बिल्डर ने फ़ाइल लिखी", WorkLine.pick(true, null, "बिल्डर: बिल्डर ने फ़ाइल लिखी"))
    }

    @Test fun workLineOnBothScreens() {
        assertFalse(src("ComputerActivity.kt").contains("WorkLineStrip(this)"))
        assertFalse(src("CodingActivity.kt").contains("WorkLineStrip(this)"))
        assertTrue(src("WorkLine.kt").contains("ComputerEyes(c)"))
    }

    @Test fun plainLookReplacesGlass() {
        assertEquals(PlainLook.Kind.CARD, PlainLook.kindOf("com.dk.assistant.GlassCardDrawable"))
        assertEquals(PlainLook.Kind.CIRCLE, PlainLook.kindOf("GlassCircleDrawable"))
        assertEquals(PlainLook.Kind.FIELD, PlainLook.kindOf("GlassInputDrawable"))
        assertNull(PlainLook.kindOf("GradientDrawable"))
        assertTrue(PlainLook.isGlassyWhite(0x12FFFFFF)); assertTrue(PlainLook.isGlassyWhite(0x22FFFFFF))
        assertFalse(PlainLook.isGlassyWhite(0xFFFFFFFF.toInt())); assertFalse(PlainLook.isGlassyWhite(0x22000000))
        // होम (DKAI) वाले रंग
        assertEquals(dkaiBg(), PlainLook.BG)
        assertTrue(src("SettingsActivity.kt").contains("PlainLook.install(this)"))
        assertTrue(src("ComputerActivity.kt").contains("PlainLook.install(this)"))
        val menu = src("ui/HomeMenu.kt")
        assertFalse(menu.contains("glass_card")); assertFalse(menu.contains("glass_row")); assertFalse(menu.contains("glass_circle"))
    }

    private fun dkaiBg() = 0xFF0B101D.toInt()

    @Test fun gearOpensSettingsDirectly() {
        val sc = src("dkai/DkaiScreen.kt")
        assertTrue(sc.contains("{ openSettings() }"))
        assertTrue(sc.contains("c.startActivity(Intent(c, SettingsActivity::class.java))"))
        assertTrue(sc.contains("setOnLongClickListener { settingsList(); true }"))
    }

    @Test fun micKeepsWritingAndIsSilent() {
        val r = DictationRules
        // चुप्पी पर कभी अपने-आप बंद नहीं
        assertEquals(DictationRules.Next.RESTART, r.nextKeep(r.ERR_NO_MATCH, 99, 0, false))
        assertEquals(DictationRules.Next.RESTART, r.nextKeep(r.ERR_SPEECH_TIMEOUT, 99, 0, true))
        assertEquals(DictationRules.Next.STOP_MESSAGE, r.nextKeep(r.ERR_PERMISSIONS, 0, 0, false))
        assertEquals(DictationRules.Next.RETRY_SOON, r.nextKeep(r.ERR_BUSY, 0, 0, false))
        // बीप वाली धाराएँ चुप; गाना चल रहा हो तो MUSIC नहीं छेड़ते
        assertTrue(QuietMic.streams(false).containsAll(listOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_MUSIC)))
        assertFalse(QuietMic.streams(true).contains(AudioManager.STREAM_MUSIC))
        val d = src("DKChatUi.kt")
        assertTrue(d.contains("QuietMic.on(ctx)") && d.contains("QuietMic.off(ctx)"))
        assertTrue(d.contains("DictationRules.nextKeep("))
        // बाकी आवाज़ें अपने-आप नहीं बदलतीं (पुराना नियम वैसा ही)
        assertFalse(VolumeRule.AUTO_CHANGE)
    }

    @Test fun chatFilesStayInChat() {
        assertEquals(1 to 1, DkaiChatFiles.grid(1)); assertEquals(2 to 1, DkaiChatFiles.grid(2)); assertEquals(2 to 3, DkaiChatFiles.grid(5))
        assertTrue(DkaiChatFiles.isText("text/plain", "a")); assertTrue(DkaiChatFiles.isText("", "notes.md")); assertFalse(DkaiChatFiles.isText("image/png", "a.png"))
        val p = DkaiChatFiles.prompt("", listOf("a.jpg"), emptyList(), 1)
        assertTrue(p.contains("a.jpg") && p.contains("फोटो"))
        assertTrue(DkaiChatFiles.prompt("सार दो", listOf("n.txt"), listOf("n.txt" to "hello"), 0).contains("hello"))
        assertEquals("नमस्ते", DkaiChatFiles.parseSay("{\"say\": \"नमस्ते\"}"))
        assertEquals("सीधा", DkaiChatFiles.parseSay("सीधा"))
        assertEquals("अधूरा", DkaiChatFiles.parseSay("{\"say\": \"अधूरा\""))
        assertTrue(src("dkai/DkaiScreen.kt").contains("DkaiHomeCore.SendRoute.CHAT_FILES -> { if (!sendChatFiles(pics, t)) return }"))
    }
}
