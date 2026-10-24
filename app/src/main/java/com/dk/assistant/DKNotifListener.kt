package com.dk.assistant

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * v3.41: फ़ोन के notification पढ़ना + उनका जवाब (reply) देना.
 * user एक बार Settings -> "Notification access" में DK को इजाज़त देता है.
 * हर नया notification [NotifStore] में (सिर्फ़ memory); reply वाला button (RemoteInput) अलग रखा जाता है
 * ताकि "इसको रिप्लाई कर दो …" पर उसी notification के अंदर से संदेश जाए (WhatsApp/Messages/Telegram…).
 */
class DKNotifListener : NotificationListenerService() {

    companion object {
        @Volatile var instance: DKNotifListener? = null
        /** notification key -> reply action (Android object, NotifStore में नहीं रख सकते) */
        private val replyActions = ConcurrentHashMap<String, Notification.Action>()

        fun enabled(c: Context): Boolean {
            val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.split(':').any { ComponentName.unflattenFromString(it)?.packageName == c.packageName }
        }

        fun openSettings(c: Context) {
            try {
                c.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                try { c.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
        }

        /** notification के अंदर से जवाब भेजो. null = भेज दिया, वरना कारण */
        fun reply(c: Context, key: String, message: String): String? {
            val a = replyActions[key] ?: return "इस notification में reply का option नहीं है"
            val inputs = a.remoteInputs?.filter { it.allowFreeFormInput } ?: return "इस notification में reply का option नहीं है"
            if (inputs.isEmpty()) return "इस notification में reply का option नहीं है"
            return try {
                val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                val b = Bundle(); inputs.forEach { b.putCharSequence(it.resultKey, message) }
                RemoteInput.addResultsToIntent(inputs.toTypedArray(), intent, b)
                a.actionIntent.send(c, 0, intent)
                NotifStore.markReplied(key)
                null
            } catch (e: PendingIntent.CanceledException) {
                "वह notification अब पुराना हो गया (हट गया) — ऐप खोलकर भेजना होगा"
            } catch (e: Exception) { "reply नहीं गया: ${e.message}" }
        }

        /**
         * v3.51: फ़ोन पर अभी पड़े सारे notification ताज़ा पढ़ो (memory खाली/पुरानी हो तो भी सही जवाब).
         * true = पढ़ने वाला जुड़ा है और पढ़ लिया; false = Android ने अलग कर रखा है (फिर से जोड़ने को कहा).
         */
        fun refresh(c: Context): Boolean {
            val l = instance
            if (l == null) {
                try { if (android.os.Build.VERSION.SDK_INT >= 24) NotificationListenerService.requestRebind(ComponentName(c, DKNotifListener::class.java)) } catch (_: Exception) {}
                return false
            }
            return try { l.activeNotifications?.forEach { l.take(it, push = false) }; true } catch (_: Exception) { false }
        }

        /** v3.51: पहले से देखे (key+text) - वही notification दोबारा (update) Live को फिर न बताओ */
        private val seen = ArrayDeque<NotifPush.Seen>()

        private fun findReply(n: Notification): Notification.Action? =
            n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
    }

    override fun onListenerConnected() {
        instance = this
        // पहले से पड़े notification भी ले लो (DK लाइव शुरू होने से पहले वाले)
        try { activeNotifications?.forEach { take(it, push = false) } } catch (_: Exception) {}
    }

    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    override fun onNotificationPosted(sbn: StatusBarNotification?) { sbn?.let { take(it, push = true) } }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // हटाया गया notification: reply action बेकार; पर याद रखो (user पूछ सकता है "क्या आया था")
        sbn?.key?.let { replyActions.remove(it) }
    }

    private fun take(sbn: StatusBarNotification, push: Boolean) {
        try {
            val n = sbn.notification ?: return
            val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 || (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
            val summary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0
            if (NotifStore.skip(sbn.packageName, packageName, ongoing, summary)) return
            val ex = n.extras ?: return
            val title = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString() ?: ""
            // chat ऐप: सबसे नया संदेश (MessagingStyle) - नहीं तो big text / text
            val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
            val last = msgs?.lastOrNull()?.let { (it as? Bundle)?.getCharSequence("text")?.toString() }
            val text = last ?: (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
            val app = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
            } catch (_: Exception) { sbn.packageName }
            val r = findReply(n)
            if (r != null) replyActions[sbn.key] = r else replyActions.remove(sbn.key)
            NotifStore.add(NotifStore.Item(sbn.key, sbn.packageName, app, title.trim(), text.trim(), sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis(), r != null))
            if (DKState.live) DKLog.d("🔔 notification: [$app] ${title.take(30)}" + if (r != null) " (reply हो सकता है)" else "")
            // v3.51: Live चालू है -> नया notification तुरंत DK को बताओ (पहले सिर्फ़ याद रखा जाता था, DK को पता नहीं चलता था)
            val at = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
            val isNew = synchronized(seen) {
                val fresh = NotifPush.isNew(sbn.key, title, text, at, System.currentTimeMillis(), seen)
                seen.addLast(NotifPush.Seen(sbn.key, text.trim())); while (seen.size > 60) seen.removeFirst()
                fresh
            }
            if (push && isNew && DKState.live) DKAccessibilityService.instance?.gemLive?.pushNotif(app, title.trim(), text.trim())
        } catch (_: Exception) {}
    }
}
