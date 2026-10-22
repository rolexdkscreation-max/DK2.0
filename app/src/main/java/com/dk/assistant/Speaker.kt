package com.dk.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * DK ki awaaz. Do tareeke (dono FREE):
 *  1. "google"  - phone ki Google awaaz (offline/online, koi limit nahi). Mahila/purush chun sakte hain.
 *  2. "gemini"  - Gemini ki bahut natural awaaz (Gemini key se). Limit khatam ho to apne-aap Google awaaz.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val appCtx = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    @Volatile private var tts = TextToSpeech(appCtx, this, "com.google.android.tts")
    /** Google TTS na chale to phone ka default TTS engine */
    @Volatile private var triedDefault = false
    /** Kaunse vakya sach me bolne lage (onStart aaya) */
    private val startedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var lastVoiceWarn = 0L

    @Volatile private var player: MediaPlayer? = null
    @Volatile private var playDone: CompletableDeferred<Unit>? = null
    /** Abhi bol raha hai? (tab mic apni awaaz nahi sunta) */
    @Volatile var busy = false
        private set
    @Volatile var lastSpokeAt = 0L
        private set
    @Volatile private var geminiCooldownUntil = 0L

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            DKLog.d("TTS start nahi hua (status=$status)")
            if (!triedDefault) {
                // Google TTS nahi chala -> phone ka apna TTS engine try karo (warna jawab sirf likha aata)
                triedDefault = true
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try { tts.shutdown() } catch (_: Exception) {}
                    tts = TextToSpeech(appCtx, this)
                }
                return
            }
            ready.complete(false); return
        }
        // Hindi awaaz na ho to bhi chup mat raho - jo bhasha phone me hai usme bolo
        val lr = try { tts.setLanguage(Locale("hi", "IN")) } catch (_: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
        if (lr == TextToSpeech.LANG_MISSING_DATA || lr == TextToSpeech.LANG_NOT_SUPPORTED) {
            DKLog.d("Hindi TTS awaaz phone me nahi hai ($lr) - default bhasha")
            try { tts.setLanguage(Locale.getDefault()) } catch (_: Exception) {}
        }
        try {
            tts.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        } catch (_: Exception) {}
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                utteranceId?.let { startedIds.add(it) }
                // ab sach me awaaz nikal rahi hai -> "बोल रहा हूँ"
                if (busy && !paused && DKState.mode != Mode.SPEAKING) DKState.set(Mode.SPEAKING)
            }
            override fun onDone(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
            // QUEUE_FLUSH / stop() se kata vakya: onDone nahi aata - warna DK atka rehta (aur agla jawab bolta hi nahi)
            override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
            override fun onError(utteranceId: String?, errorCode: Int) { DKLog.d("TTS error $errorCode"); utteranceId?.let { pending.remove(it)?.complete(Unit) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
        })
        ready.complete(true)
    }

    /** Phone me install Hindi Google awaazein. */
    suspend fun hindiVoices(): List<Voice> {
        if (!ready.await()) return emptyList()
        return try {
            tts.voices.orEmpty()
                .filter { it.locale.language == "hi" }
                .filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .sortedWith(compareBy<Voice> { VoiceCatalog.googleGender(it) ?: "z" }.thenBy { it.name })
        } catch (e: Exception) { emptyList() }
    }

    private suspend fun applyGoogleVoice(override: String? = null) {
        val voices = hindiVoices()
        val wanted = override ?: Prefs.googleVoice(appCtx)
        val v = voices.firstOrNull { it.name == wanted } ?: VoiceCatalog.pickGoogle(voices, Prefs.voiceGender(appCtx))
        if (v != null && tts.voice?.name != v.name) tts.voice = v
    }

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

    /** Turant bolne wali chhoti baat (ठीक है / कॉल उठाऊँ?) - Gemini ki saved awaaz ho to wahi, warna Google. */
    suspend fun speakQuick(text: String) {
        if (text.isBlank()) return
        val ch = kotlinx.coroutines.channels.Channel<String>(1)
        ch.trySend(text.trim()); ch.close()
        speakStream(ch, quick = true)
    }

    /**
     * Vakya jaise-jaise aate hain (AI abhi likh hi raha ho), waise-waise bolta hai.
     * Beech me user bole -> pause() ; goonj thi -> resume() (wahi vakya dobara) ; asli baat -> stop().
     */
    suspend fun speakStream(ch: kotlinx.coroutines.channels.ReceiveChannel<String>, quick: Boolean = false) {
        mutex.lock()
        val prev = DKState.mode
        try {
            aborted = false; paused = false
            synchronized(spoken) { spoken.clear() }
            busy = true
            // "बोल रहा हूँ" tabhi jab sach me awaaz nikle (pehle vakya ka intezaar = "जवाब आ रहा है")
            if (prev != Mode.THINKING && prev != Mode.WORKING) DKState.set(Mode.THINKING)
            ensureAudible()
            val key = Prefs.ttsGeminiKey(appCtx)
            val voice = Prefs.geminiVoice(appCtx)
            var gem = Prefs.voiceEngine(appCtx) == "gemini" && key.isNotBlank() && System.currentTimeMillis() > geminiCooldownUntil
            if (!quick) applyGoogleVoiceSafe()
            val pendingWav = HashMap<String, kotlinx.coroutines.Deferred<ByteArray?>>()
            val bg = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
            fun prefetch(t: String) {
                if (gem && System.currentTimeMillis() <= geminiCooldownUntil) gem = false
                if (gem && !pendingWav.containsKey(t)) pendingWav[t] = bg.async {
                    try { if (quick) cacheFile(voice, t)?.takeIf { it.exists() }?.readBytes() else synth(key, t, voice) }
                    catch (e: Exception) {
                        val code = (e as? AiException)?.code ?: 0
                        val until = if (code == 429) maxOf(System.currentTimeMillis() + 10 * 60_000L, GeminiTts.allBlockedUntil())
                            else System.currentTimeMillis() + 2 * 60_000L
                        val first = System.currentTimeMillis() > geminiCooldownUntil
                        geminiCooldownUntil = maxOf(geminiCooldownUntil, until)
                        if (first) DKLog.d("Gemini awaaz nahi chali ($code) - ${(geminiCooldownUntil - System.currentTimeMillis()) / 60_000} मिनट तक Google awaaz"); null
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
                        var played = false
                        if (gem) {
                            // Gemini awaaz 3.5 sec me na bane to Google awaaz (der nahi)
                            val wav = withTimeoutOrNull(if (quick) 400L else 3500L) { pendingWav[chunk]?.await() }
                            if (wav != null) { DKState.set(Mode.SPEAKING); playWav(wav); played = true }
                            else if (!quick) { gem = false; geminiCooldownUntil = maxOf(geminiCooldownUntil, System.currentTimeMillis() + 60_000L) }
                        }
                        if (!played && !aborted) played = googleSay(chunk)
                        // phone ki awaaz nahi chali -> Gemini awaaz se bolo (key ho to), warna user ko batao
                        if (!played && !aborted && !paused) {
                            val wav = if (key.isNotBlank() && System.currentTimeMillis() > geminiCooldownUntil)
                                withTimeoutOrNull(7000) { try { synth(key, chunk, voice) } catch (e: Exception) { null } } else null
                            if (wav != null) { DKState.set(Mode.SPEAKING); playWav(wav) } else warnNoVoice()
                        }
                        if (paused && !aborted) {
                            // user bol raha hai - sun lo; goonj nikli to wahi vakya dobara
                            DKState.set(Mode.LISTENING)
                            val go = withTimeoutOrNull(20_000) { resumeSignal?.await() } ?: false
                            paused = false
                            if (go && !aborted) { DKState.set(Mode.SPEAKING); ensureAudible(); replay = true } else aborted = true
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
            if (DKState.mode == Mode.SPEAKING || DKState.mode == Mode.LISTENING)
                DKState.set(if (prev == Mode.WORKING) prev else Mode.IDLE)
            mutex.unlock()
        }
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

    private suspend fun applyGoogleVoiceSafe() { try { if (ready.await()) applyGoogleVoice() } catch (_: Exception) {} }

    /** Ek vakya Google awaaz me. true = sach me bola (awaaz shuru hui), false = TTS ne nahi bola */
    private suspend fun googleSay(text: String): Boolean {
        if (!withTimeoutOrNull(6000) { ready.await() }.let { it == true }) return false
        tts.setSpeechRate(Prefs.ttsRate(appCtx))
        tts.setPitch(Prefs.ttsPitch(appCtx))
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        var r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (r != TextToSpeech.SUCCESS) {
            DKLog.d("TTS speak fail ($r) - dobara")
            delay(250)
            r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (r != TextToSpeech.SUCCESS) { pending.remove(id); return false }
        }
        // awaaz sach me shuru hui? (3.5 sec me nahi = TTS atka/kharab)
        val started = withTimeoutOrNull(3500) {
            while (id !in startedIds && !done.isCompleted) delay(40)
            id in startedIds
        }
        if (started != true) {
            if (aborted || paused) { pending.remove(id); return true }
            DKLog.d("TTS ne bola hi nahi")
            try { tts.stop() } catch (_: Exception) {}
            pending.remove(id); startedIds.remove(id); return false
        }
        withTimeoutOrNull(8_000L + text.length * 120L) { done.await() }
        pending.remove(id); startedIds.remove(id)
        return true
    }

    private fun warnNoVoice() {
        val now = System.currentTimeMillis()
        if (now - lastVoiceWarn < 5 * 60_000L) return
        lastVoiceWarn = now
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                android.widget.Toast.makeText(appCtx, "आवाज़ नहीं चल रही — सेटिंग्स → आवाज़ → \"फ़ोन की TTS सेटिंग\" में Google इंजन चुनें और हिंदी आवाज़ डाउनलोड करें", android.widget.Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
    }

    /** Settings me Google awaaz sunne ke liye (seedha) */
    suspend fun speakGoogle(text: String, voiceName: String? = null) {
        if (!ready.await()) return
        applyGoogleVoice(voiceName)
        ensureAudible()
        if (!googleSay(text)) warnNoVoice()
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

    private suspend fun synth(key: String, text: String, voice: String): ByteArray {
        val cf = cacheFile(voice, text)
        if (cf != null && cf.exists() && cf.length() > 100) return withContext(Dispatchers.IO) { cf.readBytes() }
        return withContext(Dispatchers.IO) { GeminiTts.synthesize(key, text, voice, Prefs.voiceStyle(appCtx)).also { w -> cf?.writeBytes(w) } }
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
        } catch (e: Exception) { DKLog.d("awaaz unmute: ${e.message}") }
    }

    /** Settings me awaaz sunne ke liye. */
    suspend fun previewGemini(voice: String, text: String): String? {
        val key = Prefs.ttsGeminiKey(appCtx)
        if (key.isBlank()) return "पहले Gemini की API key डालें"
        return try {
            val wav = withContext(Dispatchers.IO) { GeminiTts.synthesize(key, text, voice, Prefs.voiceStyle(appCtx)) }
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
            mp.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
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
        try { tts.stop() } catch (_: Exception) {}
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
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
        tts.shutdown()
    }
}
