package com.dk.assistant

/**
 * v5.1 (मालिक: "DK लाइव 1-2 सेकंड में जुड़े, धीमे नेट पर भी चले, key/मॉडल खराब हो तो भी"):
 * (मॉडल × key) की जोड़ियों की दौड़. Android के बिना (JVM test में चलता है).
 *
 *  - क्रम: पिछली बार जो जोड़ी जुड़ी वह पहले, फिर हाल में जुड़ी हुईं, फिर बाकी (मॉडल का अपना क्रम, हर मॉडल पर हर key)
 *  - जोड़ी #1 शुरू; 1.5s में setup न हुआ = #2 भी साथ में (पहली बंद नहीं), फिर 1.5s बाद #3 - एक साथ ज़्यादा से ज़्यादा 3
 *  - जो पहले setup पूरा करे वही जीती, बाकी तुरंत बंद
 *  - 401/403/429/quota/गलत key = वह key 10 मिनट तक नहीं; timeout/connection की गलती = वह जोड़ी 2 मिनट तक नहीं
 *  - एक ही नाकाम जोड़ी बार-बार नहीं
 * key कभी log/स्क्रीन पर नहीं - सिर्फ़ उसका नंबर (#1, #2 ...).
 */
object LiveRace {
    const val STAGGER_MS = 1_500L
    const val MAX_PARALLEL = 3
    /** इतनी देर में कोई जोड़ी न जुड़े = धीमा नेट - हल्का मोड (Live पीछे से जुड़ता रहता है) */
    const val LIGHT_AFTER_MS = 6_000L
    const val KEY_BAD_MS = 10 * 60_000L
    const val COMBO_FAIL_MS = 2 * 60_000L
    const val MIN_TIMEOUT_MS = 4_000L
    const val MAX_TIMEOUT_MS = 22_000L
    /** नाप नहीं है (पहली बार) */
    const val DEFAULT_TIMEOUT_MS = 6_000L
    /** हल्के मोड में Live को पीछे से फिर आज़माना (हर इतनी देर में एक दौड़) */
    const val BACKGROUND_EVERY_MS = 8_000L
    private const val KEEP_TIMES = 8

    data class Combo(val model: String, val keyIdx: Int) {
        val id: String get() = "$keyIdx|$model"
        /** log/स्क्रीन के लिए - key नहीं, सिर्फ़ नंबर */
        val label: String get() = "$model • key #${keyIdx + 1}"
    }

    enum class Fail { KEY, CONFIG, MODEL, NET }

    /** गलती किस तरह की: key की (10 मिनट), setup की बनावट की (उसे ठीक करके फिर), मॉडल नहीं, या नेट/timeout (2 मिनट) */
    fun classify(code: Int, reason: String): Fail {
        val r = reason.lowercase()
        if (code == 401 || code == 403 || code == 429 || isQuota(code, r) ||
            r.contains("api key") || r.contains("api_key") || r.contains("permission") || r.contains("unauthenticated") ||
            r.contains("billing")) return Fail.KEY
        if (r.contains("not found") || code == 404 || code == 1008 || (r.contains("model") && r.contains("not supported"))) return Fail.MODEL
        if (code == 1007 || r.contains("modalit") || r.contains("search") || r.contains("tool") || r.contains("voice") ||
            r.contains("language") || r.contains("affective") || r.contains("think") || r.contains("handle") ||
            r.contains("resum") || r.contains("invalid")) return Fail.CONFIG
        return Fail.NET
    }

    fun isQuota(code: Int, reason: String): Boolean {
        val r = reason.lowercase()
        return code == 429 || r.contains("quota") || r.contains("exhausted") || r.contains("rate limit") || r.contains("rate_limit")
    }

    /** extended-thinking वाले मॉडल धीमे - सबसे पीछे (तेज़, बिना लंबी सोच वाला पहले) */
    fun fastFirst(models: List<String>): List<String> {
        val (slow, fast) = models.distinct().partition { it.contains("thinking") }
        return fast + slow
    }

    /** setup का इंतज़ार: अच्छे नेट पर ~4s, धीमे पर 22s तक (हाल के जुड़ने के समय से) */
    fun timeoutMs(recent: List<Long>): Long {
        val ok = recent.filter { it > 0 }
        if (ok.isEmpty()) return DEFAULT_TIMEOUT_MS
        val s = ok.sorted()
        val median = s[s.size / 2]
        return (median * 3).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }

    /** नई जोड़ी अभी शुरू करें? (पहली तुरंत; फिर 1.5s बाद; पिछली नाकाम = तुरंत; 3 से ज़्यादा साथ नहीं) */
    fun shouldLaunch(inFlight: Int, queued: Int, now: Long, lastLaunchAt: Long): Boolean =
        queued > 0 && inFlight < MAX_PARALLEL && (inFlight == 0 || now - lastLaunchAt >= STAGGER_MS)

    /** जुड़ने के बाद भेजी जाने वाली याद (setup में नहीं - setup छोटा रहे) */
    fun contextNote(t: String): String =
        "SYSTEM CONTEXT (background memory for this call - do not reply to this message, just use it when relevant):\n" + t.trim()

