package com.dk.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.dk.assistant.DKLog
import com.dk.assistant.Prefs
import com.dk.assistant.voice.engine.PreviewPlayer
import com.dk.assistant.voice.engine.VoiceEngines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.sqrt

/**
 * v3.81: "Hey DK" / "Hello DK" - ऐप बंद हो तब भी (KeepAlive + Accessibility), पूरी तरह फ़ोन के अंदर.
 *
 *  - माइक की आवाज़ कहीं नहीं जाती: बोलना शुरू -> छोटी बात -> [WakeMatch] (शब्द) + [OwnerVoice] (आवाज़) -> नहीं मिला = भूल जाओ.
 *  - मिला = तुरंत "हाँ सर" (पहले से बना रखा, इंटरनेट का इंतज़ार नहीं) -> Gemini Live -> चुप्पी पर वापस सो जाओ.
 *  - "Hey DK, WhatsApp खोलो" एक साँस में = बिना "हाँ सर" के सीधे वही काम.
 */
object HeyDk {
    private const val RATE = WakeMatch.RATE
    private const val CHUNK_MS = 40

    fun modelFile(c: Context) = File(File(c.filesDir, "voice"), "hey_dk.json")

    @Volatile private var cached: WakeMatch.Model? = null
    @Volatile private var cachedAt = -1L

    fun model(c: Context): WakeMatch.Model? {
        val f = modelFile(c)
        if (!f.exists()) { cached = null; cachedAt = -1L; return null }
        if (cached != null && cachedAt == f.lastModified()) return cached
        return WakeMatch.Model.fromJson(try { f.readText() } catch (_: Exception) { null }).also { cached = it; cachedAt = f.lastModified() }
    }

    fun trained(c: Context) = model(c) != null
    /** चालू + सीखा हुआ */
    fun on(c: Context) = Prefs.heyDkOn(c) && trained(c)
    /** आवाज़-पहचान भी लगेगी? (नहीं = सिर्फ़ शब्द मिलान, जो आपकी आवाज़ के नमूनों से ही बना है) */
    fun ownerCheck(c: Context) = OwnerVoice.supported && OwnerVoice.hasModel(c) && Prefs.heyDkPrint(c) != null && OwnerVoice.broken == null

    fun status(c: Context): String = when {
        !trained(c) && Prefs.wakeMode(c) -> "बिना सिखाए वाला तरीका चालू: \"हे डीके\" / \"हेलो डीके\" बोलिए (इंटरनेट से पहचान, हर आवाज़ पर)"
        !trained(c) -> "अभी सिखाया नहीं - नीचे \"Hey DK सिखाओ\" दबाइए"
        !Prefs.heyDkOn(c) -> "सीखा हुआ है, पर बंद है"
        ownerCheck(c) -> "चालू: \"Hey DK\" + सिर्फ़ आपकी आवाज़ (फ़ोन के अंदर)"
        else -> "चालू: \"Hey DK\" (आवाज़-पहचान मॉडल नहीं - दूसरे की आवाज़ पर भी कभी जाग सकता है)"
    }

    // ------------------------------------------------------------------ सीखना

    /**
     * कौन-सा माइक-रास्ता: पहले VOICE_RECOGNITION (कम बदलाव), पर कई फ़ोन पर वह बहुत धीमा/खाली आता है
     * (सिखाते समय "आवाज़ नहीं पकड़ी") -> तब VOICE_COMMUNICATION (Live वाला). जो सीखते समय चला, सुनते समय वही.
     */
    fun source(c: Context) = Prefs.heyDkSource(c)

    class Take(val pcm: ByteArray, val speechMs: Int, val peak: Double, val noise: Double)

    /** पिछली रिकॉर्डिंग का हाल (सिखाते समय दिखाने को): शोर / सबसे तेज़ */
    @Volatile var lastInfo = ""
        private set

