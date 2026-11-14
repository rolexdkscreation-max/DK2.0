package com.dk.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
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
import com.dk.assistant.DKAccessibilityService.CallTarget
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

// DKAccessibilityService का हिस्सा: कॉल: आने वाली कॉल, उठाना/काटना, म्यूट, स्पीकर, मना करने का संदेश, कौन कॉल कर रहा.
// यह code पहले DKAccessibilityService.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state DKAccessibilityService में)।

internal suspend fun DKAccessibilityService.onIncomingCall(who0: String, ask: String? = null) {
    if (!Prefs.announceCalls(this)) return
    callPending = true
    VolumeKeep.raiseForCall(this)   // मालिक (नवंबर 2026): रिंगटोन / कॉल की आवाज़ कम हो तो फुल, कभी कम नहीं
    agent.stop()
    calls.quietRinger()
    delay(80)        // v3.156: 200 -> 80ms (मालिक: "जैसे ही फ़ोन बजे, तुरंत बोले")
    var who = who0
    if (who.isBlank() && ask == null) who = callerFromScreen()
    if (!calls.ringing && (calls.known || ask != null)) { callPending = false; return }
    val msg = ask ?: CallCmd.announce(who)
    if (ask == null) rejectTarget = null
    
    // v3.156 मालिक: "कॉल आया तो तुरंत फटाक से बोले" - पहले यहाँ "सवाल चल रहा है तो पहले जवाब" का इंतज़ार था;
    // Live में DK हमेशा सुन रहा (LISTENING) होता है, तो कॉल का एलान बहुत देर से (या घंटी ख़त्म होने पर) होता था.
    // अब कॉल सबसे पहले: जो बोल रहा था वह रोको, तुरंत बताओ.
    if (speaker.busy) try { speaker.stop() } catch (_: Exception) { }

    DKState.reply(msg)
    if (DKState.live && gemLive.canTalk()) {
        val whoNote = if (ask == null) who else AppCall.current?.let { r -> "${r.who.ifBlank { "कोई" }} (${r.app}${if (r.video) " वीडियो" else ""} कॉल)" } ?: who
        // नवंबर 2026 (मालिक): "इंटरनेट है तो DK लाइव खुद तुरंत बोले; न बोले तो बैकअप तुरंत".
        // पहले यहाँ फ़ोन की सहेजी आवाज़ पहले बजती थी और Live का माइक बंद रहता था - "DK उठा लो" सुनाई नहीं देता था.
        // अब: माइक खुला, Live पहले (1.3s तक); न बोले = बैकअप आवाज़ उसी पल. बीच में उठा / काट दिया = कुछ नहीं बोलना.
        gemLive.callAudio(true)
        speaker.fullCallVolume()
        val liveSaid = gemLive.announceCall(whoNote)
        if (!liveSaid && calls.ringing && callPending) {
            if (gemLive.isPlaying()) gemLive.flushPlayback()
            // माइक खुला रहे: बैकअप बोलते समय भी "डीके उठा लो" सुनाई दे (गूँज से बचने को तब DK का नाम ज़रूरी - CallReply.instant)
            scope.launch { gemLive.noteCallAsked(whoNote) }     // Live को साथ-साथ बताओ - वह चुप रहे, बैकअप बोल रहा
            callAnnouncing = true
            // बैकअप बोलते समय Live देर से बोलने लगे = दो आवाज़ें नहीं
            val quiet = scope.launch { while (callAnnouncing) { if (gemLive.isPlaying()) gemLive.flushPlayback(); delay(100) } }
            try {
                val spoke = speaker.speakCall(msg, generic = false)
                if (spoke == Speaker.CallSpoke.NONE && calls.ringing && callPending) speaker.speakCall("", generic = true)
            } finally { callAnnouncing = false; quiet.cancel() }
        }
        scope.launch { while (calls.ringing) delay(500); callPending = false; if (!calls.inCall) gemLive.callAudio(false) }
        scope.launch { delay(1_000); while (calls.ringing || calls.inCall) delay(500); speaker.restoreCallVolume() }
        return
    }
    callAnnouncing = true
    // v4.6 मालिक: "जवाब देते में यूज़र बोलेगा 'डीके फोन उठा लो' तो फटाक से पहले फोन उठाएगा" - एलान अलग चलता है,
    // अपने माइक (callBargeEar) से साथ-साथ सुनना. Google माइक वाले तरीके में पहले जैसा: एलान पूरा, फिर सुनो
    val announce = scope.launch { try { speaker.speakCall(msg) } finally { callAnnouncing = false } }
    if (!callBargeEar()) announce.join()
    scope.launch { delay(1_000); while (calls.ringing || calls.inCall) delay(500); speaker.restoreCallVolume() }

    if (loopJob?.isActive == true) {
        scope.launch { while (calls.ringing) delay(500); callPending = false }
        return
    }
    val until = System.currentTimeMillis() + 30_000
    try {
        while (calls.ringing && callPending && System.currentTimeMillis() < until) {
            DKState.set(Mode.LISTENING)
            val ans = hear()
            DKState.set(Mode.IDLE)
            if (!calls.ringing) break
            // v3.156 मालिक: एक बार पूछने के बाद DK चुप - दोबारा "कॉल उठाऊँ?" नहीं; घंटी जितनी है उतनी बजेगी,
            // बीच में "उठा लो" / "काट दो" / "स्पीकर पर डालो" बोलो तो वही होगा
            if (ans == null) continue
            if (handleCallReply(ans)) break
        }
    } finally { callPending = false; DKState.set(Mode.IDLE) }
}

