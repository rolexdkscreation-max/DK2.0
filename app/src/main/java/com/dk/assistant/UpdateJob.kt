package com.dk.assistant

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * v3.6 (owner): हर 6 घंटे में अपने-आप अपडेट जाँच - ऐप खुली हो या बंद, फ़ोन restart के बाद भी (setPersisted)।
 * नया version मिला = नोटिफ़िकेशन "DK x.y आ गया" + असली बदलाव; टैप = अपडेट स्क्रीन। एक version का एक ही बार।
 */
class UpdateJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(p: JobParameters?): Boolean {
        scope.launch {
            try {
                val rel = Updater.check(applicationContext)
                if (rel != null) Updater.notifyNew(applicationContext, rel)
            } catch (e: Exception) { DKLog.d("update job: ${e.message}") }
            jobFinished(p, false)
        }
        return true
    }

    override fun onStopJob(p: JobParameters?): Boolean = true

    companion object {
        const val JOB_ID = 4202
        const val EVERY_MS = 6L * 60 * 60 * 1000

        fun schedule(c: Context) = try {
            val js = c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (js.allPendingJobs.none { it.id == JOB_ID && it.intervalMillis == EVERY_MS }) {
                js.schedule(JobInfo.Builder(JOB_ID, ComponentName(c, UpdateJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(EVERY_MS)
                    .setPersisted(true)
                    .build())
            }
            Unit
        } catch (e: Exception) { DKLog.d("update job schedule: ${e.message}") }
    }
}
