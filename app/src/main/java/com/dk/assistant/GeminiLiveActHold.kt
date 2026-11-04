package com.dk.assistant

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import com.dk.assistant.GeminiLive.Companion.OUT_RATE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

// GeminiLive का हिस्सा: फ़ोन-काम के समय DK की आवाज़ रोकना/छोड़ना.
// यह code पहले GeminiLive.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state GeminiLive में)।

// ================================================================ v3.67: DoneGate (सच्चा "हो गया")
/** जवाब की पहली आवाज़: यूज़र ने काम कहा और अभी तक कोई असली काम नहीं दिखा -> आवाज़ रोको */
internal fun GeminiLive.beginActHold() {
    clearAct()
    // यूज़र की बात नहीं (SYSTEM NOTE से बना जवाब, जैसे "संदेश भेज दिया" वाला) = रोकना नहीं
    val u = turnUser.ifBlank { synchronized(turnLock) { userText.toString() } }
    actAfterTool = false
    val now = System.currentTimeMillis()
    val ev = actEvidence || toolJobs.isNotEmpty() || now - lastFastOkAt < DoneGate.FAST_EVIDENCE_MS
    if (ev || !DoneGate.isAction(u)) return
    actCommand = DoneGate.isCommand(u)
    actTextFrom = synchronized(turnLock) { replyText.length }
    actHold = true; actHoldAt = now
}

internal fun GeminiLive.heldActText(): String = synchronized(turnLock) { if (replyText.length > actTextFrom) replyText.substring(actTextFrom) else "" }

internal fun GeminiLive.holdAct(pcm: ByteArray) {
    synchronized(actHeld) { actHeld.add(pcm) }
    actHeldBytes += pcm.size
    // इतनी देर कोई दावा नहीं = आम बात (लंबा जवाब/सवाल) - रोकना बंद, बजाओ
    if (actHeldBytes >= OUT_RATE * 2 * DoneGate.holdLimitMs(actAfterTool, actCommand) / 1000 && !HonestGuard.claimsDone(heldActText())) releaseAct("आम बात")
    // v3.7: आम बात 0.9 s से ज़्यादा नहीं रुकती (पहले 2.5-8 s = देरी); हुक्म में ज़रूरी सवाल तुरंत
    else if (TaskFirst.release(actCommand && !actAfterTool, System.currentTimeMillis() - actHoldAt, heldActText())) releaseAct("आम बात / ज़रूरी सवाल")
}

internal fun GeminiLive.releaseAct(why: String) {
    val l = synchronized(actHeld) { actHeld.toList().also { actHeld.clear() } }
    actHold = false; actHeldBytes = 0
    if (connSplit && !splitLive) { actReleased++; pumpSplit(); log("काम की बात: रुका text बोलने भेजा ($why)"); return }
    if (l.isEmpty()) return
    actReleased++
    for (b in l) { turnRecvBytes += b.size; playOut(b) }
    log("काम की बात: रोकी आवाज़ बजाई ($why, ${l.sumOf { it.size } * 1000 / (OUT_RATE * 2)} ms)")
}

internal fun GeminiLive.dropAct(why: String): String {
    val t = heldActText()
    val n = synchronized(actHeld) { actHeld.sumOf { it.size }.also { actHeld.clear() } }
    actHold = false; actHeldBytes = 0
    synchronized(turnLock) { if (replyText.length > actTextFrom) replyText.setLength(actTextFrom) }
    actDropped++
    log("सच-जाँच: काम होने से पहले का \"हो गया\" नहीं बजाया ($why, ${n * 1000 / (OUT_RATE * 2)} ms छोड़े)")
    return t
}

internal fun GeminiLive.clearAct() { synchronized(actHeld) { actHeld.clear() }; actHold = false; actHeldBytes = 0; actPending = false; actAfterTool = false; actCommand = false }

/** बारी खत्म और आवाज़ अब भी रुकी है: tool नहीं आया */
internal fun GeminiLive.endActHold() {
    val fj = fastJob
    if (fj?.isActive == true) {
        // फ़ोन वही काम अभी कर रहा है (Gemini से पहले वाला रास्ता) - उसका नतीजा आने तक रुको, फिर तय करो
        val keep = synchronized(actHeld) { actHeld.toList().also { actHeld.clear() } }
        val said = heldActText()
        actHold = false; actHeldBytes = 0
        if (connSplit) synchronized(turnLock) { splitFedTo = replyText.length }    // यह text फ़ैसले तक रुका रहे
        svc.scope.launch {
            withTimeoutOrNull(8_000) { fj.join() }
            val ok = System.currentTimeMillis() - lastFastOkAt < DoneGate.FAST_EVIDENCE_MS
            if (ok || !HonestGuard.claimsDone(said)) {
                actReleased++; for (b in keep) { turnRecvBytes += b.size; playOut(b) }
                if (connSplit && said.isNotBlank()) { pipeline.feed(said); pipeline.endTurn() }
                log("काम की बात: फ़ोन ने काम कर दिया - रोकी आवाज़ बजाई")
            } else {
                actDropped++; log("सच-जाँच: फ़ोन का काम नहीं हुआ - \"हो गया\" नहीं बजाया")
                visionNote(DoneGate.dropNote(said), evidence = false)
            }
        }
        return
    }
    val said = heldActText()
    val after = actAfterTool
    if (DoneGate.decide(said, actEvidence && !after) == DoneGate.Verdict.DROP) {
        dropAct(if (after) "काम अभी पूरा नहीं, फिर भी \"हो गया\"" else "बारी खत्म, कोई काम नहीं चला")
        lastGuardAt = System.currentTimeMillis()          // HonestGuard दोबारा वही सुधार न भेजे
        visionNote(if (after) DoneGate.pendingNote(said) else DoneGate.dropNote(said), evidence = false)
    } else releaseAct("बारी खत्म, कोई दावा नहीं")
}
