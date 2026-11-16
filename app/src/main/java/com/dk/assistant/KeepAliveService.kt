package com.dk.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Notification wali foreground service - taaki Android DK ko background me band na kare aur mic chale. */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel("dk", "DK Assistant", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, "dk") else @Suppress("DEPRECATION") Notification.Builder(this)
        val notif = builder
            .setContentTitle(Prefs.assistantName(this) + " " + UiLang.s("चालू है"))
            .setContentText(UiLang.s(if (com.dk.assistant.wake.WakeWord.on(this)) com.dk.assistant.wake.WakeRules.NOTIF_TEXT else "गोल ओर्ब पर टैप करके बोलिए"))
            .setSmallIcon(R.drawable.ic_dk)
            .setContentIntent(open)
            .setOngoing(true)
            // नवंबर 2026: ऊपर "Active apps" का Stop पूरा DK (फ़ोन कंट्रोल भी) मार देता है - यह बटन सिर्फ़ माइक बंद करता है
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, UiLang.s("माइक बंद करें"),
                PendingIntent.getBroadcast(this, 1, Intent(this, MicOffReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)).build())
            .build()

        // Android 14: background se mic wali service shuru karna mana hai -> crash na ho (isi se DK band hota tha)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // नवंबर 2026 (मालिक: स्क्रीन बंद / लॉक पर भी "मैं कहाँ हूँ"): लोकेशन की इजाज़त हो तो service में location भी,
                // वरना Android पीछे से ताज़ा लोकेशन नहीं देता. न चले (Android 14 की शर्तें) = पहले जैसा सिर्फ़ माइक
                val loc = LocalInfo.hasPermission(this)
                try {
                    startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        (if (loc) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0))
                } catch (e: Exception) {
                    if (!loc) throw e
                    DKLog.d("KeepAlive: location type नहीं चला, सिर्फ़ माइक: ${e.message}")
                    startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                }
            } else {
                startForeground(1, notif)
            }
        } catch (e: Exception) {
            DKLog.d("KeepAlive start nahi hua: ${e.message}")
            // 16 नवंबर 2026: Android 14+ ने पीछे से माइक वाली service रोकी - Settings में साफ़ हिंदी में बताया जाता है
            Prefs.edit(this) { putBoolean(com.dk.assistant.wake.WakeWord.P_FGS_BLOCK, true) }
            stopSelf()
            return START_NOT_STICKY
        }
        if (Prefs.bool(this, com.dk.assistant.wake.WakeWord.P_FGS_BLOCK, false)) Prefs.edit(this) { putBoolean(com.dk.assistant.wake.WakeWord.P_FGS_BLOCK, false) }
        Setup.selfEnableAccessibility(this)
        DKAccessibilityService.instance?.startWakeLoopIfEnabled()
        return START_STICKY
    }
}

/**
 * नवंबर 2026: DK की notification का "माइक बंद करें" - Live/सुनना बंद, notification हटे; फ़ोन कंट्रोल (Accessibility) चलता रहे.
 * (Android के "Active apps" वाले Stop से पूरा ऐप मरता है और Accessibility "not available" हो जाती है.)
 * माइक फिर: DK खोलकर ओर्ब दबाएँ / बोलें.
 */
class MicOffReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(c: android.content.Context, i: Intent?) {
        DKLog.d("सूचना से: माइक बंद (ऐप और फ़ोन कंट्रोल चालू)")
        Prefs.edit(c) { putBoolean("live_mode", false); putBoolean("mic_user_off", true) }
        try { DKAccessibilityService.instance?.stopLive("सूचना से माइक बंद") } catch (e: Exception) { Quiet.note(e) }
        try { c.stopService(Intent(c, KeepAliveService::class.java)) } catch (e: Exception) { Quiet.note(e) }
    }
}
