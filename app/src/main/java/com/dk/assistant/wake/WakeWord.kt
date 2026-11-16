package com.dk.assistant.wake

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.PowerManager
import com.dk.assistant.DKLog
import com.dk.assistant.Prefs
import com.dk.assistant.voice.VoiceCmds
import com.dk.assistant.voice.VoiceGuardian
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 16 नवंबर 2026 (मालिक): नया वेक वर्ड - "हे डीके" और "हेलो डीके" (openWakeWord, फ़ोन पर ही).
 * पुराना Hey DK (DTW, खुद सिखाने वाला) और Google/Groq वाला "हमेशा सुनो" हटाए गए (backup: backup/dk-old-heydk-wake).
 *
 *  - वेक वर्ड से पहले: आवाज़ सिर्फ़ RAM में (2 s की अंगूठी), न सेव, न इंटरनेट. मॉडल सिर्फ़ तब चलता है जब कोई आवाज़ हो (VAD - बैटरी)
 *  - वेक वर्ड मिला: (सिर्फ़ मालिक मोड में उसी हिस्से से आवाज़ मिलान - साथ-साथ, शब्द पूरा होने से पहले शुरू) -> Live
 *  - कोई key / API नहीं माँगता. Live की key जैसी थी वैसी.
 */
object WakeWord {
    const val P_ON = "wake2_on"
    const val P_LISTEN = "wake2_listen"
    const val P_SENSE = "wake2_sense"
    const val P_OWNER = "wake2_owner_only"
    const val P_OWNER_TH = "wake2_owner_th"
    const val P_SILENCE = "wake2_silence_ms"
    const val P_FGS_BLOCK = "wake2_fgs_blocked"

    // ------------------------------------------------------------ सेटिंग (हर बदलाव तुरंत - हर ~1 s दोबारा पढ़ी जाती है)
    fun enabled(c: Context) = Prefs.bool(c, P_ON, false)
    fun listenMode(c: Context) = WakeRules.Listen.byId(Prefs.str(c, P_LISTEN, WakeRules.Listen.ALWAYS.id))
    fun sense(c: Context) = WakeRules.Sense.byId(Prefs.str(c, P_SENSE, WakeRules.Sense.MID.id))
    fun wordOn(c: Context, w: WakeRules.Word) = Prefs.bool(c, w.pref, true)
    fun words(c: Context) = WakeRules.Word.values().filter { wordOn(c, it) }
    fun ownerTh(c: Context) = WakeRules.clampOwner(Prefs.long(c, P_OWNER_TH, (WakeRules.OWNER_TH_DEFAULT * 100).toLong()) / 100f)
    fun silenceMs(c: Context) = Prefs.long(c, P_SILENCE, WakeRules.SILENCE_DEFAULT_MS.toLong()).toInt().coerceIn(3_000, 60_000)
    /** "सिर्फ़ मालिक" - voiceprint न हो तो चुना ही नहीं जा सकता (और चुना हुआ भी नहीं माना जाता) */
    fun ownerReady(c: Context) = WakeRules.ownerSelectable(VoiceGuardian.enrolled(c), VoiceGuardian.hasModel(c)) && VoiceGuardian.supported
    fun ownerOnly(c: Context) = Prefs.bool(c, P_OWNER, false) && ownerReady(c)

    /** मुख्य स्विच चालू + मोड "सिर्फ़ बटन" नहीं (मॉडल हों या न हों - न हों तो loop डाउनलोड का इंतज़ार करता है, माइक बंद) */
    fun on(c: Context) = enabled(c) && listenMode(c) != WakeRules.Listen.BUTTON

    /** अभी माइक खोलकर सुनें? */
    fun active(c: Context) = on(c) && WakeModels.ready(c) && words(c).isNotEmpty() && allowedNow(c)

    fun screenOn(c: Context) = try { c.getSystemService(PowerManager::class.java)?.isInteractive != false } catch (_: Exception) { true }
    fun charging(c: Context) = try { c.getSystemService(BatteryManager::class.java)?.isCharging == true } catch (_: Exception) { false }
    fun allowedNow(c: Context) = WakeRules.allowedNow(listenMode(c), screenOn(c), charging(c))
    fun locked(c: Context) = try { c.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true } catch (_: Exception) { false }

    /** पुराने वेक वर्ड (Hey DK / हमेशा सुनो) चालू थे = नया भी चालू (एक बार) */
    fun migrate(c: Context) {
        if (Prefs.bool(c, "wake2_migrated", false)) return
        val was = Prefs.bool(c, "hey_dk_on", false) || Prefs.wakeMode(c)
        Prefs.edit(c) { putBoolean("wake2_migrated", true); if (was) putBoolean(P_ON, true) }
        if (was) DKLog.d("वेक वर्ड: पुराना Hey DK चालू था - नया \"हे डीके / हेलो डीके\" भी चालू (मॉडल डाउनलोड होते ही)")
    }

