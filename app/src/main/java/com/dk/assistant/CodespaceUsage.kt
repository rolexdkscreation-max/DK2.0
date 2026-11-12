package com.dk.assistant

import android.content.Context
import java.util.Calendar

/**
 * नवंबर 2026: Codespaces के फ़्री घंटों का फ़ोन वाला हिसाब ([CodespaceMeter] का Android हिस्सा).
 *  - DK ने codespace चालू/बंद किया = मिनट × core इस महीने के खाते में (DK का अनुमान)
 *  - GitHub billing API मिले (token में "Plan: read") = वही पक्का आँकड़ा, 30 मिनट cache
 */
object CodespaceUsage : CoderSandbox.Meter {
    private const val P = "dk_codespace"
    private const val API_TTL_MS = 30 * 60_000L
    @Volatile private var app: Context? = null

    fun init(c: Context): CodespaceUsage { app = c.applicationContext; return this }
    private fun prefs(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    private fun month(at: Long = System.currentTimeMillis()): String {
        val k = Calendar.getInstance().apply { timeInMillis = at }
        return CodespaceMeter.monthKey(k.get(Calendar.YEAR), k.get(Calendar.MONTH) + 1)
    }

    override fun up(cores: Int) {
        val c = app ?: return
        val p = prefs(c)
        if (p.getLong("start_at", 0L) > 0L) return   // पहले से चालू गिना जा रहा है
        p.edit().putLong("start_at", System.currentTimeMillis()).putInt("cores", cores.coerceAtLeast(1)).apply()
    }

    override fun down() {
        val c = app ?: return
        val p = prefs(c)
        val st = p.getLong("start_at", 0L).takeIf { it > 0 } ?: return
        val now = System.currentTimeMillis()
        val add = CodespaceMeter.session(st, now, p.getInt("cores", CodespaceMeter.CORES))
        val key = "cm_" + month(st)
        p.edit().putLong(key, p.getLong(key, 0L) + add).remove("start_at").apply()
    }

    /** अभी का हिसाब: API (इसी महीने का, ताज़ा) > DK का अपना (चालू वाला भी जोड़कर) */
    fun current(c: Context): CodespaceMeter.Usage {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val m = month(now)
        if (p.getString("api_month", "") == m) {
            p.getString("api_json", null)?.let { j -> CodespaceMeter.parse(j, p.getLong("api_at", 0L))?.let { return it } }
        }
        var mins = p.getLong("cm_$m", 0L)
        val st = p.getLong("start_at", 0L)
        if (st > 0 && month(st) == m) mins += CodespaceMeter.session(st, now, p.getInt("cores", CodespaceMeter.CORES))
        return CodespaceMeter.local(mins, now)
    }

    fun apiWhy(c: Context): String = prefs(c).getString("api_why", "").orEmpty()

    /** GitHub billing API से ताज़ा (नेटवर्क - IO thread पर). force = cache की परवाह नहीं */
    fun refresh(c: Context, force: Boolean = false): CodespaceMeter.Usage {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        if (!force && p.getString("api_month", "") == month(now) && now - p.getLong("api_at", 0L) < API_TTL_MS) return current(c)
        val gh = GitHub.fromPrefs(c) ?: run { p.edit().putString("api_why", "GitHub token नहीं जुड़ा").apply(); return current(c) }
        try {
            val login = org.json.JSONObject(gh.call("GET", "/user")).optString("login")
            if (login.isBlank()) throw IllegalStateException("GitHub user नहीं मिला")
            val k = Calendar.getInstance()
            val y = k.get(Calendar.YEAR); val mo = k.get(Calendar.MONTH) + 1
            val j = gh.call("GET", "/users/$login/settings/billing/usage/summary?year=$y&month=$mo&product=codespaces")
            if (CodespaceMeter.parse(j) != null || j.contains("usageItems")) {
                // खाली usageItems = इस महीने 0 घंटे (वह भी पक्का)
                val fixed = if (CodespaceMeter.parse(j) == null) """{"usageItems":[{"product":"codespaces","sku":"compute 2-core","unitType":"Hours","grossQuantity":0}]}""" else j
                p.edit().putString("api_json", fixed).putString("api_month", month(now)).putLong("api_at", now).putString("api_why", "").apply()
            }
        } catch (e: GitHub.Err) {
            p.edit().putString("api_why", if (e.code == 403 || e.code == 404) "token में \"Plan: read\" इजाज़त नहीं (HTTP ${e.code})" else "HTTP ${e.code}").apply()
        } catch (e: Exception) {
            p.edit().putString("api_why", (e.message ?: e.javaClass.simpleName).take(120)).apply()
        }
        return current(c)
    }
}
