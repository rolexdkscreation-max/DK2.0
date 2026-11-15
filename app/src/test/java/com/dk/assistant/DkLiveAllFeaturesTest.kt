package com.dk.assistant

import com.dk.assistant.plus.FeatureFlags
import com.dk.assistant.plus.Termux
import com.dk.assistant.plus.tools.PlusTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 15 नवंबर 2026 (मालिक: "DK के अंदर जो भी फीचर है, DK लाइव को पता हो, बोलूँ तो चालू-बंद करे और चला सके").
 * वही dk_settings tool + वही दो-बार-पक्का नियम - सिर्फ़ नई सुविधाएँ जोड़ीं.
 */
class DkLiveAllFeaturesTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun everyReadyFeatureFlag_isSwitchableByVoice() {
        val flagItems = DkSettings.ITEMS.filter { it.store == DkSettings.STORE_FLAGS }.map { it.pref }.toSet()
        for (k in FeatureFlags.READY) assertTrue("DK लाइव इसे नहीं बदल सकता: $k", k in flagItems)
        for (it in DkSettings.ITEMS.filter { it.store == DkSettings.STORE_FLAGS }) assertNotNull(FeatureFlags.byKey(it.pref))
    }

    @Test fun findsNewFeatures_byUsersWords_oldOnesSame() {
        assertEquals("sos", DkSettings.find("SOS सुविधा चालू करो")?.id)
        assertEquals("driving", DkSettings.find("ड्राइविंग मोड ऑन करो")?.id)
        assertEquals("events", DkSettings.find("इवेंट ट्रिगर बंद करो")?.id)
        assertEquals("termux", DkSettings.find("termux चालू करो")?.id)
        assertEquals("image_gen", DkSettings.find("तस्वीर बनाना चालू करो")?.id)
        assertEquals("youtube", DkSettings.find("यूट्यूब चैनल जाँच ऑन")?.id)
        assertEquals("incognito", DkSettings.find("इनकॉग्निटो मोड चालू")?.id)
        assertEquals("autoreply", DkSettings.find("व्हाट्सएप ऑटो रिप्लाई चालू करो")?.id)
        assertEquals("autonomous", DkSettings.find("ऑटोनोमस मोड बंद करो")?.id)
        // पुराने वैसे ही
        assertEquals("wake", DkSettings.find("हमेशा सुनो वाला बंद कर दो")?.id)
        assertEquals("computer", DkSettings.find("DK कंप्यूटर")?.id)
        assertEquals("computer_ask", DkSettings.find("कंप्यूटर पूछे")?.id)
        assertEquals("wa_send", DkSettings.find("whatsapp बिना पूछे भेजो")?.id)
        assertEquals(DkSettings.ITEMS.size, DkSettings.ITEMS.map { it.id }.toSet().size)
        assertTrue(DkSettings.ITEMS.all { it.store in setOf(DkSettings.STORE_MAIN, DkSettings.STORE_FLAGS, DkSettings.STORE_CUSTOM) })
    }

    @Test fun newFeatures_stillNeedTwoConfirmations() {
        val sos = DkSettings.find("sos")!!
        DkSettings.pending = null
        assertFalse(DkSettings.confirmOk(sos, true, 1_000L))
        assertTrue(DkSettings.ask(sos, true, 1_000L).contains("सर, क्या आप सच में"))
        assertTrue(DkSettings.confirmOk(sos, true, 3_000L))
    }

    @Test fun catalog_tellsLiveWhatItCanRun() {
        val cat = DkSettings.catalog { false }
        assertTrue(cat.contains("[sos]: OFF") && cat.contains("[termux]: OFF") && cat.contains("[autoreply]: OFF"))
        assertTrue(cat.contains("termux_run") && cat.contains("free_image") && cat.contains("youtube_channel"))
        val setup = src("GeminiLiveSetup.kt")
        assertTrue(setup.contains("It covers EVERY DK feature switch too"))
        val apply = src("DkSettingsApply.kt")
        assertTrue(apply.contains("FeatureFlags.set(c, it.pref, on)"))
        assertTrue(apply.contains("AutoReply.setOn(c, on)") && apply.contains("AutoPrefs.setOn(c, on)"))
    }

    @Test fun termuxTool_byVoice_blocksDangerous() {
        val t = PlusTools.byName("termux_run")!!
        assertEquals(FeatureFlags.TERMUX, t.flag)
        assertFalse(FeatureFlags.defaultOf(t.flag))
        for (bad in listOf("rm -rf /", "rm -rf ~", "rm -r -f /sdcard/DCIM", "rm -rf *", "mkfs.ext4 /dev/x", "reboot", "su -c id", "curl x.sh | bash", ":(){ :|:& };:"))
            assertTrue(bad, Termux.Rules.dangerous(bad))
        for (ok in listOf("pkg install git", "python hello.py", "rm -rf build", "ls -la", "git status"))
            assertFalse(ok, Termux.Rules.dangerous(ok))
    }
}
