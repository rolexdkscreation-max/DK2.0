package com.dk.assistant.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import com.dk.assistant.DKLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * v4.6: 30 सेकंड की एक लगातार रिकॉर्डिंग - मालिक वाक्य 5 बार बोलता है, [VoiceTape.Cutter] हर बार काटता है.
 * 5 साफ़ बार मिलते ही (या 30 सेकंड पूरे होते ही) रुक जाता है. आवाज़ फ़ोन में ही रहती है.
 */
object VoiceTapeRec {
    private const val RATE = WakeMatch.RATE

    class Tick(val leftMs: Long, val got: Int, val level: Float, val note: String)
    class Result(val takes: List<ByteArray>, val tooShort: Int, val tooLong: Int, val micOk: Boolean)

    /**
     * @param ok हर कटे हुए बार की जाँच (जैसे बोले फ़्रेम गिनना) - false = गिनो मत
     * @param onTick UI के लिए (IO thread से, ~हर 40 ms)
     */
    @SuppressLint("MissingPermission")
    suspend fun record(
        source: Int,
        seconds: Int = VoiceTape.SECONDS,
        want: Int = VoiceTape.TIMES,
        ok: (ByteArray) -> Boolean,
        onTick: (Tick) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val chunk = RATE * VoiceTape.CHUNK_MS / 1000
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, RATE * 2))
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (_: Exception) { null }
        if (r == null) return@withContext Result(emptyList(), 0, 0, micOk = false)
        val cut = VoiceTape.Cutter()
        val takes = ArrayList<ByteArray>()
        val buf = ShortArray(chunk)
        val total = seconds * 1000L
        var ms = 0L; var note = ""; var skipped = 0
        try {
            r.startRecording()
            while (isActive && ms < total && takes.size < want) {
                val n = r.read(buf, 0, chunk)
                if (n <= 0) { Thread.sleep(10); continue }
                val bytes = ByteArray(n * 2); var sum = 0.0
                for (i in 0 until n) {
                    val v = buf[i].toInt(); sum += (v * v).toDouble()
                    bytes[i * 2] = (v and 0xff).toByte(); bytes[i * 2 + 1] = (v shr 8 and 0xff).toByte()
                }
                val lv = sqrt(sum / n)
                ms += n * 1000L / RATE
                val shortBefore = cut.tooShort; val longBefore = cut.tooLong
                cut.feed(bytes, lv)?.let { seg ->
                    if (ok(seg)) { takes += seg; note = if (takes.size < want) "✓ सुना - अब फिर बोलिए" else "✓ हो गया" }
                    else { skipped++; note = "साफ़ नहीं आया - फिर बोलिए" }
                }
                if (cut.tooShort > shortBefore) note = "बहुत छोटा था - पूरा वाक्य बोलिए"
                if (cut.tooLong > longBefore) note = "बहुत लंबा - हर बार के बाद रुकिए"
                if (cut.inSpeech) note = "सुन रहा हूँ…"
                onTick(Tick(total - ms, takes.size, (lv / 2500.0).toFloat().coerceIn(0f, 1f), note))
            }
            if (takes.size < want) cut.flush()?.let { if (ok(it)) takes += it }
        } finally {
            try { r.stop() } catch (_: Exception) {}
            r.release()
        }
        DKLog.d("आवाज़-कमांड 30s: ${takes.size}/$want बार, छोटे ${cut.tooShort}, लंबे ${cut.tooLong}, साफ़ नहीं $skipped, ${ms / 1000}s")
        Result(takes, cut.tooShort + skipped, cut.tooLong, micOk = true)
    }
}