    /**
     * एक बार "Hey DK" रिकॉर्ड: बोलना शुरू होने का 7 s इंतज़ार, फिर 0.5 s चुप्पी या 3.5 s पर बंद.
     * @param onLevel 0..1 मीटर (IO thread से)
     */
    @SuppressLint("MissingPermission")
    suspend fun recordTake(source: Int, maxMs: Int = 3_500, endMs: Int = 500, onLevel: (Float) -> Unit): Take? = withContext(Dispatchers.IO) {
        val chunk = RATE * CHUNK_MS / 1000
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, RATE * 2))
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (_: Exception) { null }
        if (r == null) { lastInfo = "माइक नहीं खुला"; return@withContext null }
        val vad = WakeMatch.Vad(CHUNK_MS, endMs = endMs)       // आवाज़-कमांड: "हे डीके, ..." के बीच रुकना भी चले (900)
        val pre = ArrayDeque<ByteArray>(); val out = ByteArrayOutputStream()
        val buf = ShortArray(chunk); val bytes = ByteArray(chunk * 2)
        var started = false; var ms = 0; var peak = 0.0
        try {
            r.startRecording()
            while (isActive) {
                val n = r.read(buf, 0, chunk)
                if (n <= 0) { Thread.sleep(10); continue }
                var sum = 0.0
                for (i in 0 until n) { val v = buf[i].toInt(); sum += (v * v).toDouble(); bytes[i * 2] = (v and 0xff).toByte(); bytes[i * 2 + 1] = (v shr 8 and 0xff).toByte() }
                val lv = sqrt(sum / n)
                peak = maxOf(peak, lv); ms += CHUNK_MS
                onLevel((lv / maxOf(vad.startThr() * 3, 600.0)).toFloat().coerceIn(0f, 1f))
                val ev = vad.feed(lv)
                val piece = bytes.copyOf(n * 2)
                if (!started) {
                    pre.addLast(piece); while (pre.size > 8) pre.removeFirst()
                    if (ev == WakeMatch.Vad.Ev.START) { started = true; pre.forEach { out.write(it) }; pre.clear() }
                    else if (ms > 7_000) break
                    continue
                }
                out.write(piece)
                if (ev == WakeMatch.Vad.Ev.END || out.size() >= RATE * 2 * maxMs / 1000) break
            }
        } finally {
            try { r.stop() } catch (_: Exception) {}
            r.release()
        }
        lastInfo = "शोर ${vad.noise.toInt()}, सबसे तेज़ ${peak.toInt()}"
        DKLog.d("Hey DK नमूना: ${if (started) "${out.size() / 32} ms" else "कोई नहीं बोला"} ($lastInfo, माइक $source)")
        if (!started) null else Take(out.toByteArray(), vad.speechMs, peak, vad.noise)
    }

    /** रिकॉर्ड में से सिर्फ़ बोला हुआ हिस्सा कितने फ़्रेम (10 ms) - VAD की गिनती पर भरोसा नहीं (शोर में लंबी/छोटी गिनती) */
    fun spokenFrames(pcm: ByteArray): Int = featuresOf(pcm).size

    fun featuresOf(pcm: ByteArray): Array<FloatArray> = WakeMatch.features(SpeakerFbank.pcm16(pcm))

    /**
     * नमूने सेव. @return null = ठीक, वरना हिंदी कारण
     * आवाज़ (PCM) सेव नहीं होती - सिर्फ़ अंक (MFCC + पहचान).
     */
    fun save(c: Context, takes: List<ByteArray>, source: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION): String? {
        val feats = takes.map { featuresOf(it) }.filter { it.size >= 15 }
        if (feats.size < 3) return "आवाज़ साफ़ नहीं आई - शांत जगह पर दोबारा सिखाइए"
        val m = WakeMatch.Model.build(feats) ?: return "नमूने आपस में नहीं मिले - हर बार एक ही तरह \"Hey DK\" बोलिए"
        modelFile(c).parentFile?.mkdirs()
        modelFile(c).writeText(m.toJson())
        cached = null
        val prints = if (OwnerVoice.supported && OwnerVoice.hasModel(c)) takes.mapNotNull { OwnerVoice.embed(c, it) } else emptyList()
        val print = VoicePrint.mean(prints)
        Prefs.edit(c) {
            if (print != null) putString("hey_dk_print", VoicePrint.encode(print)) else remove("hey_dk_print")
            putBoolean("hey_dk_on", true)
            putInt("hey_dk_src", source)
            putBoolean("wake_mode", true)       // सुनने वाला loop चले
        }
        DKLog.d("Hey DK सीखा: ${feats.size} नमूने, सीमा ${"%.2f".format(m.base)}, आवाज़-पहचान ${if (print != null) "हाँ" else "नहीं"}")
        return null
    }

    fun forget(c: Context) {
        modelFile(c).delete(); cached = null
        File(c.filesDir, "voice/ack").deleteRecursively()
        Prefs.edit(c) { remove("hey_dk_print"); putBoolean("hey_dk_on", false) }
    }

    // ------------------------------------------------------------------ सुनना

    /** @param rest "Hey DK" के साथ एक साँस में बोली बाकी बात (पूरी आवाज़, शुरू के "Hey DK" समेत) - null = सिर्फ़ "Hey DK" */
    class Hit(val distance: Float, val owner: Float?, val rest: ByteArray?, val cmd: VoiceCmds.Hit? = null)

    /** दो जागने के बीच कम से कम (एक ही बात दो बार न पकड़े) */
    @Volatile private var lastHitAt = 0L

    /**
     * "Hey DK" का इंतज़ार. [stop] true होते ही (Live चालू / म्यूट / call / बंद) माइक छोड़कर null.
     * मालिक की आवाज़ में "Hey DK" = [Hit].
     */
    @SuppressLint("MissingPermission")
    suspend fun listen(c: Context, stop: () -> Boolean): Hit? = withContext(Dispatchers.IO) {
        val model = model(c) ?: return@withContext null
        val chunk = RATE * CHUNK_MS / 1000
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(source(c), RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, RATE * 2)).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (_: Exception) { null } ?: return@withContext null
        val det = WakeMatch.Vad(CHUNK_MS, endMs = 350)
        val pre = ArrayDeque<ByteArray>()
        val seg = ByteArrayOutputStream()
        // बात चलती रहे तो शुरू के कुछ कटों पर जाँचो (एक साँस में "Hey DK, ..." भी पकड़ में आए)
        val cuts = model.prefixCuts().map { it * 2 }
        val maxSeg = RATE * 2 * WakeMatch.MAX_MS / 1000 + 8 * chunk * 2
        var inSeg = false; var preBytes = 0; var cutIdx = 0; var checkedUpTo = 0
        val buf = ShortArray(chunk); val bytes = ByteArray(chunk * 2)
        val sense = Prefs.heyDkSense(c)
        // नवंबर 2026: बिना इंटरनेट वाली आवाज़-कमांड (लॉक / अनलॉक / किधर हो) - "Hey DK" के साथ एक साँस में, या रुककर
        val cmdsOn = VoiceCmds.anyOn(c)
        var prevSeg: ByteArray? = null; var prevEndAt = 0L
        try {
            r.startRecording()
            while (isActive && !stop()) {
                val n = r.read(buf, 0, chunk)
                if (n <= 0) { Thread.sleep(20); continue }
                var sum = 0.0
                for (i in 0 until n) { val s = buf[i].toInt(); sum += (s * s).toDouble(); bytes[i * 2] = (s and 0xff).toByte(); bytes[i * 2 + 1] = (s shr 8 and 0xff).toByte() }
                val ev = det.feed(sqrt(sum / n))
                val piece = bytes.copyOf(n * 2)
                if (!inSeg) {
                    pre.addLast(piece); while (pre.size > 8) pre.removeFirst()
                    if (ev == WakeMatch.Vad.Ev.START) {
                        inSeg = true; cutIdx = 0; checkedUpTo = 0; seg.reset(); pre.forEach { seg.write(it) }; preBytes = seg.size(); pre.clear()
                    }
                    continue
                }
                seg.write(piece)
                val ended = ev == WakeMatch.Vad.Ev.END || !det.inSpeech
                if (ended && cmdsOn) {
                    val whole = seg.toByteArray()
                    val joined = prevSeg?.takeIf { System.currentTimeMillis() - prevEndAt < VoiceCmdMatch.JOIN_GAP_MS }?.let { it + whole }
                    val ch = VoiceCmds.match(c, whole) ?: joined?.let { VoiceCmds.match(c, it) }
                    if (ch != null) { lastHitAt = System.currentTimeMillis(); return@withContext Hit(0f, ch.owner, null, ch) }
                }
                var probe = -1
                if (cutIdx < cuts.size && seg.size() >= preBytes + cuts[cutIdx]) { probe = preBytes + cuts[cutIdx]; cutIdx++ }
                else if (ended && seg.size() > checkedUpTo && seg.size() <= maxSeg) probe = seg.size()
                if (probe > 0) {
                    checkedUpTo = probe
                    val hit = check(c, model, sense, seg.toByteArray().copyOf(probe))
                    if (hit != null && System.currentTimeMillis() - lastHitAt > 1500) {
                        lastHitAt = System.currentTimeMillis()
                        if (ended) return@withContext hit
                        // एक साँस में आगे की बात: पूरी होने तक सुनो (0.7 s चुप्पी / 8 s)
                        val rest = ByteArrayOutputStream(); rest.write(seg.toByteArray())
                        var quiet = 0; val t0 = System.currentTimeMillis()
                        while (isActive && quiet < 700 && System.currentTimeMillis() - t0 < 8_000) {
                            val k = r.read(buf, 0, chunk); if (k <= 0) continue
                            var s2 = 0.0
                            for (i in 0 until k) { val v = buf[i].toInt(); s2 += (v * v).toDouble(); bytes[i * 2] = (v and 0xff).toByte(); bytes[i * 2 + 1] = (v shr 8 and 0xff).toByte() }
                            rest.write(bytes, 0, k * 2)
                            det.feed(sqrt(s2 / k))
                            quiet = if (det.inSpeech) 0 else quiet + CHUNK_MS
                        }
                        // "Hey DK" के बाद बस थोड़ा-सा (साँस/हम्म) = सिर्फ़ "Hey DK"
                        val extra = rest.size() - probe
                        val all = rest.toByteArray()
                        val ch = if (cmdsOn && extra > RATE * 2 * 3 / 10) VoiceCmds.match(c, all) else null
                        return@withContext Hit(hit.distance, hit.owner, if (extra > RATE * 2 * 4 / 10) all else null, ch)
                    }
                }
                if (ended || seg.size() > maxSeg * 3) {
                    if (ended) { prevSeg = seg.toByteArray(); prevEndAt = System.currentTimeMillis() } else prevSeg = null
                    inSeg = false; seg.reset(); det.reset()
                }
            }
            null
        } finally {
            try { r.stop() } catch (_: Exception) {}
            r.release()
        }
    }

    /** शब्द + आवाज़ जाँच (माइक thread पर ही - छोटा काम, बोलने के बाद एक बार) */
    private fun check(c: Context, model: WakeMatch.Model, sense: WakeMatch.Sense, pcm: ByteArray): Hit? {
        val ms = pcm.size / 2 * 1000 / RATE
        if (ms < WakeMatch.MIN_MS) return null
        val f = featuresOf(pcm)
        if (f.size < 15) return null
        val (d1, d2) = model.distances(f)
        val th = model.threshold(sense)
        if (!(d1 <= th && d2 <= th * 1.15f)) return null
        var owner: Float? = null
        if (ownerCheck(c)) {
            val print = Prefs.heyDkPrint(c)
            val e = OwnerVoice.embed(c, pcm)
            if (print != null && e != null) {
                owner = VoicePrint.cosine(print, e)
                if (owner < VoicePrint.threshold(Prefs.ownerVoiceStrict(c))) {
                    DKLog.d("Hey DK जैसा सुना, पर आवाज़ आपकी नहीं (${"%.2f".format(owner)}) - नहीं जागा")
                    return null
                }
            }
        }
        DKLog.d("Hey DK सुना (दूरी ${"%.2f".format(d1)}/${"%.2f".format(th)}${owner?.let { ", आवाज़ %.2f".format(it) } ?: ""})")
        return Hit(d1, owner, null)
    }

    // ------------------------------------------------------------------ "हाँ सर" - तुरंत

    @Volatile private var lastAck = ""

    fun acks(c: Context) = WakeMatch.parseAcks(Prefs.heyDkAcks(c))

    fun nextAck(c: Context): String = WakeMatch.nextAck(acks(c), lastAck).also { lastAck = it }

    /** पहले से बनाकर फ़ोन में रखी आवाज़ें: "हाँ सर" वाले + "मैं इधर हूँ बॉस" (फ़ोन ढूँढना - तब इंटरनेट न हो तो भी) */
    fun clipTexts(c: Context): List<String> = (acks(c) + com.dk.assistant.FindPhone.SAY).distinct()

    /** बनी हुई आवाज़ (24 kHz PCM) - नहीं बनी = null */
    fun clipPcm(c: Context, text: String): ByteArray? = ackPick(c, text)?.let { try { it.readBytes() } catch (_: Exception) { null } }

    /** जो इंजन अकेले बोल सकता है (Live की अपनी आवाज़ अकेले नहीं बोलती -> Gemini TTS) */
    private fun ackEngine(c: Context) = listOfNotNull(VoiceEngines.active(c), VoiceEngines.byId(VoiceEngines.GEMINI_TTS))
        .firstOrNull { it.id != VoiceEngines.LIVE_NATIVE && it.notReady(c) == null }

    private fun ackFile(c: Context, engineId: String, voice: String, text: String): File {
        val h = java.security.MessageDigest.getInstance("MD5").digest("$engineId|$voice|$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(c.filesDir, "voice/ack"), "$h.pcm")
    }

    /** सब "हाँ सर" पहले से बना लो (एक बार, इंटरनेट से; फिर फ़ोन में). IO thread पर बुलाओ */
    fun prepareAcks(c: Context) {
        val e = ackEngine(c) ?: return
        val voice = e.voice(c)
        // DKI-9DC74D (नवंबर 2026): जड़ - Worker (dk-brain) में APP_TOKEN secret नहीं = हर power बटन / Hey DK पर Worker फिर
        // बुलाया जाता, हर बार 500 और वही गलती (29 बार). अब: (1) Live आवाज़ चालू हो और Live वाला ack बना हो = Worker की ज़रूरत
        // ही नहीं; (2) Worker नाकाम = 10 मिनट तक दोबारा नहीं (टिंग/Live वाली आवाज़ चलती रहे).
        val now = System.currentTimeMillis()
        if (!LiveAckRules.retryDue(ackFailAt, now)) return
        // नवंबर 2026: एक समय पर एक ही - जवाब आने तक दबाया हर बटन नई request न भेजे (एक ही फ़ाइल पर दो लिखने वाले भी नहीं)
        if (!LiveAckRules.tryBegin(ackBusy)) return
        try { makeAcks(c, e, voice) } finally { ackBusy.set(false) }
    }

    private fun makeAcks(c: Context, e: com.dk.assistant.voice.engine.VoiceEngine, voice: String) {
        val liveOn = VoiceEngines.activeId(c) == VoiceEngines.LIVE_NATIVE
        for (t in clipTexts(c)) {
            val f = ackFile(c, e.id, voice, t)
            if (f.exists() && f.length() > 2000) continue
            if (liveOn && hasLiveAck(c, t)) continue
            try {
                val out = ByteArrayOutputStream()
                e.speak(c, t, voice) { out.write(it) }
                if (out.size() > 2000) { f.parentFile?.mkdirs(); f.writeBytes(out.toByteArray()); ackFailAt = 0L }
                else {
                    // 200 पर भी खाली/कटी आवाज़ = नाकामी (वरना हर बटन पर फिर माँगते)
                    ackFailAt = System.currentTimeMillis()
                    DKLog.d("Hey DK जवाब की आवाज़ खाली आई - ${ACK_RETRY_MS / 60_000} मिनट तक दोबारा नहीं")
                    return
                }
            } catch (ex: Exception) {
                ackFailAt = System.currentTimeMillis()
                DKLog.d("Hey DK जवाब की आवाज़ नहीं बनी: ${ex.message} - ${ACK_RETRY_MS / 60_000} मिनट तक दोबारा नहीं")
                return
            }
        }
    }

    /** Worker/TTS नाकाम होने के बाद इतनी देर दोबारा नहीं */
    const val ACK_RETRY_MS = LiveAckRules.RETRY_MS
    @Volatile private var ackFailAt = 0L
    private val ackBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** नवंबर 2026: यह "हाँ सर" फ़ोन में पहले से बना है? (power बटन पर तुरंत - बिना इंटरनेट) */
    fun hasAck(c: Context, text: String): Boolean = ackPick(c, text) != null

    /** मालिक (नवंबर 2026): DK की अपनी Gemini Live आवाज़ में बना "Yes Boss" ([LiveAckMaker]) - voice बदली = नई फ़ाइल */
    fun liveAckFile(c: Context, voice: String, text: String): File = ackFile(c, VoiceEngines.LIVE_NATIVE, voice, text)

    fun hasLiveAck(c: Context, text: String): Boolean = liveAckFile(c, Prefs.geminiVoice(c), text).let { it.exists() && it.length() > 1000 }

    /** कौन सी बनी आवाज़ बजे: Live आवाज़ चालू (डिफ़ॉल्ट) = Live वाली पहले, फिर पुराना इंजन वाला. कोई नहीं = null */
    private fun ackPick(c: Context, text: String): File? {
        val live = liveAckFile(c, Prefs.geminiVoice(c), text).takeIf { it.exists() && it.length() > 1000 }
        val e = ackEngine(c)
        val eng = e?.let { ackFile(c, it.id, it.voice(c), text) }?.takeIf { it.exists() && it.length() > 2000 }
        return if (VoiceEngines.activeId(c) == VoiceEngines.LIVE_NATIVE) live ?: eng else eng ?: live
    }

    /** पहले से बनी आवाज़ बजाओ (रुककर, ~1 s). @return false = बनी नहीं थी (तब आम तरीके से बोलो) */
    fun playAck(c: Context, text: String): Boolean {
        val f = ackPick(c, text) ?: return false
        val pcm = try { f.readBytes() } catch (_: Exception) { return false }
        return PreviewPlayer.play(c) { out -> out.write(pcm) } >= 0
    }
}
