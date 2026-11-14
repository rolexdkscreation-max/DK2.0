package com.dk.assistant

import org.json.JSONObject
import java.util.Locale

/**
 * v4.3 (मालिक): "मैं अभी कहाँ खड़ा हूँ?" - ताज़ा लोकेशन का इलाका/सड़क/शहर + आसपास की जगहें (मंदिर, बाज़ार, अस्पताल,
 * पेट्रोल पंप, स्कूल...). मुफ़्त OpenStreetMap: पता = Nominatim, आसपास = Overpass (Google Places के लिए billing वाली key चाहिए).
 * यहाँ सिर्फ़ URL/parse/वाक्य (pure) - नेटवर्क LocalInfo में. Android import नहीं -> JVM test.
 */
object NearbyPlaces {
    /** नवंबर 2026: + [house] (मकान/प्लॉट नंबर), [spot] (जिस इमारत/दुकान पर खड़े हैं), [full] (पूरा लिखा पता) - सब ज़रूरी नहीं */
    data class Addr(val street: String, val area: String, val city: String, val state: String,
                    val house: String = "", val spot: String = "", val full: String = "") {
        fun text(): String = listOf(street, area, city, state).filter { it.isNotBlank() }.distinct().joinToString(", ")
        fun isEmpty() = text().isBlank()
    }

    data class Spot(val name: String, val kind: String, val distM: Int, val lat: Double = 0.0, val lon: Double = 0.0)

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
            house = first("house_number"),
            spot = try { JSONObject(json).optString("name") } catch (_: Exception) { "" }.trim(),
            full = try { JSONObject(json).optString("display_name") } catch (_: Exception) { "" }.trim(),
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
    fun toolText(addr: Addr, spots: List<Spot>, weather: String?, accM: Int = -1, shops: List<Spot> = emptyList(), precise: Boolean = true): String = buildString {
        append("FRESH current location (checked just now). ")
        append("Place: ").append(addr.text().ifBlank { "area name not found" }).append(". ")
        if (addr.street.isNotBlank()) append("Street/road: ${addr.street}. ")
        exactText(addr, accM, precise).takeIf { it.isNotBlank() }?.let { append(it) }
        if (shops.isNotEmpty()) append("Shops right next to him: ").append(shops.joinToString("; ") { "${it.name} (${it.kind}, ${distText(it.distM)})" }).append(". ")
        if (spots.isEmpty()) append("Nearby places: none found in the map data. ")
        else append("Nearby: ").append(spots.joinToString("; ") { "${it.name} (${it.kind}, ${distText(it.distM)})" }).append(". ")
        if (!weather.isNullOrBlank()) append("Weather now: $weather. ")
        append("Answer in 1-2 short, warm Hindi sentences like \"सर, आप अभी ${addr.area.ifBlank { addr.street.ifBlank { addr.city } }.ifBlank { "..." }} के पास हैं। आपके आसपास ... है।\" ")
        append("Mention at most 2-3 nearby places. Only answer what he asked (weather only if he asked about it).")
    }

    // ---------- नवंबर 2026 (मालिक): "एग्ज़ैक्ट मैं कहाँ हूँ", "बाजू में कौन सी दुकान है", "पास में पेट्रोल पंप / ATM / मेडिकल कहाँ है" ----------

    /** इतने मीटर या कम = पक्की (GPS) लोकेशन, और इंतज़ार नहीं */
    const val GOOD_ACC_M = 25f
    /** "पास में X" ढूँढने का दायरा */
    const val FIND_RADIUS_M = 3000
    /** "बाजू की दुकानें" का दायरा */
    const val SHOP_RADIUS_M = 250

    /** नई fix पुरानी से बेहतर? (कम accuracy-मीटर = बेहतर; accuracy न हो = सबसे बुरी) */
    fun better(oldAccM: Float?, newAccM: Float?): Boolean {
        val n = newAccM?.takeIf { it > 0f } ?: Float.MAX_VALUE
        val o = oldAccM?.takeIf { it > 0f } ?: Float.MAX_VALUE
        return oldAccM == null || n < o
    }

    /** मकान नंबर / इमारत / पूरा पता + कितनी सही लोकेशन है */
    fun exactText(addr: Addr, accM: Int, precise: Boolean = true): String = buildString {
        val at = listOf(addr.house, addr.spot).filter { it.isNotBlank() }.distinct().joinToString(", ")
        if (at.isNotBlank()) append("Exact spot: $at. ")
        if (addr.full.isNotBlank()) append("Full address: ${addr.full.take(220)}. ")
        if (accM > 0) {
            append("Location accuracy: about $accM m. ")
            if (accM > 100) append("This fix is only approximate - say 'लगभग' and that GPS is weak here. ")
        }
        if (!precise) append("DK has only APPROXIMATE location permission - tell him to allow 'Precise location' for DK in Settings for the exact spot. ")
    }

