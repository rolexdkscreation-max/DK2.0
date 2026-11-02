package com.dk.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context.WINDOW_SERVICE
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

// DKAccessibilityService का हिस्सा: सुनना और Live की हालत: माइक जाँच, Google से सुनना, caption कान, गूँज, बीच में टोकना, backup मोड, किनारे की चमक.
// यह code पहले DKAccessibilityService.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state DKAccessibilityService में)।

/** v4.6: पिछली गलती का इंतज़ार खत्म - अगली बार loop सीधे Gemini Live जोड़े */
internal fun DKAccessibilityService.liveNow(why: String) {
    if (liveOffUntil > System.currentTimeMillis()) DKLog.d("Live: $why - इंतज़ार खत्म, अभी जोड़ रहा हूँ")
    liveOffUntil = 0L; liveSoftFails = 0; liveOffSoft = false
}

internal suspend fun DKAccessibilityService.enterBackup(kind: LiveFailWhy.Kind, detail: String) {
    val firstTime = !backupMode || backupKind != kind
    val now = System.currentTimeMillis()
    quotaStrikes = LiveHealth.strikesAfter(backupKind, kind, quotaStrikes)
    backupMode = true; backupKind = kind; backupAt = now
    liveOffSoft = false
    // नवंबर 2026: आख़िरी सीमा (जाँच न चले तब भी) - लिमिट में कभी 60s से पहले नहीं (429 का चक्कर नहीं)
    val cap = LiveHealth.capMs(kind, quotaStrikes)
    liveOffUntil = now + cap
    speaker.localOnly = true
    listener.preferOffline = false
    try { com.dk.assistant.voice.engine.KokoroTtsEngine.warm(this) } catch (_: Throwable) {}   // पहला जवाब बिना model-लोड की देरी
    DKState.setLink(LiveFailWhy.link(kind))
    ModeTrack.switchTo(ModeTrack.Ch.TEXT, "${kind.short} ($detail)")
    DKLog.d("बैकअप मोड: ${kind.short} ($detail) - ${LiveHealth.cooldownMs(kind, quotaStrikes) / 1000}s बाद से चुप सेहत-जाँच, ज़्यादा से ज़्यादा ${cap / 1000}s बाद Live फिर आज़माऊँगी")
    // मालिक (नवंबर 2026): नेट / socket / server / लिमिट = चुपचाप (सिर्फ़ ऊपर पट्टी), बाकी कारण एक बार बोलकर
    if (firstTime && !LiveHealth.silent(kind)) { liveTroubleSpoken = false; liveTroubleSay(LiveFailWhy.backupLine(kind)) }
    startHealthCheck()
}

/**
 * नवंबर 2026 (मालिक: "text मोड में पीछे चुप health-check, ठीक होते ही अगली बात से वापस Live"):
 * IO thread पर, सिर्फ़ बैकअप मोड में. Live सत्र / माइक नहीं छूता - एक छोटा HTTPS GET ([LiveNet.healthProbe]).
 * ठीक + cooldown पूरा = liveNow() -> चलता सुनना / जवाब पूरा होने के बाद loop अगली बार Live जोड़ता है (बीच में नहीं टोकता).
 */
