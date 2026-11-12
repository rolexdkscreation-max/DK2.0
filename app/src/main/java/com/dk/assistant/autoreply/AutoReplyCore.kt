package com.dk.assistant.autoreply

/**
 * नवंबर 2026 (मालिक): "ऑटो रिप्लाई" - सेटिंग में अलग हिस्सा. फ़ोन में जो-जो मैसेज वाले ऐप हैं (WhatsApp, WhatsApp Business,
 * Telegram, Messenger, Instagram, Signal, SMS...) उनकी सूची, हर एक का अपना स्विच (शुरू में बंद). जिसका स्विच चालू है उसका
 * नया मैसेज आए तो DK मालिक की ओर से, मालिक के ही लिखने के ढंग में, सिर्फ़ उसी बात का जवाब देता है - अपनी तरफ़ से कुछ नहीं.
 *
 * यह pure हिस्सा है (Android import नहीं) - JUnit से जाँचा जाता है. Android वाला हिस्सा [AutoReply].
 */
object AutoReplyCore {
    data class App(val pkg: String, val name: String)

    /** जाने-माने मैसेज वाले ऐप - इनमें से जो फ़ोन में हैं, वही सेटिंग में दिखते हैं */
    val APPS: List<App> = listOf(
        App("com.whatsapp", "WhatsApp"),
        App("com.whatsapp.w4b", "WhatsApp Business"),
        App("org.telegram.messenger", "Telegram"),
        App("org.telegram.messenger.web", "Telegram"),
        App("org.thunderdog.challegram", "Telegram X"),
        App("com.facebook.orca", "Messenger"),
        App("com.facebook.mlite", "Messenger Lite"),
        App("com.instagram.android", "Instagram"),
        App("com.google.android.apps.messaging", "Google Messages (SMS)"),
        App("com.samsung.android.messaging", "Samsung Messages (SMS)"),
        App("org.thoughtcrime.securesms", "Signal"),
        App("com.snapchat.android", "Snapchat"),
        App("jp.naver.line.android", "LINE"),
        App("com.viber.voip", "Viber"),
        App("com.discord", "Discord"),
        App("com.Slack", "Slack"),
        App("com.microsoft.teams", "Microsoft Teams"),
        App("com.skype.raider", "Skype"),
        App("com.google.android.apps.dynamite", "Google Chat"),
        App("com.truecaller", "Truecaller"),
        App("com.kakao.talk", "KakaoTalk"),
        App("com.imo.android.imoim", "imo"),
        App("com.bsb.hike", "Hike"),
        App("in.mohalla.sharechat", "ShareChat"),
        App("com.linkedin.android", "LinkedIn"),
        App("com.twitter.android", "X (Twitter)"),
    )

    fun known(pkg: String): App? = APPS.firstOrNull { it.pkg == pkg }

    /**
     * सेटिंग में दिखने वाली सूची: जाने-माने ऐप जो फ़ोन में हैं + वे ऐप जिनके "जवाब दें" वाले चैट-notification DK ने
     * अपने-आप पहचाने ([extra]); हर पैकेज एक बार, अपना ऐप नहीं.
     */
    fun available(installed: (String) -> Boolean, extra: List<App>, ownPkg: String): List<App> {
        val out = LinkedHashMap<String, App>()
        APPS.filter { it.pkg != ownPkg && installed(it.pkg) }.forEach { out.putIfAbsent(it.pkg, it) }
        extra.filter { it.pkg != ownPkg && it.pkg.isNotBlank() && installed(it.pkg) }.forEach { out.putIfAbsent(it.pkg, it) }
        return out.values.toList()
    }

    /** कोई अनजान ऐप मैसेज वाला है? (MessagingStyle का चैट-notification + जवाब देने का बटन) */
    fun looksLikeMessenger(hasMessages: Boolean, canReply: Boolean): Boolean = hasMessages && canReply

