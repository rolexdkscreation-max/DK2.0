package com.dk.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.dk.assistant.DKLog
import com.dk.assistant.Prefs
import com.dk.assistant.TurnDetector
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
        !trained(c) -> "अभी सिखाया नहीं - नीचे \"Hey DK सिखाओ\" दबाइए"
        !Prefs.heyDkOn(c) -> "सीखा हुआ है, पर बंद है"
        ownerCheck(c) -> "चालू: \"Hey DK\" + सिर्फ़ आपकी आवाज़ (फ़ोन के अंदर)"
        else -> "चालू: \"Hey DK\" (आवाज़-पहचान मॉडल नहीं - दूसरे की आवाज़ पर भी कभी जाग सकता है)"
    }

    // ------------------------------------------------------------------ सीखना

    /** एक बार "Hey DK" रिकॉर्ड (सुनने वाला माइक-रास्ता ही, ताकि सुनते समय वैसा ही मिले) */
    suspend fun recordTake(onLevel: (Float) -> Unit): VoiceEnroll.Take? =
        VoiceEnroll.record(onLevel, MediaRecorder.AudioSource.VOICE_RECOGNITION, endSilenceMs = 450, minSpeechMs = 200)

    fun featuresOf(pcm: ByteArray): Array<FloatArray> = WakeMatch.features(SpeakerFbank.pcm16(pcm))

    /**
     * नमूने सेव. @return null = ठीक, वरना हिंदी कारण
     * आवाज़ (PCM) सेव नहीं होती - सिर्फ़ अंक (MFCC + पहचान).
     */
    fun save(c: Context, takes: List<ByteArray>): String? {
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
    class Hit(val distance: Float, val owner: Float?, val rest: ByteArray?)

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
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, RATE * 2)).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (_: Exception) { null } ?: return@withContext null
        val det = TurnDetector(CHUNK_MS, endSilenceMs = 350, minSpeechMs = 200)
        val pre = ArrayDeque<ByteArray>()
        val seg = ByteArrayOutputStream()
        // बात चलती रहे तो शुरू के कुछ कटों पर जाँचो (एक साँस में "Hey DK, ..." भी पकड़ में आए)
        val cuts = model.prefixCuts().map { it * 2 }
        val maxSeg = RATE * 2 * WakeMatch.MAX_MS / 1000 + 8 * chunk * 2
        var inSeg = false; var preBytes = 0; var cutIdx = 0; var checkedUpTo = 0
        val buf = ShortArray(chunk); val bytes = ByteArray(chunk * 2)
        val sense = Prefs.heyDkSense(c)
        try {
            r.startRecording()
            while (isActive && !stop()) {
                val n = r.read(buf, 0, chunk)
                if (n <= 0) { Thread.sleep(20); continue }
                var sum = 0.0
                for (i in 0 until n) { val s = buf[i].toInt(); sum += (s * s).toDouble(); bytes[i * 2] = (s and 0xff).toByte(); bytes[i * 2 + 1] = (s shr 8 and 0xff).toByte() }
                val ev = det.feed(sqrt(sum / n), System.currentTimeMillis())
                val piece = bytes.copyOf(n * 2)
                if (!inSeg) {
                    pre.addLast(piece); while (pre.size > 8) pre.removeFirst()
                    if (ev == TurnDetector.Event.SPEECH_START) {
                        inSeg = true; cutIdx = 0; checkedUpTo = 0; seg.reset(); pre.forEach { seg.write(it) }; preBytes = seg.size(); pre.clear()
                    }
                    continue
                }
                seg.write(piece)
                val ended = ev == TurnDetector.Event.SPEECH_END || !det.inSpeech
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
                            det.feed(sqrt(s2 / k), System.currentTimeMillis())
                            quiet = if (det.inSpeech) 0 else quiet + CHUNK_MS
                        }
                        // "Hey DK" के बाद बस थोड़ा-सा (साँस/हम्म) = सिर्फ़ "Hey DK"
                        val extra = rest.size() - probe
                        return@withContext Hit(hit.distance, hit.owner, if (extra > RATE * 2 * 4 / 10) rest.toByteArray() else null)
                    }
                }
                if (ended || seg.size() > maxSeg * 3) { inSeg = false; seg.reset(); det.reset() }
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

    /** जो इंजन अकेले बोल सकता है (Live की अपनी आवाज़ अकेले नहीं बोलती -> Gemini TTS / Edge) */
    private fun ackEngine(c: Context) = listOfNotNull(VoiceEngines.active(c), VoiceEngines.byId(VoiceEngines.GEMINI_TTS), VoiceEngines.byId(VoiceEngines.EDGE_TTS))
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
        for (t in acks(c)) {
            val f = ackFile(c, e.id, voice, t)
            if (f.exists() && f.length() > 2000) continue
            try {
                val out = ByteArrayOutputStream()
                e.speak(c, t, voice) { out.write(it) }
                if (out.size() > 2000) { f.parentFile?.mkdirs(); f.writeBytes(out.toByteArray()) }
            } catch (ex: Exception) { DKLog.d("Hey DK जवाब की आवाज़ नहीं बनी: ${ex.message}") }
        }
    }

    /** पहले से बनी आवाज़ बजाओ (रुककर, ~1 s). @return false = बनी नहीं थी (तब आम तरीके से बोलो) */
    fun playAck(c: Context, text: String): Boolean {
        val e = ackEngine(c) ?: return false
        val f = ackFile(c, e.id, e.voice(c), text)
        if (!f.exists()) return false
        val pcm = try { f.readBytes() } catch (_: Exception) { return false }
        return PreviewPlayer.play(c) { out -> out.write(pcm) } >= 0
    }
}
