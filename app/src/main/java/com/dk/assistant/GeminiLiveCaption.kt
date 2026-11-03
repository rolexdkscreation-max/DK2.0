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
import com.dk.assistant.GeminiLive.Companion.HOLD_HINT
import com.dk.assistant.GeminiLive.Companion.LEAD_BYTES
import com.dk.assistant.GeminiLive.Companion.WS
import com.dk.assistant.GeminiLive.Companion.micOffCommand
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

// GeminiLive का हिस्सा: Caption (आपकी बात का लिखा रूप): आना, देर से आना, माइक बंद/रुको वाले शब्द, धीरे-धीरे दिखाना.
// यह code पहले GeminiLive.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state GeminiLive में)।

/** Caption se turant shabd - "आप:" line */
internal fun GeminiLive.onCaption(t: String, final: Boolean) {
    // v3.53: फ़ोन पर वीडियो/गाना बज रहा है और DK चुप - बिना DK के नाम वाले शब्द फ़ोन की अपनी आवाज़ (न दिखाओ, न धीमा करो)
    if (guardOn && !isPlaying() && !guardOk) {
        val named = if (dkOnlyNow) DkOnly.filter(t) != null else PhoneAudioGuard.hasName(t, Prefs.wakeWords(svc))
        if (!(guardHeld && named)) return
        guardOk = true
        log("वीडियो/गाने के बीच DK का नाम सुना (\"${t.take(40)}\") - यूज़र की बात, Gemini को भेज रहा हूँ")
    }
    lastAct = System.currentTimeMillis()
    // नवंबर 2026: "फोन लॉक करो" / "DK फोन अनलॉक करो" का पक्का text आते ही - DK बोल रहा हो (जवाब रोके-सुने वाला
    // रास्ता) तब भी, बोलना-खत्म / Gemini के tool का इंतज़ार नहीं। DK की अपनी आवाज़ की गूँज = नहीं।
    if (final && !micMuted && ComputerSession.ask == null) {
        PhoneLockVoice.instant(t, Prefs.wakeWords(svc), needName = dkOnlyNow)?.let { act ->
            val dkNear = isPlaying() || System.currentTimeMillis() - playEnd < 1500
            if (!(dkNear && BargeGate.isEcho(t, dkHeardNow()))) runLockNow(PhoneLockVoice.quick(act), t, "caption")
        }
    }
    if (t.isNotBlank()) Ducker.duck(svc, "Live: user बोल रहा")   // user ke shabd aaye -> peeche ka gaana/video dheema
    // DK bol raha hai aur user ke asli shabd aaye (mic DK bolte samay sirf tez awaaz bhejta hai) -> DK chup
    // (caption ko DK bolte samay sirf phone ki pakdi user-awaaz jaati hai). Jawab abhi ban raha ho to
    // yeh pichhli baat ka der se aaya text ho sakta hai - tab phone/server ka barge-in hi rokega
    suspectAt = 0L
    if (bargeGate.state == BargeGate.State.HOLD) {
        // v3.5+: यूज़र का अगला सवाल सुन रहे (जवाब चल रहा). "रुको" आया = अभी रोको; बाकी = सुनते रहो, जवाब के बाद
        val now = System.currentTimeMillis()
        if (t != holdCap) { holdCap = t; holdCapAt = now }
        if (final) holdFinal = true
        if (!LiveStop.isStop(t) && Prefs.bargeHold(svc)) { DKState.hint(HOLD_HINT); return }
        askQueue.clear()
        synchronized(sendLock) { bargeGate.confirm(); confirmBarge("रुको: ${t.take(30)}") }
    } else if (bargeGate.state == BargeGate.State.TENTATIVE) {
        val dkNow = dkHeardNow()
        bargeGate.sawCaption(t, System.currentTimeMillis())   // v3.152: caption ज़िंदा है (गूँज भी) - "caption चुप" वाला रोकना नहीं
        // DK ki apni awaaz ya pichhli baat ka der se aaya text = user ka naya bolna nahi
        // (v3.5+: पूरे शब्द मिलाकर, आम शब्द छोड़कर - पहले "है/क्या" से असली सवाल भी गूँज मान लिया जाता था)
        val real = BargeGate.realWords(t) && !BargeGate.isEcho(t, dkNow) && !BargeGate.isRepeat(t, turnUser)
        if (!real) { log("caption: \"${t.take(40)}\" - पक्का नहीं, जवाब जारी"); return }
        if (dkOnlyNow && !CallCmd.windowOpen(now0()) && !svc.callRinging() && DkOnly.filter(t) == null && !LiveStop.isStop(t)) return   // v4.1: DK नहीं बोला = अनदेखा
        // v4.7 (मालिक: "जवाब देते समय मैं बोलूँ तो बात रोककर मेरी बात सुने"): असली शब्द = DK रुके और सुने.
        // पुराना तरीका (जवाब चलता रहे, सवाल बाद में) सिर्फ़ जब Prefs.bargeHold चालू हो
        if (captionOn() && !LiveStop.isStop(t) && Prefs.bargeHold(svc)) {
            // अगला सवाल: जवाब मत काटो (Gemini को आवाज़ नहीं - वरना सर्वर पुराना जवाब रोक देता), caption सुनता रहे
            val now = System.currentTimeMillis()
            synchronized(sendLock) { bargeGate.hold(now) }
            holdCap = t; holdCapAt = now; holdLoudAt = now; holdFinal = final
            track?.let { try { it.setVolume(1f) } catch (_: Exception) {} }
            log("यूज़र ने बीच में कुछ पूछा (\"${t.take(30)}\") - जवाब नहीं रोका, सवाल सुन रहा हूँ")
            DKState.hint(HOLD_HINT)
            return
        }
        if (LiveStop.isStop(t)) askQueue.clear()
        // sendLock: CONFIRMED hote hi mic thread naya tukda bhejta hai - pehle shak ke samay ki (purani) awaaz jaye
        synchronized(sendLock) { bargeGate.confirm(); confirmBarge("caption: ${t.take(30)}") }
    } else if (isPlaying()) return        // DK bol raha hai aur shak nahi = shor/goonj ka text, dikhao bhi mat
    DKState.user("$t…")
    prefetch(t, final)        // v3.88: यूज़र बोल ही रहा है - ज़रूरी डेटा पहले से तैयार (गलत अंदाज़ा = चुपचाप बेकार)
    if (final) checkMicOff(t)        // andaze (interim) par mic band nahi - "माइक बंद मत करो" bhi ho sakta hai
    if (final) capFinal = t.trim()
    // v3.41: ghanti baj rahi hai aur pakka text aaya ("उठा लो" / "काट दो") -> bolna-khatam ka intezaar bhi nahi, turant
    // v4.1: काटकर मैसेज / तैयार मैसेज / म्यूट / कॉल काटो (घंटी, कॉल, या कॉल के ठीक बाद) - फ़ोन खुद, Gemini का जवाब/tool नहीं
    if (final && System.currentTimeMillis() - playEnd > 600 && !micMuted && CallCmd.windowOpen(System.currentTimeMillis())) {
        val cc = CallCmd.route(t, System.currentTimeMillis())
        if (cc != null) {
            muteTurn = true; muteAt = System.currentTimeMillis()
            log("कॉल कमांड फ़ोन ने सुनी: ${cc.action}")
            svc.scope.launch { svc.doCallCmd(cc) }
            return
        }
    }
    if (final && svc.callRinging() && System.currentTimeMillis() - playEnd > 600 && !micMuted) {
        CallReply.parse(t)?.takeIf { it != CallReply.WHO }?.let { act -> svc.scope.launch { callTool(act, "फ़ोन ने सुना (तुरंत): \"${t.take(30)}\"") } }
    }
}

