package com.dk.assistant

/**
 * v3.87: DK टीचर अपने-आप - पढ़ाई की बात आते ही टीचर वाला सफ़ेद पन्ना खुले और DK जो समझाए वह उसी पर लिखा दिखे।
 *
 * यह हिस्सा Android के बिना है (JVM test): पहचान (पढ़ाई है या नहीं), पन्ने की हालत (कौन-सा सवाल, क्या लिखा),
 * और लिखाई का ढाँचा (गणित = चरण-दर-चरण, बाक़ी = शीर्षक + बिंदु)। स्क्रीन खोलना/बंद करना [TeacherLink] करता है।
 */
object TeacherBoard {

    enum class Action { NONE, OPEN, CLOSE }

    data class Section(val question: String, val answer: String)

    // ---------------- पहचान ----------------

    private val EXPLICIT = Regex(
        "(टीचर\\s*मोड|teacher\\s*mode|डीके\\s*टीचर|dk\\s*teacher|पढ़ाओ|पढ़ा\\s*दो|पढाओ|पढ़ाइए|समझाओ|समझा\\s*दो|समझाइए|समझाना|सिखाओ|सिखा\\s*दो|सिखाइए|" +
            "explain|teach\\s*me|पढ़ाई\\s*कर|पढ़ाई\\s*करनी|होमवर्क|homework|सवाल\\s*हल|हल\\s*करो|हल\\s*कर\\s*दो|solve)",
        RegexOption.IGNORE_CASE
    )

    private val SUBJECT = Regex(
        "(गणित|मैथ्स|मैथ|विज्ञान|साइंस|भौतिक|रसायन|जीव\\s*विज्ञान|जीवविज्ञान|इतिहास|भूगोल|नागरिक\\s*शास्त्र|अर्थशास्त्र|व्याकरण|ग्रामर|संस्कृत|" +
            "maths|math|science|physics|chemistry|biology|history|geography|grammar|economics|civics|" +
            "सूत्र|फ़ॉर्मूला|फार्मूला|formula|परिभाषा|definition|प्रमेय|theorem|अध्याय|चैप्टर|chapter|निबंध|essay|पाठ\\s|कक्षा|class\\s*\\d|" +
            "प्रकाश\\s*संश्लेषण|photosynthesis|गुरुत्वाकर्षण|gravity|परमाणु|atom|अणु|molecule|कोशिका|न्यूटन|newton|ऊर्जा|energy|बल\\s|force|" +
            "सौर\\s*मंडल|solar\\s*system|ग्रह|पृथ्वी|ज्वालामुखी|भूकंप|संविधान|लोकतंत्र|क्रांति|साम्राज्य|विलोम|पर्यायवाची|संज्ञा|सर्वनाम|क्रिया\\s*विशेषण|tense|noun|verb)",
        RegexOption.IGNORE_CASE
    )

    private val MATH_WORDS = Regex(
        "(प्रतिशत|परसेंट|percent|%|वर्गमूल|वर्ग|square|घनमूल|घन|cube|जोड़|जोड़ो|घटाओ|घटा|गुणा|भाग|हल|solve|निकालो|निकालिए|कितना\\s*होगा|कितने\\s*होंगे|कितना\\s*हुआ|" +
            "equation|समीकरण|लघुत्तम|महत्तम|lcm|hcf|औसत|average|ब्याज|interest|क्षेत्रफल|area|परिमाप|perimeter|आयतन|भिन्न|fraction|अनुपात|ratio|" +
            "\\d\\s*[+\\-×x*/÷^=]\\s*\\d|x\\s*=|\\d+\\s*का\\s*\\d+)",
        RegexOption.IGNORE_CASE
    )

    /** "क्यों / कैसे / क्या होता है / किसे कहते हैं / what is / why does" */
    private val WHY = Regex(
        "(क्यों|क्यूँ|क्यू|कैसे\\s+(बनता|बनती|बनते|होता|होती|होते|काम|चलता|चलती|उड़ता|उड़ती|उगता|उगती|गिरता|गिरती|बहता|बहती|आता|आती)|" +
            "क्या\\s+होता\\s+है|क्या\\s+होती\\s+है|किसे\\s+कहते|किसको\\s+कहते|का\\s+मतलब\\s+क्या|का\\s+अर्थ|कैसे\\s+होता|" +
            "what\\s+is\\s+(a|an|the)?\\s*\\w+|why\\s+(does|do|is|are)|how\\s+(does|do)\\s+\\w+\\s+work)",
        RegexOption.IGNORE_CASE
    )

