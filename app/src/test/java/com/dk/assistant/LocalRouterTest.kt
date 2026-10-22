package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.33: local-first रूटर - कौन सी बात फ़ोन पर ही होगी, कौन सी AI को जाएगी */
class LocalRouterTest {
    private val wake = listOf("डीके", "दीके", "डी के", "dk", "d k")
    private fun type(t: String, fg: String = "") = LocalRouter.route(t, wake, fg)?.action?.optString("type")
    private fun act(t: String, fg: String = "") = LocalRouter.route(t, wake, fg)!!.action

    @Test fun openAnyAppHindiEnglishHinglish() {
        assertEquals("open_app", type("Hey DK, open YouTube."))
        assertEquals("youtube", act("Hey DK, open YouTube.").optString("name"))
        assertEquals("open_app", type("यूट्यूब खोलो"))
        assertEquals("open_app", type("Open Gallery."))
        assertEquals("open_app", type("Open Chrome"))
        assertEquals("open_app", type("youtube kholo"))
        assertEquals("snapchat", act("snapchat open karo").optString("name"))
        assertEquals("instagram", act("launch instagram").optString("name"))
        assertEquals("open_app", type("Open the phone app"))
        assertEquals("open_app", type("Open my shopping app"))
        // कोई भी ऐप - सिर्फ़ YouTube/Facebook नहीं (नाम फ़ोन पर ढूँढा जाता है)
        assertEquals("open_app", type("स्नैपचैट खोलो"))
    }

    @Test fun settingsStillOldPath() {
        assertEquals("open_settings", type("Open Settings."))
        assertEquals("torch", type("टॉर्च जलाओ"))
        assertEquals("answer_call", type("कॉल उठा लो"))
        assertEquals("end_call", type("कॉल काट दो"))
    }

    @Test fun youtubeSearchAndPlayLocal() {
        val s = act("Search Arijit Singh songs on YouTube")
        assertEquals("youtube_search", s.optString("type"))
        assertEquals("arijit singh songs", s.optString("query"))
        assertEquals("youtube_search", type("यूट्यूब पर अरिजीत सिंह के गाने सर्च करो"))
        val p = act("Play the latest song by Arijit Singh")
        assertEquals("youtube_play", p.optString("type"))
        assertTrue(p.optString("query").contains("arijit singh"))
        assertEquals("youtube_play", type("यूट्यूब पर अरिजीत सिंह के गाने चलाओ"))
        // YouTube सामने खुला हो तो "X सर्च करो" उसी में
        assertEquals("youtube_search", type("Search for Android tutorials", LocalRouter.YT_PKG))
        // YouTube सामने नहीं = आम सर्च (पहले जैसा, AI/रिसर्च)
        assertNull(type("Search for Android tutorials"))
        // "यह वीडियो चलाओ" = स्क्रीन समझनी पड़ेगी -> AI
        assertNull(type("Play this video", LocalRouter.YT_PKG))
    }

    @Test fun callsAndContacts() {
        val c = act("Call Rahul")
        assertEquals("call_contact", c.optString("type"))
        assertEquals("rahul", c.optString("name"))
        assertEquals("राहुल", act("राहुल को कॉल करो").optString("name"))
        assertEquals("rahul sharma", act("Rahul Sharma ko call karo").optString("name"))
        assertEquals("मम्मी", act("मम्मी को फोन लगाओ").optString("name"))
        assertEquals("पापा जी", act("पापा जी को कॉल करो").optString("name"))
        assertEquals("call_log", type("Show my recent calls"))
        assertEquals("call_log", type("मिस्ड कॉल दिखाओ"))
        assertEquals("open_app", type("Open the phone app"))
        // स्क्रीन वाला कॉन्टैक्ट / मना करना = फ़ोन खुद कॉल नहीं करेगा
        assertNull(type("Call this contact"))
        assertNull(type("राहुल को कॉल मत करो"))
        assertNull(type("don't call rahul"))
        // मैसेज = पहले जैसा AI
        assertNull(type("राहुल को मैसेज भेजो कि मैं आ रहा हूँ"))
    }

    @Test fun galleryLocalDeleteNot() {
        assertEquals("open_app", type("Open Gallery"))
        assertEquals("gallery_latest", type("Show my latest photo"))
        assertEquals("gallery_latest", type("मेरी आखिरी फोटो दिखाओ"))
        assertNull("मिटाना कभी अपने आप नहीं", type("Delete this photo"))
        assertNull(type("latest photo delete karo"))
    }

    @Test fun shoppingSearchLocalCartNot() {
        val s = act("search black t-shirt on flipkart")
        assertEquals("shop_search", s.optString("type"))
        assertEquals("flipkart", s.optString("app"))
        assertEquals("black t-shirt", s.optString("query"))
        assertEquals("shop_search", type("फ्लिपकार्ट पर काली टी-शर्ट सर्च करो"))
        assertEquals("open_app", type("flipkart kholo"))
        assertNull("कार्ट = स्क्रीन देखकर पक्का करना होगा", type("Add the first product to cart"))
    }

