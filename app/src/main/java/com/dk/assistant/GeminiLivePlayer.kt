package com.dk.assistant

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.dk.assistant.GeminiLive.Companion.OUT_RATE
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

// GeminiLive का हिस्सा: DK की आवाज़ बजाना (AudioTrack player, रोकना, आवाज़ का स्तर).
// यह code पहले GeminiLive.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state GeminiLive में)।

internal fun GeminiLive.startPlayer() {
    stopPlayer()
    val min = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val t = AudioTrack.Builder()
        // Bluetooth = कॉल वाली आवाज़ (headset में, गूँज हटाना ठीक); नहीं तो पहले जैसा मीडिया (speaker)
        // v3.51: कॉल (घंटी/बात) के समय media mute होता है -> accessibility (CallVoice)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(CallVoice.usage(callAudioOn || svc.callRinging(), btOn, CallSpeaker.active))
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(OUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(maxOf(min * 2, OUT_RATE * 2 / 5))      // ~200ms buffer: jaldi shuru, atke bina
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    com.dk.assistant.voice.engine.VoiceTune.load(svc)
    var tuneVer = com.dk.assistant.voice.engine.VoiceTune.apply(t, -1)   // v3.80: गति / पिच (सेटिंग्स -> आवाज़)
    // v3.6: speaker पर DK की आवाज़ फ़ोन की बाकी आवाज़ से धीमी थी - थोड़ा boost (Bluetooth पर नहीं)
    spkBoost = SpeakerBoost.attach(t.audioSessionId, btOn)
    t.play()
    track = t
    log((if (btOn) "आवाज़: Bluetooth headset" else "स्पीकर" + (if (spkBoost != null) " (+${SpeakerBoost.GAIN_MB / 100} dB)" else "")) + ": $OUT_RATE Hz PCM16 mono stream • " + com.dk.assistant.voice.engine.VoiceTune.label())
    playThread = Thread {
        var speakingSet = false
        while (!Thread.currentThread().isInterrupted) {
            val b0 = try { playQ.poll(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
            val tr = track ?: break
            if (b0 == null) {
                if (speakingSet && !isPlaying()) {
                    speakingSet = false
                    if (!turnActive && DKState.mode == Mode.SPEAKING) idleMode()
                }
                continue
            }
            // v5.1: छोटा jitter buffer - जवाब के बीच नेट रुका और आवाज़ खत्म हो गई = ~250ms जमा करके फिर बजाओ
            // (टूटी-टूटी आवाज़ नहीं). जवाब का पहला टुकड़ा कभी नहीं रुकता - शुरू में कोई देरी नहीं
            val bRaw = if (LiveRace.Jitter.underrun(turnPlayedBytes > 0, turnActive, System.currentTimeMillis(), playEnd)) {
                val g0 = gen; val st = System.currentTimeMillis()
                val parts = arrayListOf(b0); var ms = b0.size * 1000 / (OUT_RATE * 2)
                while (g0 == gen && LiveRace.Jitter.keepFilling(ms, System.currentTimeMillis() - st)) {
                    val x = try { playQ.poll(20, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null } ?: continue
                    parts.add(x); ms += x.size * 1000 / (OUT_RATE * 2)
                }
                jitterWaits++
                if (parts.size == 1) b0 else ByteArray(parts.sumOf { it.size }).also { out ->
                    var o = 0; for (p in parts) { System.arraycopy(p, 0, out, o, p.size); o += p.size } }
            } else b0
            // नवंबर 2026 (मालिक): JARVIS मोड = JARVIS वाली मशीनी परत (JarvisFx) - बजने से ठीक पहले
            val b = if (JarvisFx.enabled(svc)) JarvisFx.process(bRaw) else bRaw
            val g = gen
            // pehla tukda: haalat PEHLE "Speaking" (UI turant), awaaz likhne ke baad baaki kaam
            val first = !speakingSet || DKState.mode != Mode.SPEAKING
            if (first) { speakingSet = true; setMode(Mode.SPEAKING) }
            val now = System.currentTimeMillis()
            // v3.80: बातचीत के बीच गति / पिच बदली -> अगले टुकड़े से ही
            tuneVer = com.dk.assistant.voice.engine.VoiceTune.apply(tr, tuneVer)
            com.dk.assistant.voice.engine.VoiceLevel.schedule(b, maxOf(playEnd, now))     // v3.68: असली ज़ोर से लिप-सिंक
            playEnd = maxOf(playEnd, now) + com.dk.assistant.voice.engine.VoiceTune.playMs(b.size, OUT_RATE)
            // DK की अपनी आवाज़ कितनी तेज़ (गूँज पहचानने के लिए - BargeDetector)
            noteOutLevel(b, now)
            var off = 0
            while (off < b.size && g == gen) {
                val n = try { tr.write(b, off, b.size - off) } catch (_: Exception) { -1 }
                if (n <= 0) break
                // v3.46: आवाज़ पहले हिस्से के लिखते ही शुरू हो जाती है - पहले पूरा टुकड़ा लिखने (buffer भरा हो तो
                // ~800ms रुकना) के बाद गिनते थे = "playback 818ms" असल में नाप की गलती थी
                if (off == 0) { val pt = System.currentTimeMillis(); timeline.play(pt); if (trace.firstChunkAt > 0 && trace.firstPlayAt == 0L) firstWriteAt = pt }
                off += n
                if (g == gen) turnPlayedBytes += n
            }
            if (trace.firstChunkAt > 0 && trace.firstPlayAt == 0L && off > 0) {
                trace.firstPlayAt = firstWriteAt.takeIf { it > 0 } ?: System.currentTimeMillis(); firstWriteAt = 0L
                // नवंबर 2026 (DKI-CCB3F8): इस बारी में फ़ोन का काम (tool) चला = देरी उस काम की, बात की नहीं - औसत में नहीं
                val withTool = toolCallAt >= trace.speechEndAt && trace.speechEndAt > 0
                trace.summary()?.let { sm ->
                    lastLatency = trace.firstPlayAt - trace.speechEndAt
                    if (!connSplit && !withTool && lastLatency in 0..60_000) com.dk.assistant.voice.engine.VoiceEngines.recordLatency(svc,
                        com.dk.assistant.voice.engine.VoiceEngines.LIVE_NATIVE, lastLatency)
                    lastBreakdown = "$sm • सबसे बड़ा हिस्सा: ${trace.bottleneck()}" + if (withTool) " ${LiveRecover.TOOL_MARK}" else ""
                    log("देरी के हिस्से: $lastBreakdown")
                }
                if (!connSplit && !withTool) LatencyBoard.record("Gemini Live (सीधी आवाज़)", trace.speechEndAt, trace.firstChunkAt, trace.firstChunkAt, trace.firstPlayAt)
                    ?.let { log("देरी: " + LatencyBoard.line(it)) }
                trace.reset()                // agli baari nayi ginti (purane aankde na chipkein)
            }
            if (first) svc.speaker.ensureAudible()      // awaaz mute to nahi (system calls - awaaz shuru hone ke baad)
        }
    }.apply { name = "dk-live-play"; isDaemon = true; start() }
}

/** Beech me toka: jo awaaz kataar me hai sab hatao, turant chup */
internal fun GeminiLive.flushPlayback() {
    gen++
    startHold.clear()                            // नवंबर 2026: आपके बोलते समय रुका पुराना जवाब भी
    playQ.clear()
    playEnd = 0L
    JarvisFx.reset()                             // टोका = पुरानी JARVIS गूँज भी नहीं
    track?.let { try { it.pause(); it.flush(); it.play() } catch (_: Exception) {} }
    pipeStop()                                   // v3.68: आवाज़ इंजन की कतार + चलती आवाज़ भी तुरंत
    dropLiveKeep()                               // नवंबर 2026: टोका = रखी हुई Live आवाज़ भी नहीं बजेगी
    com.dk.assistant.voice.engine.VoiceLevel.clear()
}

/** बजने वाले टुकड़े की तेज़ी (हर चौथा sample - सस्ता) */
internal fun GeminiLive.noteOutLevel(b: ByteArray, now: Long) {
    var sq = 0.0; var k = 0; var i = 0
    while (i + 1 < b.size) { val v = (b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8); sq += (v * v).toDouble(); k++; i += 8 }
    if (k > 0) synchronized(outLevels) { outLevels.addLast(now to sqrt(sq / k)); while (outLevels.size > 40) outLevels.removeFirst() }
}

internal fun GeminiLive.stopPlayer() {
    playThread?.interrupt(); playThread = null
    playQ.clear(); playEnd = 0L
    pipeStop(); com.dk.assistant.voice.engine.VoiceLevel.clear()
    track?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
    track = null
    SpeakerBoost.release(spkBoost); spkBoost = null
}