// ------------------------------------------------------------ v4.1: कॉल की नई आवाज़-कमांड (API नहीं)

internal fun DKAccessibilityService.currentTarget(): CallTarget? {
    val r = AppCall.current
    if (!calls.simRinging && calls.state == android.telephony.TelephonyManager.CALL_STATE_IDLE && r != null)
        return CallTarget(r.who, "", r.pkg, r.app, r.open)
    val num = calls.simNumber
    if (num.isBlank() && calls.caller.isBlank()) return rejectTarget
    return CallTarget(calls.caller, num, "", "", null)
}

internal suspend fun DKAccessibilityService.muteCall(on: Boolean): String {
    if (!calls.inCall) return "सर, अभी कोई कॉल नहीं चल रही।"
    // पहले कॉल स्क्रीन का Mute बटन (हालत देखकर - वह toggle है), फिर सिस्टम तरीका
    val btn = allRoots().asSequence().flatMap { root ->
        listOf("Mute", "म्यूट", "MUTE", "Microphone").asSequence().flatMap { root.findAccessibilityNodeInfosByText(it).asSequence() }
    }.mapNotNull { n -> var x: AccessibilityNodeInfo? = n; var d = 0; while (x != null && !x.isClickable && d < 4) { x = x.parent; d++ }; x }.firstOrNull()
    val word = if (on) "म्यूट कर दिया" else "म्यूट हटा दिया"
    if (btn != null && btn.isCheckable) {
        if (btn.isChecked == on) return "सर, पहले से ${if (on) "म्यूट है" else "म्यूट नहीं है"}।"
        if (btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "सर, $word।"
    }
    if (calls.muteApi(on)) return "सर, $word।"
    if (btn != null && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "सर, $word।"
    return "सर, म्यूट नहीं हो पाया। कॉल स्क्रीन पर म्यूट बटन दबाइए।"
}

/** काटी कॉल वाले को मैसेज: SIM = SMS; ऐप = उसी व्यक्ति के notification से सीधा reply, नहीं तो ऐप खोलो + मैसेज कॉपी */
internal suspend fun DKAccessibilityService.sendRejectMessage(t: CallTarget, text: String): String {
    val who = t.name.ifBlank { t.number.ifBlank { "उन्हें" } }
    if (t.pkg.isBlank()) {
        if (t.number.isBlank()) return "सर, कॉल काट दी। नंबर नहीं मिला, इसलिए मैसेज नहीं भेजा।"
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return try {
                @Suppress("DEPRECATION")
                val sms = if (Build.VERSION.SDK_INT >= 31) getSystemService(android.telephony.SmsManager::class.java) else android.telephony.SmsManager.getDefault()
                sms.sendMultipartTextMessage(t.number, null, sms.divideMessage(text), null, null)
                "सर, कॉल काट दी और $who को मैसेज भेज दिया।"
            } catch (e: Exception) { DKLog.d("sms: ${e.message}"); openSmsDraft(t.number, text); "सर, कॉल काट दी। SMS सीधे नहीं गया, मैसेज लिखकर खोल दिया है, भेजें दबाइए।" }
        }
        openSmsDraft(t.number, text)
        return "सर, कॉल काट दी। SMS भेजने की परमिशन नहीं है, इसलिए मैसेज लिखकर खोल दिया है, भेजें दबाइए। Settings में DK को SMS की परमिशन दे दें तो आगे सीधे जाएगा।"
    }
    // ऐप की कॉल: उसी व्यक्ति के हाल के message notification में reply हो तो सीधा
    val item = NotifStore.all().filter { it.pkg == t.pkg && it.canReply }.let { list ->
        if (t.name.isBlank()) null else list.firstOrNull { it.title.equals(t.name, true) } ?: list.firstOrNull { it.title.contains(t.name, true) }
    }
    if (item != null && DKNotifListener.reply(this, item.key, text) == null) return "सर, कॉल काट दी और ${t.app} पर $who को मैसेज भेज दिया।"
    try {
        getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("DK", text))
    } catch (_: Exception) {}
    var opened = false
    try { t.open?.let { it.send(); opened = true } } catch (_: Exception) {}
    if (!opened) try {
        packageManager.getLaunchIntentForPackage(t.pkg)?.let { startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); opened = true }
    } catch (_: Exception) {}
    return if (opened) "सर, कॉल काट दी। ${t.app} में सीधे भेजने का रास्ता नहीं मिला, ऐप खोल दिया है और मैसेज कॉपी कर दिया है, $who की चैट में चिपकाकर भेज दीजिए।"
        else "सर, कॉल काट दी, पर ${t.app} पर मैसेज नहीं भेज पाया।"
}

