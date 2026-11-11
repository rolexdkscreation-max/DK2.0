package com.dk.assistant

import com.dk.assistant.agent.android.LockedWork
import com.dk.assistant.agent.social.WaHinglish
import com.dk.assistant.agent.social.WaScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक): DK Live - एग्ज़ैक्ट लोकेशन, "बाजू में क्या है / पेट्रोल पंप कहाँ है", लॉक स्क्रीन पर काम, WhatsApp में Hinglish */
class LiveLocationLockHinglishTest {
    private fun src(name: String): String = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()
    private fun res(name: String): String = listOf(File("src/main/$name"), File("app/src/main/$name")).first { it.exists() }.readText()

    // ---- सटीक लोकेशन
    @Test fun betterFixIsTheMoreAccurateOne() {
        assertTrue(NearbyPlaces.better(null, 900f))
        assertTrue(NearbyPlaces.better(900f, 12f))
        assertFalse(NearbyPlaces.better(12f, 900f))
        assertFalse(NearbyPlaces.better(12f, null))       // accuracy नहीं = बेहतर नहीं
        assertTrue(NearbyPlaces.GOOD_ACC_M <= 30f)
    }

    @Test fun exactAddressAndAccuracyInToolText() {
        val a = NearbyPlaces.parseAddr("""{"name":"शिव मेडिकल","display_name":"12, शिव मेडिकल, गंगापुर रोड, नाशिक 422013","address":{"house_number":"12","road":"गंगापुर रोड","suburb":"कॉलेज रोड","city":"नाशिक","state":"महाराष्ट्र"}}""")
        assertEquals("12", a.house); assertEquals("शिव मेडिकल", a.spot)
        assertEquals("गंगापुर रोड, कॉलेज रोड, नाशिक, महाराष्ट्र", a.text())   // पुराना text() वैसा ही
        val shops = listOf(NearbyPlaces.Spot("राम किराना", "किराना / जनरल स्टोर", 40))
        val t = NearbyPlaces.toolText(a, emptyList(), null, 8, shops)
        assertTrue(t.contains("Exact spot: 12, शिव मेडिकल"))
        assertTrue(t.contains("Full address: 12, शिव मेडिकल, गंगापुर रोड"))
        assertTrue(t.contains("about 8 m"))
        assertTrue(t.contains("राम किराना (किराना / जनरल स्टोर, बिल्कुल पास)"))
        assertTrue(NearbyPlaces.toolText(a, emptyList(), null, 1500).contains("लगभग"))
        assertTrue(NearbyPlaces.toolText(a, emptyList(), null, 20, precise = false).contains("Precise location"))
        // पुराना 3-argument वाला रूप चलता रहे
        assertTrue(NearbyPlaces.toolText(a, emptyList(), null).contains("FRESH"))
    }

    // ---- "पास में X"
    @Test fun spokenWordsMapToMapTags() {
        assertEquals(listOf("amenity=fuel"), NearbyPlaces.findTags("पेट्रोल पंप"))
        assertEquals(listOf("amenity=fuel"), NearbyPlaces.findTags("petrol pump"))
        assertEquals(listOf("amenity=atm"), NearbyPlaces.findTags("ATM"))
        assertTrue(NearbyPlaces.findTags("मेडिकल स्टोर").contains("amenity=pharmacy"))
        assertTrue(NearbyPlaces.findTags("दवाखाना").contains("amenity=hospital"))
        assertTrue(NearbyPlaces.findTags("किराना दुकान").contains("shop=convenience"))
        assertTrue(NearbyPlaces.findTags("कपड़े की दुकान").contains("shop=clothes"))
        assertTrue(NearbyPlaces.findTags("पंक्चर वाला").contains("shop=tyres"))
        assertEquals(listOf("amenity=police"), NearbyPlaces.findTags("पुलिस स्टेशन"))
        assertEquals(listOf("shop=*"), NearbyPlaces.findTags("कोई दुकान"))
        assertTrue(NearbyPlaces.findTags("xyz").isEmpty())
        assertTrue(NearbyPlaces.findTags("").isEmpty())
    }

