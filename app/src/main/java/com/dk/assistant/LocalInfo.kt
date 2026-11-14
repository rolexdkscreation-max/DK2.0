package com.dk.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * v3.45: अभी की ताज़ा लोकेशन + शहर का नाम + मौसम. हर बार नई लोकेशन (पुरानी cache/सेव की कभी नहीं) और
 * लोकेशन कहीं सेव नहीं होती - सिर्फ़ इस बातचीत के लिए.
 */
object LocalInfo {
    data class Place(val lat: Double, val lon: Double, val name: String)

    private val http = com.dk.assistant.NetFix.builder().callTimeout(8, TimeUnit.SECONDS).build()

    fun hasPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** अभी की ताज़ा लोकेशन (getLastKnownLocation नहीं) - [timeoutMs] में न मिले तो null */
    @SuppressLint("MissingPermission")
    suspend fun fresh(c: Context, timeoutMs: Long = 9_000): Location? {
        if (!hasPermission(c)) return null
        val lm = c.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { try { lm.isProviderEnabled(it) } catch (_: Exception) { false } }
        if (providers.isEmpty()) return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                val listeners = mutableListOf<LocationListener>()
                fun done(l: Location?) {
                    if (!cont.isActive) return
                    listeners.forEach { try { lm.removeUpdates(it) } catch (_: Exception) {} }
                    cont.resume(l)
                }
                try {
                    if (Build.VERSION.SDK_INT >= 30) {
                        val sig = android.os.CancellationSignal()
                        cont.invokeOnCancellation { sig.cancel() }
                        for (p in providers.filter { it != LocationManager.PASSIVE_PROVIDER }.ifEmpty { providers }) {
                            lm.getCurrentLocation(p, sig, c.mainExecutor) { l -> if (l != null) done(l) }
                        }
                    } else {
                        for (p in providers) {
                            val ll = LocationListener { l -> done(l) }
                            listeners.add(ll)
                            @Suppress("DEPRECATION") lm.requestSingleUpdate(p, ll, Looper.getMainLooper())
                        }
                        cont.invokeOnCancellation { listeners.forEach { try { lm.removeUpdates(it) } catch (_: Exception) {} } }
                    }
                } catch (e: Exception) { DKLog.d("लोकेशन नहीं मिली: ${e.message}"); done(null) }
            }
        }
    }

    /** lat/lon -> "पंचवटी, नाशिक, महाराष्ट्र" (फ़ोन का Geocoder, नहीं तो मुफ़्त BigDataCloud) */
    suspend fun placeName(c: Context, lat: Double, lon: Double): String = withContext(Dispatchers.IO) {
        try {
            @Suppress("DEPRECATION")
            val a = Geocoder(c, Locale("hi", "IN")).getFromLocation(lat, lon, 1)?.firstOrNull()
            val n = listOfNotNull(a?.subLocality, a?.locality ?: a?.subAdminArea, a?.adminArea).distinct().joinToString(", ")
            if (n.isNotBlank()) return@withContext n
        } catch (_: Exception) {}
        try {
            val url = "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon&localityLanguage=hi"
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val o = JSONObject(r.body?.string() ?: "{}")
                listOf(o.optString("locality"), o.optString("city"), o.optString("principalSubdivision"))
                    .filter { it.isNotBlank() }.distinct().joinToString(", ")
            }
        } catch (_: Exception) { "" }
    }

    suspend fun weather(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(WeatherText.url(lat, lon)).build()).execute().use { r ->
                if (!r.isSuccessful) null else WeatherText.describe(r.body?.string() ?: "")
            }
        } catch (_: Exception) { null }
    }

    /** "नाशिक, महाराष्ट्र — अभी 27°C, हल्के बादल; आज 21–31°C" (null = लोकेशन नहीं मिली) */
    suspend fun now(c: Context): Pair<Place, String>? {
        val l = fresh(c) ?: return null
        val name = placeName(c, l.latitude, l.longitude)
        val w = weather(l.latitude, l.longitude)
        val place = Place(l.latitude, l.longitude, name)
        val text = listOfNotNull(name.ifBlank { null }, w).joinToString(" — ")
        DKLog.d("ताज़ा लोकेशन: ${name.ifBlank { "नाम नहीं मिला" }}" + (w?.let { " | $it" } ?: ""))
        return place to text.ifBlank { return null }
    }

    /** v4.3: फ़ोन की Location (GPS) सेटिंग चालू है? */
    fun locationOn(c: Context): Boolean {
        val lm = c.getSystemService(LocationManager::class.java) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
            else listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).any { lm.isProviderEnabled(it) }
        } catch (_: Exception) { false }
    }

    /** v4.3: पूरा पता (सड़क/इलाका/शहर) - फ़ोन का Geocoder, नहीं तो मुफ़्त OpenStreetMap (Nominatim) */
    suspend fun address(c: Context, lat: Double, lon: Double): NearbyPlaces.Addr = withContext(Dispatchers.IO) {
        try {
            @Suppress("DEPRECATION")
            val a = Geocoder(c, Locale("hi", "IN")).getFromLocation(lat, lon, 1)?.firstOrNull()
            if (a != null) {
                val ad = NearbyPlaces.Addr(a.thoroughfare ?: "", a.subLocality ?: "", a.locality ?: a.subAdminArea ?: "", a.adminArea ?: "",
                    house = a.subThoroughfare ?: "", spot = a.featureName?.takeIf { it != a.subThoroughfare && it != a.thoroughfare } ?: "",
                    full = try { a.getAddressLine(0) ?: "" } catch (_: Exception) { "" })
                if (ad.area.isNotBlank() || ad.street.isNotBlank()) return@withContext ad
            }
        } catch (_: Exception) {}
        try {
            http.newCall(Request.Builder().url(NearbyPlaces.nominatimUrl(lat, lon)).header("User-Agent", NearbyPlaces.UA).build()).execute().use { r ->
                if (r.isSuccessful) NearbyPlaces.parseAddr(r.body?.string() ?: "") else NearbyPlaces.Addr("", "", "", "")
            }
        } catch (_: Exception) { NearbyPlaces.Addr("", "", "", "") }
    }

    /** v4.3: आसपास की जगहें (OpenStreetMap Overpass, ~600 m) */
    suspend fun nearby(lat: Double, lon: Double): List<NearbyPlaces.Spot> = withContext(Dispatchers.IO) {
        try {
            val body = okhttp3.FormBody.Builder().add("data", NearbyPlaces.overpassQuery(lat, lon)).build()
            http.newCall(Request.Builder().url(NearbyPlaces.OVERPASS).header("User-Agent", NearbyPlaces.UA).post(body).build()).execute().use { r ->
                if (r.isSuccessful) NearbyPlaces.parseSpots(r.body?.string() ?: "", lat, lon) else emptyList()
            }
        } catch (e: Exception) { DKLog.d("आसपास की जगहें नहीं मिलीं: ${e.message}"); emptyList() }
    }

    /**
     * v4.3 (मालिक): "मैं अभी कहाँ खड़ा हूँ?" - हमेशा ताज़ा लोकेशन (पुरानी सेव की कभी नहीं) -> पता + आसपास + मौसम (साथ-साथ).
     * null = लोकेशन नहीं मिली (वजह [hasPermission]/[locationOn] से).
     */
    suspend fun where(c: Context): String? = kotlinx.coroutines.coroutineScope {
        // नवंबर 2026 (मालिक: "एग्ज़ैक्ट जहाँ मैं हूँ"): पहली fix अक्सर टावर/WiFi वाली (100-2000 m) - कुछ सेकंड और GPS सुनो
        val l = fresh(c, 10_000)?.let { sharpen(c, it) } ?: return@coroutineScope null
        val addr = async { address(c, l.latitude, l.longitude) }
        val spots = async { nearby(l.latitude, l.longitude) }
        val shops = async { shops(l.latitude, l.longitude) }
        val w = async { weather(l.latitude, l.longitude) }
        val ad = addr.await(); val sp = spots.await()
        val acc = if (l.hasAccuracy()) l.accuracy.toInt() else -1
        DKLog.d("ताज़ा जगह: ${ad.text().ifBlank { "नाम नहीं मिला" }} (±$acc m) | आसपास ${sp.size}")
        NearbyPlaces.toolText(ad, sp, w.await(), acc, shops.await(), precise(c))
    }

    /** Android 12+: "Approximate" वाली इजाज़त = सिर्फ़ ~2 km तक सही */
    fun precise(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * नवंबर 2026: [first] काफ़ी सही नहीं (> [NearbyPlaces.GOOD_ACC_M]) = GPS/fused से [waitMs] तक और fix लो, सबसे सही लौटाओ
     * (हर बार ताज़ा - पुरानी सेव लोकेशन नहीं).
     */
    @SuppressLint("MissingPermission")
    suspend fun sharpen(c: Context, first: Location, waitMs: Long = 7_000): Location {
        if (first.hasAccuracy() && first.accuracy <= NearbyPlaces.GOOD_ACC_M) return first
        if (!precise(c)) return first
        val lm = c.getSystemService(LocationManager::class.java) ?: return first
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        }.filter { p -> try { lm.allProviders.contains(p) && lm.isProviderEnabled(p) } catch (_: Exception) { false } }
        if (providers.isEmpty()) return first
        var best = first
        val listeners = mutableListOf<LocationListener>()
        try {
            withTimeoutOrNull(waitMs) {
                suspendCancellableCoroutine<Unit> { cont ->
                    for (p in providers) {
                        val ll = LocationListener { l ->
                            if (NearbyPlaces.better(if (best.hasAccuracy()) best.accuracy else null, if (l.hasAccuracy()) l.accuracy else null)) best = l
                            if (best.hasAccuracy() && best.accuracy <= NearbyPlaces.GOOD_ACC_M && cont.isActive) cont.resume(Unit)
                        }
                        listeners.add(ll)
                        try { lm.requestLocationUpdates(p, 500L, 0f, ll, Looper.getMainLooper()) } catch (e: Exception) { runCatching { DKLog.d("लोकेशन $p: ${e.message}") } }
                    }
                }
            }
        } finally { listeners.forEach { try { lm.removeUpdates(it) } catch (_: Exception) {} } }
        return best
    }

    /** "बाजू की दुकानें" (~250 m, कोई भी नाम वाली दुकान) */
    suspend fun shops(lat: Double, lon: Double): List<NearbyPlaces.Spot> = overpass(NearbyPlaces.shopsQuery(lat, lon)) {
        NearbyPlaces.parseFound(it, lat, lon, listOf("shop=*"), 4)
    }

    private suspend fun overpass(q: String, parse: (String) -> List<NearbyPlaces.Spot>): List<NearbyPlaces.Spot> = withContext(Dispatchers.IO) {
        try {
            val body = okhttp3.FormBody.Builder().add("data", q).build()
            val slow = http.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
            slow.newCall(Request.Builder().url(NearbyPlaces.OVERPASS).header("User-Agent", NearbyPlaces.UA).post(body).build()).execute().use { r ->
                if (r.isSuccessful) parse(r.body?.string() ?: "") else emptyList()
            }
        } catch (e: Exception) { runCatching { DKLog.d("Overpass नहीं चला: ${e.message}") }; emptyList() }
    }

    /**
     * 14 नवंबर 2026 (मालिक: "सबसे पास वाली दुकान ढूँढो और रास्ता लगा दो") - [NearbyGo] के लिए:
     * ताज़ा लोकेशन (तेज़ - sharpen नहीं, रास्ते के लिए काफ़ी) + [what] वाली जगहें नज़दीक से दूर. null = लोकेशन नहीं मिली.
     */
    suspend fun nearest(c: Context, what: String): Pair<Location, List<NearbyPlaces.Spot>>? {
        val l = fresh(c, 8_000) ?: return null
        val tags = NearbyPlaces.findTags(what)
        val found = if (tags.isEmpty()) emptyList() else overpass(NearbyPlaces.findQuery(l.latitude, l.longitude, tags)) {
            NearbyPlaces.parseFound(it, l.latitude, l.longitude, tags)
        }
        return l to found
    }

    /**
     * नवंबर 2026 (मालिक): "मेरे पास पेट्रोल पंप / ATM / मेडिकल / किराना दुकान कहाँ है" - ताज़ा लोकेशन से 3 km में, सबसे पास वाले पहले.
     * null = लोकेशन नहीं मिली.
     */
    suspend fun find(c: Context, what: String): String? = kotlinx.coroutines.coroutineScope {
        val l = fresh(c, 10_000)?.let { sharpen(c, it, 4_000) } ?: return@coroutineScope null
        val tags = NearbyPlaces.findTags(what)
        val addr = async { address(c, l.latitude, l.longitude) }
        val found = if (tags.isEmpty()) emptyList() else overpass(NearbyPlaces.findQuery(l.latitude, l.longitude, tags)) {
            NearbyPlaces.parseFound(it, l.latitude, l.longitude, tags)
        }
        DKLog.d("पास में \"${what.take(30)}\": ${found.size} मिले")
        NearbyPlaces.findText(what.trim(), addr.await(), found, if (l.hasAccuracy()) l.accuracy.toInt() else -1, tags.isNotEmpty())
    }
}
