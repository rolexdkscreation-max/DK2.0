package com.dk.assistant.insights

import android.content.Context
import com.dk.assistant.DKLog
import java.io.File

/**
 * 24 नवंबर 2026 (मालिक: "Reports go in Settings → DK Social Agent → Reports, newest first, openable, deletable").
 * हर सोशल रिव्यू की रिपोर्ट ऐप के अपने फ़ोल्डर में भी (Download/DK Reports वाली फ़ाइल पहले जैसी बनती है).
 * ज़्यादा से ज़्यादा [MAX] रिपोर्ट - पुरानी अपने-आप हटती हैं. Pure हिस्सा ([Rules]) JVM test में.
 */
object SocialReports {
    const val MAX = 50
    private const val DIR = "social_reports"

    data class Item(val name: String, val at: Long, val title: String)

    object Rules {
        /** फ़ाइल नाम = समय (ms).md */
        fun fileName(at: Long) = "$at.md"
        fun timeOf(name: String): Long? = name.removeSuffix(".md").toLongOrNull()?.takeIf { name.endsWith(".md") }

        /** सबसे नई ऊपर */
        fun newestFirst(names: List<String>): List<String> = names.filter { timeOf(it) != null }.sortedByDescending { timeOf(it) }

        /** [MAX] से ऊपर वाली (सबसे पुरानी) - मिटानी हैं */
        fun overflow(names: List<String>, max: Int = MAX): List<String> = newestFirst(names).drop(max)

        /** सूची में दिखने वाला नाम: रिपोर्ट की पहली heading / लाइन (markdown साफ़) */
        fun title(report: String): String = report.lineSequence().map { it.trim().trimStart('#', ' ', '*').trim() }
            .firstOrNull { it.isNotBlank() && it != "सार" }?.take(70) ?: "सोशल रिव्यू"

        /** सिर्फ़ हमारे फ़ोल्डर की फ़ाइल ("../" वगैरह नहीं) */
        fun safe(name: String) = timeOf(name) != null && !name.contains('/') && !name.contains("..")
    }

    private fun dir(c: Context) = File(c.filesDir, DIR).apply { mkdirs() }

    fun add(c: Context, report: String, at: Long = System.currentTimeMillis()) {
        if (report.isBlank()) return
        try {
            val d = dir(c)
            File(d, Rules.fileName(at)).writeText(report)
            Rules.overflow(d.list()?.toList().orEmpty()).forEach { File(d, it).delete() }
        } catch (e: Exception) { DKLog.d("सोशल रिपोर्ट सेव नहीं: ${e.message}") }
    }

    fun list(c: Context): List<Item> = Rules.newestFirst(dir(c).list()?.toList().orEmpty()).map { n ->
        val t = try { File(dir(c), n).useLines { l -> Rules.title(l.take(8).joinToString("\n")) } } catch (_: Exception) { "सोशल रिव्यू" }
        Item(n, Rules.timeOf(n) ?: 0L, t)
    }

    fun read(c: Context, name: String): String = if (!Rules.safe(name)) "" else try { File(dir(c), name).readText() } catch (_: Exception) { "" }

    fun delete(c: Context, name: String): Boolean = Rules.safe(name) && File(dir(c), name).delete()
}
