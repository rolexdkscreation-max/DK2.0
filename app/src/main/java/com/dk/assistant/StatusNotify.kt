package com.dk.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * v3.31: पीछे चल रहे काम का असली कदम notification में (TaskStatus से). काम खत्म = यह notification हटे
 * (आख़िरी नतीजे वाली notification पहले की तरह DK कंप्यूटर / कोडिंग टीम खुद देते हैं).
 */
object StatusNotify {
    private const val CH = "dk_status"
    private const val ID_PC = 7402
    private const val ID_CODER = 7403
    private val lastAt = HashMap<String, Long>()

    fun install(c: Context) {
        val app = c.applicationContext
        TaskStatus.listen { job, ended -> show(app, job, ended) }
    }

    private fun show(c: Context, j: TaskStatus.Job, ended: Boolean) = try {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = if (j.key == TaskStatus.PC) ID_PC else ID_CODER
        if (ended) { nm.cancel(id); synchronized(lastAt) { lastAt.remove(j.key) } }
        else {
            val now = System.currentTimeMillis()
            val skip = synchronized(lastAt) {
                val t = lastAt[j.key] ?: 0L
                // बहुत तेज़ कदम: 1.5 सेकंड में एक बार (इजाज़त वाला हमेशा)
                if (j.state == TaskStatus.State.RUNNING && j.steps > 0 && now - t < 1500) true else { lastAt[j.key] = now; false }
            }
            if (!skip) {
                if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
                    nm.createNotificationChannel(NotificationChannel(CH, "DK काम की हालत", NotificationManager.IMPORTANCE_LOW))
                val target = if (j.key == TaskStatus.PC) ComputerActivity::class.java else CodingActivity::class.java
                val pi = PendingIntent.getActivity(c, id, Intent(c, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val text = TaskStatus.line(j)
                val b = (if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, CH) else android.app.Notification.Builder(c))
                    .setSmallIcon(android.R.drawable.stat_notify_sync_noanim).setContentTitle("${j.who}: ${j.task.take(60)}")
                    .setContentText(text.lineSequence().first()).setStyle(android.app.Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true)
                nm.notify(id, b.build())
            }
        }
        Unit
    } catch (_: Exception) {}
}
