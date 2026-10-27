package com.dk.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build

/**
 * v3.89: कोडिंग एजेंट की अपनी अलग आवाज़ें (फ़ोन की आम notification tone नहीं):
 *  DONE = काम पूरा (ऊपर चढ़ती खुश "टिंग-टिंग-टिंग")
 *  BUG  = build/test fail, बग मिला (नीची दोहरी "टन-टन")
 *  WAIT = रुक गया / आपका जवाब-मंज़ूरी चाहिए (लंबी नरम "टूऊँ")
 * हर आवाज़ का अपना channel - Android में channel बनने के बाद उसकी आवाज़ नहीं बदलती, इसलिए नए नाम.
 * फ़ोन silent / DND पर हो तो Android आवाज़ नहीं बजाता (यह फ़ोन का नियम है).
 */
object CoderSound {
    const val DONE = "done"
    const val BUG = "bug"
    const val WAIT = "wait"
    private const val NOTIFY_ID = 7301      // वही id जो पहले "dk_coder" का था - एक ही notification बदलता रहता है

    /** finish() के नतीजे से कौन-सी आवाज़ */
    fun kindFor(ok: Boolean, title: String?): String = when {
        ok -> DONE
        title != null && (title.contains("विफलता") || title.contains("fail", true)) -> BUG
        else -> WAIT
    }

    private fun raw(kind: String) = when (kind) { DONE -> R.raw.coder_done; BUG -> R.raw.coder_bug; else -> R.raw.coder_wait }
    private fun channel(kind: String) = when (kind) { DONE -> "dk_coder_done"; BUG -> "dk_coder_bug"; else -> "dk_coder_wait" }
    private fun label(kind: String) = when (kind) { DONE -> "DK कोडिंग: काम पूरा"; BUG -> "DK कोडिंग: बग / build fail"; else -> "DK कोडिंग: रुका / जवाब चाहिए" }

    private fun uri(c: Context, kind: String): Uri =
        Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + c.packageName + "/" + raw(kind))

    fun notify(c: Context, kind: String, title: String, text: String) = try {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = channel(kind)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(ch) == null) {
            val nc = NotificationChannel(ch, label(kind), NotificationManager.IMPORTANCE_DEFAULT)
            nc.setSound(uri(c, kind), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            nm.createNotificationChannel(nc)
        }
        val pi = PendingIntent.getActivity(c, NOTIFY_ID, Intent(c, CodingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = (if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, ch) else @Suppress("DEPRECATION") android.app.Notification.Builder(c)
            .setSound(uri(c, kind)))
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim).setContentTitle(title).setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true)
        nm.notify(NOTIFY_ID, b.build())
    } catch (_: Exception) {}
}
