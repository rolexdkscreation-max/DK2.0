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

// GeminiLive का हिस्सा: बारी: पहला वाक्य, दोबारा भेजना, timeline, बारी शुरू/खत्म, जवाब के टुकड़े.
// यह code पहले GeminiLive.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state GeminiLive में)।

internal fun GeminiLive.sendOpener() {
    val t = openerText ?: return
    // 90s से पुराना (Live नहीं जुड़ा था) = बासी; अगली बार ऐप खुलने पर नया
    if (System.currentTimeMillis() - openerAt > 90_000) { openerText = null; return }
    if (toolJobs.isNotEmpty() || turnDet.inSpeech) return
    openerText = null
    try {
        val now = System.currentTimeMillis()
        eosAt = now; firstAudioAt = 0; trace.reset(); trace.speechEndAt = now; trace.endpointAt = now
        beginTimeline(now, now, "DK ने खुद बात शुरू की")
        setMode(Mode.THINKING)
        ws?.tx(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString())
        lastAct = now
        log("DK ने खुद बात शुरू की (ऐप खुला)")
    } catch (_: Exception) {}
}

internal fun GeminiLive.sendFirstText() {
    val t = firstText ?: return
    firstText = null
    try {
        // "आप:" line me wahi baat (wake word ke baad jo bola) - jawab ke turn me judegi
        startTurn(); turnUser = t; userDone = false
        DKState.user("$t…")
        setMode(Mode.THINKING)
        eosAt = System.currentTimeMillis(); trace.reset(); trace.speechEndAt = eosAt; trace.endpointAt = eosAt
        beginTimeline(eosAt, eosAt, "wake word की बात (text)")
        ws?.tx(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString())
        log("पहली बात भेजी: $t")
    } catch (_: Exception) {}
}

/** Tootne se pehle ki unanswered baat - naye connection par ek baar text me (UI line dobara nahi) */
internal fun GeminiLive.sendResend() {
    val t = resendText ?: return
    resendText = null
    if (!textRescue) { log("टूटने से पहले की बात text में दोबारा नहीं भेजी (बचाव-text बंद) - बातचीत handle से वहीं से"); return }
    val w = ws ?: return
    val now = System.currentTimeMillis()
    eosAt = now; firstAudioAt = 0; trace.reset(); trace.speechEndAt = now; trace.endpointAt = now
    beginTimeline(now, now, "टूटने के बाद दोबारा")
    val ok = try { w.tx(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString()) } catch (_: Exception) { false }
    if (ok) { resentN++; if (DKState.mode != Mode.WORKING) setMode(Mode.THINKING) }
    log("टूटने से पहले की बात (${t.length} अक्षर) नए connection पर text में भेजी" + if (ok) "" else " (भेज नहीं पाया)")
}

internal fun GeminiLive.beginTimeline(speechEnd: Long, endpoint: Long, why: String) {
    if (timeline.active && timeline.eventAt == 0L && timeline.audioAt == 0L && toolJobs.isEmpty()) {
        // v3.46: खटखट/शोर (caption में कोई शब्द नहीं) को "जवाब नहीं आया" में मत गिनो - वहाँ चुप रहना ही सही है
        // नवंबर 2026 (DKI-70D747): आप [LiveRecover.CONTINUE_MS] के अंदर फिर बोलने लगे = वही बात आगे (टुकड़ा), Gemini पूरी
        // बात का एक साथ जवाब देता है - इसे "जवाब नहीं आया" नहीं गिनते. इंतज़ार का समय लाइन में (पक्का सबूत)
        val from = if (speechStartAt > timeline.endpointAt) speechStartAt else speechEnd
        val waited = from - timeline.endpointAt
        if (!tlWords) noiseTurns++
        else if (waited in 0 until LiveRecover.CONTINUE_MS) {
            contTurns++
            if (contTurns <= 3 || contTurns % 20 == 0) log("बात आगे बोली (${waited} ms बाद) - टुकड़ा, जवाब पूरी बात पर (#$contTurns)")
        } else { noReplyTurns++; log("पिछली बात पर Gemini से कुछ नहीं आया (#$noReplyTurns, इंतज़ार ${waited / 100 / 10.0}s) • ${timeline.summary()}") }
    }
    tlWords = BargeGate.realWords(caption?.text().orEmpty())
    timeline.begin(speechEnd, endpoint, why); waitingLogged = false
}

/**
 * User DK ke bolte samay bola (phone ne pakda / server ne / caption ne):
 * 1) awaaz TURANT band (AudioTrack flush - sirf dheemi nahi)  2) purane jawab ke aane wale tukde phenko
 * 3) haalat turant Listening. Generation server rokta hai (mic ki awaaz -> server VAD -> "interrupted").
 */
internal fun GeminiLive.bargeIn(why: String) {
    localBargeAt = System.currentTimeMillis(); bargeUntil = localBargeAt + 1500; suspectAt = 0L
    if (generating) dropOld = true
    stopForUser(why)
}

