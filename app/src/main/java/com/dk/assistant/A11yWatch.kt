package com.dk.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * नवंबर 2026 (मालिक): "ऊपर 'Active apps' से DK बंद (Stop) किया तो Accessibility में स्विच ON रहता है पर लिखता है
 * 'Service is not available' - फिर से चालू करना पड़ता है".
 *
 * जड़: Android 13+ का Stop पूरा ऐप memory से हटा देता है (सिर्फ़ notification नहीं). Accessibility सेवा उसी ऐप में है -
 * वह भी मरती है, और Android उसे "खराब" मानकर अपने-आप फिर नहीं जोड़ता. पर Android के नियम से तय jobs Stop के बाद भी चलते हैं.
 *
 * यह चौकीदार (हर ~15 मिनट + ऐप/फ़ोन शुरू होने पर) देखता है: Settings में ON पर सेवा चल नहीं रही =
 *  - WRITE_SECURE_SETTINGS मिली हो (PC से या बिना PC - Shizuku/aShell से एक बार) -> खुद OFF-ON करके तुरंत ठीक;
 *  - न मिली हो -> साफ़ notification: "फ़ोन कंट्रोल रुक गया - टैप करें" (एक टैप में सही जगह).
 * सेवा फिर चलते ही notification हटती है.
 */
object A11yWatch {
    enum class State { RUNNING, OFF, HEAL, ASK_USER }

    /** pure (JVM test) */
    fun state(running: Boolean, enabledInSettings: Boolean, canWriteSecure: Boolean): State = when {
        running -> State.RUNNING
        !enabledInSettings -> State.OFF          // मालिक ने खुद बंद किया - छेड़ो नहीं
        canWriteSecure -> State.HEAL
        else -> State.ASK_USER
    }

    /** एक ही बात की notification बार-बार नहीं: इतने समय में एक बार (सेवा चलने पर गिनती साफ़) */
    const val NOTIFY_EVERY_MS = 3 * 60 * 60_000L
    /** सेवा अभी-अभी जुड़ रही हो सकती है - इतना रुककर दोबारा देखो */
    const val SETTLE_MS = 4_000L

    fun shouldNotify(now: Long, lastNotified: Long): Boolean = lastNotified <= 0L || now - lastNotified >= NOTIFY_EVERY_MS

    /** pure: notification का लिखा (यूज़र ने Stop दबाया था तो वही बताओ) */
    fun message(userStopped: Boolean): String =
        (if (userStopped) "आपने ऊपर 'Active apps' से DK बंद किया था, इसलिए Android ने फ़ोन कंट्रोल रोक दिया। "
         else "Android ने DK का फ़ोन कंट्रोल (Accessibility) रोक दिया है। ") +
            "ठीक करने के लिए टैप करें → DK → एक बार OFF → फिर ON।"

    /** ApplicationExitInfo: 10 = REASON_USER_REQUESTED (force stop / Active apps का Stop), 11 = REASON_USER_STOPPED */
    fun userStopReason(reason: Int): Boolean = reason == 10 || reason == 11

    private const val CH = "dk_a11y"
    private const val ID = 7410
    private const val P_NOTIFIED = "a11y_notified_at"
    private const val P_EXIT_SEEN = "a11y_exit_seen"

    /** Android का रिकॉर्ड: पिछली बार DK यूज़र के Stop (Active apps / force stop) से बंद हुआ? (Android 11+) */
    fun lastExitWasUserStop(c: Context): Boolean = try {
        if (Build.VERSION.SDK_INT < 30) false else {
            val am = c.getSystemService(android.app.ActivityManager::class.java)
            val e = am.getHistoricalProcessExitReasons(c.packageName, 0, 1).firstOrNull()
            e != null && userStopReason(e.reason)
        }
    } catch (_: Throwable) { false }

    /** एक बार जाँचो और जो ठीक हो करो. लौटाता है हालत (log/test) */
    suspend fun check(c: Context, why: String): State {
        val app = c.applicationContext
        var s = state(DKAccessibilityService.instance != null, Setup.enabledInSettings(app), Setup.canWriteSecure(app))
        if (s == State.HEAL || s == State.ASK_USER) {
            delay(SETTLE_MS)
            s = state(DKAccessibilityService.instance != null, Setup.enabledInSettings(app), Setup.canWriteSecure(app))
        }
        val p = app.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)
        when (s) {
            State.RUNNING, State.OFF -> { cancel(app); p.edit().putLong(P_NOTIFIED, 0L).apply() }
            State.HEAL -> {
                val ok = Setup.healAccessibility(app)       // सिर्फ़ Settings में पहले से ON हो तब (नई इजाज़त कभी नहीं)
                DKLog.d("फ़ोन कंट्रोल रुका था ($why) - खुद फिर से जोड़ा: $ok")
                if (!ok) notifyUser(app, p)
            }
            State.ASK_USER -> notifyUser(app, p)
        }
        return s
    }

    private fun notifyUser(c: Context, p: android.content.SharedPreferences) {
        val now = System.currentTimeMillis()
        if (!shouldNotify(now, p.getLong(P_NOTIFIED, 0L))) return
        val stopped = lastExitWasUserStop(c)
        p.edit().putLong(P_NOTIFIED, now).putBoolean(P_EXIT_SEEN, stopped).apply()
        DKLog.d("फ़ोन कंट्रोल रुका है (Settings में ON, सेवा नहीं चल रही${if (stopped) ", यूज़र ने Stop दबाया था" else ""}) - notification")
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(NotificationChannel(CH, "DK फ़ोन कंट्रोल", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(c, ID, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, CH)
                else @Suppress("DEPRECATION") android.app.Notification.Builder(c)
            val text = UiLang.s(message(stopped))
            nm.notify(ID, b.setSmallIcon(R.drawable.ic_dk)
                .setContentTitle(UiLang.s("DK का फ़ोन कंट्रोल रुक गया"))
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi).setAutoCancel(true).build())
        } catch (e: Exception) { DKLog.d("a11y notification: ${e.message}") }
    }

    fun cancel(c: Context) = try { c.getSystemService(NotificationManager::class.java)?.cancel(ID); Unit } catch (_: Exception) {}
}

/** हर ~15 मिनट (Android का सबसे छोटा) - Active apps से Stop के बाद भी चलता है (Android का नियम), restart के बाद भी */
class A11yWatchJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartJob(p: JobParameters?): Boolean {
        scope.launch {
            try { A11yWatch.check(applicationContext, "job") } catch (e: Exception) { DKLog.d("a11y job: ${e.message}") }
            jobFinished(p, false)
        }
        return true
    }

    override fun onStopJob(p: JobParameters?): Boolean = true

    companion object {
        const val JOB_ID = 4203
        const val EVERY_MS = 15L * 60 * 1000

        fun schedule(c: Context) = try {
            val js = c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (js.allPendingJobs.none { it.id == JOB_ID && it.intervalMillis == EVERY_MS }) {
                js.schedule(JobInfo.Builder(JOB_ID, ComponentName(c, A11yWatchJob::class.java))
                    .setPeriodic(EVERY_MS)
                    .setPersisted(true)
                    .build())
            }
            Unit
        } catch (e: Exception) { DKLog.d("a11y job schedule: ${e.message}") }
    }
}
