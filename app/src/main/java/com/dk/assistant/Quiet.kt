package com.dk.assistant

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 16 नवंबर 2026 (ऑडिट Q1, मालिक: "कोड की सेहत बिगड़ी है, catch को जो दबाने वाले हैं, उसे सही करो").
 *
 * जड़: ~490 जगह `catch (_: Exception) {}` - गलती चुपचाप निगल ली जाती थी; कोई हिस्सा टूटे तो किसी को पता नहीं चलता था.
 * अब उन जगहों पर `catch (e: Exception) { Quiet.note(e) }`: काम पहले जैसा ही चलता है (कुछ नहीं फेंकता),
 * पर हर जगह (फ़ाइल:लाइन) की गिनती + आख़िरी गलती memory में रहती है - Settings → डिबग "दबी गलतियाँ" और
 * crash रिपोर्ट में दिखती है. नई खाली catch = detekt (EmptyCatchBlock) से CI लाल.
 *
 * नियम: कोई I/O नहीं, DKLog नहीं (DKLog के अंदर भी इस्तेमाल हो सके - कोई चक्कर नहीं), कभी exception नहीं फेंकता.
 * रुकवाया गया coroutine (CancellationException) गलती नहीं - गिना नहीं जाता. Pure (JVM test: QuietTest).
 */
object Quiet {
    const val MAX_SITES = 200

    class Site(val where: String) {
        val count = AtomicLong(0)
        @Volatile var last: String = ""
        @Volatile var at: Long = 0
    }

    private val sites = ConcurrentHashMap<String, Site>()
    private val total = AtomicLong(0)

    fun note(e: Throwable) {
        try {
            if (e is kotlinx.coroutines.CancellationException || e is InterruptedException) return
            total.incrementAndGet()
            val where = site(e)
            val s = sites[where] ?: (if (sites.size >= MAX_SITES) return else sites.getOrPut(where) { Site(where) })
            s.count.incrementAndGet()
            s.last = e.javaClass.simpleName + (e.message?.let { ": " + it.take(160) } ?: "")
            s.at = System.currentTimeMillis()
        } catch (_: Throwable) { /* ignore: यह खुद कभी न टूटे */ }
    }

    /** DK के अपने code की पहली लाइन (फ़ाइल:लाइन) - वहीं गलती निगली गई */
    internal fun site(e: Throwable): String = try {
        e.stackTrace.firstOrNull { it.className.startsWith("com.dk.") && !it.className.startsWith("com.dk.assistant.Quiet") }
            ?.let { "${it.fileName}:${it.lineNumber}" } ?: (e.stackTrace.firstOrNull()?.let { "${it.fileName}:${it.lineNumber}" } ?: "?")
    } catch (_: Throwable) { "?" }

    fun total(): Long = total.get()

    /** सबसे ज़्यादा बार वाली जगहें पहले (छिपाकर - key / token नहीं) */
    fun summary(max: Int = 25): String {
        val list = sites.values.sortedByDescending { it.count.get() }.take(max)
        if (list.isEmpty()) return "कोई दबी गलती नहीं"
        return "दबी गलतियाँ (कुल ${total()}):\n" + list.joinToString("\n") {
            "${it.count.get()}× ${it.where} — " + com.dk.assistant.inspector.InspectorCore.redact(it.last)
        }
    }

    fun clear() { sites.clear(); total.set(0) }
}
