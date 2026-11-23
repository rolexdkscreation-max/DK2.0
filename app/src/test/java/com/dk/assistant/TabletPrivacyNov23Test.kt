package com.dk.assistant

import com.dk.assistant.dkai.DkaiHomeCore
import com.dk.assistant.dkai.HomeAvatarView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 23 नवंबर 2026 (मालिक): READY कार्ड नीचे, अवतार सिर्फ़ बीच की जगह में, फ़ोन / टैबलेट अलग लेआउट, बिना पूछे कुछ न देखना */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TabletPrivacyNov23Test {
    private val root = listOf(File("src/main/java/com/dk/assistant"), File("app/src/main/java/com/dk/assistant")).first { it.exists() }
    private fun src(p: String) = File(root, p).readText()
    private fun j(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { put(it.first, it.second) } }

    @org.junit.Before fun setUp() { DKState.init(org.robolectric.RuntimeEnvironment.getApplication()) }

    @After fun clean() { PrivacyGate.reset(); PrivacyGate.showCard = null; DKState.lastUser = "" }

    // ------------------------------------------------------------------ निजता
    @Test fun privateToolsAreRecognised() {
        assertEquals(PrivacyGate.Kind.SCREEN, PrivacyGate.kind("look_screen", JSONObject()))
        assertEquals(PrivacyGate.Kind.SCREEN, PrivacyGate.kind("read_screen", JSONObject()))
        assertEquals(PrivacyGate.Kind.SCREEN, PrivacyGate.kind("screen_share", j("on" to true)))
        assertEquals(PrivacyGate.Kind.CAMERA, PrivacyGate.kind("camera", JSONObject()))
        assertEquals(PrivacyGate.Kind.GALLERY, PrivacyGate.kind("look_photo", JSONObject()))
        assertEquals(PrivacyGate.Kind.CHAT, PrivacyGate.kind("whatsapp_task", j("action" to "read_chat_screen")))
        assertEquals(PrivacyGate.Kind.CHAT, PrivacyGate.kind("whatsapp_task", j("action" to "READ_UNREAD")))
        assertEquals(PrivacyGate.Kind.GALLERY, PrivacyGate.kind("social_media_task", j("media" to "latest_gallery")))
        assertEquals(PrivacyGate.Kind.GALLERY, PrivacyGate.kind("social_media_task", j("media_query" to "कल की बीच वाली फ़ोटो")))
        // बंद करना / बिना निजी चीज़ वाले काम = बिना पूछे
        assertNull(PrivacyGate.kind("screen_share", j("on" to false)))
        assertNull(PrivacyGate.kind("camera", j("on" to false)))
        assertNull(PrivacyGate.kind("whatsapp_task", j("action" to "send")))
        assertNull(PrivacyGate.kind("open_app", j("name" to "YouTube")))
        assertNull(PrivacyGate.kind("web_search", j("query" to "मौसम")))
    }

    @Test fun explicitCommandNeedsNoAsk() {
        // 23 नवंबर 2026 (मालिक): खुद साफ़ कहा = बिना पूछे
        assertTrue(PrivacyGate.asksFor(PrivacyGate.Kind.CHAT, "ठीक है, WhatsApp पे जाके चेक करो और देख के बताओ कौन-कौन सा चैट है"))
        assertTrue(PrivacyGate.asksFor(PrivacyGate.Kind.GALLERY, "गैलरी देखो"))
        assertTrue(PrivacyGate.asksFor(PrivacyGate.Kind.GALLERY, "meri photo dekh ke batao"))
        assertTrue(PrivacyGate.asksFor(PrivacyGate.Kind.SCREEN, "स्क्रीन देखकर बताओ"))
        assertTrue(PrivacyGate.asksFor(PrivacyGate.Kind.SCREEN, "स्क्रीन शेयर करो"))
        assertTrue(PrivacyGate.asksFor(PrivacyGate.Kind.CAMERA, "डीके, उह, डीके लेंस ओपन करो"))
        assertTrue(PrivacyGate.asksFor(PrivacyGate.Kind.CAMERA, "DK scan kholo"))
        // साफ़ नहीं / मना / दूसरी चीज़ = हुक्म नहीं (पूछेगा)
        assertFalse(PrivacyGate.asksFor(PrivacyGate.Kind.GALLERY, "गैलरी मत देखो"))
        assertFalse(PrivacyGate.asksFor(PrivacyGate.Kind.CHAT, "आज मौसम कैसा है"))
        assertFalse(PrivacyGate.asksFor(PrivacyGate.Kind.GALLERY, "स्क्रीन देखकर बताओ"))
        assertFalse(PrivacyGate.asksFor(PrivacyGate.Kind.SCREEN, "स्क्रीन"))
        assertFalse(PrivacyGate.asksFor(PrivacyGate.Kind.CHAT, ""))
    }

    @Test fun yesAnswers() {
        for (y in listOf("हाँ", "ठीक है, देख लो", "ठीक है ये देखो", "हाँ देखो", "देख लो", "ok", "कर दो", "चलो", "haan dekh lo", "theek hai"))
            assertTrue(y, PrivacyGate.isYesAnswer(y))
        for (n in listOf("नहीं", "मत देखो", "क्या दिखेगा?", "", "रुको", "nahi"))
            assertFalse(n, PrivacyGate.isYesAnswer(n))
    }

    @Test fun gateDecisions() {
        val k = PrivacyGate.Kind.GALLERY
        val p = PrivacyGate.Pending(k, "look_photo", 1_000)
        fun c(pending: PrivacyGate.Pending?, said: List<Pair<String, Long>>, tapAt: Long = 0, tapYes: Boolean = false, now: Long = 2_000,
              explicit: Boolean = false, granted: Boolean = false) = PrivacyGate.check(k, explicit, granted, pending, said, tapAt, tapYes, now)
        assertEquals(PrivacyGate.Gate.Ask, c(null, emptyList()))
        // खुद कहा / इसी काम की हाँ पहले = सीधे
        assertEquals(PrivacyGate.Gate.Run, c(null, emptyList(), explicit = true))
        assertEquals(PrivacyGate.Gate.Run, c(null, emptyList(), granted = true))
        assertEquals(PrivacyGate.Gate.Run, c(p, listOf("हाँ" to 1_500)))
        assertEquals(PrivacyGate.Gate.Run, c(p, listOf("ठीक है, देख लो" to 1_500)))
        assertEquals(PrivacyGate.Gate.Deny, c(p, listOf("नहीं" to 1_500)))
        // चुप्पी / पूछने से पहले की बात = नहीं देखना (पर फिर से पूछना भी नहीं - रुको)
        assertEquals(PrivacyGate.Gate.NotHeard, c(p, listOf("कुछ और" to 900)))
        assertEquals(PrivacyGate.Gate.NotHeard, c(p, listOf("क्या दिखेगा?" to 1_500)))
        // हाँ के बाद ना = ना; ना के बाद हाँ = हाँ (आख़िरी बात)
        assertEquals(PrivacyGate.Gate.Deny, c(p, listOf("हाँ" to 1_200, "नहीं रहने दो" to 1_400)))
        assertEquals(PrivacyGate.Gate.Run, c(p, listOf("नहीं" to 1_200, "अच्छा ठीक है देख लो" to 1_400)))
        // कार्ड का टैप
        assertEquals(PrivacyGate.Gate.Run, c(p, emptyList(), 1_600, true))
        assertEquals(PrivacyGate.Gate.Deny, c(p, emptyList(), 1_600, false))
        assertEquals(PrivacyGate.Gate.NotHeard, c(p, emptyList(), 900, true))
        // दूसरी निजी चीज़ / समय निकल गया = फिर पूछो; उसी चीज़ का दूसरा tool = वही जवाब (दोबारा नहीं पूछता)
        assertEquals(PrivacyGate.Gate.Ask, PrivacyGate.check(PrivacyGate.Kind.SCREEN, false, false, p, listOf("हाँ" to 1_500), 0, false, 2_000))
        assertEquals(PrivacyGate.Gate.Ask, c(p, listOf("हाँ" to 1_500), now = 1_000 + PrivacyGate.WINDOW_MS + 1))
        assertEquals(PrivacyGate.Gate.Run, c(PrivacyGate.Pending(k, "social_media_task", 1_000), listOf("हाँ" to 1_500)))
        // एक काम की हाँ: नई बात = नया काम
        assertTrue(PrivacyGate.taskGrantValid(1_000, 1_000, 2_000))
        assertFalse(PrivacyGate.taskGrantValid(1_000, 9_000, 10_000))
        assertFalse(PrivacyGate.taskGrantValid(1_000, 0, 1_000 + PrivacyGate.TASK_MS + 1))
        assertFalse(PrivacyGate.taskGrantValid(null, 0, 2_000))
    }

    @Test fun explicitRunsAtOnce_vagueAsksOnce_yesRunsOnce() {
        var cards = 0
        PrivacyGate.showCard = { _, _ -> cards++ }
        val a = JSONObject()
        // खुद साफ़ कहा: बिना पूछे, और उसी काम के अगले कदम भी बिना पूछे
        DKState.user("WhatsApp पे जाके चेक करो और बताओ कौन-कौन सा चैट है"); Thread.sleep(3)
        assertNull(PrivacyGate.gate("whatsapp_task", j("action" to "read_unread")))
        assertNull(PrivacyGate.gate("read_chat_screen", a))
        assertEquals(0, cards)
        // बात साफ़ नहीं (DK ने किसी मैसेज में पढ़ा) = एक बार पूछो, tool नहीं चला
        Thread.sleep(6_000); DKState.user("अच्छा फिर क्या हुआ"); Thread.sleep(3)
        val ask = PrivacyGate.gate("look_photo", a)
        assertNotNull(ask); assertTrue(ask!!.startsWith("PRIVACY_ASK")); assertTrue(ask.contains(PrivacyGate.QUESTION))
        assertEquals(1, cards)
        // जवाब अभी नहीं = नहीं देखा, और फिर से पूछना भी नहीं (कार्ड दोबारा नहीं)
        val wait = PrivacyGate.gate("look_photo", a)
        assertEquals(PrivacyGate.NOT_HEARD, wait); assertTrue(wait!!.contains("Do NOT ask the question again")); assertEquals(1, cards)
        // "नहीं" = नहीं देखा
        Thread.sleep(3); DKState.user("नहीं")
        assertTrue(PrivacyGate.gate("look_photo", a)!!.startsWith("PRIVACY_DENIED"))
        // नया काम: फिर पूछो, फिर "ठीक है, देख लो" = पूरा काम एक बार में (उसी चीज़ के दूसरे कदम भी)
        assertTrue(PrivacyGate.gate("look_photo", a)!!.startsWith("PRIVACY_ASK"))
        Thread.sleep(3); DKState.user("ठीक है, देख लो")
        assertNull(PrivacyGate.gate("look_photo", a))
        assertNull(PrivacyGate.gate("social_media_task", j("media" to "latest_gallery")))
        // अनुमति सिर्फ़ उसी एक काम की - यूज़र की नई बात के बाद फिर पूछो
        Thread.sleep(6_000); DKState.user("अब कुछ और")
        assertTrue(PrivacyGate.gate("look_photo", a)!!.startsWith("PRIVACY_ASK"))
        // कार्ड पर "हाँ"
        Thread.sleep(3); PrivacyGate.tap(true)
        assertNull(PrivacyGate.gate("look_photo", a))
        // निजी नहीं = कभी नहीं पूछता
        assertNull(PrivacyGate.gate("open_app", j("name" to "YouTube")))
    }

    @Test fun oneShotGrant() {
        assertFalse(PrivacyGate.consumeGrant(PrivacyGate.Kind.CAMERA))
        assertFalse(PrivacyGate.askThen(PrivacyGate.Kind.CAMERA) { })        // कार्ड नहीं = कुछ नहीं
        var ran = 0
        PrivacyGate.showCard = { _, answer -> answer(true) }
        assertTrue(PrivacyGate.askThen(PrivacyGate.Kind.CAMERA) { ran++ })
        assertEquals(1, ran)
        assertTrue(PrivacyGate.consumeGrant(PrivacyGate.Kind.CAMERA))
        assertFalse(PrivacyGate.consumeGrant(PrivacyGate.Kind.CAMERA))      // दोबारा नहीं
        PrivacyGate.showCard = { _, answer -> answer(false) }
        assertTrue(PrivacyGate.askThen(PrivacyGate.Kind.CAMERA) { ran++ })
        assertEquals(1, ran)
        assertFalse(PrivacyGate.grantValid(0L, PrivacyGate.GRANT_MS + 1))
    }

    @Test fun ruleInEveryPromptAndGateInCode() {
        for (w in listOf("क्या मैं ये देखूँ?", "गैलरी", "स्क्रीन", "कैमरा", "सिर्फ़ उसी एक काम", "याद मत रखो", "दोबारा मत पूछो", "गलती से सुना", "फिर से मत पूछो")) assertTrue(w, PrivacyGate.RULE.contains(w))
        assertEquals(5, Regex("PrivacyGate\\.RULE").findAll(src("BrainManager.kt")).count())
        val tools = src("GeminiLiveTools.kt")
        val gateAt = tools.indexOf("?: PrivacyGate.gate(name, args)")
        assertTrue(gateAt > 0 && gateAt < tools.indexOf("\"look_screen\" -> ScreenLook.liveTool"))
        // 23 नवंबर 2026 (मालिक, दूसरी बार): तेज़ रास्ता स्क्रीन शेयर तभी चालू करे जब यूज़र ने खुद साफ़ कहा हो
        val te = src("GeminiLiveTurnEnd.kt")
        assertFalse(te.contains("LiveVision.setScreen(svc, q.action.optBoolean(\"on\", true))"))
        assertTrue(te.indexOf("!PrivacyGate.asksFor(PrivacyGate.Kind.SCREEN, b)") in 1 until te.indexOf("LiveVision.setScreen(svc, on)"))
        assertTrue(src("GeminiLiveQueue.kt").contains("PrivacyGate.gate(\"camera\""))
        assertTrue(src("DKAccessibilityYt.kt").contains("!PrivacyGate.explicitNow(PrivacyGate.Kind.CAMERA) && !PrivacyGate.consumeGrant(PrivacyGate.Kind.CAMERA)"))
        assertTrue(src("tools/ToolAgent.kt").contains("PrivacyGate.asksFor(pk, goal)"))
        assertTrue(src("tools/ToolAgent.kt").contains("PrivacyGate.askLine("))
        assertTrue(src("DKApp.kt").contains("PrivacyCard.install"))
        assertTrue(src("DKApp.kt").contains("UiFit.center(a)"))
    }

    @Test fun noBackgroundGalleryScan() {
        // कोई भी हिस्सा गैलरी पर पीछे से नज़र (ContentObserver / रोज़ का काम) नहीं रखता
        root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val t = f.readText()
            if (t.contains("MediaStore")) {
                assertFalse("${f.name}: गैलरी पर ContentObserver", t.contains("registerContentObserver"))
                assertFalse("${f.name}: गैलरी का periodic काम", t.contains("PeriodicWorkRequest"))
            }
        }
    }

    // ------------------------------------------------------------------ होम
    @Test fun readyCardDrops24to40dp() {
        assertEquals(24, DkaiHomeCore.cardDropDp(480))
        assertEquals(36, DkaiHomeCore.cardDropDp(800))
        assertEquals(40, DkaiHomeCore.cardDropDp(1400))
        val d = src("dkai/DkaiScreen.kt")
        assertTrue(d.contains("topMargin = d(14f + cardDrop)"))
        assertTrue(d.contains("GREET_LIFT_DP - cardDrop"))
    }

    @Test fun avatarOnlyInMiddleArea_withFade() {
        val s = HomeAvatarView.fadeStops()
        assertEquals(4, s.size); assertEquals(0f, s[0], 0f); assertEquals(1f, s[3], 0f); assertTrue(s[1] > 0f && s[2] < 1f)
        val d = src("dkai/DkaiScreen.kt")
        assertTrue(d.contains("bgAvatar.bitmap = null"))          // पूरी स्क्रीन वाला पीछा कभी नहीं
        assertTrue(d.contains("centerAvatar.bitmap = bmp"))
        assertTrue(d.contains("LinearLayout.LayoutParams(-1, 0, 1f)"))   // अभिवादन और कार्ड के बीच की जगह
        assertTrue(d.contains("fade = true"))
    }

    // ------------------------------------------------------------------ फ़ोन / टैबलेट
    @Test fun phoneVsTabletRules() {
        assertFalse(UiFitRules.isTablet(411)); assertFalse(UiFitRules.isTablet(599)); assertTrue(UiFitRules.isTablet(600))
        assertEquals(0, UiFitRules.sideDp(false, 900))
        assertEquals(0, UiFitRules.sideDp(true, 640))
        val side = UiFitRules.sideDp(true, 1000)
        assertTrue(side in 60..80)
        assertTrue(UiFitRules.scaleFor(800) > 1.15f)
    }

    @Test fun everyScreenUsesTabletScaling() {
        val acts = root.walkTopDown().filter { it.extension == "kt" }
            .filter { Regex("class \\w+[^{\\n]*: ?(AppCompat)?Activity\\(\\)").containsMatchIn(it.readText()) }.toList()
        assertTrue(acts.size >= 25)
        for (f in acts) assertTrue(f.name, Regex("attachBaseContext\\(newBase: android.content.Context\\) \\{ super.attachBaseContext\\((com.dk.assistant.)?UiFit.(tablet|wrap)\\(newBase\\)\\) }").containsMatchIn(f.readText()))
    }

    @Test fun phoneStaysExactlySame() {
        val a = org.robolectric.Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        UiFit.center(a)                                        // DKApp हर स्क्रीन पर यही बुलाता है
        assertEquals(a.applicationContext.resources.displayMetrics.density, a.resources.displayMetrics.density, 0.0001f)
        assertEquals(0, a.findViewById<android.view.ViewGroup>(android.R.id.content).paddingLeft)
    }

    @Config(qualifiers = "sw800dp-w1280dp-h800dp-land")
    @Test fun tabletIsBiggerAndCentered() {
        val a = org.robolectric.Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        UiFit.center(a)
        val base = a.applicationContext.resources.displayMetrics.density
        assertTrue("टैबलेट पर UI बड़ा", a.resources.displayMetrics.density >= base * 1.25f)
        assertTrue("टैबलेट पर बीच में", a.findViewById<android.view.ViewGroup>(android.R.id.content).paddingLeft > 0)
    }

    @Config(qualifiers = "sw800dp-w800dp-h1280dp-port")
    @Test fun tabletPortraitBigger() {
        val a = org.robolectric.Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertTrue(a.resources.displayMetrics.density > a.applicationContext.resources.displayMetrics.density * 1.15f)
    }
}
