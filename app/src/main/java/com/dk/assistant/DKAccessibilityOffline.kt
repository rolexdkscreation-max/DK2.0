package com.dk.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// DKAccessibilityService का हिस्सा: बिना इंटरनेट (बैकअप मोड) वाला सुनना - देखें [OfflineListen].

/**
 * नवंबर 2026 (मालिक): ऑफलाइन में माइक एक बार चालू = चालू (कोई बीप नहीं). DK का अपना माइक ([Ear]) लगातार,
 * सिर्फ़ आवाज़-कमांड ([com.dk.assistant.voice.VoiceCmds]: लॉक / अनलॉक मालिक की आवाज़ पर, "किधर हो", "मिल गया").
 * बाकी हर बात अनसुनी. नेट लौटा = सुनना छोड़ो (बोलते समय नहीं) -> loop फिर पुराना रास्ता / Live.
 * सिर्फ़ ऑफलाइन कमांड वाली लिखी बात लौटती है ([OfflineListen.keepText]: लॉक / अनलॉक / किधर हो + टॉर्च, आवाज़, ऐप, अलार्म,
 * कॉल, SMS जैसे फ़ोन वाले काम), बाकी null (agent तक कुछ नहीं).
 */
/** v4.7: ऑफलाइन बातचीत में DK ने आख़िरी जवाब कब दिया ([OfflineTalk.FOLLOW_MS] तक बिना नाम भी बात चले) */
@Volatile internal var offlineTalkAt = 0L
/** v4.8: अकेला "डीके" कब सुना ([OfflineListen.NAME_WINDOW_MS] तक अगली बात DK के लिए) */
@Volatile internal var offlineNameAt = 0L

