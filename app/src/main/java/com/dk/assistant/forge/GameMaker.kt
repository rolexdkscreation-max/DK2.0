package com.dk.assistant.forge

import android.content.Context
import com.dk.assistant.ComputerSession
import com.dk.assistant.ComputerTools
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.DKState
import com.dk.assistant.LiveReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * नवंबर 2026 (मालिक: "DK से गेम बनाने को बोलूँ तो वो DK कंप्यूटर को देगी, गेम बनवाएगी, फिर Chrome में चलाकर दिखाएगी और
 * बताएगी 'चेक कर लीजिए' - गेम में क्या-क्या है"). जैसा गेम बोला, वैसा: DK कंप्यूटर एक ही HTML फ़ाइल में पूरा गेम लिखता है,
 * फिर DK उसे फ़ोन के लोकल सर्वर ([LocalSite]) से Chrome में खोलती है और गेम की खूबियाँ बोलती है. फ़ाइल न बने = साफ़ "नहीं बना" (झूठा "हो गया" नहीं).
 */
object GameMaker {
    private val GAME = listOf("गेम", "game", "गेम्स", "games", "खेल")
    private val MAKE = listOf("बनाओ", "बना दो", "बनाकर", "बना के", "बनाके", "बनाइए", "बनाएँ", "बनाएं", "बना", "तैयार कर", "bana", "banao", "make", "create", "build", "develop")
    private val NOT = listOf("खेलो", "खेलते", "कोडिंग", "coding", "github", "repo", "apk", "play store", "प्ले स्टोर", "डाउनलोड", "download", "install", "इंस्टॉल", "कैसे बना", "how to", "kaise")

    /** "DK, एक सांप वाला गेम बनाओ" -> गेम का विवरण; नहीं तो null. Pure (JVM test) */
    fun extract(text: String?): String? {
        val raw = text?.trim().orEmpty()
        if (raw.length < 5) return null
        val low = " " + raw.lowercase() + " "
        if (GAME.none { low.contains(it) } || MAKE.none { low.contains(it) }) return null
        if (NOT.any { low.contains(it) } || raw.trimEnd().endsWith("?")) return null
        return raw.trimEnd('.', '।', '!', ' ')
    }

    /** DK कंप्यूटर के लिए पूरा काम (नियम सहित). Pure */
    fun task(desc: String, file: String): String =
        "Make a complete, polished, playable HTML5 game exactly as the user asked: \"${desc.trim()}\".\n" +
            "Rules: ONE self-contained file - save it with write_file as \"$file\" (all HTML, CSS and JavaScript inside, no external libraries, " +
            "no internet, no images from the web - draw with canvas/CSS/emoji). It must work on a phone in Chrome: full-screen responsive canvas, " +
            "touch controls (on-screen buttons or swipe/tap) and keyboard too, start screen, score, levels or rising difficulty, game-over + restart, " +
            "best score saved in localStorage, smooth 60fps requestAnimationFrame loop, nice colours and sound effects with WebAudio (no files). " +
            "Test the logic mentally; no errors in the console. Write the full game in the file - never a stub.\n" +
            "After saving, your final answer (in Hindi, short) must list: the file name, how to play (controls), and the game's features as a short list."

    fun fileName(desc: String): String {
        val w = com.dk.assistant.NameMatch.latin(desc.lowercase()).lowercase().replace(Regex("[^a-z0-9]+"), " ").split(' ')
            .filter { it.length >= 3 && it !in setOf("game", "banao", "bana", "make", "create", "ek", "ekk", "wala", "wali", "mera", "mere", "dijiye", "chahiye", "geim", "gem") }
            .take(3).joinToString("_").ifBlank { "play" }
        return "game_${w}_${System.currentTimeMillis() / 1000 % 100000}.html"
    }

    /** पूरा होने पर बोलने लायक: "गेम तैयार है, Chrome में खुल गया है। चेक कर लीजिए… इसमें: …" Pure */
    fun doneSpeech(features: String): String {
        val f = com.dk.assistant.ComputerVoice.spoken(features, 0, max = 320)
        return "गेम तैयार है और Chrome में चल रहा है, चेक कर लीजिए कैसा बना है। " + if (f.isBlank()) "" else "इसमें: $f"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    /** आख़िरी बना गेम (बदलाव उसी में) */
    @Volatile var lastFile: String? = null

    /** गेम में बदलाव का काम (उसी फ़ाइल में). Pure */
    fun editTask(change: String, file: String): String =
        "The user wants a change in the HTML5 game \"$file\": \"${change.trim()}\".\n" +
            "read_file \"$file\", make exactly that change (keep everything else working: touch + keyboard controls, score, restart), " +
            "then save the COMPLETE updated game with write_file as \"$file\" (same name, one self-contained file). " +
            "Final answer in Hindi, short: what changed and the game's features as a short list."

    /** DK कंप्यूटर को गेम दो. return = मॉडल / यूज़र के लिए संदेश */
    fun start(c: Context, desc: String, voice: Boolean = true, change: Boolean = false): String {
        val ctx = c.applicationContext
        val old = lastFile?.takeIf { change && File(ComputerTools.dir(ctx), it).isFile }
        val file = old ?: fileName(desc)
        val t = if (old != null) editTask(desc, old) else task(desc, file)
        lastFile = file
        val before = ComputerSession.lines.size
        val t0 = System.currentTimeMillis()
        ComputerSession.start(ctx, t, voice = false)?.let { return "game_not_started: $it Tell the user exactly this." }
        DKLog.d("गेम: DK कंप्यूटर को दिया - $file")
        scope.launch { watch(ctx, t, file, before, t0, voice) }
        return STARTED + "DK Computer is making the game now (\"${desc.take(80)}\"). When it is ready DK opens it in Chrome by itself and you get a SYSTEM NOTE " +
            "with its features. Tell the user briefly that it has started."
    }
    private const val STARTED = "game_started: "

    /** लिखी / बोली बात वाला रास्ता (AgentRunner): यूज़र को सीधे हिंदी में */
    fun startForUser(c: Context, desc: String): String {
        val r = start(c, desc, voice = true)
        return if (r.startsWith(STARTED)) "ठीक है, DK कंप्यूटर आपका गेम बना रहा है। बनते ही Chrome में खोलकर दिखाऊँगी और बताऊँगी उसमें क्या-क्या है।"
        else r.removePrefix("game_not_started: ").removeSuffix(" Tell the user exactly this.")
    }


    /** DK कंप्यूटर का काम खत्म होने तक रुको -> गेम फ़ाइल -> Chrome -> खूबियाँ बोलो */
    private suspend fun watch(c: Context, task: String, file: String, before: Int, t0: Long, voice: Boolean) {
        val deadline = t0 + 25 * 60_000L
        var mine = -1
        var result: ComputerSession.Line? = null
        while (System.currentTimeMillis() < deadline) {
            delay(2000)
            val ls = ComputerSession.lines.toList()
            if (mine < 0) mine = (before until ls.size).firstOrNull { ls[it].role == "u" && ls[it].text.contains(task.take(60)) } ?: -1
            if (mine >= 0) {
                result = ls.drop(mine + 1).firstOrNull { it.role == "d" || it.role == "e" }
                if (result != null) break
            }
        }
        val game = File(ComputerTools.dir(c), file).takeIf { it.isFile && it.length() > 500 && it.lastModified() >= t0 - 2000 }
            ?: ComputerTools.list(c).firstOrNull { LocalSite.isPage(it.name) && it.lastModified() >= t0 && it.length() > 500 }
        if (game == null) {
            val why = result?.takeIf { it.role == "e" }?.text ?: if (result == null) "समय पर पूरा नहीं हुआ" else "गेम की फ़ाइल नहीं बनी"
            report(c, "गेम नहीं बन पाया: $why", "SYSTEM NOTE: DK Computer could NOT make the game ($why). Tell the user briefly. Do NOT say it is ready.", voice)
            return
        }
        val url = LocalSite.openInChrome(c, game)
        val features = result?.takeIf { it.role == "d" }?.text.orEmpty()
        val say = if (url != null) doneSpeech(features) else "गेम बन गया (${game.name}) पर Chrome नहीं खुल पाया।"
        report(c, say, "SYSTEM NOTE: the game is really ready (${game.name})" +
            (if (url != null) " and is now open in Chrome ($url)" else " but Chrome could not open") +
            ". Tell the user: चेक कर लीजिए कैसा गेम बना है, and briefly what is in the game: ${features.take(600)}. " +
            "If he wants changes, call make_game again with the change.", voice)
    }

    private fun report(c: Context, say: String, note: String, voice: Boolean) {
        DKLog.d("गेम: " + say.take(160))
        if (DKState.live) { LiveReport.note(note); return }
        DKState.reply(say)
        if (!voice) return
        val svc = DKAccessibilityService.instance ?: return
        svc.scope.launch { try { svc.speaker.speak(say) } catch (_: Exception) {} }
    }
}