/** Pichhli baat ka pakka text der se aaya - wahi line theek karo, nayi line nahi */
internal fun GeminiLive.onLateCaption(t: String) {
    if (!userDone) { turnUser = t; DKState.user("$t…") } else DKState.fixLastUser(t)
    checkMicOff(t)
}

/** DK ki bachi awaaz rokkar user ko suno; jitna bola utna hi text */
internal fun GeminiLive.stopForUser(why: String) {
    log("user बोलने लगा ($why) - बची आवाज़ रोकी")
    flushPlayback()
    commitPending(false)
    setMode(Mode.LISTENING)
}

internal fun GeminiLive.checkMicOff(t: String) {
    if (micOffSent || !micOffCommand(t)) return
    // DK khud bol raha tha / abhi bola ("ठीक है, माइक बंद") - apni hi awaaz ko hukm mat samjho
    val now = System.currentTimeMillis()
    if (isPlaying() || now - playEnd < 1500 || svc.speaker.busy || now - svc.speaker.lastSpokeAt < 2500) {
        log("'माइक बंद' सुना पर DK खुद बोल रहा था - अनदेखा: $t"); return
    }
    micOffSent = true
    android.os.Handler(android.os.Looper.getMainLooper()).post { svc.stopLive("user ने कहा: ${t.takeLast(60)}"); micOffSent = false }
}

/**
 * DK ka text uski awaaz ke saath: jitni awaaz baj chuki, utne shabd.
 * (Gemini text pehle bhej deta hai aur awaaz baad me bajti hai - isliye seedha dikhane par text aage bhaagta tha.)
 */
internal fun GeminiLive.revealTick() {
    val (pend, live) = synchronized(turnLock) { pendingReply to replyText.toString().trim() }
    val full = pend ?: live
    if (full.isBlank()) return
    if (pend != null && !isPlaying()) { commitPending(true); return }
    if (connSplit) return                                   // v3.68: बँटवारे में screen का text onSpoken से (जितना बोला)
    val recv = turnRecvBytes
    if (recv <= 0L) return                                  // awaaz abhi shuru nahi hui
    val played = (turnPlayedBytes - LEAD_BYTES).coerceAtLeast(0L)
    if (played <= 0L) return
    val words = full.split(WS).filter { it.isNotEmpty() }
    val frac = (played.toDouble() / recv).coerceIn(0.0, 1.0)
    val n = kotlin.math.ceil(frac * words.size).toInt().coerceIn(1, words.size)
    val shown = words.take(n).joinToString(" ")
    if (shown != shownReply) { shownReply = shown; DKState.replyLive(shown) }
}

/** Ruka hua jawab chat me pakka karo. full=false: sirf jitna bola gaya (beech me toka) */
internal fun GeminiLive.commitPending(full: Boolean) {
    val (text, src) = synchronized(turnLock) {
        (pendingReply to pendingSources).also { pendingReply = null; pendingSources = emptyList() }
    }
    if (text == null) return
    val out = if (full || shownReply.isBlank()) text else "$shownReply…"
    shownReply = ""
    // search hua = chat/caption me click hone wale sources; nahi hua = khali (koi "Sources" nahi)
    DKState.reply(out, src)
}