    /** निजी/बातचीत वाले शब्द - "तुम क्यों नाराज़ हो" पढ़ाई नहीं */
    private val PERSONAL = Regex(
        "(^|\\s)(तुम|तू|तुम्हें|तुम्हारा|तुम्हारी|तुझे|आप|आपको|आपका|आपकी|मैं|मुझे|मेरा|मेरी|मेरे|हम|हमें|हमारा|यार|बेबी|जान|डीके|dk|you|your|me|my|i)(\\s|$|[,?।])",
        RegexOption.IGNORE_CASE
    )

    /** ताज़ा जानकारी वाले सवाल (खोज) पढ़ाई नहीं */
    private val LIVE = Regex("(आज|अभी|ख़बर|खबर|न्यूज़|न्यूज|मौसम|स्कोर|मैच|भाव|कीमत|रेट|शेयर|तारीख़|तारीख|टाइम|समय\\s*क्या|news|weather|score|price|today)", RegexOption.IGNORE_CASE)

    /**
     * v5 (मालिक: "टीचर मोड अपने-आप न खुले - जब तक यूज़र खुद न बोले कि टीचर मोड खोलो"):
     * यूज़र ने साफ़ टीचर मोड / पढ़ाई मोड खोलने को कहा? (सिर्फ़ पढ़ाई का सवाल पूछना = नहीं)
     */
    private val OPEN_CMD = Regex("(टीचर|टिचर|टीचरमोड|teacher|पढ़ाई\\s*मोड|पढाई\\s*मोड|स्टडी\\s*मोड|study\\s*mode|शिक्षक\\s*मोड)", RegexOption.IGNORE_CASE)
    fun isOpenCommand(text: String): Boolean {
        val t = text.trim().trimEnd('…').trim()
        if (t.isBlank() || isExit(t)) return false
        return OPEN_CMD.containsMatchIn(t)
    }

    /** पढ़ाई से जुड़ा सवाल / सीखने की बात? */
    fun isStudy(text: String): Boolean {
        val t = text.trim().trimEnd('…').trim()
        if (t.length < 4) return false
        if (EXPLICIT.containsMatchIn(t)) {
            // "टीचर मोड बंद करो" पढ़ाई नहीं, बाहर जाना है
            return !isExit(t)
        }
        // फ़ोन का काम ("वॉल्यूम 50 करो", "अलार्म 7 बजे") पढ़ाई नहीं
        val d = TaskQueue.domainOf(t)
        if (d != null && d != "other") return false
        if (MATH_WORDS.containsMatchIn(t) && t.any { it.isDigit() || it in '०'..'९' }) return true
        if (LIVE.containsMatchIn(t)) return false
        if (SUBJECT.containsMatchIn(t) && (WHY.containsMatchIn(t) || t.contains("?") || Regex("(बताओ|बताइए|लिखो|क्या\\s+है|क्या\\s+हैं|कौन|कितने|कितनी)").containsMatchIn(t))) return true
        if (WHY.containsMatchIn(t) && !PERSONAL.containsMatchIn(t)) return true
        return false
    }

    private val EXIT = Regex(
        "^(अब\\s+)?(टीचर\\s*मोड\\s*|पढ़ाई\\s*|बोर्ड\\s*|teacher\\s*mode\\s*)?(बंद\\s*करो|बंद\\s*कर\\s*दो|बंद|वापस\\s*जाओ|वापस\\s*चलो|वापस|बाहर\\s*जाओ|exit|close|बस\\s*हो\\s*गया)(\\s+(अब|यार|प्लीज|please|डीके|dk|ना))*\\s*[।.!]*$",
        RegexOption.IGNORE_CASE
    )

    /** "बंद करो" / "वापस जाओ" / "टीचर मोड बंद करो" (पर "टॉर्च बंद करो" नहीं) */
    fun isExit(text: String): Boolean {
        val t = text.trim().trimEnd('…', '।', '.', '!').trim()
        if (t.isBlank() || t.length > 40) return false
        return EXIT.matches(t)
    }

    // ---------------- पन्ने की हालत ----------------

