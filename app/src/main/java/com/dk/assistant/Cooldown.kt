package com.dk.assistant

import android.content.Context
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Kis model/cheez ki limit (429 / quota) khatam hai aur kab tak - PHONE ME SAVE (app band-chalu hone par bhi yaad).
 * Pehle yeh sirf memory me tha: har baar app/Live shuru hote hi wahi band model dobara maanga jata tha (deri + "गिरे" tukde).
 */
object Cooldown {
    private val mem = ConcurrentHashMap<String, Long>()
    private fun sp() = DKState.appCtx?.getSharedPreferences("dk_cooldown", Context.MODE_PRIVATE)

    fun until(key: String): Long {
        val m = mem[key]
        if (m != null) return m
        val v = try { sp()?.getLong(key, 0L) ?: 0L } catch (_: Exception) { 0L }
        mem[key] = v
        return v
    }

    fun blocked(key: String) = until(key) > System.currentTimeMillis()

    fun block(key: String, ms: Long) {
        val t = System.currentTimeMillis() + ms
        mem[key] = t
        try { sp()?.edit()?.putLong(key, t)?.apply() } catch (_: Exception) {}
    }

    fun clear(key: String) {
        mem[key] = 0L
        try { sp()?.edit()?.remove(key)?.apply() } catch (_: Exception) {}
    }

    /** Aaj kitni baar fail hua (din badalte hi 0) */
    fun strike(key: String): Int {
        val day = dayKey()
        val p = sp()
        val k = "strike:$key"
        val saved = try { p?.getString(k, "") ?: "" } catch (_: Exception) { "" }
        val n = if (saved.startsWith("$day:")) (saved.substringAfter(':').toIntOrNull() ?: 0) + 1 else 1
        try { p?.edit()?.putString(k, "$day:$n")?.apply() } catch (_: Exception) {}
        return n
    }

    /** Gemini ki free daily limit Pacific time (America/Los_Angeles) ki aadhi raat ko wapas aati hai */
    fun msToQuotaReset(): Long {
        val tz = TimeZone.getTimeZone("America/Los_Angeles")
        val c = Calendar.getInstance(tz)
        c.add(Calendar.DAY_OF_YEAR, 1)
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 2); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return (c.timeInMillis - System.currentTimeMillis()).coerceAtLeast(60_000L)
    }

    private fun dayKey(): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"))
        return "${c.get(Calendar.YEAR)}-${c.get(Calendar.DAY_OF_YEAR)}"
    }
}