    const val P_HOTWORD = "wake2_hotword"
    fun setHotwordState(c: Context, st: String) { Prefs.edit(c) { putString(P_HOTWORD, st) }; DKLog.d("Android का अपना hotword: $st") }
    fun hotwordState(c: Context) = Prefs.str(c, P_HOTWORD, "")

    // ------------------------------------------------------------ इंजन
    @Volatile private var engine: OwwEngine? = null
    @Volatile private var engineKey = ""
    private val ownerExec = Executors.newSingleThreadExecutor { r -> Thread(r, "dk-wake-owner").apply { isDaemon = true } }

    private fun engineFor(c: Context, ws: List<WakeRules.Word>): OwwEngine? {
        val key = WakeModels.version(c).toString() + ":" + ws.joinToString { it.id }
        engine?.takeIf { engineKey == key }?.let { return it }
        try { engine?.close() } catch (_: Exception) {}
        engine = null
        val e = OwwEngine.open(WakeModels.melFile(c), WakeModels.embFile(c), ws.associate { it.id to WakeModels.headFile(c, it) })
        if (e == null) DKLog.d("वेक वर्ड: मॉडल नहीं खुला - ${OwwEngine.lastError}")
        engine = e; engineKey = key
        return e
    }

    fun closeEngine() { try { engine?.close() } catch (_: Exception) {}; engine = null; engineKey = "" }

    class Hit(
        val word: WakeRules.Word, val score: Float, val owner: Float?, val latencyMs: Long, val ownerMs: Long?,
        val locked: Boolean, val cmd: VoiceCmds.Hit? = null,
    )