internal fun DKAccessibilityService.openSmsDraft(number: String, text: String) {
    try {
        startActivity(android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:" + android.net.Uri.encode(number)))
            .putExtra("sms_body", text).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { DKLog.d("sms draft: ${e.message}") }
}

/** v4.6: कॉल का एलान बीच में रोको (उठाना/काटना पहले, बोलना बाद में) */
internal fun DKAccessibilityService.hushForCall() {
    if (speaker.busy) try { speaker.stop() } catch (_: Exception) {}
}

/**
 * v4.6 मालिक: "डीके फोन उठा लो" / "फोन उठा के स्पीकर पर डाल दो" - रिकॉर्ड की हुई आवाज़ से (बिना इंटरनेट भी), फटाक से.
 * घंटी बज रही हो तभी; पहले फ़ोन उठाओ, फिर बोलो.
 */
internal fun DKAccessibilityService.answerByVoice(speakerOn: Boolean, from: String) {
    hushForCall()
    callPending = false
    DKLog.d("कॉल उठाओ (आवाज़-कमांड, $from)${if (speakerOn) " + स्पीकर" else ""}")
    agent.runQuick(QuickCommands.Quick(JSONObject().put("type", "answer_call").put("speaker", speakerOn),
        if (speakerOn) "कॉल उठा कर स्पीकर पर डाल दिया" else "कॉल उठा लिया"))
}

/** Ghanti ke dauraan boli baat: true = samajh kar kaam kar diya. */
internal suspend fun DKAccessibilityService.handleCallReply(heard: String): Boolean {
    val l = " " + heard.lowercase().trim().trim('.', '।', '!', '?') + " "
    fun has(vararg w: String) = w.any { l.contains(it) }
    // v4.6: यह बात DK के एलान के बीच शुरू हुई - माइक में DK की अपनी आवाज़ ("उठाऊँ या काट दूँ") भी हो सकती है.
    // तब सिर्फ़ DK के नाम वाली बात ("डीके फोन उठा लो") मानो, गूँज से कॉल न उठे न कटे
    if (callBargeAt > 0L) {
        callBargeAt = 0L
        if (!CallReply.bargeOk(heard, Prefs.wakeWords(this))) { DKLog.d("एलान के बीच की बात (DK नाम नहीं / गूँज) - छोड़ी: \"${heard.take(30)}\""); return false }
    }
    DKState.user(heard)
    // v4.1: काटकर मैसेज / तैयार मैसेज / म्यूट / कॉल काटो
    CallCmd.route(heard, System.currentTimeMillis())?.let { cc ->
        doCallCmd(cc)
        return cc.action !in listOf(CallCmd.MUTE, CallCmd.UNMUTE, CallCmd.CANCEL)
    }
    // टेम्पलेट "उठाओ / कॉल उठाओ" (Settings → टेम्पलेट कमांड में चालू हो): उठाते ही स्पीकर भी चालू
    val speakerOn = has("स्पीकर", "speaker", "लाउड") ||
        (has("उठा", "uthao", "utha lo") && com.dk.assistant.template.TemplateStore.on(this, "call_answer"))
    val pick = has("उठा", "रिसीव", "receive", "answer", "पिक", "pick", "accept", "स्वीकार", "बात करा", "बात करनी", "जोड़ो", "जोड़ दो") ||
        Regex("(^| )(हाँ|हां|हा|हम्म|yes|yeah|ok|ओके|ठीक है|जी|जी हाँ|जी हां|बिलकुल|बिल्कुल|उठाओ)( |$)").containsMatchIn(l.trim())
    val cut = has("काट", "कट ", "कट", "reject", "decline", "मना", "रख दो", "बाद में", "अभी नहीं", "नहीं उठा", "मत उठा", "बिज़ी", "busy") ||
        Regex("(^| )(नहीं|नही|ना|no|nahi)( |$)").containsMatchIn(l.trim())
    val quiet = has("चुप", "साइलेंट", "silent", "ringtone बंद", "रिंगटोन बंद", "घंटी बंद", "आवाज़ बंद", "आवाज बंद", "आवाज़ कम", "आवाज कम", "धीरे")
    val who = has("कौन", "किसका", "who")
    return when {
        // "मत उठाओ" / "नहीं उठाना" = kaato (pehle check)
        has("मत उठा", "नहीं उठा", "ना उठा") -> { agent.runQuick(QuickCommands.Quick(JSONObject().put("type", "end_call"), "कॉल काट दिया")).join(); callPending = false; true }
        pick && !has("काट", "reject") -> {
            hushForCall()      // एलान अभी चल रहा हो तो वहीं रोको - पहले फ़ोन उठे
            agent.runQuick(QuickCommands.Quick(JSONObject().put("type", "answer_call").put("speaker", speakerOn),
                if (speakerOn) "कॉल उठा कर स्पीकर पर डाल रहा हूँ" else "कॉल उठा रहा हूँ")).join()
            callPending = false; true
        }
        cut -> { hushForCall(); agent.runQuick(QuickCommands.Quick(JSONObject().put("type", "end_call"), "कॉल काट दिया")).join(); callPending = false; true }
        quiet -> {
            DeviceActions.volume(this, "set", 0, "ring")
            DKState.reply("रिंगटोन धीमी कर दी।"); speaker.speakQuick("रिंगटोन धीमी कर दी।"); false
        }
        who -> {
            val name = calls.caller.ifBlank { callerFromScreen() }
            val m = if (name.isNotBlank()) "$name का कॉल है। उठाऊँ?" else "नंबर पता नहीं चला। उठाऊँ?"
            DKState.reply(m); speaker.speakQuick(m); false
        }
        else -> {
            // "आवाज़ बढ़ाओ" jaisi seedhi command chalne do
            val q = QuickCommands.parse(heard, Prefs.wakeWords(this))
            if (q != null) { agent.runQuick(q).join(); q.action.optString("type") in listOf("answer_call", "end_call") } else false
        }
    }
}

/** Doori naapo: Maps me rasta kholo, phir screen se km + samay padho. */
internal suspend fun DKAccessibilityService.distance(to: String, from: String, mode: String): String {
    val r = Maps.directions(this, to, from, mode)
    if (r.startsWith("ERROR")) return r
    val until = System.currentTimeMillis() + 12_000
    delay(2500)
    while (System.currentTimeMillis() < until) {
        val sc = readScreen()
        if (sc.packageName.contains("maps")) Maps.readDistance(sc.text)?.let { return "DISTANCE: $it" }
        delay(800)
    }
    return "maps route opened, distance screen par dikh rahi hai par padh nahi paya"
}

/** Call wali screen se naam padho (contacts permission na ho tab). */
internal fun DKAccessibilityService.callerFromScreen(): String {
    return try {
        val root = rootInActiveWindow ?: return ""
        val texts = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 25) return
            n.text?.toString()?.trim()?.let { if (it.length in 2..40) texts.add(it) }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        val skip = listOf("incoming", "call", "कॉल", "answer", "decline", "उत्तर", "अस्वीकार", "message", "मैसेज", "reply", "remind", "swipe", "स्वाइप", "mobile", "मोबाइल", "sim", "hd")
        texts.firstOrNull { t -> skip.none { t.lowercase().contains(it) } && !t.contains(":") } ?: ""
    } catch (e: Exception) { "" }
}

internal suspend fun DKAccessibilityService.answerCall(askedSpeaker: Boolean): String {
    // 14 नवंबर 2026 (मालिक): Bluetooth जुड़ा = आवाज़ Bluetooth पर, नहीं = speaker पर ही
    val bt = CallRoute.btConnected(this)
    val speakerOn = CallRoute.wantSpeaker(bt)
    if (askedSpeaker && bt) DKLog.d("कॉल: Bluetooth जुड़ा है - speaker की जगह Bluetooth")
    if (bt) scope.launch { delay(1500); if (calls.inCall || AppCall.ongoing()) CallRoute.toBt(this@answerCall) }
    // v4: WhatsApp / Instagram / Facebook / Messenger… की कॉल = उसी notification का Answer बटन, फिर जाँच कि सच में उठी
    if (!calls.simRinging && AppCall.ringing()) {
        val app = AppCall.current?.app.orEmpty()
        val sent = calls.answer()
        var up = withTimeoutOrNull(2500) { while (!AppCall.ongoing()) delay(100) } != null
        if (!up) {
            // बटन नहीं था / नहीं चला -> कॉल की स्क्रीन खुली है, वहाँ Answer
            delay(600)
            clickByText("Answer", "Accept", "ANSWER", "Join", "उत्तर दें", "जवाब दें", "स्वीकार करें", "स्वीकार") ||
                clickById("answer", "accept", "accept_call", "answer_call")
            up = withTimeoutOrNull(2000) { while (!AppCall.ongoing()) delay(100) } != null
        }
        calls.restoreRinger()
        if (!up && !sent) return "ERROR: $app कॉल नहीं उठ पाई - फ़ोन पर खुद उठाइए"
        if (speakerOn) scope.launch { delay(1500); setSpeaker(true) }
        return (if (up) "call utha li" else "$app call ka Answer daba diya") + (if (speakerOn) " aur speaker on" else "")
    }
    if (calls.inCall && !calls.ringing) {
        if (speakerOn) setSpeaker(true)
        return "call pehle se chal rahi hai" + if (speakerOn) ", speaker on" else ""
    }
    // v3.41: tay deri (700/700/800/900/1500 ms) ki jagah "uthi ya nahi" har 100 ms dekho - uthte hi aage
    suspend fun answered(ms: Long) = withTimeoutOrNull(ms) { while (!(calls.known && calls.inCall)) delay(100) } != null
    // 1) Android ka seedha tareeka (permission chahiye)
    var ok = calls.answer()
    if (answered(1000)) ok = true
    // 2) Call popup / call screen ka "Answer" button
    if (!(calls.known && calls.inCall)) {
        ok = clickByText("Answer", "Accept", "ANSWER", "उत्तर दें", "उत्तर", "स्वीकार करें", "स्वीकार", "जवाब दें", "रिसीव", "उठाएं") ||
            clickById("answer", "accept", "incoming_call_accept") || ok
        answered(900)
    }
    // 3) "Swipe up to answer" wali screen
    if (!(calls.known && calls.inCall) && isCallScreen()) {
        val dm = resources.displayMetrics
        swipe(dm.widthPixels / 2f, dm.heightPixels * 0.85f, dm.widthPixels / 2f, dm.heightPixels * 0.35f, 300)
        answered(900)
        ok = ok || (calls.known && calls.inCall)
    }
    // 4) ek baar aur seedha tareeka (kuch phone pehli baar me nahi uthate)
    if (calls.known && !calls.inCall && calls.ringing) { calls.answer(); answered(900) }
    calls.restoreRinger()
    if (calls.known) ok = calls.inCall
    if (!ok) return if (!calls.canAnswer()) "ERROR_CALL_PERM" else "ERROR: call nahi uthi"
    if (speakerOn) {
        // call screen ka Speaker button aane do (pehle 1.5s fixed), phir ek baar aur pakka (kuch phone shuru me speaker hata dete)
        delay(400); setSpeaker(true)
        scope.launch { delay(1500); if (calls.inCall && calls.speakerIsOn() != true) setSpeaker(true) }
    }
    return "call utha li" + (if (speakerOn) " aur speaker on" else "")
}

internal fun DKAccessibilityService.isCallScreen(): Boolean {
    val p = rootInActiveWindow?.packageName?.toString()?.lowercase() ?: return false
    return listOf("dialer", "incall", "telecom", "phone", "contacts").any { p.contains(it) }
}

internal suspend fun DKAccessibilityService.endCall(): String {
    calls.restoreRinger()
    if (calls.end()) return "call kaat di"
    if (clickByText("End call", "Decline", "Reject", "DECLINE", "कॉल समाप्त करें", "कॉल समाप्त", "समाप्त", "अस्वीकार करें", "अस्वीकार", "काटें", "कट करें", "Hang up") ||
        clickById("decline", "reject", "end_call", "endcall", "hangup")) return "call kaat di"
    return if (!calls.canAnswer()) "ERROR_CALL_PERM" else "ERROR: call nahi kati"
}

internal suspend fun DKAccessibilityService.setSpeaker(on: Boolean): String {
    // Pehle call screen ka "Speaker" button (sabse pakka), phir system tareeka
    // Button TOGGLE hai - bina haalat dekhe dabane se "speaker off" par speaker ON ho jaata tha
    val btn = speakerButton()
    if (btn == null) { calls.speakerApi(on); return "speaker ${if (on) "on" else "off"}" }
    val now: Boolean? = if (btn.isCheckable) btn.isChecked else calls.speakerIsOn()
    if (now == on) return "speaker pehle se ${if (on) "on" else "off"} hai"
    if (now == null || !btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) calls.speakerApi(on)
    return "speaker ${if (on) "on" else "off"}"
}

/** Call screen ka "Speaker" button (clickable node) - na mile to null */
internal fun DKAccessibilityService.speakerButton(): AccessibilityNodeInfo? {
    for (root in allRoots()) for (w in listOf("Speaker", "स्पीकर", "Speakerphone", "लाउडस्पीकर")) {
        for (n in root.findAccessibilityNodeInfosByText(w)) {
            var c: AccessibilityNodeInfo? = n; var depth = 0
            while (c != null && !c.isClickable && depth < 4) { c = c.parent; depth++ }
            if (c != null && c.isClickable) return c
        }
    }
    return null
}

internal fun DKAccessibilityService.whoCalls(): String {
    if (!calls.ringing && !calls.inCall) return "abhi koi call nahi hai"
    val who = calls.caller.ifBlank { callerFromScreen() }
    return if (who.isBlank()) "caller ka naam pata nahi chala" else "CALLER: $who"
}