    /** दुकान की OSM shop=... वैल्यू -> हिंदी */
    val SHOPS = linkedMapOf(
        "supermarket" to "सुपरमार्केट", "convenience" to "किराना / जनरल स्टोर", "general" to "किराना / जनरल स्टोर",
        "grocery" to "किराना", "greengrocer" to "सब्ज़ी की दुकान", "clothes" to "कपड़ों की दुकान", "tailor" to "दर्ज़ी",
        "mobile_phone" to "मोबाइल शॉप", "electronics" to "इलेक्ट्रॉनिक्स की दुकान", "computer" to "कंप्यूटर शॉप",
        "bakery" to "बेकरी", "confectionery" to "मिठाई की दुकान", "sweets" to "मिठाई की दुकान", "pastry" to "मिठाई / केक की दुकान",
        "chemist" to "मेडिकल स्टोर", "hardware" to "हार्डवेयर की दुकान", "jewelry" to "ज्वेलरी की दुकान", "shoes" to "जूतों की दुकान",
        "stationery" to "स्टेशनरी", "books" to "किताबों की दुकान", "butcher" to "मीट की दुकान", "dairy" to "डेयरी",
        "hairdresser" to "सैलून", "beauty" to "ब्यूटी पार्लर", "car_repair" to "गैरेज", "motorcycle_repair" to "बाइक गैरेज",
        "tyres" to "टायर / पंक्चर की दुकान", "motorcycle" to "बाइक शोरूम", "car" to "कार शोरूम", "optician" to "चश्मे की दुकान",
        "furniture" to "फ़र्नीचर की दुकान", "gift" to "गिफ़्ट शॉप", "variety_store" to "वैरायटी स्टोर", "kiosk" to "छोटी दुकान / गुमटी",
        "mall" to "मॉल", "department_store" to "डिपार्टमेंट स्टोर", "boutique" to "बुटीक (कपड़े)", "fashion" to "फैशन / कपड़े", "paint" to "पेंट की दुकान", "alcohol" to "शराब की दुकान",
    )

    /** KINDS के अलावा "पास में X" में ढूँढने लायक जगहें */
    val EXTRA = linkedMapOf(
        "amenity=atm" to "ATM", "amenity=restaurant" to "रेस्टोरेंट / होटल", "amenity=fast_food" to "फ़ास्ट फ़ूड",
        "amenity=cafe" to "चाय / कैफ़े", "tourism=hotel" to "होटल (रुकने के लिए)", "tourism=guest_house" to "लॉज / गेस्ट हाउस",
        "amenity=toilets" to "शौचालय", "amenity=charging_station" to "EV चार्जिंग", "amenity=doctors" to "डॉक्टर",
        "amenity=dentist" to "दाँतों का डॉक्टर", "highway=bus_stop" to "बस स्टॉप", "amenity=post_office" to "पोस्ट ऑफ़िस",
        "amenity=cinema" to "सिनेमा", "amenity=parking" to "पार्किंग", "amenity=car_wash" to "कार वॉश", "amenity=veterinary" to "जानवरों का डॉक्टर",
    )

    /** OSM tag ("amenity=fuel" / "shop=clothes" / "shop=*") -> हिंदी नाम */
    fun label(tag: String): String = KINDS[tag] ?: EXTRA[tag]
        ?: tag.removePrefix("shop=").let { v -> if (tag.startsWith("shop=")) SHOPS[v] ?: if (v == "*") "दुकान" else "दुकान ($v)" else tag }

