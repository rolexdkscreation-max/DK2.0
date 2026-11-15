package com.dk.assistant

import com.dk.assistant.plus.Character3D
import com.dk.assistant.plus.FeatureFlags
import com.dk.assistant.plus.GroupReport
import com.dk.assistant.plus.Mail
import com.dk.assistant.plus.models.ModelCatalog
import com.dk.assistant.plus.tools.PlusTools
import com.dk.assistant.plus.tools.TwoStep
import com.dk.assistant.voice.WakeMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 15 नवंबर 2026 (मालिक की सूची, Market / Marketing Suite छोड़कर): नाम से "जी बॉस", FRIDAY / VENOM, DuckDuckGo,
 * चेहरा पहचान, God / Stealth, ईमेल, WhatsApp ग्रुप रिपोर्ट, 3D कैरेक्टर - और "DK को सब पता हो".
 */
class AllFeaturesNov15Test {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    // ---------------------------------------------------------------- नाम
    @Test fun ack_usesSavedName() {
        assertEquals(listOf("जी बॉस"), WakeMatch.acksFor(null, "बॉस"))
        assertEquals(listOf("जी सर"), WakeMatch.acksFor("", "जी सर"))          // "जी जी सर" कभी नहीं
        assertEquals(WakeMatch.DEFAULT_ACKS, WakeMatch.acksFor(null, ""))
        assertEquals(listOf("हाँ धीरज"), WakeMatch.acksFor("हाँ धीरज", "बॉस"))   // अपना लिखा जवाब वैसा ही
        assertEquals("हेलो बॉस", NameRule.greet("बॉस"))
        assertEquals("हेलो बॉस", NameRule.greet("जी बॉस"))
        assertEquals("जी बॉस", NameRule.call("जी बॉस"))
        assertTrue(NameRule.isTitle("बॉस") && NameRule.isTitle("Sir") && !NameRule.isTitle("धीरज"))
    }

    // ---------------------------------------------------------------- FRIDAY / VENOM
    @Test fun persona_byVoice() {
        assertEquals(AssistantPersona.Cmd.On(AssistantPersona.A.FRIDAY), AssistantPersona.parse("फ्राइडे मोड ऑन करो"))
        assertEquals(AssistantPersona.Cmd.On(AssistantPersona.A.VENOM), AssistantPersona.parse("venom mode on"))
        assertEquals(AssistantPersona.Cmd.Off, AssistantPersona.parse("वेनम मोड बंद करो"))
        assertNull(AssistantPersona.parse("फ्राइडे मोड क्या है"))
        assertNull(AssistantPersona.parse("जार्विस मोड ऑन करो"))
        assertTrue(AssistantPersona.blockFor(AssistantPersona.A.VENOM, "बॉस").isNotBlank())
        assertTrue(AssistantPersona.A.VENOM.male && !AssistantPersona.A.FRIDAY.male)
    }

    // ---------------------------------------------------------------- God / Stealth
    @Test fun guardModes_byVoice_andRules() {
        assertEquals(GuardModes.Mode.GOD, (GuardModes.parse("गॉड मोड चालू करो") as GuardModes.Cmd.On).m)
        assertEquals(GuardModes.Mode.STEALTH, (GuardModes.parse("stealth mode on") as GuardModes.Cmd.On).m)
        assertEquals(GuardModes.Cmd.Normal, GuardModes.parse("गॉड मोड बंद करो"))
        assertNull(GuardModes.parse("टच मोड चालू करो"))     // पुराना टच मोड वैसा ही
        assertFalse(GuardModes.loud(GuardModes.Mode.STEALTH)); assertTrue(GuardModes.loud(GuardModes.Mode.GOD))
        assertTrue(GuardModes.photoMax(GuardModes.Mode.GOD) > GuardModes.photoMax(GuardModes.Mode.NORMAL))
        assertFalse(GuardModes.pinStops(GuardModes.Mode.GOD, alarming = true, faceReady = true, hasCode = false))
        assertTrue(GuardModes.pinStops(GuardModes.Mode.GOD, alarming = true, faceReady = false, hasCode = false))   // वरना फ़ोन कभी बंद न हो
        assertTrue(GuardModes.pinStops(GuardModes.Mode.NORMAL, alarming = true, faceReady = true, hasCode = true))
    }

