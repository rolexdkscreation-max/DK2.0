package com.dk.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * DK ki awaaz - v3.74 se SIRF Gemini (Gemini TTS). Phone ki apni awaaz (Android TTS) kabhi nahi.
 * Internet nahi / key nahi / Gemini limit khatam -> DK chup, jawab sirf likha (VoicePolicy).
 */
class Speaker(context: Context) {
    /** v3.51: कॉल (घंटी/बात) चल रही है? - तब media mute होता है, इसलिए accessibility वाली आवाज़ (CallVoice) */
    @Volatile var callNow: () -> Boolean = { false }
    @Volatile private var lastUsage = -1

    private fun voiceAttrs(): AudioAttributes {
        val call = try { callNow() } catch (_: Exception) { false }
        return AudioAttributes.Builder().setUsage(CallVoice.usage(call))
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    }

    /** TTS की आवाज़ का usage कॉल के हिसाब से (बदला हो तभी) */
    private fun applyVoiceUsage() {
        val a = voiceAttrs()
        if (a.usage == lastUsage) return
        lastUsage = a.usage
        if (a.usage == CallVoice.USAGE_ASSISTANCE_ACCESSIBILITY) DKLog.d("कॉल चल रही है - DK की आवाज़ accessibility पर (media कॉल में mute होता है)")
    }

    private val appCtx = context.applicationContext
    @Volatile private var lastVoiceWarn = 0L
    @Volatile private var lastWarnWhy: VoicePolicy.Why? = null

    @Volatile private var player: MediaPlayer? = null
    @Volatile private var playDone: CompletableDeferred<Unit>? = null
    /** Abhi bol raha hai? (tab mic apni awaaz nahi sunta) */
    @Volatile var busy = false
        private set
    @Volatile var lastSpokeAt = 0L
        private set
    @Volatile private var geminiCooldownUntil = 0L

    /** v3.74: phone ki Google/Android awaaz ab nahi chalti - list hamesha khali */
    @Suppress("RedundantSuspendModifier")
    suspend fun hindiVoices(): List<Voice> = emptyList()

    /** Internet chalu hai? */
    private fun online(): Boolean = try {
        val cm = appCtx.getSystemService(android.net.ConnectivityManager::class.java)
        cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) { true }

    /** v4.2: DK लाइव की तीनों keys (मुख्य + बैकअप 2 + बैकअप 3) - पहले सिर्फ़ मुख्य key से आवाज़ बनती थी */
    private fun keys(): List<String> = Prefs.liveGeminiKeys(appCtx)

    private fun why(keys: List<String>): VoicePolicy.Why =
        VoicePolicy.gemini(keys.isNotEmpty(), online(), System.currentTimeMillis(), geminiCooldownUntil)

    // ================================================================ bolna (ek samay me ek hi awaaz)
    private val mutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var paused = false
    @Volatile private var aborted = false
    @Volatile private var resumeSignal: CompletableDeferred<Boolean>? = null
    /** Is baar jitna bol chuka (beech me tokne par AI ko batate hain) */
    private val spoken = StringBuilder()
    /** Abhi kaunsa vakya bol raha hai (apni hi goonj pehchanne ke liye) */
    @Volatile var currentChunk: String = ""
        private set

    fun spokenSoFar(): String = synchronized(spoken) { spoken.toString().trim() }

    /** Bolta hai aur bolna khatam hone tak wait karta hai. */
    suspend fun speak(text: String) {
        if (text.isBlank()) return
        val ch = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        sentences(text).forEach { ch.trySend(it) }
        ch.close()
        speakStream(ch)
    }

    /** Turant bolne wali chhoti baat (ठीक है / कॉल उठाऊँ?) - Gemini ki saved awaaz (ya nayi Gemini awaaz). */
    suspend fun speakQuick(text: String): Boolean {
        if (text.isBlank()) return false
        val ch = kotlinx.coroutines.channels.Channel<String>(1)
        ch.trySend(text.trim()); ch.close()
        return speakStream(ch, quick = true)
    }

    // ================================================================ v4.2: कॉल का एलान - हर हाल में, पूरी आवाज़ में
    /**
     * कॉल आई: पूरी आवाज़ में बोलो. क्रम: नाम वाला वाक्य (तीनों keys) -> न बने तो पहले से सहेजा "सर, कॉल आ रहा है"
     * (बिना इंटरनेट / limit के भी तुरंत). लौटता है = क्या बोला (NAME / GENERIC / NONE).
     */
    enum class CallSpoke { NAME, GENERIC, NONE }

