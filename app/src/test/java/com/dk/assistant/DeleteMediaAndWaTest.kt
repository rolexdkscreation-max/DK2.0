package com.dk.assistant

import com.dk.assistant.agent.core.Box
import com.dk.assistant.agent.core.ScreenObservation
import com.dk.assistant.agent.core.UiElement
import com.dk.assistant.agent.social.WaDelete
import com.dk.assistant.agent.social.WaRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

/** नवंबर 2026: "आज के सारे स्क्रीनशॉट / ऊपर से 50 / नीचे से 10 फोटो डिलीट करो" + WhatsApp पर भेजा मैसेज डिलीट */
class DeleteMediaAndWaTest {
    private fun go(t: String) = (MediaPick.parse(t) as? MediaPick.Out.Go)?.spec
    private fun ask(t: String) = MediaPick.parse(t) is MediaPick.Out.Ask

    @Test fun dayWise() {
        val a = go("आज के सारे स्क्रीनशॉट डिलीट करो")!!
        assertEquals("screenshot", a.kind); assertEquals(0, a.day); assertTrue(a.all)
        val b = go("जो आज का जितना भी स्क्रीनशॉट है डिलीट करो")!!
        assertEquals(0, b.day); assertTrue(b.all)
        val c = go("कल का स्क्रीनशॉट डिलीट करो")!!
        assertEquals(1, c.day); assertTrue(c.all)
        assertEquals(1, go("yesterday ke screenshot delete karo")!!.day)
        val d = go("आज की 5 फोटो डिलीट कर दो")!!
        assertEquals("photo", d.kind); assertEquals(5, d.count); assertEquals(0, d.day); assertFalse(d.all)
    }

    @Test fun topAndBottom() {
        val a = go("ऊपर से 50, 60 फोटो डिलीट करो")!!
        assertEquals(50, a.count); assertEquals(MediaPick.From.NEW, a.from)
        val b = go("नीचे से 10, 12 फोटो डिलीट करो")!!
        assertEquals(10, b.count); assertEquals(MediaPick.From.OLD, b.from)
        assertEquals(20, go("सबसे पुरानी बीस फोटो मिटा दो")!!.count)
        assertEquals(MediaPick.From.OLD, go("सबसे पुरानी बीस फोटो मिटा दो")!!.from)
        assertEquals(50, go("last 50 photo delete karo")!!.count)
        assertEquals(2, go("दो फोटो डिलीट कर दो")!!.count)
        assertEquals(1, go("आखिरी फोटो डिलीट कर दो")!!.count)        // "कर दो" का "दो" गिनती नहीं
        assertEquals(30, go("ऊपर से ३० स्क्रीनशॉट हटाओ")!!.count)
        assertEquals("screenshot", go("सारे स्क्रीनशॉट डिलीट करो")!!.kind)
    }

    @Test fun vagueAsksFirst() {
        assertTrue(ask("कुछ फोटो डिलीट करो"))
        assertTrue(ask("फोटो डिलीट करो"))
        assertTrue(ask("सारी फोटो डिलीट करो"))                       // पूरी गैलरी अंदाज़े से नहीं
        assertTrue(ask("ऊपर से 900 फोटो डिलीट करो"))
        assertNull(MediaPick.parse("इस फोटो को डिलीट करो"))            // स्क्रीन पर खुली - एजेंट देखे
        assertNull(MediaPick.parse("आखिरी फोटो डिलीट मत करो"))
        assertNull(MediaPick.parse("राहुल को WhatsApp पर फोटो भेजो"))
        assertNull(MediaPick.parse("WhatsApp पर भेजी फोटो डिलीट करो"))
        assertNull(MediaPick.parse("आज का मौसम बताओ"))
    }

    @Test fun oldPathSame() {
        // पुराना LocalRouter.deleteMedia वैसा ही; quick() पहले उसी को देखता है
        assertEquals(3, MediaPick.quick("last 3 screenshot delete karo").let { (it as MediaPick.Out.Go).spec.count })
        val j = MediaPick.Spec("photo", 1).toJson()
        assertEquals("delete_media", j.optString("type"))
        val back = MediaPick.fromJson(org.json.JSONObject().put("type", "delete_media").put("kind", "photo").put("count", 1))
        assertEquals(-1, back.day); assertEquals(MediaPick.From.NEW, back.from); assertFalse(back.all)
        assertTrue(LiveFast.ok("delete_media", "DELETED: आज के सारे 12 स्क्रीनशॉट मिटा दिए"))
        assertEquals("आज के सारे 12 स्क्रीनशॉट", MediaPick.Spec("screenshot", 500, 0, all = true).hi(12))
        assertEquals("आपने Allow नहीं दबाया - कुछ नहीं मिटाया।", MediaPick.spoken("ERROR_DENIED: आपने Allow नहीं दबाया - कुछ नहीं मिटाया।"))
        assertEquals("किसकी चैट का मैसेज डिलीट करूँ?", MediaPick.spoken("NEEDS USER CHOICE (nothing was deleted): किसकी चैट का मैसेज डिलीट करूँ? Ask the user for the contact name"))
    }

    @Test fun dayRangeIsLocalDay() {
        val tz = TimeZone.getTimeZone("Asia/Kolkata")
        val now = 1_794_000_000_000L
        val today = MediaPick.dayRange(0, now, tz)
        val yday = MediaPick.dayRange(1, now, tz)
        assertTrue(now / 1000 in today)
        assertEquals(86_400L, today.last - today.first + 1)
        assertEquals(today.first, yday.last + 1)
    }

