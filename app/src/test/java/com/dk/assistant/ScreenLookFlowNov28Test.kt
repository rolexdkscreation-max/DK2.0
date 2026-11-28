package com.dk.assistant

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 28 नवंबर 2026 (मालिक): अपना काम (मैप / गाना / Flipkart सर्च, स्क्रॉल, "पसंद नहीं, और दिखाओ") = स्क्रीन बिना पूछे;
 * खोज के बाद नतीजे पढ़कर बताना और अगला कदम पूछना; "DK लेंस ओपन करो" = तुरंत, लेंस चालू रहने तक दोबारा नहीं पूछना;
 * "कॉल करो" = आम फ़ोन कॉल, WhatsApp तभी जब बोला; गैलरी = पहले की तरह हमेशा एक बार पूछो.
 */
class ScreenLookFlowNov28Test {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()
    private fun j(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { put(it.first, it.second) } }

    @After fun clean() { PrivacyGate.lensOn = false; PrivacyGate.reset() }

    @Test fun ownAppTask_screenWithoutAsking() {
        for (t in listOf("मैप पे पेट्रोल पंप सर्च करो और देखो है या नहीं", "पवन सिंह का सॉन्ग सर्च करो", "Flipkart पे जूते सर्च करो",
            "नीचे स्क्रॉल करो", "पहला वाला प्ले करो", "youtube pe pawan singh ka song search karo")) {
            assertTrue(t, PrivacyGate.explicitOk(PrivacyGate.Kind.SCREEN, t))
        }
        // "नहीं, ये पसंद नहीं - और दिखाओ" = आगे देखना, मना नहीं
        assertTrue(PrivacyGate.screenTask("नहीं, ये पसंद नहीं आ रहा, और दिखाओ"))
        assertTrue(PrivacyGate.screenTask("मतलब ये अच्छा नहीं है, नीचे स्क्रॉल करो"))
        // साफ़ मना = मना ही
        assertFalse(PrivacyGate.screenTask("स्क्रॉल मत करो"))
        assertFalse(PrivacyGate.explicitOk(PrivacyGate.Kind.SCREEN, "कार्ट में मत डालो"))
        assertFalse(PrivacyGate.explicitOk(PrivacyGate.Kind.SCREEN, "आज मौसम कैसा है"))
        // गैलरी = पहले की तरह हमेशा पूछो
        assertFalse(PrivacyGate.explicitOk(PrivacyGate.Kind.GALLERY, "गैलरी में फोटो सर्च करो"))
    }

    @Test fun lens_opensAtOnce_andNoRepeatAsk() {
        assertTrue(PrivacyGate.lensSaid("डीके लेंस ओपन करो"))
        assertTrue(PrivacyGate.lensSaid("DK लैंस खोलो"))
        assertTrue(PrivacyGate.explicitOk(PrivacyGate.Kind.CAMERA, "डीके लेंस ओपन करो"))
        assertFalse(PrivacyGate.lensSaid("लेंस बंद करो"))
        // लेंस यूज़र ने खोला और चालू है = कैमरा देखना बिना पूछे
        PrivacyGate.lensOn = true
        assertNull(PrivacyGate.gate("camera", j("on" to true)))
        PrivacyGate.lensOn = false
        assertTrue(src("GeminiLiveQueue.kt").contains("PrivacyGate.lensSaid(req)"))
        assertTrue(src("DKAccessibilityYt.kt").contains("!PrivacyGate.lensOn && !PrivacyGate.explicitNow(PrivacyGate.Kind.CAMERA)"))
        assertTrue(src("LiveVision.kt").contains("PrivacyGate.lensOn = cameraOn || cameraWanted"))
        assertTrue(PrivacyGate.RULE.contains("DK लेंस खुद खोला"))
    }

    @Test fun search_readsResults_andAsksNext() {
        assertEquals(SearchSee.Kind.MAP, SearchSee.kind(j("type" to "map_search", "query" to "पेट्रोल पंप")))
        assertEquals(SearchSee.Kind.SONG, SearchSee.kind(j("type" to "youtube_search", "query" to "पवन सिंह")))
        assertEquals(SearchSee.Kind.SHOP, SearchSee.kind(j("type" to "shop_search", "app" to "flipkart")))
        assertEquals(SearchSee.Kind.SHOP, SearchSee.kind(j("type" to "scroll"), "com.flipkart.android"))
        // चैट / गैलरी में स्क्रॉल = कुछ नहीं पढ़ना
        assertNull(SearchSee.kind(j("type" to "scroll"), "com.whatsapp"))
        assertNull(SearchSee.kind(j("type" to "app_search", "app" to "whatsapp")))
        assertNull(SearchSee.kind(j("type" to "open_app")))
        val screen = "[0] TextView \"Search\" {click} @1,1\n[1] TextView \"Nike Running Shoes\" @2,2\n[2] TextView \"₹1,299\" @3,3\n" +
            "[3] ImageView (Nike Running Shoes) @4,4\n[4] Button \"Add to cart\" {click} @5,5"
        val seen = SearchSee.lines(screen)
        assertEquals(listOf("Nike Running Shoes", "₹1,299", "Add to cart"), seen)
        val shop = SearchSee.note(SearchSee.Kind.SHOP, "shoes", seen)
        assertTrue(shop.contains("कार्ट में ऐड कर दूँ?") && shop.contains("नीचे स्क्रॉल करो") && shop.contains("Nike Running Shoes"))
        assertTrue(SearchSee.note(SearchSee.Kind.SONG, "पवन सिंह", listOf("Pawan Singh new song")).contains("चलाऊँ"))
        assertTrue(SearchSee.note(SearchSee.Kind.MAP, "पेट्रोल पंप", listOf("HP Petrol Pump")).contains("रास्ता लगा दूँ?"))
        assertTrue(SearchSee.note(SearchSee.Kind.MAP, "x", emptyList()).contains("Never invent"))
        val q = src("GeminiLiveQueue.kt")
        assertTrue(q.contains("withSeen(q, r, LiveFast.quickResult(q, r, already = false))"))
        assertTrue(q.contains("withSeen(q, r, LiveFast.quickResult(q, r, already = true))"))
    }

    @Test fun call_isNormalUnlessWhatsAppSaid() {
        assertTrue(WaCall.plainCallInstead("राहुल को कॉल करो", video = false))
        assertFalse(WaCall.plainCallInstead("व्हाट्सएप पे राहुल को कॉल करो", video = false))
        assertFalse(WaCall.plainCallInstead("whatsapp call karo rahul ko", video = false))
        assertFalse(WaCall.plainCallInstead("राहुल को वीडियो कॉल करो", video = true))
        assertTrue(src("GeminiLiveTools.kt").contains("WaCall.plainCallInstead(heard, cmd.video)"))
    }
}
