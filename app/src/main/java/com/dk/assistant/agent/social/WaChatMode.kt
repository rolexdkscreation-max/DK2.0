package com.dk.assistant.agent.social

/**
 * नवंबर 2026 (मालिक: "चैटिंग मोड / WhatsApp मोड ऑन करो - DK लगातार बताए कौन क्या पूछ रहा है, पहले मुझसे पूछे
 * 'बॉस क्या जवाब देना है', मैं बोलूँ तो वही टाइप करे; 'तुम खुद कर दो' बोलूँ तो खुद करे; 2-3 लोग एक साथ लिखें
 * तो पहले पूछे किससे बात करनी है - जिसे चुनूँ सिर्फ़ उसी से, 'तीनों से' बोलूँ तो तीनों से").
 *
 * यहाँ सिर्फ़ शुद्ध नियम (JUnit से जाँचे जाते हैं). Android वाला हिस्सा: [com.dk.assistant.agent.android.WaAutoChat].
 */
object WaChatMode {
    /** चैटिंग मोड कितनी देर (मिनट) - बोला न हो तो */
    const val DEFAULT_MIN = 60
    /** इतने समय में 2+ लोगों के मैसेज = "एक साथ" -> पहले पूछो किससे बात करनी है */
    const val TOGETHER_MS = 2 * 60_000L
    /** जो चुना नहीं, उसका नया मैसेज - बॉस को बस एक बार बताओ (इतने समय में दोबारा नहीं) */
    const val TELL_AGAIN_MS = 3 * 60_000L

    const val ON_LINE = "ठीक है बॉस, चैटिंग मोड ऑन कर दिया।"

    data class Incoming(val chat: String, val text: String, val at: Long)

    /** एक चैटिंग-मोड सत्र */
    class State(val until: Long) {
        /** बॉस ने जिनसे बात करनी चुनी (key) */
        val picked = linkedSetOf<String>()
        var all = false
        /** जिनके लिए बॉस ने कहा "तुम खुद कर दो" */
        val self = linkedSetOf<String>()
        var selfAll = false
        /** हर चैट का सबसे नया बिना-जवाब मैसेज */
        val waiting = LinkedHashMap<String, Incoming>()
        /** key -> दिखने वाला नाम */
        val names = HashMap<String, String>()
        var askedPick: Set<String> = emptySet()
        val toldAt = HashMap<String, Long>()
        val lastSent = HashMap<String, String>()
        var replies = 0
    }

    sealed class Route {
        /** 2+ लोग एक साथ - बॉस से पूछो किससे बात करनी है */
        data class AskPick(val people: List<Incoming>) : Route()
        /** बॉस को बताओ क्या लिखा, पूछो क्या जवाब दूँ (sensitive = पैसे/OTP... खुद कभी नहीं) */
        data class AskBoss(val msg: Incoming, val sensitive: Boolean) : Route()
        /** बॉस ने "खुद कर दो" कहा है - DK खुद जवाब बनाकर भेजे, फिर बॉस को बताए */
        data class Auto(val msg: Incoming) : Route()
        /** कोई और (चुना नहीं) लिख रहा है - बॉस को बस बताओ */
        data class TellNew(val msg: Incoming) : Route()
        data class Skip(val why: String) : Route()
    }

    private val ON_WORDS = Regex("(चैटिंग|चैट|chatting|chat|व्हाट्सएप|व्हाट्सऐप|whatsapp|वॉट्सऐप)\\s*(वाला\\s*)?(मोड|mode)\\s*(ऑन|on|चालू|शुरू|start|कर)", RegexOption.IGNORE_CASE)
    private val OFF_WORDS = Regex("(चैटिंग|चैट|chatting|chat|व्हाट्सएप|व्हाट्सऐप|whatsapp|वॉट्सऐप)\\s*(वाला\\s*)?(मोड|mode)\\s*(ऑफ|off|बंद|band|stop)", RegexOption.IGNORE_CASE)

    fun saysOn(text: String): Boolean = ON_WORDS.containsMatchIn(text) && !OFF_WORDS.containsMatchIn(text)
    fun saysOff(text: String): Boolean = OFF_WORDS.containsMatchIn(text)