    // ---------------------------------------------------------------- चेहरा
    @Test fun facePeople_identify_andStore() {
        val boss = floatArrayOf(1f, 0f, 0f); val ram = FacePeople.Person("राम", floatArrayOf(0f, 1f, 0f))
        assertTrue(FacePeople.identify(floatArrayOf(0.99f, 0.05f, 0f), boss, listOf(ram), 0.45f).boss)
        val r = FacePeople.identify(floatArrayOf(0.02f, 0.98f, 0f), boss, listOf(ram), 0.45f)
        assertEquals("राम", r.name); assertTrue(r.sure)
        val u = FacePeople.identify(floatArrayOf(0f, 0f, 1f), boss, listOf(ram), 0.45f)
        assertNull(u.name); assertFalse(u.boss)
        // दो लगभग बराबर = पक्का नहीं (ग़लत नाम नहीं)
        val close = FacePeople.identify(floatArrayOf(0.7f, 0.7f, 0f), boss, listOf(ram), 0.45f)
        assertFalse(close.sure)
        val back = FacePeople.decode(FacePeople.encode(listOf(ram)))
        assertEquals("राम", back.single().name); assertEquals(1f, back.single().print[1], 1e-6f)
        assertTrue(FacePeople.decode("garbage").isEmpty())
        assertNull(FacePeople.cleanName("   "))
        assertTrue(SecretNames.isSecret(FacePeople.KEY))
    }

    @Test fun faceSettings_registered_andLiveTool() {
        assertEquals("चेहरा पहचान", SettingsSearch.SECTIONS["face"])
        for (card in listOf("चेहरा पहचान", "जान-पहचान वाले चेहरे")) assertTrue(card, SettingsSearch.ENTRIES.any { it.section == "face" && it.card == card })
        assertTrue(src("AndroidManifest.xml".let { "../../../../AndroidManifest.xml" }).contains(".FaceWhoActivity"))
        assertEquals(FeatureFlags.FACE_ON, PlusTools.byName("face_who")!!.flag)
    }

    // ---------------------------------------------------------------- DuckDuckGo
    @Test fun duckDuckGo_freeInstantAnswer() {
        assertTrue(OpenSearch.ddgUrl("ताज महल").startsWith("https://api.duckduckgo.com/?q="))
        val j = """{"Heading":"Taj Mahal","AbstractText":"The Taj Mahal is an ivory-white marble mausoleum in Agra, India.","AbstractSource":"Wikipedia","AbstractURL":"https://en.wikipedia.org/wiki/Taj_Mahal","RelatedTopics":[]}"""
        val h = OpenSearch.parseDdg(j)
        assertEquals("Wikipedia", h.first().site); assertTrue(h.first().text.contains("Agra"))
        assertTrue(OpenSearch.parseDdg("not json").isEmpty())
    }

