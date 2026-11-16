package com.dk.assistant.wake

import android.media.AudioRecord
import com.dk.assistant.DKLog

/**
 * 16 नवंबर 2026 (मालिक: "वेक वर्ड के बाद की आवाज़ बिना कटे Live को, वेक वर्ड वाला माइक पहले छोड़ो, फिर Live"):
 *
 *  1. वेक वर्ड पहचाना -> वही माइक (वेक वर्ड वाला) चलता रहता है, आगे की आवाज़ इस कतार में (सिर्फ़ RAM, अधिकतम 6 s)
 *  2. Live का माइक खुलने से ठीक पहले [releaseMic] - हमारा माइक बंद (एक समय पर एक ही माइक)
 *  3. Live का माइक-loop पहले यह कतार भेजता है ([next]), फिर अपना माइक - इसलिए "हे डीके, व्हाट्सऐप खोलो" का "व्हाट्सऐप" नहीं कटता
 *
 * वेक वर्ड से पहले की आवाज़ यहाँ कभी नहीं आती. 6 s में Live न खुले = सब मिटा, माइक बंद.
 */
object WakeHandoff {
    /** Live के माइक-loop वाला टुकड़ा: 40 ms = 640 sample */
    const val LIVE_CHUNK = 640
    private val q = ArrayDeque<ShortArray>()
    private val lock = Object()
    @Volatile private var rec: AudioRecord? = null
    @Volatile private var th: Thread? = null
    @Volatile private var startedAt = 0L
    @Volatile var lastHandoffMs = 0L; private set

    val active: Boolean get() = rec != null

    /** [tail] = पहचान के समय तक का शब्द के बाद वाला छोटा हिस्सा (Live को सबसे पहले) */
    fun begin(r: AudioRecord, tail: ShortArray?) {
        releaseMic(); clear()
        startedAt = System.currentTimeMillis()
        tail?.let { put(it) }
        rec = r
        th = Thread({
            val buf = ShortArray(LIVE_CHUNK)
            try {
                while (rec === r) {
                    val n = r.read(buf, 0, LIVE_CHUNK)
                    if (n <= 0) { if (n < 0) break else continue }
                    put(buf.copyOf(n))
                    if (System.currentTimeMillis() - startedAt > WakeRules.HANDOFF_MAX_MS) {
                        DKLog.d("वेक वर्ड: Live ${WakeRules.HANDOFF_MAX_MS / 1000}s में नहीं खुला - बाद की आवाज़ मिटाई, माइक बंद")
                        clear(); stopRec(r); break
                    }
                }
            } catch (_: Exception) {}
        }, "dk-wake-handoff").apply { isDaemon = true; start() }
    }

    private fun put(x: ShortArray) {
        synchronized(lock) {
            // 640 के टुकड़ों में (Live loop यही लेता है)
            var i = 0
            while (i < x.size) {
                val n = minOf(LIVE_CHUNK, x.size - i)
                val c = ShortArray(LIVE_CHUNK); System.arraycopy(x, i, c, 0, n)
                q.addLast(c); i += n
            }
            while (q.size > WakeRules.HANDOFF_MAX_MS / 40) q.removeFirst()
        }
    }

    private fun stopRec(r: AudioRecord) {
        if (rec === r) rec = null
        try { r.stop() } catch (_: Exception) {}
        try { r.release() } catch (_: Exception) {}
    }

    /** Live का माइक खोलने से ठीक पहले: हमारा माइक छोड़ो (thread रुकने तक इंतज़ार, 300 ms तक) */
    fun releaseMic() {
        val r = rec ?: return
        rec = null
        try { th?.join(300) } catch (_: Exception) {}
        th = null
        stopRec(r)
        lastHandoffMs = System.currentTimeMillis() - startedAt
        DKLog.d("वेक वर्ड: माइक Live को सौंपा (${lastHandoffMs} ms बाद, ${size()} टुकड़े आवाज़ साथ)")
    }

    /** Live loop: अगला जमा टुकड़ा (कोई नहीं = null). पुरानी (6 s से ज़्यादा) कतार कभी नहीं भेजी जाती */
    fun next(): ShortArray? = synchronized(lock) {
        if (q.isNotEmpty() && rec == null && System.currentTimeMillis() - startedAt > WakeRules.HANDOFF_MAX_MS + 2_000) q.clear()
        q.removeFirstOrNull()
    }

    fun size(): Int = synchronized(lock) { q.size }
    fun clear() = synchronized(lock) { q.clear() }
}
