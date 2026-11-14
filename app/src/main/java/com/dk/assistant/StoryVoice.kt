package com.dk.assistant

import android.content.Context
import com.dk.assistant.dkai.DkaiCastTts
import com.dk.assistant.dkai.DkaiKit
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

    private fun sync() { val k = key(); if (k != storyKey) { storyKey = k; keep.clear(); genders.clear() } }

    /** AI को: कहानी का भाग -> "Name (gender, tone): text" (कहानी के शब्द वही, भाषा वही) */
    fun castSystem(known: Map<String, Boolean>): String = buildString {
        append("You turn one part of a story into an AUDIO-DRAMA script for text-to-speech. Output ONLY script lines, one per line, in exactly this form:\n")
        append("Name (female|male, tone): spoken text\n")
        append("Rules:\n- Narration goes to `Narrator (female, calm storytelling)` (tone may change with the scene).\n")
        append("- Every spoken dialogue goes to the character who says it, with their name. Do not put quotes around speech.\n")
        append("- KEEP the story's own words and language exactly - do not translate, summarize, shorten or add anything. Only split it into lines.\n")
        append("- tone = 1-3 English words for HOW the line is spoken (excited, whispering, angry, sad, scared, laughing, proud, gentle, shouting...).\n")
        append("- Each line at most about 400 characters; split long narration into several Narrator lines.\n")
        append("- No headings, no markdown, no notes.")
        if (known.isNotEmpty()) append("\nCharacters already in this story (keep the same names and genders): " + known.entries.joinToString(", ") { "${it.key} (${if (it.value) "female" else "male"})" })
    }

    /** भाग -> किरदार-स्क्रिप्ट. AI न चले = पूरा भाग Narrator की आवाज़ में (टुकड़ों में) */
    suspend fun script(c: Context, text: String): List<DkaiKit.Line> {
        sync()
        val raw = try { BrainManager(c).quickText(castSystem(genders), text) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
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
}