    /** हर key / जोड़ी की हालत (फ़ोन में save होती है) */
    class Health {
        private val keyBadUntil = HashMap<Int, Long>()
        private val comboFailUntil = HashMap<String, Long>()
        private val lastOk = HashMap<String, Long>()
        private val times = ArrayDeque<Long>()
        var lastWin: String = ""
            private set

        @Synchronized fun keyBad(k: Int, now: Long) { keyBadUntil[k] = now + KEY_BAD_MS }
        @Synchronized fun isKeyBad(k: Int, now: Long): Boolean = (keyBadUntil[k] ?: 0L) > now
        @Synchronized fun comboFail(c: Combo, now: Long) { comboFailUntil[c.id] = now + COMBO_FAIL_MS }
        @Synchronized fun isComboFailed(c: Combo, now: Long): Boolean = (comboFailUntil[c.id] ?: 0L) > now
        /** नेट वापस आया: नेट की वजह से रुकी जोड़ियाँ फिर आज़माओ (गलत key वाली नहीं) */
        @Synchronized fun clearComboFails() { comboFailUntil.clear() }
        @Synchronized fun ok(c: Combo, ms: Long, now: Long) {
            lastWin = c.id; lastOk[c.id] = now; comboFailUntil.remove(c.id); keyBadUntil.remove(c.keyIdx)
            time(ms)
        }
        @Synchronized fun time(ms: Long) { if (ms > 0) { times.addLast(ms); while (times.size > KEEP_TIMES) times.removeFirst() } }
        @Synchronized fun recentTimes(): List<Long> = times.toList()
        @Synchronized fun lastOkAt(c: Combo): Long = lastOk[c.id] ?: 0L

        /** जोड़ियों का क्रम: पिछली जीती पहले, फिर हाल में जुड़ी (नई पहले), फिर बाकी अपने क्रम में. रुकी हुई (key/जोड़ी/limit) बाहर */
        @Synchronized fun order(models: List<String>, keyCount: Int, now: Long, blocked: (Combo) -> Boolean = { false }): List<Combo> {
            val base = ArrayList<Combo>()
            for (m in fastFirst(models)) for (k in 0 until keyCount) base.add(Combo(m, k))
            val usable = base.filter { !isKeyBad(it.keyIdx, now) && !isComboFailed(it, now) && !blocked(it) }
            val idx = usable.withIndex().associate { it.value.id to it.index }
            return usable.sortedWith(compareBy<Combo>({ if (it.id == lastWin) 0 else 1 }, { -(lastOk[it.id] ?: 0L) }, { idx[it.id] ?: 0 }))
        }

        @Synchronized fun encode(): String = buildString {
            append("w\t").append(lastWin).append('\n')
            keyBadUntil.forEach { (k, v) -> append("k\t").append(k).append('\t').append(v).append('\n') }
            comboFailUntil.forEach { (k, v) -> append("c\t").append(k).append('\t').append(v).append('\n') }
            lastOk.forEach { (k, v) -> append("o\t").append(k).append('\t').append(v).append('\n') }
            times.forEach { append("t\t").append(it).append('\n') }
        }

        companion object {
            fun decode(s: String?): Health {
                val h = Health()
                if (s.isNullOrBlank()) return h
                for (line in s.split('\n')) {
                    val p = line.split('\t')
                    try {
                        when (p[0]) {
                            "w" -> if (p.size > 1) h.lastWin = p[1]
                            "k" -> h.keyBadUntil[p[1].toInt()] = p[2].toLong()
                            "c" -> h.comboFailUntil[p[1]] = p[2].toLong()
                            "o" -> h.lastOk[p[1]] = p[2].toLong()
                            "t" -> h.time(p[1].toLong())
                        }
                    } catch (_: Exception) {}
                }
                return h
            }
        }
    }

    /**
     * v5.1: जवाब की आवाज़ के लिए छोटा jitter buffer. जवाब की शुरुआत तुरंत बजती है (कोई देरी नहीं);
     * जवाब के बीच नेट रुकने से आवाज़ खत्म हो जाए (underrun) तो ~250ms जमा होने तक रुककर फिर बजाओ -
     * टूटी-टूटी (अटकती) आवाज़ की जगह एक छोटा सा ठहराव.
     */
    object Jitter {
        const val TARGET_MS = 250
        const val MAX_WAIT_MS = 300L
        /** बीच जवाब में आवाज़ खत्म हो गई थी? (पहला टुकड़ा कभी नहीं रुकता) */
        fun underrun(playedThisTurn: Boolean, turnActive: Boolean, now: Long, playEnd: Long): Boolean =
            playedThisTurn && turnActive && playEnd > 0 && now > playEnd + 40
        /** और जमा करें? */
        fun keepFilling(bufferedMs: Int, waitedMs: Long): Boolean = bufferedMs < TARGET_MS && waitedMs < MAX_WAIT_MS
    }
}