internal suspend fun DKAccessibilityService.hearOfflineCmds(): String? {
    if (!checkMic()) { delay(2000); return null }
    if (DKState.micMuted) { delay(800); return null }
    listener.release()      // फ़ोन वाला SpeechRecognizer (बीप वाला) बंद
    earCaptionOff()         // caption को नेट चाहिए
    val since = System.currentTimeMillis() - speaker.lastSpokeAt
    if (since < 350) delay(350 - since)
    DKState.set(Mode.LISTENING)
    val me = this
    // v4.8: घंटी के समय DK खुद एलान कर रहा हो तो माइक में उसकी अपनी आवाज़ ("उठाऊँ…") भी - तब नाम वाली बात ही मानो
    val echoRisk = callAnnouncing || speaker.busy
    val pcm = coroutineScope {
        val job = async { ear.nextUtterance() }
        val watch = launch {
            while (isActive && job.isActive) {
                delay(OfflineListen.WATCH_MS)
                if (OfflineListen.stopNow(NetProbe.online(me), me.listeningWanted(), DKState.micMuted,
                        calls.inCall && !calls.ringing, ear.capturing)) { job.cancel(); break }
            }
        }
        try { job.await() } catch (e: CancellationException) { null } finally { watch.cancel() }
    }
    currentCoroutineContext().ensureActive()
    if (pcm == null) {
        DKState.set(Mode.IDLE)
        if (ear.problem == "perm") { ear.release(); checkMic(); return null }
        if (ear.problem != null) { ear.release(); delay(400) }   // Google माइक पर नहीं जाते (वहाँ बीप)
        return null
    }
    if (pcm.isNotEmpty() && com.dk.assistant.voice.VoiceCmds.anyOn(this)) {
        val vc = withContext(Dispatchers.Default) { com.dk.assistant.voice.VoiceCmds.match(me, pcm) }
        if (vc != null && runVoiceCmd(vc, "ऑफलाइन")) { DKState.user(""); DKState.set(Mode.IDLE); return null }
    }
    // नमूना न मिला / सिखाया नहीं: वही पकड़ी बात फ़ोन की on-device पहचान को (अपना माइक नहीं खोलती = बीप नहीं)
    if (pcm.isNotEmpty() && OfflineStt.available(this)) {
        val raw = OfflineStt.transcribe(this, pcm)
        currentCoroutineContext().ensureActive()
        PhoneLockStore.reload(this)
        // नवंबर 2026: agent ने सवाल पूछा है (SMS हाँ/ना, किसे भेजूँ…) - जवाब बिना छाँटे उसी को
        if (agentHearing && !raw.isNullOrBlank()) { DKState.set(Mode.IDLE); return raw }
        // v4.8 मालिक: "फ़ोन आया है तो डीके फोन उठा लो / स्पीकर पर डाल दो - ऑफलाइन में भी". घंटी के समय बात सीधे
        // loop के कॉल वाले हिस्से (handleCallReply) को; एलान के बीच शुरू हुई बात में DK का नाम ज़रूरी (गूँज से कॉल न उठे)
        if ((calls.ringing || callPending) && !raw.isNullOrBlank()) {
            DKState.set(Mode.IDLE)
            if (echoRisk && !CallReply.bargeOk(raw, Prefs.wakeWords(this))) { DKLog.d("घंटी: एलान की गूँज - छोड़ी"); return null }
            return raw
        }
        val names = Prefs.wakeWords(this)
        // v4.8 मालिक: "पहला शब्द डीके ही सुनेगा, उसके बाद और कोई बात बोलेगा तो ही सुनेगा" - अकेला "डीके" = "जी",
        // फिर अगली बात (थोड़ी देर तक) DK के लिए. नाम के बिना बाकी सब अनसुना (पहले मालिक की आवाज़ / बातचीत में बिना नाम भी चलता था)
        if (!raw.isNullOrBlank()) {
            val bare = OfflineListen.findRest(raw, names)
            if (bare != null && bare.isBlank()) {
                offlineNameAt = System.currentTimeMillis()
                DKState.set(Mode.IDLE); DKState.reply("जी, बोलिए")
                try { speaker.speak("जी, बोलिए।") } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                offlineNameAt = System.currentTimeMillis()
                return null
            }
        }
        val said0 = raw?.let { OfflineListen.withName(it, names, offlineNameAt, System.currentTimeMillis()) }
        if (said0 != raw) offlineNameAt = 0L
        val text = said0
        if (OfflineListen.keepText(text, names, FindPhone.active, ownerOk = false, fg = currentPackage())) {
            DKLog.d("ऑफलाइन कमांड (लिखी बात): \"${text?.take(40)}\"")
            DKState.set(Mode.IDLE)
            return text      // loop का पुराना लॉक / अनलॉक / किधर हो वाला हिस्सा चलेगा
        }
        // v4.7 मालिक: "ऑफलाइन में नॉर्मल बातें भी - डीके कहाँ हो, क्या कर रहे हो, क्या खाना खाया" ([OfflineTalk]).
        // DK का नाम लिया / मालिक की आवाज़ / अभी-अभी बात चल रही थी = बात करो. इंटरनेट वाली बात = साफ़ बताओ.
        if (!text.isNullOrBlank()) {
            val rest = OfflineListen.findRest(text, names)
            val addressed = rest != null     // v4.8: सिर्फ़ "डीके ..." (या अकेले "डीके" के ठीक बाद वाली बात)
            val said = (rest?.takeIf { it.isNotBlank() } ?: text)
            val reply = if (addressed) OfflineTalk.reply(this, said, addressed = true)
                else null
            if (reply != null) {
                DKLog.d("ऑफलाइन बातचीत: \"${said.take(40)}\" -> \"${reply.take(40)}\"")
                DKState.user(said); DKState.reply(reply)
                try { speaker.speak(reply) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                offlineTalkAt = System.currentTimeMillis()
                DKState.set(Mode.IDLE)
                return null
            }
            DKLog.d("ऑफलाइन: \"${text.take(40)}\" - कमांड नहीं, अनसुनी")
        }
    }
    DKLog.d("ऑफलाइन: आवाज़-कमांड नहीं मिली - अनसुनी")
    DKState.set(Mode.IDLE)
    return null
}
