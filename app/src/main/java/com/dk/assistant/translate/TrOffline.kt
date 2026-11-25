package com.dk.assistant.translate

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.dk.assistant.DKLog
import com.dk.assistant.Quiet
import java.util.concurrent.Executors

/**
 * 25 नवंबर 2026 (मालिक: "ऑफलाइन भी काम करे, मॉडल इंटरनेट से डाउनलोड हो"): DK ट्रांसलेटर का बिना-नेट रास्ता.
 * - सुनना: फ़ोन की अपनी बोली-पहचान (Android SpeechRecognizer, EXTRA_PREFER_OFFLINE) - भाषा-पैक फ़ोन में (Google → आवाज़ → ऑफ़लाइन).
 * - अनुवाद: IndicTrans2 ([TrOfflineMt], ~374 MB, Wi-Fi पर एक बार डाउनलोड, APK में कुछ नहीं).
 * - बोलना: फ़ोन की आवाज़ ([TrTts]) - हिंदी ईयरफोन में, सामने वाले की भाषा फ़ोन स्पीकर से.
 * SpeechRecognizer सिर्फ़ main thread पर; अनुवाद अलग thread पर.
 */
class TrOffline(private val c: Context, private val au: TrAudio, chosen0: String, private val cb: Cb) {
    interface Cb {
        fun status(st: TrEngine.St)
        fun line(mine: Boolean, lang: String): TrEngine.Line
        fun remove(l: TrEngine.Line)
        fun changed()
        fun note(s: String)
        fun detected(code: String)
        fun closeAsked()
    }

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "dk-tr-off").apply { isDaemon = true } }
    private val tts = TrTts(c) { cb.note(it) }
    @Volatile private var alive = false
    @Volatile private var mic = true
    @Volatile private var ptt = false
    @Volatile private var chosen = chosen0
    private var sr: SpeechRecognizer? = null
    private var onDevice = true
    private var cur: TrEngine.Line? = null
    private var curMine = false
    private var fails = 0

    /** Auto में ऑफ़लाइन पहचान के लिए एक भाषा चाहिए - मराठी (मालिक: पहले मराठी) */
    private fun hearLang(): String = if (chosen == TrLang.AUTO) "mr" else chosen

    fun start() {
        alive = true
        if (chosen == TrLang.AUTO) cb.note("ऑफ़लाइन में Auto-detect नहीं - मराठी सुन रहा हूँ (ऊपर से भाषा चुनें)")
        if (!TrOfflineMt.downloaded(c)) cb.note("ऑफ़लाइन अनुवाद मॉडल नहीं है - नीचे \"ऑफ़लाइन मॉडल डाउनलोड\" दबाएँ (Wi-Fi, ~374 MB). तब तक सिर्फ़ लिखा हुआ दिखेगा")
        else work.execute { if (!TrOfflineMt.loaded()) { cb.status(TrEngine.St.TRANSLATING); TrOfflineMt.load(c); cb.status(TrEngine.St.LISTENING) } }
        main.post { listen() }
    }

    fun stop() {
        alive = false
        main.post { kill() }
        tts.close()
        work.shutdown()
    }

    fun setLang(code: String) { chosen = code; main.post { kill(); listen() } }

    fun setMic(on: Boolean) { mic = on; main.post { if (on) listen() else kill() } }

    fun pttDown() { ptt = true; main.post { kill(); au.hindi.flush(); begin(mine = true) } }

    fun pttUp() { ptt = false; main.post { try { sr?.stopListening() } catch (e: Exception) { Quiet.note(e) } } }

    fun typed(t: String) { val l = cb.line(true, target()); l.orig = t; cb.changed(); sayMine(l, t) }

    private fun target(): String = if (chosen == TrLang.AUTO) "mr" else chosen

    // ------------------------------------------------------------ सुनना

    private fun kill() {
        try { sr?.cancel(); sr?.destroy() } catch (e: Exception) { Quiet.note(e) }
        sr = null
    }

    /** सामने वाले को सुनो (अगर कुछ बज नहीं रहा) */
    private fun listen() {
        if (!alive || !mic || ptt || sr != null) return
        if (au.partner.busy() || (au.headset == null && au.hindi.busy())) { main.postDelayed({ listen() }, 300); return }
        begin(mine = false)
    }

    private fun begin(mine: Boolean) {
        if (!alive) return
        if (!SpeechRecognizer.isRecognitionAvailable(c)) { cb.note("फ़ोन में बोली-पहचान नहीं (Google ऐप चाहिए)"); return }
        kill()
        val r = try {
            if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(c)) SpeechRecognizer.createOnDeviceSpeechRecognizer(c)
            else SpeechRecognizer.createSpeechRecognizer(c)
        } catch (e: Exception) { Quiet.note(e); null } ?: return
        sr = r; curMine = mine; cur = null
        val lang = if (mine) TrLang.HI.bcp else (TrLang.of(hearLang())?.bcp ?: "mr-IN")
        r.setRecognitionListener(Listener(mine))
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
        cb.status(TrEngine.St.LISTENING)
        try { r.startListening(i) } catch (e: Exception) { Quiet.note(e); sr = null }
    }

    private inner class Listener(val mine: Boolean) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { fails = 0 }
        override fun onBeginningOfSpeech() { /* सुन रहा है */ }
        override fun onRmsChanged(rmsdB: Float) { /* स्तर - दिखाते नहीं */ }
        override fun onBufferReceived(buffer: ByteArray?) { /* नहीं चाहिए */ }
        override fun onEndOfSpeech() { /* नतीजा onResults में */ }
        override fun onEvent(eventType: Int, params: Bundle?) { /* नहीं चाहिए */ }

        override fun onPartialResults(b: Bundle?) {
            val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (t.isEmpty()) return
            val l = cur ?: cb.line(mine, if (mine) target() else hearLang()).also { cur = it }
            l.orig = t; cb.changed()
        }

        override fun onResults(b: Bundle?) {
            val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            val l = cur; cur = null; sr?.destroy(); sr = null
            if (t.isEmpty()) { l?.let { cb.remove(it) }; cb.changed(); next(); return }
            val line = l ?: cb.line(mine, if (mine) target() else hearLang())
            line.orig = t; cb.changed()
            if (mine) sayMine(line, t) else sayPartner(line, t)
        }

        override fun onError(error: Int) {
            sr?.destroy(); sr = null
            val l = cur; cur = null
            if (l != null && l.orig.isBlank()) cb.remove(l)
            // 12 = LANGUAGE_NOT_SUPPORTED, 13 = LANGUAGE_UNAVAILABLE (API 31+)
            if ((error == 12 || error == 13) && onDevice) { onDevice = false; main.postDelayed({ if (mine && ptt) begin(true) else next() }, 200); return }
            if (error == 12 || error == 13) {
                cb.note("फ़ोन में ${TrLang.of(if (mine) TrLang.HINDI else hearLang())?.hi} की ऑफ़लाइन बोली-पहचान नहीं - Settings → Google → आवाज़ → ऑफ़लाइन भाषा डाउनलोड करें")
                fails += 3
            }
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { cb.note("माइक की अनुमति दें"); return }
            fails++
            main.postDelayed({ next() }, if (fails > 6) 3000L else 250L)
        }
    }

    private fun next() { if (alive && !ptt) listen() }

    // ------------------------------------------------------------ अनुवाद + बोलना

    private fun sayPartner(l: TrEngine.Line, t: String) {
        // Language filter: मेरी हिंदी = छोड़ो. ऑफ़लाइन पहचान चुनी भाषा में ही होती है - देवनागरी बराबरी = चुनी भाषा
        when (TrLang.verdict(chosen, t, true)) {
            TrLang.Verdict.DROP -> { cb.remove(l); cb.changed(); next(); return }
            TrLang.Verdict.UNSURE -> { l.note = TrPrompt.NOT_UNDERSTOOD; cb.note(TrPrompt.NOT_UNDERSTOOD); cb.changed(); next(); return }
            else -> Unit
        }
        val g = TrLang.guess(t)
        val src = if (chosen != TrLang.AUTO) chosen else if (g.isEmpty() || g == TrLang.UNKNOWN || g == TrLang.HINDI) hearLang() else g
        cb.detected(src)
        cb.status(TrEngine.St.TRANSLATING)
        work.execute {
            val tr = mt(t, src, TrLang.HINDI)
            if (tr == null) { l.note = "ऑफ़लाइन अनुवाद मॉडल नहीं"; cb.changed(); main.post { next() }; return@execute }
            l.tr = tr; cb.changed()
            cb.status(TrEngine.St.SPEAKING)
            tts.say(tr, TrLang.HI.bcp, au.hindi)
            main.postDelayed({ waitThen { cb.status(TrEngine.St.LISTENING); next() } }, 400)
        }
    }

    private fun sayMine(l: TrEngine.Line, t: String) {
        if (TrCommands.parse(t)?.action?.optBoolean("on", true) == false) { cb.closeAsked(); return }
        val tgt = target()
        if (!TrLang.offlineTo(tgt)) {
            l.note = "ऑफ़लाइन में हिंदी → ${TrLang.of(tgt)?.hi} नहीं (नेट चाहिए)"; cb.note(l.note); cb.changed(); main.post { next() }; return
        }
        cb.status(TrEngine.St.TRANSLATING)
        work.execute {
            val tr = mt(t, TrLang.HINDI, tgt)
            if (tr == null) { l.note = "ऑफ़लाइन अनुवाद मॉडल नहीं"; cb.changed(); main.post { next() }; return@execute }
            l.tr = tr; l.read = TrLang.readInHindi(tr); cb.changed()
            cb.status(TrEngine.St.SPEAKING)
            tts.say(tr, TrLang.of(tgt)?.bcp ?: "mr-IN", au.partner)
            main.postDelayed({ waitThen { cb.status(TrEngine.St.LISTENING); next() } }, 400)
        }
    }

    /** आवाज़ बजना खत्म + 0.3 s, फिर */
    private fun waitThen(r: () -> Unit) {
        if (!alive) return
        if (tts.pending() || au.partner.busy() || au.hindi.busy()) { main.postDelayed({ waitThen(r) }, 200); return }
        main.postDelayed({ if (alive) r() }, 300)
    }

    private fun mt(t: String, from: String, to: String): String? {
        if (!TrOfflineMt.downloaded(c)) return null
        if (!TrOfflineMt.loaded() && !TrOfflineMt.load(c)) return null
        if (!TrLang.offlineFrom(from)) return null
        return try { TrOfflineMt.translate(t, from, to) } catch (e: Exception) { DKLog.d("Translator: ऑफ़लाइन अनुवाद गड़बड़: ${e.message}"); null }
    }
}
