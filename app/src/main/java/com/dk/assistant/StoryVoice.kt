package com.dk.assistant

import android.content.Context
import com.dk.assistant.dkai.DkaiCastTts
import com.dk.assistant.dkai.DkaiKit
import com.dk.assistant.plus.models.ModelManager
import com.dk.assistant.voice.engine.HinglishText
import com.dk.assistant.voice.st3.St3Config
import com.dk.assistant.voice.st3.St3Engine
import com.dk.assistant.voice.st3.St3Store
import com.dk.assistant.voice.st3.St3Story
import com.dk.assistant.voice.st3.St3Synth
import com.dk.assistant.voice.st3.St3Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 14 नवंबर 2026 (मालिक: "ये काम मेरे स्टोरी राइटर में भी कर दो, लेकिन उसमें ऑडियो जनरेशन ऑटोमेटिक नहीं होगा… हर एक पार्ट
 * के लास्ट में ऑडियो जनरेट करने के लिए बटन… और उस ऑडियो को शेयर करने का भी ऑप्शन"):
 * Story Writer का एक भाग -> किरदारों वाली स्क्रिप्ट (Narrator + हर किरदार, हर लाइन का अंदाज़) -> हर किरदार की अपनी आवाज़.
 * किरदार की आवाज़ पूरी कहानी में वही रहती है ([keep]) - भाग 1 में रामू की जो आवाज़, भाग 5 में भी वही.
 */
object StoryVoice {
    /** कहानी पहचान (विषय + शीर्षक) -> किरदार की तय आवाज़ */
    private var storyKey = ""
    private val keep = LinkedHashMap<String, DkaiKit.StudioVoice>()
    private val genders = LinkedHashMap<String, Boolean>()

    fun dir(c: Context) = File(File(c.filesDir, "dkai_broadcast"), "story").apply { mkdirs() }

    private fun key(): String = StorySession.topic.trim() + "|" + StorySession.title.trim()

    /** इस भाग की बनी आवाज़ (न बनी हो = null) */
    fun file(c: Context, part: Int): File? = File(dir(c), "story_${key().hashCode().toUInt()}_${part + 1}.wav").takeIf { it.isFile && it.length() > 44 }

    private fun target(c: Context, part: Int) = File(dir(c), "story_${key().hashCode().toUInt()}_${part + 1}.wav")

    private fun sync() { val k = key(); if (k != storyKey) { storyKey = k; keep.clear(); genders.clear(); keepSt3.clear() } }
    /** 20 नवंबर 2026: फ़ोन वाली आवाज़ में किरदार -> आवाज़ (F1-F5 / M1-M5), पूरी कहानी में वही */
    private val keepSt3 = LinkedHashMap<String, String>()

    /** AI को: कहानी का भाग -> "Name (gender, tone): text" (कहानी के शब्द वही, भाषा वही) */
    fun castSystem(known: Map<String, Boolean>): String = buildString {
        append("You turn one part of a story into an AUDIO-DRAMA script for text-to-speech. Output ONLY script lines, one per line, in exactly this form:\n")
        append("Name (female|male, tone): spoken text\n")
        append("Rules:\n- Narration goes to `Narrator (female, calm storytelling)` (tone may change with the scene).\n")
        append("- Every spoken dialogue goes to the character who says it, with their name. Do not put quotes around speech.\n")
        append("- KEEP the story's own words and language exactly - do not translate, summarize, shorten or add anything. Only split it into lines.\n")
        append("- tone = 1-3 English words for HOW the line is spoken (excited, whispering, angry, sad, scared, laughing, proud, gentle, shouting, panting, crying, sighing, terrified, mysterious...).\n")
        append("- Each line at most about 400 characters; split long narration into several Narrator lines.\n")
        append("- No headings, no markdown, no notes.")
        if (known.isNotEmpty()) append("\nCharacters already in this story (keep the same names and genders): " + known.entries.joinToString(", ") { "${it.key} (${if (it.value) "female" else "male"})" })
    }

    /** भाग -> किरदार-स्क्रिप्ट. AI न चले = पूरा भाग Narrator की आवाज़ में (टुकड़ों में) */
    suspend fun script(c: Context, text: String): List<DkaiKit.Line> {
        sync()
        val raw = try { BrainManager(c).quickText(castSystem(genders), text, OneKeyLanes.Lane.HEAVY) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        val lines = raw?.let { DkaiKit.parseScript(it) }.orEmpty()
        val gotWords = lines.sumOf { it.text.length }
        // AI ने कहानी आधी कर दी / कुछ नहीं दिया = अपनी तरफ़ से Narrator
        return if (lines.isNotEmpty() && gotWords >= text.length * 0.6) lines else narratorOnly(text)
    }

    /** पूरा भाग Narrator में (पैराग्राफ़ के टुकड़े) */
    fun narratorOnly(text: String): List<DkaiKit.Line> =
        text.split(Regex("\\n\\s*\\n|\\n")).map { it.trim() }.filter { it.isNotEmpty() }
            .flatMap { p -> if (p.length <= 600) listOf(p) else p.chunked(600) }
            .map { DkaiKit.Line("Narrator", true, it, "calm storytelling") }

    /** आवाज़ बनाओ (IO). [step] = (टुकड़ा, कुल). लौटाता है WAV + किरदार-सूची */
    suspend fun render(c: Context, part: Int, lines: List<DkaiKit.Line>, step: (Int, Int) -> Unit): Pair<File, Map<String, DkaiKit.StudioVoice>> {
        sync()
        // 20 नवंबर 2026 (मालिक): फ़ोन वाली आवाज़ (Supertonic 3) का मुख्य मॉडल फ़ोन में = कहानी उसी से (पुरुष + महिला, भाव के साथ).
        // वह न चले = पहले जैसा Gemini
        if (St3Store.coreReady(c)) {
            try { return renderOffline(c, part, lines, step) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                DKLog.d("कहानी की आवाज़: फ़ोन वाली आवाज़ नहीं बनी (${e.message?.take(80)}) - Gemini से")
                if (DkaiCastTts.keys(c).isEmpty()) throw e
            }
        }
        val keys = DkaiCastTts.keys(c)
        if (keys.isEmpty()) throw IllegalStateException("आवाज़ के लिए Gemini की key चाहिए (सेटिंग्स → DK लाइव)")
        val cast = DkaiKit.studioCast(lines, DkaiKit.ALL_VOICES, keep)
        for ((n, v) in cast) { keep[n] = v; genders[n] = v.female }
        val f = withContext(Dispatchers.IO) {
            val (pcm, rate) = DkaiCastTts.render(keys, lines, cast, DkaiKit.studioStyle("Story", DkaiKit.LANGS[0]), step)
            DkaiCastTts.writeWav(target(c, part), pcm, rate)
        }
        return f to cast
    }

    /** कहानी में ज़्यादा कदम (साफ़ आवाज़) - यह बातचीत नहीं, थोड़ा धीमा चलेगा */
    const val STORY_STEPS = 10

    /**
     * 20 नवंबर 2026 (मालिक: "नरेशन के लिए अलग, हर कैरेक्टर के लिए अलग आवाज़... जोर से, चिल्ला के, धीमे से, डरा हुआ, हाँफते
     * हुए"): फ़ोन पर ही - Narrator की अपनी आवाज़, हर किरदार अपने लिंग की अलग आवाज़ ([St3Story.assign]), हर लाइन अपने
     * अंदाज़ में ([St3Story.mood]: रफ़्तार, आवाज़ ऊँची/धीमी, साँस / आह / हँसी). जो आवाज़ फ़ोन में नहीं (~300 KB) वह पहले डाउनलोड.
     */
    private suspend fun renderOffline(c: Context, part: Int, lines: List<DkaiKit.Line>, step: (Int, Int) -> Unit): Pair<File, Map<String, DkaiKit.StudioVoice>> =
        withContext(Dispatchers.IO) {
            val speakers = LinkedHashMap<String, Boolean>()
            for (l in lines) if (!speakers.containsKey(l.speaker)) speakers[l.speaker] = l.female
            val cast = St3Story.assign(speakers, keepSt3)
            for (v in cast.values.distinct()) if (!St3Store.voiceReady(c, v)) {
                ModelManager.download(c, St3Config.castVoice(v).asset)?.let { throw IllegalStateException("आवाज़ $v डाउनलोड नहीं हुई: $it") }
            }
            val pcm = java.io.ByteArrayOutputStream()
            val steps = maxOf(St3Store.steps(c), STORY_STEPS)
            for ((i, l) in lines.withIndex()) {
                step(i + 1, lines.size)
                val m = St3Story.mood(l.tone, l.speaker == St3Story.NARRATOR)
                val said = St3Text.onlyTags(St3Story.lineText(HinglishText.toSpeakable(l.text), m))
                if (said.isBlank()) continue
                val w = St3Engine.renderLine(c, said, cast.getValue(l.speaker), St3Store.speed(c) * m.speed, steps)
                pcm.write(St3Synth.pcm16(St3Story.applyGain(w, m.gain)))
                pcm.write(St3Synth.silence(m.pauseMs))
            }
            if (pcm.size() == 0) throw IllegalStateException("कहानी में बोलने लायक कुछ नहीं")
            for ((n, v) in cast) { keepSt3[n] = v; genders[n] = speakers[n] ?: true }
            DKLog.d("कहानी की आवाज़ (फ़ोन पर): ${lines.size} लाइन, ${cast.size} किरदार - " + cast.entries.joinToString { "${it.key}=${it.value}" })
            DkaiCastTts.writeWav(target(c, part), pcm.toByteArray(), St3Config.SAMPLE_RATE) to
                cast.mapValues { (n, v) -> DkaiKit.StudioVoice(St3Story.voiceName(v), v, speakers[n] ?: true) }
        }
}
