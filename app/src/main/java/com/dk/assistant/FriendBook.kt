package com.dk.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * v3.45: DK की "दोस्त वाली" याददाश्त (Android import नहीं -> JVM unit test). फ़ाइल सिर्फ़ फ़ोन पर (FriendTalk).
 *
 * - हर बातचीत: कब शुरू, कब खत्म, कितनी देर, छोटा सारांश, मूड
 * - आखिरी मुलाकात (lastSeen) -> "पूरे 3 दिन बाद याद आई?"
 * - अधूरी बातें (threads): "कल इंटरव्यू है", "माँ की तबियत ठीक नहीं" -> अगली बार DK खुद पूछे
 * - "यह भूल जाओ" -> [forget] सब जगह से मिटाए. निजी नंबर/पासवर्ड [Sensitive] कभी अंदर नहीं.
 */
class FriendBook(
    val sessions: MutableList<Session> = mutableListOf(),
    val threads: MutableList<Thread> = mutableListOf(),
    var lastSeen: Long = 0L,
    var lastGreet: Long = 0L,
    var mood: String = "",
) {
    data class Session(val start: Long, val end: Long, val summary: String, val mood: String = "") {
        val minutes: Long get() = ((end - start) / 60_000).coerceAtLeast(0)
    }
    data class Thread(val text: String, val at: Long)

    companion object {
        const val MAX_SESSIONS = 40
        const val MAX_THREADS = 12
        const val THREAD_DAYS = 30L

        fun fromJson(s: String): FriendBook = try {
            val o = JSONObject(s)
            val b = FriendBook(lastSeen = o.optLong("lastSeen"), lastGreet = o.optLong("lastGreet"), mood = o.optString("mood"))
            o.optJSONArray("sessions")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { x ->
                    b.sessions.add(Session(x.optLong("start"), x.optLong("end"), x.optString("summary"), x.optString("mood")))
                }
            }
            o.optJSONArray("threads")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { x -> b.threads.add(Thread(x.optString("text"), x.optLong("at"))) }
            }
            b
        } catch (_: Exception) { FriendBook() }

        private fun words(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), " ").split(' ').filter { it.length >= 3 }.toSet()

        /** दो बातें एक ही विषय पर? (अधूरी बात पूरी हुई / दोहराव) */
        fun similar(a: String, b: String): Boolean {
            val x = words(a); val y = words(b)
            if (x.isEmpty() || y.isEmpty()) return false
            val shared = x.intersect(y)
            // आधे शब्द मिलें, या कोई लंबा खास शब्द ("इंटरव्यू") दोनों में हो
            return shared.isNotEmpty() && (shared.size * 2 >= minOf(x.size, y.size) || shared.any { it.length >= 6 })
        }
    }

    fun toJson(): String = JSONObject()
        .put("lastSeen", lastSeen).put("lastGreet", lastGreet).put("mood", mood)
        .put("sessions", JSONArray().apply { sessions.forEach { put(JSONObject().put("start", it.start).put("end", it.end).put("summary", it.summary).put("mood", it.mood)) } })
        .put("threads", JSONArray().apply { threads.forEach { put(JSONObject().put("text", it.text).put("at", it.at)) } })
        .toString()

    /** बातचीत खत्म: सारांश (निजी नंबर हटाकर) + आखिरी मुलाकात */
    fun addSession(start: Long, end: Long, summary: String, mood: String = "") {
        val s = Sensitive.redact(summary.trim())
        sessions.add(Session(start, end, s, mood.trim()))
        while (sessions.size > MAX_SESSIONS) sessions.removeAt(0)
        if (end > lastSeen) lastSeen = end
        if (mood.isNotBlank()) this.mood = mood.trim()
    }

    /** नई अधूरी बातें जोड़ो, पूरी हुई हटाओ; पुरानी (30 दिन) अपने-आप हटें */
    fun updateThreads(open: List<String>, done: List<String>, now: Long) {
        threads.removeAll { t -> done.any { similar(it, t.text) } }
        for (o in open) {
            val t = o.trim()
            if (t.isBlank() || Sensitive.isSensitive(t)) continue
            threads.removeAll { similar(it.text, t) }
            threads.add(Thread(t, now))
        }
        threads.removeAll { now - it.at > THREAD_DAYS * 86_400_000L }
        while (threads.size > MAX_THREADS) threads.removeAt(0)
    }

    /** "यह भूल जाओ" - सारांश के वाक्य, अधूरी बातें, सब जगह से. कितनी जगह से मिटा लौटाता है */
    fun forget(about: String): Int {
        val q = about.trim()
        if (q.isBlank()) return 0
        var n = 0
        val tb = threads.size
        threads.removeAll { similar(q, it.text) || it.text.contains(q, ignoreCase = true) }
        n += tb - threads.size
        for (i in sessions.indices) {
            val s = sessions[i]
            val parts = s.summary.split(Regex("(?<=[।.!?\\n])"))
            val kept = parts.filterNot { p -> p.isNotBlank() && (p.contains(q, ignoreCase = true) || similar(q, p)) }
            if (kept.size != parts.size) { n += parts.size - kept.size; sessions[i] = s.copy(summary = kept.joinToString("").trim()) }
        }
        return n
    }

    fun clear() { sessions.clear(); threads.clear(); lastSeen = 0L; lastGreet = 0L; mood = "" }

    /** आखिरी मुलाकात से अब तक */
    fun gapMs(now: Long): Long = if (lastSeen <= 0) -1 else (now - lastSeen).coerceAtLeast(0)

    /** इंसानों जैसा: "अभी थोड़ी देर पहले" / "5 घंटे पहले" / "3 दिन पहले" */
    fun gapText(now: Long): String {
        val g = gapMs(now)
        if (g < 0) return "पहली मुलाकात"
        val min = g / 60_000; val hr = min / 60; val day = hr / 24
        return when {
            min < 10 -> "अभी थोड़ी देर पहले"
            min < 60 -> "$min मिनट पहले"
            hr < 24 -> "$hr घंटे पहले"
            day == 1L -> "कल (1 दिन पहले)"
            else -> "$day दिन पहले"
        }
    }

    /** system instruction में जुड़ने वाला हिस्सा (हर बार Live/चैट शुरू होते ही) */
    fun promptBlock(now: Long, zone: ZoneId = ZoneId.systemDefault(), maxSessions: Int = 5): String = buildString {
        val fmt = DateTimeFormatter.ofPattern("d MMM, EEE h:mm a", Locale.ENGLISH).withZone(zone)
        append("\nMEMORY OF YOUR FRIENDSHIP (private, on this phone; use it naturally like a friend who remembers - never read it out as a list):")
        if (lastSeen <= 0) append("\n- This is your first conversation with the user. Get to know them warmly.")
        else append("\n- Last time you talked: ${fmt.format(Instant.ofEpochMilli(lastSeen))} (${gapText(now)}).")
        if (mood.isNotBlank()) append("\n- The user's mood last time: $mood.")
        val recent = sessions.filter { it.summary.isNotBlank() }.takeLast(maxSessions)
        if (recent.isNotEmpty()) {
            append("\n- Earlier conversations (oldest first):")
            recent.forEach { append("\n  • ${fmt.format(Instant.ofEpochMilli(it.start))} (${it.minutes} min): ${it.summary.take(300)}") }
        }
        if (threads.isNotEmpty()) {
            append("\n- Unfinished things the user told you (ask about ONE of these naturally when it fits, e.g. \"उस दिन तुम ... बता रहे थे, उसका क्या हुआ?\"):")
            threads.takeLast(5).forEach { append("\n  • ${it.text} (told ${fmt.format(Instant.ofEpochMilli(it.at))})") }
        }
    }
}
