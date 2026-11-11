package com.dk.assistant.agent.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * नवंबर 2026 (मालिक): "DK को बोलूँ - जाओ ChatGPT / Claude / Gemini / Perplexity (या कोई भी AI ऐप) से बात करो, उससे
 * मुकाबला करो, सवाल-जवाब करो, या उससे कुछ सीखो - तो DK वह ऐप खोलकर खुद बातचीत करे. उसके सवाल का जवाब DK खुद दे,
 * मैं बस देखूँ-सुनूँ. सीखने को बोलूँ तो उसके काम का तरीका पूछे और वह तरीका अपने अंदर जोड़े - और हमेशा सीखता रहे."
 *
 * यह हिस्सा सिर्फ़ दिमाग + क्रम है (Android import नहीं -> JVM test):
 *  - DK का अगला संदेश DK की अपनी AI ([brain]) लिखती है - लक्ष्य + अब तक की पूरी बातचीत देखकर.
 *  - संदेश असली ऐप में भेजना + जवाब पढ़ना [send] करता है ([ImageGenFlow.askIn] - वही chat, "New chat" नहीं).
 *  - सामने वाला AI कुछ पूछे = DK खुद जवाब देता है (निजी जानकारी कभी नहीं).
 *  - आख़िर में छोटा सार + सीखे हुए तरीके ([Result.lessons]) - इन्हें Android हिस्सा हमेशा के लिए सेव करता है.
 */
enum class TalkMode(val hi: String, val en: String, val rounds: Int) {
    TALK("बातचीत", "a friendly conversation", 6),
    DEBATE("मुकाबला / बहस", "a debate / competition (each side argues its view, DK tries to win with facts and logic, politely)", 6),
    QUIZ("सवाल-जवाब", "a question-answer match (DK and the other AI take turns asking each other questions and answering them)", 8),
    LEARN("सीखना", "a learning session (DK is the student: asks how the other AI works, its methods, steps, tips and best practices for the topic)", 6),
    TASK("काम करवाना", "getting a task done (DK asks the other AI to do the user's task and follows up until it is done well)", 4);

    companion object {
        fun parse(s: String?, goal: String = ""): TalkMode {
            val m = s.orEmpty().trim().lowercase()
            values().firstOrNull { it.name.lowercase() == m }?.let { return it }
            when (m) {
                "debate", "compete", "competition" -> return DEBATE
                "qa", "quiz", "question_answer" -> return QUIZ
                "learn", "study", "teach_me" -> return LEARN
                "task", "do", "work" -> return TASK
                "talk", "chat" -> return TALK
            }
            val g = goal.lowercase()
            return when {
                Regex("सीख|सिख|learn|तरीका|तरीके|कैसे काम|method|tips").containsMatchIn(g) -> LEARN
                Regex("मुकाबला|मुक़ाबला|बहस|डिबेट|debate|compete|हराओ|जीत").containsMatchIn(g) -> DEBATE
                Regex("सवाल.?जवाब|प्रश्न.?उत्तर|quiz|क्विज़|क्विज|question.?answer").containsMatchIn(g) -> QUIZ
                Regex("काम करा|करवा|बनवा|लिखवा|बना दे|बनाओ|लिख दे|लिखो").containsMatchIn(g) -> TASK
                else -> TALK
            }
        }
    }
}

object AiTalkPlan {
    const val MAX_ROUNDS = 20
    const val MAX_SAY = 900

    /** मालिक के कहे नाम से ऐप: जाना-पहचाना AI, नहीं तो फ़ोन का कोई भी ऐप ([find] = launcher नाम), नहीं तो लक्ष्य में लिखा AI */
    fun target(name: String, goal: String, find: (String) -> Pair<String, String>?): ChatTarget? {
        ImageApp.parse(name)?.let { return it.target }
        if (name.isNotBlank() && name.trim().lowercase() !in setOf("auto", "any", "कोई भी")) find(name)?.let { (label, pkg) -> return ChatTarget(label, pkg, null) }
        return ImageApp.parse(goal)?.target
    }

    fun rounds(asked: Int, mode: TalkMode): Int = if (asked <= 0) mode.rounds else asked.coerceIn(1, MAX_ROUNDS)

