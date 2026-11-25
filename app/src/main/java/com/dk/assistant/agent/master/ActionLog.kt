package com.dk.assistant.agent.master

import android.content.Context
import com.dk.assistant.Quiet
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 24 नवंबर 2026 (मालिक: "हर कार्रवाई को लॉग करना है"): मास्टर एजेंट के हर काम का छोटा लॉग (फ़ोन में ही, बाहर नहीं जाता).
 * सबसे नया ऊपर, ज़्यादा से ज़्यादा [MAX] लाइनें. पासवर्ड / OTP जैसे अंक लॉग में नहीं ([Rules.mask]).
 */
object ActionLog {
    const val MAX = 300
    private const val PREF = "dk_master_log"
    private const val KEY = "log"

    /** Pure हिस्सा - JVM test */
    object Rules {
        /** 4+ अंक वाले टुकड़े (OTP / PIN / कार्ड / खाता) छिपाओ, लंबाई कम */
        fun mask(t: String): String = t.replace(Regex("\\d{4,}")) { "•".repeat(minOf(it.value.length, 6)) }.replace('\n', ' ').take(220)

        /** नई लाइन ऊपर, [max] से ज़्यादा पुरानी हटाओ */
        fun add(old: List<String>, line: String, max: Int = MAX): List<String> = (listOf(line) + old).take(max)
    }

    @Synchronized
    fun add(c: Context, kind: String, text: String) {
        try {
            val sp = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val old = read(sp.getString(KEY, null))
            val o = JSONObject().put("t", System.currentTimeMillis()).put("k", kind).put("x", Rules.mask(text)).toString()
            sp.edit().putString(KEY, JSONArray(Rules.add(old, o)).toString()).apply()
        } catch (e: Exception) { Quiet.note(e) }
    }

    private fun read(s: String?): List<String> = try {
        if (s.isNullOrBlank()) emptyList() else JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } }
    } catch (_: Exception) { emptyList() }

    /** Settings में दिखाने लायक आख़िरी [n] लाइनें */
    fun text(c: Context, n: Int): String {
        val f = SimpleDateFormat("dd MMM HH:mm", Locale.US)
        return read(c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)).take(n).mapNotNull {
            try { val o = JSONObject(it); "${f.format(Date(o.optLong("t")))} • ${o.optString("k")}: ${o.optString("x")}" } catch (_: Exception) { null }
        }.joinToString("\n")
    }

    fun clear(c: Context) {
        try { c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply() } catch (e: Exception) { Quiet.note(e) }
    }
}