    @Test fun multiStepFullyLocal() {
        val a = act("Open YouTube, search Arijit Singh, and play the first video")
        assertEquals("youtube_play", a.optString("type"))
        assertEquals("arijit singh", a.optString("query"))
        val b = act("Open Flipkart and search for black T-shirt under 500")
        assertEquals("shop_search", b.optString("type"))
        assertEquals("black t-shirt under 500", b.optString("query"))
        assertEquals("gallery_latest", type("Open Gallery and show my latest photo"))
        val st = act("टॉर्च जलाओ और यूट्यूब खोलो")
        assertEquals("steps", st.optString("type"))
        assertEquals(2, st.getJSONArray("steps").length())
        // "and" नाम का हिस्सा हो तो अलग कदम नहीं
        assertEquals("tom and jerry", act("search tom and jerry on youtube").optString("query"))
        assertEquals("youtube_play", type("open youtube and play tom and jerry"))
    }

    @Test fun multiStepPartialGoesToAiWithLocalPrefix() {
        val sp = LocalRouter.split("Open Flipkart, search black T-shirt under 500 and add the first product to cart", wake)
        assertEquals("shop_search", sp.local?.action?.optString("type"))
        assertNotNull("बाकी (कार्ट) AI को", sp.rest)
        assertNull(LocalRouter.route("Open Flipkart, search black T-shirt under 500 and add the first product to cart", wake))
        // कॉल + अनजान काम = आधा नहीं, पूरा AI को
        val c = LocalRouter.split("call rahul and tell him i am late", wake)
        assertNull(c.local)
    }

    @Test fun normalizeStripsHeyAndWakeWord() {
        assertEquals("open youtube", LocalRouter.normalize("Hey DK, open YouTube.", wake))
        assertEquals("यूट्यूब खोलो", LocalRouter.normalize("डीके यूट्यूब खोलो", wake))
        assertEquals("पापा जी को कॉल करो", LocalRouter.normalize("भाई पापा जी को कॉल करो", wake))
    }

    @Test fun honestResultTexts() {
        assertEquals("\"xyz\" नाम का ऐप फ़ोन में नहीं मिला।", LocalRouter.userText("ERROR_NOT_INSTALLED: \"xyz\" नाम का ऐप फ़ोन में नहीं मिला।\nINSTALLED: A, B"))
        assertTrue(LocalRouter.final("ERROR_NOT_INSTALLED: x"))
        assertTrue(LocalRouter.final("ASK: कौन सा?"))
        assertTrue(LocalRouter.needsUser("ASK: कौन सा?"))
        assertFalse(LocalRouter.final("ERROR: tap"))
        assertNull(LocalRouter.userText("opened YouTube (verified)"))
    }

    @Test fun cartVerification() {
        assertTrue(LocalRouter.isCartGoal("पहला प्रोडक्ट कार्ट में डालो"))
        assertFalse("फ्लिपकार्ट का नाम = कार्ट नहीं", LocalRouter.isCartGoal("फ्लिपकार्ट पर जूते दिखाओ"))
        assertTrue(LocalRouter.cartConfirmed("[3] Added to cart\n[4] GO TO CART"))
        assertTrue(LocalRouter.cartConfirmed("आइटम कार्ट में जोड़ा गया"))
        assertFalse(LocalRouter.cartConfirmed("[1] Add to cart\n[2] Buy now"))
    }

    @Test fun liveFastNeverCallsFromCaption() {
        // caption (Gemini से पहले) से कॉल नहीं - Gemini का phone_task आए तब (फिर भी फ़ोन पर, बिना AgentRunner)
        assertNull(LiveFast.pick("राहुल को कॉल करो", wake, fast = true))
        assertEquals("call_contact", LiveFast.pick("राहुल को कॉल करो", wake)?.action?.optString("type"))
        assertNotNull(LiveFast.pick("यूट्यूब खोलो", wake, fast = true))
    }

    @Test fun liveResultsHonest() {
        val q = LiveFast.pick("राहुल को कॉल करो", wake)!!
        assertTrue(LiveFast.quickResult(q, "ASK: 2 राहुल हैं", false).startsWith("NEEDS USER CHOICE"))
        val d = LiveFast.quickResult(q, "DIALER: राहुल का नंबर डायलर में भर दिया है", false)
        assertTrue(d.startsWith("DONE") && d.contains("डायलर"))
        val yt = LiveFast.pick("यूट्यूब पर भजन चलाओ", wake)!!
        assertTrue(LiveFast.ok("youtube_play", "VIDEO_OPENED: खोल दिया"))
        assertTrue(LiveFast.quickResult(yt, "VIDEO_OPENED: वीडियो खोल दिया, पर चलना पक्का नहीं", false).contains("पक्का नहीं"))
        assertFalse(LiveFast.ok("open_app", "ASK: कौन सा?"))
        assertTrue(LiveFast.quickResult(q, "ERROR_NO_CONTACT: नहीं मिला", false).startsWith("FAILED"))
    }
}
