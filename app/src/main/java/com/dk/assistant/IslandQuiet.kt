package com.dk.assistant

import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * 13 नवंबर 2026 (मालिक): "डबल मैसेज नहीं आना चाहिए. कॉल आ रहा है तो island में भी दिख रहा है और ऊपर से Android वाला भी -
 * वह नहीं. एक ही दिखे. किसी भी ऐप का मैसेज हो या कहीं से भी कॉल, island चालू है तो सिर्फ़ DK Island में."
 *
 * जड़: island अपने-आप में एक और खिड़की है - Android का अपना popup (heads-up) भी आता था = दो बार.
 * इलाज: island ने जो notification ले लिया, उसका Android वाला हटाओ (cancel) - island में रहे, वहीं से खुले / जवाब / हटे.
 * बजती कॉल का notification हटाया नहीं जा सकता (कॉल ही कट जाए) - उसे थोड़ी देर snooze (छिपाओ), island में उठाएँ / काटें.
 * नियम [IslandCore.quiet] में (test). फ़ोन lock / island बंद / DK सामने / वही ऐप सामने = Android वाला जैसा था वैसा.
 * हमने ख़ुद हटाया = [keep] -> reply का बटन और island की चीज़ बनी रहे.
 */
object IslandQuiet {
    private const val P_ON = "island_quiet"
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)
    fun on(c: Context) = sp(c).getBoolean(P_ON, true)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(P_ON, v).apply() }

    /** हमने Android से हटाए (island में हैं): key -> pkg */
    private val cancelled = ConcurrentHashMap<String, String>()
    /** हमने छिपाई बजती कॉल: key -> कब तक */
    private val snoozed = ConcurrentHashMap<String, Long>()
    /** एक बार छिपा चुके (वापस आई = फिर नहीं छिपाते, Android वाला ही - कॉल खोए नहीं) */
    private val snoozedOnce = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    /** group का summary हटाया = उसके साथ बच्चे भी हटते हैं - उस ऐप का कब */
    private val groupAt = ConcurrentHashMap<String, Long>()
    private val SYSTEM = setOf("android", "com.android.systemui")
    private const val MAX = 300

    /** फ़ोन खुला और इस्तेमाल में (lock / स्क्रीन बंद = Android वाला ही रहे) */
    private fun usable(c: Context): Boolean = try {
        c.getSystemService(PowerManager::class.java)?.isInteractive == true &&
            c.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true
    } catch (_: Exception) { false }

    /** [DKNotifListener] नया notification आने पर (island में लेने के बाद) */
    fun onPosted(l: DKNotifListener, sbn: StatusBarNotification, inIsland: Boolean) {
        try {
            snoozed.remove(sbn.key)     // छिपाने की देर पूरी होकर वापस आया
            val n = sbn.notification ?: return
            if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
            val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 || (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
            val media = n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
            val app = AppCall.has(sbn.key)
            val call = app || n.category == Notification.CATEGORY_CALL
            val titles = n.actions?.map { it.title?.toString().orEmpty() }.orEmpty()
            val ringing = if (app) AppCall.ringingKey(sbn.key) else call && (n.fullScreenIntent != null || AppCall.pickAnswer(titles) >= 0)
            val q = IslandCore.quiet(on(l), DKIsland.canShow(l), DKIsland.notifsOn(l), usable(l), DKIsland.front(), sbn.packageName,
                l.packageName, sbn.packageName in SYSTEM, ongoing, call, ringing, media, n.category, inIsland)
            when (q) {
                IslandCore.Quiet.CANCEL -> {
                    if (cancelled.size > MAX) cancelled.clear()
                    cancelled[sbn.key] = sbn.packageName
                    l.cancelNotification(sbn.key)
                    // उसी बातचीत का group summary भी (वरना ऊपर ख़ाली "WhatsApp: 2 संदेश" रहता)
                    l.activeNotifications?.filter { o -> o.packageName == sbn.packageName && o.key != sbn.key && o.groupKey == sbn.groupKey &&
                        ((o.notification?.flags ?: 0) and Notification.FLAG_GROUP_SUMMARY) != 0 }?.forEach { o ->
                        groupAt[sbn.packageName] = System.currentTimeMillis()
                        cancelled[o.key] = o.packageName
                        l.cancelNotification(o.key)
                    }
                }
                IslandCore.Quiet.SNOOZE -> if (snoozedOnce.add(sbn.key)) {
                    if (snoozedOnce.size > MAX) snoozedOnce.clear()
                    snoozed[sbn.key] = System.currentTimeMillis() + IslandCore.CALL_SNOOZE_MS
                    l.snoozeNotification(sbn.key, IslandCore.CALL_SNOOZE_MS)
                }
                IslandCore.Quiet.NONE -> {}
            }
        } catch (e: Exception) { DKLog.d("island एक ही: ${e.javaClass.simpleName}") }
    }

    /** true = यह हटना हमने किया (island में बना रहे, reply का बटन याद रहे) - [DKNotifListener.onNotificationRemoved] */
    fun keep(key: String, pkg: String, reason: Int): Boolean = when (reason) {
        NotificationListenerService.REASON_SNOOZED -> snoozed.containsKey(key)
        NotificationListenerService.REASON_LISTENER_CANCEL -> cancelled.containsKey(key) && IslandFeed.list().any { it.key == key }
        NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED -> (groupAt[pkg] ?: 0L) > System.currentTimeMillis() - 3_000L
        else -> false
    }

    /** असल में हटा (ऐप ने / user ने) */
    fun gone(key: String) { cancelled.remove(key); snoozed.remove(key); snoozedOnce.remove(key) }

    /** ऐप खुला: उसके जो notification Android से हमने हटाए थे वे island से भी (वरना पढ़ने के बाद भी अटके रहते) */
    fun appOpened(pkg: String) {
        val keys = cancelled.filterValues { it == pkg }.keys.toList()
        if (keys.isEmpty()) return
        keys.forEach { cancelled.remove(it); IslandFeed.removed(it) }
    }

    /**
     * island हर सेकंड: छिपाई कॉल का हाल - उठी (Answer बटन गया) = island में "चल रही"; कट गई (कहीं नहीं) = island से हटे.
     * छिपे notification के बदलाव Android हमें नहीं बताता, इसलिए ख़ुद देखते हैं.
     */
    fun poll() {
        if (snoozed.isEmpty()) return
        val l = DKNotifListener.instance ?: return
        try {
            val now = System.currentTimeMillis()
            val hid = l.snoozedNotifications?.associateBy { it.key }.orEmpty()
            val active = l.activeNotifications?.map { it.key }?.toSet().orEmpty()
            for ((k, until) in snoozed.entries.toList()) {
                val sbn = hid[k]
                when {
                    sbn != null -> refreshCall(l, sbn)
                    k in active || now > until + 5_000L -> snoozed.remove(k)
                    else -> { snoozed.remove(k); AppCall.removed(k); IslandFeed.removed(k) }
                }
            }
        } catch (e: Exception) { DKLog.d("island छिपी कॉल: ${e.javaClass.simpleName}") }
    }

    private fun refreshCall(l: DKNotifListener, sbn: StatusBarNotification) {
        if (!AppCall.has(sbn.key)) return
        val n = sbn.notification ?: return
        val ex = n.extras
        val app = AppCall.all().firstOrNull { it.key == sbn.key }?.app ?: sbn.packageName
        AppCall.posted(sbn.key, sbn.packageName, app, n, ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            ex?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())
    }
}
