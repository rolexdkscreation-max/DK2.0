package com.dk.assistant.plus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
import com.dk.assistant.DKLog

/**
 * 15 नवंबर 2026 (मालिक, Advanced → Auto Event Trigger, default बंद):
 * चार्जर लगा / हटा, Wi-Fi जुड़ा / टूटा, बैटरी सेवर बदला - हर एक का अपना स्विच, DK बोलकर बताए.
 * Android 8+ पर ये broadcast manifest से नहीं मिलते - इसलिए ऐप चलते समय runtime receiver ([sync]).
 * मुख्य स्विच बंद = कोई receiver नहीं (बैटरी पर कोई बोझ नहीं).
 */
object EventTriggers {
    private var receiver: BroadcastReceiver? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    @Volatile private var wifiUp: Boolean? = null

    /** Pure: कौन-सी घटना, कौन-सा स्विच, क्या बोलना */
    enum class Ev(val flag: String, val line: String) {
        CHARGER_ON(FeatureFlags.EV_CHARGER_ON, "चार्जर लग गया"),
        CHARGER_OFF(FeatureFlags.EV_CHARGER_OFF, "चार्जर हटा दिया गया"),
        WIFI_ON(FeatureFlags.EV_WIFI_ON, "Wi-Fi जुड़ गया"),
        WIFI_OFF(FeatureFlags.EV_WIFI_OFF, "Wi-Fi टूट गया"),
        SAVER_ON(FeatureFlags.EV_BATTERY_SAVER, "बैटरी सेवर चालू हो गया"),
        SAVER_OFF(FeatureFlags.EV_BATTERY_SAVER, "बैटरी सेवर बंद हो गया"),
    }

    fun line(ev: Ev, percent: Int?): String = when (ev) {
        Ev.CHARGER_ON, Ev.CHARGER_OFF -> if (percent != null && percent in 0..100) "${ev.line}, बैटरी $percent प्रतिशत है" else ev.line
        else -> ev.line
    }

    /** ऐप शुरू होने पर और हर बार स्विच बदलने पर: चालू हो तो सुनो, बंद हो तो सब हटाओ */
    fun sync(c: Context) {
        val app = c.applicationContext
        val want = FeatureFlags.on(app, FeatureFlags.EVENTS_ON)
        if (!want) { stop(app); return }
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_POWER_CONNECTED -> fire(ctx, Ev.CHARGER_ON)
                    Intent.ACTION_POWER_DISCONNECTED -> fire(ctx, Ev.CHARGER_OFF)
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                        val on = ctx.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
                        fire(ctx, if (on) Ev.SAVER_ON else Ev.SAVER_OFF)
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(r, f)
            receiver = r
        } catch (e: Exception) { DKLog.d("EventTriggers: ${e.message}") }
        try {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) { if (wifiUp == false) fire(app, Ev.WIFI_ON); wifiUp = true }
                override fun onLost(n: Network) { if (wifiUp == true) fire(app, Ev.WIFI_OFF); wifiUp = false }
            }
            wifiUp = null
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
            netCb = cb
            // शुरू की हालत (पहला onAvailable घोषणा न करे)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (wifiUp == null) wifiUp = false }, 3000)
        } catch (e: Exception) { DKLog.d("EventTriggers wifi: ${e.message}") }
    }

    fun stop(c: Context) {
        receiver?.let { try { c.applicationContext.unregisterReceiver(it) } catch (_: Exception) {} }
        receiver = null
        netCb?.let { try { c.applicationContext.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {} }
        netCb = null
    }

    private fun fire(c: Context, ev: Ev) {
        if (!FeatureFlags.on(c, FeatureFlags.EVENTS_ON) || !FeatureFlags.on(c, ev.flag)) return
        val pct = try { c.getSystemService(android.os.BatteryManager::class.java)?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (_: Exception) { null }
        PlusVoice.say(c, line(ev, pct))
    }
}
