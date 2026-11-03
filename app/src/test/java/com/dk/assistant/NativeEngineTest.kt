package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (मालिक): "DK Native Local Command Engine" - फ़ोन के आम काम बिना Gemini / Worker / इंटरनेट.
 * हर बात -> कौन सा सीधा काम (या null = AI). साथ में: पुराने काम वैसे ही, कोई दोहरा handler नहीं, देरी का हिसाब.
 */
class NativeEngineTest {
    private val wake = listOf("डीके", "dk", "hey dk", "ok dk", "hello dk")
    private fun q(t: String) = LocalRouter.route(t, wake, "")
    private fun type(t: String) = q(t)?.action?.optString("type")
    private fun src(p: String) = File("src/main/java/com/dk/assistant/$p").readText()

    // ------------------------------------------------------------------ 1. ऐप (पुराना रास्ता वैसा ही)
    @Test fun appsHindiEnglishHinglish() {
        assertEquals("whatsapp", q("WhatsApp खोलो")!!.action.optString("name"))
        assertEquals("open_app", type("youtube kholo"))
        assertEquals("open_app", type("open instagram"))
        assertEquals("open_app", type("कैलकुलेटर खोलो"))
        assertEquals("open_app", type("फोनपे खोलो"))
        // ऐप न हो / कई मिलें = AppLauncher का "ERROR_NOT_INSTALLED:" / "ASK:" (आख़िरी नतीजा, user को बताओ)
        assertTrue(LocalRouter.final("ERROR_NOT_INSTALLED: नहीं है"))
        assertTrue(LocalRouter.final("ASK: कौन सा?"))
    }

    // ------------------------------------------------------------------ 2. सेटिंग के पेज
    @Test fun settingsPagesWithAndWithoutTheWordSetting() {
        fun page(t: String) = q(t)!!.action.let { assertEquals(t, "open_settings", it.optString("type")); it.optString("page") }
        assertEquals("display", page("डिस्प्ले खोलो"))
        assertEquals("battery", page("बैटरी खोलो"))
        assertEquals("storage", page("स्टोरेज दिखाओ"))
        assertEquals("storage", page("open storage"))
        assertEquals("location", page("लोकेशन खोलो"))
        assertEquals("sound", page("साउंड खोलो"))
        assertEquals("about", page("फोन के बारे में खोलो"))
        assertEquals("developer", page("डेवलपर ऑप्शन खोलो"))
        // "सेटिंग" वाला पुराना रास्ता वैसा ही
        assertEquals("वाईफाई", page("वाईफाई सेटिंग खोलो"))
        assertEquals("बैटरी", page("बैटरी सेटिंग खोलो"))
        assertEquals("", page("सेटिंग खोलो"))
        assertEquals("open_settings", type("hey dk open settings"))
        assertEquals("panel", type("वाईफाई चालू करो"))
        assertEquals("panel", type("wifi band karo"))
        assertEquals("bluetooth", page("ब्लूटूथ चालू करो"))
        assertEquals("hotspot", page("हॉटस्पॉट चालू करो"))
        assertEquals("panel", type("मोबाइल डेटा बंद करो"))
        assertEquals("airplane", page("एयरप्लेन मोड चालू करो"))
    }

    @Test fun restrictedTogglesOpenTheirPageAndSaySoHonestly() {
        for (t in listOf("लोकेशन चालू करो", "turn on location", "जीपीएस बंद करो")) {
            val x = q(t)!!; assertEquals("location", x.action.optString("page")); assertTrue(t, x.ack.contains("Android"))
        }
        for (t in listOf("डार्क मोड चालू करो", "dark mode off")) {
            val x = q(t)!!; assertEquals("dark", x.action.optString("page")); assertTrue(x.ack.contains("सीधे बदलने नहीं देता"))
        }
        assertEquals("battery saver", q("बैटरी सेवर चालू करो")!!.action.optString("page"))
        assertTrue(src("DeviceActions.kt").contains("ACTION_BATTERY_SAVER_SETTINGS"))
    }

