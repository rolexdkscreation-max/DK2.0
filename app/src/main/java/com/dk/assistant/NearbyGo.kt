package com.dk.assistant

import android.content.Context

/**
 * 14 नवंबर 2026 (मालिक): "कपड़े लेना है / कुछ खरीदना है - पास की अच्छी दुकान या मॉल ढूँढो, सबसे पास वाले का नाम बताओ
 * और जाने का रास्ता लगा दो. पहले 'हाँ ठीक है कर रहा हूँ' मत बोलो, पहले काम करो फिर बात."
 *
 * काम: ताज़ा लोकेशन -> सबसे पास वाली जगह -> Google Maps navigation तुरंत चालू -> AI को एक छोटी लाइन का जवाब.
 * नक्शे (OpenStreetMap) में कुछ न मिले = Google Maps खुद अपने नक्शे में सबसे पास वाली ढूँढकर रास्ता लगाता है.
 * कोई सवाल नहीं पूछा जाता.
 */
object NearbyGo {

    /** Google Maps में खोजने के लिए सीधी अंग्रेज़ी (Maps का "near me" सबसे भरोसेमंद) */
    fun mapsQuery(what: String): String {
        val t = what.lowercase()
        return when {
            listOf("कपड़", "कपड़", "cloth", "kapd", "dress", "ड्रेस", "शर्ट", "shirt", "साड़ी", "saree", "बुटीक", "boutique", "fashion", "फैशन").any { t.contains(it) } -> "clothing store near me"
            listOf("मॉल", "mall").any { t.contains(it) } -> "shopping mall near me"
            listOf("शॉपिंग", "shopping", "खरीद", "kharid").any { t.contains(it) } -> "shopping mall near me"
            else -> "${what.trim()} near me"
        }
    }

    /** मिली जगह -> AI को बताने वाली लाइन (navigation चालू हो चुका) */
    fun startedText(s: NearbyPlaces.Spot, more: List<NearbyPlaces.Spot>): String = buildString {
        append("DONE: Google Maps navigation is ALREADY STARTED to the nearest place: ${s.name} (${s.kind}, ${NearbyPlaces.distText(s.distM)}). ")
        if (more.isNotEmpty()) append("Other close ones: ").append(more.joinToString("; ") { "${it.name} (${NearbyPlaces.distText(it.distM)})" }).append(". ")
        append("Now say ONE short Hindi line, like \"सर, सबसे पास ${s.name} है, करीब ${NearbyPlaces.distText(s.distM)} - रास्ता लगा दिया है।\" No questions.")
    }

    fun mapsText(q: String) =
        "DONE: free map data had no named place, so Google Maps navigation was ALREADY STARTED with \"$q\" (Maps picks the nearest one itself). " +
            "Say ONE short Hindi line: \"सर, Google Maps में सबसे पास वाली जगह का रास्ता लगा दिया है, नाम वहीं ऊपर दिख रहा है।\" No questions."

    /** काम का नतीजा: [spot] = नक्शे में मिली सबसे पास वाली (null = Google Maps की खोज [query] से रास्ता); [opened] = Maps खुला */
    data class Result(val spot: NearbyPlaces.Spot?, val more: List<NearbyPlaces.Spot>, val query: String, val opened: Boolean)

    /** बोली/लिखी बात "पास की दुकान/मॉल का रास्ता लगा दो" या "कपड़े लेना है, पास की दुकान ढूँढो" जैसी है? (Pure) */
    fun wantsGo(text: String): Boolean {
        val t = text.lowercase()
        if (NearbyPlaces.findTags(t).isEmpty()) return false
        val near = listOf("पास", "नज़दीक", "नजदीक", "आसपास", "आस पास", "near", "nearby", "closest", "nearest").any { t.contains(it) }
        val buy = listOf("लेना है", "लेने जाना", "खरीदना", "खरीदने", "ख़रीदना", "शॉपिंग करनी", "shopping").any { t.contains(it) }
        val route = listOf("रास्ता", "रस्ता", "डायरेक्शन", "direction", "नेविगेशन", "navigat", "ले चलो", "जाना है", "जाने के लिए", "जाने का", "route", "रूट", "पहुँचा", "पहुंचा").any { t.contains(it) }
        return route && (near || buy)
    }

    /** पूरा काम (ढूँढो + Maps navigation). null = लोकेशन नहीं मिली */
    suspend fun run(c: Context, what: String, mode: String = "driving"): Result? {
        val (_, found) = LocalInfo.nearest(c, what) ?: return null
        val best = found.firstOrNull()
        if (best != null && best.lat != 0.0) {
            val r = Maps.navigate(c, "${best.lat},${best.lon}", mode)
            DKLog.d("पास की जगह का रास्ता: ${best.name} -> $r")
            if (!r.startsWith("ERROR")) return Result(best, found.drop(1).take(2), "", true)
        }
        val q = mapsQuery(what)
        val r = Maps.navigate(c, q, mode)
        DKLog.d("पास की जगह (Maps खोज) का रास्ता: $q -> $r")
        return Result(null, emptyList(), q, !r.startsWith("ERROR"))
    }

    /** Live (AI) के लिए */
    suspend fun go(c: Context, what: String, mode: String = "driving"): String? {
        val r = run(c, what, mode) ?: return null
        return when {
            !r.opened -> "Google Maps did not open on this phone. Tell him in one short Hindi line."
            r.spot != null -> startedText(r.spot, r.more)
            else -> mapsText(r.query)
        }
    }

    /** सीधा बोलने लायक हिंदी वाक्य (लिखा हुआ / phone_task रास्ता - "SAID:" = बोलकर बताओ) (Pure) */
    fun sayLine(r: Result): String = when {
        !r.opened -> "SAID: सर, इस फ़ोन पर Google Maps नहीं खुला।"
        r.spot != null -> "SAID: सर, सबसे पास ${r.spot.name} है, करीब ${NearbyPlaces.distText(r.spot.distM)} - रास्ता लगा दिया है।"
        else -> "SAID: सर, Google Maps में सबसे पास वाली जगह का रास्ता लगा दिया है, नाम ऊपर दिख रहा है।"
    }

    /** फ़ोन के काम (perform "nearby_go") के लिए */
    suspend fun goSay(c: Context, what: String, mode: String = "driving"): String {
        if (!LocalInfo.hasPermission(c)) return "ERROR_PERM: सर, DK को लोकेशन की इजाज़त नहीं है - सेटिंग्स में Location चालू करके फिर बोलिए।"
        if (!LocalInfo.locationOn(c)) return "ERROR_PERM: सर, फ़ोन की Location बंद है - ऊपर से खींचकर Location चालू कीजिए, फिर बोलिए।"
        val r = run(c, what, mode) ?: return "STEP_FAILED: सर, अभी लोकेशन नहीं मिल पाई - खुली जगह में जाकर फिर बोलिए।"
        return sayLine(r)
    }
}
