package com.dk.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.dk.assistant.GeminiLive.Companion.ASK_KEEP_MS
import com.dk.assistant.GeminiLive.Companion.CHUNK_MS
import com.dk.assistant.GeminiLive.Companion.CLIENT_END_MS
import com.dk.assistant.GeminiLive.Companion.CHUNK_SAMPLES
import com.dk.assistant.GeminiLive.Companion.HOLD_END_MS
import com.dk.assistant.GeminiLive.Companion.HOLD_FINAL_MS
import com.dk.assistant.GeminiLive.Companion.HOLD_MAX_MS
import com.dk.assistant.GeminiLive.Companion.HOLD_QUIET_MS
import com.dk.assistant.GeminiLive.Companion.HOLD_STALE_MS
import com.dk.assistant.GeminiLive.Companion.IN_RATE
import com.dk.assistant.GeminiLive.Companion.PREROLL_CHUNKS
import com.dk.assistant.GeminiLive.Fatal
import kotlin.math.sqrt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// GeminiLive का हिस्सा: माइक: खोलना और लगातार सुनने वाला loop (40ms के टुकड़े Gemini तक).
// यह code पहले GeminiLive.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state GeminiLive में)।

@SuppressLint("MissingPermission")
internal fun GeminiLive.openMic(): AudioRecord? {
    val min = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (min <= 0) { log("यह फ़ोन 16kHz PCM16 mono नहीं देता (min=$min)"); return null }
    // VOICE_COMMUNICATION = phone ka echo-cancel (DK ki apni awaaz mic me kam), na chale to baaki.
    // (#158 ने DEFAULT पहले रखा था "error 13" के लिए - पर 13 SpeechRecognizer की भाषा-गलती है, AudioRecord की नहीं; MicLang.kt)
    for (src in intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT)) {
        try {
            val r = AudioRecord(src, IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, CHUNK_SAMPLES * 2 * 6))
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); continue }
            // Bluetooth जुड़ा = headset का माइक (कॉल वाला तरीका)
            if (btOn) BtAudio.btInput?.let { d -> try { r.setPreferredDevice(d) } catch (_: Exception) {} }
            val sid = r.audioSessionId
            val aec = try { AcousticEchoCanceler.isAvailable() && AcousticEchoCanceler.create(sid)?.apply { enabled = true } != null } catch (_: Exception) { false }
            val ns = try { NoiseSuppressor.isAvailable() && NoiseSuppressor.create(sid)?.apply { enabled = true } != null } catch (_: Exception) { false }
            val agc = try { AutomaticGainControl.isAvailable() && AutomaticGainControl.create(sid)?.apply { enabled = true } != null } catch (_: Exception) { false }
            aecOn = aec
            log("माइक: ${if (btOn) "Bluetooth headset" else "फ़ोन"} • ${r.sampleRate} Hz, mono, PCM16, source=$src, AEC=$aec NS=$ns AGC=$agc, buffer=${min}B" +
                " • शोर-गेट चालू: Gemini को सिर्फ़ यूज़र की आवाज़; DK बोलते समय सिर्फ़ पक्के शब्दों पर रुकेगा")
            return r
        } catch (e: Exception) { log("माइक source $src: ${e.message}") }
    }
    return null
}

