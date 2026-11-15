package com.dk.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 15 नवंबर 2026 (मालिक): "DK वॉचमैन - ऑन करके फ़ोन सामने रख दूँ, कौन आ रहा है, कौन जा रहा है, DK देखता रहे. बाद में पूछूँ तो
 * बताए कौन कितनी बार आया और क्या-क्या किया. जानकारी सिर्फ़ Boss को - पहले DK ख़ुद चेहरा स्कैन खोले, Boss हो तो ही बताए."
 *
 * - [WatchmanActivity]: आगे वाला कैमरा, हर ~1 s चेहरे ([FaceAI.detectAll]) -> कौन ([FacePeople.whoAll]: Boss / सेव किए लोग /
 *   अनजान). अनजान चेहरों को आपस में मिलाकर "अनजान 1, अनजान 2" (एक ही अनजान दोबारा आए तो वही नंबर).
 * - "आना" (visit): वही व्यक्ति [GAP_MS] से ज़्यादा न दिखे तो अगली बार नया आना. हर आने पर: कब आया, कितनी देर, फ़ोन के पास आया,
 *   फ़ोन (स्क्रीन) छुआ, साथ में कौन था, और एक फ़ोटो.
 * - रिपोर्ट सिर्फ़ [BossCheckActivity] (सख़्त Boss जाँच) के बाद.
 * - सब कुछ सिर्फ़ इसी फ़ोन में: लॉग [KEY] (AndroidKeyStore वाले ताले में), फ़ोटो filesDir/watchman. [KEEP_DAYS] दिन बाद पुराना हटता है.
 * Pure हिस्सा ([Book], [report], [matchUnknown]) JVM test में.
 */
object Watchman {
    const val KEY = "watchman_log_v1"
    /** इतनी देर न दिखे = चला गया (अगली बार नया आना) */
    const val GAP_MS = 90_000L
    /** इतने फ़्रेम से कम = गिनती नहीं (एक झलक / ग़लत पहचान) */
    const val MIN_FRAMES = 2
    /** चेहरा फ़्रेम की चौड़ाई का इतना हिस्सा = फ़ोन के बिल्कुल पास */
    const val NEAR_FRAC = 0.33f
    /** अनजान चेहरे आपस में इतना मिलें = वही अनजान आदमी */
    const val UNKNOWN_SAME = 0.40f
    const val MAX_UNKNOWN = 30
    const val MAX_VISITS = 400
    const val MAX_EVENTS = 200
    const val MAX_PHOTOS = 150
    const val KEEP_DAYS = 7
    const val BOSS_ID = "boss"

    data class Visit(
        val id: String, val name: String, val kind: String, val rel: String,
        var start: Long, var end: Long, var frames: Int = 1, var near: Boolean = false, var touched: Int = 0,
        var photo: String = "", val with: MutableSet<String> = mutableSetOf(),
    )
    data class Event(val at: Long, val text: String)
    data class Session(val start: Long, var end: Long)

    class Book {
        val visits = mutableListOf<Visit>()
        val events = mutableListOf<Event>()
        val sessions = mutableListOf<Session>()
        /** अनजान: id -> कुछ पहचान (अंक) */
        val unknown = LinkedHashMap<String, MutableList<FloatArray>>()
        private var nextUnknown = 1

        fun start(now: Long) { sessions += Session(now, now); events += Event(now, "वॉचमैन चालू हुआ") ; trim(now) }
        fun stop(now: Long, why: String) { sessions.lastOrNull()?.end = now; events += Event(now, why) }
        fun tick(now: Long) { sessions.lastOrNull()?.end = now }
        fun note(now: Long, text: String) { events += Event(now, text); while (events.size > MAX_EVENTS) events.removeAt(0) }

        /** अनजान चेहरे का नंबर (पुराना मिले तो वही, वरना नया) */
        fun unknownId(e: FloatArray): String {
            matchUnknown(e, unknown)?.let { id -> unknown[id]?.let { if (it.size < 5) it += e }; return id }
            val id = "u${nextUnknown++}"
            unknown[id] = mutableListOf(e)
            while (unknown.size > MAX_UNKNOWN) unknown.remove(unknown.keys.first())
            return id
        }

        /** यह व्यक्ति अभी दिखा. लौटाता है: (visit, नया आना?) */
        fun see(id: String, name: String, kind: String, rel: String, now: Long, faceFrac: Float, others: Collection<String> = emptyList()): Pair<Visit, Boolean> {
            val open = visits.lastOrNull { it.id == id && now - it.end <= GAP_MS }
            val v = open ?: Visit(id, name, kind, rel, now, now).also { visits += it; while (visits.size > MAX_VISITS) visits.removeAt(0) }
            if (open != null) { v.end = now; v.frames++ }
            if (faceFrac >= NEAR_FRAC) v.near = true
            v.with += others.filter { it != id }
            tick(now)
            return v to (open == null)
        }

