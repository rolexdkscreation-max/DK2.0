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
            .setContentTitle(Prefs.assistantName(this) + " चालू है")
            .setContentText("गोल ओर्ब पर टैप करके बोलिए")
            .setSmallIcon(R.drawable.ic_dk)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        // Android 14: background se mic wali service shuru karna mana hai -> crash na ho (isi se DK band hota tha)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(1, notif)
            }
        } catch (e: Exception) {
            DKLog.d("KeepAlive start nahi hua: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        DKAccessibilityService.instance?.startWakeLoopIfEnabled()
        return START_STICKY
    }
}
