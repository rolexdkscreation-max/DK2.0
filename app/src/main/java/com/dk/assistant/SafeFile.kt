package com.dk.assistant

import java.io.File
import java.io.FileOutputStream

/**
 * 16 नवंबर 2026 (ऑडिट P0-3, मालिक: "ठीक है करो 1.2.3"): यादें / चैट / इतिहास की फ़ाइल सुरक्षित लिखना.
 * पहले `writeText()` सीधे पुरानी फ़ाइल के ऊपर लिखता था - बीच में ऐप मरा / बैटरी गई = आधी फ़ाइल, अगली बार पढ़ना fail
 * और सब कुछ चुपचाप खाली. अब:
 *  1. नया text पहले `नाम.tmp` में, डिस्क पर पक्का (`fd.sync`)
 *  2. पुरानी फ़ाइल `नाम.bak` बनती है
 *  3. `.tmp` -> असली नाम (rename, एक झटके में)
 * पढ़ते समय फ़ाइल गायब / टूटी हो तो `.bak` से (पिछली सही हालत), और log में साफ़ लाइन.
 */
object SafeFile {
    fun tmp(f: File) = File(f.path + ".tmp")
    fun bak(f: File) = File(f.path + ".bak")

    /** सुरक्षित लिखना. गलती हो तो Exception (पुरानी फ़ाइल / .bak वैसी ही रहती है) */
    fun write(f: File, text: String) {
        f.parentFile?.mkdirs()
        val t = tmp(f)
        FileOutputStream(t).use { o -> o.write(text.toByteArray(Charsets.UTF_8)); o.flush(); try { o.fd.sync() } catch (_: Exception) {} }
        if (f.exists()) {
            val b = bak(f)
            if (b.exists()) b.delete()
            if (!f.renameTo(b)) f.copyTo(b, overwrite = true)
        }
        if (!t.renameTo(f)) { t.copyTo(f, overwrite = true); t.delete() }
    }

    /**
     * पढ़ना: असली फ़ाइल [valid] हो तो वही, वरना `.bak`, वरना null. [valid] = यह text पूरा/सही है?
     * (JSON के लिए [validJson]). दोनों न हों = null (नया install).
     */
    fun read(f: File, valid: (String) -> Boolean = ::validJson): String? {
        val main = try { f.takeIf { it.exists() }?.readText(Charsets.UTF_8) } catch (_: Exception) { null }
        if (main != null && valid(main)) return main
        val b = bak(f)
        val old = try { b.takeIf { it.exists() }?.readText(Charsets.UTF_8) } catch (_: Exception) { null }
        if (old != null && valid(old)) {
            if (main != null || !f.exists()) DKLog.d("SafeFile: ${f.name} ${if (main == null) "नहीं मिली" else "टूटी थी"} - पिछली सही कॉपी (.bak) से वापस")
            return old
        }
        if (main != null) DKLog.d("SafeFile: ${f.name} टूटी है और .bak भी नहीं - खाली से शुरू")
        return null
    }

    /** पूरा JSON (array/object) है? आधी लिखी फ़ाइल = false. Pure */
    fun validJson(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        return try {
            when (t[0]) {
                '[' -> { org.json.JSONArray(t); true }
                '{' -> { org.json.JSONObject(t); true }
                else -> false
            }
        } catch (_: Exception) { false }
    }

    /** फ़ाइल + उसकी .bak/.tmp - "सब मिटाओ" में */
    fun delete(f: File) { f.delete(); bak(f).delete(); tmp(f).delete() }
}
