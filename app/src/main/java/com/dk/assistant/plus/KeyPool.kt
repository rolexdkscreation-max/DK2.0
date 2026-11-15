package com.dk.assistant.plus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 15 नवंबर 2026 (मालिक: "Sub Agent - कई API key, Base URL, Model; 'Use Them All' से 429 पर काम न रुके"):
 * कई keys का पूल. "सारी keys इस्तेमाल करो" ([FeatureFlags.KEY_POOL_ALL], default बंद):
 *  - बंद = सिर्फ़ पहली (मुख्य) entry, पहले जैसा
 *  - चालू = Round-Robin (हर नया काम अगली key से) + किसी key पर 429/limit हो तो उसे थोड़ी देर ठंडा करके अगली पर
 * keys [com.dk.assistant.SecretPrefs] वाली ताले की परत में ही रहती हैं (Prefs के ज़रिए), log में कभी नहीं.
 * Pure हिस्सा ([Pool]) JVM test में ([KeyPoolTest]).
 */
object KeyPool {

    data class Entry(val name: String, val baseUrl: String, val key: String, val model: String) {
        val usable get() = key.isNotBlank() && baseUrl.startsWith("https://")
        fun toJson(): JSONObject = JSONObject().put("name", name).put("base", baseUrl).put("key", key).put("model", model)
        companion object {
            fun of(o: JSONObject) = Entry(o.optString("name"), o.optString("base").trim().trimEnd('/'), o.optString("key").trim(), o.optString("model").trim())
        }
    }

    /** Pure round-robin + ठंडा (cooldown) करने का नियम. समय बाहर से (test के लिए) */
    class Pool(entries: List<Entry>, private val useAll: Boolean, private val coolMs: Long = 60_000L) {
        private val list = entries.filter { it.usable }
        private var next = 0
        private val coolUntil = HashMap<Int, Long>()

        val size get() = if (useAll) list.size else list.size.coerceAtMost(1)

        /** अभी किस क्रम में कोशिश करें: ठंडी keys आख़िर में, बाकी round-robin. [now] = ms */
        @Synchronized fun order(now: Long): List<Entry> {
            if (list.isEmpty()) return emptyList()
            if (!useAll) return listOf(list[0])
            val start = next % list.size
            next = (next + 1) % list.size
            val idx = (0 until list.size).map { (start + it) % list.size }
            val (warm, cold) = idx.partition { (coolUntil[it] ?: 0L) <= now }
            return (warm + cold.sortedBy { coolUntil[it] ?: 0L }).map { list[it] }
        }

        /** इस key पर limit (429) आई - [coolMs] तक इसे आख़िर में रखो */
        @Synchronized fun cool(e: Entry, now: Long) { list.indexOf(e).takeIf { it >= 0 }?.let { coolUntil[it] = now + coolMs } }

        /**
         * एक काम: हर key पर [call] (क्रम = [order]). [call] का नतीजा: Ok(text) = बस; Limit = अगली key; Fatal = रुको.
         */
        fun <T> run(now: () -> Long, call: (Entry) -> Res<T>): Res<T> {
            var last: Res<T> = Res.Fatal("कोई key नहीं डाली")
            for (e in order(now())) {
                when (val r = call(e)) {
                    is Res.Ok -> return r
                    is Res.Limit -> { cool(e, now()); last = r }
                    is Res.Fatal -> return r
                }
            }
            return last
        }
    }

    sealed class Res<out T> {
        data class Ok<T>(val value: T) : Res<T>()
        data class Limit(val why: String) : Res<Nothing>()
        data class Fatal(val why: String) : Res<Nothing>()
    }

    /** HTTP code से फ़ैसला: 429 / 503 / quota = अगली key; 401/403 = यही key खराब, अगली आज़माओ (पर ठंडी); बाकी = Fatal */
    fun classify(code: Int, body: String): String = when {
        code == 429 || code == 503 -> "limit"
        body.contains("quota", true) || body.contains("rate limit", true) || body.contains("RESOURCE_EXHAUSTED") -> "limit"
        code == 401 || code == 403 -> "limit"
        code in 200..299 -> "ok"
        else -> "fatal"
    }

    // ---------------------------------------------------------------- store
    private const val PREF = "plus_key_pool"
    fun load(c: Context): List<Entry> = try {
        val a = JSONArray(com.dk.assistant.Prefs.str(c, PREF, "[]"))
        (0 until a.length()).map { Entry.of(a.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    fun save(c: Context, list: List<Entry>) {
        val a = JSONArray(); list.forEach { a.put(it.toJson()) }
        com.dk.assistant.Prefs.edit(c) { putString(PREF, a.toString()) }
    }

    private var shared: Pool? = null
    private var sharedSig = ""
    /** पूरे ऐप का एक पूल (round-robin की गिनती याद रहे). Settings बदलने पर नया */
    @Synchronized fun pool(c: Context): Pool {
        val list = load(c); val all = FeatureFlags.on(c, FeatureFlags.KEY_POOL_ALL)
        val sig = list.hashCode().toString() + all
        if (shared == null || sig != sharedSig) { shared = Pool(list, all); sharedSig = sig }
        return shared!!
    }
}
