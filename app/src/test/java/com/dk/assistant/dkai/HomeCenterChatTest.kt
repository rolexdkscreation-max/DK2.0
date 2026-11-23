package com.dk.assistant.dkai

import com.dk.assistant.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 23 नवंबर 2026 (मालिक): होम के बीच में ORB / 3D अवतार, लाइव टेक्स्ट बॉक्स, इनपुट बॉक्स Chat में, मेन्यू स्क्रॉल */
class HomeCenterChatTest {
    private fun src(p: String): String = listOf(
        java.io.File("src/main/java/com/dk/assistant/$p"), java.io.File("app/src/main/java/com/dk/assistant/$p")
    ).first { it.exists() }.readText()

    @Test fun avatarIndex_parse_safe() {
        val l = HomeAvatars.parse("""{"version":1,"avatars":[
            {"id":"dk_girl","name":"DK गर्ल","gender":"girl","file":"dk_avatar_girl.png"},
            {"id":"dk_boy","name":"DK बॉय","gender":"boy","file":"dk_avatar_boy.png"},
            {"id":"bad","file":"../x.png"},
            {"id":"dk_girl","file":"dup.png"}]}""")
        assertEquals(listOf("dk_girl", "dk_boy"), l.map { it.id })
        assertEquals("boy", l[1].gender)
        assertTrue(HomeAvatars.parse("नहीं json").isEmpty())
        assertTrue(HomeAvatars.parse(null).isEmpty())
        assertFalse(HomeAvatars.safeFile("../a.png")); assertFalse(HomeAvatars.safeFile("a.jpg")); assertTrue(HomeAvatars.safeFile("dk_a-1.png"))
        // बाद में और लड़के-लड़कियाँ: नई सूची पहले, पहले से तय वाला एक ही बार
        assertEquals(listOf("dk_boy", "dk_girl"), HomeAvatars.merge(listOf(l[1])).map { it.id })
        assertEquals(listOf("dk_girl"), HomeAvatars.merge(emptyList()).map { it.id })
    }

    @Test fun avatarChoice_prefRoundTrip_png() {
        assertNull(HomeAvatars.idOf(HomeAvatars.ORB)); assertNull(HomeAvatars.idOf(null)); assertNull(HomeAvatars.idOf("avatar:../x"))
        assertEquals("dk_girl", HomeAvatars.idOf(HomeAvatars.prefFor("dk_girl")))
        assertEquals(HomeAvatars.ORB, HomeAvatars.prefFor(null))
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        assertTrue(HomeAvatars.isPng(png)); assertFalse(HomeAvatars.isPng("<html>..".toByteArray()))
        // इंटरनेट से (ऐप के अंदर नहीं) - GitHub के avatars/ फ़ोल्डर से
        assertTrue(HomeAvatars.BASE.startsWith("https://raw.githubusercontent.com/") && HomeAvatars.BASE.endsWith("/avatars/"))
    }

    @Test fun homeStatus_order() {
        assertEquals(DkaiCore.Status.SETUP, DkaiCore.homeLive(false, Mode.SPEAKING, true, false, true, false))
        assertEquals(DkaiCore.Status.CONNECTING, DkaiCore.homeLive(true, Mode.IDLE, true, false, false, false))
        assertEquals(DkaiCore.Status.LISTENING, DkaiCore.homeLive(true, Mode.LISTENING, true, false, true, false))
        assertEquals(DkaiCore.Status.THINKING, DkaiCore.homeLive(true, Mode.THINKING, true, false, true, false))
        assertEquals(DkaiCore.Status.SPEAKING, DkaiCore.homeLive(true, Mode.SPEAKING, true, false, true, true))
        assertEquals(DkaiCore.Status.RESEARCHING, DkaiCore.homeLive(true, Mode.THINKING, false, false, false, true))
        assertEquals(DkaiCore.Status.PROCESSING, DkaiCore.homeLive(true, Mode.WORKING, true, false, true, false))
        assertEquals(DkaiCore.Status.READY, DkaiCore.homeLive(true, Mode.IDLE, false, false, false, false))
    }

    @Test fun homeLiveText_userThenDkSameBox() {
        // यूज़र बोल रहा = उसकी पूरी बात
        assertEquals("आज मौसम कैसा है" to true,
            DkaiCore.homeText(DkaiCore.Status.LISTENING, "आज मौसम कैसा है…", 200, "", "पुराना", 100, 50))
        // DK बोलने लगा = उसी डिब्बे में DK की बात
        assertEquals("आज धूप है" to false,
            DkaiCore.homeText(DkaiCore.Status.SPEAKING, "आज मौसम कैसा है", 200, "आज धूप है", "पुराना", 100, 50))
        // सोचते समय यूज़र की बात बनी रहे
        assertEquals("आज मौसम कैसा है" to true,
            DkaiCore.homeText(DkaiCore.Status.THINKING, "आज मौसम कैसा है", 200, "", "पुराना", 100, 50))
        // नया सत्र शुरू होने से पहले की बात नहीं
        assertNull(DkaiCore.homeText(DkaiCore.Status.READY, "पुरानी", 10, "", "पुराना", 20, 50))
    }

    @Test fun avatarMotion_pure() {
        assertEquals(0f, HomeAvatarView.breath(0f, 4f), 0.001f)
        assertEquals(4f, HomeAvatarView.breath(HomeAvatarView.BREATH_S / 4, 4f), 0.01f)
        for (t in 0 until 50) assertTrue(kotlin.math.abs(HomeAvatarView.sway(t / 10f, 5f)) <= 4.0001f)
        assertEquals(0f, HomeAvatarView.sway(1.3f, 0f), 0.0001f)
    }

    @Test fun menuScrolls_insideScreen() {
        assertEquals(1400, com.dk.assistant.ui.HomeMenu.maxHeight(1600, 150, 50))
        assertEquals(200, com.dk.assistant.ui.HomeMenu.maxHeight(300, 250, 50))
        val m = src("ui/HomeMenu.kt")
        assertTrue(m.contains("ScrollView"))
    }

    @Test fun clayNavy_firstTheme() {
        assertEquals("Clay Navy", com.dk.assistant.ui.ClayTheme.ALL.first().name)
    }

    @Test fun inputBox_movedToChat_tabsStay() {
        val s = src("dkai/DkaiScreen.kt")
        assertTrue(s.contains("if (chatPage) { buildInput(); buildTabs() } else buildTabs()"))
        assertTrue(s.contains("buildTabs()"))
        val a = src("ChatActivity.kt")
        assertTrue(a.contains("chatHeader = { top }") && a.contains("chatBody = scroll"))
        assertFalse(a.contains("DKComposer("))
    }
}
