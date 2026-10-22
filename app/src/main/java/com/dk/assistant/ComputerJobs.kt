package com.dk.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * DK कंप्यूटर के तय किए काम (रिमाइंडर / बाद में चलने वाले काम).
 * फ़ोन में सेव रहते हैं -> फ़ोन restart या ऐप update के बाद ComputerBoot इन्हें दोबारा लगा देता है.
 * restart के समय जो काम निकल गया, वह फ़ोन चालू होते ही (1 मिनट बाद) "देर से" चलता है; 7 दिन से पुराना हटता है.
 */
object ComputerJobs {
    data class Job(val id: Int, val at: Long, val task: String)

    const val PREF = "pc_jobs"
    const val FIRST_ID = 20000
    const val LAST_ID = 29999
    const val LATE_DELAY = 60_000L
    const val MAX_LATE = 7 * 24 * 3600_000L

    // ------------------------------------------------------------ pure (tests)
    fun decode(raw: String?): List<Job> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optString("task")
                val at = o.optLong("at", 0L)
                if (t.isBlank() || at <= 0L) null else Job(o.optInt("id", FIRST_ID), at, t)
            }
        } catch (_: Exception) { emptyList() }
    }

    fun encode(jobs: List<Job>): String = JSONArray().apply {
        jobs.forEach { put(JSONObject().put("id", it.id).put("at", it.at).put("task", it.task)) }
    }.toString()

    /** अगला खाली id (20000..29999 में घूमता है) */
    fun nextId(jobs: List<Job>): Int {
        val used = jobs.map { it.id }.toSet()
        var id = (jobs.maxOfOrNull { it.id } ?: (FIRST_ID - 1)) + 1
        repeat(LAST_ID - FIRST_ID + 1) {
            if (id > LAST_ID) id = FIRST_ID
            if (id !in used) return id
            id++
        }
        return FIRST_ID
    }

    /** restart के बाद: हर काम कब चले (late = समय निकल चुका था). बहुत पुराने छोड़ दो */
    data class Plan(val job: Job, val fireAt: Long, val late: Boolean)
    fun plan(jobs: List<Job>, now: Long): List<Plan> = jobs.mapNotNull { j ->
        when {
            j.at > now -> Plan(j, j.at, false)
            now - j.at <= MAX_LATE -> Plan(j, now + LATE_DELAY, true)
            else -> null
        }
    }

    // ------------------------------------------------------------ फ़ोन में सेव
    private fun prefs(c: Context) = c.getSharedPreferences("dk_computer_jobs", Context.MODE_PRIVATE)
    @Synchronized fun all(c: Context): List<Job> = decode(prefs(c).getString(PREF, null)).sortedBy { it.at }
    @Synchronized private fun save(c: Context, jobs: List<Job>) { prefs(c).edit().putString(PREF, encode(jobs)).apply() }

    /** नया काम सेव करके alarm लगाओ */
    @Synchronized fun add(c: Context, at: Long, task: String): Job {
        val jobs = all(c)
        val j = Job(nextId(jobs), at, task)
        save(c, jobs + j)
        arm(c, j, at, late = false)
        return j
    }

    @Synchronized fun remove(c: Context, id: Int) { save(c, all(c).filterNot { it.id == id }) }

    @Synchronized fun cancelAll(c: Context): Int {
        val jobs = all(c)
        val am = c.getSystemService(AlarmManager::class.java)
        jobs.forEach { j -> try { am?.cancel(pending(c, j, late = false)) } catch (_: Exception) {} }
        save(c, emptyList())
        return jobs.size
    }

    /** restart / update के बाद सारे alarm फिर से */
    @Synchronized fun rearm(c: Context): Int {
        val all = all(c)
        val p = plan(all, System.currentTimeMillis())
        if (p.size != all.size) save(c, p.map { it.job })
        p.forEach { arm(c, it.job, it.fireAt, it.late) }
        return p.size
    }

    private fun pending(c: Context, j: Job, late: Boolean): PendingIntent =
        PendingIntent.getBroadcast(c, j.id, Intent(c, ComputerAlarm::class.java)
            .putExtra("id", j.id).putExtra("task", j.task).putExtra("late", late),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun arm(c: Context, j: Job, at: Long, late: Boolean) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        // exact alarm की इजाज़त ज़रूरी नहीं - कुछ मिनट आगे-पीछे चल सकता है
        if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, j, late))
        else am.set(AlarmManager.RTC_WAKEUP, at, pending(c, j, late))
    }
}

/** फ़ोन चालू होने / ऐप update होने पर DK कंप्यूटर के तय काम फिर से लगाओ */
class ComputerBoot : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.intent.action.QUICKBOOT_POWERON" ->
                try { val n = ComputerJobs.rearm(c.applicationContext); DKLog.d("DK कंप्यूटर: $n तय काम फिर से लगाए") } catch (_: Exception) {}
        }
    }
}
