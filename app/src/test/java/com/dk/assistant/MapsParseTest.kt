package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** मैप: बोली बात से सही जगह का नाम + सही काम (खोज / रास्ता / नेविगेशन) - बिना AI, तुरंत */
class MapsParseTest {
    private val wake = listOf("डीके", "दीके", "डी के", "dk", "d k")
    private fun r(t: String) = LocalRouter.route(t, wake)?.action
    private fun type(t: String) = r(t)?.optString("type")
    private fun to(t: String) = r(t)!!.let { it.optString("to").ifBlank { it.optString("query") } }

    @Test fun searchOnMap_hindi() {
        assertEquals("map_search", type("मैप पर पंचवटी सर्च करो"))
        assertEquals("पंचवटी", to("मैप पर पंचवटी सर्च करो"))
        assertEquals("नासिक रोड स्टेशन", to("गूगल मैप में नासिक रोड स्टेशन ढूंढो"))
        assertEquals("map_search", type("मैप में राम कुंड दिखाओ"))
        assertEquals("राम कुंड", to("मैप में राम कुंड दिखाओ"))
        assertEquals("map_search", type("गूगल मैप्स पर सिटी सेंटर मॉल खोजो"))
        assertEquals("सिटी सेंटर मॉल", to("गूगल मैप्स पर सिटी सेंटर मॉल खोजो"))
    }

    @Test fun searchThenDirection_startsNavigation_withCleanName() {
        assertEquals("navigate", type("मैप पर पंचवटी सर्च करके डायरेक्शन लगा दो"))
        assertEquals("पंचवटी", to("मैप पर पंचवटी सर्च करके डायरेक्शन लगा दो"))
        // बोलने में "डिस्ट्रेक्शन" सुनाई दे तब भी
        assertEquals("navigate", type("डीके मैप पर त्र्यंबकेश्वर मंदिर सर्च करके डिस्ट्रेक्शन लगा दो"))
        assertEquals("त्र्यंबकेश्वर मंदिर", to("डीके मैप पर त्र्यंबकेश्वर मंदिर सर्च करके डिस्ट्रेक्शन लगा दो"))
        assertEquals("नासिक स्टेशन", to("मैप में नासिक स्टेशन खोजकर नेविगेशन शुरू करो"))
        assertEquals("navigate", type("मैप में नासिक स्टेशन खोजकर नेविगेशन शुरू करो"))
    }

    @Test fun directionWord() {
        assertEquals("navigate", type("सिन्नर का डायरेक्शन लगा दो"))
        assertEquals("सिन्नर", to("सिन्नर का डायरेक्शन लगा दो"))
        assertEquals("directions", type("पुणे का डायरेक्शन दिखाओ"))
        assertEquals("पुणे", to("पुणे का डायरेक्शन दिखाओ"))
    }

    @Test fun openMapsAnd_isOneMapTask() {
        assertEquals("navigate", type("मैप खोलो और गंगापुर रोड का डायरेक्शन लगा दो"))
        assertEquals("गंगापुर रोड", to("मैप खोलो और गंगापुर रोड का डायरेक्शन लगा दो"))
        assertEquals("map_search", type("गूगल मैप खोलो और कॉलेज रोड सर्च करो"))
        assertEquals("कॉलेज रोड", to("गूगल मैप खोलो और कॉलेज रोड सर्च करो"))
    }

    @Test fun english() {
        assertEquals("navigate", type("navigate to nashik road"))
        assertEquals("nashik road", to("navigate to nashik road"))
        assertEquals("map_search", type("search sula vineyards on google maps"))
        assertEquals("sula vineyards", to("search sula vineyards on google maps"))
        assertEquals("navigate", type("open google maps and start directions to mumbai airport"))
        assertEquals("mumbai airport", to("open google maps and start directions to mumbai airport"))
    }

    @Test fun oldPhrases_stillSame() {
        assertEquals("directions", type("पुणे का रास्ता दिखाओ"))
        assertEquals("पुणे", to("पुणे का रास्ता दिखाओ"))
        assertEquals("navigate", type("नासिक रोड ले चलो"))
        assertEquals("navigate", type("पंचवटी के लिए नेविगेशन चालू करो"))
        assertEquals("पंचवटी", to("पंचवटी के लिए नेविगेशन चालू करो"))
        assertEquals("distance", type("पंचवटी कितनी दूर है"))
        assertEquals("map_search", type("पास में पेट्रोल पंप दिखाओ"))
        assertEquals("open_app", type("मैप खोलो"))
    }

    @Test fun otherApps_notTakenByMap() {
        assertNotEquals("map_search", type("मैप खोलो और यूट्यूब पर गाना चलाओ"))
        assertNull(Maps.mergeOpen("मैप खोलो और राहुल को कॉल करो"))
        assertNull(Maps.parse("यूट्यूब पर अरिजीत का गाना सर्च करो"))
    }

    @Test fun collegeRoad_isPlace_notCall() {
        assertEquals("map_search", type("मैप पर कॉलेज रोड सर्च करो"))
        assertEquals("कॉलेज रोड", to("मैप पर कॉलेज रोड सर्च करो"))
        assertNull(Maps.parse("राहुल को कॉल करो"))
    }

    @Test fun place_cleaner() {
        assertEquals("पंचवटी", Maps.place("पंचवटी सर्च करके डायरेक्शन लगा दो"))
        assertEquals("नासिक रोड", Maps.place("नासिक रोड के लिए नेविगेशन स्टार्ट करो"))
    }
}
