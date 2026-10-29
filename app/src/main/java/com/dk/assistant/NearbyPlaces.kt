package com.dk.assistant

import org.json.JSONObject
import java.util.Locale

/**
 * v4.3 (मालिक): "मैं अभी कहाँ खड़ा हूँ?" - ताज़ा लोकेशन का इलाका/सड़क/शहर + आसपास की जगहें (मंदिर, बाज़ार, अस्पताल,
 * पेट्रोल पंप, स्कूल...). मुफ़्त OpenStreetMap: पता = Nominatim, आसपास = Overpass (Google Places के लिए billing वाली key चाहिए).
 * यहाँ सिर्फ़ URL/parse/वाक्य (pure) - नेटवर्क LocalInfo में. Android import नहीं -> JVM test.
 */
object NearbyPlaces {
    data class Addr(val street: String, val area: String, val city: String, val state: String) {
        fun text(): String = listOf(street, area, city, state).filter { it.isNotBlank() }.distinct().joinToString(", ")
        fun isEmpty() = text().isBlank()
    }

    data class Spot(val name: String, val kind: String, val distM: Int)

    const val RADIUS_M = 600
    const val UA = "DK-Assistant (Android)"
    const val OVERPASS = "https://overpass-api.de/api/interpreter"

    /** OSM tag -> हिंदी नाम */
    val KINDS = linkedMapOf(
        "amenity=place_of_worship" to "मंदिर/धार्मिक स्थल",
        "amenity=marketplace" to "बाज़ार",
        "shop=mall" to "मॉल",
        "amenity=hospital" to "अस्पताल",
        "amenity=clinic" to "क्लिनिक",
        "amenity=fuel" to "पेट्रोल पंप",
        "amenity=school" to "स्कूल",
        "amenity=college" to "कॉलेज",
        "amenity=bank" to "बैंक",
        "amenity=pharmacy" to "मेडिकल स्टोर",
        "railway=station" to "रेलवे स्टेशन",
        "amenity=bus_station" to "बस स्टैंड",
        "leisure=park" to "पार्क",
        "amenity=police" to "पुलिस स्टेशन",
    )

    private val WEATHER = listOf("मौसम", "बारिश", "तापमान", "ठंड", "गर्मी", "धूप", "weather", "rain", "temperature", "mausam")
    private val WHERE = listOf("कहाँ", "कहां", "आसपास", "आस पास", "आस-पास", "पास में", "नज़दीक", "नजदीक", "इलाक", "लोकेशन", "जगह", "सड़क", "पता",
        "where", "nearby", "near me", "location", "kahan", "kaha")

    /** सिर्फ़ मौसम पूछा = नहीं (जल्दी जवाब); जगह/आसपास पूछा या पता न चले = हाँ */
    fun wantsWhere(userText: String): Boolean {
        val t = userText.lowercase()
        if (WHERE.any { t.contains(it) }) return true
        return !WEATHER.any { t.contains(it) }
    }

    fun nominatimUrl(lat: Double, lon: Double): String =
        String.format(Locale.US, "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.6f&lon=%.6f&zoom=18&addressdetails=1&accept-language=hi,en", lat, lon)

    /** Overpass query: [RADIUS_M] में नाम वाली जगहें (node/way का बीच का बिंदु) */
    fun overpassQuery(lat: Double, lon: Double, radius: Int = RADIUS_M): String = buildString {
        append("[out:json][timeout:8];(")
        val at = String.format(Locale.US, "around:%d,%.6f,%.6f", radius, lat, lon)
        for (k in KINDS.keys) {
            val (key, v) = k.split("=")
            append("nwr(").append(at).append(")[\"").append(key).append("\"=\"").append(v).append("\"][\"name\"];")
        }
        append(");out center 60;")
    }

    fun parseAddr(json: String): Addr {
        val a = try { JSONObject(json).optJSONObject("address") } catch (_: Exception) { null } ?: return Addr("", "", "", "")
        fun first(vararg k: String) = k.map { a.optString(it) }.firstOrNull { it.isNotBlank() } ?: ""
        return Addr(
            street = first("road", "pedestrian", "street"),
            area = first("neighbourhood", "suburb", "quarter", "hamlet", "city_district"),
            city = first("city", "town", "village", "municipality", "county", "state_district"),
            state = first("state"),
        )
    }

    /** दूरी मीटर में (haversine) */
    fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val h = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
        return (2 * r * Math.asin(Math.sqrt(h))).toInt()
    }

    /** Overpass का जवाब -> नज़दीक से दूर, हर तरह की ज़्यादा से ज़्यादा 2, कुल [max] */
    fun parseSpots(json: String, lat: Double, lon: Double, max: Int = 6): List<Spot> {
        val arr = try { JSONObject(json).optJSONArray("elements") } catch (_: Exception) { null } ?: return emptyList()
        val all = ArrayList<Spot>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val tags = e.optJSONObject("tags") ?: continue
            val name = tags.optString("name:hi").ifBlank { tags.optString("name") }.trim()
            if (name.isBlank()) continue
            val kind = KINDS.entries.firstOrNull { (k, _) -> val (key, v) = k.split("="); tags.optString(key) == v }?.value ?: continue
            val c = e.optJSONObject("center")
            val la = if (e.has("lat")) e.optDouble("lat") else c?.optDouble("lat") ?: continue
            val lo = if (e.has("lon")) e.optDouble("lon") else c?.optDouble("lon") ?: continue
            if (la.isNaN() || lo.isNaN()) continue
            all.add(Spot(name, kind, distM(lat, lon, la, lo)))
        }
        val seen = HashSet<String>(); val perKind = HashMap<String, Int>()
        return all.sortedBy { it.distM }.filter { s ->
            val ok = seen.add(s.name.lowercase()) && (perKind[s.kind] ?: 0) < 2
            if (ok) perKind[s.kind] = (perKind[s.kind] ?: 0) + 1
            ok
        }.take(max)
    }

    fun distText(m: Int): String = when {
        m < 100 -> "बिल्कुल पास"
        m < 1000 -> "${(m + 25) / 50 * 50} मीटर"
        else -> String.format(Locale.US, "%.1f किलोमीटर", m / 1000.0)
    }

    /** Live tool का जवाब (Gemini इसे छोटे इंसानी वाक्यों में बोले) */
    fun toolText(addr: Addr, spots: List<Spot>, weather: String?): String = buildString {
        append("FRESH current location (checked just now). ")
        append("Place: ").append(addr.text().ifBlank { "area name not found" }).append(". ")
        if (addr.street.isNotBlank()) append("Street/road: ${addr.street}. ")
        if (spots.isEmpty()) append("Nearby places: none found in the map data. ")
        else append("Nearby: ").append(spots.joinToString("; ") { "${it.name} (${it.kind}, ${distText(it.distM)})" }).append(". ")
        if (!weather.isNullOrBlank()) append("Weather now: $weather. ")
        append("Answer in 1-2 short, warm Hindi sentences like \"सर, आप अभी ${addr.area.ifBlank { addr.street.ifBlank { addr.city } }.ifBlank { "..." }} के पास हैं। आपके आसपास ... है।\" ")
        append("Mention at most 2-3 nearby places. Only answer what he asked (weather only if he asked about it).")
    }
}
