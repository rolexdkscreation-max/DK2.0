package com.dk.assistant.plus

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 15 नवंबर 2026 (मालिक: SOS / "लोकेशन भेजो"): ताज़ा लोकेशन. पहले सिर्फ़ getLastKnownLocation (अक्सर खाली / घंटों पुरानी).
 * अब: GPS / नेटवर्क से नई जगह [timeoutMs] तक; न मिले तो सबसे नई याद वाली (पुरानी हो तो भी, खाली से बेहतर).
 */
object LocNow {
    /** 2 मिनट से नई याद वाली जगह = ताज़ा मान लो (GPS चालू करने का इंतज़ार नहीं) */
    const val FRESH_MS = 120_000L

    fun permitted(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun last(c: Context): Location? {
        if (!permitted(c)) return null
        val lm = c.getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    suspend fun get(c: Context, timeoutMs: Long = 10_000L): Location? {
        if (!permitted(c)) return null
        val old = last(c)
        if (old != null && System.currentTimeMillis() - old.time < FRESH_MS) return old
        val lm = c.getSystemService(LocationManager::class.java) ?: return old
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { p ->
            try { lm.isProviderEnabled(p) } catch (_: Exception) { false }
        }
        if (providers.isEmpty()) return old
        val fresh = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val listeners = ArrayList<LocationListener>()
                    fun done(l: Location?) {
                        listeners.forEach { try { lm.removeUpdates(it) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) } }
                        listeners.clear()
                        if (cont.isActive) cont.resume(l)
                    }
                    for (p in providers) {
                        try {
                            if (Build.VERSION.SDK_INT >= 30) {
                                lm.getCurrentLocation(p, null, c.mainExecutor) { l -> if (l != null) done(l) }
                            } else {
                                val ls = LocationListener { l -> done(l) }
                                listeners.add(ls)
                                @Suppress("DEPRECATION") lm.requestSingleUpdate(p, ls, Looper.getMainLooper())
                            }
                        } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
                    }
                    cont.invokeOnCancellation { listeners.forEach { try { lm.removeUpdates(it) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) } } }
                }
            }
        }
        return fresh ?: old
    }
}
