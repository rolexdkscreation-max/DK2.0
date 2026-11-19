package com.dk.assistant.voice.engine

import android.content.Context
import android.widget.LinearLayout
import com.dk.assistant.voice.st3.St3Config
import com.dk.assistant.voice.st3.St3Engine
import com.dk.assistant.voice.st3.St3Store
import com.dk.assistant.voice.st3.St3Stats
import com.dk.assistant.voice.st3.St3Synth
import com.dk.assistant.voice.st3.St3Text

/**
 * इंजन 3 (19 नवंबर 2026, मालिक: "उस TTS को चुन सकूँ, फिर बातचीत में उसी से आवाज़ बने"):
 * फ़ोन पर ही बनने वाली आवाज़ (Supertone का मॉडल, सिर्फ़ महिला आवाज़ें 1-5). इंटरनेट के बिना आवाज़.
 *
 * बातचीत: Gemini Live सुनता + जवाब लिखता है (पहले जैसा), जवाब के टुकड़े यह इंजन बोलता है.
 * यह न चले (मॉडल डाउनलोड नहीं / गलती) = वही पुराना रास्ता: Gemini TTS, फिर फ़ोन की आवाज़ ([SpeechPipeline]).
 * मॉडल / आवाज़ें: Settings -> आवाज़ -> "आवाज़ें खोलें".
 */
object St3VoiceEngine : VoiceEngine {
    const val ID = "st3_offline"
    override val id = ID
    override val title = "DK ऑफ़लाइन आवाज़ (फ़ोन पर बनती है)"
    override val note = "इंटरनेट के बिना, फ़ोन पर ही • महिला आवाज़ें 1-5 • मॉडल \"आवाज़ें खोलें\" से डाउनलोड • न चले तो Gemini TTS"
    override val split = true
    override val defaultOn = false

    override fun voices(): List<Pair<String, String>> = St3Config.VOICES.map { it.id to "${it.name} (${it.id})" }
    override fun voice(c: Context): String = St3Store.voice(c)
    override fun setVoice(c: Context, v: String) { St3Store.setVoice(c, v) }

    override fun notReady(c: Context): String? = when {
        !St3Store.coreReady(c) -> "मुख्य आवाज़-मॉडल डाउनलोड नहीं हुआ - Settings → आवाज़ → \"आवाज़ें खोलें\""
        St3Store.usableVoice(c) == null -> "कोई आवाज़ डाउनलोड नहीं हुई - \"आवाज़ें खोलें\" में आवाज़ 1 डाउनलोड करें"
        else -> null
    }

    override fun warm(c: Context) { if (notReady(c) == null) St3Engine.warm(c) }

    override fun speak(c: Context, text: String, voice: String, out: PcmOut) {
        notReady(c)?.let { throw java.io.IOException("ऑफ़लाइन आवाज़: $it") }
        try {
            var first = true
            val user = St3Store.steps(c)
            val auto = autoSteps(user, perStep)
            val firstOfAnswer = firstFast.getAndSet(false)
            val ahead = lead
            val chars = St3Text.onlyTags(text).length
            // पहला: जल्दी वाले कदम. बाकी: कदम बारी आने पर - आगे जितनी आवाज़ तैयार है उसमें यह बन जाए (समय हो = पूरे कदम, साफ़)
            val maxSteps = when { firstOfAnswer -> minOf(auto, FIRST_STEPS); ahead != null -> user; else -> auto }
            val pick: ((Int) -> Int)? = if (firstOfAnswer || ahead == null) null else { cfg -> leadSteps(cfg, ahead(), freshPerStep(), chars * msPerChar()) }
            var samples = 0
            St3Engine.synthBlocking(c, St3Text.onlyTags(text), voice, maxSteps, pick) { wav ->
                if (!first) out.write(ByteArray(St3Synth.SILENCE_24K_BYTES))
                first = false
                if (firstOfAnswer && samples == 0) St3Stats.firstAudio(System.currentTimeMillis() - answerAt)
                samples += wav.size
                out.write(St3Synth.pcm16(St3Synth.to24k(wav)))
            }
            // सिर्फ़ बनने का असली समय (कतार में रुकना नहीं) - St3Engine हर वाक्य का नाप रखता है + लॉग लिखता है
            St3Stats.recent(1).lastOrNull()?.let { if (samples > 0 && it.audioMs > 300 && it.steps > 0) perStep = learn(perStep, it.rtf / it.steps) }
        } catch (e: IllegalStateException) {
            throw java.io.IOException("ऑफ़लाइन आवाज़: ${e.message}", e)
        }
    }

    override fun stop() { St3Engine.stop() }