    /** बोले गए शब्द -> OSM tags (पहला मिला मेल; कुछ न मिले = खाली) */
    private val FIND = listOf(
        listOf("पेट्रोल", "petrol", "डीज़ल", "डीजल", "diesel", "fuel", "पंप", "pump", "cng", "सीएनजी") to listOf("amenity=fuel"),
        listOf("atm", "एटीएम", "पैसे निकाल") to listOf("amenity=atm"),
        listOf("बैंक", "bank") to listOf("amenity=bank"),
        listOf("अस्पताल", "hospital", "हॉस्पिटल", "दवाखाना") to listOf("amenity=hospital", "amenity=clinic"),
        listOf("मेडिकल", "medical", "दवा", "दवाई", "pharmacy", "फार्मेसी", "chemist", "केमिस्ट") to listOf("amenity=pharmacy", "shop=chemist"),
        listOf("क्लिनिक", "clinic", "डॉक्टर", "doctor") to listOf("amenity=clinic", "amenity=doctors", "amenity=hospital"),
        listOf("पंक्चर", "puncture", "टायर", "tyre", "tire") to listOf("shop=tyres", "shop=motorcycle_repair", "shop=car_repair"),
        listOf("गैरेज", "garage", "mechanic", "मैकेनिक", "रिपेयर", "repair") to listOf("shop=car_repair", "shop=motorcycle_repair", "shop=tyres"),
        listOf("चार्जिंग", "charging", "ev ") to listOf("amenity=charging_station"),
        listOf("रुकने", "लॉज", "lodge", "stay", "guest house", "गेस्ट हाउस") to listOf("tourism=hotel", "tourism=guest_house"),
        listOf("होटल", "hotel", "रेस्टोरेंट", "restaurant", "ढाबा", "dhaba", "खाना", "food", "भोजन") to listOf("amenity=restaurant", "amenity=fast_food", "tourism=hotel"),
        listOf("चाय", "tea", "कैफ़े", "कैफे", "cafe", "coffee", "कॉफ़ी") to listOf("amenity=cafe"),
        listOf("किराना", "kirana", "grocery", "ग्रोसरी", "जनरल स्टोर", "general store", "सुपरमार्केट", "supermarket", "राशन") to
            listOf("shop=supermarket", "shop=convenience", "shop=general", "shop=grocery"),
        listOf("सब्ज़ी", "सब्जी", "vegetable") to listOf("shop=greengrocer", "amenity=marketplace"),
        listOf("दर्ज़ी", "दर्जी", "tailor") to listOf("shop=tailor"),
        // 14 नवंबर 2026 (मालिक: "कपड़े लेना है, पास की दुकान/मॉल ढूँढो") - कपड़े की दुकान + बुटीक + मॉल / बड़ा स्टोर
        listOf("कपड़", "कपड़", "clothes", "cloth", "garment", "kapde", "kapda", "ड्रेस", "dress", "शर्ट", "shirt", "साड़ी", "saree", "बुटीक", "boutique", "fashion", "फैशन") to
            listOf("shop=clothes", "shop=boutique", "shop=fashion", "shop=department_store", "shop=mall"),
        listOf("मोबाइल", "mobile", "फ़ोन की दुकान", "phone shop", "रिचार्ज", "recharge") to listOf("shop=mobile_phone", "shop=electronics"),
        listOf("इलेक्ट्रॉनिक", "electronic", "कंप्यूटर", "computer", "लैपटॉप", "laptop") to listOf("shop=electronics", "shop=computer"),
        listOf("मिठाई", "sweet", "केक", "cake") to listOf("shop=confectionery", "shop=sweets", "shop=pastry", "shop=bakery"),
        listOf("बेकरी", "bakery", "ब्रेड") to listOf("shop=bakery"),
        listOf("ज्वेल", "jewel", "सोना", "सुनार", "gold") to listOf("shop=jewelry"),
        listOf("जूत", "चप्पल", "shoe", "footwear") to listOf("shop=shoes"),
        listOf("हार्डवेयर", "hardware") to listOf("shop=hardware"),
        listOf("स्टेशनरी", "stationery", "किताब", "book") to listOf("shop=stationery", "shop=books"),
        listOf("सैलून", "salon", "नाई", "बाल कट", "haircut", "पार्लर", "parlour", "parlor") to listOf("shop=hairdresser", "shop=beauty"),
        listOf("चश्म", "optician", "spectacle") to listOf("shop=optician"),
        listOf("मंदिर", "temple", "मस्जिद", "mosque", "चर्च", "church", "गुरुद्वारा", "gurudwara") to listOf("amenity=place_of_worship"),
        listOf("पुलिस", "police", "थाना", "चौकी") to listOf("amenity=police"),
        listOf("बस स्टॉप", "bus stop") to listOf("highway=bus_stop", "amenity=bus_station"),
        listOf("बस", "bus") to listOf("amenity=bus_station", "highway=bus_stop"),
        listOf("रेलवे", "railway", "ट्रेन", "train", "स्टेशन", "station") to listOf("railway=station"),
        listOf("पार्क", "park", "गार्डन", "garden", "बगीचा") to listOf("leisure=park"),
        listOf("स्कूल", "school") to listOf("amenity=school"),
        listOf("कॉलेज", "college") to listOf("amenity=college"),
        listOf("मॉल", "mall") to listOf("shop=mall", "shop=department_store"),
        listOf("बाज़ार", "बाजार", "market", "मार्केट", "मंडी") to listOf("amenity=marketplace", "shop=mall"),
        listOf("शौचालय", "toilet", "washroom", "बाथरूम") to listOf("amenity=toilets"),
        listOf("पोस्ट", "post office", "डाकघर") to listOf("amenity=post_office"),
        listOf("सिनेमा", "cinema", "theatre", "थिएटर", "movie") to listOf("amenity=cinema"),
        listOf("पार्किंग", "parking") to listOf("amenity=parking"),
        // "कुछ खरीदना है / शॉपिंग" (कोई खास चीज़ नहीं) = मॉल, बड़े स्टोर, सुपरमार्केट, कपड़े - खास चीज़ों के बाद, "दुकान" से पहले
        listOf("शॉपिंग", "shopping", "खरीद", "kharid") to
            listOf("shop=mall", "shop=department_store", "shop=supermarket", "shop=clothes"),
        listOf("दुकान", "dukan", "shop", "store", "स्टोर") to listOf("shop=*"),
    )