        /** स्क्रीन छुई: अभी जो सामने हैं उनके नाम */
        fun touch(now: Long): List<Visit> {
            val here = visits.filter { now - it.end in 0..5_000L }
            here.forEach { it.touched++ }
            note(now, if (here.isEmpty()) "फ़ोन (स्क्रीन) छुआ गया — चेहरा नहीं दिखा" else "फ़ोन छुआ: " + here.joinToString(", ") { label(it) })
            return here
        }

        fun trim(now: Long) {
            val old = now - KEEP_DAYS * 24 * 3_600_000L
            visits.removeAll { it.end < old }; events.removeAll { it.at < old }; sessions.removeAll { it.end < old }
        }

        fun clear() { visits.clear(); events.clear(); sessions.clear(); unknown.clear(); nextUnknown = 1 }

        fun label(v: Visit): String = when {
            v.id == BOSS_ID -> "आप (Boss)"
            v.kind == "unknown" -> "अनजान व्यक्ति ${v.id.removePrefix("u")}"
            FacePeople.relationOf(v.kind, v.rel) == v.name -> FacePeople.relationPhrase(v.kind, v.rel).ifBlank { v.name }
            else -> v.name + FacePeople.relationPhrase(v.kind, v.rel).let { if (it.isBlank()) "" else " ($it)" }
        }

        // ------------------------------------------------------------------ सहेजना
        fun toJson(): String = JSONObject().apply {
            put("next", nextUnknown)
            put("visits", JSONArray().apply { visits.forEach { v -> put(JSONObject().put("id", v.id).put("n", v.name).put("k", v.kind).put("r", v.rel)
                .put("s", v.start).put("e", v.end).put("f", v.frames).put("near", v.near).put("t", v.touched).put("p", v.photo)
                .put("w", JSONArray(v.with.toList()))) } })
            put("events", JSONArray().apply { events.forEach { put(JSONObject().put("a", it.at).put("x", it.text)) } })
            put("sessions", JSONArray().apply { sessions.forEach { put(JSONObject().put("s", it.start).put("e", it.end)) } })
            put("unknown", JSONObject().apply { unknown.forEach { (id, ps) -> put(id, JSONArray().apply { ps.forEach { p -> put(JSONArray().apply { p.forEach { put(it.toDouble()) } }) } }) } })
        }.toString()

        companion object {
            fun fromJson(raw: String?): Book {
                val b = Book(); if (raw.isNullOrBlank()) return b
                try {
                    val o = JSONObject(raw)
                    b.nextUnknown = o.optInt("next", 1)
                    o.optJSONArray("visits")?.let { a -> for (i in 0 until a.length()) { val v = a.getJSONObject(i)
                        val w = v.optJSONArray("w"); val ws = mutableSetOf<String>(); if (w != null) for (j in 0 until w.length()) ws += w.getString(j)
                        b.visits += Visit(v.getString("id"), v.optString("n"), v.optString("k"), v.optString("r"), v.getLong("s"), v.getLong("e"),
                            v.optInt("f", 1), v.optBoolean("near"), v.optInt("t"), v.optString("p"), ws) } }
                    o.optJSONArray("events")?.let { a -> for (i in 0 until a.length()) { val e = a.getJSONObject(i); b.events += Event(e.getLong("a"), e.optString("x")) } }
                    o.optJSONArray("sessions")?.let { a -> for (i in 0 until a.length()) { val s = a.getJSONObject(i); b.sessions += Session(s.getLong("s"), s.getLong("e")) } }
                    o.optJSONObject("unknown")?.let { u -> u.keys().forEach { id -> val ps = u.getJSONArray(id)
                        b.unknown[id] = MutableList(ps.length()) { i -> val p = ps.getJSONArray(i); FloatArray(p.length()) { j -> p.getDouble(j).toFloat() } } } }
                } catch (_: Exception) {}
                return b
            }
        }
    }

    // ------------------------------------------------------------------ pure
    fun matchUnknown(e: FloatArray, known: Map<String, List<FloatArray>>, same: Float = UNKNOWN_SAME): String? =
        known.mapNotNull { (id, ps) -> ps.maxOfOrNull { FaceMatch.cosineSimilarity(it, e) }?.let { id to it } }
            .filter { it.second >= same }.maxByOrNull { it.second }?.first

    private fun hm(ms: Long) = SimpleDateFormat("hh:mm a", Locale("hi", "IN")).format(Date(ms))
    private fun day(ms: Long) = SimpleDateFormat("dd MMM", Locale("hi", "IN")).format(Date(ms))