    /**
     * 19 नवंबर 2026 (मालिक: "जवाब आते ही तुरंत बोलना शुरू हो"): हर जवाब का पहला टुकड़ा कम कदमों ([FIRST_STEPS]) में -
     * ~40% जल्दी बनता है, साफ़ी लगभग वही (जाँचा: 5 कदम पर भी Whisper पूरा वाक्य सही पढ़ता है). बाकी टुकड़े Settings वाले कदमों में.
     */
    const val FIRST_STEPS = 5
    /**
     * 19 नवंबर 2026 (मालिक: "पहला वाक्य बोलकर 1-2 सेकंड रुकता है, फिर लगातार"): जड़ - दूसरा टुकड़ा पहले से दोगुना बड़ा
     * और पूरे कदमों में बनता था; पहला (छोटा) बजकर ख़त्म, दूसरा अभी बन रहा = चुप्पी. अब हर टुकड़े के कदम उसकी बारी पर:
     * आगे जितनी आवाज़ बजने को तैयार है ([lead]), उसके 85% में यह बन जाए - समय कम = 5 कदम, समय ज़्यादा = Settings वाले (साफ़).
     * pure - JVM test. [perStepRtf] अभी नहीं नापा = Settings वाले.
     */
    fun leadSteps(user: Int, leadMs: Long, perStepRtf: Double, estAudioMs: Double): Int {
        if (perStepRtf <= 0.0 || estAudioMs <= 0.0) return user
        val fit = ((leadMs * 0.85 - SYNTH_OVERHEAD_MS) / (perStepRtf * estAudioMs)).toInt()
        return fit.coerceIn(minOf(MIN_AUTO_STEPS, user), user)
    }
    /** हर टुकड़े का बँधा खर्च (text -> अंक, आवाज़-मॉडल) - नापा: ~100-150 ms */
    const val SYNTH_OVERHEAD_MS = 120.0
    /** [SpeechPipeline] बताती है: अभी कितने ms की आवाज़ बजने को तैयार (बज रही + कतार में बनी) */
    @Volatile var lead: (() -> Long)? = null
    /** lock के अंदर: पिछले टुकड़े का नाप अभी-अभी जुड़ा ([perStep] बाद में बदलता है) - दोनों में जो धीमा, वही (सावधानी) */
    private fun freshPerStep(): Double {
        val r = St3Stats.recent(1).lastOrNull()?.takeIf { it.audioMs > 300 && it.steps > 0 } ?: return perStep
        return maxOf(perStep, r.rtf / r.steps)
    }
    /** एक अक्षर ≈ कितने ms आवाज़ (पिछले नाप से; अभी नहीं = 75) */
    private fun msPerChar(): Double = St3Stats.recent(1).lastOrNull()?.takeIf { it.chars > 5 && it.audioMs > 0 }
        ?.let { it.audioMs.toDouble() / it.chars } ?: 75.0

    /**
     * 19 नवंबर 2026 (मालिक: "बीच में रुकना नहीं चाहिए"): फ़ोन धीमा हो तो कदम अपने-आप कम - हर टुकड़ा अपनी आवाज़ के
     * [TARGET_RTF] समय में बन जाए. [perStep] = पिछला नाप (बनने का समय / आवाज़ / कदम). Settings वाले कदम ऊपरी हद. pure - JVM test.
     * 19 नवंबर 2026 (मालिक: "लगातार बोलते समय रोबोटिक गूंज"): जड़ - धीमे फ़ोन पर कदम 4 तक गिरते थे (4 पर आवाज़ धातु-सी /
     * गूंजती). अब 5 से नीचे कभी नहीं, और 0.5 की जगह 0.7. (बातचीत में अब [leadSteps] चलता है; यह तब जब pipeline नाप न दे.)
     */
    const val TARGET_RTF = 0.7
    const val MIN_AUTO_STEPS = 5
    @Volatile private var perStep = 0.0
    fun autoSteps(user: Int, perStepRtf: Double): Int {
        if (perStepRtf <= 0.0) return user
        val fit = (TARGET_RTF / perStepRtf).toInt()
        return fit.coerceIn(minOf(MIN_AUTO_STEPS, user), user)
    }
    /** पिछले नाप से थोड़ा-थोड़ा सीखो (एक अटका टुकड़ा सब न बिगाड़े) */
    fun learn(old: Double, now: Double): Double = if (old <= 0.0) now else old * 0.6 + now * 0.4
    private val firstFast = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var answerAt = 0L
    /** नया जवाब (Live का पहला text आया): पहला टुकड़ा जल्दी + यहाँ से पहली आवाज़ तक का समय नापो */
    fun newAnswer() { answerAt = System.currentTimeMillis(); firstFast.set(true) }

    override fun settings(c: Context, box: LinearLayout, ui: VoiceUi) {
        ui.hint(box, "मॉडल और आवाज़ें डाउनलोड: Settings → आवाज़ → \"आवाज़ें खोलें\". जवाब Gemini Live लिखता है, आवाज़ फ़ोन पर बनती है.")
        ui.slider(box, "रफ़्तार", (St3Config.SPEED_MIN * 100).toInt(), Math.round(St3Config.SPEED_MAX * 100),
            Math.round(St3Store.speed(c) * 100), { String.format(java.util.Locale.US, "%.2f", it / 100f) }) { St3Store.setSpeed(c, it / 100f) }
        ui.slider(box, "कदम (ज़्यादा = साफ़ पर धीमी)", St3Config.STEPS_MIN, St3Config.STEPS_MAX, St3Store.steps(c), { "$it" }) { St3Store.setSteps(c, it) }
    }
}