    @Test fun findQueryAndNearestFirst() {
        val q = NearbyPlaces.findQuery(20.0, 73.78, listOf("amenity=fuel", "shop=*"))
        assertTrue(q.contains("around:3000,20.000000,73.780000"))
        assertTrue(q.contains("[\"amenity\"=\"fuel\"][\"name\"]"))
        assertTrue(q.contains("[\"shop\"][\"name\"]"))
        assertTrue(NearbyPlaces.shopsQuery(20.0, 73.78).contains("around:250,"))
        val j = """{"elements":[
            {"type":"node","lat":20.0100,"lon":73.7800,"tags":{"amenity":"fuel","name":"Far Pump"}},
            {"type":"node","lat":20.0004,"lon":73.7800,"tags":{"amenity":"fuel","name":"HP Petrol Pump"}},
            {"type":"node","lat":20.0004,"lon":73.7800,"tags":{"amenity":"fuel"}},
            {"type":"node","lat":20.0002,"lon":73.7800,"tags":{"amenity":"bank","name":"SBI"}}]}"""
        val f = NearbyPlaces.parseFound(j, 20.0, 73.78, listOf("amenity=fuel"))
        assertEquals(listOf("HP Petrol Pump", "Far Pump"), f.map { it.name })
        assertEquals("पेट्रोल पंप", f[0].kind)
        val shops = NearbyPlaces.parseFound("""{"elements":[{"type":"node","lat":20.0001,"lon":73.78,"tags":{"shop":"mobile_phone","name":"Jio Point"}},
            {"type":"node","lat":20.0002,"lon":73.78,"tags":{"shop":"weird","name":"Z"}}]}""", 20.0, 73.78, listOf("shop=*"))
        assertEquals("मोबाइल शॉप", shops[0].kind); assertEquals("दुकान (weird)", shops[1].kind)
        val addr = NearbyPlaces.Addr("गंगापुर रोड", "कॉलेज रोड", "नाशिक", "महाराष्ट्र")
        assertTrue(NearbyPlaces.findText("पेट्रोल पंप", addr, f, 10).contains("सबसे पास HP Petrol Pump"))
        assertTrue(NearbyPlaces.findText("पेट्रोल पंप", addr, emptyList()).contains("No \"पेट्रोल पंप\" found"))
        assertTrue(NearbyPlaces.findText("जूते का शोरूम xyz", addr, emptyList(), known = false).contains("Google Search"))
    }

    @Test fun liveToolHasFindAndPreciseFix() {
        val setup = src("GeminiLiveSetup.kt")
        assertTrue(setup.contains(".put(\"find\", JSONObject().put(\"type\", \"STRING\")"))
        assertTrue(src("GeminiLiveTools.kt").contains("\"local_info\" -> localInfoTool(args.optString(\"find\"))"))
        val li = src("LocalInfo.kt")
        assertTrue(li.contains("fresh(c, 10_000)?.let { sharpen(c, it) }"))
        assertTrue(li.contains("LocationManager.FUSED_PROVIDER"))
        assertFalse(li.contains("getLastKnownLocation("))
        // स्क्रीन बंद / लॉक पर भी लोकेशन: service में location type
        assertTrue(res("AndroidManifest.xml").contains("android:foregroundServiceType=\"microphone|location\""))
        assertTrue(res("AndroidManifest.xml").contains("android.permission.FOREGROUND_SERVICE_LOCATION"))
        assertTrue(src("KeepAliveService.kt").contains("FOREGROUND_SERVICE_TYPE_LOCATION"))
    }

    // ---- लॉक स्क्रीन
    @Test fun whichWorkNeedsTheScreen() {
        for (t in listOf("call_contact", "sms", "alarm", "timer", "torch", "volume", "media", "lock", "answer_call", "find_phone"))
            assertFalse(t, LockedWork.needsScreen(t))
        for (t in listOf("open_app", "youtube_play", "map_search", "tap", "steps", "wa_call"))
            assertTrue(t, LockedWork.needsScreen(t))
        assertFalse(LockedWork.waNeedsScreen("auto_chat_on")); assertFalse(LockedWork.waNeedsScreen("auto_chat_off"))
        assertTrue(LockedWork.waNeedsScreen("send_message")); assertTrue(LockedWork.waRelock("send_message"))
        assertTrue(LockedWork.waRelock("smart_reply")); assertFalse(LockedWork.waRelock("video_call"))
        assertTrue(LockedWork.LOCKED_NOTE.contains("फ़ोन लॉक / अनलॉक"))
    }

    @Test fun lockedWorkIsWired() {
        assertTrue(src("GeminiLiveTools.kt").contains("LockedWork.wa(svc, args.optString(\"action\"))"))
        assertTrue(src("GeminiLiveQueue.kt").contains("lw.needsScreen(q.action.optString(\"type\"))"))
        assertTrue(src("agent/android/LockedWork.kt").contains("GLOBAL_ACTION_LOCK_SCREEN"))
    }

    // ---- WhatsApp Hinglish
    @Test fun whatsappWritesHinglish() {
        assertTrue(WaScreen.SYSTEM.contains("ALWAYS write in Hinglish"))
        assertTrue(WaScreen.SYSTEM.contains("never Devanagari script"))
        assertFalse(WaScreen.SYSTEM.contains("Hindi in Devanagari"))
        assertTrue(WaHinglish.need("कल मिलते हैं"))
        assertFalse(WaHinglish.need("Kal milte hain bhai"))
        assertEquals("Kal milte hain", WaHinglish.clean("\"Kal milte hain\"", "कल मिलते हैं"))
        assertNull(WaHinglish.clean("कल मिलते हैं", "कल मिलते हैं"))
        assertNull(WaHinglish.clean("", "कल"))
        assertNull(WaHinglish.clean("x".repeat(200), "कल"))
        assertTrue(src("GeminiLiveSetup.kt").contains("ALWAYS written in Hinglish"))
        assertTrue(src("GeminiLiveTools.kt").contains("waHinglish(args)"))
    }
}