/** जवाब का हिस्सा (आवाज़ या text) आया: पहली बार पर बारी की गिनती + देरी (पहले inline था; v3.68 में text के लिए भी) */
internal fun GeminiLive.replyPartArrived(what: String) {
    if (!replyStarted) {
        replyStarted = true
        // naya jawab: pichhla (agar abhi bhi baj raha tha) poora likho, ginti shuru se
        commitPending(true)
        turnRecvBytes = 0; turnPlayedBytes = 0; shownReply = ""
        // user ki baat yahin tak - aage jo bole woh nayi baat
        caption?.closeUtterance()?.takeIf { it.isNotBlank() }?.let { turnUser = it; userDone = false }
        beginActHold()
        // v3.71: नया जवाब = भाव शुरू से (TEXT बँटवारे में टैग से, बाकी में लिखावट के शब्दों से)
        com.dk.assistant.avatar.MoodState.set(com.dk.assistant.avatar.Mood.NORMAL)
        tagFilter = if (connSplit && connText) com.dk.assistant.avatar.EmotionTag.Filter { com.dk.assistant.avatar.MoodState.set(it) } else null
        if (connSplit) {
            splitSpoke = false
            resetLiveKeep()                                    // नवंबर 2026: नए जवाब की Live आवाज़ शुरू से
            synchronized(turnLock) { splitFedTo = minOf(splitFedTo, replyText.length) }
            val e = splitEngine ?: com.dk.assistant.voice.engine.VoiceEngines.fallback()
            if (e != null) pipeline.begin(e)
        }
    }
    if (firstAudioAt == 0L) {
        firstAudioAt = System.currentTimeMillis()
        if (trace.firstChunkAt == 0L) trace.firstChunkAt = firstAudioAt
        val from = if (trace.speechEndAt > 0 && firstAudioAt - trace.speechEndAt < 30_000) trace.speechEndAt else eosAt
        if (from > 0) {
            lastLatency = firstAudioAt - from; log("देरी: बोलना खत्म → $what = $lastLatency ms")
            // v3.41: is model ki asli raftaar yaad - agli baar sabse tez model pehle (tool wali baari nahi)
            if (lastToolAt < from && toolJobs.isEmpty() && model.isNotBlank()) Prefs.recordLiveLatency(svc, model, lastLatency)
        }
    }
    if (actPending && !actHold) {
        actPending = false; actAfterTool = true
        actTextFrom = synchronized(turnLock) { replyText.length }; actHold = true; actHoldAt = System.currentTimeMillis()
    }
}

/** बँटवारे में आवाज़ नहीं रुकती, text रुकता है: इतना लिखा और कोई "हो गया" दावा नहीं = आम बात, बोलो */
internal fun GeminiLive.splitHoldCheck() {
    if (!connSplit || !actHold) return
    val t = heldActText()
    if (t.length >= DoneGate.holdLimitMs(actAfterTool, actCommand) * 14 / 1000 && !HonestGuard.claimsDone(t)) releaseAct("आम बात")
    else if (TaskFirst.release(actCommand && !actAfterTool, System.currentTimeMillis() - actHoldAt, t)) releaseAct("आम बात / ज़रूरी सवाल")
}

internal fun GeminiLive.startTurn() {
    turnActive = true; routeDedupe.reset(); clearAct(); actEvidence = false
    leakFilter = newLeakFilter(); leakMute = false
    synchronized(turnLock) { userText.clear(); replyText.clear(); turnSources = emptyList(); splitFedTo = 0 }
}

internal fun GeminiLive.finishTurn(close: Boolean = true) {
    turnActive = false; generating = false
    val (mu, r) = synchronized(turnLock) {
        (userText.toString().trim() to replyText.toString().trim()).also { userText.clear(); replyText.clear() }
    }
    // caption ka text (jo user ne sach me bola, turant likha) pehle; na ho to Live model ka
    // awaaz wala jawab: baat pehli awaaz par band ho chuki. Bina awaaz (sirf tool) wala: abhi band karo
    val snap = if (close && !replyStarted) caption?.closeUtterance().orEmpty() else ""
    val cur = if (!userDone) turnUser else ""               // pichhli baari ka text dobara na lo
    val u = cur.ifBlank { snap }.ifBlank { mu }
    turnUser = u; userDone = true                           // der se aaya pakka text isi line ko theek karega
    if (u.isNotBlank()) DKState.user(u)
    if (u.isNotBlank()) lastUserLine = u
    if (r.isNotBlank()) { lastDkLine = r; checkHonest(r) }
    // jawab: awaaz abhi baj rahi hai to revealTick() saath-saath poora karega
    synchronized(turnLock) {
        if (r.isNotBlank()) { pendingReply = r; pendingSources = Grounding.merge(turnSources, toolSources); toolSources = emptyList() }
        turnSources = emptyList()
    }
    firstAudioAt = 0; replyStarted = false
    if (!isPlaying()) idleMode()
}

/**
 * v3.51: DK ने "कर दिया / भेज दिया" बोला पर कोई tool चला ही नहीं (90s में) = झूठ.
 * तुरंत Gemini को सुधार का SYSTEM NOTE - वह सच बोले और ज़रूरत हो तो सच में tool चलाए.
 */
internal fun GeminiLive.checkHonest(reply: String) {
    val now = System.currentTimeMillis()
    if (toolJobs.isNotEmpty() || now - lastGuardAt < 30_000) return
    if (!HonestGuard.unbacked(reply, lastEvidenceAt, now)) return
    lastGuardAt = now
    log("सच-जाँच: DK ने बिना किसी काम के \"हो गया\" बोला - सुधार भेजा: ${reply.take(60)}")
    visionNote(HonestGuard.correctionNote(reply), evidence = false)
}
