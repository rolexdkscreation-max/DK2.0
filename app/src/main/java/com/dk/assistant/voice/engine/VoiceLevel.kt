package com.dk.assistant.voice.engine

import kotlin.math.sqrt

/**
 * v3.68: लिप-सिंक. जो आवाज़ सच में बज रही है उसका ज़ोर (0..1), हर 20 ms का.
 *
 * player आवाज़ लिखते समय बताता है कि यह टुकड़ा कब बजना शुरू होगा ([schedule]); अवतार हर frame पर
 * [mouth] से उसी पल का ज़ोर पढ़ता है. इंजन कोई भी हो (Gemini TTS / Edge / Live की अपनी आवाज़) -
 * सब एक ही रास्ते से यहाँ आते हैं.
 */
object VoiceLevel {
    const val FRAME_MS = 20
    private const val KEEP = 400                       // ~8 s आगे तक
    private val at = LongArray(KEEP)
    private val lv = FloatArray(KEEP)
    private var head = 0
    private var n = 0
    @Volatile private var lastAt = 0L
    @Volatile private var smooth = 0f

    /** pcm (16-bit LE mono 24 kHz) बजना startAt (ms) पर शुरू होगा */
    fun schedule(pcm: ByteArray, startAt: Long) {
        val per = VoiceEngine.RATE * FRAME_MS / 1000 * 2         // 20 ms के bytes
        var off = 0; var t = startAt
        synchronized(this) {
            while (off + 1 < pcm.size) {
                val end = minOf(pcm.size, off + per)
                val r = rms(pcm, off, end)
                val i = (head + n) % KEEP
                if (n < KEEP) n++ else head = (head + 1) % KEEP
                at[i] = t; lv[i] = norm(r)
                t += FRAME_MS; off = end
            }
            lastAt = t
        }
    }

    /** बीच में टोका / रुका: आगे की गिनती हटाओ */
    fun clear() { synchronized(this) { n = 0; head = 0; lastAt = 0L }; smooth = 0f }

    /** अभी बजती आवाज़ का ज़ोर (0..1). null = कोई नापी हुई आवाज़ नहीं (तब पुराना अंदाज़ा चले) */
    fun mouth(now: Long = System.currentTimeMillis()): Float? {
        val raw: Float
        synchronized(this) {
            if (n == 0 || now > lastAt + 250) return null
            // पुराने frames हटाओ, अभी वाला खोजो
            while (n > 1 && at[(head + 1) % KEEP] <= now) { head = (head + 1) % KEEP; n-- }
            raw = if (at[head] <= now && now < at[head] + FRAME_MS * 2) lv[head] else 0f
        }
        // खुलना तेज़, बंद होना थोड़ा धीमा - होंठ काँपें नहीं
        smooth = if (raw > smooth) smooth + (raw - smooth) * 0.6f else smooth + (raw - smooth) * 0.3f
        return smooth
    }

    fun rms(b: ByteArray, from: Int = 0, to: Int = b.size): Double {
        var sq = 0.0; var k = 0; var i = from
        while (i + 1 < to) { val v = ((b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)).toShort().toInt(); sq += (v * v).toDouble(); k++; i += 4 }
        return if (k == 0) 0.0 else sqrt(sq / k)
    }

    /** RMS -> 0..1 (बोली की आम ऊँचाई ~ 1500-6000) */
    fun norm(r: Double): Float = (((r - 250.0) / 4500.0).coerceIn(0.0, 1.0)).toFloat()
}
