package com.dk.assistant.forge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * मालिक (नवंबर 2026): वेबसाइट से पहले पूरा डिटेल, आवाज़ से एडिट, लोकल लिंक / Chrome, लिंक WhatsApp पर, डिप्लॉय सिर्फ़ कहने पर;
 * "गेम बनाओ" = DK कंप्यूटर बनाए, DK Chrome में चलाकर दिखाए और बताए क्या-क्या है.
 */
class ForgeGameUpgradeTest {
    private fun src(path: String) = listOf(File("src/main/java/com/dk/assistant/$path"), File("app/src/main/java/com/dk/assistant/$path")).first { it.exists() }.readText()

    @Test fun briefAsksBeforeBuilding() {
        val vague = ForgeBrief.liveGate(JSONObject().put("prompt", "ek website banao"))
        assertNotNull(vague)
        assertTrue(vague!!.startsWith("need_details"))
        assertTrue(vague.contains("नाम") && vague.contains("संपर्क"))
        // पूरा ब्रीफ़ / मालिक ने "खुद तय करो" कहा / details_done = बनाना शुरू
        assertNull(ForgeBrief.liveGate(JSONObject().put("prompt", "cafe").put("site_type", "cafe").put("business_name", "Chai Point")
            .put("sections", "menu, gallery").put("style", "black gold").put("contact", "98xxxxxx")))
        assertNull(ForgeBrief.liveGate(JSONObject().put("prompt", "cafe ki website, baaki tum khud tay karo")))
        assertNull(ForgeBrief.liveGate(JSONObject().put("prompt", "cafe").put("details_done", true)))
        // सिर्फ़ एक चीज़ बाकी = DK खुद
        assertNull(ForgeBrief.liveGate(JSONObject().put("prompt", "x").put("site_type", "gym").put("business_name", "Iron").put("sections", "plans").put("style", "red")))
    }

    @Test fun fullPromptCarriesEveryDetail() {
        val p = ForgeBrief.fullPrompt(JSONObject().put("prompt", "cafe ki website").put("business_name", "Chai Point").put("contact", "9876543210").put("style", "black gold"))
        assertTrue(p.contains("Chai Point") && p.contains("9876543210") && p.contains("black gold") && p.contains("professional"))
        assertTrue(ForgeBrief.vagueText("DK एक वेबसाइट बनाओ"))
        assertFalse(ForgeBrief.vagueText("नाशिक के चाय पॉइंट कैफ़े की काले सुनहरे रंग की वेबसाइट बनाओ जिसमें मेन्यू हो"))
    }

    @Test fun deployOnlyWhenAsked() {
        assertTrue(ForgeDeploy.userAsked("DK वेबसाइट को डिप्लॉय कर दो"))
        assertTrue(ForgeDeploy.userAsked("website publish karo"))
        assertTrue(ForgeDeploy.userAsked("इसे ऑनलाइन डाल दो"))
        assertFalse(ForgeDeploy.userAsked("वेबसाइट Chrome में खोलो"))
        assertFalse(ForgeDeploy.userAsked("अभी डिप्लॉय मत करो"))
        assertFalse(ForgeDeploy.userAsked(null))
        val r = ForgeDeploy.repoName("Chai Point cafe ki website", "website_20261104_101010.html")
        assertTrue(r, r.startsWith("dk-site-") && r.contains("chai") && r.matches(Regex("[a-z0-9-]+")))
    }

    @Test fun localServerIsSafe() {
        assertEquals("website_1.html", LocalSite.safeName("/website_1.html?x=1"))
        assertNull(LocalSite.safeName("/../secret.txt"))
        assertNull(LocalSite.safeName("/a/b.html"))
        assertNull(LocalSite.safeName("/.hidden"))
        assertNull(LocalSite.safeName("/"))
        assertTrue(LocalSite.contentType("x.html").startsWith("text/html"))
        assertEquals("192.168.1.5", LocalSite.pickLanIp(listOf("10.0.0.2", "192.168.1.5")))
        assertEquals("172.20.1.1", LocalSite.pickLanIp(listOf("172.20.1.1")))
        assertNull(LocalSite.pickLanIp(listOf("100.64.1.1")))
        assertEquals("http://127.0.0.1:8765/my%20site.html", LocalSite.localUrl("my site.html", 8765))
        val d = Files.createTempDirectory("dk").toFile()
        File(d, "website_a.html").also { it.writeText("<html>a</html>"); it.setLastModified(1000) }
        File(d, "website_b.html").also { it.writeText("<html>b</html>"); it.setLastModified(5000) }
        assertEquals("website_a.html", LocalSite.resolve("/website_a.html", listOf(d))?.name)
        assertEquals("website_b.html", LocalSite.resolve("/latest", listOf(d))?.name)
        assertNull(LocalSite.resolve("/nope.html", listOf(d)))
        d.deleteRecursively()
    }

    @Test fun gameIntent() {
        assertNotNull(GameMaker.extract("DK एक सांप वाला गेम बनाओ"))
        assertNotNull(GameMaker.extract("make a car racing game"))
        assertNotNull(GameMaker.extract("डीके क्रिकेट का गेम बना दो जिसमें 3 लेवल हों"))
        assertNull(GameMaker.extract("गेम खेलो"))
        assertNull(GameMaker.extract("गेम कैसे बनाते हैं?"))
        assertNull(GameMaker.extract("play store से गेम डाउनलोड करो"))
        assertNull(GameMaker.extract("कोडिंग एजेंट से github पर गेम बनाओ"))
        assertNull(GameMaker.extract("वेबसाइट बनाओ"))
    }

    @Test fun gameTaskRules() {
        val f = GameMaker.fileName("snake game banao")
        assertTrue(f, f.startsWith("game_snake") && f.endsWith(".html"))
        val t = GameMaker.task("सांप वाला गेम", f)
        assertTrue(t.contains(f) && t.contains("write_file") && t.contains("touch") && t.contains("self-contained") && t.contains("features"))
        assertTrue(GameMaker.editTask("speed badha do", f).contains("read_file"))
        val s = GameMaker.doneSpeech("**Features:** - 3 levels\n- high score")
        assertTrue(s.contains("चेक कर लीजिए") && s.contains("3 levels") && !s.contains("**"))
    }

    @Test fun liveWiring() {
        val setup = src("GeminiLiveSetup.kt")
        assertTrue(setup.contains("\"website_action\"") && setup.contains("\"make_game\"") && setup.contains("details_done"))
        val tools = src("GeminiLiveTools.kt")
        assertTrue(tools.contains("ForgeBrief.liveGate(args)"))
        assertTrue(tools.contains("\"website_action\" -> com.dk.assistant.forge.ForgeVoice.run(svc, args)"))
        assertTrue(tools.contains("\"make_game\" -> com.dk.assistant.forge.GameMaker.start("))
        assertTrue(com.dk.assistant.LeakedCall.TOOLS.containsAll(listOf("website_action", "make_game")))
        val voice = src("forge/ForgeVoice.kt")
        assertTrue(voice.contains("ForgeDeploy.userAsked(DKState.lastUser)"))   // डिप्लॉय सिर्फ़ कहने पर
        assertTrue(src("AgentRunner.kt").contains("GameMaker.extract(goal)"))
        assertTrue(src("AgentRunner.kt").contains("ForgeBrief.vagueText(p0)"))
    }
}