    suspend fun speakCall(text: String, generic: Boolean = true): CallSpoke {
        fullCallVolume()
        if (speakQuick(text)) return CallSpoke.NAME
        if (!generic) return CallSpoke.NONE
        val wav = cachedCallWav() ?: return CallSpoke.NONE
        DKLog.d("कॉल: नाम वाली आवाज़ नहीं बनी - सहेजी आवाज़ \"${CallVoice.GENERIC_ASK}\" बजा रहा हूँ")
        busy = true
        try { DKState.set(Mode.SPEAKING); fullCallVolume(); playWav(wav) } finally { busy = false; lastSpokeAt = System.currentTimeMillis() }
        return CallSpoke.GENERIC
    }

    private fun cachedCallWav(): ByteArray? = try {
        cacheFile(Prefs.geminiVoice(appCtx), CallVoice.GENERIC_ASK)?.takeIf { it.exists() && it.length() > 100 }?.readBytes()
    } catch (_: Exception) { null }

    /** "सर, कॉल आ रहा है" की आवाज़ पहले से बनाकर रखो (एक बार - फिर हमेशा तुरंत, limit / इंटरनेट न हो तब भी) */
    suspend fun warmCallVoice() {
        if (cachedCallWav() != null) return
        val keys = keys(); if (keys.isEmpty() || !online()) return
        try { synth(keys, CallVoice.GENERIC_ASK, Prefs.geminiVoice(appCtx)); DKLog.d("कॉल की सहेजी आवाज़ तैयार") }
        catch (e: Exception) { DKLog.d("कॉल की सहेजी आवाज़ अभी नहीं बनी: ${e.message?.take(80)}") }
    }