    // ---------- फ़ैसला: जवाब दें या नहीं ----------

    sealed class Decision {
        data class Skip(val why: String) : Decision()
        /** पैसे / OTP / बैंक जैसी बात - DK खुद नहीं, मालिक को बताओ */
        object AskOwner : Decision()
        object Reply : Decision()
    }

    /** एक ही बातचीत में दो जवाबों के बीच कम से कम इतना (लगातार मशीन जैसे जवाब नहीं) */
    const val GAP_MS = 6_000L
    /** एक बातचीत में एक घंटे में ज़्यादा से ज़्यादा जवाब (कभी अंतहीन बात नहीं) */
    const val MAX_PER_HOUR = 20
    /** कई मैसेज जल्दी-जल्दी आएँ तो रुककर एक ही जवाब */
    const val SETTLE_MS = 3_500L

    /** हर बातचीत का हिसाब (आख़िरी जवाब कब / किस बात का / इस घंटे कितने) */
    data class ChatState(var lastAt: Long = 0L, var lastAnswered: String = "", var hourStart: Long = 0L, var inHour: Int = 0)

    fun decide(
        masterOn: Boolean, appOn: Boolean, ownPkg: String, pkg: String, lastText: String, lastMine: Boolean,
        isGroup: Boolean, canReply: Boolean, otherBusy: Boolean, st: ChatState?, now: Long,
    ): Decision {
        if (!masterOn) return Decision.Skip("off")
        if (pkg == ownPkg) return Decision.Skip("own app")
        if (!appOn) return Decision.Skip("app off")
        if (otherBusy) return Decision.Skip("other mode busy")
        if (!canReply) return Decision.Skip("no reply button")
        if (isGroup) return Decision.Skip("group")
        if (lastMine) return Decision.Skip("my own message")
        val t = lastText.trim()
        if (t.isEmpty()) return Decision.Skip("empty")
        if (st != null) {
            if (st.lastAnswered == t) return Decision.Skip("already answered")
            if (now - st.lastAt < GAP_MS) return Decision.Skip("too soon")
            if (now - st.hourStart < 3_600_000L && st.inHour >= MAX_PER_HOUR) return Decision.Skip("hour limit")
        }
        if (needsOwner(t)) return Decision.AskOwner
        return Decision.Reply
    }

    fun sent(st: ChatState, answered: String, now: Long) {
        if (now - st.hourStart >= 3_600_000L) { st.hourStart = now; st.inHour = 0 }
        st.inHour++; st.lastAt = now; st.lastAnswered = answered.trim()
    }

    fun key(pkg: String, title: String) = pkg + "|" + title.trim().lowercase()

    // ---------- मालिक का लिखने का ढंग ----------

