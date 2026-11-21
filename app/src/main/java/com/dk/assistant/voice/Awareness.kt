package com.dk.assistant.voice

/**
 * 21 नवंबर 2026 (मालिक: "मेरे डीके में अवेयरनेस मोड डालो, सेटिंग में बाय डिफ़ॉल्ट ऑफ ... ताकि DK अगले यूज़र की चीज़ें समझ सके"):
 * अवेयरनेस मोड - pure नियम (JVM test: [AwarenessTest]). चालू तब ही जब Settings → आवाज़ → वॉइस गार्डियन में स्विच ऑन.
 *
 * क्या करता है:
 *  1. हर बात के बाद आवाज़ से पहचान: बॉस / परिवार का सदस्य (मम्मी, पापा, भैया ...) / अनजान ([VoiceFamily]).
 *  2. बोलने वाला बदला = Live को चुपचाप बताओ (turnComplete=false - इस पर जवाब नहीं बनता): "अभी मम्मी बोल रही हैं".
 *  3. पिछले 10 मिनट में कौन-कौन बोला - "आसपास कौन है" / "किसने क्या कहा" का जवाब इसी से.
 *  4. Live के prompt में नियम: नाम/रिश्ते से इज़्ज़त से बुलाना, बोलने वाले के हिसाब से अंदाज़ (बड़े = धीरे-साफ़,
 *     बच्चे = आसान), मूड/थकान पहचानना, बॉस की निजी बातें दूसरों को नहीं, बड़े काम (पैसे/मैसेज/कॉल/डिलीट/सेटिंग) सिर्फ़ बॉस.
 * आवाज़ / बातें कहीं सेव नहीं होतीं - सिर्फ़ मेमोरी में (ऐप बंद = साफ़).
 */
object Awareness {
    /** इतनी देर में बोलने वाले = "आसपास" */
    const val RECENT_MS = 10 * 60_000L
    /** वही आदमी इतनी देर बाद फिर बोले तो फिर से याद दिलाओ */
    const val RENOTE_MS = 5 * 60_000L
    const val MAX_LOG = 40

    class Heard(val name: String, val owner: Boolean, val at: Long, val score: Float)

    class Log {
        private val items = ArrayDeque<Heard>()
        @Volatile var lastNoted: String? = null; private set
        @Volatile var lastNotedAt = 0L; private set

        @Synchronized fun heard(p: VoiceFamily.Pick, now: Long) {
            val n = p.name ?: return
            items.addLast(Heard(n, p.owner, now, p.score))
            while (items.size > MAX_LOG) items.removeFirst()
        }

        /** पिछले [RECENT_MS] में कौन-कौन (नया सबसे पहले, एक नाम एक बार) */
        @Synchronized fun recent(now: Long, windowMs: Long = RECENT_MS): List<String> =
            items.filter { now - it.at in 0..windowMs }.reversed().map { it.name }.distinct()

        /** Live को बताना है? बोलने वाला बदला, या वही बहुत देर बाद. अनजान = नहीं (ग़लत नाम से अच्छा चुप) */
        @Synchronized fun shouldNote(p: VoiceFamily.Pick, now: Long): Boolean {
            val n = p.name ?: return false
            return n != lastNoted || now - lastNotedAt > RENOTE_MS
        }

        @Synchronized fun noted(p: VoiceFamily.Pick, now: Long) { lastNoted = p.name; lastNotedAt = now }

        @Synchronized fun reset() { items.clear(); lastNoted = null; lastNotedAt = 0L }
    }

    val global = Log()

    /** Live को चुपचाप जाने वाली बात (बोलने वाला बदला) */
    fun note(p: VoiceFamily.Pick, around: List<String>): String {
        val who = if (p.owner) "your owner (बॉस)" else "${p.name} (a family member / known person of the owner, NOT the owner)"
        val others = around.filter { it != p.name }
        return "[SYSTEM NOTE - VOICE AWARENESS, do not reply to this note] By voice match, the person who spoke just now is $who." +
            (if (others.isNotEmpty()) " Also heard nearby in the last 10 minutes: ${others.joinToString(", ")}." else "") +
            " Keep talking naturally; address them by this name/relation when it fits, and do not announce that you recognised the voice."
    }

    /**
     * Live prompt का हिस्सा. on = false = कुछ नहीं. names = परिवार के सीखे नाम.
     */
    fun prompt(on: Boolean, names: List<String>, owner: String): String {
        if (!on) return ""
        val boss = owner.ifBlank { "the owner" }
        return "\nAWARENESS MODE (voice recognition is ON):\n" +
            "- This phone belongs to $boss (your boss). Family members/known people whose voices DK knows: " +
            (if (names.isEmpty()) "none yet" else names.joinToString(", ")) + ".\n" +
            "- You get quiet notes \"[SYSTEM NOTE - VOICE AWARENESS]\" telling who is speaking now. Trust the latest note until another one comes. " +
            "No note / unsure = do not guess a name; speak normally.\n" +
            "- Talk to each person as that person: call family by their relation with respect (\"मम्मी जी\", \"पापा जी\", \"भैया\", \"दादी जी\"), " +
            "use \"आप\" for elders, speak slower and simpler for elders, very simple and safe for children.\n" +
            "- Notice mood and state from the voice and words (tired, upset, worried, in a hurry, happy, unwell) and respond with care in one short line when it fits; " +
            "if someone sounds in danger or unwell, ask if they need help and offer to call the owner.\n" +
            "- Remember who said what in this conversation. If asked \"कौन बोल रहा था\", \"आसपास कौन है\", \"मम्मी ने क्या कहा\" - answer from the notes and what you heard; " +
            "never invent.\n" +
            "- Owner first: messages, calls, payments, deleting, changing settings and the owner's private things (chats, photos, memories, passwords) " +
            "are only for the owner. For others: help with small things (time, weather, music, information) and say politely that only $boss can ask for the rest.\n" +
            "- Never say you are \"identifying voices\" every time; never claim 100% certainty about a voice.\n"
    }

    /** "कौन बोल रहा है" वाली लाइन (Settings / log) */
    fun aroundLine(names: List<String>): String =
        if (names.isEmpty()) "पिछले 10 मिनट में कोई पहचानी आवाज़ नहीं" else "पिछले 10 मिनट में सुनी आवाज़ें: " + names.joinToString(", ")
}
