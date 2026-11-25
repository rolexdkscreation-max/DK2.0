package com.dk.assistant

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.dk.assistant.stt.SherpaStt
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 24 नवंबर 2026 (मालिक: "कॉल बजते समय 'डीके कॉल उठा लो' बोलूँ तो कभी-कभी नहीं सुनता. घंटी बजे तो पहली प्राथमिकता मेरी आवाज़,
 * तुरंत उठे, फिर माइक कॉल को. DK लाइव न जुड़ा हो तब भी, अनजान नंबर पर भी.")
 *
 * जड़ (पहले):
 *  - DKAccessibilityCalls.kt `onIncomingCall`: एलान बंद हो और Live न जुड़ा हो = कुछ ख़ास नहीं, घंटी पूरी आवाज़ में; आम सुनने वाला
 *    (cloud STT / Google) घंटी के शोर में बात खो देता.
 *  - Google माइक वाले तरीके में एलान ("…का कॉल आ रहा है, उठाऊँ या काट दूँ?") पूरा होने तक माइक बंद
 *    (`if (!callBargeEar()) announce.join()`) - उसी बीच बोला "डीके कॉल उठा लो" अनसुना.
 *  - हर बात के बाद नया STT सत्र (बीप / नेट / देरी) - बीच के शब्द छूटते.
 *
 * अब: घंटी बजते ही (Live की बात करने वाली लाइन न हो तो) यह फ़ोन पर ही (Sherpa-ONNX, बिना इंटरनेट) लगातार सुनता है - एलान के साथ-साथ.
 * हर ~0.1 s पर अब तक के शब्द [CallReply.instant] से जाँचे जाते हैं; "उठा लो" पूरा आते ही माइक छोड़कर तुरंत कॉल उठती है
 * (ताकि माइक कॉल को मिले). एलान के बीच (DK बोल रहा) सिर्फ़ DK के नाम वाली बात - गूँज से कॉल न उठे/कटे. काटना सिर्फ़ पक्की
 * (पूरी) बात पर. मॉडल न हो / माइक न खुले = पुराना रास्ता, पहले जैसा.
 */
internal class CallEar(private val svc: DKAccessibilityService) {
    @Volatile var running = false
        private set
    private var th: Thread? = null

    /** शुरू हुआ? false = पुराना रास्ता चलाओ */
    fun start(): Boolean {
        if (running) return true
        val ok = try {
            CallEarRules.use(
                liveTalking = DKState.live && svc.gemLive.canTalk(),
                sherpaReady = SherpaStt.ready(svc),
                micOk = svc.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                muted = DKState.micMuted,
            )
        } catch (_: Throwable) { false }
        if (!ok) return false
        running = true
        th = Thread({ loop() }, "dk-call-ear").also { it.start() }
        DKLog.d("कॉल: फ़ोन का कान चालू (${SherpaStt.NAME}) - घंटी के समय आपकी आवाज़ पहले")
        return true
    }

    fun stop() { running = false }

    private fun keepGoing(): Boolean = running && svc.callRinging() && !(DKState.live && svc.gemLive.canTalk())

    @SuppressLint("MissingPermission")
    private fun loop() {
        val s = SherpaStt.Stream.open(svc)
        if (s == null) { running = false; DKLog.d("कॉल: ${SherpaStt.NAME} नहीं खुला - पुराना सुनना"); return }
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, READ * 20))
        } catch (e: Exception) { DKLog.d("कॉल कान माइक: ${e.message}"); null }
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
            try { r?.release() } catch (e: Exception) { Quiet.note(e) }
            s.close(); running = false; return
        }
        val frame = ShortArray(READ)
        var last = ""
        var act: String? = null
        var said = ""
        var fullText: String? = null
        try {
            r.startRecording()
            while (keepGoing()) {
                val n = r.read(frame, 0, frame.size)
                if (n <= 0) continue
                val t = s.feed(FloatArray(n) { frame[it] / 32768f })
                val ep = s.endpoint()
                val text = if (ep) s.text() else t
                if (text.isNotBlank() && (text != last || ep)) {
                    last = text
                    val talking = svc.callAnnouncing || (svc.speaker.busy)
                    val names = Prefs.wakeWords(svc)
                    // "काट दो और मैसेज भेजो कि…" = पहले पूरी बात (सिर्फ़ काटना नहीं) - पुराना पूरा जवाब
                    val cmd = CallCmd.parse(text) != null
                    val a = if (cmd) null else CallEarRules.decide(text, ep, talking, names)
                    if (a != null) { act = a; said = text; break }
                    // पूरी बात, फ़ौरन वाला काम नहीं ("कौन है", "घंटी धीमी", मैसेज वाला) = पुराना पूरा जवाब
                    if (ep && CallEarRules.full(text, talking, names, cmd)) { fullText = text; break }
                }
                if (ep) { s.reset(); last = "" }
            }
        } catch (e: Throwable) {
            DKLog.d("कॉल कान (${SherpaStt.NAME}): ${e.javaClass.simpleName} ${e.message?.take(60)}")
        } finally {
            // पहले माइक छोड़ो - कॉल उठते ही माइक कॉल का
            try { r.stop() } catch (e: Exception) { Quiet.note(e) }
            try { r.release() } catch (e: Exception) { Quiet.note(e) }
            s.close()
        }
        val a = act; val full = fullText
        if (a != null && svc.callRinging()) svc.scope.launch {
            DKLog.d("कॉल: फ़ोन के कान ने सुना \"${said.take(30)}\" -> $a")
            DKState.user(said)
            when (a) {
                CallReply.ANSWER, CallReply.ANSWER_SPEAKER -> svc.answerByVoice(a == CallReply.ANSWER_SPEAKER, "फ़ोन का कान")
                CallReply.REJECT -> {
                    svc.hushForCall(); svc.callPending = false
                    svc.agent.runQuick(QuickCommands.Quick(JSONObject().put("type", "end_call"), "कॉल काट दिया"))
                }
            }
        } else if (full != null && svc.callRinging()) svc.scope.launch {
            val done = try { svc.handleCallReply(full) } catch (e: Exception) { DKLog.d("कॉल जवाब: ${e.message}"); false }
            running = false
            // काम नहीं हुआ (जैसे "कौन है") और घंटी अभी बज रही = फिर सुनो
            if (!done && svc.callRinging()) start()
            return@launch
        }
        if (full == null) running = false
    }

    companion object {
        private const val RATE = 16_000
        /** 100 ms */
        private const val READ = RATE / 10
    }
}