    @Test fun waDeleteDetection() {
        assertTrue(WaDelete.isDelete("WhatsApp पर जो मैसेज गलती से चला गया उसे डिलीट कर दो"))
        assertTrue(WaDelete.isDelete("राहुल को भेजा हुआ मैसेज डिलीट करो"))
        assertTrue(WaDelete.isDelete("whatsapp message delete kar do"))
        assertTrue(WaDelete.isDelete("हाँ उसे डिलीट कर दो", sentRecently = true))
        assertFalse(WaDelete.isDelete("हाँ उसे डिलीट कर दो", sentRecently = false))
        assertFalse(WaDelete.isDelete("राहुल को WhatsApp पर बोल दो कि गाड़ी हटा दो"))
        assertFalse(WaDelete.isDelete("आज के स्क्रीनशॉट डिलीट करो"))
        assertFalse(WaDelete.isDelete("मैसेज डिलीट मत करो"))
        // पहले यह "भेजो" बन जाता था - अब नहीं
        assertFalse(WaRoute.isWhatsAppSend("WhatsApp पर जो मैसेज भेजा वो डिलीट कर दो"))
        assertTrue(WaRoute.isWhatsAppSend("व्हाट्सएप पर राहुल को मैसेज भेजो कि मैं आ रहा हूँ"))
        assertEquals("राहुल", WaDelete.contactIn("राहुल को भेजा मैसेज डिलीट करो"))
        assertEquals("मम्मी", WaDelete.contactIn("मम्मी वाली चैट में आखिरी मैसेज डिलीट करो"))
        assertEquals("", WaDelete.contactIn("जो मैसेज गलती से चला गया उसे डिलीट कर दो"))
        assertTrue(WaDelete.forMeOnly("सिर्फ़ मेरे लिए डिलीट करो"))
        assertFalse(WaDelete.forMeOnly("सबके लिए डिलीट करो"))
    }

    private fun e(id: Int, text: String = "", desc: String = "", vid: String = "", b: Box) = UiElement(id, text, desc, vid, "TextView", bounds = b)

    @Test fun picksMyLastBubble() {
        val o = ScreenObservation(1, "com.whatsapp", listOf(
            e(0, "Rahul", vid = "com.whatsapp:id/conversation_contact_name", b = Box(150, 80, 500, 140)),
            e(1, "कल मिलते हैं", vid = "com.whatsapp:id/message_text", b = Box(60, 600, 500, 660)),
            e(2, "ठीक है", vid = "com.whatsapp:id/message_text", b = Box(700, 700, 1000, 760)),
            e(3, desc = "Read", vid = "com.whatsapp:id/status", b = Box(1010, 770, 1040, 800)),
            e(4, "galat message", vid = "com.whatsapp:id/message_text", b = Box(650, 900, 1000, 960)),
            e(5, desc = "Delivered", vid = "com.whatsapp:id/status", b = Box(1010, 970, 1040, 1000)),
            e(6, "हाय", vid = "com.whatsapp:id/message_text", b = Box(60, 1100, 300, 1160)),
        ))
        assertEquals("galat message", WaDelete.target(o)?.text)
        assertEquals("ठीक है", WaDelete.target(o, "ठीक है")?.text)
        assertFalse(WaDelete.outgoing(o, o.elements[6]))
        val bar = ScreenObservation(2, "com.whatsapp", listOf(e(0, desc = "Delete", vid = "com.whatsapp:id/menuitem_delete", b = Box(800, 60, 880, 140))))
        assertNotNull(WaDelete.deleteButton(bar))
        val dlg = ScreenObservation(3, "com.whatsapp", listOf(
            e(0, "Delete message?", b = Box(100, 900, 900, 960)),
            e(1, "Delete for everyone", b = Box(500, 1000, 980, 1060)),
            e(2, "Delete for me", b = Box(500, 1080, 980, 1140)),
            e(3, "Cancel", b = Box(500, 1160, 980, 1220)),
        ))
        assertEquals("Delete for everyone", WaDelete.everyoneButton(dlg)?.text)
        assertEquals("Delete for me", WaDelete.forMeButton(dlg)?.text)
        assertEquals("Cancel", WaDelete.cancelButton(dlg)?.text)
        val after = ScreenObservation(4, "com.whatsapp", listOf(e(0, "You deleted this message", vid = "com.whatsapp:id/message_text", b = Box(650, 900, 1000, 960))))
        assertTrue(WaDelete.deleted(after, "galat message"))
        assertFalse(WaDelete.deleted(o, "galat message"))
    }

    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    @Test fun wired() {
        val q = src("GeminiLiveQueue.kt")
        assertTrue(q.contains("WaDeleteRunner.run(svc, req)") && q.contains("MediaPick.quick(req)"))
        assertTrue(src("GeminiLiveTools.kt").contains("\"delete_message\""))
        assertTrue(src("GeminiLiveSetup.kt").contains(".put(\"delete_message\")"))
        assertTrue(src("AgentRunner.kt").contains("MediaPick.quick(goal)"))
        assertTrue(src("DKAccessibilityService.kt").contains("MediaDelete.run(this, MediaPick.fromJson(a))"))
        val m = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()
        assertTrue(m.contains("android.permission.MANAGE_MEDIA"))
    }
}