    /** टीचर वाला पन्ना खुला है (TeacherActivity बताती है) */
    @Volatile var open = false
    /** DK की बातें अभी पन्ने पर लिखी जा रही हैं (पढ़ाई की बारी) */
    @Volatile var capturing = false
        private set
    /** कुछ भी बदला = नया नंबर (स्क्रीन दोबारा बनाए) */
    @Volatile var version = 0
        private set
    /** कई काम चल रहे हों तो "बंद करो" = काम रोकना, टीचर बंद नहीं */
    @Volatile var exitBlocked: () -> Boolean = { false }

    private val sections = ArrayList<Section>()
    @Volatile private var replyClosed = true

    @Synchronized fun sections(): List<Section> = sections.toList()

    @Synchronized fun reset() { sections.clear(); capturing = false; replyClosed = true; version++ }

    private fun similar(a: String, b: String): Boolean {
        val x = a.trim().trimEnd('…').lowercase(); val y = b.trim().trimEnd('…').lowercase()
        if (x.isBlank() || y.isBlank()) return false
        val n = minOf(x.length, y.length, 12)
        return x.startsWith(y.take(n)) || y.startsWith(x.take(n))
    }

    /** यूज़र बोला (आधी "…" या पूरी बात) -> पन्ना खोलो / बंद करो / कुछ नहीं */
    @Synchronized fun onUser(raw: String): Action {
        val t = raw.trim().trimEnd('…').trim()
        if (t.isBlank()) return Action.NONE
        val partial = raw.trim().endsWith("…")
        if (open && !partial && isExit(t) && !exitBlocked()) { capturing = false; return Action.CLOSE }
        val last = sections.lastOrNull()
        // पन्ना खुला है और बात काम की नहीं = पढ़ाई ही आगे चल रही है ("और समझाओ", "अच्छा फिर?")
        val study = isStudy(t) || (open && WorkChatMode.classify(t) == WorkChatMode.Mode.CHAT && !isExit(t))
        if (study) {
            capturing = true
            if (last != null && similar(last.question, t)) {
                // उसी सवाल की पूरी/सुधरी लाइन (Live में पूरी बात जवाब के बाद आती है) - सवाल बदलो, जवाब वही
                if (t.length > last.question.length) sections[sections.size - 1] = last.copy(question = t)
            } else {
                sections += Section(t, ""); replyClosed = false
                while (sections.size > 12) sections.removeAt(0)
            }
            version++
            return if (open) Action.NONE else Action.OPEN
        }
        // काम की बात (अलार्म/मैसेज) - काम हो, पन्ने पर उसका जवाब न लिखा जाए
        if (WorkChatMode.classify(t) != WorkChatMode.Mode.CHAT) capturing = false
        return Action.NONE
    }

    /** DK का जवाब (बोलते-बोलते जितना बोला, उतना) - पढ़ाई की बारी हो तो पन्ने पर */
    @Synchronized fun onReply(t: String, final: Boolean) {
        if (!capturing) return
        val i = sections.size - 1
        if (i < 0 || t.isBlank()) return
        if (replyClosed && final) return
        sections[i] = sections[i].copy(answer = t)
        if (final) replyClosed = true
        version++
    }

    /** पन्ने के अंदर से पूछा गया सवाल (टाइप/माइक) - सीधे जोड़ो */
    @Synchronized fun addLocal(q: String, a: String) {
        sections += Section(q.trim(), a.trim()); replyClosed = true
        while (sections.size > 12) sections.removeAt(0)
        version++
    }

    // ---------------- लिखाई का ढाँचा ----------------

    data class Line(val text: String, val kind: Kind)
    enum class Kind { QUESTION, HEADING, STEP, ANSWER, POINT }

    private val STEP = Regex("(चरण|स्टेप|step)\\s*([0-9०-९]+)\\s*[:.)-]?", RegexOption.IGNORE_CASE)
    private val FINAL = Regex("^(उत्तर|जवाब|answer|अतः|इसलिए\\s+उत्तर)\\s*[:-]", RegexOption.IGNORE_CASE)

