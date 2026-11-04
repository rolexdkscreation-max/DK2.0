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
internal suspend fun DKAccessibilityService.hearOfflineCmds(): String? {
    if (!checkMic()) { delay(2000); return null }
    if (DKState.micMuted) { delay(800); return null }
    listener.release()      // फ़ोन वाला SpeechRecognizer (बीप वाला) बंद
    earCaptionOff()         // caption को नेट चाहिए
    val since = System.currentTimeMillis() - speaker.lastSpokeAt
    if (since < 350) delay(350 - since)
    DKState.set(Mode.LISTENING)
    val me = this
    val pcm = coroutineScope {
        val job = async { ear.nextUtterance() }
        val watch = launch {
            while (isActive && job.isActive) {
                delay(OfflineListen.WATCH_MS)
                if (OfflineListen.stopNow(NetProbe.online(me), DKState.live, DKState.micMuted,
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
        val text = OfflineStt.transcribe(this, pcm)
        currentCoroutineContext().ensureActive()
        PhoneLockStore.reload(this)
        // नवंबर 2026: agent ने सवाल पूछा है (SMS हाँ/ना, किसे भेजूँ…) - जवाब बिना छाँटे उसी को
        if (agentHearing && !text.isNullOrBlank()) { DKState.set(Mode.IDLE); return text }
        // मालिक की आवाज़ पक्की (पहचान चालू + मिलान) = नाम बोले बिना भी फ़ोन वाले काम; वरना DK का नाम पहले चाहिए
        val owner = com.dk.assistant.voice.OwnerVoice.active(this) && ownerHeard(pcm)
        if (OfflineListen.keepText(text, Prefs.wakeWords(this), FindPhone.active, ownerOk = owner, fg = currentPackage())) {
            DKLog.d("ऑफलाइन कमांड (लिखी बात): \"${text?.take(40)}\"")
            DKState.set(Mode.IDLE)
            return text      // loop का पुराना लॉक / अनलॉक / किधर हो वाला हिस्सा चलेगा
        }
        if (!text.isNullOrBlank()) DKLog.d("ऑफलाइन: \"${text.take(40)}\" - कमांड नहीं, अनसुनी")
    }
    DKLog.d("ऑफलाइन: आवाज़-कमांड नहीं मिली - अनसुनी")
    DKState.set(Mode.IDLE)
    return null
}
