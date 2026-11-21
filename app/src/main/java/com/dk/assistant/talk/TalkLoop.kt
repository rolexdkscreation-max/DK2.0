package com.dk.assistant.talk

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.dk.assistant.AgentRunner
import com.dk.assistant.BargeGate
import com.dk.assistant.DKLog
import com.dk.assistant.DKState
import com.dk.assistant.Mode
import com.dk.assistant.NetProbe
import com.dk.assistant.OfflineStt
import com.dk.assistant.Quiet
import com.dk.assistant.Speaker
import com.dk.assistant.UiLang
import com.dk.assistant.stt.SttRouter
import com.dk.assistant.stt.SherpaStt
import com.dk.assistant.voice.SileroVad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * 18 नवंबर 2026 (मालिक: "सुनने का काम अलग हो और सोचने-बोलने का काम अलग"): नया "अलग-अलग" तरीका.
 *
 *   माइक (VOICE_COMMUNICATION + गूँज-रोक, 16 kHz 16-bit mono)
 *     -> [Endpointer] (Silero / RMS, रुकने के बाद [TalkPrefs.silenceMs] चुप्पी = बात पूरी)
 *     -> Sherpa-ONNX (फ़ोन पर, हिंदी + English) या Android की पहचान ([SttRouter] - Sherpa-ONNX न चले/धीमा = अपने-आप Android)
 *     -> स्क्रीन पर आपकी बात + वही text दिमाग़ को ([AgentRunner.run] - वही Gemini key / settings, जवाब text में)
 *     -> जवाब [Speaker] से (Gemini की आवाज़, न चले तो फ़ोन की आवाज़ - पहले से लगी व्यवस्था)
 *     -> DK बोलते समय आप बोले = तुरंत चुप ([Speaker.pause]), आपकी बात पहचानी: असली बात = नया जवाब, गूँज = वहीं से आगे
 *     -> अपने-आप फिर सुनना. स्क्रीन पर: सुन रहा है (LISTENING) / सोच रहा है (THINKING) / बोल रहा है (SPEAKING).
 *
 * माइक पढ़ना = अलग (IO) धागा, VAD / पहचान = Dispatchers.Default. मुख्य धागे पर कोई भारी काम नहीं. कोई भी गलती = हिंदी
 * संदेश, ऐप नहीं गिरता. Gemini Live (पुराना तरीका) इससे बिल्कुल नहीं छुआ गया - Settings में जो चुना वही चलता है.
 */
class TalkLoop(private val c: Context, private val agent: AgentRunner, private val speaker: Speaker) {

    /** > 0 = इतनी देर चुप्पी (DK भी चुप) पर बातचीत खत्म ("हे डीके" / power बटन वाली बातचीत) */
    @Volatile var idleExitMs = 0L
    @Volatile var idleExited = false; private set

    private val router = SttRouter()
    private var vad: SileroVad? = null
    private var vadTried = false
    @Volatile private var lastNotice = ""
    @Volatile private var lastNoticeAt = 0L

    /** [stop] = true होते ही लौटो; [hold] = true तक माइक छोड़े रहो (म्यूट, बोलकर लिखना) */
    suspend fun run(stop: () -> Boolean, hold: () -> Boolean): String = coroutineScope {
        idleExited = false
        router.reset()
        val first = pick()
        if (first.engine == SttRouter.Engine.NONE) {
            notice(first.notice, speak = true)
            return@coroutineScope "पहचान नहीं: ${first.notice}"
        }
        if (first.notice.isNotBlank()) notice(first.notice)
        if (first.engine == SttRouter.Engine.SHERPA) launch { if (!SherpaStt.warm(c)) DKLog.d("IndicConformer: पहले से नहीं खुला - पहली बात पर फिर कोशिश") }
        withContext(Dispatchers.Default) { if (!vadTried) { vadTried = true; vad = SileroVad.open(c) } }
        DKLog.d("अलग तरीका शुरू: पहचान=${first.engine}, चुप्पी=${TalkPrefs.silenceMs(c)} ms, VAD=${if (vad != null) "Silero" else "RMS"}")
        DKState.workNow = { true }
        link(first.engine, "सुन रहा है")
        DKState.set(Mode.LISTENING)

        val ch = Channel<ShortArray>(capacity = 300, onBufferOverflow = BufferOverflow.DROP_OLDEST)   // 12 s
        val mic = launch(Dispatchers.IO) { capture(ch, hold) }
        try {
            withContext(Dispatchers.Default) { listen(ch, stop, hold) }
        } finally {
            mic.cancel()
            ch.close()
            withContext(NonCancellable) {
                vad?.reset()
                DKState.setLink("")
                if (DKState.mode == Mode.LISTENING) DKState.set(Mode.IDLE)
            }
        }
    }

