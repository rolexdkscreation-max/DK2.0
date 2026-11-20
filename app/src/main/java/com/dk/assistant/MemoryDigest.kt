package com.dk.assistant

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 18 नवंबर 2026 (मालिक: "सब बातें फ़ोन पर सेव रहें; चैट की बातें ज़्यादातर याद रहें; बाकी का सार -
 * जो याद रखने लायक है वही, जो DK को लगे कि हाँ यह याद रखना है").
 *
 *  - हर बात पहले जैसी [LocalMemory] (SQLite, सिर्फ़ फ़ोन में) में - कुछ नहीं बदला, कुछ नहीं मिटता.
 *  - चैट में हाल की 20 बातें सीधे AI को ([BrainManager.CHAT_TURNS]).
 *  - बाकी: हर ~20 नई बातों पर / बातचीत खत्म होने पर, DK (आपका चुना AI) नई बातें पढ़कर सिर्फ़ याद रखने लायक
 *    (पसंद, लोग, काम, तारीख, वादे, ज़रूरी जानकारी) छोटी हिंदी लाइनों में चुनता है -> "हमेशा वाले सार" में.
 *    गपशप, एक बार के हुक्म, पासवर्ड / बैंक नंबर नहीं. यूज़र का मिटाया सार दोबारा नहीं जुड़ता.
 *  - AI न चले / इंटरनेट नहीं = पहले वाला नियमों से बना सार चलता रहता है; बाद में फिर कोशिश.
 *  - सब पीछे वाले thread पर; अपनी keys ही (DK सर्वर की मुफ़्त सीमा नहीं खाता); बंद करने का switch याददाश्त स्क्रीन पर.
 */
object MemoryDigest {
    const val PREF_ON = "memory_digest_on"
    private const val PREF_LAST = "memory_digest_last_id"
    private const val PREF_AT = "memory_digest_at"

    /** इतनी नई बातें = अपने-आप सार */
    const val BATCH = 20
    /** इससे कम नई बातें = कुछ नहीं (बातचीत खत्म होने पर भी) */
    const val MIN_TURNS = 4
    /** दो सार के बीच कम से कम */
    const val MIN_GAP_MS = 3 * 60_000L
    /** बातचीत खत्म / ऐप खुला वाला सार - पिछले से इतनी देर बाद */
    const val FORCE_GAP_MS = 10 * 60_000L
    /** एक बार में AI को इतनी बातें / इतने अक्षर */
    const val LIMIT = 80
    const val MAX_CHARS = 7000
    /** एक बार में ज़्यादा से ज़्यादा नई यादें */
    const val MAX_ITEMS = 6
    /** पहली बार: पुरानी सारी बातें नहीं (उनका नियमों वाला सार बन चुका) - सिर्फ़ आख़िरी इतनी */
    const val FIRST_BACK = 60
    private const val MAX_ROUNDS = 3

    val CATS = listOf("पसंद", "लोग", "काम", "तारीख", "वादा/फ़ैसला", "जानकारी")

    val SYSTEM = """You are DK's memory keeper. DK is a personal Hindi voice assistant on the user's phone.
Read the conversation between the User and DK below and pick ONLY the things worth remembering for weeks or months:
the user's likes and dislikes, people in their life and relationships, their plans, important dates and events,
promises or decisions, ongoing work or projects, and other lasting personal information.
Skip greetings, small talk, jokes, one-time commands (open an app, set an alarm, play a song, call someone) unless
they reveal a lasting preference, general-knowledge questions, and anything DK said that is not about the user.
NEVER include passwords, PINs, OTPs, bank, card or Aadhaar numbers.
Do not repeat anything already listed under ALREADY REMEMBERED (same meaning = repeat).
Write each item as ONE short Hindi line (at most 25 words), starting with exactly one of these labels:
${CATS.joinToString(", ") { "$it:" }}
Reply with JSON only, no other text: {"keep":["...", "..."]} (at most $MAX_ITEMS items).
If nothing is worth remembering, reply {"keep":[]}."""

    fun on(c: Context) = Prefs.bool(c, PREF_ON, true)
    fun setOn(c: Context, v: Boolean) = Prefs.edit(c) { putBoolean(PREF_ON, v) }

    private val running = AtomicBoolean(false)
    private val counter = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ------------------------------------------------------------------ pure (tests)
    /** अभी सार बनाना है? [pending] = नई बातें, [sinceLastMs] = पिछले सार से समय */
    fun due(pending: Int, force: Boolean, sinceLastMs: Long): Boolean = when {
        pending < MIN_TURNS -> false
        sinceLastMs < MIN_GAP_MS -> false
        pending >= BATCH -> true
        else -> force && sinceLastMs >= FORCE_GAP_MS
    }

    /** पहली बार (कभी सार नहीं बना) = आख़िरी [FIRST_BACK] बातों से; वरना वहीं से जहाँ पिछली बार रुके */
    fun startFrom(saved: Long?, maxId: Long): Long = saved ?: (maxId - FIRST_BACK).coerceAtLeast(0L)

    /** AI को भेजने वाला text + इसमें आई आख़िरी बात की id (उसके बाद वाली अगली बार) */
    fun prompt(turns: List<LocalRecall.Turn>, known: List<String>, maxChars: Int = MAX_CHARS): Pair<String, Long> {
        val sb = StringBuilder()
        if (known.isNotEmpty()) {
            sb.append("ALREADY REMEMBERED:\n")
            known.takeLast(40).forEach { sb.append("- ").append(it.take(120)).append('\n') }
            sb.append('\n')
        }
        sb.append("CONVERSATION (oldest first):\n")
        var lastId = 0L
        for (t in turns) {
            val line = (if (t.who == "u") "User: " else "DK: ") + t.text.replace('\n', ' ').take(300) + "\n"
            if (lastId != 0L && sb.length + line.length > maxChars) break
            sb.append(line); lastId = t.id
        }
        return sb.toString() to lastId
    }

    private val LEAD = Regex("^[\\s\\-•*\\d.)]+")
    private val SPACES = Regex("\\s+")

    /** AI का जवाब -> साफ़ यादें. JSON ही न मिले = null (बाद में फिर कोशिश). पहले से / मिटाई हुई / निजी = नहीं */
    fun parse(raw: String, known: List<String>, dropped: Set<String>): List<String>? {
        val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        val arr = try { JSONObject(raw.substring(s, e + 1)).optJSONArray("keep") } catch (_: Exception) { null } ?: return null
        val seen = HashSet<String>().apply { known.forEach { add(LocalRecall.norm(it)) }; addAll(dropped) }
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            var t = arr.optString(i).replace(LEAD, "").replace(SPACES, " ").trim().trimEnd('।', '.').trim()
            if (t.length < 6 || t.length > 220 || Sensitive.isSensitive(t)) continue
            if (CATS.none { t.startsWith("$it:") }) t = "जानकारी: $t"
            if (!seen.add(LocalRecall.norm(t))) continue
            out.add(t)
            if (out.size >= MAX_ITEMS) break
        }
        return out
    }

    // ------------------------------------------------------------------ चलाना
    private fun allowed(c: Context) = on(c) && Prefs.localMemoryOn(c) &&
        !com.dk.assistant.plus.FeatureFlags.on(c, com.dk.assistant.plus.FeatureFlags.INCOGNITO)

    /** [LocalMemory.record] से - हर [BATCH] नई बातों पर एक बार */
    fun noteTurn(c: Context) {
        if (counter.incrementAndGet() < BATCH) return
        counter.set(0)
        kick(c, "$BATCH नई बातें", force = false)
    }

    /** पीछे से एक बार (पहले से चल रहा हो = कुछ नहीं). कभी crash नहीं */
    fun kick(c: Context, why: String, force: Boolean) {
        val ctx = c.applicationContext ?: return
        try { if (!allowed(ctx)) return } catch (e: Exception) { Quiet.note(e); return }
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            // 20 नवंबर 2026 (मालिक: आवाज़ बजते समय भारी काम टालो): ऑफ़लाइन आवाज़ बन/बज रही = चुप होने तक (ज़्यादा से ज़्यादा 60 s)
            val t0 = System.currentTimeMillis()
            try {
                while (!force && com.dk.assistant.voice.st3.St3Load.shouldDefer(System.currentTimeMillis(), System.currentTimeMillis() - t0, com.dk.assistant.voice.st3.St3Load.MEMORY_DEFER_MS)) {
                    kotlinx.coroutines.delay(500)
                }
                runOnce(ctx, why, force)
            } catch (e: Exception) { DKLog.d("DK की याद (सार) गड़बड़: ${e.message?.take(80)}") }
            finally { running.set(false) }
        }
    }

    private suspend fun runOnce(c: Context, why: String, force: Boolean) {
        val since = System.currentTimeMillis() - (Prefs.str(c, PREF_AT, "0").toLongOrNull() ?: 0L)
        var last = Prefs.str(c, PREF_LAST, "").toLongOrNull()
        for (round in 0 until MAX_ROUNDS) {
            if (last == null) {
                val probe = LocalMemory.digestInput(c, Long.MAX_VALUE, 1) ?: return
                last = startFrom(null, probe.lastId)
                Prefs.edit(c) { putString(PREF_LAST, last.toString()) }
            }
            val inp = LocalMemory.digestInput(c, last, LIMIT) ?: return
            if (inp.turns.isEmpty()) return
            if (round == 0 && !due(inp.pending, force, since)) return
            if (round > 0 && inp.pending < MIN_TURNS) return
            if (!NetProbe.online(c)) return
            val (text, upTo) = prompt(inp.turns, inp.known)
            val raw = BrainManager(c).quickText(SYSTEM, text, router = false)
            if (raw == null) { DKLog.d("DK की याद ($why): AI नहीं चला - बाद में फिर"); return }
            val items = parse(raw, inp.known, inp.dropped)
            if (items == null) { DKLog.d("DK की याद ($why): जवाब समझ नहीं आया - बाद में फिर"); return }
            val n = if (items.isEmpty()) 0 else LocalMemory.keepDigest(c, items)
            last = upTo
            Prefs.edit(c) { putString(PREF_LAST, upTo.toString()); putString(PREF_AT, System.currentTimeMillis().toString()) }
            DKLog.d("DK की याद ($why): ${inp.turns.count { it.id <= upTo }} बातें पढ़ीं, $n नई याद रखीं")
            if (inp.pending <= inp.turns.count { it.id <= upTo }) return
        }
    }
}