    /** DK की AI के लिए नियम (हर बार वही) */
    fun system(app: String, mode: TalkMode, goal: String, user: String = "", learned: List<String> = emptyList()): String = buildString {
        append("You are DK, the personal AI assistant of ${user.ifBlank { "your owner" }} on his Android phone. ")
        append("Right now YOU are chatting, on his behalf, with another AI called \"$app\" inside the real $app app. He is only watching and listening.\n")
        append("SESSION TYPE: ${mode.en}.\n")
        append("YOUR OWNER'S GOAL (his own words): \"${goal.ifBlank { "talk with $app" }}\"\n")
        append("RULES:\n")
        append("- Each turn you write ONLY the next message DK types into $app. Natural, clear, at most 5 sentences (a list is fine when asking for steps).\n")
        append("- Write in the language of the owner's goal (Hindi in Devanagari if he spoke Hindi) unless he asked for another language.\n")
        append("- If $app asked YOU a question, first answer it yourself, briefly and honestly, as DK - then continue toward the goal.\n")
        append("- Never share private details of the owner: passwords, OTP, PIN, bank/card/Aadhaar numbers, phone numbers, addresses, contacts, location. Politely say you keep those private.\n")
        append("- Do not repeat a question that was already answered. Build on what $app just said.\n")
        when (mode) {
            TalkMode.LEARN -> append("- LEARN: ask how it does this, its exact method / steps / rules / prompts / checks, and why. Ask for concrete examples. After each answer, write down short reusable lessons.\n")
            TalkMode.DEBATE -> append("- DEBATE: take a clear side (or the side the owner gave), give strong points with facts, reply to its points, stay polite. End with a fair verdict when done.\n")
            TalkMode.QUIZ -> append("- QUIZ: take turns - answer its question if it asked one, then ask it one good new question on the topic. Keep score honestly.\n")
            TalkMode.TASK -> append("- TASK: ask $app to do the owner's task exactly; if the result is incomplete or wrong, ask it to fix that part.\n")
            TalkMode.TALK -> append("- TALK: be friendly and curious, keep the conversation on the owner's topic.\n")
        }
        append("- Set done=true when the goal is fully reached (or nothing useful is left to ask).\n")
        if (learned.isNotEmpty()) {
            append("WHAT DK ALREADY LEARNED BEFORE (do not ask these again):\n")
            learned.takeLast(15).forEach { append("- ").append(it.take(200)).append('\n') }
        }
        append("OUTPUT: only JSON {\"say\": \"next message to type\", \"done\": false, \"lessons\": [\"short reusable lesson\", ...]}. ")
        append("lessons = only NEW, practical things learned from $app's LAST answer (empty list if none).")
    }

    /** अब तक की बातचीत (पुरानी पहले) + इस बार क्या लिखना है */
    fun turnPrompt(app: String, lines: List<AiTalk.Line>, round: Int, max: Int): String = buildString {
        if (lines.isEmpty()) {
            append("The chat with $app has not started. Write DK's FIRST message (turn 1 of at most $max).")
            return@buildString
        }
        append("CONVERSATION SO FAR (oldest first):\n")
        // बहुत लंबी हो तो शुरू की 2 + आख़िरी 10 बातें (AI की सीमा)
        val shown = if (lines.size <= 12) lines else lines.take(2) + lines.takeLast(10)
        if (shown.size < lines.size) append("[... ${lines.size - shown.size} earlier lines skipped ...]\n")
        shown.forEach { l -> append(if (l.dk) "DK: " else "$app: ").append(l.text.take(1200)).append('\n') }
        append("\nWrite DK's next message (turn $round of at most $max).")
        if (round >= max) append(" This is the LAST turn: close the conversation politely (thank $app) and set done=true.")
    }

    data class Move(val say: String, val done: Boolean, val lessons: List<String>)

    fun parseMove(raw: String?): Move? {
        val o = json(raw) ?: return raw?.let { plain(it) }?.let { Move(it, false, emptyList()) }
        val say = clean(o.optString("say").ifBlank { o.optString("message") })
        return Move(say, o.optBoolean("done", false), list(o.optJSONArray("lessons")))
    }

    /** बातचीत खत्म - सार + सीखे तरीके */
    fun wrapPrompt(app: String, mode: TalkMode, goal: String, lines: List<AiTalk.Line>): String = buildString {
        append("DK just finished ${mode.en} with $app. Owner's goal: \"$goal\".\nCONVERSATION:\n")
        val shown = if (lines.size <= 16) lines else lines.take(2) + lines.takeLast(14)
        shown.forEach { l -> append(if (l.dk) "DK: " else "$app: ").append(l.text.take(900)).append('\n') }
        append("\nReturn only JSON {\"summary\": \"3-5 short sentences in Hindi (Devanagari) telling the owner what happened and the result")
        if (mode == TalkMode.DEBATE || mode == TalkMode.QUIZ) append(", including who did better and why")
        append("\", \"lessons\": [\"up to 8 short, practical, reusable lessons or methods DK learned from $app that DK can apply itself in future work (Hindi or English)\"]}")
    }

    data class Wrap(val summary: String, val lessons: List<String>)

    fun parseWrap(raw: String?): Wrap? {
        val o = json(raw) ?: return null
        return Wrap(o.optString("summary").trim().take(800), list(o.optJSONArray("lessons")))
    }

    /** DK की AI न चले तो पहला संदेश खुद (लक्ष्य से) */
    fun opener(mode: TalkMode, goal: String): String {
        val g = goal.trim().ifBlank { "कुछ अच्छी बातें" }
        return when (mode) {
            TalkMode.LEARN -> "नमस्ते! मैं DK हूँ, एक AI असिस्टेंट। मुझे आपसे सीखना है: $g। आप यह काम किस तरीके से करते हैं - अपने कदम, नियम और टिप्स बताइए।"
            TalkMode.DEBATE -> "नमस्ते! मैं DK हूँ। चलिए इस विषय पर मुकाबला करते हैं: $g। आप अपनी राय और सबसे मज़बूत तर्क बताइए, फिर मैं अपनी बात रखूँगा।"
            TalkMode.QUIZ -> "नमस्ते! मैं DK हूँ। चलिए सवाल-जवाब खेलते हैं: $g। पहले आप मुझसे एक सवाल पूछिए, फिर मैं आपसे पूछूँगा।"
            TalkMode.TASK -> g
            TalkMode.TALK -> "नमस्ते! मैं DK हूँ, एक AI असिस्टेंट। आपसे बात करनी है: $g"
        }
    }

