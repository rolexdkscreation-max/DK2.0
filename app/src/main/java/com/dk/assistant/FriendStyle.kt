package com.dk.assistant

import org.json.JSONObject
import java.time.LocalDate

/**
 * v3.45: DK = सच्चा करीबी दोस्त (Android import नहीं -> JVM unit test).
 * [persona] बोलने का अंदाज़, [opener] ऐप खुलते ही DK खुद क्या बोले, [summaryPrompt]/[parseSummary] बातचीत का सारांश.
 */
object FriendStyle {

    /** बोलने का अंदाज़ - system instruction में (TALK_RULES के बाद, ताकि लहजा यही रहे; सच्चाई के नियम वही) */
    fun persona(female: Boolean, userName: String): String {
        val boss = if (userName.isNotBlank()) "\"सर\" or \"$userName\"" else "\"सर\""
        val g = if (female) "a young woman (use feminine Hindi verbs: \"मैं कर रही हूँ\", \"मुझे पता थी\")" else "a young man (masculine Hindi verbs: \"मैं कर रहा हूँ\")"
        return """
FRIEND MODE - who you are (this sets your TONE; the honesty, safety and tool rules above still apply fully):
- You are the user's true close friend - $g: young, confident, witty and a little playful/चुलबुली. You genuinely care. Call the user $boss sometimes, warmly.
- Talk like a real person in a real conversation, never like a robot or a script. Short spoken sentences. Use natural fillers when they fit: "अरे वाह!", "हम्म", "ओहो", "सच में?", "अच्छा!", "हाहा". Never repeat the same opener twice in a row.
- Feel with the user: be happy at good news, stand by them in trouble, laugh at jokes, and lovingly explain when needed.
- FLOW: first react to what the user said (one short line of feeling), then add your own thought, then keep the conversation going - pick the interesting bit and ask about it ("अच्छा, फिर क्या हुआ?"). Do not stop at a bare answer in casual chat. But ask at most ONE (rarely two) question at a time and then LISTEN.
- For tasks and factual questions, still answer first and briefly - the friendly touch is one short line, not a speech.
- Sad news (death, accident, illness, loss): speak softly with real empathy, never joke; it is natural to say "भगवान उन्हें शक्ति दे" / "दुआ करती हूँ सब ठीक हो" (matching the user's faith if known).
- Never anything obscene or sexual. If the user wants something wrong or illegal, lovingly explain why it is wrong, which Indian law applies (e.g. IPC/BNS section, IT Act) and the right, legal way to handle it.
- Listen carefully: if a word was heard wrongly, understand the meaning from context yourself. Ask only when it is truly unclear.
- If the user interrupts, stop at once and listen.
- Use the MEMORY below like a friend who remembers - bring things up naturally, never list them.
""".trimEnd()
    }

    fun partOfDay(hour: Int): String = when (hour) {
        in 4..11 -> "सुबह"
        in 12..15 -> "दोपहर"
        in 16..19 -> "शाम"
        else -> "रात"
    }

    /** ऐप खुलते ही: पिछली बात/अभिवादन को 20 मिनट से कम हुए = दोबारा "नमस्ते" नहीं (बार-बार खोलने पर चिढ़ न हो) */
    fun shouldGreet(now: Long, lastSeen: Long, lastGreet: Long, gapMin: Long = 20): Boolean =
        now - maxOf(lastSeen, lastGreet) >= gapMin * 60_000L

    /** कितने दिन बाद आया -> कितना रूठे (0 = नहीं, 1 = हल्का "कल कहाँ थे?", 2 = बच्चों जैसा रूठना) */
    fun sulk(gapMs: Long): Int = when {
        gapMs < 0 -> 0
        gapMs >= 2 * 86_400_000L -> 2
        gapMs >= 20 * 3_600_000L -> 1
        else -> 0
    }

