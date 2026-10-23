package com.dk.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * DK ka apna "kaan": bina beep ke mic se lagatar sunta hai.
 * Jab aap bolte ho (awaaz tez hoti hai) recording shuru, chup hote hi (0.8 sec) recording khatam.
 * Sirf aapki baat ka hissa text banane bheja jaata hai.
 */
class Ear(
    private val ctx: Context,
    private val muted: () -> Boolean = { false },
    /** Aapne bolna shuru kiya (jaise: ghanti baj rahi ho to ringtone dheemi karo) */
    private val onSpeechStart: () -> Unit = {}
) {

    companion object {
        const val RATE = 16000
        private const val FRAME = 480                 // 30 ms
        private const val START_FRAMES = 3            // 90 ms awaaz = bolna shuru
        // Chhoti baat ("हाँ", "उठाओ") ke baad 0.7 sec chup = khatam (tez jawab).
        // Lambi baat me beech ka saans/ruk-ruk kar bolna na kate -> 1.1 sec tak intezaar.
        private const val END_SILENCE_SHORT = 15      // ~0.45 sec (v3.7: pehle 0.6) - chhoti baat ke baad turant jawab
        private const val END_SILENCE_LONG = 22       // ~0.66 sec (v3.7: pehle 0.8) (beech me saans le kar bolo to baat na kate)
        private const val LONG_AFTER = 50             // 1.5 sec se zyada bole = lambi baat
        private const val MAX_FRAMES = 25 * 1000 / 30 // 25 sec max (lamba sawaal bhi poora suno)
        private const val MIN_SPEECH_FRAMES = 8       // 0.24 sec se chhota = shor ("हाँ" bhi pakdo)
        private const val PRE_ROLL = 15               // shuru se pehle ka 0.36 sec bhi rakho (pehla shabd na kate)
    }

    private var rec: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var noise = 150.0
    /** Pehli baar hi shor naapne ka lamba intezaar; baad me turant sunna (pehla shabd na kate) */
    private var warmed = false
    /** Asli sunne wala caption (Gemini key ho to): bolte-bolte shabd screen par (v3.7) */
    @Volatile var onFrame: ((ShortArray, Int) -> Unit)? = null
    /** DK ki apni awaaz mic me kitni aati hai (bolte waqt) */
    private var echo = 0.0
    /** Abhi user ki baat record ho rahi hai? */
    @Volatile var capturing = false
        private set
    /** Mic me kya dikkat aayi: "perm" = permission nahi, "busy" = mic nahi khula (koi aur app?), "silent" = mic se bilkul awaaz nahi aa rahi */
    @Volatile var problem: String? = null
        private set

    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord? {
        rec?.let { return it }
        if (!Setup.hasMic(ctx)) { problem = "perm"; return null }
        return try {
            // v3.39: Bluetooth headset जुड़ा हो तभी उसका माइक (साझा BtAudio); नहीं तो फ़ोन का - मोड को बेवजह हाथ नहीं
            val bt = BtAudio.acquire(ctx, this) { routeChanged = true }
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 2 * 8))
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); problem = "busy"; return null }
            if (bt) BtAudio.btInput?.let { d -> try { r.setPreferredDevice(d) } catch (_: Exception) {} }
            DKLog.d("Ear माइक: " + if (bt) "Bluetooth headset" else "फ़ोन")
            try { if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true } } catch (_: Exception) {}
            try { if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true } } catch (_: Exception) {}
            rec = r; r
        } catch (e: Exception) {
            DKLog.d("Mic nahi khula: ${e.message}"); problem = if (e is SecurityException) "perm" else "busy"; null
        }
    }

    /** Bluetooth जुड़ा/हटा - माइक नए रास्ते पर दोबारा खुले */
    @Volatile private var routeChanged = false

    fun release() {
        closeRec()
        BtAudio.release(this)
    }

    private fun closeRec() {
        try { rec?.stop() } catch (_: Exception) {}
        try { aec?.release(); ns?.release() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        rec = null; aec = null; ns = null
    }

    /** Agli baat ka PCM (16kHz mono 16-bit). null = mic error. */
    /**
     * barge = DK bol raha ho tab bhi suno (beech me tokna). DK ki awaaz (goonj) se upar wali awaaz hi user maani jaati hai.
     * vadOnly = sirf pata karo ki user bolne laga (Google mic wale tareeke ke liye) - turant khaali array.
     */
    suspend fun nextUtterance(barge: Boolean = false, vadOnly: Boolean = false, onStart: (() -> Unit)? = null,
                              onDiscard: (() -> Unit)? = null): ByteArray? = withContext(Dispatchers.IO) {
        if (routeChanged) { routeChanged = false; closeRec() }
        var r = open() ?: return@withContext null
        try { r.startRecording() } catch (e: Exception) { release(); problem = "busy"; return@withContext null }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { release(); problem = "busy"; return@withContext null }
        problem = null
        var zeroRun = 0
        val buf = ShortArray(FRAME)
        val pre = ArrayDeque<ShortArray>()
        val speech = ByteArrayOutputStream()
        var loud = 0; var silent = 0; var frames = 0; var speechFrames = 0
        var talking = false
        // pehli baar 240ms shor naapo; baad me sirf 60ms (v3.7: pehle har baar 240ms - shuru ka shabd kat jaata tha)
        var warm = if (warmed) 2 else 8; warmed = true
        try {
            while (true) {
                coroutineContext.ensureActive()
                // Bluetooth जुड़ा/हटा (बात के बीच नहीं): माइक नए रास्ते पर
                if (routeChanged && !talking) {
                    routeChanged = false
                    try { r.stop() } catch (_: Exception) {}
                    closeRec()
                    r = open() ?: return@withContext null
                    try { r.startRecording() } catch (_: Exception) { release(); problem = "busy"; return@withContext null }
                }
                val n = r.read(buf, 0, FRAME)
                if (n <= 0) { if (n < 0) return@withContext null; continue }
                var sum = 0.0
                for (i in 0 until n) { val s = buf[i].toDouble(); sum += s * s }
                val rms = sqrt(sum / n)
                // bilkul 0 awaaz 3 sec tak = Android ne mic kisi aur ko de diya (call/dusra app) -> batao
                if (sum == 0.0) { if (++zeroRun > 100 && !talking) { problem = "silent"; return@withContext null } } else zeroRun = 0

                val dkTalking = muted()
                // DK khud bol raha hai -> (normal sunne me) apni awaaz mat suno
                if (dkTalking && !barge) { talking = false; speech.reset(); pre.clear(); loud = 0; warm = 4; DKState.level = 0f; continue }
                if (warm > 0) {
                    // naapte waqt bhi awaaz rakh lo (pehle shabd ke liye), aur boli hui baat ko shor mat maano
                    pre.addLast(buf.copyOf(n)); if (pre.size > PRE_ROLL) pre.removeFirst()
                    if (!dkTalking && rms < noise * 2.5) noise = noise * 0.7 + rms * 0.3
                    warm--; continue
                }
                if (dkTalking && !talking) echo = if (echo == 0.0) rms else echo * 0.85 + rms * 0.15
                // dheemi awaaz bhi pakdo, par pankhe/TV ka shor nahi. DK bol raha ho to uski goonj se kaafi upar.
                // v3.7: dheemi awaaz ke liye kam seema (pehle noise*2.0 / 260) - dheere bolo tab bhi pakde
                val startTh = if (barge && dkTalking) maxOf(noise * 2.3, echo * 1.8, 650.0) else maxOf(noise * 1.7, 150.0)
                val stopTh = maxOf(noise * 1.3, 120.0)
                val startFrames = if (barge && dkTalking) 7 else START_FRAMES

                if (!talking) {
                    DKState.level = 0f
                    val copy = buf.copyOf(n)
                    pre.addLast(copy); if (pre.size > PRE_ROLL) pre.removeFirst()
                    // shor: neeche jaldi, upar bahut dheere (dheemi baat se seema na badhe)
                    if (rms > startTh) loud++ else { loud = 0; if (!dkTalking) noise = if (rms < noise) noise * 0.9 + rms * 0.1 else noise * 0.995 + rms * 0.005 }
                    if (loud >= startFrames) {
                        talking = true; capturing = true
                        pre.forEach { writeShorts(speech, it, it.size); try { onFrame?.invoke(it, it.size) } catch (_: Exception) {} }
                        pre.clear()
                        frames = 0; silent = 0; speechFrames = loud
                        try { onSpeechStart() } catch (_: Exception) {}
                        try { onStart?.invoke() } catch (_: Exception) {}
                        if (vadOnly) return@withContext ByteArray(0)
                    }
                } else {
                    DKState.level = (rms / 3500.0).toFloat().coerceIn(0f, 1f)
                    writeShorts(speech, buf, n)
                    try { onFrame?.invoke(buf, n) } catch (_: Exception) {}
                    frames++
                    if (rms < stopTh) silent++ else { silent = 0; speechFrames++ }
                    val endAfter = if (speechFrames > LONG_AFTER) END_SILENCE_LONG else END_SILENCE_SHORT
                    if (silent >= endAfter || frames >= MAX_FRAMES) {
                        // dheemi baat ko theek level tak (Whisper ko saaf sunaai de)
                        if (speechFrames >= MIN_SPEECH_FRAMES) return@withContext MicBoost.normalize(speech.toByteArray())
                        // bahut chhota = khaansi/khatak -> phir se suno
                        talking = false; capturing = false; speech.reset(); loud = 0
                        try { onDiscard?.invoke() } catch (_: Exception) {}
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        } finally {
            try { r.stop() } catch (_: Exception) {}
            capturing = false
            DKState.level = 0f
        }
    }

    private fun writeShorts(out: ByteArrayOutputStream, s: ShortArray, n: Int) {
        val b = ByteArray(n * 2)
        for (i in 0 until n) { b[i * 2] = (s[i].toInt() and 0xff).toByte(); b[i * 2 + 1] = (s[i].toInt() shr 8 and 0xff).toByte() }
        out.write(b)
    }
}