internal suspend fun GeminiLive.micLoop() = coroutineScope {
    var r = openMic() ?: throw Fatal("माइक नहीं खुला (कोई दूसरा ऐप माइक इस्तेमाल कर रहा है?)", recoverable = true)
    try {
        r.startRecording()
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw Fatal("माइक रिकॉर्डिंग शुरू नहीं हुई", recoverable = true)
        val buf = ShortArray(CHUNK_SAMPLES)
        val bytes = ByteArray(CHUNK_SAMPLES * 2)
        val zeros = ByteArray(CHUNK_SAMPLES * 2)
        // DK bolte samay (gate mode) ki pichhli 320ms asli awaaz - user ki pakdi awaaz ka shuru ka hissa na kate
        val preroll = ArrayDeque<ByteArray>()
        // नवंबर 2026: पूरी बोली बात - बिना इंटरनेट वाली आवाज़-कमांड (लॉक / अनलॉक / किधर हो) के लिए, Live से अलग
        val cmdBuf = java.io.ByteArrayOutputStream()
        var last = 0L; var zeroRun = 0; var errRun = 0
        loudMs = 0L
        while (isActive && !stopped) {
            // इनपुट बार में बोलकर लिखना: माइक छोड़ो (Google पहचान को आवाज़ मिले), खत्म होते ही वापस
            if (DKMic.dictating) {
                try { r.stop() } catch (_: Exception) {}
                try { r.release() } catch (_: Exception) {}
                log("माइक छोड़ा: आप इनपुट बार में बोलकर लिख रहे हैं")
                while (isActive && !stopped && DKMic.dictating) delay(200)
                r = openMic() ?: throw Fatal("माइक दोबारा नहीं खुला (बोलकर लिखने के बाद)", recoverable = true)
                r.startRecording(); last = 0L; errRun = 0
                log("माइक वापस: बोलकर लिखना खत्म")
                continue
            }
            // v3.59: म्यूट बटन = माइक सच में बंद (Android का हरा माइक-निशान भी हटे)।
            // जड़: पहले माइक खुला रहता था और सिर्फ़ Gemini को खाली आवाज़ जाती थी - पर caption वाले को असली
            // आवाज़ जाती थी, बीच में टोकना (barge) भी चलता था, और फ़ोन "माइक चालू" ही दिखाता था।
            if (DKState.micMuted) {
                try { r.stop() } catch (_: Exception) {}
                try { r.release() } catch (_: Exception) {}
                log("माइक म्यूट: माइक छोड़ दिया - अनम्यूट तक कुछ नहीं सुनूँगा")
                synchronized(sendLock) { try { ws?.tx("{\"realtimeInput\":{\"audioStreamEnd\":true}}") } catch (_: Exception) {} }
                try { caption?.endOfSpeech() } catch (_: Exception) {}
                synchronized(bargeBuf) { bargeBuf.clear() }
                preroll.clear(); turnDet.reset(); DKState.level = 0f
                while (isActive && !stopped && DKState.micMuted) delay(150)
                if (!isActive || stopped) break
                r = openMic() ?: throw Fatal("माइक दोबारा नहीं खुला (अनम्यूट के बाद)", recoverable = true)
                r.startRecording(); last = 0L; errRun = 0
                quietSince = System.currentTimeMillis()
                log("माइक अनम्यूट: फिर सुन रहा हूँ")
                continue
            }
            // v3.39: Bluetooth जुड़ा/हटा -> माइक नए रास्ते पर दोबारा खोलो
            if (routeChanged) {
                routeChanged = false
                try { r.stop() } catch (_: Exception) {}; try { r.release() } catch (_: Exception) {}
                r = openMic() ?: throw Fatal("माइक दोबारा नहीं खुला (Bluetooth बदला)", recoverable = true)
                r.startRecording()
                log("माइक नए रास्ते पर: " + if (btOn) "Bluetooth headset" else "फ़ोन का माइक")
            }
            // poora 40ms tukda padho (beech ka khaali hissa = gap). Koi jama nahi: har 40ms turant bhejo
            var got = 0
            while (got < CHUNK_SAMPLES && isActive) {
                val n = r.read(buf, got, CHUNK_SAMPLES - got)
                if (n < 0) { readErr++; errRun++; break }
                got += n
            }
            if (got < CHUNK_SAMPLES) {
                log("माइक पढ़ने में गलती ($errRun)")
                if (errRun >= 5) {           // mic atak gaya -> dobara kholo
                    try { r.stop() } catch (_: Exception) {}; r.release()
                    delay(300)
                    r = openMic() ?: throw Fatal("माइक दोबारा नहीं खुला", recoverable = true)
                    r.startRecording(); errRun = 0
                }
                continue
            }
            errRun = 0
            val now = System.currentTimeMillis()
            // नवंबर 2026 (DKI-4144DC "मिक गैप: 10" - वजह पक्की नहीं थी): गैप के समय हालत भी लिखो (जुड़ा था? क्या चल रहा था?)
            if (last > 0 && now - last > CHUNK_MS * 3) {
                gaps++
                if (gaps <= 5 || gaps % 20 == 1L) log("माइक में ${now - last} ms का गैप (#$gaps) • " +
                    LiveStay.gapContext(ready, upSince, now, DKState.mode.name, isPlaying()))
            }
            last = now
            var sum = 0.0
            for (i in 0 until CHUNK_SAMPLES) { val s = buf[i].toInt(); sum += (s * s).toDouble() }
            val rms = sqrt(sum / CHUNK_SAMPLES)
            val playing = isPlaying()
            // dheemi awaaz tez (v3.7): DK chup ho tab hi - DK ki apni goonj ko tez nahi karna
            if (!playing) boost.apply(buf, CHUNK_SAMPLES)
            for (i in 0 until CHUNK_SAMPLES) {
                val s = buf[i].toInt()
                bytes[i * 2] = (s and 0xff).toByte(); bytes[i * 2 + 1] = (s shr 8 and 0xff).toByte()   // little-endian
            }
            if (sum == 0.0) { if (++zeroRun == 75) log("माइक से 3 सेकंड से बिल्कुल आवाज़ नहीं (कोई दूसरा ऐप माइक ले रहा है?)") } else zeroRun = 0
            val chunk = bytes.copyOf()
            if (playing) lastPlayingAt = now
            // v3.5+: HOLD (यूज़र का अगला सवाल सुन रहे) - DK का जवाब ख़त्म हो जाए तब भी सवाल पूरा होने तक इसी रास्ते
            if (playing || bargeGate.state == BargeGate.State.HOLD) {
                // ---- DK bol raha hai: mic band NAHI, har 40ms suno. Par Gemini ko sirf PAKKE user shabd.
                quietSince = now
                turnDet.reset()
                if (guardHeld) { guardHeld = false; guardEndAt = 0L; guardBuf.clear() }   // v3.53: DK बोलने लगा - रोकी आवाज़ छोड़ो
                guardPass = false
                if (bargeDet.ambient == 0.0) bargeDet.ambient = turnDet.noise
                when (bargeGate.state) {
                    BargeGate.State.IDLE -> {
                        if (bargeDet.feed(rms, now, outLevel(now))) {
                            // SHAK: tez awaaz. DK ki awaaz halki (rokna nahi), caption model ko suno - shabd hain?
                            bargeGate.suspect(now); barging = true; loudMs = 0L; overEchoMs = 0L
                            // मालिक (नवंबर 2026): DK की आवाज़ अपने-आप धीमी-तेज़ नहीं - शक के समय भी पूरी (रोकना पक्के शब्दों पर ही)
                            if (VolumeRule.AUTO_CHANGE) track?.let { try { it.setVolume(SpeakerBoost.SUSPECT_VOLUME) } catch (_: Exception) {} }
                            log("तेज़ आवाज़ (rms=${rms.toInt()}, गूँज=${bargeDet.echoLevel().toInt()}, शोर=${bargeDet.ambient.toInt()}) - यूज़र है? शब्द सुन रहा हूँ (DK की आवाज़ नहीं बदली)")
                            synchronized(bargeBuf) { bargeBuf.clear(); bargeBuf.addAll(preroll); bargeBuf.addLast(chunk) }
                            preroll.forEach { capSend(it) }; preroll.clear()
                            capSend(chunk)
                        } else {
                            preroll.addLast(chunk); while (preroll.size > PREROLL_CHUNKS) preroll.removeFirst()
                            capSend(zeros, silent = true)
                        }
                        sendAudio(zeros, silent = true); gatedChunks++
                    }
                    BargeGate.State.TENTATIVE -> {
                        loudMs = if (rms > maxOf(bargeDet.ambient * 2.5, 800.0)) loudMs + CHUNK_MS else 0L
                        overEchoMs = if (rms > maxOf(bargeDet.ambient * 2.5, bargeDet.echoLevel() * 1.6, 900.0)) overEchoMs + CHUNK_MS else 0L
                        synchronized(bargeBuf) { bargeBuf.addLast(chunk); while (bargeBuf.size > 50) bargeBuf.removeFirst() }
                        capSend(chunk)
                        sendAudio(zeros, silent = true); gatedChunks++
                        if (bargeGate.expired(now, captionOn(), loudMs, overEchoMs)) {
                            // shabd nahi aaye = shor/TV/log. DK ka jawab poori awaaz me chalta rahe
                            falseAlarms++
                            log("शब्द नहीं मिले - पीछे का शोर था, जवाब जारी (#$falseAlarms)")
                            track?.let { try { it.setVolume(1f) } catch (_: Exception) {} }
                            bargeDet.rearm(rms); barging = false
                            synchronized(bargeBuf) { bargeBuf.clear() }
                        } else if (bargeGate.state == BargeGate.State.CONFIRMED) confirmBarge(
                            if (!captionOn()) "लगातार आवाज़ (caption बंद)" else if (bargeGate.captionSlow) "लगातार आवाज़ (caption धीमा)" else "लगातार आवाज़ (caption चुप)")
                    }
                    BargeGate.State.CONFIRMED -> { sendAudio(chunk); capSend(chunk) }
                    BargeGate.State.HOLD -> {
                        // सिर्फ़ caption सुने (Gemini को खामोशी - जवाब चलता रहे). "रुको" सुनते ही onCaption रोकेगा
                        synchronized(bargeBuf) { bargeBuf.addLast(chunk); while (bargeBuf.size > 50) bargeBuf.removeFirst() }
                        capSend(chunk)
                        sendAudio(zeros, silent = true); gatedChunks++
                        if (rms > maxOf(bargeDet.ambient * 2.5, bargeDet.echoLevel() * 1.5, 800.0)) holdLoudAt = now
                        val capQuiet = now - holdCapAt; val loudQuiet = now - holdLoudAt
                        val done = (holdFinal && capQuiet >= HOLD_FINAL_MS && loudQuiet >= HOLD_FINAL_MS) ||
                            (capQuiet >= HOLD_END_MS && loudQuiet >= HOLD_QUIET_MS) || capQuiet >= HOLD_STALE_MS ||
                            now - bargeGate.since >= HOLD_MAX_MS
                        if (done) finishHold(now)
                    }
                }
            } else {
                // ---- DK chup: phone par hi "bolna shuru / khatam" + SHOR-GATE:
                // Gemini/caption ko sirf user ki awaaz (320ms pehle se); baaki samay khaamoshi.
                // (Peeche ka lagatar shor bhejne par server "user abhi bol raha" maan kar jawab rokta tha.)
                if (barging) bargeUntil = now + 1500
                barging = false; bargeDet.reset(); bargeDet.ambient = 0.0
                if (bargeGate.state != BargeGate.State.IDLE) { bargeGate.reset(); track?.let { try { it.setVolume(1f) } catch (_: Exception) {} } }
                // v3.5+: जवाब पूरा, DK चुप -> बीच में पूछा सवाल अब (उसका भी जवाब)
                if (!askQueue.isEmpty()) {
                    if (now - askAt > ASK_KEEP_MS) { askQueue.clear(); log("बीच में पूछा सवाल बहुत पुराना हो गया - छोड़ा") }
                    else if (ready && ws != null && !turnActive && !muteTurn && AskQueue.readyToSend(isPlaying(), generating,
                            toolJobs.isNotEmpty(), turnDet.inSpeech, false, now - lastPlayingAt)) flushAsks(now)
                }
                if (DKState.mode == Mode.LISTENING) DKState.level = (rms / 3500.0).toFloat().coerceIn(0f, 1f)
                if (now - ownerCfgAt > 3_000) refreshOwnerCfg(now)
                // नवंबर 2026 (Smart turn): "बात खत्म" की चुप्पी तय नहीं - caption देखकर हर chunk पर (पूरा वाक्य 0.36s,
                // "और / to / for…" पर 1.1s, caption पीछे हो तो 0.7s तक, आपकी आदत के ठहराव से थोड़ा ज़्यादा). Hybrid VAD:
                // यही फ़ैसला audioStreamEnd भेजता है, server का silenceDurationMs सिर्फ़ बचाव ([SERVER_SILENCE_MS]).
                val capHeard = turnDet.inSpeech && (caption?.textAt ?: 0L) >= turnDet.lastTalkAt
                turnDet.endNeedMs = if (turnDet.inSpeech && !guardHeld)
                    TurnEnd.adaptiveEndMs(caption?.text().orEmpty(), caption?.textAt ?: 0L, turnDet.lastTalkAt,
                        turnDet.speechMsNow, turnDet.typicalPauseMs, captionOn(), CLIENT_END_MS, now) else 0
                var ev = turnDet.feed(rms, now)
                // v3.66 (तेज़ी): वाक्य साफ़ पूरा + 0.36s चुप + caption 0.2s से नहीं बदला = अभी "बात खत्म" (0.55s इंतज़ार नहीं)
                // नवंबर 2026: + caption आपकी आख़िरी आवाज़ के बाद बदला हो (पुराना caption "Kal meeting hai" पर "…to" से पहले न कटे)
                if (ev == TurnDetector.Event.NONE && turnDet.inSpeech && turnDet.silentMs > 0 && !guardHeld && captionOn()) {
                    val cap = caption?.text().orEmpty()
                    if (cap != capSeen) { capSeen = cap; capSeenAt = now }
                    if (turnDet.silentMs >= TurnEnd.EARLY_END_MS && turnDet.speechMsNow < TurnEnd.LONG_SPEECH_MS &&
                        now - capSeenAt >= 200 && capHeard && TurnEnd.earlyOk(cap) && turnDet.forceEnd()) {
                        ev = TurnDetector.Event.SPEECH_END; earlyEnds++; LatencyBoard.count("पूरा वाक्य: 0.36s पर बात खत्म")
                        if (earlyEnds <= 3 || earlyEnds % 20 == 0) log("वाक्य पूरा लगा - ${TurnEnd.EARLY_END_MS} ms चुप्पी पर ही बात खत्म (#$earlyEnds)")
                    } else if (TurnEnd.noiseTail(now, turnDet.lastTalkAt, caption?.textAt ?: 0L, turnDet.endNeedMs, true) && turnDet.forceEnd()) {
                        // नवंबर 2026: पंखा / TV की आवाज़ से "अभी भी बोल रहा" - पर आपकी आवाज़ और नए शब्द 1s से नहीं
                        ev = TurnDetector.Event.SPEECH_END; noiseEnds++; LatencyBoard.count("शोर के बीच बात खत्म पहचानी")
                        if (noiseEnds <= 3 || noiseEnds % 20 == 0) log("पीछे का शोर - आप ${now - turnDet.lastTalkAt} ms से चुप, caption में नए शब्द नहीं = बात खत्म (#$noiseEnds)")
                    }
                }
                pollStartHold(now)
                if (turnDet.inSpeech || generating || (turnActive && now - eosAt < 8_000)) quietSince = now
                // v3.53: फ़ोन पर दूसरे ऐप की आवाज़ (वीडियो/गाना) - हर 300ms देखो (DK की अपनी आवाज़ थमने के बाद ही भरोसा)
                if (now - mediaAt > 300) { mediaAt = now; mediaOn = Ducker.musicActive(svc) }
                // v4.1: "सिर्फ़ DK बोलने पर सुनो" चालू = हर बात रोको, caption में शुरुआत में DK हो तभी Gemini को
                // (घंटी / कॉल के ठीक बाद के कुछ सेकंड में नहीं - तब कॉल की कमांड बिना DK)
                if (now - dkOnlyAt > 3_000) { dkOnlyAt = now; dkOnlyNow = Prefs.dkOnly(svc) }
                val dkGate = dkOnlyNow && caption?.usable == true && !CallCmd.windowOpen(now)
                guardOn = !svc.callRinging() && !callAudioOn && (dkGate || PhoneAudioGuard.active(mediaOn, now - playEnd))
                if (guardPass && (ev == TurnDetector.Event.SPEECH_END || !turnDet.inSpeech)) guardPass = false
                if (guardHeld || (guardOn && !guardPass && (ev == TurnDetector.Event.SPEECH_START || turnDet.inSpeech))) {
                    if (!guardHeld) {
                        // नई आवाज़ आई, पर फ़ोन पर वीडियो चल रहा है: पहले सिर्फ़ लिखने वाले को, Gemini को नहीं
                        guardHeld = true; guardOk = false; guardEndAt = 0L; guardBuf.clear()
                        caption?.discard()
                        if (caption?.usable != true && phoneAudioIgnored == 0)
                            log("वीडियो/गाना चल रहा है और लिखने वाला (caption) बंद - DK का नाम पहचान नहीं सकता, फ़ोन की आवाज़ अनसुनी रहेगी (बात के लिए वीडियो रोकें)")
                        preroll.forEach { guardBuf.addLast(it); capSend(it) }; preroll.clear()
                    }
                    guardBuf.addLast(chunk); capSend(chunk)
                    while (guardBuf.size > PhoneAudioGuard.MAX_HOLD_CHUNKS) guardBuf.removeFirst()
                    if (ev == TurnDetector.Event.SPEECH_END) { guardEndAt = now; caption?.endOfSpeech() }
                    if (guardOk && dkOnlyNow && ownerOn && !ownerSaysOwner(guardBuf)) {
                        // v4.1: पहले "सिर्फ़ मेरी आवाज़" - DK बोला पर आवाज़ मालिक की नहीं = अनसुनी
                        othersIgnored++
                        log("DK सुना पर आवाज़ मालिक की नहीं - अनसुनी (#$othersIgnored)")
                        guardHeld = false; guardOk = false; guardEndAt = 0L; guardBuf.clear(); caption?.discard(); DKState.user("")
                        sendAudio(zeros, silent = true); gatedChunks++
                        continue
                    }
                    if (guardOk) {
                        // नाम मिला = यूज़र. रुकी आवाज़ Gemini को, फिर सामान्य रास्ता (बात चल रही या खत्म)
                        guardHeld = false; guardPass = turnDet.inSpeech && guardEndAt == 0L
                        synchronized(sendLock) { guardBuf.forEach { sendAudio(it) } }; guardBuf.clear()
                        lastAct = now; trace.reset(); replyWatch.onSpeechStart(); rescueWaits = 0; speechStartAt = now
                        if (Ducker.musicActive(svc)) Ducker.duck(svc, "Live: वीडियो के बीच DK का नाम")
                        if (DKState.mode != Mode.WORKING) setMode(Mode.LISTENING)
                        if (guardEndAt > 0L || !turnDet.inSpeech) { guardEndAt = 0L; onLocalEndOfSpeech(now) }
                        continue
                    }
                    val tooLong = guardBuf.size >= PhoneAudioGuard.MAX_HOLD_CHUNKS && turnDet.inSpeech
                    if (PhoneAudioGuard.expired(guardEndAt, now) || tooLong || (!guardOn && !turnDet.inSpeech && guardEndAt == 0L)) {
                        // नाम नहीं आया = फ़ोन से निकली आवाज़ (वीडियो की बात). छोड़ो - न जवाब, न धीमा, न स्क्रीन पर
                        phoneAudioIgnored++
                        if (phoneAudioIgnored % 10 == 1) log("फ़ोन से निकली आवाज़ (वीडियो/गाना) - DK का नाम नहीं, अनसुनी (#$phoneAudioIgnored)")
                        guardHeld = false; guardEndAt = 0L; guardBuf.clear(); caption?.discard()
                        if (tooLong) caption?.endOfSpeech()
                    }
                    sendAudio(zeros, silent = true); gatedChunks++
                    continue
                }
                // v3.39: पीछे की आवाज़ - यूज़र चुप, DK चुप, जवाब नहीं बन रहा, 3s शांत = वापस तेज़ (हर रास्ते पर)
                if (DuckWatch.shouldRelease(Ducker.active, turnDet.inSpeech, false, generating, quietSince, now) && now - duckFreedAt > 2_000) {
                    duckFreedAt = now; Ducker.releaseNow()
                    log("पीछे की आवाज़ वापस (3s से सब शांत)")
                }
                if (ev == TurnDetector.Event.SPEECH_START) {
                    cmdBuf.reset(); preroll.forEach { cmdBuf.write(it) }
                    // v3.66: नई बात - मालिक की पहचान शुरू. DK सोच/काम कर रहा हो तो पहचान तक आवाज़ रोको
                    ownerGen++; ownerResult = null; ownerHold.clear()
                    if (ownerOn) owner.start(preroll) else owner.finish()
                    ownerHolding = ownerOn && (generating || toolJobs.isNotEmpty() || (turnActive && now - eosAt < 8_000))
                    if (ownerHolding) { preroll.forEach { ownerHold.addLast(it) }; preroll.clear() }
                    else { preroll.forEach { sendAudio(it); capSend(it) }; preroll.clear() }
                }
                applyOwnerResult(now)
                val other = owner.active && owner.verdict == com.dk.assistant.voice.OwnerGate.Verdict.OTHER
                // "किधर हो" किसी की भी आवाज़ पर - इसलिए मालिक-जाँच (other) से पहले, असली आवाज़
                if ((turnDet.inSpeech || ev == TurnDetector.Event.SPEECH_END) && cmdBuf.size() < IN_RATE * 2 * 7) cmdBuf.write(chunk)
                // bolte samay + baat khatam hone tak (aakhri 500ms chup bhi) asli awaaz; warna khaamoshi
                // (khaamoshi 1s tak jaati hai, phir audioStreamEnd aur upload band - SilenceStreamGate)
                if ((turnDet.inSpeech || ev == TurnDetector.Event.SPEECH_END) && !other) {
                    if (ownerHolding) {
                        ownerHold.addLast(chunk); while (ownerHold.size > 75) ownerHold.removeFirst()
                        sendAudio(zeros, silent = true); capSend(zeros, silent = true)
                    } else { sendAudio(chunk); capSend(chunk) }
                    if (owner.active && turnDet.inSpeech && owner.add(chunk, rms)) checkOwnerAsync()
                    if (turnDet.inSpeech && nearRms.size < 400) nearRms.add(rms)      // v4.7: पास वाली आवाज़ का स्तर
                } else if (other && (turnDet.inSpeech || ev == TurnDetector.Event.SPEECH_END)) {
                    // किसी और की आवाज़: बाकी बात Gemini/लिखने वाले को नहीं
                    sendAudio(zeros, silent = true); capSend(zeros, silent = true); gatedChunks++
                } else {
                    preroll.addLast(chunk); while (preroll.size > PREROLL_CHUNKS) preroll.removeFirst()
                    sendAudio(zeros, silent = true); capSend(zeros, silent = true); gatedChunks++
                }
                when (ev) {
                    TurnDetector.Event.SPEECH_START -> {
                        nearRms.clear()
                        // नवंबर 2026: जवाब बन रहा / आने वाला पर अभी बजा नहीं और आप आगे बोले = वह जवाब आपके चुप होने तक रुके
                        if (eosAt > 0 && now - eosAt < 3_000) LatencyBoard.count("बात खत्म के 3s में आप फिर बोले (संभावित झूठा अंत)")
                        if (!dropOld && !muteTurn && (generating || turnActive || (eosAt > 0 && now - eosAt < 3_000))) startHold.begin(now)
                        lastAct = now; trace.reset(); replyWatch.onSpeechStart(); rescueWaits = 0; speechStartAt = now
                        // v3.46: गाना/वीडियो चल रहा हो तो आप बोलना शुरू करते ही धीमा (पहले शब्द पहचाने जाने के बाद ही -
                        // पर तेज़ गाने में शब्द पहचाने ही नहीं जाते थे)
                        if (Ducker.musicActive(svc)) Ducker.duck(svc, "Live: फ़ोन ने आवाज़ पकड़ी")
                        // user bolne laga -> turant Listening (Thinking/Idle se bhi; phone ka kaam chal raha ho to nahi)
                        if (DKState.mode != Mode.WORKING) setMode(Mode.LISTENING)
                    }
                    TurnDetector.Event.SPEECH_END -> {
                        liveVoiceCmd(cmdBuf.toByteArray()); cmdBuf.reset()
                        learnNear()
                        startHold.userEnded(now, BargeGate.realWords(caption?.text().orEmpty()))
                        if (owner.active) ownerEnd(now)
                        else onLocalEndOfSpeech(now)
                    }
                    else -> {}
                }
                if (muteTurn && now - muteAt > 20_000 && !generating) muteTurn = false
            }
        }
    } finally {
        try { r.stop() } catch (_: Exception) {}
        try { r.release() } catch (_: Exception) {}
        try { if (ready) ws?.tx("{\"realtimeInput\":{\"audioStreamEnd\":true}}") } catch (_: Exception) {}
        DKState.level = 0f
    }
}

/**
 * नवंबर 2026 (मालिक): Live चालू हो तब भी लॉक / अनलॉक / "किधर हो" फ़ोन पर ही ([com.dk.assistant.voice.VoiceCmds]) -
 * Gemini के जवाब का इंतज़ार नहीं. मिलान होते ही इस बात का Live वाला जवाब बंद (muteTurn), काम फ़ोन पर.
 */
internal fun GeminiLive.liveVoiceCmd(pcm: ByteArray) {
    if (pcm.size < IN_RATE * 2 / 2 || !com.dk.assistant.voice.VoiceCmds.anyOn(svc)) return
    svc.scope.launch(kotlinx.coroutines.Dispatchers.Default) {
        val hit = com.dk.assistant.voice.VoiceCmds.match(svc, pcm) ?: return@launch
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            if (hit.cmd == com.dk.assistant.voice.VoiceCmdMatch.Cmd.FOUND && !FindPhone.active) return@withContext
            muteTurn = true; muteAt = System.currentTimeMillis()
            log("आवाज़-कमांड फ़ोन पर (Live का जवाब बंद): ${hit.cmd.hi}")
            svc.runVoiceCmd(hit, "Live")
        }
    }
}