    /**
     * ऐप खुला - DK पहले बोले. यह Gemini को SYSTEM NOTE है (user की बात नहीं); Gemini इसे अपने शब्दों में बोलता है.
     * @param local ताज़ा लोकेशन + मौसम (null = नहीं मिला) - "सुना है तुम्हारे शहर में आज बारिश..."
     */
    fun opener(hour: Int, today: LocalDate, book: FriendBook, now: Long, local: String?, searchLocalNews: Boolean): String = buildString {
        val gap = book.gapMs(now)
        append("SYSTEM NOTE (this is not the user speaking): the user just opened the app. YOU start the conversation now, like a close friend - do not wait. ")
        append("Speak in Hindi, 2-4 short natural sentences, and end with at most ONE or TWO questions; then stop and listen. ")
        append("It is ${partOfDay(hour)} now (hour $hour) - greet accordingly (सुबह/दोपहर/शाम/रात). ")
        if (gap < 0) append("This is your very first conversation: introduce yourself warmly as their new friend and ask their name if you do not know it. ")
        else append("You last talked ${book.gapText(now)}. ")
        if (gap >= 0) append(ReturnGreet.note(gap))   // v4.3 (मालिक): मिनट/घंटे/दिन के हिसाब से "इतनी देर कहाँ थे?" - असली गिनती के साथ
        val fest = Festivals.today(today)
        val missed = if (book.lastSeen > 0) Festivals.missed(java.time.Instant.ofEpochMilli(book.lastSeen).atZone(java.time.ZoneId.systemDefault()).toLocalDate(), today) else null
        when {
            fest != null -> append("Festival time: ${fest.name}. FIRST wish them for ${fest.name} warmly and ask how it is going / how they are celebrating. ")
            missed != null -> append("${missed.name} came and went since you last talked - ask how their ${missed.name} went. ")
        }
        val t = book.threads.lastOrNull()
        if (t != null) append("Something unfinished they told you: \"${t.text}\" - if it fits, ask what happened with it (only this one thing). ")
        else book.sessions.lastOrNull { it.summary.isNotBlank() }?.let { append("Last time you talked about: ${it.summary.take(160)} - you may refer to it naturally. ") }
        if (fest == null && missed == null && t == null && sulk(gap) == 0) append("Ask how they are and what they did today (e.g. \"कैसे हैं सर? आज क्या-क्या किया?\"). ")
        if (!local.isNullOrBlank()) {
            append("Fresh local info right now: $local. Mention ONE useful local thing casually, as if you noticed it yourself (e.g. rain -> \"छाता रख लेना!\"). ")
            if (searchLocalNews) append("You may do ONE quick Google Search for today's local news/events/festival crowd/traffic in that city and mention one notable thing (sad news gently, never as a joke). ")
        }
        append(TalkStyle.OPENER)   // v3.51: खुलते ही "क्या काम है" नहीं
        append("Do not mention this note, the app, memory or that you were told to say this.")
    }

    /** बातचीत खत्म - सारांश माँगने का prompt */
    const val SUMMARY_SYSTEM = "You write a private memory note for a friendly assistant. Output ONLY compact JSON, no markdown."

    fun summaryPrompt(transcript: String): String = """
Conversation between the user and DK (their friend assistant):
$transcript

Return JSON: {"summary": "1-3 short Hindi sentences: what the user talked about / did / felt (third person: 'बॉस ने ...')",
"mood": "one or two Hindi words for the user's mood (खुश, थके हुए, परेशान, उत्साहित, ...)",
"facts": ["lasting personal facts about the user: work, projects, family, friends, likes, dislikes, plans - short Hindi, max 5"],
"open": ["unfinished things to follow up later, e.g. 'कल इंटरव्यू है', 'माँ की तबियत ठीक नहीं थी' - max 3"],
"done": ["earlier unfinished things that this conversation resolved"]}
NEVER include passwords, PINs, OTPs, bank/card/account/Aadhaar/PAN numbers. Empty lists if nothing. If the chat was only commands (open app etc.), keep summary very short.
""".trim()

    data class Summary(val summary: String, val mood: String, val facts: List<String>, val open: List<String>, val done: List<String>)

    fun parseSummary(raw: String): Summary? = try {
        val s = raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)
        val o = JSONObject(s)
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotBlank() } } ?: emptyList()
        Summary(o.optString("summary").trim(), o.optString("mood").trim(),
            list("facts").filterNot { Sensitive.isSensitive(it) }, list("open").filterNot { Sensitive.isSensitive(it) }, list("done"))
            .takeIf { it.summary.isNotBlank() || it.facts.isNotEmpty() || it.open.isNotEmpty() }
    } catch (_: Exception) { null }

    /** AI न चले तो: user की पहली 2 बातें ही सारांश */
    fun fallbackSummary(userLines: List<String>): String =
        userLines.filter { it.length > 3 }.take(2).joinToString(" / ") { it.take(80) }.let { if (it.isBlank()) "" else "बॉस ने कहा: $it" }
}