internal fun DKAccessibilityService.startHealthCheck() {
    if (healthJob?.isActive == true) return
    val me = this
    healthJob = scope.launch(Dispatchers.IO) {
        var last = 0L
        while (isActive && backupMode) {
            val k = backupKind ?: break
            if (!LiveHealth.probes(k)) break                     // मॉडल / सेटिंग / माइक: जाँच से पता नहीं - पुराना नियम (cap)
            delay(LiveHealth.nextCheckInMs(System.currentTimeMillis(), backupAt, k, quotaStrikes, last).coerceAtLeast(500L))
            if (!backupMode || backupKind != k) continue
            val p = if (!NetProbe.online(me)) LiveHealth.Probe.NET else LiveNet.healthProbe(GeminiLive.key(me))
            last = System.currentTimeMillis()
            ModeTrack.call(ModeTrack.Ch.PROBE)
            if (p == LiveHealth.Probe.OK) ModeTrack.ok(ModeTrack.Ch.PROBE) else ModeTrack.error(ModeTrack.Ch.PROBE, "${k.short}: ${p.name}")
            val nk = LiveHealth.kindAfter(k, p)
            if (nk != k) {
                // जाँच में ही लिमिट / key की गलती = उसी के नियम से इंतज़ार (लिमिट = फिर कम से कम 60s)
                quotaStrikes = LiveHealth.strikesAfter(k, nk, quotaStrikes)
                backupKind = nk; backupAt = last
                liveOffUntil = last + LiveHealth.capMs(nk, quotaStrikes)
                DKState.setLink(LiveFailWhy.link(nk))
                DKLog.d("सेहत-जाँच: ${nk.short} - Live अभी नहीं")
                continue
            }
            if (LiveHealth.open(last, backupAt, k, quotaStrikes, p)) {
                DKLog.d("सेहत-जाँच ठीक (${k.short} दूर) - अगली बात से फिर Gemini Live")
                liveNow("सेहत-जाँच ठीक")
                break
            }
        }
    }
}

internal fun DKAccessibilityService.exitBackup(why: String) {
    if (!backupMode) return
    backupMode = false; backupKind = null
    healthJob?.cancel(); healthJob = null
    speaker.localOnly = false
    ModeTrack.switchTo(ModeTrack.Ch.LIVE, why)
    DKLog.d("बैकअप मोड खत्म: $why")
}

/** Live kyun nahi chal raha - log me saaf likho */
internal fun DKAccessibilityService.liveWhyNot(): String = when {
    Prefs.listenEngine(this) != "auto" -> "सेटिंग में सुनने का तरीका '${Prefs.listenEngine(this)}' है (Gemini Live के लिए 'अपने आप' चुनें)"
    !GeminiLive.available(this) -> "Gemini key नहीं है"
    System.currentTimeMillis() <= liveOffUntil -> "पिछली गलती के बाद ${(liveOffUntil - System.currentTimeMillis()) / 1000}s रुका है"
    else -> "-"
}

