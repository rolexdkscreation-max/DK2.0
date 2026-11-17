package com.dk.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.sqrt

/** बोलकर लिखना - फ़ोन वाला ([InlineDictation]) या DK सर्वर वाला ([BackendDictation]) */
interface Dictation {
    val text: String
    fun start()
    /** रोको, अब तक का लिखा रखो */
    fun stop(): String
    fun cancel()
}

/** टुकड़े काटने के नियम (JVM test) - चुप्पी/लंबाई से, बिना Android */
object DictationChunks {
    const val RATE = 16_000
    const val FRAME = 320                 // 20 ms
    const val SPEECH_RMS = 700.0
    const val END_SILENCE_MS = 700
    const val MAX_MS = 12_000
    const val MIN_SPEECH_MS = 300

    fun rms(buf: ShortArray, n: Int): Double {
        if (n <= 0) return 0.0
        var s = 0.0
        for (i in 0 until n) { val v = buf[i].toDouble(); s += v * v }
        return sqrt(s / n)
    }

    /** इस टुकड़े को अब भेजें? (बोल चुके + चुप्पी, या बहुत लंबा) */
    fun cut(speechMs: Int, silenceMs: Int, totalMs: Int): Boolean =
        (speechMs >= MIN_SPEECH_MS && silenceMs >= END_SILENCE_MS) || totalMs >= MAX_MS

    /** बिल्कुल बोला ही नहीं = भेजो मत (समय/limit बचे) */
    fun worth(speechMs: Int): Boolean = speechMs >= MIN_SPEECH_MS
}

/**
 * 17 नवंबर 2026 (मालिक का "DK बैकएंड मास्टर प्रॉम्प्ट"): टाइप वाला माइक पहले DK सर्वर (/stt, Whisper) से,
 * वह न चले तो फ़ोन की पहचान ([InlineDictation], पहले जैसा) - अब तक का लिखा साथ रहता है.
 * आज का मुफ़्त समय पूरा = तय संदेश दिखाकर रुक जाता है.
 */
class BackendDictation(
    private val ctx: Context,
    private val onText: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onEnd: (String?) -> Unit,
    /** फ़ोन वाला (वही callbacks) */
    private val phone: (onText: (String) -> Unit, onLevel: (Float) -> Unit, onEnd: (String?) -> Unit) -> Dictation,
) : Dictation {
    companion object {
        /** सर्वर जुड़ा हो तो पहले सर्वर */
        fun usable(): Boolean = AiRouter.backendReady()

        /** सर्वर हो तो [BackendDictation], नहीं तो सीधे फ़ोन वाला */
        fun pick(c: Context, onText: (String) -> Unit, onLevel: (Float) -> Unit, onEnd: (String?) -> Unit,
                 phone: (onText: (String) -> Unit, onLevel: (Float) -> Unit, onEnd: (String?) -> Unit) -> Dictation): Dictation =
            if (usable()) BackendDictation(c, onText, onLevel, onEnd, phone) else phone(onText, onLevel, onEnd)
    }

    private val h = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor { r -> Thread(r, "dk-dictation").apply { isDaemon = true } }
    @Volatile private var active = false
    @Volatile private var committed = ""
    @Volatile private var fallback: Dictation? = null
    private var rec: Thread? = null

    override val text: String get() = fallback?.let { DictationRules.join(committed, it.text) } ?: committed

    override fun start() {
        if (active) return
        active = true
        QuietMic.on(ctx)
        rec = Thread({ loop() }, "dk-dictation-mic").also { it.start() }
    }

    @SuppressLint("MissingPermission")   // बुलाने वाला पहले माइक की इजाज़त जाँचता है
    private fun loop() {
        val min = AudioRecord.getMinBufferSize(DictationChunks.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, DictationChunks.RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, DictationChunks.FRAME * 8))
        } catch (e: Exception) { null }
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) { try { r?.release() } catch (e: Exception) { Quiet.note(e) }; h.post { toPhone() }; return }
        val frame = ShortArray(DictationChunks.FRAME)
        val out = ByteArrayOutputStream()
        var speech = 0; var silence = 0; var total = 0
        try {
            r.startRecording()
            while (active && fallback == null) {
                val n = r.read(frame, 0, frame.size)
                if (n <= 0) continue
                val lv = DictationChunks.rms(frame, n)
                h.post { if (active) onLevel((lv / 6000.0).coerceIn(0.0, 1.0).toFloat()) }
                for (i in 0 until n) { out.write(frame[i].toInt() and 0xff); out.write((frame[i].toInt() shr 8) and 0xff) }
                val ms = n * 1000 / DictationChunks.RATE
                total += ms
                if (lv > DictationChunks.SPEECH_RMS) { speech += ms; silence = 0 } else silence += ms
                if (DictationChunks.cut(speech, silence, total)) {
                    if (DictationChunks.worth(speech)) send(out.toByteArray())
                    out.reset(); speech = 0; silence = 0; total = 0
                } else if (speech == 0 && total >= 2_000) { out.reset(); total = 0 }   // सिर्फ़ चुप्पी - फेंको
            }
            if (!active && DictationChunks.worth(speech)) send(out.toByteArray())   // रोका तो आख़िरी टुकड़ा भी
        } catch (e: Exception) {
            DKLog.d("बोलकर लिखना (सर्वर): ${e.message?.take(60)}")
        } finally {
            try { r.stop() } catch (e: Exception) { Quiet.note(e) }
            try { r.release() } catch (e: Exception) { Quiet.note(e) }
        }
    }

    private fun send(pcm: ByteArray) {
        val wav = GeminiTts.pcmToWav(pcm, DictationChunks.RATE)
        net.execute {
            try {
                val t = AiRouter.stt(ctx, wav, "hi").trim()
                if (t.isNotBlank()) h.post { committed = DictationRules.join(committed, t); if (fallback == null) onText(text) }
            } catch (e: Exception) {
                DKLog.d("सर्वर से लिखना नहीं हुआ: ${e.message?.take(60)} - फ़ोन वाली पहचान")
                h.post {
                    if (AiRouter.stopsTalk(e)) { val say = AiRouter.say(e); active = false; release(); onEnd(say) }
                    else if (active) toPhone()
                }
            }
        }
    }

    /** सर्वर नहीं चला - फ़ोन वाला, अब तक का लिखा आगे जोड़कर */
    private fun toPhone() {
        if (!active || fallback != null) return
        val base = committed
        val f = phone({ t -> onText(DictationRules.join(base, t)) }, onLevel, { msg -> active = false; release(); onEnd(msg) })
        fallback = f
        f.start()
    }

    override fun stop(): String {
        val t = text
        active = false
        fallback?.stop()
        release()
        return fallback?.let { DictationRules.join(committed, it.text) }?.ifBlank { t } ?: t
    }

    override fun cancel() { active = false; fallback?.cancel(); release() }

    private fun release() {
        h.post { onLevel(0f) }
        QuietMic.off(ctx)
    }
}