    fun findTags(find: String): List<String> {
        val t = " " + find.lowercase().trim() + " "
        if (t.isBlank()) return emptyList()
        return FIND.firstOrNull { (words, _) -> words.any { t.contains(it) } }?.second ?: emptyList()
    }

    /** Overpass: [tags] वाली नाम-वाली जगहें [radius] में ("shop=*" = कोई भी दुकान) */
    fun findQuery(lat: Double, lon: Double, tags: List<String>, radius: Int = FIND_RADIUS_M): String = buildString {
        append("[out:json][timeout:10];(")
        val at = String.format(Locale.US, "around:%d,%.6f,%.6f", radius, lat, lon)
        for (k in tags.distinct()) {
            val (key, v) = k.split("=")
            append("nwr(").append(at).append(")[\"").append(key).append("\"")
            if (v != "*") append("=\"").append(v).append("\"")
            append("][\"name\"];")
        }
        append(");out center 80;")
    }

    /** "बाजू की दुकानें": [SHOP_RADIUS_M] में कोई भी नाम वाली दुकान */
    fun shopsQuery(lat: Double, lon: Double): String = findQuery(lat, lon, listOf("shop=*"), SHOP_RADIUS_M)

    /** Overpass का जवाब -> [tags] में से जो मिला, नज़दीक से दूर, कुल [max] (हर तरह की सीमा नहीं) */
    fun parseFound(json: String, lat: Double, lon: Double, tags: List<String>, max: Int = 4): List<Spot> {
        val arr = try { JSONObject(json).optJSONArray("elements") } catch (_: Exception) { null } ?: return emptyList()
        val all = ArrayList<Spot>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val t = e.optJSONObject("tags") ?: continue
            val name = t.optString("name:hi").ifBlank { t.optString("name") }.trim()
            if (name.isBlank()) continue
            val tag = tags.firstOrNull { k -> val (key, v) = k.split("="); if (v == "*") t.optString(key).isNotBlank() else t.optString(key) == v } ?: continue
            val kind = if (tag == "shop=*") label("shop=" + t.optString("shop")) else label(tag)
            val c = e.optJSONObject("center")
            val la = if (e.has("lat")) e.optDouble("lat") else c?.optDouble("lat") ?: continue
            val lo = if (e.has("lon")) e.optDouble("lon") else c?.optDouble("lon") ?: continue
            if (la.isNaN() || lo.isNaN()) continue
            all.add(Spot(name, kind, distM(lat, lon, la, lo), la, lo))
        }
        val seen = HashSet<String>()
        return all.sortedBy { it.distM }.filter { seen.add(it.name.lowercase()) }.take(max)
    }

    /** "पास में X" का Live जवाब */
    fun findText(find: String, addr: Addr, found: List<Spot>, accM: Int = -1, known: Boolean = true): String = buildString {
        append("FRESH current location (checked just now). He is at: ").append(addr.text().ifBlank { "area name not found" }).append(". ")
        if (accM > 0) append("Location accuracy: about $accM m. ")
        if (!known) {
            append("DK has no map category for \"$find\". Use Google Search for \"$find near ${listOf(addr.area, addr.city).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "his area" }}\" and answer from that, or offer to open Google Maps (phone_task 'Maps में पास का $find दिखाओ').")
            return@buildString
        }
        if (found.isEmpty()) {
            append("No \"$find\" found within ${FIND_RADIUS_M / 1000} km in the free map data (OpenStreetMap may simply not list it). ")
            append("Tell him honestly in one short Hindi line and offer to open Google Maps for it (phone_task 'Maps में पास का $find दिखाओ').")
            return@buildString
        }
        append("Nearest \"$find\" (closest first): ").append(found.joinToString("; ") { "${it.name} (${it.kind}, ${distText(it.distM)})" }).append(". ")
        append("Answer in 1-2 short, warm Hindi sentences like \"सर, सबसे पास ${found[0].name} है, करीब ${distText(found[0].distM)} पर।\" ")
        append("Mention at most 2-3. If he wants to go there, use phone_task for Maps directions to that name.")
    }
}
