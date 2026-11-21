package com.dk.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.dk.assistant.stt.SherpaStt
import kotlin.math.sqrt

/** बोलकर लिखना - फ़ोन पर Sherpa-ONNX ([SherpaDictation]) या Android/Google वाला ([InlineDictation]) */
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
 * 21 नवंबर 2026 (मालिक: "माइक बटन में Whisper turbo / Google / Groq की जगह फ़ोन पर Sherpa-ONNX; बोलता जाऊँ और शब्द उसी
 * पल टेक्स्ट बॉक्स में"): टाइप वाला माइक फ़ोन पर ही ([SherpaStt], streaming, बिना इंटरनेट / key). हर ~0.1 s की आवाज़
 * पर अब तक के शब्द टेक्स्ट बॉक्स में; ठहराव = वह हिस्सा पक्का. मॉडल न हो / Settings में Android चुना / बीच में
 * नाकाम = फ़ोन की Android/Google पहचान ([InlineDictation], पहले जैसा) - अब तक का लिखा साथ रहता है.
 */
class SherpaDictation(
    private val ctx: Context,
    private val onText: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onEnd: (String?) -> Unit,
    /** फ़ोन वाला (वही callbacks) */
    private val phone: (onText: (String) -> Unit, onLevel: (Float) -> Unit, onEnd: (String?) -> Unit) -> Dictation,
) : Dictation {
    companion object {
        /** 100 ms के टुकड़े (मॉडल 320 ms देखता है - इससे छोटे पर बस ज़्यादा बार बुलाना) */
        private const val READ = DictationChunks.RATE / 10
        /** रोकने पर आख़िरी शब्द निकलने तक ज़्यादा से ज़्यादा इतना रुको (स्क्रीन न अटके) */
        private const val STOP_WAIT_MS = 500L

        /** Sherpa-ONNX चुना + मॉडल तैयार = यही */
        fun usable(c: Context): Boolean = com.dk.assistant.talk.TalkPrefs.stt(c) == com.dk.assistant.talk.TalkPrefs.STT_SHERPA && SherpaStt.ready(c)

        /** फ़ोन वाला Sherpa-ONNX चल सके तो [SherpaDictation], नहीं तो सीधे Android/Google वाला */
        fun pick(c: Context, onText: (String) -> Unit, onLevel: (Float) -> Unit, onEnd: (String?) -> Unit,
                 phone: (onText: (String) -> Unit, onLevel: (Float) -> Unit, onEnd: (String?) -> Unit) -> Dictation): Dictation =
            if (usable(c)) SherpaDictation(c, onText, onLevel, onEnd, phone) else phone(onText, onLevel, onEnd)
    }

    private val h = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    @Volatile private var committed = ""
    @Volatile private var seg = ""
    @Volatile private var fallback: Dictation? = null
    private var rec: Thread? = null

    private val mine: String get() = DictationRules.join(committed, seg)
    override val text: String get() = fallback?.let { DictationRules.join(mine, it.text) } ?: mine

    override fun start() {
        if (active) return
        active = true
        QuietMic.on(ctx)
        rec = Thread({ loop() }, "dk-dictation-mic").also { it.start() }
    }

    @SuppressLint("MissingPermission")   // बुलाने वाला पहले माइक की इजाज़त जाँचता है
    private fun loop() {
        val s = SherpaStt.Stream.open(ctx)
        if (s == null) { DKLog.d("बोलकर लिखना: ${SherpaStt.NAME} नहीं खुला - फ़ोन वाली पहचान"); h.post { toPhone() }; return }
        val min = AudioRecord.getMinBufferSize(DictationChunks.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, DictationChunks.RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, READ * 4))
        } catch (e: Exception) { DKLog.d("बोलकर लिखना माइक: ${e.message}"); null }
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
            try { r?.release() } catch (e: Exception) { Quiet.note(e) }
            s.close(); h.post { toPhone() }; return
        }
        val frame = ShortArray(READ)
        var failed = false
        try {
            r.startRecording()
            while (active && fallback == null) {
                val n = r.read(frame, 0, frame.size)
                if (n <= 0) continue
                val lv = DictationChunks.rms(frame, n)
                h.post { if (active) onLevel((lv / 6000.0).coerceIn(0.0, 1.0).toFloat()) }
                val f = FloatArray(n) { frame[it] / 32768f }
                show(s, s.feed(f), s.endpoint())
            }
            // रोका: थोड़ी चुप्पी देकर आख़िरी शब्द भी
            if (fallback == null) show(s, s.feed(FloatArray(DictationChunks.RATE * SherpaStt.TAIL_MS / 1000)), true)
        } catch (e: Throwable) {
            DKLog.d("बोलकर लिखना (${SherpaStt.NAME}): ${e.javaClass.simpleName} ${e.message?.take(60)}")
            failed = true
        } finally {
            try { r.stop() } catch (e: Exception) { Quiet.note(e) }
            try { r.release() } catch (e: Exception) { Quiet.note(e) }
            s.close()
        }
        if (failed) h.post { if (active) toPhone() }
    }

    /** अब तक का text टेक्स्ट बॉक्स में (बदला हो तभी); ठहराव = हिस्सा पक्का */
    private fun show(s: SherpaStt.Stream, t: String, ep: Boolean) {
        val before = mine
        if (ep) {
            if (t.isNotBlank()) committed = DictationRules.join(committed, t)
            seg = ""
            s.reset()
        } else seg = t
        val now = mine
        if (now != before) h.post { if (active && fallback == null) onText(now) }
    }

    /** Sherpa-ONNX नहीं चला - फ़ोन वाला, अब तक का लिखा आगे जोड़कर */
    private fun toPhone() {
        if (!active || fallback != null) return
        val base = mine
        val f = phone({ t -> onText(DictationRules.join(base, t)) }, onLevel, { msg -> active = false; release(); onEnd(msg) })
        fallback = f
        f.start()
    }

    override fun stop(): String {
        active = false
        fallback?.stop()
        // आख़िरी टुकड़े के शब्द (ज़्यादा से ज़्यादा आधा सेकंड)
        if (fallback == null) try { rec?.join(STOP_WAIT_MS) } catch (e: InterruptedException) { DKLog.d("बोलकर लिखना: ${e.message}") }
        release()
        return text
    }

    override fun cancel() { active = false; fallback?.cancel(); release() }

    private fun release() {
        h.post { onLevel(0f) }
        QuietMic.off(ctx)
    }
}