    /** मालिक के भेजे मैसेज (notification की बातचीत में भेजने वाला खाली) से ढंग के नमूने - नए आगे, दोहराव नहीं */
    fun addStyle(old: List<String>, mine: List<String>, max: Int = 30): List<String> {
        val fresh = mine.map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length in 2..220 && !unsafe(it) && !it.startsWith("http") }
        val out = LinkedHashSet<String>()
        fresh.asReversed().forEach { out += it }
        old.forEach { out += it }
        return out.take(max)
    }

    const val BASE_SYSTEM = "You reply AS the phone owner himself (first person, his own voice) - never as an assistant, bot or AI, never say you are DK. " +
        "Read the chat and answer ONLY what the other person last said or asked, staying exactly on that topic - never start a new topic, " +
        "never add your own suggestions, news, questions or small talk that they did not ask for. " +
        "Never invent facts, promises, amounts, addresses, times, plans or meetings the owner has not told you - if the answer needs that, " +
        "give a short polite holding reply in the owner's style (e.g. 'Abhi thoda busy hoon, thodi der mein batata hoon'). " +
        "Never share OTP, passwords, PIN, bank or card details. Keep it short and natural like a real chat text. " +
        "Output ONLY the message text to send - no quotes, no labels, no explanation."

    /** AI का नियम: मालिक के असली मैसेज के नमूनों जैसा ही लिखो (भाषा, लिपि, लंबाई, शब्द, इमोजी की आदत) */
    fun system(app: String, samples: List<String>): String {
        val s = samples.take(15)
        val style = if (s.isEmpty())
            "Write in Hinglish (Hindi in Roman letters mixed with everyday English words, like 'Haan bhai, kal milte hain'), 1-2 short sentences, " +
                "emojis only if they used them. Match their tone (friendly, respectful with elders)."
        else "Write EXACTLY in the owner's own texting style, copied from these real messages he sent earlier - same language and script, " +
            "same length, same words and spellings, same punctuation and emoji habit (if he never uses emojis, use none):\n" +
            s.joinToString("\n") { "- " + it.take(160) }
        return "This is a $app chat. " + BASE_SYSTEM + "\n" + style
    }

    data class Msg(val text: String, val mine: Boolean)

    fun prompt(who: String, msgs: List<Msg>, max: Int = 14): String {
        val name = who.ifBlank { "Them" }
        val chat = msgs.takeLast(max).joinToString("\n") { (if (it.mine) "Me" else name) + ": " + it.text.take(400) }
        return "Chat with ${who.ifBlank { "this person" }} (oldest first):\n$chat\n\nWrite my next reply to their last message only."
    }

    /** AI के जवाब से भेजने लायक सादा text (null = भेजने लायक कुछ नहीं) */
    fun clean(raw: String?): String? {
        var t = raw?.trim().orEmpty()
        t = t.removePrefix("```").removeSuffix("```").trim()
        t = t.replace(Regex("^(reply|message|me|मैं|जवाब|answer)\\s*[:：-]\\s*", RegexOption.IGNORE_CASE), "")
        t = t.trim().trim('"', '“', '”', '\'').trim()
        if (t.isEmpty()) return null
        if (Regex("\\b(as an ai|language model|i am an assistant|मैं एक (ai|एआई))\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) return null
        return t.take(500)
    }

    private val PRIVATE = Regex("\\b(otp|password|passcode|pin|cvv|upi pin|पासवर्ड|ओटीपी|पिन)\\b", RegexOption.IGNORE_CASE)
    private val LONG_DIGITS = Regex("\\d[\\d\\s-]{5,}\\d")

    /** भेजने वाले text में निजी चीज़? (OTP/पासवर्ड/लंबे अंक) */
    fun unsafe(text: String): Boolean = PRIVATE.containsMatchIn(text) || LONG_DIGITS.findAll(text).any { m -> m.value.count { it.isDigit() } >= 6 }

    private val SENSITIVE_ASK = Regex("(otp|password|पासवर्ड|ओटीपी|\\bpin\\b|cvv|bank|बैंक|account number|खाता नंबर|पैसे भेज|paise bhej|transfer|udhaar|उधार|loan|लोन)", RegexOption.IGNORE_CASE)

    /** सामने वाला ऐसा कुछ माँग रहा जिस पर मालिक खुद फ़ैसला करे */
    fun needsOwner(text: String): Boolean = SENSITIVE_ASK.containsMatchIn(text)

    /** Live / लॉग के लिए छोटी ख़बर */
    fun sentNote(app: String, who: String, incoming: String, reply: String) =
        "AUTO REPLY ($app): $who ने लिखा \"${incoming.take(120)}\" - DK ने आपकी ओर से जवाब दिया: \"${reply.take(160)}\". " +
            "Tell the user in one short Hindi line only if he is talking to you right now."

    fun askNote(app: String, who: String, incoming: String) =
        "AUTO REPLY ($app): $who ने पैसे/OTP/बैंक जैसी बात लिखी: \"${incoming.take(140)}\" - DK ने खुद जवाब नहीं दिया. " +
            "Tell the user and ask what to reply (notification_reply)."
}
