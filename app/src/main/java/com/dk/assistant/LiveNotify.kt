package com.dk.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build

/**
 * मालिक (12 नवंबर 2026): "ORB वाली Live बातचीत चालू हो और मैं minimize करूँ (DK से बाहर), तो ऊपर notification नीचे खींचने पर
 * कॉल काटने का बटन और म्यूट का बटन दिखे" - जैसे फ़ोन कॉल में दिखता है.
 * नियम [LiveNotifyRules] में (JVM test). बटन [LiveNotifyReceiver] में: "कॉल काटें" = Live बंद, "म्यूट" = DK का माइक चुप (Live जुड़ा रहे).
 */
object LiveNotifyRules {
    /** notification दिखे: Live चालू + DK ऐप सामने नहीं (minimize / दूसरा ऐप) */
    fun wanted(live: Boolean, inApp: Boolean): Boolean = live && !inApp
    fun muteLabel(muted: Boolean) = if (muted) "माइक चालू करें" else "म्यूट"
    const val END_LABEL = "कॉल काटें"
    fun text(muted: Boolean, mode: Mode): String = if (muted) "म्यूट है - DK अभी आपकी बात नहीं सुन रहा" else IslandCore.modeHi(mode)
}

object LiveNotify {
    private const val CH = "dk_live_call"
    const val ID = 7421
    const val ACTION_END = "com.dk.assistant.LIVE_END"
    const val ACTION_MUTE = "com.dk.assistant.LIVE_MUTE"

    @Volatile private var app: Context? = null
    /** आख़िरी दिखाई हालत (वही दोबारा notify नहीं - बार-बार की आवाज़/झिलमिल नहीं) */
    @Volatile private var shown: String? = null
    private val onState: () -> Unit = { app?.let { sync(it) } }

    fun install(c: Context) { app = c.applicationContext; DKState.listen(onState); sync(c) }
    fun release(c: Context) { DKState.unlisten(onState); cancel(c); app = null }

    fun sync(c: Context) {
        if (!LiveNotifyRules.wanted(DKState.live, AppVisible.visible)) { cancel(c); return }
        val muted = DKState.micMuted
        val mode = DKState.shownMode()
        val sig = "$muted|${if (muted) "" else mode.name}"
        if (sig == shown) return
        shown = sig
        show(c, muted, mode)
    }

    private fun show(c: Context, muted: Boolean, mode: Mode) = try {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "DK लाइव बातचीत", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        fun bc(action: String, rc: Int) = PendingIntent.getBroadcast(c, rc, Intent(c, LiveNotifyReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(c, ID, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val name = Prefs.assistantName(c)
        @Suppress("DEPRECATION")
        val b = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CH) else Notification.Builder(c))
            .setSmallIcon(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
            .setContentTitle(UiLang.s("$name से लाइव बात चल रही है"))
            .setContentText(UiLang.s(LiveNotifyRules.text(muted, mode)))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setCategory(Notification.CATEGORY_CALL)
            .setColor(0xFFE5393B.toInt())
            .addAction(Notification.Action.Builder(Icon.createWithResource(c, if (muted) R.drawable.ic_mic else R.drawable.ic_mic_off),
                UiLang.s(LiveNotifyRules.muteLabel(muted)), bc(ACTION_MUTE, ID + 1)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stop),
                UiLang.s(LiveNotifyRules.END_LABEL), bc(ACTION_END, ID + 2)).build())
        nm.notify(ID, b.build())
    } catch (e: Exception) { DKLog.d("लाइव notification नहीं लगी: ${e.message}") }

    fun cancel(c: Context) {
        if (shown == null) return
        shown = null
        try { (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID) } catch (_: Exception) {}
    }
}

/** notification के बटन: "कॉल काटें" = Live बंद (ORB / सुनना बंद), "म्यूट" = माइक चुप / फिर चालू */
class LiveNotifyReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        when (i?.action) {
            LiveNotify.ACTION_END -> {
                DKLog.d("सूचना से: लाइव बात काटी")
                if (DKState.micMuted) DKState.setMicMuted(false)
                val svc = DKAccessibilityService.instance
                if (svc != null) try { svc.stopLive("सूचना से कॉल काटा") } catch (_: Exception) {}
                else DKState.setLive(false)
                LiveNotify.cancel(c)
            }
            LiveNotify.ACTION_MUTE -> {
                val m = !DKState.micMuted
                DKState.setMicMuted(m)
                DKLog.d("सूचना से: माइक ${if (m) "म्यूट" else "चालू"}")
                LiveNotify.sync(c)
            }
        }
        DKIsland.poke()
    }
}
