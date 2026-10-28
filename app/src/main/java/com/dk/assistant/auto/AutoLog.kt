package com.dk.assistant.auto

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ऑटोनोमस मोड का हर कदम फ़ोन में (बाद में Settings → ऑटोनोमस मोड → लॉग देखो).
 * सिर्फ़ इसी फ़ोन में; API key / token / पासवर्ड / OTP / लंबे नंबर कभी नहीं लिखे जाते ([AutoText.clean]).
 */
object AutoLog {
    private const val FILE = "auto_log.txt"
    private const val MAX_LINES = 600
    private val lock = Any()

    private fun f(c: Context) = File(c.applicationContext.filesDir, FILE)

    fun add(c: Context, text: String) {
        val line = SimpleDateFormat("dd-MM HH:mm:ss", Locale.US).format(Date()) + "  " + AutoText.clean(text).take(400)
        synchronized(lock) {
            try {
                val file = f(c)
                val old = if (file.exists()) file.readLines() else emptyList()
                file.writeText((old.takeLast(MAX_LINES - 1) + line).joinToString("\n") + "\n")
            } catch (_: Exception) { }
        }
    }

    fun read(c: Context): String = synchronized(lock) { try { f(c).takeIf { it.exists() }?.readText().orEmpty() } catch (_: Exception) { "" } }

    fun clear(c: Context) = synchronized(lock) { try { f(c).delete() } catch (_: Exception) { }; Unit }
}

/** शुद्ध (JVM test): लॉग में जाने से पहले key / token / निजी नंबर हटाओ */
object AutoText {
    private val KEYS = Regex(
        "(AIza[0-9A-Za-z_\\-]{20,}|gsk_[0-9A-Za-z]{10,}|sk-[0-9A-Za-z_\\-]{10,}|tvly-[0-9A-Za-z_\\-]{8,}|" +
            "gh[pousr]_[0-9A-Za-z]{16,}|github_pat_[0-9A-Za-z_]{16,}|xox[abp]-[0-9A-Za-z\\-]{10,}|Bearer\\s+[0-9A-Za-z._\\-]{12,})"
    )
    private val LONG_NUM = Regex("(?<!\\d)(?:\\d[ -]?){8,18}\\d(?!\\d)")
    private val CODE = Regex("(?i)(otp|ओटीपी|code|कोड|pin|पिन|password|पासवर्ड|cvv)(\\s*(is|है|:|=|-))?\\s*(?=\\S*\\d)[0-9A-Za-z@#$%!]{4,}")
    fun clean(s: String): String = KEYS.replace(s, "[key हटाई]")
        .replace(CODE) { it.groupValues[1] + " [हटाया]" }
        .replace(LONG_NUM, "[नंबर हटाया]")
        .replace('\n', ' ')
}
