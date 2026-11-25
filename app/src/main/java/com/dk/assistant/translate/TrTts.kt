package com.dk.assistant.translate

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.dk.assistant.DKLog
import com.dk.assistant.Quiet
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 25 नवंबर 2026: DK ट्रांसलेटर का ऑफ़लाइन बोलना - फ़ोन की अपनी आवाज़ (Android TextToSpeech, भाषा-पैक फ़ोन खुद
 * उतारता है - ऐप में कुछ नहीं). आवाज़ पहले फ़ाइल में बनती है, फिर हमारे अपने रास्ते ([TrAudio.Out]) से बजती है
 * ताकि हिंदी ईयरफोन में और सामने वाले की भाषा फ़ोन स्पीकर से ही जाए.
 */
class TrTts(c: Context, private val onNote: (String) -> Unit) {
    private val dir = File(c.cacheDir, "dk_tr_tts").apply { mkdirs() }
    @Volatile private var ok = false
    private val jobs = ConcurrentHashMap<String, Pair<File, TrAudio.Out>>()
    private var n = 0
    private val tts: TextToSpeech = TextToSpeech(c.applicationContext) { s -> ok = s == TextToSpeech.SUCCESS; if (!ok) onNote("फ़ोन की आवाज़ (TTS) नहीं चली") }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { /* बनना शुरू - कुछ नहीं करना */ }
            override fun onDone(id: String?) {
                val j = jobs.remove(id ?: return) ?: return
                try { Wav.read(j.first)?.let { j.second.play(it) } } catch (e: Exception) { Quiet.note(e) }
                j.first.delete()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { jobs.remove(id ?: return)?.first?.delete() }
        })
    }

    /** text को उस भाषा (bcp, जैसे "mr-IN") में बोलो, out रास्ते से. false = भाषा / आवाज़ नहीं */
    fun say(text: String, bcp: String, out: TrAudio.Out): Boolean {
        if (!ok || text.isBlank()) return false
        val loc = Locale.forLanguageTag(bcp)
        val r = try { tts.setLanguage(loc) } catch (e: Exception) { Quiet.note(e); TextToSpeech.LANG_NOT_SUPPORTED }
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            onNote("फ़ोन की आवाज़ में ${TrLang.ALL.firstOrNull { it.bcp == bcp }?.hi ?: bcp} नहीं - Settings → भाषा → Text-to-speech से डाउनलोड करें")
            return false
        }
        val id = "tr" + (n++)
        val f = File(dir, "$id.wav")
        jobs[id] = f to out
        val res = tts.synthesizeToFile(text, Bundle(), f, id)
        if (res != TextToSpeech.SUCCESS) { jobs.remove(id); DKLog.d("Translator: TTS फ़ाइल नहीं बनी"); return false }
        return true
    }

    /** फ़ाइल बन रही है (अभी बजी नहीं) */
    fun pending(): Boolean = jobs.isNotEmpty()

    fun close() { try { tts.stop(); tts.shutdown() } catch (e: Exception) { Quiet.note(e) }; dir.listFiles()?.forEach { it.delete() } }

    /** WAV (PCM16) पढ़ो और 24 kHz mono में बदलो */
    object Wav {
        fun read(f: File): ByteArray? = parse(f.readBytes())

        fun parse(b: ByteArray): ByteArray? {
            if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF") return null
            var p = 12; var rate = 0; var ch = 1; var bits = 16
            while (p + 8 <= b.size) {
                val id = String(b, p, 4, Charsets.US_ASCII); val len = le32(b, p + 4)
                if (id == "fmt ") { ch = le16(b, p + 10); rate = le32(b, p + 12); bits = le16(b, p + 22) }
                if (id == "data") {
                    if (bits != 16 || rate <= 0) return null
                    val end = minOf(b.size, p + 8 + (if (len <= 0) b.size else len))
                    return toMono24k(b, p + 8, end, rate, maxOf(1, ch))
                }
                p += 8 + len + (len and 1)
                if (len < 0) return null
            }
            return null
        }

        private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        private fun le32(b: ByteArray, i: Int) = le16(b, i) or (le16(b, i + 2) shl 16)

        fun toMono24k(b: ByteArray, start: Int, end: Int, rate: Int, ch: Int): ByteArray {
            val frames = (end - start) / (2 * ch)
            val src = ShortArray(frames) { k -> (le16(b, start + k * 2 * ch)).toShort() }
            val outN = (frames.toLong() * TrAudio.OUT_RATE / rate).toInt()
            val out = ByteArray(outN * 2)
            for (k in 0 until outN) {
                val x = k.toDouble() * rate / TrAudio.OUT_RATE
                val i = x.toInt(); val fr = x - i
                val a = src.getOrElse(i) { 0.toShort() }.toInt(); val c2 = src.getOrElse(i + 1) { src.getOrElse(i) { 0.toShort() } }.toInt()
                val v = (a + (c2 - a) * fr).toInt()
                out[2 * k] = (v and 0xFF).toByte(); out[2 * k + 1] = ((v shr 8) and 0xFF).toByte()
            }
            return out
        }
    }
}
