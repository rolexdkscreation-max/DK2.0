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

    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()

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
                val ad = NearbyPlaces.Addr(a.thoroughfare ?: "", a.subLocality ?: "", a.locality ?: a.subAdminArea ?: "", a.adminArea ?: "")
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
        val l = fresh(c, 10_000) ?: return@coroutineScope null
        val addr = async { address(c, l.latitude, l.longitude) }
        val spots = async { nearby(l.latitude, l.longitude) }
        val w = async { weather(l.latitude, l.longitude) }
        val ad = addr.await(); val sp = spots.await()
        DKLog.d("ताज़ा जगह: ${ad.text().ifBlank { "नाम नहीं मिला" }} | आसपास ${sp.size}")
        NearbyPlaces.toolText(ad, sp, w.await())
    }
}
