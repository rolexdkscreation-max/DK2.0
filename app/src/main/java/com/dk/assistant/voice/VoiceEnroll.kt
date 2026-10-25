package com.dk.assistant.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.dk.assistant.TurnDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * v3.66: "मेरी आवाज़ सिखाओ" - एक वाक्य रिकॉर्ड (Live वाला ही माइक-रास्ता VOICE_COMMUNICATION, ताकि पहचान वैसी ही बने)।
 * आवाज़ सिर्फ़ मेमोरी में, पहचान बनते ही छोड़ दी जाती है - कहीं सेव/भेजी नहीं जाती।
 */
object VoiceEnroll {
    /** सीखने के वाक्य (हर एक ~3 सेकंड) */
    val PHRASES = listOf(
        "डीके, आज मौसम कैसा रहेगा, मुझे ज़रा बताओ।",
        "मेरे फ़ोन पर व्हाट्सऐप खोलो और नया मैसेज दिखाओ।",
        "कल सुबह सात बजे का अलार्म लगा देना।",
        "आज की ताज़ा ख़बरें सुनाओ, आराम से बोलना।",
        "मैं बोल रहा हूँ, मेरी आवाज़ अच्छे से पहचान लो।",
    )

    class Take(val pcm: ByteArray, val speechMs: Int, val level: Double)

    /**
     * एक वाक्य: बोलना शुरू होने का 6 सेकंड इंतज़ार, फिर 0.8 सेकंड चुप्पी या 8 सेकंड पर बंद।
     * @param onLevel 0..1 (मीटर), IO thread से
     * @return null = माइक नहीं खुला / कोई नहीं बोला
     */
    @SuppressLint("MissingPermission")
    suspend fun record(onLevel: (Float) -> Unit = {}): Take? = withContext(Dispatchers.IO) {
        val rate = OwnerGate.RATE
        val chunk = rate * 40 / 1000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, chunk * 2 * 6)).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (_: Exception) { null } ?: return@withContext null
        val det = TurnDetector(40, endSilenceMs = 800, minSpeechMs = 400)
        val out = ByteArrayOutputStream()
        val pre = ArrayDeque<ByteArray>()
        val rms = ArrayList<Double>()
        val buf = ShortArray(chunk); val bytes = ByteArray(chunk * 2)
        var started = false; var ms = 0; var speech = 0
        try {
            r.startRecording()
            while (isActive) {
                val n = r.read(buf, 0, chunk)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) { val s = buf[i].toInt(); sum += (s * s).toDouble(); bytes[i * 2] = (s and 0xff).toByte(); bytes[i * 2 + 1] = (s shr 8 and 0xff).toByte() }
                val lv = sqrt(sum / n)
                onLevel((lv / 3500.0).toFloat().coerceIn(0f, 1f))
                ms += 40
                val ev = det.feed(lv, System.currentTimeMillis())
                val c = bytes.copyOf(n * 2)
                if (!started) {
                    pre.addLast(c); while (pre.size > 8) pre.removeFirst()
                    if (ev == TurnDetector.Event.SPEECH_START) { started = true; pre.forEach { out.write(it) }; pre.clear() }
                    else if (ms > 6_000) return@withContext null
                    continue
                }
                out.write(c)
                if (det.inSpeech) { speech += 40; rms.add(lv) }
                if (ev == TurnDetector.Event.SPEECH_END || ms > 14_000 || out.size() >= rate * 2 * 8) break
            }
        } finally {
            try { r.stop() } catch (_: Exception) {}
            r.release()
        }
        if (!started) return@withContext null
        Take(out.toByteArray(), speech, VoicePrint.speechLevel(rms, det.noise))
    }
}
