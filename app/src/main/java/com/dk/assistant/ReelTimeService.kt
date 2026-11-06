package com.dk.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder

/**
 * नवंबर 2026 (मालिक: "यह फ़ीचर Accessibility से नहीं जुड़ा हो - Accessibility बंद हो फिर भी चलता रहे").
 *
 * Accessibility बंद हो तब DK स्क्रीन नहीं पढ़ सकता (Android की रोक) - तब यह सेवा Android के Usage access से
 * देखती है कि इंस्टाग्राम / YouTube / फेसबुक कितनी देर सामने रहा, उससे रील का अंदाज़ा (~), 7 दिन का हिसाब और DK की आवाज़
 * (लिमिट पार / कल से ज़्यादा). Android लगातार चलने वाली सेवा के साथ एक चुप (बिना आवाज़) सूचना ज़रूरी करता है -
 * उसी में आज की गिनती. ऊपर वाली छोटी पट्टी सिर्फ़ Accessibility चालू होने पर (उसके बिना दूसरे ऐप के ऊपर कुछ नहीं दिखा सकते).
 */
class ReelTimeService : Service() {
    private var thread: HandlerThread? = null
    private var h: Handler? = null
    private var lastUsage = 0L
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ReelDaily.bgOn(this) || !PayGuard.usageOk(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            val n = note("रील का हिसाब चालू")
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
        } catch (e: Exception) { DKLog.d("रील हिसाब सेवा शुरू नहीं हुई: ${e.javaClass.simpleName}"); stopSelf(); return START_NOT_STICKY }
        if (thread == null) {
            thread = HandlerThread("dk-reel-time").apply { start() }
            h = Handler(thread!!.looper).also { it.post(tick) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        h?.removeCallbacksAndMessages(null); thread?.quitSafely(); thread = null; h = null
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            val next = try { step() } catch (e: Exception) { DKLog.d("रील हिसाब: ${e.javaClass.simpleName}"); 30_000L }
            h?.postDelayed(this, next)
        }
    }

    private fun step(): Long {
        if (!ReelDaily.bgOn(this) || !PayGuard.usageOk(this)) { stopSelf(); return 60_000L }
        val accOn = DKAccessibilityService.instance != null
        val app = ReelRules.appOf(ReelDaily.foregroundNow(this))
        val t = System.currentTimeMillis()
        // रील ऐप सामने: हर 20 सेकंड समय ताज़ा; नहीं तो हर 5 मिनट
        if (t - lastUsage > (if (app != null) 20_000L else 300_000L)) { lastUsage = t; ReelDaily.refreshUsage(this, onlyToday = true) }
        val n = ReelDaily.now(this)
        val text = (if (n.estimated) "~" else "") + "आज ${n.total} रील" + (if (n.limit > 0) " / ${n.limit}" else "") + " • ${n.minutes} मिनट" +
            (if (n.yesterday > 0) " • कल ${n.yesterday}" else "") + (if (accOn) "" else " • (Accessibility बंद: समय से अंदाज़ा)")
        if (text != lastText) { lastText = text; try { getSystemService(NotificationManager::class.java)?.notify(ID, note(text)) } catch (_: Exception) {} }
        // Accessibility चालू = ReelWatch गिनकर खुद बोलता है; बंद = यहाँ से
        if (app != null && !accOn) ReelDaily.coach(this)
        return if (app != null) 10_000L else 30_000L
    }

    private fun note(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm?.createNotificationChannel(NotificationChannel(CH, "DK रील हिसाब", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false); setSound(null, null) })
        val open = PendingIntent.getActivity(this, 7301, Intent(this, SettingsActivity::class.java).putExtra("section", "reels").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("DK रील हिसाब").setContentText(text).setSmallIcon(R.drawable.ic_dk).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
    }

    companion object {
        private const val ID = 7301
        private const val CH = "dk_reels"

        /** ऐप / फ़ोन शुरू, सेटिंग बदली: चालू करो (Usage access न हो = कुछ नहीं) */
        fun start(c: Context) {
            if (!ReelDaily.bgOn(c) || !PayGuard.usageOk(c)) return
            try { c.startForegroundService(Intent(c, ReelTimeService::class.java)) } catch (e: Exception) { DKLog.d("रील हिसाब सेवा: ${e.javaClass.simpleName}") }
        }

        fun stop(c: Context) = try { c.stopService(Intent(c, ReelTimeService::class.java)); Unit } catch (_: Exception) {}
    }
}