    /** "जागने की जाँच" चल रही = पीछे वाला सुनना माइक छोड़ दे */
    @Volatile var testing = false
    @Volatile var lastHit: Hit? = null; private set
    @Volatile var lastHitAt = 0L; private set
    @Volatile var falseIgnored = 0; private set
    /** यह Live सत्र वेक वर्ड से, फ़ोन लॉक होते हुए शुरू हुआ - निजी काम (मैसेज पढ़ना वगैरह) अनलॉक के बाद ही */
    @Volatile var sessionLocked = false

    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(WakeRules.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        for (src in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            try {
                val r = AudioRecord(src, WakeRules.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, WakeRules.STEP * 2 * 4))
                if (r.state == AudioRecord.STATE_INITIALIZED) return r
                r.release()
            } catch (_: Exception) {}
        }
        return null
    }

    private fun pcm(chunks: List<ShortArray>): ByteArray {
        val out = ByteArrayOutputStream(chunks.size * WakeRules.STEP * 2)
        for (ch in chunks) for (s in ch) { out.write(s.toInt() and 0xff); out.write(s.toInt() shr 8 and 0xff) }
        return out.toByteArray()
    }

    /**
     * वेक वर्ड का इंतज़ार. null = रुकने को कहा ([stop]) / मोड बदला / माइक नहीं.
     * [handoff] = true: मिलने पर माइक बंद नहीं, [WakeHandoff] को (Live तक बिना कटे). जाँच वाले में false.
     */
    suspend fun listen(c: Context, handoff: Boolean = true, stop: () -> Boolean): Hit? = withContext(Dispatchers.IO) {
        val ws = words(c)
        if (ws.isEmpty()) return@withContext null
        val eng = engineFor(c, ws) ?: return@withContext null
        val r = openMic() ?: run { DKLog.d("वेक वर्ड: माइक नहीं खुला (कोई और ऐप माइक ले रहा है?)"); return@withContext null }
        var handed = false
        try {
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@withContext null
            val st = eng.stream; st.reset()
            val gate = WakeRules.Gate(); val agc = WakeRules.Agc(); val dec = WakeRules.Decider()
            val ring = ArrayDeque<ShortArray>()
            val seg = ArrayList<ShortArray>()               // आवाज़-कमांड (लॉक / किधर हो) के लिए, सिर्फ़ RAM, gate बंद होते ही मिटा
            val cmdsOn = VoiceCmds.anyOn(c)
            var cfgAt = 0L; var sense = sense(c); var ownerOn = ownerOnly(c); var ownerTh = ownerTh(c)
            var lastLoudAt = 0L
            var early: Future<Float?>? = null; var earlyAt = 0L
            val buf = ShortArray(WakeRules.STEP)
            DKLog.d("वेक वर्ड: सुन रहा हूँ (${ws.joinToString { it.hi }}) • संवेदनशीलता ${sense.hi} • " +
                (if (ownerOn) "सिर्फ़ मालिक" else "सबकी आवाज़") + " • ${listenMode(c).hi}")
            while (true) {
                val now = System.currentTimeMillis()
                if (stop() || testing && handoff) return@withContext null
                if (now - cfgAt > 1_000) {
                    cfgAt = now
                    if ((handoff && (!on(c) || !allowedNow(c))) || words(c) != ws) return@withContext null   // जाँच (handoff=false) स्विच बंद हो तब भी
                    sense = sense(c); ownerOn = ownerOnly(c); ownerTh = ownerTh(c)
                }
                var got = 0
                while (got < WakeRules.STEP) { val n = r.read(buf, got, WakeRules.STEP - got); if (n < 0) return@withContext null; got += n }
                val raw = buf.copyOf()
                ring.addLast(raw); while (ring.size > WakeRules.RING_STEPS) ring.removeFirst()
                val lv = WakeRules.rms(raw)
                val opened = gate.feed(lv)
                if (lv > gate.floor * 2.2) lastLoudAt = now
                if (!gate.open) {
                    // चुप्पी: मॉडल नहीं चलता (बैटरी). अभी-अभी बंद हुआ हो तो रुकी बात से आवाज़-कमांड
                    if (seg.isNotEmpty()) {
                        val whole = pcm(seg); seg.clear()
                        if (cmdsOn) VoiceCmds.match(c, whole)?.let { ch ->
                            return@withContext Hit(ws.first(), 0f, null, 0, null, locked(c), cmd = ch)
                        }
                    }
                    agc.reset(); continue
                }
                if (seg.size < 90) seg.add(raw)              // ~7 s तक
                // हर score वाले कदम पर फ़ैसला (Decider लगातार कदम गिनता है)
                var best: WakeRules.Word? = null; var sc = 0f; var fired = false
                fun judge(scores: Map<String, Float>) {
                    if (scores.isEmpty() || fired) return
                    val b = ws.maxByOrNull { scores[it.id] ?: 0f } ?: return
                    best = b; sc = scores[b.id] ?: 0f
                    if (dec.hit(sc, sense.th, now, sense.need)) fired = true
                }
                if (opened) {
                    // अभी-अभी आवाज़ शुरू: पिछले 2 s से भरो (शब्द का शुरू का हिस्सा न कटे)
                    st.reset(); seg.clear(); seg.addAll(ring)
                    val all = ring.toList()
                    for ((i, ch) in all.withIndex()) { val x = ch.copyOf(); agc.apply(x, true); judge(st.feed(x, score = i >= all.size - 4)) }
                } else {
                    val x = raw.copyOf(); agc.apply(x, true); judge(st.feed(x))
                }
                val word = best ?: continue
                // मालिक-जाँच पहले से (शब्द पूरा होने का इंतज़ार नहीं) - वेक वर्ड और आवाज़-मिलान साथ-साथ
                if (ownerOn && early == null && WakeRules.earlyOwner(sc, sense.th)) {
                    val snap = pcm(ring.toList().takeLast(18)); earlyAt = now
                    early = ownerExec.submit<Float?> { try { VoiceGuardian.score(c, snap) } catch (_: Throwable) { null } }
                }
                if (early != null && now - earlyAt > 2_500) early = null
                if (!fired) continue
                val lat = WakeRules.latencyMs(now, lastLoudAt)
                var owner: Float? = null; var ownerMs: Long? = null
                if (ownerOn) {
                    val t0 = System.currentTimeMillis()
                    val full = pcm(ring.toList().takeLast(18))
                    val fin = ownerExec.submit<Float?> { try { VoiceGuardian.score(c, full) } catch (_: Throwable) { null } }
                    val a = try { early?.get(400, TimeUnit.MILLISECONDS) } catch (_: Exception) { null }
                    val b = try { fin.get(600, TimeUnit.MILLISECONDS) } catch (_: Exception) { null }
                    owner = b ?: a
                    ownerMs = System.currentTimeMillis() - t0
                    early = null
                    if (!WakeRules.ownerOk(owner, ownerTh)) {
                        falseIgnored++
                        DKLog.d("वेक वर्ड \"${word.hi}\" सुना, पर आवाज़ मालिक की नहीं (मिलान ${owner?.let { "%.2f".format(it) } ?: "-"} < ${"%.2f".format(ownerTh)}) - चुपचाप अनदेखा")
                        continue
                    }
                }
                val hit = Hit(word, sc, owner, lat, ownerMs, locked(c))
                lastHit = hit; lastHitAt = now
                DKLog.d("वेक वर्ड: \"${word.hi}\" (score ${"%.2f".format(sc)}, ${lat} ms" + (owner?.let { ", मालिक ${"%.2f".format(it)}" } ?: "") +
                    (if (hit.locked) ", फ़ोन लॉक" else "") + ")")
                if (handoff) {
                    // माइक बंद नहीं - Live खुलने तक सिर्फ़ पहचान के *बाद* की आवाज़ जमा (वेक वर्ड और उससे पहले का कुछ नहीं जाता)
                    WakeHandoff.begin(r, null); handed = true
                }
                ring.clear(); seg.clear()
                return@withContext hit
            }
            null
        } catch (e: Exception) {
            DKLog.d("वेक वर्ड: गलती ${e.javaClass.simpleName} ${e.message}"); null
        } finally {
            if (!handed) { try { r.stop() } catch (_: Exception) {}; try { r.release() } catch (_: Exception) {} }
        }
    }
}