    private val ALL_WORDS = Regex("^(all|sab|sabse|sabhi|sabko|सब|सबसे|सभी|सबको|सबसे ही|दोनों|दोनो|दोनों से|तीनों|तीनो|तीनों से|चारों|everyone|both|सब लोग|सब लोगों से)$", RegexOption.IGNORE_CASE)

    fun isAll(contact: String): Boolean = ALL_WORDS.matches(contact.trim().trimEnd('।', '.', '!'))

    fun minutes(asked: Int): Int = if (asked <= 0) DEFAULT_MIN else asked.coerceAtMost(WaScreen.MAX_MIN)

    private fun find(s: State, name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return null
        val k = WaScreen.key(n)
        if (s.names.containsKey(k)) return k
        return s.names.entries.firstOrNull { WaScreen.nameMatches(n, it.value) }?.key
    }

    private fun split(contacts: String): List<String> =
        contacts.split(Regex("\\s*(,|/|\\bऔर\\b|\\band\\b|\\baur\\b|&)\\s*", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotEmpty() }

    /** नया WhatsApp मैसेज आया - क्या करें? */
    fun route(s: State?, title: String, text: String, mine: Boolean, isGroup: Boolean, now: Long): Route {
        if (s == null) return Route.Skip("off")
        if (now > s.until) return Route.Skip("time over")
        if (title.isBlank() || text.isBlank()) return Route.Skip("empty")
        if (mine) return Route.Skip("mine")
        val k = WaScreen.key(title)
        if (s.lastSent[k]?.trim() == text.trim()) return Route.Skip("mine")
        val inPicked = s.picked.contains(k) || s.picked.any { WaScreen.nameMatches(s.names[it] ?: it, title) }
        val inSelf = s.self.contains(k) || s.self.any { WaScreen.nameMatches(s.names[it] ?: it, title) }
        if (isGroup && !inPicked && !inSelf) return Route.Skip("group")
        s.names[k] = title.trim()
        val inc = Incoming(title.trim(), text.trim(), now)
        s.waiting[k] = inc
        val chosen = s.all || inPicked || inSelf || s.selfAll
        if (chosen) {
            if (WaScreen.needsOwner(text)) return Route.AskBoss(inc, true)
            if (s.selfAll || inSelf) return Route.Auto(inc)
            return Route.AskBoss(inc, false)
        }
        if (s.picked.isEmpty()) {
            val together = s.waiting.filterValues { now - it.at <= TOGETHER_MS }
            if (together.size >= 2) {
                if (together.keys == s.askedPick) return Route.Skip("already asked")
                s.askedPick = together.keys.toSet()
                return Route.AskPick(together.values.toList())
            }
            return Route.AskBoss(inc, WaScreen.needsOwner(text))
        }
        if (s.toldAt[k]?.let { now - it < TELL_AGAIN_MS } == true) return Route.Skip("told")
        s.toldAt[k] = now
        return Route.TellNew(inc)
    }

    /** बॉस ने चुना किससे बात करनी है ("Rahul", "Rahul aur Amit", "तीनों से") - चुने गए नाम */
    fun pick(s: State, contacts: String): List<String> {
        if (isAll(contacts)) { s.all = true; return s.waiting.values.map { it.chat }.ifEmpty { listOf("सब") } }
        val out = ArrayList<String>()
        for (n in split(contacts)) {
            val k = find(s, n) ?: WaScreen.key(n).also { s.names[it] = n }
            s.picked.add(k); out.add(s.names[k] ?: n)
        }
        return out
    }

    /** बॉस: "तुम खुद कर दो" (contact खाली/सब = सबके लिए) */
    fun selfOn(s: State, contact: String): String {
        if (contact.isBlank() || isAll(contact)) { s.selfAll = true; return "सब" }
        val k = find(s, contact) ?: WaScreen.key(contact).also { s.names[it] = contact.trim() }
        s.self.add(k); s.picked.add(k)
        return s.names[k] ?: contact.trim()
    }

    /** बॉस: "नहीं, इसका जवाब मैं दूँगा" - फिर पहले पूछो */
    fun bossOn(s: State, contact: String) {
        if (contact.isBlank() || isAll(contact)) { s.selfAll = false; s.self.clear(); return }
        val k = find(s, contact) ?: return
        s.self.remove(k)
        if (s.selfAll) { s.selfAll = false; s.waiting.keys.filter { it != k }.forEach { s.self.add(it) } }
    }

    /** किस चैट को जवाब देना है: नाम दिया = वही; खाली = अकेला इंतज़ार वाला (एक से ज़्यादा = null, पूछो) */
    fun target(s: State, contact: String): String? {
        if (contact.isNotBlank()) return find(s, contact) ?: WaScreen.key(contact)
        val wait = s.waiting.keys.toList()
        return when {
            wait.size == 1 -> wait[0]
            s.picked.size == 1 -> s.picked.first()
            else -> null
        }
    }

    fun nameOf(s: State, key: String): String = s.names[key] ?: key

    /** जवाब चला गया - याद रखो (लौटकर आया notification दोबारा जवाब न बनवाए); इस व्यक्ति से बात चालू */
    fun answered(s: State, key: String, text: String) {
        s.waiting.remove(key); s.picked.add(key); s.lastSent[key] = text.trim(); s.replies++
        s.askedPick = emptySet()
    }

    // ---------- नवंबर 2026 (मालिक: "एक बार उस व्यक्ति की चैट में जाए तो वहीं रुककर टाइप करके बात करता रहे - बार-बार
    // बैक / सर्च नहीं") - खुली चैट पर नज़र. WhatsApp खुली चैट का notification नहीं देता, इसलिए स्क्रीन ही देखनी पड़ती है ----------

    /** बोला गया नाम = अभी स्क्रीन पर खुली चैट? (हाँ = वहीं लिखो, पीछे जाकर दोबारा ढूँढो मत) */
    fun sameChat(said: String, openTitle: String): Boolean {
        val a = said.trim(); val b = openTitle.trim()
        if (a.isEmpty() || b.isEmpty()) return false
        return WaScreen.key(a) == WaScreen.key(b) || WaScreen.nameMatches(a, b)
    }

    /** इस खुली चैट में DK खुद जवाब दे? (बॉस ने इसके लिए / सबके लिए "तुम खुद कर दो" कहा हो) */
    fun selfFor(s: State, title: String): Boolean {
        if (title.isBlank()) return false
        if (s.selfAll) return true
        val k = WaScreen.key(title)
        return s.self.contains(k) || s.self.any { WaScreen.nameMatches(s.names[it] ?: it, title) }
    }

    sealed class Here {
        /** कुछ नहीं करना (नई बात नहीं / यह चैट खुद-जवाब वाली नहीं) - स्क्रीन जस की तस, कहीं मत जाओ */
        data class Wait(val why: String) : Here()
        /** यहीं (इसी खुली चैट में) जवाब लिखो */
        data class Reply(val msg: Incoming) : Here()
        /** पैसे/OTP जैसी बात - खुद नहीं, बॉस से पूछो */
        data class Ask(val msg: Incoming) : Here()
        object Stop : Here()
    }

    /**
     * खुली चैट में अभी क्या करें. [msgs] = स्क्रीन पर दिख रहे मैसेज (पुराने पहले), [handled] = इस चैट की जिस बात का
     * जवाब DK दे चुका / दे रहा है (दोबारा नहीं).
     */
    fun here(s: State?, title: String, msgs: List<WaScreen.Msg>, handled: String?, isGroup: Boolean, now: Long): Here {
        if (s == null || now > s.until) return Here.Stop
        if (!selfFor(s, title)) return Here.Wait("not self")
        if (isGroup && !s.self.contains(WaScreen.key(title))) return Here.Wait("group")
        val last = msgs.lastOrNull() ?: return Here.Wait("empty")
        if (last.mine) return Here.Wait("mine")
        val t = last.text.trim()
        if (t.isEmpty()) return Here.Wait("empty")
        val k = WaScreen.key(title)
        if (handled?.trim() == t || s.lastSent[k]?.trim() == t) return Here.Wait("done")
        s.names[k] = title.trim()
        val inc = Incoming(title.trim(), t, now)
        return if (WaScreen.needsOwner(t)) Here.Ask(inc) else Here.Reply(inc)
    }

    private fun q(t: String, max: Int = 220) = "\"" + t.replace("\"", "'").replace('\n', ' ').take(max) + "\""

    // ---------- Live (Gemini) के लिए SYSTEM NOTE - बॉस से बात हिंदी में, WhatsApp पर Hinglish ----------

    fun onNote(min: Int, chats: String): String =
        "CHAT MODE ON (WhatsApp चैटिंग मोड) for $min minutes. Say first, exactly: '$ON_LINE' " +
            "Then in 1-2 short Hindi lines tell the boss who has messaged (from the list below, if any) and ask whom to talk to. " +
            "From now on DK will send you a SYSTEM NOTE for every new WhatsApp message: tell the boss who wrote what (short, no extra talk), " +
            "then ask 'बॉस, क्या जवाब दूँ? या मैं खुद कर दूँ?'. When he dictates the answer call whatsapp_task action=chat_reply " +
            "(contact + message = his words in Hinglish, Roman letters, never Devanagari). When he says 'तुम खुद कर दो / अपने से कर दो' call " +
            "action=chat_self (contact, or 'all' if he says sab/सबको). When he names whom to talk to call action=chat_pick (contact = names or 'all'). " +
            "Never reply to anyone he did not choose. 'चैटिंग मोड बंद' = action=chat_mode_off.\n" +
            (if (chats.isBlank()) "WhatsApp list: (could not read the screen - new messages will come as SYSTEM NOTE)" else "WhatsApp list now:\n$chats")

    fun pickNote(people: List<Incoming>): String {
        val names = people.map { it.chat }
        val list = people.mapIndexed { i, p -> "${i + 1}) ${p.chat}: ${q(p.text, 140)}" }.joinToString("\n")
        val ask = when (names.size) {
            2 -> "बॉस, ${names[0]} और ${names[1]} दोनों का मैसेज आया है - किससे बात करनी है, या दोनों से?"
            3 -> "बॉस, ${names[0]}, ${names[1]} और ${names[2]} - तीनों का मैसेज आया है - किससे बात करनी है, या तीनों से?"
            else -> "बॉस, ${names.size} लोगों का मैसेज आया है (${names.joinToString(", ")}) - किससे बात करनी है, या सबसे?"
        }
        return "SYSTEM NOTE (WhatsApp चैटिंग मोड): several people wrote at the same time:\n$list\n" +
            "In very short Hindi tell the boss who wrote what, then ask exactly: '$ask'. Wait for his answer. " +
            "Then call whatsapp_task action=chat_pick contact=<the names he chose, or 'all'>. Do NOT reply to anyone before he chooses."
    }

    fun askNote(m: Incoming, sensitive: Boolean): String =
        "SYSTEM NOTE (WhatsApp चैटिंग मोड): ${m.chat} ने लिखा: ${q(m.text)}. " +
            "In one short Hindi line tell the boss what ${m.chat} said/asked (no extra talk), then ask: 'बॉस, क्या जवाब दूँ? या मैं खुद कर दूँ?'. " +
            (if (sensitive) "This is about money/OTP/bank - DK must NOT answer it on its own; only send what the boss dictates. " else "") +
            "If he dictates an answer -> whatsapp_task action=chat_reply contact=${q(m.chat, 60)} message=<his words in Hinglish>. " +
            (if (sensitive) "" else "If he says 'तुम खुद कर दो / अपने से कर दो' -> action=chat_self contact=${q(m.chat, 60)}. ") +
            "Do not send anything before he answers."

    fun tellNote(m: Incoming): String =
        "SYSTEM NOTE (WhatsApp चैटिंग मोड): ${m.chat} का भी मैसेज आया: ${q(m.text, 140)}. The boss has not chosen this person, so DK did not reply. " +
            "Tell him in one short Hindi line and ask: 'इनसे भी बात करूँ?'. Yes -> action=chat_pick contact=${q(m.chat, 60)}."

    fun autoNote(m: Incoming, sent: String): String =
        "SYSTEM NOTE (WhatsApp चैटिंग मोड): ${m.chat} ने लिखा ${q(m.text, 140)} - DK ने खुद जवाब भेज दिया: ${q(sent, 160)}. " +
            "Tell the boss in one short Hindi line (who asked what, what DK replied). No extra talk."
}