    fun dur(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(1)
        return when { s < 60 -> "$s सेकंड"; s < 3600 -> "${s / 60} मिनट"; else -> "${s / 3600} घंटे ${(s % 3600) / 60} मिनट" }
    }

    /**
     * Boss के लिए पूरी रिपोर्ट: [from] के बाद (0 = आख़िरी वॉचमैन चालू होने से). हर व्यक्ति: कितनी बार, कब-कब, कितनी देर,
     * पास आया / फ़ोन छुआ / साथ में कौन. फिर ख़ास घटनाएँ (स्क्रीन छोड़ी, छुआ).
     */
    fun report(b: Book, from: Long, now: Long): String {
        val since = if (from > 0) from else b.sessions.lastOrNull()?.start ?: return "वॉचमैन अभी तक कभी चालू नहीं हुआ — कोई रिकॉर्ड नहीं।"
        val vs = b.visits.filter { it.end >= since && it.frames >= MIN_FRAMES }
        val until = b.sessions.lastOrNull()?.end?.coerceAtMost(now) ?: now
        val sb = StringBuilder("DK वॉचमैन रिपोर्ट — ${day(since)} ${hm(since)} से ${hm(until)} तक (${dur(until - since)})\n")
        if (vs.isEmpty()) sb.append("कोई नहीं आया — कैमरे के सामने कोई चेहरा नहीं दिखा।\n")
        else {
            val people = vs.groupBy { it.id }.values.sortedWith(compareBy<List<Visit>> { it.first().id == BOSS_ID }.thenByDescending { it.size })
            val names = vs.associate { it.id to b.label(it) }
            sb.append("कुल ${people.count { it.first().id != BOSS_ID }} लोग आए")
            if (people.any { it.first().id == BOSS_ID }) sb.append(" (आपके अलावा)")
            sb.append(":\n")
            for (p in people) {
                val f = p.first()
                sb.append("• ${b.label(f)} — ${p.size} बार")
                sb.append(": " + p.take(6).joinToString(", ") { "${hm(it.start)} (${dur(it.end - it.start)})" })
                if (p.size > 6) sb.append(" …")
                val did = buildList {
                    if (p.any { it.near }) add("फ़ोन के बिल्कुल पास आए")
                    p.sumOf { it.touched }.let { if (it > 0) add("फ़ोन $it बार छुआ") }
                    p.flatMap { it.with }.toSet().mapNotNull { names[it] }.let { if (it.isNotEmpty()) add("साथ में: " + it.joinToString(", ")) }
                    if (f.kind == "unknown") add("फ़ोटो सेव")
                }
                if (did.isNotEmpty()) sb.append(" — " + did.joinToString("; "))
                sb.append("\n")
            }
        }
        val ev = b.events.filter { it.at >= since && it.text != "वॉचमैन चालू हुआ" }
        if (ev.isNotEmpty()) {
            sb.append("घटनाएँ:\n")
            ev.takeLast(10).forEach { sb.append("• ${hm(it.at)} — ${it.text}\n") }
        }
        return sb.toString().trim()
    }

    // ------------------------------------------------------------------ फ़ोन पर
    @Volatile var running = false
    private val lock = Any()
    private var cache: Book? = null

    fun book(c: Context): Book = synchronized(lock) { cache ?: Book.fromJson(Prefs.str(c, KEY, "")).also { cache = it } }
    fun save(c: Context) = synchronized(lock) { cache?.let { b -> Prefs.edit(c) { putString(KEY, b.toJson()) } } }

    fun dir(c: Context) = File(c.filesDir, "watchman").apply { mkdirs() }
    fun photos(c: Context): List<File> = (dir(c).listFiles { f -> f.name.endsWith(".jpg") } ?: emptyArray()).sortedByDescending { it.name }

    fun savePhoto(c: Context, bmp: android.graphics.Bitmap): String? = try {
        val f = File(dir(c), "watch_${System.currentTimeMillis()}.jpg")
        val (w, h) = GuardPhotos.fitSide(bmp.width, bmp.height, 1280)
        val s = if (w == bmp.width) bmp else android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
        f.outputStream().use { s.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        if (s !== bmp) s.recycle()
        photos(c).drop(MAX_PHOTOS).forEach { it.delete() }
        f.name
    } catch (_: Exception) { null }

    fun clearAll(c: Context) {
        synchronized(lock) { book(c).clear(); save(c) }
        photos(c).forEach { it.delete() }
    }

    /** रिपोर्ट (सिर्फ़ Boss जाँच के बाद बुलाओ). [hours] > 0 = पिछले इतने घंटे */
    fun reportFor(c: Context, hours: Int = 0): String {
        val now = System.currentTimeMillis()
        return synchronized(lock) { report(book(c), if (hours > 0) now - hours * 3_600_000L else 0L, now) }
    }
}