    // ---------------------------------------------------------------- ईमेल
    @Test fun mail_rules() {
        val (i, s) = Mail.Rules.servers("me@gmail.com")!!
        assertEquals("imap.gmail.com" to 993, i.host to i.port); assertEquals("smtp.gmail.com" to 465, s.host to s.port)
        assertFalse(Mail.Rules.servers("me@outlook.com")!!.second.tls)       // 587 STARTTLS
        assertEquals("mail.my.in", Mail.Rules.servers("a@my.in", "mail.my.in:993")!!.first.host)
        assertNull(Mail.Rules.servers("bad"))
        assertTrue(Mail.Rules.validAddr("a.b@x.co")); assertFalse(Mail.Rules.validAddr("ram"))
        assertEquals("नमस्ते", Mail.Rules.decodeHeader("=?UTF-8?B?" + java.util.Base64.getEncoder().encodeToString("नमस्ते".toByteArray()) + "?="))
        assertEquals("Hi there", Mail.Rules.decodeHeader("=?utf-8?Q?Hi_there?="))
        assertEquals("राम (ram@x.com)", Mail.Rules.fromName("=?UTF-8?B?" + java.util.Base64.getEncoder().encodeToString("राम".toByteArray()) + "?= <ram@x.com>"))
        assertEquals("ram@x.com", Mail.Rules.fromName("<ram@x.com>"))
        val mp = "--b1\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n" +
            java.util.Base64.getEncoder().encodeToString("कल मिलते हैं".toByteArray()) + "\r\n--b1\r\nContent-Type: text/html\r\n\r\n<b>x</b>\r\n--b1--"
        assertEquals("कल मिलते हैं", Mail.Rules.plainText("multipart/alternative; boundary=\"b1\"", "", mp))
        assertEquals("a=b é", String(Mail.Rules.qp("a=3Db =C3=A9"), Charsets.UTF_8))
        val r = "* 1 FETCH (UID 7 BODY[TEXT]<0> {5}\nhello)\nA3 OK done (0.1 secs)\n"
        assertEquals("hello", Mail.Rules.literalAfter(r, "BODY[TEXT]"))
        val m = Mail.Rules.message("me@x.com", "to@y.com", "नमस्ते", "body", "Mon, 1 Jan 2026", "1@x")
        assertTrue(m.contains("Subject: =?UTF-8?B?") && m.contains("charset=UTF-8") && !m.contains("नमस्ते"))
        assertTrue(Mail.Rules.q("a\"b").contains("\\\""))
    }

    @Test fun sendAndDelete_needYes() {
        val now = 1_000_000L
        assertFalse(TwoStep.ok("send|a", confirmed = true, now = now))      // बिना पूछे "हाँ" नहीं चलता
        assertTrue(TwoStep.ok("send|a", confirmed = true, now = now + 1_000))
        assertFalse(TwoStep.ok("send|b", confirmed = false, now = now))
        assertFalse(TwoStep.ok("send|c", confirmed = true, now = now + 1_000))   // दूसरा काम = फिर से पूछो
        assertFalse(TwoStep.ok("send|x", confirmed = false, now = now))
        assertFalse(TwoStep.ok("send|x", confirmed = true, now = now + TwoStep.WINDOW_MS + 1))   // देर हो गई
    }

    // ---------------------------------------------------------------- ग्रुप रिपोर्ट
    @Test fun groupReport_fillAndMissing() {
        val f = "आज ({तारीख}) की बिक्री\nकुल: {कुल}\nनकद: {नकद}\nफिर: {कुल}"
        assertEquals(listOf("तारीख", "कुल", "नकद"), GroupReport.Rules.fields(f))
        val (t, miss) = GroupReport.Rules.fill(f, GroupReport.Rules.parseValues("कुल: 5000, नकद = 2000"), "15/11/2026")
        assertTrue(miss.isEmpty()); assertTrue(t.contains("15/11/2026") && t.contains("कुल: 5000") && t.endsWith("फिर: 5000"))
        assertEquals(listOf("नकद"), GroupReport.Rules.fill(f, mapOf("कुल" to "1"), "x").second)
        assertEquals(mapOf("a" to "1"), GroupReport.Rules.parseValues("{\"a\":\"1\"}"))
        val l = GroupReport.Rules.upsert(emptyList(), GroupReport.Template("बिक्री", "दुकान टीम", f))
        val l2 = GroupReport.Rules.upsert(l, GroupReport.Template("बिक्री", "नया ग्रुप", f))
        assertEquals(1, l2.size); assertEquals("नया ग्रुप", l2[0].group)
        assertEquals("बिक्री", GroupReport.Rules.find(GroupReport.Rules.decode(GroupReport.Rules.encode(l2)), "बिक्री रिपोर्ट")?.name)
        assertTrue(GroupReport.Rules.catalog(emptyList()).startsWith("NO_TEMPLATES"))
    }