/** [CallEar] के नियम (JUnit, बिना Android) */
object CallEarRules {
    /** फ़ोन का कान चलाएँ? Live बात कर रहा = उसी का माइक (वहाँ अलग रास्ता); मॉडल / माइक नहीं = पुराना रास्ता */
    fun use(liveTalking: Boolean, sherpaReady: Boolean, micOk: Boolean, muted: Boolean): Boolean =
        !liveTalking && sherpaReady && micOk && !muted

    /** अब तक के शब्द -> तुरंत वाला काम (उठाओ / स्पीकर पर / पक्की बात पर काटो), नहीं तो null */
    fun decide(text: String, final: Boolean, dkTalking: Boolean, names: List<String>): String? =
        CallReply.instant(text, final, dkTalking, names)

    /** पूरी बात है, तुरंत वाला काम नहीं बना, पर कॉल से जुड़ी हो सकती है = पुराना पूरा जवाब ([DKAccessibilityService.handleCallReply]) */
    fun full(text: String, dkTalking: Boolean, names: List<String>, callCmd: Boolean = false): Boolean {
        if (text.isBlank() || CallReply.askEcho(text)) return false
        if (dkTalking && !CallReply.bargeOk(text, names)) return false
        if (callCmd) return true
        val p = CallReply.parse(text)
        if (p != null) return true
        val l = text.lowercase()
        return QUIET.any { l.contains(it) }
    }

    /** घंटी धीमी / चुप वाली बात (शोर से बने बेमतलब शब्द नहीं) */
    private val QUIET = listOf("चुप", "साइलेंट", "silent", "रिंगटोन", "ringtone", "घंटी", "आवाज़ कम", "आवाज कम", "धीरे")
}
