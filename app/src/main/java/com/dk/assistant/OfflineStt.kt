package com.dk.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * नवंबर 2026 (मालिक: ऑफलाइन में "DK फोन लॉक करो / कहाँ हो" नहीं सुन रहा):
 * DK के अपने माइक ([Ear]) से पकड़ी बात को फ़ोन की on-device पहचान को सीधे देते हैं (EXTRA_AUDIO_SOURCE, Android 13+) -
 * फ़ोन का पहचान वाला सिस्टम अपना माइक नहीं खोलता, इसलिए हर बार चालू/बंद वाली बीप नहीं. नेट की ज़रूरत नहीं.
 */
object OfflineStt {
    private const val TIMEOUT_MS = 7_000L

    fun available(c: Context): Boolean = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isRecognitionAvailable(c)

    /** आख़िरी दिक्कत (डिबग लॉग / Settings में दिखाने लायक) */
    @Volatile var lastProblem: String = ""
        private set

    private const val PREP_PREF = "offline_stt_prep_at"
    private const val PREP_EVERY_MS = 12 * 60 * 60 * 1000L

    /**
     * नवंबर 2026 (मालिक: "ऑफलाइन में लॉक / अनलॉक नहीं सुनता"): जड़ - बिना नेट पहचान के लिए फ़ोन में हिंदी का on-device
     * मॉडल चाहिए. वह न हो तो पहचान ERROR_LANGUAGE_UNAVAILABLE देती थी और DK चुपचाप सब अनसुना कर देता था.
     * अब नेट रहते (दिन में ~2 बार) जाँचो, और हिंदी / अंग्रेज़ी (भारत) का मॉडल न हो तो Android से डाउनलोड करवाओ.
     */
    fun prepare(c: Context) {
        if (Build.VERSION.SDK_INT < 33) { lastProblem = "Android 13 से पुराना फ़ोन - बिना नेट पहचान नहीं (सिर्फ़ सिखाए आवाज़-नमूने)"; return }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(c)) { lastProblem = "फ़ोन में बिना-नेट पहचान नहीं (Google ऐप / Speech Services अपडेट करें)"; DKLog.d("ऑफलाइन पहचान: $lastProblem"); return }
        val now = System.currentTimeMillis()
        val sp = c.getSharedPreferences("dk_offline_stt", Context.MODE_PRIVATE)
        if (now - sp.getLong(PREP_PREF, 0L) < PREP_EVERY_MS) return
        sp.edit().putLong(PREP_PREF, now).apply()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            for (lang in listOf("hi-IN", "en-IN")) try {
                val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(c)
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                rec.checkRecognitionSupport(i, c.mainExecutor, object : android.speech.RecognitionSupportCallback {
                    override fun onSupportResult(s: android.speech.RecognitionSupport) {
                        val base = lang.substringBefore('-')
                        val has = s.installedOnDeviceLanguages.any { it.startsWith(base) }
                        val pending = s.pendingOnDeviceLanguages.any { it.startsWith(base) }
                        if (!has && !pending) {
                            try { rec.triggerModelDownload(i); DKLog.d("ऑफलाइन पहचान: $lang मॉडल डाउनलोड शुरू") } catch (e: Exception) { DKLog.d("ऑफलाइन पहचान: $lang डाउनलोड नहीं - ${e.message}") }
                        } else DKLog.d("ऑफलाइन पहचान: $lang मॉडल " + if (has) "फ़ोन में है" else "डाउनलोड हो रहा है")
                        if (base == "hi") lastProblem = if (has) "" else "हिंदी का बिना-नेट मॉडल अभी फ़ोन में नहीं (डाउनलोड हो रहा है)"
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ try { rec.destroy() } catch (_: Exception) {} }, 5000)
                    }
                    override fun onError(error: Int) {
                        DKLog.d("ऑफलाइन पहचान: $lang जाँच गलती $error")
                        try { rec.destroy() } catch (_: Exception) {}
                    }
                })
            } catch (e: Exception) { DKLog.d("ऑफलाइन पहचान: जाँच ${e.message}") }
        }
    }

    suspend fun transcribe(c: Context, pcm: ByteArray): String? {
        if (!available(c) || pcm.isEmpty()) return null
        for (lang in listOf("hi-IN", "en-IN")) {
            val r = withTimeoutOrNull(TIMEOUT_MS) { once(c, pcm, lang) }
            if (r == null) { lastProblem = "पहचान में देर ($lang)"; DKLog.d("ऑफलाइन पहचान: $lastProblem"); return null }
            if (r.text != null) { lastProblem = ""; return r.text }
            if (r.err == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || r.err == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                lastProblem = "$lang का बिना-नेट मॉडल फ़ोन में नहीं (नेट आते ही DK डाउनलोड करवाएगा)"; DKLog.d("ऑफलाइन पहचान: $lastProblem"); continue
            }
            if (r.err != 0) DKLog.d("ऑफलाइन पहचान: गलती ${r.err} ($lang)")
            return null
        }
        return null
    }

    private class Res(val text: String?, val err: Int)

    private suspend fun once(c: Context, pcm: ByteArray, lang: String): Res = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < 33) return@withContext Res(null, -1)
        val pipe = ParcelFileDescriptor.createPipe()
        val rec = if (SpeechRecognizer.isOnDeviceRecognitionAvailable(c)) SpeechRecognizer.createOnDeviceSpeechRecognizer(c)
            else SpeechRecognizer.createSpeechRecognizer(c)
        try {
            suspendCancellableCoroutine { cont ->
                var partial: String? = null
                fun done(r: Res) { if (cont.isActive) cont.resume(r) }
                rec.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(b: Bundle?) {
                        val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() } ?: partial
                        done(Res(t, 0))
                    }
                    override fun onPartialResults(b: Bundle?) {
                        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { partial = it }
                    }
                    override fun onError(e: Int) { done(Res(partial, e)) }
                    override fun onReadyForSpeech(p: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(t: Int, p: Bundle?) {}
                })
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, Ear.RATE)
                rec.startListening(i)
                // बात + थोड़ी चुप्पी (पहचान को "बात ख़त्म" पता चले), फिर पाइप बंद
                Thread {
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { o ->
                            o.write(pcm); o.write(ByteArray(Ear.RATE * 2 * 6 / 10)); o.flush()
                        }
                    } catch (e: Exception) { DKLog.d("ऑफलाइन पहचान: पाइप ${e.message}") }
                }.start()
                cont.invokeOnCancellation { try { rec.cancel() } catch (_: Exception) {} }
            }
        } catch (e: Exception) {
            DKLog.d("ऑफलाइन पहचान: ${e.message}"); Res(null, -1)
        } finally {
            try { rec.destroy() } catch (_: Exception) {}
            try { pipe[0].close() } catch (_: Exception) {}
        }
    }
}