    // ---------------------------------------------------------------- 3D
    @Test fun character3d_downloadOnly_andSafePaths() {
        assertEquals(emptyList<String>(), ModelCatalog.problems())
        assertEquals("char_fox", Character3D.Rules.find("लोमड़ी वाला दिखाओ")?.id)
        assertEquals("char_robot", Character3D.Rules.find("रोबोट")?.id)
        assertEquals(ModelCatalog.GLTF_LOADER, Character3D.Rules.fileFor("lib/loaders/GLTFLoader.js"))
        assertEquals(ModelCatalog.GEOMETRY_UTILS, Character3D.Rules.fileFor("/lib/utils/BufferGeometryUtils.js"))
        assertEquals("fox.glb", Character3D.Rules.fileFor("fox.glb")?.fileName)
        assertNull(Character3D.Rules.fileFor("../shared_prefs/dk_prefs.xml"))
        assertNull(Character3D.Rules.fileFor("sface_2021dec_int8.onnx"))     // सिर्फ़ 3D फ़ाइलें
        val robot = ModelCatalog.CHARACTERS.first()
        assertEquals(robot.bytes + ModelCatalog.VIEWER_LIBS.sumOf { it.bytes }, Character3D.Rules.needBytes(robot) { false })
        assertEquals(0L, Character3D.Rules.needBytes(robot) { true })
        val js = src("../../../../assets/character/character.js")
        assertTrue(js.contains("/models/lib/loaders/GLTFLoader.js") && js.contains("Char"))
    }

    // ---------------------------------------------------------------- DK को सब पता
    @Test fun dkLive_knowsAndSwitchesEverythingNew() {
        assertEquals("face", DkSettings.find("चेहरा पहचान चालू करो")?.id)
        assertEquals("email", DkSettings.find("ईमेल वाला चालू करो")?.id)
        assertEquals("wa_report", DkSettings.find("ग्रुप रिपोर्ट ऑन करो")?.id)
        assertEquals("char3d", DkSettings.find("3D कैरेक्टर चालू करो")?.id)
        assertEquals("friday", DkSettings.find("फ्राइडे ऑन")?.id)
        assertEquals("venom", DkSettings.find("venom")?.id)
        assertEquals("god_mode", DkSettings.find("गॉड मोड")?.id)
        assertEquals("stealth", DkSettings.find("स्टेल्थ मोड")?.id)
        for (k in listOf(FeatureFlags.FACE_ON, FeatureFlags.EMAIL_ON, FeatureFlags.WA_GROUP_REPORT, FeatureFlags.CHARACTER_3D)) {
            assertTrue(k, FeatureFlags.ready(k)); assertFalse(k, FeatureFlags.defaultOf(k))
        }
        for (t in listOf("face_who", "email_read", "email_send", "email_delete", "group_report", "show_character")) {
            assertNotNull(t, PlusTools.byName(t)); assertTrue(t, DkSettings.RUN_HELP.contains(t))
        }
        val apply = src("DkSettingsApply.kt")
        assertTrue(apply.contains("AssistantPersona.set(c, a)") && apply.contains("GuardModes.set(c, m)"))
        assertTrue(src("GeminiLiveSetup.kt").contains("FRIDAY / VENOM mode"))
        for (card in listOf("FRIDAY / VENOM मोड", "टच मोड का रूप", "ईमेल (IMAP/SMTP)", "WhatsApp ग्रुप रिपोर्ट", "3D कैरेक्टर"))
            assertTrue(card, SettingsSearch.ENTRIES.any { it.card == card })
        val all = SetSrc.all()
        assertTrue(all.contains("secEmail()") && all.contains("secGroupReport()") && all.contains("sec3D()"))
    }

    @Test fun noMarket_noMarketingSuite() {
        val main = listOf(File("src/main/java/com/dk/assistant"), File("app/src/main/java/com/dk/assistant")).first { it.exists() }
        val names = main.walkTopDown().filter { it.isFile }.map { it.name.lowercase() }.toList()
        assertTrue(names.none { it.startsWith("market") })
    }
}
