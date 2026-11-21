package com.dk.assistant

import android.content.Context
import com.dk.assistant.stt.SherpaStt
import com.dk.assistant.talk.TalkPrefs

/**
 * Bola hua audio -> text (bina beep wale mode ke liye).
 * 21 नवंबर 2026 (मालिक: "Whisper turbo, Google व Groq Whisper की जगह फ़ोन पर Sherpa-ONNX, बिना इंटरनेट / key"):
 * अब सिर्फ़ फ़ोन पर [SherpaStt] (कोई key / सर्वर नहीं). मॉडल न हो / न चले = [available] false -> बुलाने वाला
 * Android/Google की पहचान पर (पहले जैसा).
 */
object Transcriber {
    /** Sherpa-ONNX चुना है, मॉडल उतरा है और यह फ़ोन चला सकता है */
    fun available(c: Context) = TalkPrefs.stt(c) == TalkPrefs.STT_SHERPA && SherpaStt.ready(c)

    /** Pichhli baar sunne me galti (null = sab theek / bas chup tha). DK isse bolkar batata hai. */
    @Volatile var lastError: String? = null
        private set

    suspend fun transcribe(c: Context, pcm: ByteArray): String? {
        val audioMs = pcm.size * 1000L / (Ear.RATE * 2)
        val r = SherpaStt.transcribe(c, pcm, com.dk.assistant.stt.SttRouter.timeoutMs(audioMs))
        lastError = when (r) {
            is SherpaStt.Res.Text -> null
            is SherpaStt.Res.Slow -> "${SherpaStt.NAME} बहुत धीमा"
            is SherpaStt.Res.Fail -> r.why.takeIf { it != "आवाज़ बहुत छोटी" }
        }
        return (r as? SherpaStt.Res.Text)?.text?.trim()?.takeIf { it.isNotBlank() }
    }
}