    fun clean(s: String): String = s.trim().trim('"', '“', '”').replace(Regex("^(DK|डीके)\\s*:\\s*", RegexOption.IGNORE_CASE), "").trim().take(MAX_SAY)

    private fun plain(raw: String): String? {
        val t = clean(raw.replace(Regex("```[a-z]*"), "").replace("```", ""))
        return t.takeIf { it.length >= 2 && !t.startsWith("{") }
    }

    private fun list(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf { s -> s.length >= 4 }?.take(300) }.distinct()

    private fun json(raw: String?): JSONObject? {
        val t = raw?.trim() ?: return null
        val a = t.indexOf('{'); val b = t.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try { JSONObject(t.substring(a, b + 1)) } catch (_: Exception) { null }
    }
}

/**
 * एक पूरी बातचीत: DK लिखे -> ऐप जवाब दे -> DK पढ़कर अगला लिखे ... ([max] बार तक, या लक्ष्य पूरा, या मालिक रोके).
 * [send] (text, fresh) = असली ऐप में भेजो + जवाब पढ़ो (fresh = पहला संदेश, नई chat). [brain] (system, user) = DK की AI.
 */
class AiTalk(
    private val app: String,
    private val mode: TalkMode,
    private val goal: String,
    private val max: Int,
    private val send: suspend (text: String, fresh: Boolean) -> ImageGenResult,
    private val brain: suspend (system: String, user: String) -> String?,
    private val user: String = "",
    private val learnedBefore: List<String> = emptyList(),
    private val onLine: (Line) -> Unit = {},
    private val stopped: () -> Boolean = { false },
) {
    data class Line(val dk: Boolean, val text: String)
    data class Result(val ok: Boolean, val lines: List<Line>, val lessons: List<String>, val summary: String, val error: String = "", val stoppedByUser: Boolean = false) {
        val turns get() = lines.count { !it.dk }
    }

    suspend fun run(): Result {
        val lines = mutableListOf<Line>()
        val lessons = LinkedHashSet<String>()
        val sys = AiTalkPlan.system(app, mode, goal, user, learnedBefore)
        var error = ""
        var userStop = false
        var round = 1
        var move = next(sys, lines, round) ?: AiTalkPlan.Move(AiTalkPlan.opener(mode, goal), false, emptyList())
        var last = false
        while (move.say.isNotBlank()) {
            if (stopped()) { userStop = true; break }
            val r = send(move.say, round == 1)
            if (!r.ok) { error = r.error.ifBlank { "$app में भेज नहीं पाया" }; break }
            lines += Line(true, move.say).also(onLine)
            val reply = r.appReply.trim()
            if (reply.isBlank()) { error = r.error.ifBlank { "$app का जवाब पढ़ नहीं पाया" }; break }
            lines += Line(false, reply).also(onLine)
            if (last || stopped()) { userStop = !last; break }
            round++
            if (round > max) break                                   // सारी बारियाँ हो गईं
            val m = next(sys, lines, round) ?: break                  // DK की AI नहीं चली = यहीं तक
            lessons += m.lessons
            // लक्ष्य पूरा: विदा/धन्यवाद वाला एक आख़िरी संदेश (हो तो) और बस
            if (m.done) { if (m.say.isBlank()) break; last = true }
            move = m
        }
        val wrap = if (lines.any { !it.dk }) AiTalkPlan.parseWrap(ask(sys, AiTalkPlan.wrapPrompt(app, mode, goal, lines))) else null
        wrap?.lessons?.let { lessons += it }
        val summary = wrap?.summary?.ifBlank { null } ?: fallbackSummary(lines, error)
        val ok = lines.any { !it.dk }
        return Result(ok, lines, lessons.toList().take(12), summary, if (ok && userStop) "" else error, userStop)
    }

    private suspend fun next(sys: String, lines: List<Line>, round: Int): AiTalkPlan.Move? {
        return AiTalkPlan.parseMove(ask(sys, AiTalkPlan.turnPrompt(app, lines, round, max)))?.takeIf { it.say.isNotBlank() || it.done }
    }

    /** DK की AI - गलती = null; पर मालिक ने रोका (cancel) तो रुकना ही है */
    private suspend fun ask(sys: String, user: String): String? = try { brain(sys, user) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }

    private fun fallbackSummary(lines: List<Line>, error: String): String {
        val last = lines.lastOrNull { !it.dk }?.text?.take(300)
        return when {
            last != null -> "$app से ${lines.count { !it.dk }} बार बात हुई। आख़िरी जवाब: \"$last\""
            error.isNotBlank() -> error
            else -> "$app से बात नहीं हो पाई।"
        }
    }
}