    // ---------------------------------------------------------------- माइक (अलग धागा)

    private suspend fun capture(ch: Channel<ShortArray>, hold: () -> Boolean) = coroutineScope {
        var fails = 0
        while (isActive) {
            if (hold() || DKState.micMuted) { delay(300); continue }
            com.dk.assistant.wake.WakeHandoff.releaseMic()         // "हे डीके" वाला माइक पहले छोड़ो (एक समय पर एक माइक)
            val r = openMic()
            if (r == null) {
                fails++
                if (fails == 1 || fails % 10 == 0) notice("माइक नहीं खुला - शायद कोई दूसरा ऐप माइक इस्तेमाल कर रहा है. थोड़ी देर में फिर कोशिश…")
                delay(2000); continue
            }
            try {
                r.startRecording()
                if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { fails++; delay(1500); continue }
                fails = 0
                // वेक वर्ड के बाद बोली गई बात सबसे पहले (कटे नहीं)
                while (true) { val x = com.dk.assistant.wake.WakeHandoff.next() ?: break; ch.trySend(x) }
                com.dk.assistant.wake.WakeHandoff.clear()
                val buf = ShortArray(CHUNK)
                while (isActive && !hold() && !DKState.micMuted) {
                    val n = r.read(buf, 0, CHUNK)
                    if (n < 0) { DKLog.d("अलग तरीका: माइक पढ़ने में गलती $n - फिर खोल रहा हूँ"); break }
                    if (n > 0) ch.trySend(buf.copyOf(n))
                }
            } catch (e: Exception) {
                DKLog.d("अलग तरीका: माइक ${e.message}")
                delay(1000)
            } finally {
                try { r.stop() } catch (e: Exception) { Quiet.note(e) }
                try { r.release() } catch (e: Exception) { Quiet.note(e) }
            }
        }
    }

