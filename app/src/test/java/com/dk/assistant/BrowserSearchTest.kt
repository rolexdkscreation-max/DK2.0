package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.75: "क्रोम खोलो और उसमें X वेबसाइट सर्च करो" = सीधे Chrome में (पहले सिर्फ़ Chrome खुलता था); कोई भी ऐप में खोज */
class BrowserSearchTest {
    private fun act(t: String) = LocalRouter.route(t, listOf("डीके", "dk"), "")?.action

    @Test fun openChromeThenSearchWebsite() {
        val a = act("क्रोम खोलो और उसमें फ्लिपकार्ट वेबसाइट सर्च करो")!!
        assertEquals("browser_search", a.optString("type"))
        assertEquals("फ्लिपकार्ट", a.optString("query"))
    }

    @Test fun chromePeSearch() {
        val a = act("क्रोम पर नासिक का मौसम सर्च करो")!!
        assertEquals("browser_search", a.optString("type"))
        assertEquals("नासिक का मौसम", a.optString("query"))
        assertEquals("chrome", a.optString("browser"))
    }

    @Test fun hinglishAndDomain() {
        val a = act("chrome kholo aur usme amazon dot in kholo")!!
        assertEquals("browser_search", a.optString("type"))
        assertEquals("amazon.in", a.optString("query"))
        assertEquals("https://amazon.in", AppLauncher.urlOf("amazon dot in"))
        assertNull(AppLauncher.urlOf("नासिक का मौसम"))
    }

    @Test fun googlePeSearch() {
        val a = act("गूगल पर आज की खबरें सर्च करो")!!
        assertEquals("browser_search", a.optString("type"))
        assertEquals("आज की खबरें", a.optString("query"))
    }

    @Test fun plainOpenChromeStillOpens() {
        val a = act("क्रोम खोलो")!!
        assertEquals("open_app", a.optString("type"))
    }

    @Test fun anyInstalledAppSearch() {
        val a = act("स्पॉटिफाई में अरिजीत सिंह सर्च करो")!!
        assertEquals("app_search", a.optString("type"))
        assertEquals("स्पॉटिफाई", a.optString("app"))
        assertEquals("अरिजीत सिंह", a.optString("query"))
        val b = act("इंस्टाग्राम खोलो और उसमें विराट कोहली सर्च करो")!!
        assertEquals("app_search", b.optString("type"))
        assertEquals("विराट कोहली", b.optString("query"))
    }

    @Test fun youtubeAndShopsUnchanged() {
        assertEquals("youtube_search", act("यूट्यूब खोलो और उसपे पवन सिंह का सॉन्ग सर्च करो")!!.optString("type"))
        assertTrue(act("फ्लिपकार्ट पर जूते सर्च करो")!!.optString("type") == "shop_search")
    }
}
