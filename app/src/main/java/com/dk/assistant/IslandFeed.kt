package com.dk.assistant

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * 13 नवंबर 2026 (मालिक): "DK Island में फ़ोन पर आने वाला सारा notification दिखेगा. स्लाइड करके हटाया जा सके,
 * आख़िर में 'सब साफ़ करें'. जिसका भी notification है उस पर टैप करें तो उसी जगह खुले - कॉल पर टैप = चलती कॉल."
 *
 * [DKNotifListener] हर नया / हटा notification यहाँ देता है (सिर्फ़ memory). सूची के नियम [IslandCore] में.
 * हटाना = फ़ोन से भी हटता है (cancelNotification). चलते (ongoing) notification Android हटाने नहीं देता - वे सिर्फ़ island से छिपते हैं.
 */
object IslandFeed {
    private val items = LinkedHashMap<String, IslandCore.Notif>()
    private val intents = ConcurrentHashMap<String, PendingIntent>()
    private val autoCancel = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    /** island से हटाए चलते notification (फ़ोन से हट नहीं सकते) - जब तक वह notification ख़ुद न हटे */
    private val hidden = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** सिस्टम के अपने (USB / चार्जिंग / स्क्रीनशॉट…) नहीं - चार्जिंग का अपना कार्ड है */
    private val SYSTEM = setOf("android", "com.android.systemui")

    /** नया / बदला notification. false = island में नहीं लिया */
    fun posted(l: DKNotifListener, sbn: StatusBarNotification): Boolean {
        try {
            val n = sbn.notification ?: return false
            if (sbn.packageName == l.packageName || sbn.packageName in SYSTEM) return false
            if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return false
            // गाना / वीडियो = island का गाने वाला कार्ड पहले से है
            if (n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true) return false
            val ex = n.extras
            val title = (ex?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: ex?.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty().trim()
            val msgs = ex?.getParcelableArray(Notification.EXTRA_MESSAGES)
            val last = msgs?.lastOrNull()?.let { (it as? android.os.Bundle)?.getCharSequence("text")?.toString() }
            val text = (last ?: ex?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ex?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()).orEmpty().trim()
            if (title.isBlank() && text.isBlank()) return false
            val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 || (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
            val call = n.category == Notification.CATEGORY_CALL || AppCall.current?.key == sbn.key
            val app = try { l.packageManager.getApplicationLabel(l.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Exception) { sbn.packageName }
            val at = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
            n.contentIntent?.let { intents[sbn.key] = it } ?: intents.remove(sbn.key)
            if ((n.flags and Notification.FLAG_AUTO_CANCEL) != 0) autoCancel.add(sbn.key) else autoCancel.remove(sbn.key)
            synchronized(this) {
                items.remove(sbn.key)
                items[sbn.key] = IslandCore.Notif(sbn.key, sbn.packageName, app, title, text.take(300), at, ongoing, call)
                while (items.size > IslandCore.MAX_NOTIFS * 2) items.remove(items.keys.first())
            }
            DKIsland.poke()
            return true
        } catch (e: Exception) { DKLog.d("island notification: ${e.javaClass.simpleName}"); return false }
    }

    /** फ़ोन से हटा (किसी ने भी हटाया) */
    fun removed(key: String) {
        val had = synchronized(this) { items.remove(key) != null }
        intents.remove(key); autoCancel.remove(key); hidden.remove(key)
        if (had) DKIsland.poke()
    }

    /** island की सूची (island से छिपाए हुए नहीं) */
    fun list(): List<IslandCore.Notif> = synchronized(this) { IslandCore.notifOrder(items.values.filter { it.key !in hidden }) }

    /** कॉल का notification (SIM / WhatsApp… कॉल) - टैप = चलती कॉल की स्क्रीन */
    fun callIntent(appCallKey: String?): PendingIntent? {
        appCallKey?.let { k -> intents[k]?.let { return it } }
        val k = synchronized(this) { items.values.lastOrNull { it.call }?.key } ?: return null
        return intents[k]
    }

    /** स्लाइड करके हटाया: फ़ोन से भी (चलता हो तो सिर्फ़ island से) */
    fun dismiss(key: String) {
        val n = synchronized(this) { items[key] }
        if (n == null || n.ongoing) hidden.add(key)
        else {
            synchronized(this) { items.remove(key) }
            try { DKNotifListener.instance?.cancelNotification(key) } catch (e: Exception) { DKLog.d("island: notification नहीं हटा: ${e.message}") }
        }
        DKIsland.poke()
    }

    /** "सब साफ़ करें": फ़ोन के सारे हटने लायक notification हटें; चलते वाले island से छिपें. DK Privacy के 🔒 भी साफ़ */
    fun clearAll() {
        try { DKNotifListener.instance?.cancelAllNotifications() } catch (e: Exception) { DKLog.d("island: सब साफ़ नहीं हुआ: ${e.message}") }
        synchronized(this) {
            val keep = IslandCore.afterClearAll(items.values.toList())
            keep.forEach { hidden.add(it.key) }
            val keepKeys = keep.map { it.key }.toSet()
            items.keys.retainAll(keepKeys)
        }
        PrivacyLocked.clear()
        DKIsland.poke()
    }

    /**
     * टैप: notification का अपना रास्ता (वही चैट / वही वीडियो / वही कॉल). न चले तो वह ऐप.
     * true = कुछ खुला.
     */
    fun open(c: Context, key: String): Boolean {
        val n = synchronized(this) { items[key] }
        val pi = intents[key]
        if (pi != null && send(c, pi)) {
            // ऐप ने "टैप पर हटो" कहा था - हमने खोला, तो हम ही हटाएँ (जैसे notification shade करता है)
            if (n != null && !n.ongoing && key in autoCancel) try { DKNotifListener.instance?.cancelNotification(key) } catch (_: Exception) {}
            return true
        }
        val pkg = n?.pkg ?: return false
        return try {
            c.packageManager.getLaunchIntentForPackage(pkg)?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } ?: false
        } catch (e: Exception) { DKLog.d("island: ऐप नहीं खुला: ${e.message}"); false }
    }

    /** Android 14+: पीछे से activity खोलने की इजाज़त साथ में (DK का Accessibility चालू है) */
    fun send(c: Context, pi: PendingIntent): Boolean = try {
        if (Build.VERSION.SDK_INT >= 34) {
            val o = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            pi.send(c, 0, null, null, null, null, o.toBundle())
        } else pi.send()
        true
    } catch (_: Exception) { false }
}