    // ------------------------------------------------------------------ 4. डिवाइस
    @Test fun brightnessRotationDnd() {
        fun b(t: String) = q(t)!!.action.also { assertEquals(t, "brightness", it.optString("type")) }
        assertEquals("up", b("ब्राइटनेस बढ़ाओ").optString("direction"))
        assertEquals("down", b("brightness kam karo").optString("direction"))
        assertEquals("down", b("स्क्रीन की रोशनी कम करो").optString("direction"))
        assertEquals(50, b("ब्राइटनेस 50 करो").optInt("level"))
        assertEquals(80, b("brightness 80 percent").optInt("level"))
        assertEquals(40, b("ब्राइटनेस ४० करो").optInt("level"))
        assertEquals("max", b("ब्राइटनेस फुल करो").optString("direction"))
        assertEquals("min", b("ब्राइटनेस सबसे कम करो").optString("direction"))
        b("ऑटो ब्राइटनेस चालू करो").let { assertEquals("auto", it.optString("direction")); assertTrue(it.optBoolean("on")) }
        assertFalse(b("auto brightness off").optBoolean("on"))
        assertEquals("open_settings", type("ब्राइटनेस सेटिंग खोलो"))

        assertEquals(true, q("ऑटो रोटेट चालू करो")!!.action.optBoolean("on"))
        assertEquals("rotation", type("auto rotate off"))
        assertFalse(q("auto rotate off")!!.action.optBoolean("on"))

        // "do not disturb" का "do not" मना करना नहीं
        assertEquals("dnd", type("डू नॉट डिस्टर्ब चालू करो"))
        assertFalse(q("do not disturb off")!!.action.optBoolean("on"))
        assertEquals("dnd", type("dnd on"))
        assertEquals("dnd", q("डू नॉट डिस्टर्ब खोलो")!!.action.optString("page"))
        // पर असली मना वैसा ही
        assertNull(q("टॉर्च मत जलाओ"))
    }

    @Test fun oldDeviceCommandsUnchanged() {
        assertEquals("torch", type("टॉर्च जलाओ"))
        assertFalse(q("flashlight off")!!.action.optBoolean("on"))
        assertEquals("torch", type("ok dk torch on"))
        assertEquals("up", q("आवाज़ बढ़ाओ")!!.action.optString("direction"))
        assertEquals(30, q("volume 30 karo")!!.action.optInt("level"))
        assertEquals("silent", q("फोन साइलेंट करो")!!.action.optString("direction"))
        for (t in listOf("फोन लॉक करो", "lock the phone", "स्क्रीन बंद करो")) assertEquals(t, "lock", type(t))
        assertEquals("home", type("होम पर जाओ"))
        assertEquals("back", type("वापस जाओ"))
        assertEquals("screenshot_save", type("स्क्रीनशॉट लो"))
        assertEquals("camera", type("कैमरा खोलो"))
        assertEquals("info", type("बैटरी कितनी है"))
        assertEquals("info", type("टाइम क्या हुआ"))
        assertEquals("alarm_set", type("सात बजे का अलार्म लगाओ"))
        assertEquals("next", q("अगला गाना")!!.action.optString("key"))
        assertEquals("pause", q("गाना रोको")!!.action.optString("key"))
    }

    // ------------------------------------------------------------------ 3. कॉल (पुष्टि वाला रास्ता वही)
    @Test fun callsStayConfirmed() {
        assertEquals("call_contact", type("राहुल को कॉल करो"))
        assertEquals("mummy", q("call mummy")!!.action.optString("name"))
        // caption से (अधूरी सुनी बात) कभी सीधे कॉल नहीं - पूरी बात सुनकर ही
        assertNull(LiveFast.pick("राहुल को कॉल करो", wake, "", fast = true))
        val pc = src("PhoneActions.kt")
        assertTrue(pc.contains("ERROR_PERM: कॉन्टैक्ट पढ़ने की परमिशन नहीं है"))
        assertTrue(pc.contains("किसे कॉल करूँ?"))
    }

    // ------------------------------------------------------------------ 5. AI सिर्फ़ जो फ़ोन का काम नहीं / पक्का नहीं
    @Test fun aiFallbackOnlyForUnsupported() {
        for (t in listOf("आज मौसम कैसा है", "एक कहानी सुनाओ", "मेरी लोकेशन भेजो", "मेरी मेमोरी दिखाओ", "डार्क वेब क्या है", "बैटरी जल्दी क्यों खत्म होती है"))
            assertNull(t, q(t))
        // "साउंडक्लाउड" / "बैटरी गेम" जैसे ऐप सेटिंग पेज नहीं बनते
        assertNull(NativeCommands.parse("साउंडक्लाउड खोलो"))
        assertNull(NativeCommands.parse("बैटरी वाला गेम खोलो"))
        assertNull(NativeCommands.parse("मैप में लोकेशन खोलो"))
    }

    @Test fun multiStepStaysLocal() {
        val a = q("ब्राइटनेस कम करो और आवाज़ बढ़ाओ")!!.action
        assertEquals("steps", a.optString("type"))
        assertEquals("brightness", a.getJSONArray("steps").getJSONObject(0).optString("type"))
        assertEquals("volume", a.getJSONArray("steps").getJSONObject(1).optString("type"))
    }

