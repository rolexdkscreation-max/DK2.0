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
    val t = buildTrack() ?: run { log("आवाज़: AudioTrack नहीं बना - अगले टुकड़े पर फिर कोशिश"); null }
    track = t
    startPlayThread()
}

/**
 * नवंबर 2026 (आवाज़ गायब होने की जड़ #4): AudioTrack बनाओ + जाँचो कि सच में बना (STATE_INITIALIZED), तभी play().
 * पहले तरीका (कॉल / Bluetooth / speaker वाला usage) न बने तो सीधा मीडिया तरीका, बड़ा buffer.
 */
internal fun GeminiLive.buildTrack(): AudioTrack? {
    val min = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val fmt = AudioFormat.Builder().setSampleRate(OUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
    // Bluetooth = कॉल वाली आवाज़ (headset में, गूँज हटाना ठीक); नहीं तो पहले जैसा मीडिया (speaker)
    // v3.51: कॉल (घंटी/बात) के समय media mute होता है -> accessibility (CallVoice)
    val usages = listOf(CallVoice.usage(callAudioOn || svc.callRinging(), btOn, CallSpeaker.active), AudioAttributes.USAGE_MEDIA).distinct()
    for ((i, u) in usages.withIndex()) {
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(u).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(min * (2 + i), OUT_RATE * 2 / 5))      // ~200ms buffer: jaldi shuru, atke bina
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) { LiveAudioDebug.w("AudioTrack बना नहीं (usage $u): ${e.javaClass.simpleName} ${e.message}"); null } ?: continue
        if (!LiveAudioGuard.canPlay(t.state)) {
            LiveAudioDebug.w("AudioTrack STATE_UNINITIALIZED (usage $u, buffer ${min * (2 + i)}) - दूसरा तरीका")
            try { t.release() } catch (_: Exception) {}
            continue
        }
        com.dk.assistant.voice.engine.VoiceTune.load(svc)
        com.dk.assistant.voice.engine.VoiceTune.apply(t, -1)   // v3.80: गति / पिच (सेटिंग्स -> आवाज़)
        // v3.6: speaker पर DK की आवाज़ फ़ोन की बाकी आवाज़ से धीमी थी - थोड़ा boost (Bluetooth पर नहीं)
        SpeakerBoost.release(spkBoost)
        spkBoost = SpeakerBoost.attach(t.audioSessionId, btOn)
        try { t.play() } catch (e: Exception) { LiveAudioDebug.w("AudioTrack.play() गलती: ${e.message}"); try { t.release() } catch (_: Exception) {}; continue }
        log((if (btOn) "आवाज़: Bluetooth headset" else "स्पीकर" + (if (spkBoost != null) " (+${SpeakerBoost.GAIN_MB / 100} dB)" else "")) + ": $OUT_RATE Hz PCM16 mono stream • " +
            com.dk.assistant.voice.engine.VoiceTune.label() + if (i > 0) " • (मीडिया वाला तरीका)" else "")
        LiveAudioDebug.d("AudioTrack तैयार: $OUT_RATE Hz mono PCM16, usage $u, session ${t.audioSessionId}")
        return t
    }
    return null
}

/**
 * नवंबर 2026 (जड़ #1 / #3): सिर्फ़ AudioTrack बदलो - कतार (playQ) और बजाने वाला thread वही (चलते जवाब का बचा हिस्सा नहीं खोता).
 * आवाज़ का रास्ता बदला (Bluetooth / कॉल) या track मर गया (ERROR_DEAD_OBJECT) तब.
 */
internal fun GeminiLive.rebuildTrack(why: String): AudioTrack? {
    val old = track
    val t = buildTrack()
    track = t
    old?.let { try { it.pause(); it.flush() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
    LiveAudioDebug.w("AudioTrack दोबारा बना ($why) - कतार में ${playQ.size} टुकड़े बचे" + if (t == null) " • नहीं बना" else "")
    if (playThread?.isAlive != true) startPlayThread()
    return t
}

/** बजाने वाला thread ज़िंदा नहीं (गलती से मरा) = नया - कतार वही (जड़ #2) */
internal fun GeminiLive.ensurePlayThread() {
    if (playThread?.isAlive == true) return
    LiveAudioDebug.w("बजाने वाला thread बंद मिला - फिर शुरू (कतार ${playQ.size})")
    if (track == null) track = buildTrack()
    startPlayThread()
}

internal fun GeminiLive.startPlayThread() {
    var tuneVer = com.dk.assistant.voice.engine.VoiceTune.version
    playThread = Thread {
        var speakingSet = false
        while (!Thread.currentThread().isInterrupted) {
            val b0 = try { playQ.poll(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
            // नवंबर 2026: track नहीं (बना नहीं / रास्ता बदल रहा) - पहले thread बंद हो जाता था (आगे की हर आवाज़ गायब). अब फिर बनाओ
            val tr0 = track ?: (if (b0 != null) rebuildTrack("track नहीं था") else null)
            if (tr0 == null) { if (b0 != null) LiveAudioDebug.w("टुकड़ा छूटा (${b0.size} bytes) - AudioTrack नहीं"); continue }
            var tr: AudioTrack = tr0
            try {
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
            var off = 0; var rebuilds = 0; var zeros = 0
            while (off < b.size && g == gen) {
                val n = try { tr.write(b, off, b.size - off) } catch (e: Exception) { LiveAudioDebug.w("AudioTrack.write exception: ${e.javaClass.simpleName}"); LiveAudioGuard.ERROR }
                // नवंबर 2026 (जड़ #1): गलती = पहले यह टुकड़ा और आगे सब चुपचाप छूटता था. अब: log, track दोबारा, वही टुकड़ा फिर
                // रास्ता बदलने पर दूसरी जगह track पहले ही बदल गया = नया वाला लो (दोबारा मत बनाओ)
                val cur = track
                if (n <= 0 && cur != null && cur !== tr) { tr = cur; zeros = 0; continue }
                when (LiveAudioGuard.onWrite(n, rebuilds, zeros)) {
                    LiveAudioGuard.Write.OK -> zeros = 0
                    LiveAudioGuard.Write.RETRY -> { zeros++; try { Thread.sleep(10) } catch (_: InterruptedException) { break }; continue }
                    LiveAudioGuard.Write.REBUILD -> {
                        LiveAudioDebug.w("AudioTrack.write() = ${LiveAudioGuard.errName(n)} (${b.size - off} bytes बाकी) - track दोबारा बना रहा हूँ")
                        rebuilds++; zeros = 0
                        tr = rebuildTrack("write ${LiveAudioGuard.errName(n)}") ?: break
                        continue
                    }
                    LiveAudioGuard.Write.SKIP -> { LiveAudioDebug.w("AudioTrack.write() = ${LiveAudioGuard.errName(n)} - टुकड़ा छोड़ा (${b.size - off} bytes)"); break }
                }
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
            } catch (e: InterruptedException) { break
            } catch (e: Exception) {
                // नवंबर 2026 (जड़ #2): एक टुकड़े की गलती से thread खत्म नहीं - वरना आगे का हर जवाब बिना आवाज़
                LiveAudioDebug.w("बजाने में गलती (${e.javaClass.simpleName}: ${e.message?.take(60)}) - अगला टुकड़ा")
            }
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