    /** बोला गया जवाब -> बोर्ड की लाइनें (गणित = हर चरण अलग लाइन; बाक़ी = शीर्षक + छोटे बिंदु) */
    fun format(answer: String): List<Line> {
        var a = answer.replace(Regex("[*#`_]{1,3}"), "").replace("\r", "").trim()
        if (a.isBlank()) return emptyList()
        // हर "चरण N" नई लाइन से
        a = a.replace(STEP) { m -> "\n" + m.value.trim().let { v -> if (v.endsWith(":") || v.endsWith(")") || v.endsWith(".") || v.endsWith("-")) v.dropLast(1).trim() + ":" else "$v:" } }
        a = a.replace(Regex("\\s*(उत्तर|answer)\\s*[:-]", RegexOption.IGNORE_CASE)) { m -> "\n" + m.groupValues[1] + ":" }
        val math = STEP.containsMatchIn(answer)
        val out = ArrayList<Line>()
        for (raw in a.split("\n")) {
            val line = raw.trim().trimStart('-', '•', '–').trim()
            if (line.isBlank()) continue
            when {
                STEP.find(line)?.range?.first == 0 -> out += Line(line, Kind.STEP)
                FINAL.containsMatchIn(line) -> out += Line(line, Kind.ANSWER)
                math -> out += sentences(line).map { Line(it, Kind.STEP) }
                line.endsWith(":") && line.length <= 50 -> out += Line(line.trimEnd(':'), Kind.HEADING)
                else -> {
                    val ss = sentences(line)
                    ss.forEachIndexed { i, s ->
                        // पहला छोटा वाक्य = शीर्षक जैसा (पहली लाइन हो तो)
                        if (out.isEmpty() && i == 0 && ss.size > 1 && s.length <= 45) out += Line(s.trimEnd('।', '.', ':'), Kind.HEADING)
                        else out += Line(s, Kind.POINT)
                    }
                }
            }
        }
        return out
    }

    /** "। ? !" (और अंक के बीच नहीं वाला ".") पर वाक्य */
    fun sentences(s: String): List<String> {
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        for (i in s.indices) {
            val c = s[i]
            sb.append(c)
            val endsHere = c == '।' || c == '?' || c == '!' ||
                (c == '.' && !(i > 0 && s[i - 1].isDigit() && i + 1 < s.length && s[i + 1].isDigit()))
            if (endsHere && (i + 1 >= s.length || s[i + 1] == ' ')) { sb.toString().trim().takeIf { it.isNotBlank() }?.let { parts += it }; sb.clear() }
        }
        sb.toString().trim().takeIf { it.isNotBlank() }?.let { parts += it }
        return parts
    }

    /** पूरे पन्ने का लिखा (सवाल + जवाब) - टाइपराइटर इसी को धीरे-धीरे दिखाता है */
    fun boardLines(): List<Line> {
        val out = ArrayList<Line>()
        for (s in sections()) {
            out += Line("प्रश्न: " + s.question, Kind.QUESTION)
            out += format(s.answer)
        }
        return out
    }

    /** मॉडल के नियम - पढ़ाई का जवाब बोर्ड पर लिखने लायक बने */
    fun block(): String =
        "\n\nSTUDY / TEACHER MODE (owner's rule): the white Teacher board opens ONLY when the user himself asks for it " +
            "(\"टीचर मोड खोलो\" / \"teacher mode on\" / \"पढ़ाई मोड चालू करो\") - then use teacher_mode on=true. NEVER open it by yourself just because a question " +
            "sounds like studies: answer such a question normally, and only if it is clearly a study topic you may ask once \"सर, क्या टीचर मोड खोल दूँ?\" and open it only if he says yes. " +
            "WHEN TEACHER MODE IS OPEN you are a fully professional teacher for ANYTHING he wants to learn: every school/college subject, competitive exams, coding, " +
            "and ANY language (English, Marathi, Hindi, Sanskrit, Urdu, Gujarati, Tamil, French, Spanish, German, Japanese ...). For a language: teach from his level - " +
            "words with meaning and pronunciation, daily-use sentences, grammar in simple steps, then ask him to repeat or answer, gently correct mistakes and give small practice / quiz. " +
            "The board writes exactly what you say, in step with your voice - do not say you are writing anything, just teach. Teach like a good teacher in simple words: " +
            "MATHS: solve it step by step, every step one short spoken line that starts with \"चरण 1:\", \"चरण 2:\" … and end with \"उत्तर: …\". " +
            "OTHER SUBJECTS: first a short heading line, then simple points, each point one short sentence. " +
            "Stay in this teaching style until the study talk is over. If he gives a phone task in between (alarm, message), do it with the WORK rules, then go back to teaching if he wants. " +
            "The board closes only when he himself says \"बंद करो\" or \"वापस जाओ\"."
}