    // ------------------------------------------------------------------ 7. बिना इंटरनेट + देरी
    @Test fun matchingIsOfflineAndFast() {
        val all = listOf("WhatsApp खोलो", "डिस्प्ले खोलो", "ब्राइटनेस 50 करो", "डू नॉट डिस्टर्ब चालू करो", "ऑटो रोटेट बंद करो", "टॉर्च जलाओ",
            "आवाज़ बढ़ाओ", "फोन लॉक करो", "राहुल को कॉल करो", "लोकेशन चालू करो")
        all.forEach { q(it) }   // पहली बार की class-loading गिनती में नहीं
        for (t in all) {
            val t0 = System.nanoTime(); val r = q(t); val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(t, r != null); assertTrue("$t = $ms ms", ms < 50)
        }
        // इंजन में नेटवर्क / AI का कोई रास्ता नहीं
        for (f in listOf("NativeCommands.kt", "NativeActionEngine.kt")) {
            val s = src(f)
            for (bad in listOf("http", "OkHttp", "URL(", "GeminiLive.", "Worker", "AgentRunner", "Socket")) assertFalse("$f: $bad", s.contains(bad))
        }
    }

    @Test fun latencyBoard() {
        NativeCommands.clear()
        NativeCommands.record("brightness", 12, true, "Live phone_task", 1)
        NativeCommands.record("dnd", 30, false, "बिना Live", 2)
        assertTrue(NativeCommands.text().contains("औसत 21 ms"))
        assertTrue(NativeCommands.line(NativeCommands.all()[1]).contains("नहीं हुआ"))
        NativeCommands.clear()
        assertTrue(NativeCommands.text().contains("अभी कोई नहीं"))
    }

    // ------------------------------------------------------------------ 5/8. एक ही रास्ता, पुराने handler वही
    @Test fun wiringSingleEngineNoDuplicateHandlers() {
        val acc = src("DKAccessibilityService.kt")
        assertTrue(acc.contains("in NativeActionEngine.TYPES -> NativeActionEngine.run(this, a)"))
        assertTrue(acc.contains("NativeActionEngine.lockOld()"))
        // पुराने handler एक-एक ही
        for (h in listOf("\"torch\" ->", "\"open_settings\" ->", "\"volume\" ->", "\"lock\" ->", "\"open_app\" ->", "\"call_contact\" ->"))
            assertEquals(h, 1, Regex(Regex.escape(h)).findAll(acc).count())
        // नए काम पुराने TYPES से टकराते नहीं
        assertTrue(NativeCommands.TYPES.none { it in OfflineExec.TYPES })
        val router = src("LocalRouter.kt")
        assertTrue(router.contains("NativeCommands.parse(t)?.let { return it }"))
        assertTrue(router.indexOf("OfflineCommands.parse(t)") < router.indexOf("NativeCommands.parse(t)"))
        assertTrue(router.indexOf("NativeCommands.parse(t)") < router.indexOf("QuickCommands.parse(t, wakeWords)"))
        // Live और बिना Live, दोनों रास्ते देरी गिनते हैं
        assertTrue(src("GeminiLiveTurnEnd.kt").contains("NativeActionEngine.exec(svc, q,"))
        assertTrue(src("GeminiLiveQueue.kt").contains("NativeActionEngine.exec(svc, q,"))
        assertTrue(src("AgentRunner.kt").contains("NativeActionEngine.exec(svc, q,"))
        assertTrue(src("SettingsSystem.kt").contains("NativeCommands.text()"))
        val man = File("src/main/AndroidManifest.xml").readText()
        assertTrue(man.contains("android.permission.WRITE_SETTINGS"))
        assertTrue(man.contains("android.permission.ACCESS_NOTIFICATION_POLICY"))
        // इजाज़त न हो = पेज खोलो + साफ़ बताओ (चुप नहीं)
        val eng = src("NativeActionEngine.kt")
        assertTrue(eng.contains("ACTION_MANAGE_WRITE_SETTINGS"))
        assertTrue(eng.contains("ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS"))
        assertTrue(eng.contains("ERROR_PERM:"))
        // Live को सच्चा नतीजा
        assertTrue(LiveFast.quickResult(QuickCommands.Quick(org.json.JSONObject().put("type", "brightness"), "ब्राइटनेस कम"),
            "SAID: ब्राइटनेस 25 प्रतिशत कर दी।", false).contains("25 प्रतिशत"))
        assertTrue(LiveFast.quickResult(QuickCommands.Quick(org.json.JSONObject().put("type", "dnd"), ""),
            "ERROR_PERM: DND access चाहिए", false).startsWith("FAILED"))
        assertEquals(0L, LiveFast.settleMs("brightness"))
    }
}