/** हर रुकावट में सिर्फ़ एक बार बोलो - बार-बार बड़बड़ नहीं, पर यूज़र को कभी न लगे कि DK सुन नहीं रही */
internal suspend fun DKAccessibilityService.liveTroubleSay(msg: String) {
    if (liveTroubleSpoken) return
    liveTroubleSpoken = true; liveTroubleShown = true
    DKLog.d("Live न जुड़ा - यूज़र को बताया: $msg")
    DKState.reply(msg)
    try { speaker.speak(msg) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
}

internal suspend fun DKAccessibilityService.warn(id: String, msg: String) {
    val now = System.currentTimeMillis()
    if (now - (warnedAt[id] ?: 0L) < 5 * 60_000L) return
    warnedAt[id] = now
    DKLog.d("Chetavni: $msg")
    DKState.reply(msg)
    speaker.speak(msg)
}

/** Mic ki permission hai? Nahi to batao aur app khol kar permission maango. */
internal suspend fun DKAccessibilityService.checkMic(): Boolean {
    if (Setup.hasMic(this)) return true
    DKState.set(Mode.IDLE)
    warn("perm", "माइक की परमिशन नहीं है, इसलिए मैं सुन नहीं पा रहा। ऐप खुल रहा है — वहाँ माइक की इजाज़त दीजिए।")
    try {
        startActivity(android.content.Intent(this, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("ask_mic", true))
    } catch (e: Exception) { DKLog.d("app nahi khula: ${e.message}") }
    return false
}

/** Google mic se ek baat (silent kaan na chale tab) */
internal suspend fun DKAccessibilityService.hearGoogle(): String? {
    ear.release()
    if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
        warn("norec", "फ़ोन में Google का सुनने वाला ऐप नहीं मिला। Groq की फ्री key डालिए, तो मैं खुद सुनूँगा।")
        delay(3000); return null
    }
    listener.lastSpokeAt = speaker.lastSpokeAt
    DKState.set(Mode.LISTENING)
    return listener.listenOnce()
}

internal fun DKAccessibilityService.earCaptionOn() {
    val key = Prefs.captionKey(this)   // v3.41: likhne ki apni key (ho to)
    if (key.isBlank()) { ear.onFrame = null; return }
    val c = earCaption ?: LiveCaption(LiveKeys.order(listOf(key) + Prefs.liveGeminiKeys(this)), onText = { t, _ -> if (earShowing && t.isNotBlank()) DKState.user("$t…") }, onLate = { })
        .also { it.start(); earCaption = it }
    ear.onFrame = { b, n ->
        val out = ByteArray(n * 2)
        for (i in 0 until n) { val v = b[i].toInt(); out[i * 2] = (v and 0xff).toByte(); out[i * 2 + 1] = (v shr 8 and 0xff).toByte() }
        c.send(out)
    }
}

internal fun DKAccessibilityService.earCaptionOff() { ear.onFrame = null; earCaption?.stop(); earCaption = null }

/** Suni hui baat DK ki apni hi awaaz (goonj) to nahi? */
internal fun DKAccessibilityService.isEcho(heard: String): Boolean {
    val dk = (speaker.currentChunk + " " + speaker.spokenSoFar().takeLast(300)).let { LocalBrain.norm(it) }
    if (dk.isBlank()) return false
    val dkWords = dk.split(' ').filter { it.length > 1 }.toSet()
    val hw = LocalBrain.norm(heard).split(' ').filter { it.length > 1 }
    if (hw.isEmpty()) return true
    val same = hw.count { it in dkWords }
    return if (hw.size <= 2) same == hw.size else same >= hw.size * 0.6
}

/** DK bol/soch raha hai - us dauraan bhi suno. User bole to DK chup, baat suno, phir dono milakar jawab. */
internal suspend fun DKAccessibilityService.listenBarge(): String? {
    if (silentEar()) {
        val pcm = ear.nextUtterance(barge = true, onStart = { if (speaker.busy) speaker.pause() }, onDiscard = { speaker.resume() })
            ?: run { speaker.resume(); delay(300); return null }
        val text = Transcriber.transcribe(this, pcm)
        if (text.isNullOrBlank() || text.trim(' ', '.', '।', ',').length < 2 || isEcho(text)) {
            DKLog.d("Beech ki awaaz goonj/shor thi: $text"); speaker.resume(); return null
        }
        return text
    }
    // Google mic: pehle apne kaan se pata karo ki user bolne laga, phir Google se suno
    ear.nextUtterance(barge = true, vadOnly = true, onStart = { if (speaker.busy) speaker.pause() }) ?: run { delay(300); return null }
    ear.release()
    listener.lastSpokeAt = speaker.lastSpokeAt
    val text = listener.listenOnce()
    if (text.isNullOrBlank() || isEcho(text)) { speaker.resume(); return null }
    return text
}

/** Kaam/jawab chalne do, saath me suno - beech me toka to naya (milaya hua) jawab. */
internal suspend fun DKAccessibilityService.runWithBarge(first: kotlinx.coroutines.Job) {
    var job = first
    while (job.isActive) {
        if (!DKState.live || agentHearing || callAnnouncing || calls.ringing || calls.inCall) {
            withTimeoutOrNull(300) { job.join() }; continue
        }
        val heard: String? = coroutineScope {
            val l = async { listenBarge() }
            bargeListen = l
            try {
                select<String?> {
                    l.onAwait { it }
                    // kaam khatam - par user beech me bol raha ho to uski baat poori suno
                    job.onJoin { if (ear.capturing) l.await() else { l.cancel(); null } }
                }
            } finally { bargeListen = null }
        }
        if (heard == null) continue
        val low = heard.lowercase()
        if (GeminiLive.micOffCommand(low)) { stopLive("बोला: ${heard.take(60)}"); return }
        job = agent.interrupt(heard)
    }
}

internal fun DKAccessibilityService.showEdgeGlow() {
    try {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val v = com.dk.assistant.fx.EdgeGlowView(this)
        wm.addView(v, params)
        edgeGlow = v
    } catch (e: Exception) { DKLog.d("Edge glow नहीं लगा: ${e.javaClass.simpleName}") }
}

internal fun DKAccessibilityService.removeEdgeGlow() {
    edgeGlow?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {} }
    edgeGlow = null
}
