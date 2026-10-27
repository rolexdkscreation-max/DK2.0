package com.dk.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** v3.88: फ़ोन से नेटवर्क की रफ़्तार का अंदाज़ा (Android) -> [NetSpeed] */
object NetProbe {
    fun refresh(c: Context): NetSpeed.Level {
        try {
            val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return NetSpeed.level
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetSpeed.level
            NetSpeed.onBandwidth(caps.linkDownstreamBandwidthKbps)
        } catch (_: Exception) {}
        return NetSpeed.level
    }

    fun online(c: Context): Boolean = try {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) { true }
}