    @Volatile private var accBefore = -1
    /** कॉल के समय DK की आवाज़ (accessibility) पूरी - मालिक: "फुल आवाज़ में बोले" */
    fun fullCallVolume() {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val a = CallVoice.STREAM_ACCESSIBILITY; val am = audio.getStreamMaxVolume(a)
            if (am <= 0) return
            val cur = audio.getStreamVolume(a)
            if (cur < am) { if (accBefore < 0) accBefore = cur; audio.setStreamVolume(a, am, 0) }
        } catch (_: Exception) {}
    }

    /** कॉल खत्म - accessibility आवाज़ पहले जितनी */
    fun restoreCallVolume() {
        val b = accBefore; accBefore = -1
        if (b < 0 || Build.VERSION.SDK_INT < 26) return
        try { audio.setStreamVolume(CallVoice.STREAM_ACCESSIBILITY, b, 0) } catch (_: Exception) {}
    }

    /**
     * Vakya jaise-jaise aate hain (AI abhi likh hi raha ho), waise-waise bolta hai.
     * Beech me user bole -> pause() ; goonj thi -> resume() (wahi vakya dobara) ; asli baat -> stop().
     * Sirf Gemini awaaz. Gemini na chale to us vakya par chup (phone TTS par NAHI jaata).
     */
    suspend fun speakStream(ch: kotlinx.coroutines.channels.ReceiveChannel<String>, quick: Boolean = false): Boolean {
        mutex.lock()
        val prev = DKState.mode
        var played = false
        try {
            aborted = false; paused = false
            synchronized(spoken) { spoken.clear() }
            busy = true
            if (prev != Mode.THINKING && prev != Mode.WORKING) DKState.set(Mode.THINKING)
            ensureAudible()
            applyVoiceUsage()
            val keys = keys()
            val voice = Prefs.geminiVoice(appCtx)
            val w0 = why(keys)
            var gem = w0 == VoicePolicy.Why.OK
            if (!gem) warnNoVoice(w0)
            val pendingWav = HashMap<String, kotlinx.coroutines.Deferred<ByteArray?>>()
            val bg = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
            fun prefetch(t: String) {
                if (gem && System.currentTimeMillis() <= geminiCooldownUntil) gem = false
                if (gem && !pendingWav.containsKey(t)) pendingWav[t] = bg.async {
                    try { synth(keys, t, voice) }
                    catch (e: Exception) {
                        val code = (e as? AiException)?.code ?: 0
                        val until = if (code == 429) maxOf(System.currentTimeMillis() + 10 * 60_000L, GeminiTts.allBlockedUntil(keys))
                            else System.currentTimeMillis() + 60_000L
                        val first = System.currentTimeMillis() > geminiCooldownUntil
                        geminiCooldownUntil = maxOf(geminiCooldownUntil, until)
                        if (first) DKLog.d("Gemini आवाज़ नहीं चली ($code) - ${(geminiCooldownUntil - System.currentTimeMillis()) / 60_000} मिनट DK चुप (फ़ोन की आवाज़ नहीं)"); null
                    }
                }
            }
            val ahead = ArrayDeque<String>()
            try {
                while (!aborted) {
                    val chunk = ahead.removeFirstOrNull() ?: ch.receiveCatching().getOrNull() ?: break
                    prefetch(chunk)
                    // aage ke 2 vakya ki awaaz pehle se bana lo (beech me ruke bina)
                    while (ahead.size < 2) { val n = ch.tryReceive().getOrNull() ?: break; ahead.addLast(n); prefetch(n) }
                    var replay = true
                    while (replay && !aborted) {
                        replay = false
                        currentChunk = chunk
                        if (gem) {
                            val wav = withTimeoutOrNull(VoicePolicy.waitMs(quick)) { pendingWav[chunk]?.await() }
                            if (wav != null) { DKState.set(Mode.SPEAKING); playWav(wav); played = true }
                            else if (!aborted && !paused) {
                                // Gemini ne awaaz nahi di -> chup (phone TTS nahi). Baaki jawab bhi likha hi rahega.
                                gem = false
                                warnNoVoice(why(keys).takeIf { it != VoicePolicy.Why.OK } ?: VoicePolicy.Why.LIMIT)
                            }
                        }
                        if (paused && !aborted) {
                            // user bol raha hai - sun lo; goonj nikli to wahi vakya dobara
                            DKState.set(Mode.LISTENING)
                            val go = withTimeoutOrNull(20_000) { resumeSignal?.await() } ?: false
                            paused = false
                            if (go && !aborted) {
                                DKState.set(Mode.SPEAKING); ensureAudible(); replay = true
                                if (gem && !pendingWav.containsKey(chunk)) prefetch(chunk)
                            } else aborted = true
                        }
                    }
                    if (!aborted) synchronized(spoken) { spoken.append(chunk).append(' ') }
                }
            } finally {
                bg.cancel()
            }
        } finally {
            busy = false
            paused = false
            currentChunk = ""
            lastSpokeAt = System.currentTimeMillis()
            if (DKState.mode == Mode.SPEAKING || DKState.mode == Mode.LISTENING || DKState.mode == Mode.THINKING && prev != Mode.THINKING)
                DKState.set(if (prev == Mode.WORKING) prev else Mode.IDLE)
            mutex.unlock()
        }
        return played
    }

    /** User beech me bolne laga: turant chup (par bolna khatam nahi - goonj nikli to resume) */
    fun pause() {
        if (!busy || paused) return
        resumeSignal = CompletableDeferred()
        paused = true
        haltAudio()
    }

    /** Goonj/shor tha - wahi vakya dobara se bolo */
    fun resume() {
        if (!paused) return
        resumeSignal?.complete(true)
    }

    val isPaused get() = paused

    /** Awaaz kyon nahi - user ko ek chhota sandesh (5 min me ek baar, ya wajah badle to) */
    private fun warnNoVoice(w: VoicePolicy.Why) {
        if (w == VoicePolicy.Why.OK) return
        val now = System.currentTimeMillis()
        if (w == lastWarnWhy && now - lastVoiceWarn < 5 * 60_000L) return
        lastVoiceWarn = now; lastWarnWhy = w
        val msg = VoicePolicy.message(w)
        DKLog.d(msg)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { UiLang.toast(appCtx, msg, android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {}
        }
    }

    /**
     * v3.74: pehle yeh phone ki Google awaaz thi. Ab phone TTS band - yeh bhi sirf Gemini awaaz
     * (internet/key na ho to chup). voiceName ab kaam ka nahi.
     */
    suspend fun speakGoogle(text: String, @Suppress("UNUSED_PARAMETER") voiceName: String? = null) {
        speak(text)
    }

    /** Chhote, baar-baar bole jaane wale vakya ki awaaz save - agli baar turant. */
    private fun cacheFile(voice: String, text: String): File? {
        if (text.length > 60) return null
        val h = java.security.MessageDigest.getInstance("MD5").digest("$voice|$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val dir = File(appCtx.cacheDir, "tts_cache").apply { mkdirs() }
        return File(dir, "$h.wav")
    }

    /** Lamba jawab -> chhote vakya. Pehla tukda chhota = jaldi awaaz. */
    fun sentences(text: String): List<String> {
        val parts = Regex("(?<=[।.!?\\n])\\s+").split(text.trim()).map { it.trim() }.filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (p in parts) {
            if (sb.isNotEmpty() && sb.length + p.length > 160) { out.add(sb.toString()); sb.clear() }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(p)
            if (out.isEmpty() && sb.length >= 25) { out.add(sb.toString()); sb.clear() }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out.ifEmpty { listOf(text) }
    }

    private suspend fun synth(keys: List<String>, text: String, voice: String): ByteArray {
        val cf = cacheFile(voice, text)
        if (cf != null && cf.exists() && cf.length() > 100) return withContext(Dispatchers.IO) { cf.readBytes() }
        return withContext(Dispatchers.IO) { GeminiTts.synthesizeAny(keys, text, voice, Prefs.voiceStyle(appCtx)).also { w -> cf?.writeBytes(w) } }
    }

    private val audio = appCtx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

    /**
     * Bolne se pehle pakka karo ki awaaz sunai de:
     *  - sunte samay "beep" dabane ke liye media mute hua tha -> turant unmute
     *  - media volume 0 hai -> thoda badha do (warna jawab sirf likha dikhta, awaaz nahi)
     */
    fun ensureAudible() {
        try {
            VoiceListener.forceUnmute(audio)
            val s = android.media.AudioManager.STREAM_MUSIC
            // pichhli baar app band hote waqt mute reh gayi awaaz bhi kholo
            // Sirf NORMAL ringer me - silent/vibrate (user ya "volume silent" command) me notification/system ko mat chhuo,
            // warna DK ke bolte hi phone wapas ring mode me chala jaata tha
            if (Build.VERSION.SDK_INT >= 23 && audio.ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL) for (st in intArrayOf(android.media.AudioManager.STREAM_NOTIFICATION, android.media.AudioManager.STREAM_SYSTEM))
                try { if (audio.isStreamMute(st)) audio.adjustStreamVolume(st, android.media.AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
            if (Build.VERSION.SDK_INT >= 23 && audio.isStreamMute(s)) audio.adjustStreamVolume(s, android.media.AudioManager.ADJUST_UNMUTE, 0)
            val max = audio.getStreamMaxVolume(s)
            if (audio.getStreamVolume(s) == 0 && max > 0) audio.setStreamVolume(s, (max * 0.5).toInt().coerceAtLeast(1), 0)
            // v3.51: कॉल के समय accessibility वाली आवाज़ - वह 0 हो तो सुनाई नहीं देगी
            if (Build.VERSION.SDK_INT >= 26 && (try { callNow() } catch (_: Exception) { false })) try {
                val a = CallVoice.STREAM_ACCESSIBILITY; val am = audio.getStreamMaxVolume(a)
                if (am > 0 && audio.getStreamVolume(a) < am / 2) audio.setStreamVolume(a, (am * 0.8).toInt().coerceAtLeast(1), 0)
            } catch (_: Exception) {}
        } catch (e: Exception) { DKLog.d("awaaz unmute: ${e.message}") }
    }

    /** Settings me awaaz sunne ke liye. */
    suspend fun previewGemini(voice: String, text: String): String? {
        val keys = keys()
        if (keys.isEmpty()) return "पहले Gemini की API key डालें"
        return try {
            val wav = withContext(Dispatchers.IO) { GeminiTts.synthesizeAny(keys, text, voice, Prefs.voiceStyle(appCtx)) }
            playWav(wav); null
        } catch (e: Exception) { "Gemini आवाज़ नहीं चली: ${e.message?.take(120)}" }
    }

    private suspend fun playWav(wav: ByteArray) {
        val f = File(appCtx.cacheDir, "dk_tts.wav")
        withContext(Dispatchers.IO) { f.writeBytes(wav) }
        val done = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            stopPlayer()
            val mp = MediaPlayer()
            mp.setAudioAttributes(voiceAttrs())
            try { mp.setDataSource(f.absolutePath) } catch (e: Exception) { done.complete(Unit); mp.release(); return@withContext }
            mp.setOnCompletionListener { done.complete(Unit) }
            mp.setOnErrorListener { _, _, _ -> done.complete(Unit); true }
            try { mp.prepare() } catch (e: Exception) { DKLog.d("play: ${e.message}"); done.complete(Unit); mp.release(); return@withContext }
            if (Build.VERSION.SDK_INT >= 23) {
                try { mp.playbackParams = mp.playbackParams.setSpeed(Prefs.ttsRate(appCtx).coerceIn(0.6f, 1.8f)) } catch (_: Exception) {}
            }
            player = mp; playDone = done
            mp.start()
        }
        withTimeoutOrNull(90_000) { done.await() }
        withContext(Dispatchers.Main) { stopPlayer() }
    }

    private fun stopPlayer() {
        player?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        player = null
        playDone?.complete(Unit); playDone = null
    }

    /** Abhi chal rahi awaaz turant band */
    private fun haltAudio() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { stopPlayer() }
        playDone?.complete(Unit)
    }

    /** Bolna poori tarah band (naya sawaal / "रुको") */
    fun stop() {
        aborted = true
        resumeSignal?.complete(false)
        haltAudio()
    }

    fun shutdown() {
        stop()
    }
}
