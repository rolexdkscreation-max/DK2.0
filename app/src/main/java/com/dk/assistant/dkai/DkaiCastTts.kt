package com.dk.assistant.dkai

import android.content.Context
import com.dk.assistant.DKLog
import com.dk.assistant.GeminiTts
import com.dk.assistant.Prefs
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 14 नवंबर 2026 (मालिक): किरदारों वाली आवाज़ का एक ही इंजन - Broadcast Studio, होम का Broadcast कार्ड और Story Writer
 * तीनों यही इस्तेमाल करते हैं. हर किरदार की अपनी आवाज़ ([DkaiKit.studioCast]) + हर लाइन का अपना अंदाज़ ([DkaiKit.Line.tone]).
 * Gemini TTS एक बार में ज़्यादा-से-ज़्यादा 2 बोलने वाले लेता है -> टुकड़े ([DkaiKit.segments]), फिर सब जोड़कर एक WAV.
 */
object DkaiCastTts {

    /** आवाज़ के लिए Gemini keys (DK लाइव वाली पहले, फिर TTS वाली) */
    fun keys(c: Context): List<String> =
        Prefs.liveGeminiKeys(c).ifEmpty { listOf(Prefs.ttsGeminiKey(c)) }.filter { it.isNotBlank() }

    /** एक टुकड़ा (<= 2 बोलने वाले): पहले Gemini multi-speaker (हर लाइन के अंदाज़ के साथ), न चले तो हर लाइन अलग आवाज़ में */
    fun segmentPcm(keys: List<String>, seg: List<DkaiKit.Line>, cast: Map<String, DkaiKit.StudioVoice>, style: String): Pair<ByteArray, Int> {
        val names = seg.map { it.speaker }.distinct()
        val tts = names.mapIndexed { i, n -> n to DkaiKit.ttsName(n, i) }.toMap()
        val voices = names.associate { (tts[it] ?: it) to (cast[it]?.api ?: "Sulafat") }
        try {
            val w = GeminiTts.synthesizeDialog(keys, seg.map { (tts[it.speaker] ?: it.speaker) to it.text }, voices, DkaiKit.toneStyle(style, seg))
            return DkaiKit.wavPcm(w)
        } catch (e: Exception) { DKLog.d("किरदार-आवाज़: multi-speaker नहीं चला (${e.message?.take(60)}) - हर लाइन अलग") }
        val out = ByteArrayOutputStream(); var rate = 24000
        for (l in seg) {
            val w = GeminiTts.synthesizeAny(keys, l.text, cast[l.speaker]?.api ?: "Sulafat", GeminiTts.FREE + DkaiKit.lineStyle(style, l))
            val (p, r) = DkaiKit.wavPcm(w); rate = r
            if (out.size() > 0) out.write(ByteArray(rate / 6 * 2))
            out.write(p)
        }
        return out.toByteArray() to rate
    }

    /** पूरी स्क्रिप्ट -> (PCM, rate). [step] = (अभी का टुकड़ा, कुल) - स्क्रीन पर "VOICE 2/5" (IO thread पर चलाएँ) */
    fun render(keys: List<String>, lines: List<DkaiKit.Line>, cast: Map<String, DkaiKit.StudioVoice>, style: String,
               step: (Int, Int) -> Unit = { _, _ -> }): Pair<ByteArray, Int> {
        val segs = DkaiKit.segments(lines)
        val pcm = ByteArrayOutputStream(); var rate = 24000
        for ((i, seg) in segs.withIndex()) {
            step(i + 1, segs.size)
            val part = segmentPcm(keys, seg, cast, style)
            rate = part.second
            if (pcm.size() > 0) pcm.write(ByteArray(rate / 4 * 2))          // बीच में 0.25 सेकंड रुकना
            pcm.write(part.first)
        }
        return pcm.toByteArray() to rate
    }

    /** WAV फ़ाइल लिखो (बीच में टूटे तो अधूरी फ़ाइल नहीं) */
    fun writeWav(f: File, pcm: ByteArray, rate: Int): File {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(GeminiTts.pcmToWav(pcm, rate))
        if (!tmp.renameTo(f)) { f.writeBytes(tmp.readBytes()); tmp.delete() }
        return f
    }

    /** WhatsApp / Telegram पर भेजने लायक फ़ाइल (M4A; न बने तो WAV) - IO thread */
    fun shareable(f: File, outDir: File): File {
        outDir.mkdirs()
        val out = File(outDir, f.nameWithoutExtension + ".m4a")
        if (out.isFile && out.lastModified() >= f.lastModified() && out.length() > 0) return out
        return try {
            val (pcm, rate) = DkaiKit.wavPcm(f.readBytes())
            BroadcastM4a.encode(pcm, rate, out)
            if (out.length() > 0) out else f
        } catch (e: Exception) { DKLog.d("किरदार-आवाज़ m4a: ${e.message?.take(80)}"); out.delete(); f }
    }

    /** फ़ाइल शेयर करो (FileProvider - authority = packageName + ".files") */
    fun share(c: Context, f: File, title: String) {
        val u = androidx.core.content.FileProvider.getUriForFile(c, c.packageName + ".files", f)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType(if (f.extension == "m4a") "audio/mp4" else "audio/wav")
            .putExtra(android.content.Intent.EXTRA_STREAM, u)
            .putExtra(android.content.Intent.EXTRA_TEXT, title)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri(f.name, u)
        c.startActivity(android.content.Intent.createChooser(send, "आवाज़ शेयर करें").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { if (c !is android.app.Activity) addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
    }
}
