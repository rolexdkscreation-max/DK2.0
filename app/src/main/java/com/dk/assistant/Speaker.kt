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
    private val tts = TextToSpeech(appCtx, this, "com.google.android.tts")

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
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
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

    /** Bolta hai aur bolna khatam hone tak wait karta hai. */
    suspend fun speak(text: String) {
        if (text.isBlank()) return
        val prev = DKState.mode
        DKState.set(Mode.SPEAKING)
        busy = true
        ensureAudible()
        try {
            var ok = false
            val key = Prefs.key(appCtx, "gemini")
            if (Prefs.voiceEngine(appCtx) == "gemini" && key.isNotBlank() && System.currentTimeMillis() > geminiCooldownUntil) {
                ok = speakGemini(key, text, Prefs.geminiVoice(appCtx))
            }
            if (!ok) speakGoogle(text)
        } finally {
            busy = false
            lastSpokeAt = System.currentTimeMillis()
            if (DKState.mode == Mode.SPEAKING) DKState.set(if (prev == Mode.SPEAKING) Mode.IDLE else prev)
        }
    }

    /** Chhote, baar-baar bole jaane wale vakya (ठीक है, सोचकर बताता हूँ...) ki awaaz save - agli baar turant. */
    private fun cacheFile(voice: String, text: String): File? {
        if (text.length > 60) return null
        val h = java.security.MessageDigest.getInstance("MD5").digest("$voice|$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val dir = File(appCtx.cacheDir, "tts_cache").apply { mkdirs() }
        return File(dir, "$h.wav")
    }

    /** Lamba jawab -> chhote vakya. Pehla vakya banate hi bolna shuru, baaki peeche-peeche bante rehte hain. */
    private fun sentences(text: String): List<String> {
        val parts = Regex("(?<=[।.!?\\n])\\s+").split(text.trim()).map { it.trim() }.filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (p in parts) {
            if (sb.isNotEmpty() && sb.length + p.length > 160) { out.add(sb.toString()); sb.clear() }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(p)
            // pehla tukda chhota rakho = jaldi awaaz
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

    private suspend fun speakGemini(key: String, text: String, voice: String): Boolean = kotlinx.coroutines.coroutineScope {
        val chunks = sentences(text)
        var spoken = 0
        try {
            var next = async(Dispatchers.IO) { synth(key, chunks[0], voice) }
            for (i in chunks.indices) {
                val wav = next.await()
                if (i + 1 < chunks.size) next = async(Dispatchers.IO) { synth(key, chunks[i + 1], voice) }
                playWav(wav); spoken++
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = (e as? AiException)?.code ?: 0
            // limit khatam / error -> kuch der Google awaaz
            geminiCooldownUntil = System.currentTimeMillis() + if (code == 429) 10 * 60_000L else 2 * 60_000L
            DKLog.d("Gemini awaaz nahi chali ($code): ${e.message} - Google awaaz use kar raha hoon")
            // jo hissa bacha hai woh Google awaaz me
            if (spoken > 0) { speakGoogle(chunks.drop(spoken).joinToString(" ")); true } else false
        }
    }

    suspend fun speakGoogle(text: String, voiceName: String? = null) {
        if (!ready.await()) return
        applyGoogleVoice(voiceName)
        tts.setSpeechRate(Prefs.ttsRate(appCtx))
        tts.setPitch(Prefs.ttsPitch(appCtx))
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        val r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (r != TextToSpeech.SUCCESS) {
            pending.remove(id)
            DKLog.d("TTS speak fail ($r) - dobara koshish")
            delay(300)
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) return
            pending[id] = done
        }
        // lamba jawab: har ~12 akshar/sec + 8 sec
        withTimeoutOrNull(8_000L + text.length * 120L) { done.await() }
    }

    private val audio = appCtx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

    /**
     * Bolne se pehle pakka karo ki awaaz sunai de:
     *  - sunte samay "beep" dabane ke liye media mute hua tha -> turant unmute
     *  - media volume 0 hai -> thoda badha do (warna jawab sirf likha dikhta, awaaz nahi)
     */
    private fun ensureAudible() {
        try {
            VoiceListener.forceUnmute(audio)
            val s = android.media.AudioManager.STREAM_MUSIC
            if (Build.VERSION.SDK_INT >= 23 && audio.isStreamMute(s)) audio.adjustStreamVolume(s, android.media.AudioManager.ADJUST_UNMUTE, 0)
            val max = audio.getStreamMaxVolume(s)
            if (audio.getStreamVolume(s) == 0 && max > 0) audio.setStreamVolume(s, (max * 0.5).toInt().coerceAtLeast(1), 0)
        } catch (e: Exception) { DKLog.d("awaaz unmute: ${e.message}") }
    }

    /** Turant bolne wali awaaz (Gemini cache me ho to wahi, warna Google) - filler/ack ke liye. */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    suspend fun speakQuick(text: String) {
        if (text.isBlank()) return
        val prev = DKState.mode
        busy = true
        ensureAudible()
        try {
            val key = Prefs.key(appCtx, "gemini")
            val voice = Prefs.geminiVoice(appCtx)
            val cf = cacheFile(voice, text)
            if (Prefs.voiceEngine(appCtx) == "gemini" && key.isNotBlank() && cf != null && cf.exists()) {
                playWav(withContext(Dispatchers.IO) { cf.readBytes() })
            } else if (Prefs.voiceEngine(appCtx) == "gemini" && key.isNotBlank() && System.currentTimeMillis() > geminiCooldownUntil) {
                // pehli baar: Google se turant bolo, peeche Gemini awaaz cache me bana lo
                speakGoogle(text)
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                    try { cf?.writeBytes(GeminiTts.synthesize(key, text, voice, Prefs.voiceStyle(appCtx))) } catch (_: Exception) {}
                }
            } else speakGoogle(text)
        } finally {
            busy = false
            lastSpokeAt = System.currentTimeMillis()
            if (DKState.mode == Mode.SPEAKING) DKState.set(prev)
        }
    }

    /** Settings me awaaz sunne ke liye. */
    suspend fun previewGemini(voice: String, text: String): String? {
        val key = Prefs.key(appCtx, "gemini")
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

    fun stop() {
        tts.stop()
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
        android.os.Handler(android.os.Looper.getMainLooper()).post { stopPlayer() }
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }
}