    @SuppressLint("MissingPermission")    // इजाज़त DKAccessibilityService.checkMic() पहले ही जाँच चुका (न हो = यह loop शुरू ही नहीं)
    private fun openMic(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        for (src in intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            try {
                val r = AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, CHUNK * 2 * 8))
                if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); continue }
                val sid = r.audioSessionId
                val aec = try { AcousticEchoCanceler.isAvailable() && AcousticEchoCanceler.create(sid)?.apply { enabled = true } != null } catch (_: Exception) { false }
                val ns = try { NoiseSuppressor.isAvailable() && NoiseSuppressor.create(sid)?.apply { enabled = true } != null } catch (_: Exception) { false }
                DKLog.d("अलग तरीका: माइक source=$src (7 = VOICE_COMMUNICATION), 16 kHz mono PCM16, गूँज-रोक=$aec, शोर-रोक=$ns")
                return r
            } catch (e: Exception) { DKLog.d("अलग तरीका: माइक source $src: ${e.message}") }
        }
        return null
    }

    // ---------------------------------------------------------------- सुनना -> पहचान -> दिमाग़

    private suspend fun listen(ch: Channel<ShortArray>, stop: () -> Boolean, hold: () -> Boolean): String {
        val ep = Endpointer(TalkPrefs.silenceMs(c))
        val pre = ArrayDeque<ShortArray>()
        val utt = ByteArrayOutputStream()
        var noise = 300.0
        var barge = false                  // DK को बीच में रोका (pause) - पहचान के बाद तय: नई बात या गूँज
        var lastActive = System.currentTimeMillis()
        var tick = 0
        vad?.reset()
        while (true) {
            if (stop()) return "रोका गया"
            val got = withTimeoutOrNull(500) { ch.receiveCatching() }
            if (got != null && got.isClosed) return "माइक बंद"
            val x = got?.getOrNull()
            val now = System.currentTimeMillis()
            val dkBusy = agent.isBusy || speaker.busy
            if (dkBusy) lastActive = now
            if (x == null) {
                if (idleOver(ep, dkBusy, now, lastActive)) { idleExited = true; return "चुप्पी - बातचीत खत्म" }
                continue
            }
            if (hold() || DKState.micMuted) { ep.reset(); utt.reset(); pre.clear(); continue }
            if (++tick % 50 == 0) ep.silenceMs = TalkPrefs.silenceMs(c)      // Settings में बदला = बिना रुके लागू (हर ~2 s)

            val speaking = speaker.busy && !speaker.isPaused
            if (speaking && !TalkPrefs.barge(c)) { ep.reset(); pre.clear(); utt.reset(); continue }
            ep.strict = speaking

            val rms = Endpointer.rms(x, x.size)
            val p = vad?.feed(x, x.size) ?: -1f
            val score = if (p >= 0f) p else Endpointer.rmsScore(rms, noise)
            noise = Endpointer.nextNoise(noise, rms, score >= Endpointer.OFF)
            if (!dkBusy) DKState.level = (rms / 3000.0).coerceIn(0.0, 1.0).toFloat()

            when (ep.feed(score)) {
                Endpointer.Ev.START -> {
                    utt.reset(); pre.forEach { write(utt, it) }; pre.clear(); write(utt, x)
                    lastActive = now
                    if (speaking) {
                        // बीच में बोले: DK तुरंत चुप (आवाज़ रुकती है), पूरी बात सुनकर तय करेंगे
                        withContext(Dispatchers.Main) { speaker.pause() }
                        barge = true
                        DKLog.d("अलग तरीका: DK बोलते समय आवाज़ - DK रोका, आपकी बात सुन रहा हूँ")
                    }
                    if (!agent.isBusy) DKState.set(Mode.LISTENING)
                }
                Endpointer.Ev.NONE -> {
                    if (ep.started) write(utt, x)
                    else { pre.addLast(x); while (pre.size > PREROLL) pre.removeFirst() }
                }
                Endpointer.Ev.DISCARD -> {
                    utt.reset()
                    if (barge) { withContext(Dispatchers.Main) { speaker.resume() }; barge = false }
                }
                Endpointer.Ev.END -> {
                    write(utt, x)
                    val pcm = utt.toByteArray(); utt.reset()
                    val b = barge; barge = false
                    handle(pcm, b)
                    vad?.reset()
                    lastActive = System.currentTimeMillis()
                }
            }
            // कुछ नहीं चल रहा = साफ़ "सुन रहा है"
            if (!ep.started && !agent.isBusy && !speaker.busy && DKState.mode != Mode.LISTENING) DKState.set(Mode.LISTENING)
            if (idleOver(ep, dkBusy, now, lastActive)) { idleExited = true; return "चुप्पी - बातचीत खत्म" }
        }
    }

    private fun idleOver(ep: Endpointer, dkBusy: Boolean, now: Long, lastActive: Long): Boolean =
        idleExitMs > 0 && !ep.started && !dkBusy && now - lastActive > idleExitMs

    private suspend fun handle(pcm: ByteArray, barge: Boolean) {
        val audioMs = pcm.size * 1000L / (RATE * 2)
        if (!barge && !agent.isBusy) DKState.set(Mode.THINKING)
        val (text, eng) = try { transcribe(pcm, audioMs) } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { DKLog.d("अलग तरीका: पहचान गलती ${e.message}"); null to SttRouter.Engine.NONE }
        val t = text?.trim().orEmpty()
        if (t.isBlank() || !BargeGate.realWords(t)) {
            DKLog.d("अलग तरीका: ${audioMs} ms की आवाज़ में कोई शब्द नहीं (${eng})")
            if (barge) withContext(Dispatchers.Main) { speaker.resume() }
            else if (!agent.isBusy) DKState.set(Mode.LISTENING)
            return
        }
        withContext(Dispatchers.Main) {
            if (barge || agent.isBusy) {
                val ref = speaker.currentChunk + " " + DKState.lastReply
                if (barge && BargeGate.isEcho(t, ref)) {
                    DKLog.d("अलग तरीका: यह DK की अपनी गूँज थी (\"${t.take(40)}\") - वहीं से आगे बोल रहा हूँ")
                    speaker.resume()
                } else {
                    DKLog.d("अलग तरीका: बीच में नई बात \"${t.take(60)}\" ($eng)")
                    agent.interrupt(t)
                }
            } else {
                DKLog.d("अलग तरीका: आपकी बात \"${t.take(60)}\" ($eng, ${audioMs} ms)")
                agent.run(t)            // स्क्रीन पर आपकी बात (DKState.user) + जवाब + आवाज़ - AgentRunner खुद
            }
        }
    }

    /** text + कौन-सी पहचान चली */
    private suspend fun transcribe(pcm: ByteArray, audioMs: Long): Pair<String?, SttRouter.Engine> {
        val p = pick()
        if (p.notice.isNotBlank()) notice(p.notice)
        when (p.engine) {
            SttRouter.Engine.NONE -> { notice(p.notice, speak = true); return null to p.engine }
            SttRouter.Engine.ANDROID -> { link(p.engine, "लिख रहा है"); return android(pcm) to p.engine }
            SttRouter.Engine.SHERPA -> {
                link(p.engine, "लिख रहा है")
                val r = SherpaStt.transcribe(c, pcm, SttRouter.timeoutMs(audioMs))
                when (r) {
                    is SherpaStt.Res.Text -> {
                        if (router.sherpaResult(ok = true, slow = SttRouter.slow(audioMs, r.ms))) switched()
                        link(p.engine, "सुन रहा है")
                        return r.text to p.engine
                    }
                    is SherpaStt.Res.Slow -> { if (router.sherpaResult(ok = false, slow = true)) switched() }
                    is SherpaStt.Res.Fail -> {
                        DKLog.d("IndicConformer नाकाम: ${r.why}")
                        if (router.sherpaResult(ok = false, slow = false)) switched()
                    }
                }
                // यही बात अभी Android की पहचान से (बात खोई नहीं)
                if (!OfflineStt.available(c)) return null to p.engine
                return android(pcm) to SttRouter.Engine.ANDROID
            }
        }
    }

    private suspend fun android(pcm: ByteArray): String? {
        val t = OfflineStt.transcribeHindi(c, pcm, NetProbe.online(c))
        link(pick().engine, "सुन रहा है")
        if (t == null && OfflineStt.lastProblem.isNotBlank() && OfflineStt.lastProblem != "बात समझ नहीं आई") notice("Android पहचान: ${OfflineStt.lastProblem}")
        return t
    }

    private fun switched() = notice("IndicConformer ${router.offWhy} - इस बातचीत में अब Android की पहचान से सुनूँगा", speak = false)

    private fun pick(): SttRouter.Pick = router.pick(
        prefSherpa = TalkPrefs.stt(c) == TalkPrefs.STT_SHERPA,
        sherpaReady = SherpaStt.ready(c),
        whyNot = SherpaStt.whyNot(c),
        androidOk = OfflineStt.available(c),
    )

    private fun link(e: SttRouter.Engine, what: String) {
        val name = when (e) { SttRouter.Engine.SHERPA -> "IndicConformer (फ़ोन पर)"; SttRouter.Engine.ANDROID -> "Android पहचान"; else -> "-" }
        DKState.setLink("अलग तरीका • $name • $what")
    }

    /** साफ़ हिंदी संदेश (एक ही बात 1 मिनट में एक बार) - toast + log; [speak] = DK बोलकर भी बताए */
    private fun notice(msg: String, speak: Boolean = false) {
        if (msg.isBlank()) return
        val now = System.currentTimeMillis()
        if (msg == lastNotice && now - lastNoticeAt < 60_000L) return
        lastNotice = msg; lastNoticeAt = now
        DKLog.d("अलग तरीका: $msg")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { UiLang.toast(c, msg, android.widget.Toast.LENGTH_LONG).show() } catch (e: Exception) { Quiet.note(e) }
            if (speak) DKState.reply(msg)
        }
    }

    private fun write(o: ByteArrayOutputStream, x: ShortArray) {
        val b = ByteArray(x.size * 2)
        for (i in x.indices) { val v = x[i].toInt(); b[i * 2] = (v and 0xff).toByte(); b[i * 2 + 1] = (v shr 8 and 0xff).toByte() }
        o.write(b)
    }

    companion object {
        const val RATE = 16_000
        const val CHUNK = 640             // 40 ms
        const val PREROLL = 8             // बात शुरू होने से पहले के 320 ms (पहला अक्षर न कटे)
    }
}
