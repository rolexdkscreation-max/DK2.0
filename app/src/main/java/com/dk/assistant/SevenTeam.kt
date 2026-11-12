package com.dk.assistant

import com.dk.assistant.Seven.Agent
import org.json.JSONObject

/** एजेंटों की AI - हर कॉल में उस एजेंट का अपना छोटा system prompt (कोडिंग एजेंट की अपनी API key से) */
interface TeamAi {
    suspend fun ask(agent: Agent, system: String, msgs: List<Pair<String, String>>): String
    /** user की फ़ोटो/वीडियो के साथ (मॉडल न देख सके तो बिना - लिखा विवरण फिर भी जाता है) */
    suspend fun askMedia(agent: Agent, system: String, msgs: List<Pair<String, String>>, media: List<CoderMedia>): String = ask(agent, system, msgs)
    /** एक request में यह मॉडल कितने अक्षर पढ़ सकता है - सब एजेंट इसी हिसाब से पूरी फ़ाइलें / पूरे log भेजते हैं */
    val budget: Int get() = CoderContext.DEFAULT_BUDGET
}

/** काम की फ़ाइलें: GitHub मोड = चुना रिपो/branch, नॉर्मल मोड = user के भेजे कोड + नई फ़ाइलें (फ़ोन की memory में) */
interface TeamFiles {
    suspend fun list(): List<String>
    /** अभी की हालत (लागू हो चुके बदलाव समेत). null = फ़ाइल नहीं */
    suspend fun read(path: String): String?
    /** काम शुरू होने से पहले की हालत */
    suspend fun original(path: String): String?
    suspend fun stage(path: String, content: String?)
    fun staged(): Map<String, String?>
    /** पूरा workspace (नाम-जाँच के लिए): path -> content, बदलाव समेत. null = नहीं दे सकते */
    suspend fun all(): Map<String, String>? = null
    /** snapshot में न आई (binary/बड़ी) फ़ाइलों के path - resource जाँच के लिए */
    suspend fun skippedPaths(): Set<String> = emptySet()
    /** all() पूरा रिपो है (GitHub मोड)? false = user की भेजी कुछ फ़ाइलें */
    val whole: Boolean get() = false
    /** v4.4: पूरे काम का हिसाब base branch (main) से - पहले के push/पिछली बार चला काम भी. "" = नहीं दे सकते */
    suspend fun footprint(): String = ""
    /** v4.4: build log की जड़ = इसी काम ने हटाई/आधी की फ़ाइल -> main से हूबहू वापस (staged). लौटाता है क्या वापस आया */
    suspend fun restoreDamaged(log: String): List<CodeGuard.Damage> = emptyList()
}

/** टेस्ट/बिल्ड का नतीजा. ok = null: नतीजा देख नहीं पाए (जैसे token में Actions की इजाज़त नहीं) */
data class TeamTest(val ok: Boolean?, val log: String, val url: String = "", val note: String = "", val stage: String = "") {
    companion object {
        /** PR से पहले, सिर्फ़ branch पर चला build (पास हो तभी PR) */
        const val BRANCH = "branch"
        /** PR का अपना चेक */
        const val PR = "pr"
    }
}

/** गिट एजेंट के हाथ (सिर्फ़ GitHub मोड) */
interface TeamGit {
    /** काम शुरू होने से पहले: backup branch (backup/dk-तारीख-समय) */
    suspend fun backup(task: String): String
    /** main से नई काम की branch (main पर कभी सीधे नहीं) */
    suspend fun branch(task: String): String
    /** बदलाव उसी branch पर push + (टेस्ट चलाने के लिए) draft PR + GitHub Actions का नतीजा */
    suspend fun pushAndTest(commit: String, title: String, summary: String, rootCause: String, onStep: (String) -> Unit): TeamTest
    /** टेस्ट पास: PR को review के लिए तैयार (draft हटाओ). merge कभी नहीं - वह सिर्फ़ user की मंज़ूरी से */
    suspend fun ready(): String
    val prUrl: String
    /** branch पर build पास होने के बाद PR खोलो (पहले नहीं). @return PR url, "" = नहीं बना (वजह prNote में) */
    suspend fun openPr(): String = prUrl
    /** PR क्यों नहीं बना / किस हाल में है */
    val prNote: String get() = ""
    /** 📒 काम-कॉपी (markdown) PR के विवरण में रखो - PR अभी न बना हो तो PR बनते ही उसमें */
    suspend fun saveNote(markdown: String) {}
    /** पहले से खुले PR का विवरण (उसमें पिछली काम-कॉपी). "" = PR नहीं / पढ़ नहीं पाया */
    suspend fun loadNote(): String = ""
}

/**
 * 7 एजेंट, तय क्रम में. नया फ़ीचर: मैनेजर → गिट → प्लानर → (कोडर → रिव्यूअर → लागू)×कदम → टेस्टर → (डिबगर → प्लानर → …)* → गिट PR → रिपोर्ट.
 * बग: मैनेजर → गिट → डिबगर (जड़) → प्लानर (न्यूनतम सुधार) → कोडर/रिव्यूअर → टेस्टर → …
 * git = null → नॉर्मल मोड (गिट एजेंट बंद; बाकी क्रम वही).
 */
class SevenTeam(
    private val ai: TeamAi,
    private val files: TeamFiles,
    private val git: TeamGit?,
    /** true = पूरी टीम (मैनेजर की समझ/spec, नक्शा+खोज, जगह-जाँच, रिव्यूअर की परफॉरमेंस जाँच, टेस्टर की QA). false = पुराना छोटा क्रम जैसा का तैसा */
    private val full: Boolean = false,
    /** नवंबर 2026: काम ख़त्म = समझाने वाला असली diff पढ़कर AI से समझाए (क्या, क्यों, कैसे चलेगा, बग कहाँ था, कैसे जाँचें). false = सिर्फ़ पक्का ढाँचा */
    private val aiResult: Boolean = false,
    /** नवंबर 2026 (मालिक: "बहुत स्लो"): true = छोटे साफ़ बदलाव का अलग AI रिव्यू नहीं + AI सारांश 10 मिनट में एक बार ([CoderSpeed]). false = पुराना जैसा का तैसा */
    private val fast: Boolean = false,
    ev: (Agent, String) -> Unit
) {
    private val sink = ev
    private fun ev(a: Agent, t: String) { memory.event(a.name, t); sink(a, t) }

    /**
     * नवंबर 2026 (मालिक: "मैनेजर पूरी बात याद रखेगा और सबको एक सारांश में समझा देगा"): पूरी टीम की साझा याददाश्त.
     * पहले हर एजेंट को सिर्फ़ उसका हिस्सा मिलता था (कोडर को कदम, रिव्यूअर को diff) - बाकी बात (user के असली शब्द, पिछली
     * गलतियाँ, CI ने क्या पकड़ा) उसे पता नहीं होती थी. अब हर AI कॉल में मैनेजर की यह कॉपी जाती है ([teamBrief]).
     */
    val memory = TeamMemory()
    private var briefCache = ""
    private var briefVer = -1
    /** नवंबर 2026 (तेज़): आख़िरी AI सारांश कब बना + पूरे काम का समय-हिसाब ([CoderSpeed]) */
    private var briefAt = 0L
    val clock = CoderSpeed.Clock()

    /**
     * v5.2 (मालिक: "प्लानर पहले जड़/कारण ढूँढकर लिखे, कारण पता होने के बाद ही कोडर"): बग के काम में शुरू की जड़-खोज
     * प्लानर की ज़िम्मेदारी है - वह डिबगर वाली जाँच (वही prompt, वही पक्की लाइन-जाँच) से जड़ ढूँढता है.
     * स्क्रीन पर उस समय नाम "प्लानर" दिखे, इसके लिए: [shownAs].
     */
    @Volatile private var rootByPlanner = false
    /** यह ज़िम्मेदारी अभी स्क्रीन पर किस सदस्य के नाम से दिखे (7 + समझाने वाला) */
    fun shownAs(a: Agent): Seven.Member = if (rootByPlanner && a == Agent.DEBUGGER) Seven.Member.PLANNER else a.member
    /** हर राउंड की जड़ (रुकना पड़े तो user को "क्या-क्या आज़माया") */
    private val tried = mutableListOf<String>()
    /** CI (Kotlin compiler) ने जो नाम "Unresolved reference" बताए + वे किस repo-class पर थे - अगले fix में वही/नया गढ़ा नाम पक्का रुके */
    private var ciProven = SevenSymbols.Proven()
    /**
     * report = पूरी तकनीकी रिपोर्ट (डिबग लॉग / PR के लिए).
     * short = यूज़र को दिखने वाली छोटी रिपोर्ट: हुआ/नहीं, API चली/नहीं, वजह, हल (ShortReport). खाली = report ही दिखाओ.
     */
    data class Outcome(val report: String, val files: Map<String, String?>, val testOk: Boolean?, val mode: Seven.Mode,
                       val answerOnly: Boolean = false, val short: String = "",
                       /** नवंबर 2026: merge से पहले चैट में पूरा ब्योरा ([MergeBrief]) - क्या कहा था, क्या बदला, कैसे चलेगा */
                       val brief: String = "")

    /** "आगे करो": पिछली बार टीम कहाँ तक पहुँची थी (null = नया काम) */
    var resumeFrom: TeamProgress? = null
    /** हर पड़ाव पर टीम की याद (ऐप इसे फ़ोन में रखती है) */
    var onProgress: ((TeamProgress) -> Unit)? = null
    private var prog = TeamProgress()
    private fun save(f: (TeamProgress) -> TeamProgress) {
        prog = f(prog).copy(changes = LinkedHashMap(changes), backup = backup, qaOk = qaOk,
            commit = commitMsg?.first.orEmpty(), title = commitMsg?.second.orEmpty())
        try { onProgress?.invoke(prog) } catch (_: Exception) {}
    }

    val mode get() = if (git != null) Seven.Mode.GITHUB else Seven.Mode.NORMAL
    private val changes = linkedMapOf<String, String>()
    private val bugs = mutableListOf<Seven.Bug>()
    private var backup = ""
    private var lastBlock = ""
    private var task = ""
    private var brief = ""
    private var acceptance = listOf<String>()
    private var passNote = ""
    private var lastTest: TeamTest? = null
    private var noCi = false
    private var commitMsg: Pair<String, String>? = null
    // 11-एजेंट
    private var spec = ""
    private var notTouch = listOf<String>()
    private var area = listOf<String>()
    private var repoMap: String? = null
    private var qaOk = false
    private var qaRounds = 0
    private val qaMiss = mutableListOf<String>()
    private var qaFix = ""
    private var qaChecks = listOf<String>()
    private val perfNotes = mutableListOf<String>()
    /** user की भेजी फ़ोटो/वीडियो (प्रॉम्प्ट इंजीनियर, प्लानर, डिबगर, QA इन्हें खुद देखते हैं) */
    var media: List<CoderMedia> = emptyList()
    /** फ़ोटो/वीडियो में क्या दिखा - लिखा हुआ, हर एजेंट को */
    private var mediaNotes = ""
    /** अभी चल रहा पूरा प्लान (कोडर को पूरी तस्वीर) */
    private var curPlan: Seven.Plan? = null
    /** 📒 काम-कॉपी: क्या पढ़ा (फ़ाइल में क्या है), क्या हुआ, क्या बाकी - PR के विवरण में; अगले राउंड बार-बार वही कोड नहीं पढ़ते */
    val note = WorkNote()
    private var noteSaved = ""

    /** काम-कॉपी PR में लिखो (बदली हो तभी). गलती हो तो काम नहीं रुकता */
    private suspend fun pushNote() {
        val g = git ?: return
        val md = note.markdown()
        if (md == noteSaved) return
        try { g.saveNote(md); noteSaved = md } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
    }

    /** प्लानर/डिबगर के लिए काम-कॉपी (नोट वाली फ़ाइलें बदली हैं या नहीं - अभी की content से) */
    private suspend fun notePrompt(): String {
        if (note.isEmpty) return ""
        val cur = note.files.keys.associateWith { p -> try { files.read(p) } catch (_: Exception) { null } }
        return note.forPrompt(cur)
    }

    /** AI के अंतिम JSON के "notes":{"path":"…"} काम-कॉपी में */
    private fun takeNotes(o: org.json.JSONObject?) {
        val n = o?.optJSONObject("notes") ?: return
        note.about(n.keys().asSequence().associateWith { n.optString(it) })
    }

    private fun stepText(s: Seven.Step) = "${s.file} — ${s.action}: ${s.change.take(120)}"

    // ---- पढ़ने की क्षमता: मॉडल जितना पढ़ सकता है उसी हिसाब से (पहले हर मॉडल पर एक जैसी छोटी सीमा थी)
    private val big get() = ai.budget.coerceAtLeast(CoderContext.MIN_BUDGET)
    /** एक फ़ाइल इतनी तक पूरी (बड़े मॉडल पर पूरी बड़ी फ़ाइल) */
    private val fileChars get() = maxOf(FILE_CHARS, big / 4)
    /**
     * नवंबर 2026 (मालिक: "coding agent slow"): एक बार में पढ़ने/खोजने की सीमा [CoderSpeed.LEAN_BUDGET] तक. पहले Gemini पर
     * 5 लाख अक्षर के हिसाब से प्लानर 3 लाख, डिबगर 2.5 लाख एक साथ पढ़ते थे = हर जवाब धीमा + हर-मिनट वाली limit (429) जल्दी.
     * एक पूरी फ़ाइल ([fileChars]) पहले जितनी ही - कोडर को फ़ाइल कभी अधूरी नहीं मिलती.
     */
    private val lean get() = CoderSpeed.lean(big)
    private val readBudget get() = maxOf(READ_BUDGET, lean * 6 / 10)
    private val debugBudget get() = maxOf(SevenDebug.DEBUG_READ_BUDGET, lean / 2)
    private val listMax get() = maxOf(LIST_MAX, 2_000)
    private val mapChars get() = maxOf(24_000, lean / 8)
    /** पुराने पढ़े नतीजे कितने बड़े तक वैसे ही रहें */
    private fun compactOld(msgs: MutableList<Pair<String, String>>) =
        SevenDebug.compact(msgs, keepLast = if (big >= 300_000) 8 else 4, bigChars = maxOf(3_000, big / 40))

    /** हर एजेंट की AI कॉल: टीम के नियम (सावधानी, पूरा पढ़ना, पूरा समझाकर आगे देना) + फ़ोटो/वीडियो का विवरण; देखने वाले एजेंटों को असली फ़ोटो भी */
    /** 13 नवंबर 2026 (मालिक: "सिंगल एजेंट फ़ोटो / वीडियो देखकर काम करे और समझाए"): सिंगल कोडर और समझाने वाला भी असली फ़ोटो / frame देखें */
    private fun seesMedia(a: Agent) = a in SEES_MEDIA || (solo && a == Agent.CODER) || a == Agent.EXPLAINER

    private suspend fun aiAsk(a: Agent, system: String, msgs: List<Pair<String, String>>): String {
        val tb = teamBrief()
        val sys = system + "\n\n" + (if (a == Agent.CODER && solo) "" else if (a == Agent.CODER) Seven.Prompts.RULES_CODER else Seven.Prompts.RULES) +
            (if (mediaNotes.isNotBlank()) "\n\nUSER'S PHOTOS / VIDEO - what the prompt engineer saw in them (the user attached them to this task):\n$mediaNotes" else "") +
            if (tb.isNotBlank()) "\n\n" + TeamMemory.HEADER + tb else ""
        val t0 = clock.start()
        try {
            return if (media.isNotEmpty() && seesMedia(a)) ai.askMedia(a, sys, msgs, media) else ai.ask(a, sys, msgs)
        } finally {
            CoderSpeed.slowNote(shownAs(a).hi, clock.done(t0, shownAs(a).hi))?.let { sink(a, it) }   // सिर्फ़ स्क्रीन पर, टीम की याद में नहीं
        }
    }

    /**
     * मैनेजर की कॉपी हर एजेंट के लिए: छोटी हो तो पूरी; बड़ी हो तो मैनेजर (AI) एक बार छोटा सारांश लिखता है (याददाश्त बदले तभी
     * दोबारा), AI न लिख पाए तो पक्का छोटा रूप. user के अपने शब्द कभी गायब नहीं.
     */
    private suspend fun teamBrief(): String {
        if (memory.isEmpty) return ""
        val max = TeamMemory.maxFor(big)
        val full = memory.full()
        if (full.length <= max) return full
        // छोटे मॉडल (कम पढ़ने वाले) पर अलग AI कॉल नहीं - पक्का छोटा रूप ही (एक request बचती है)
        if (big < SUMMARY_AI_MIN) return memory.short(max)
        val v = memory.version()
        if (v == briefVer && briefCache.isNotBlank()) return briefCache
        // नवंबर 2026 (तेज़): याद हर थोड़ी देर में बदलती है - हर बार नया AI सारांश = हर कदम पर एक फ़ालतू कॉल. 10 मिनट में एक बार, बीच में पक्का छोटा रूप
        if (fast && !CoderSpeed.briefByAi(briefCache.isBlank(), briefAt, System.currentTimeMillis())) { clock.briefSkipped(); return memory.short(max) }
        briefAt = System.currentTimeMillis()
        val sum = try { ai.ask(Agent.MANAGER, "You keep the team's memory and brief every member.", listOf("user" to memory.summaryAsk(max))) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        briefCache = memory.accept(sum, max); briefVer = v
        return briefCache
    }

    companion object {
        const val FILE_CHARS = 40_000          // एक फ़ाइल इससे बड़ी = anchor के आस-पास का हिस्सा
        const val READ_BUDGET = 150_000        // प्लानर/डिबगर एक बार में इतना पढ़ें
        const val LIST_MAX = 800
        const val CODER_LOOKUPS = 8            // कोडर लिखने से पहले इतनी बार ढूँढ/पढ़ सकता है (कोशिश नहीं गिनी). नवंबर 2026: 3 -> 8 (मालिक: "जब चाहे पूरी फ़ाइल और जुड़ी फ़ाइल पढ़ सके")
        /** एक बार में कोडर/रिव्यूअर इतनी फ़ाइलें पूरी पढ़ सकते हैं */
        const val READS_PER_LOOKUP = 8
        /** रिव्यूअर फ़ैसले से पहले इतनी बार दूसरी फ़ाइलें माँग सकता है */
        const val REVIEW_READS = 3
        /** मैनेजर (AI) से सारांश तभी लिखवाओ जब मॉडल इतना पढ़ सके (Gemini) - नहीं तो बिना AI का छोटा रूप */
        const val SUMMARY_AI_MIN = 200_000
        /** v3.6: प्रॉम्प्ट इंजीनियर का नक्शा (प्लानर/डिबगर को पूरा वाला ही) */
        const val PROMPT_MAP_CHARS = 30_000
        /** v5.1: समझाने वाले के खोज/पढ़ने के चक्कर */
        const val EXPLAIN_ROUNDS = 6      // नवंबर 2026 (तेज़): पहले 10 - हर चक्कर पूरी बातचीत दोबारा भेजता था
        /** गिट: रिपो में GitHub Actions का कोई workflow नहीं */
        const val NO_CI = "NO_CI"
        /** audit K3: ड्राई-रन का जवाब नहीं मिला - टेस्ट हुआ ही नहीं (पहले इसे "पास" मान लिया जाता था) */
        const val DRY_RUN_MISSING = "syntax जाँच पास, पर टेस्टर (AI ड्राई-रन) का जवाब 2 बार माँगने पर भी नहीं मिला — code बदला गया है लेकिन जाँचा नहीं गया। \"आगे करो\" से दोबारा जाँच करवाएँ या खुद चलाकर देखें।"
        /** पुराना: QA कितनी बार काम वापस भेज सकता था. अब AGENTS.md नियम 15 (FixLoop.TEAM_SAME_LIMIT / TEAM_CAP) - नाम पुराने code/रिपोर्ट के लिए */
        const val QA_ROUNDS = 2
        /** 11-एजेंट डिबगर: कुल राउंड, और आख़िरी कितने राउंड में खोज/पढ़ाई बंद (सिर्फ़ फ़ैसला) */
        const val DEBUG_ROUNDS = 14
        const val FORCE_ROUNDS = 3
        /** ये एजेंट user की फ़ोटो/वीडियो खुद देखते हैं (बाकी को लिखा विवरण) */
        val SEES_MEDIA = setOf(Agent.PROMPT, Agent.PLANNER, Agent.DEBUGGER, Agent.QA)
        /** run() में मैनेजर ने कहा: यह रिपो/PR का सवाल है - जाँचकर्ता जवाब दे */
        const val STATUS_ASK = "\u0000STATUS_ASK"
    }

    // ------------------------------------------------------------------ समझ (मैनेजर + प्रॉम्प्ट इंजीनियर) - code छूने से पहले
    /**
     * मैनेजर काम का प्रकार पहचाने, प्रॉम्प्ट इंजीनियर user की बात को साफ़ spec में बदले (नक्शा + खोज से जगह ढूँढकर).
     * यहाँ कोई branch / बदलाव नहीं. नतीजा user को दिखाकर "हाँ" का इंतज़ार - फिर run(task, समझ).
     */
    suspend fun understand(userTask: String): SevenPlus.Understanding {
        task = userTask
        memory.userWords = userTask
        if (media.isNotEmpty()) seeMedia()
        ev(Agent.MANAGER, "काम समझ रहा हूँ…")
        val fileList = files.list()
        val m = askJson(Agent.MANAGER, "USER REQUEST:\n$task\n" + (if (media.isNotEmpty()) CoderMedia.describe(media) + "\n" else "") +
            "\nMODE: $mode\nFILES IN WORKSPACE (${fileList.size}):\n" + fileList.take(listMax).joinToString("\n"))
        var kind = Seven.kindOf(m?.optString("kind"), task)
        // v5.1: "जांच करो / समझाओ" और कुछ बदलना नहीं = सवाल (समझाने वाला), बदलाव नहीं
        if ((kind == Seven.Kind.FEATURE || kind == Seven.Kind.BUG) && Seven.explainOnly(task)) {
            ev(Agent.MANAGER, "आपने सिर्फ़ समझाने/जाँचने को कहा है, कुछ बदलने को नहीं → कोड नहीं बदलेगा")
            kind = Seven.Kind.QUESTION
        }
        val brief0 = m?.optString("brief").orEmpty().trim()
        val acc0 = Seven.strings(m?.optJSONArray("acceptance"))
        if (kind == Seven.Kind.STATUS) {
            ev(Agent.MANAGER, "यह रिपो / PR / काम की हालत का सवाल है → ${Seven.INSPECTOR_HI} को सौंप रहा हूँ (वह GitHub खुद जाँचकर बताएगा)")
            return SevenPlus.Understanding(kind, brief0, "", "", emptyList(), emptyList(), emptyList(), emptyList(), mediaNotes = mediaNotes)
        }
        if (kind == Seven.Kind.QUESTION) {
            // सवाल = जवाब सिर्फ़ मैनेजर देता है और वहीं रुकता है (कोई branch / बदलाव / मंज़ूरी नहीं)
            var ans = m?.optString("answer").orEmpty().trim()
            if (ans.isBlank()) ans = askJson(Agent.MANAGER, "USER QUESTION:\n$task\n\nThis is a question (kind=question). Reply ONLY JSON {\"kind\":\"question\",\"answer\":\"simple Hindi answer\"}\n" +
                "FILES IN WORKSPACE (${fileList.size}):\n" + fileList.take(listMax).joinToString("\n"))?.optString("answer").orEmpty().trim()
            // v5.1 (मालिक: "कोडिंग एजेंट सवाल का जवाब अच्छे से नहीं देता"): मैनेजर काम समझाने वाले को सौंपता है - वह असली कोड पढ़कर गहराई से समझाता है
            val deep = explain(task, ans, Seven.handoff(m))
            if (deep != null) return SevenPlus.Understanding(kind, brief0, "", "", emptyList(), emptyList(), emptyList(), emptyList(), deep, mediaNotes = mediaNotes)
            ev(Agent.MANAGER, "यह सवाल है — जवाब मैं (मैनेजर) दे रहा हूँ, कोड नहीं बदलेगा")
            return SevenPlus.Understanding(kind, brief0, "", "", emptyList(), emptyList(), emptyList(), emptyList(), ans.ifBlank { Seven.NO_ANSWER_HI }, mediaNotes = mediaNotes)
        }
        val k = kind
        // v3.6 (owner: "मैनेजर समझाता नहीं"): काम के साथ पूछे सवालों का जवाब पहले - तुरंत दिखे (प्रॉम्प्ट इंजीनियर का इंतज़ार नहीं)
        val reply0 = m?.optString("reply").orEmpty().trim()
        if (reply0.isNotBlank()) ev(Agent.MANAGER, "आपके सवालों का जवाब:\n$reply0")
        // v5.1: काम के साथ "समझाओ" भी कहा = पहले समझाने वाला असली कोड से समझाए, फिर काम
        if (Seven.wantsExplain(task)) explain(task, reply0, Seven.handoff(m))?.let { ev(Agent.EXPLAINER, "समझ (असली कोड से):\n$it") }
        ev(Agent.MANAGER, if (k == Seven.Kind.BUG) "यह बग ठीक करने का काम लगता है → पहले मैं आपकी बात साफ़ काम (spec) में बदलूँगा, फिर प्लानर जड़ ढूँढकर लिखेगा"
            else "यह नया फ़ीचर/बदलाव लगता है → पहले मैं साफ़ करूँगा कि ठीक-ठीक क्या और कहाँ, फिर प्लानर")
        ev(Agent.PROMPT, "आपकी बात को साफ़ काम में बदल रहा हूँ — रिपो का नक्शा देखकर जगह भी ढूँढ रहा हूँ…")
        val ws = try { workspace() } catch (_: Exception) { emptyMap() }
        val msgs = mutableListOf("user" to ("USER REQUEST (user's own words):\n$task\n" + (if (media.isNotEmpty()) CoderMedia.describe(media) + "\n" else "") +
            "\nMANAGER: kind=${k.name.lowercase()}" +
            (if (brief0.isNotBlank()) "; brief=$brief0" else "") + "\nMODE: $mode\n" +
            // v3.6: प्रॉम्प्ट इंजीनियर को छोटा नक्शा (पहले ~1 लाख+ अक्षर हर चक्कर = धीमे नेटवर्क पर मिनटों अटका) - ज़रूरत हो तो वह खोज/पढ़ लेता है
            "REPO MAP (file: classes / functions / ids):\n" + SevenPlus.RepoMap.build(ws, maxOf(PROMPT_MAP_CHARS, mapChars)) + "\nFILE LIST (${fileList.size}):\n" + fileList.take(listMax).joinToString("\n")))
        for (round in 0..4) {
            if (round > 0) ev(Agent.PROMPT, "जवाब तैयार कर रहा हूँ… (चक्कर ${round + 1}/5)")
            val reply = aiAsk(Agent.PROMPT, Seven.Prompts.PROMPT, msgs)
            val terms = SevenPlus.RepoSearch.terms(reply)
            val reads = Seven.planReads(reply)
            if (round < 4 && terms != null) {
                ev(Agent.PROMPT, "रिपो में खोज रहा हूँ: " + terms.joinToString())
                msgs.add("model" to reply); msgs.add("user" to SevenPlus.RepoSearch.search(ws, terms) + "\nNow reply with the JSON, or search/read more.")
                continue
            }
            if (round < 4 && reads != null) {
                ev(Agent.PROMPT, "पढ़ रहा हूँ: " + reads.joinToString())
                msgs.add("model" to reply); msgs.add("user" to readFiles(reads) + "\nNow reply with the JSON, or search/read more.")
                continue
            }
            val o = Seven.json(reply)
            if (o != null && (o.has("understood") || o.has("spec"))) {
                val u = SevenPlus.Understanding.parse(o, k, brief0, acc0).copy(mediaNotes = mediaNotes, reply = reply0)
                ev(Agent.PROMPT, if (u.needsAnswer) "कुछ बातें साफ़ नहीं — आपसे पूछ रहा हूँ" else "समझ तैयार — आपकी मंज़ूरी का इंतज़ार")
                return u
            }
            msgs.add("model" to reply); msgs.add("user" to "Reply ONLY the JSON {\"understood\":…,\"spec\":…,\"acceptance\":[…],\"not_touch\":[…],\"area\":[…],\"questions\":[…]}")
        }
        ev(Agent.PROMPT, "पूरा spec नहीं बना — मैनेजर की समझ दिखा रहा हूँ")
        return SevenPlus.Understanding(k, brief0, brief0.ifBlank { task.take(300) }, brief0, acc0, emptyList(), emptyList(), emptyList(), mediaNotes = mediaNotes, reply = reply0)
    }

    /**
     * v5.1: समझाने वाला - मैनेजर का सौंपा सवाल / "समझाओ" / "जांच करो"। असली कोड खोजता-पढ़ता है, फिर आसान हिंदी में
     * गहरा, step-by-step जवाब (फ़ाइल: function के साथ)। कोड कभी नहीं बदलता। AI न चले = null (मैनेजर का छोटा जवाब बचता है)।
     */
    private suspend fun explain(question: String, quick: String, handoff: String = ""): String? {
        ev(Agent.MANAGER, "यह समझाने का काम है → ${Agent.EXPLAINER.icon} ${Agent.EXPLAINER.hi} को सौंपा (असली कोड पढ़कर गहराई से समझाएगा, कोड नहीं बदलेगा)" +
            if (handoff.isNotBlank()) "\nमैंने सवाल ऐसे समझा:\n$handoff" else "")
        ev(Agent.EXPLAINER, "असली कोड ढूँढ रहा हूँ…")
        return try {
            val ws = try { workspace() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; emptyMap() }
            val fileList = files.list()
            val msgs = mutableListOf("user" to ("OWNER'S QUESTION (his own words):\n$question\n" +
                (if (handoff.isNotBlank()) "\nMANAGER'S HANDOFF (the manager understood the question - explain EVERY point below, step by step, in full detail):\n$handoff\n" else "") +
                (if (quick.isNotBlank()) "\nMANAGER'S QUICK NOTE (may be incomplete - verify in the code):\n$quick\n" else "") +
                (if (mediaNotes.isNotBlank()) "\nPHOTO/VIDEO NOTES:\n$mediaNotes\n" else "") +
                "\nREPO MAP (file: classes / functions / ids):\n" + SevenPlus.RepoMap.build(ws, maxOf(PROMPT_MAP_CHARS, mapChars)) +
                "\nFILE LIST (${fileList.size}):\n" + fileList.take(listMax).joinToString("\n")))
            for (round in 0 until EXPLAIN_ROUNDS) {
                if (round > 0) ev(Agent.EXPLAINER, "पढ़ रहा हूँ और समझ जोड़ रहा हूँ… (चक्कर ${round + 1}/$EXPLAIN_ROUNDS)")
                val reply = aiAsk(Agent.EXPLAINER, Seven.Prompts.EXPLAINER, msgs)
                val last = round == EXPLAIN_ROUNDS - 1
                val terms = SevenPlus.RepoSearch.terms(reply)
                val reads = Seven.planReads(reply)
                if (!last && terms != null) {
                    ev(Agent.EXPLAINER, "रिपो में खोज रहा हूँ: " + terms.joinToString())
                    msgs.add("model" to reply); msgs.add("user" to SevenPlus.RepoSearch.search(ws, terms) + "\nSearch/read more, or give the full Hindi explanation now (plain text).")
                    continue
                }
                if (!last && reads != null) {
                    ev(Agent.EXPLAINER, "पढ़ रहा हूँ: " + reads.joinToString())
                    msgs.add("model" to reply); msgs.add("user" to readFiles(reads) + "\nSearch/read more, or give the full Hindi explanation now (plain text).")
                    continue
                }
                val t = reply.trim()
                if (terms == null && reads == null && t.length >= 20) { ev(Agent.EXPLAINER, "समझाना तैयार"); return t }
                msgs.add("model" to reply); msgs.add("user" to "Now give the complete explanation in simple Hindi as plain text with the headings (no JSON).")
            }
            // नवंबर 2026: आख़िरी चक्कर में भी खोज माँगी तो पहले खाली हाथ लौटता था - अब जितना पढ़ा उसी से पूरा जवाब
            val fin = aiAsk(Agent.EXPLAINER, Seven.Prompts.EXPLAINER, msgs + ("user" to "No more search/read. Using ONLY what you have read, give the complete explanation now in simple Hindi plain text with the headings (no JSON). Mark anything you could not read as \"पक्का नहीं\".")).trim()
            if (SevenPlus.RepoSearch.terms(fin) == null && Seven.planReads(fin) == null && fin.length >= 20) { ev(Agent.EXPLAINER, "समझाना तैयार"); fin } else null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ev(Agent.EXPLAINER, "⚠ गहराई से नहीं समझा पाया — मैनेजर का छोटा जवाब दिखा रहा हूँ"); null
        }
    }

    /**
     * नवंबर 2026: काम ख़त्म होने पर समझाने वाला - असली बदलाव (diff), मिली जड़, टेस्ट का नतीजा पढ़कर मालिक को समझाए.
     * कोड नहीं लिखता. AI न चले / जवाब बेकार = null (तब पक्का ढाँचा ही दिखता है - कुछ बनाया हुआ नहीं).
     */
    private suspend fun explainResult(testOk: Boolean?, problem: String, testNote: String): String? = try {
        ev(Agent.EXPLAINER, "असली बदलाव पढ़कर नतीजा समझा रहा हूँ…")
        val staged = files.staged()
        val user = buildString {
            append("OWNER'S REQUEST (his words):\n").append(task).append("\n")
            if (spec.isNotBlank()) append("SPEC (approved):\n").append(spec).append("\n") else if (brief.isNotBlank()) append("BRIEF: ").append(brief).append("\n")
            if (acceptance.isNotEmpty()) append("ACCEPTANCE:\n").append(acceptance.joinToString("\n") { "- $it" }).append("\n")
            if (notTouch.isNotEmpty()) append("MUST STAY UNCHANGED:\n").append(notTouch.joinToString("\n") { "- $it" }).append("\n")
            if (bugs.isNotEmpty()) append("BUGS FOUND BY THE DEBUGGER:\n").append(bugs.joinToString("\n") { "- what: ${it.what} | where: ${it.where} | root cause: ${it.rootCause} | fix: ${it.fix}" }).append("\n")
            append("CHANGED FILES (coder's note):\n").append(changes.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }).append("\n")
            append("RESULT: ").append(when (testOk) { true -> "build/tests PASSED"; false -> "FAILED - $problem"; null -> "NOT CONFIRMED YET - ${problem.ifBlank { testNote }}" }).append("\n")
            if (qaChecks.isNotEmpty()) append("QA CHECKS:\n").append(qaChecks.take(8).joinToString("\n") { "- $it" }).append("\n")
            if (tried.isNotEmpty()) append("WHAT THE TEAM TRIED:\n").append(tried.takeLast(6).joinToString("\n") { "- $it" }).append("\n")
            append("\nREAL DIFF (- removed, + added):\n").append(if (staged.isEmpty()) "(no change)" else diffText(staged, maxOf(40_000, big / 5)))
        }
        val t = aiAsk(Agent.EXPLAINER, Seven.Prompts.RESULT, listOf("user" to user)).trim()
        if (Seven.json(t) != null || t.length < 60 || t.contains("```")) null else t
    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }

    /** प्रॉम्प्ट इंजीनियर पहले फ़ोटो/वीडियो ध्यान से देखे - जो दिखा वह लिखकर पूरी टीम को (जिनका मॉडल फ़ोटो न देख सके उन्हें भी) */
    private suspend fun seeMedia() {
        ev(Agent.PROMPT, "आपकी ${CoderMedia.chip(media).removePrefix("📎 ")} ध्यान से देख रहा हूँ" + if (media.any { it.isVideo }) " (वीडियो frame-दर-frame)…" else "…")
        mediaNotes = try {
            ai.askMedia(Agent.PROMPT, Seven.Prompts.MEDIA + "\n\n" + Seven.Prompts.RULES,
                listOf("user" to "USER REQUEST:\n$task\n\n" + CoderMedia.describe(media) + "\nDescribe them completely now."), media).trim()
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; "" }
        if (mediaNotes.isBlank()) { mediaNotes = CoderMedia.describe(media) + " (the model could not look at them)"; ev(Agent.PROMPT, "⚠ फ़ोटो/वीडियो नहीं देख पाया — सिर्फ़ आपके शब्दों से समझूँगा") }
        else ev(Agent.PROMPT, "फ़ोटो/वीडियो में देखा:\n" + mediaNotes.take(600))
    }

    /** पिछली बार CI fail होते समय की staged हालत (test की ईमानदारी जाँच के लिए) */
    private var failBase: Map<String, String?>? = null
    /** नवंबर 2026 (तेज़): failBase वाले commit का असली log - अगला सुधार उसी गलती पर निशाना लगाता है? ([FixAim]) */
    private var failLogCi = ""

    private fun map(ws: Map<String, String>): String = repoMap ?: SevenPlus.RepoMap.build(ws, mapChars).also { repoMap = it }

    /** @param pre user की मंज़ूर की हुई समझ (11-एजेंट). null = मैनेजर यहीं समझेगा (पुराना क्रम) */
    suspend fun run(userTask: String, pre: SevenPlus.Understanding? = null): Outcome {
        task = userTask
        memory.userWords = userTask
        val kind: Seven.Kind
        if (pre != null) {
            // 1. मैनेजर: समझ user ने मंज़ूर कर दी - वही spec सबको
            kind = pre.kind
            spec = pre.spec; brief = pre.spec.ifBlank { pre.brief }; acceptance = pre.acceptance; notTouch = pre.notTouch; area = pre.area
            memory.understanding = pre.brief; memory.spec = pre.spec; memory.acceptance = pre.acceptance; memory.notTouch = pre.notTouch
            if (pre.mediaNotes.isNotBlank()) mediaNotes = pre.mediaNotes
            ev(Agent.MANAGER, "आपकी मंज़ूरी मिली — मंज़ूर की हुई समझ के हिसाब से काम सौंप रहा हूँ")
        } else {
            // 1. मैनेजर: काम समझो
            ev(Agent.MANAGER, "काम समझ रहा हूँ…")
            val fileList = files.list()
            val m = askJson(Agent.MANAGER, "USER REQUEST:\n$task\n\nMODE: $mode\nFILES IN WORKSPACE (${fileList.size}):\n" + fileList.take(listMax).joinToString("\n"))
            kind = Seven.kindOf(m?.optString("kind"), task)
            if (kind == Seven.Kind.STATUS) return Outcome(STATUS_ASK, emptyMap(), null, mode, answerOnly = true)
            if (kind == Seven.Kind.QUESTION) {
                val ans = m?.optString("answer").orEmpty().trim()
                if (ans.isNotBlank()) {
                    ev(Agent.MANAGER, "यह सवाल है — कोड बदलने की ज़रूरत नहीं")
                    return Outcome(ans, emptyMap(), null, mode, answerOnly = true)
                }
            }
            brief = m?.optString("brief").orEmpty().trim()
            acceptance = Seven.strings(m?.optJSONArray("acceptance"))
            memory.understanding = brief; memory.acceptance = acceptance
        }
        val bug = kind == Seven.Kind.BUG
        // 📒 पहले से खुले PR की काम-कॉपी: पिछले राउंड/एजेंट ने जो पढ़ा और किया - वहीं से, शुरू से नहीं
        git?.let { g ->
            val old = try { WorkNote.fromBody(g.loadNote()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            if (old != null) {
                note.files.putAll(old.files); note.done.addAll(old.done); note.todo = old.todo; note.status = old.status; note.next = old.next
                ev(Agent.MANAGER, "📒 PR की काम-कॉपी मिली: ${old.files.size} फ़ाइलों के नोट, ${old.done.size} काम हो चुके" +
                    (if (old.todo.isNotEmpty()) ", ${old.todo.size} बाकी" else "") + " — जो पढ़ा जा चुका, शुरू से दोबारा नहीं पढ़ेंगे")
            }
        }
        note.task = task.take(300)
        // "आगे करो": जो हो चुका वह याद - दोबारा नहीं
        val r = resumeFrom?.takeIf { it.phase > TeamProgress.NONE || it.leftSteps > 0 }
        if (r != null) {
            prog = r
            changes.putAll(r.changes); backup = r.backup; qaOk = r.qaOk
            if (r.commit.isNotBlank()) commitMsg = r.commit to r.title.ifBlank { task.take(80) }
            ev(Agent.MANAGER, "↩ वहीं से आगे: " + r.hindi())
        } else ev(Agent.MANAGER, (if (bug) "यह बग ठीक करने का काम है → प्लानर को सौंपा: पहले जड़ (कारण) ढूँढकर लिखेगा, कारण पता होने के बाद ही कोडर"
            else "यह नया फ़ीचर/बदलाव है → प्लानर को सौंपा: पहले फ़ाइलें पढ़कर कारण/ज़रूरत लिखेगा, फिर कोडर") +
            if (acceptance.isNotEmpty()) "\nपूरा होने की शर्तें:\n" + acceptance.joinToString("\n") { "• $it" } else "")

        // 2. गिट: backup + नई branch (सिर्फ़ GitHub मोड). "आगे करो" पर पहले से बनी हैं - दोबारा नहीं
        git?.let { g ->
            if (r != null && r.phase >= TeamProgress.BRANCH) {
                g.branch(task)
            } else {
                backup = g.backup(task)
                ev(Agent.GIT, if (backup.isNotBlank()) "backup branch बनाई: $backup" else "backup branch नहीं बन पाई — फिर भी main को हाथ नहीं लगेगा")
                ev(Agent.GIT, "नई branch: ${g.branch(task)} — सारा काम इसी में, main अछूता")
            }
        }
        if (r == null) save { it.copy(phase = TeamProgress.BRANCH) }

        // 3. प्लान (बग: पहले डिबगर)
        var rootCause = r?.rootCause.orEmpty()
        // "आगे करो": बीच में रुका प्लान - बाकी कदम ही
        if (r != null && r.leftSteps > 0) {
            if (!execute(Seven.Plan(r.steps, emptyList()), emptySet(), r.stepsDone)) return finish(false, lastBlock)
            save { it.copy(phase = maxOf(it.phase, TeamProgress.CODED), steps = emptyList(), stepsDone = 0, failLog = "", failUrl = "") }
        }
        val coded = prog.phase >= TeamProgress.CODED
        if (!coded && solo) {
            // एक-दिमाग: पूरा काम एक ही एजेंट (खोज → पढ़ना → सारे बदलाव → जाँच), हाथ-बदली नहीं
            ev(Agent.CODER, "एक ही एजेंट पूरा काम करेगा: पहले कोड खोजकर पढ़ेगा, फिर सारे बदलाव, फिर खुद जाँच")
            val order = Solo.firstOrder(task, brief, spec, acceptance, notTouch, git != null, files.list().size,
                try { map(ws()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "" })
            if (!soloWork(order)) return finish(false, lastBlock)
            if (!takeAdded()) return finish(false, lastBlock)
            save { it.copy(phase = TeamProgress.CODED, steps = emptyList(), stepsDone = 0, failLog = "", failUrl = "") }
        } else if (!coded) {
        val plan = if (bug) {
            ev(Agent.PLANNER, "पहले बग की जड़ ढूँढ रहा हूँ (कोड अभी नहीं बदलेगा)…")
            rootByPlanner = true
            val d = try { debug("USER BUG REPORT:\n$task" + if (spec.isNotBlank()) "\nSPEC (approved by the user): $spec" else "", repeat = false) }
                finally { rootByPlanner = false }
            if (d == null && !full) return finish(false, "डिबगर बग की जड़ नहीं ढूँढ पाया — थोड़ी और जानकारी दें (कब होता है, क्या एरर दिखता है)")
            if (d == null) {
                // मैनेजर: डिबगर अटका - रुककर user से पूछने की जगह दूसरा रास्ता: प्लानर डिबगर की पढ़ी फ़ाइलों से खुद जगह ढूँढकर सुधार बनाए
                val readByDebugger = debugSeen?.paths().orEmpty().toList()
                ev(Agent.MANAGER, "जड़ की एक पक्की लाइन नहीं मिली — रणनीति बदल रहा हूँ: प्लानर खुद खोजकर/पढ़कर जगह और कारण ढूँढेगा" +
                    if (readByDebugger.isNotEmpty()) " (अब तक पढ़ी फ़ाइलें: ${readByDebugger.joinToString()})" else "")
                bugs.add(Seven.Bug("जड़ की एक पक्की लाइन नहीं मिली", "-", "डिबगर ने ${readByDebugger.size} फ़ाइलें पढ़ीं, पर एक पक्की लाइन तय नहीं कर पाया", "प्लानर ने खोजकर सुधार बनाया"))
                makePlan("FIX THIS USER PROBLEM (the debugger could not pin one exact line - the needed code may be MISSING, not broken).\n" +
                    "USER PROBLEM: $task\n" + (if (spec.isNotBlank()) "SPEC: $spec\n" else "") +
                    "Search and read to find where the behaviour is (or must be) implemented, then plan the smallest complete change.\n" +
                    "ROOT CAUSE FIRST: in every step's \"why\" write the root cause you found (what is wrong or missing, and where).", readByDebugger)
            } else {
                rootCause = d.second
                memory.found("Root cause found: ${d.second.take(250)}")
                ev(Agent.PLANNER, "जड़ (कारण) लिखी: ${d.second.take(300)}\nअब इसी कारण पर सुधार का प्लान — कोडर इसके बाद")
                makePlan((if (d.first.missing) "ADD THE MISSING CODE for this user problem (it is not implemented anywhere yet) - smallest complete change, wired to the place given.\n"
                    else "MINIMAL FIX for this bug.\n") + "ROOT CAUSE: ${d.second}\nFIX: ${d.third}", d.first.files)
            }
        } else makePlan(task + if (brief.isNotBlank()) "\n(Manager brief: $brief)" else "", emptyList())
        plan ?: return finish(false, "प्लानर प्लान नहीं बना पाया: $lastBlock")
        // v5.2: कोडर से पहले प्लानर का लिखा कारण/ज़रूरत (बग = जड़; फ़ीचर = क्या और क्यों)
        val planWhy = plan.steps.map { it.why.trim() }.filter { it.isNotBlank() }.distinct()
        if (!bug || rootCause.isBlank()) ev(Agent.PLANNER, (if (bug) "जड़ (कारण) लिखी: " else "कारण/ज़रूरत लिखी: ") +
            (planWhy.take(3).joinToString(" • ").ifBlank { (spec.ifBlank { brief }).ifBlank { task }.take(300) }).take(400) + "\nअब कोडर शुरू करेगा")
        save { it.copy(rootCause = rootCause) }
        note.plan(plan.steps.map { stepText(it) })
        note.state("प्लान बना: ${plan.steps.size} कदम — कोड बदल रहा है", "कदम पूरे करके build/tests")
        pushNote()
        if (!execute(plan, emptySet())) return finish(false, lastBlock)
        // नवंबर 2026 (बड़ा प्रोजेक्ट मोड): काम 10 कदम से बड़ा = प्लानर ने बाकी काम "more" में लिखा -> अगला चरण, उसी branch/PR में
        var left = plan.more
        var phaseNo = 1
        while (left.isNotBlank() && phaseNo < Seven.MAX_PHASES) {
            phaseNo++
            ev(Agent.MANAGER, "बड़ा काम — चरण ${phaseNo - 1} पूरा. अब चरण $phaseNo (बाकी: ${left.take(200)})")
            note.state("चरण ${phaseNo - 1} पूरा — चरण $phaseNo का प्लान", "बाकी चरण, फिर build/tests")
            pushNote()
            val next = makePlan("CONTINUE THE SAME TASK - PHASE $phaseNo.\nORIGINAL TASK: $task\n" +
                (if (brief.isNotBlank()) "BRIEF: $brief\n" else "") +
                "ALREADY DONE IN EARLIER PHASES (do not redo): " + changes.entries.joinToString("; ") { "${it.key}: ${it.value.take(100)}" } +
                "\nLEFT FOR THIS PHASE: $left", changes.keys.toList())
                ?: return finish(false, "चरण $phaseNo का प्लान नहीं बना: $lastBlock")
            if (!execute(next, emptySet())) return finish(false, lastBlock)
            left = next.more
        }
        if (!takeAdded()) return finish(false, lastBlock)
        if (left.isNotBlank()) ev(Agent.MANAGER, "⚠ ${Seven.MAX_PHASES} चरणों के बाद भी कुछ बाकी — QA जाँचेगा, बाकी काम रिपोर्ट में: ${left.take(200)}")
        save { it.copy(phase = TeamProgress.CODED, steps = emptyList(), stepsDone = 0, failLog = "", failUrl = "") }
        // 3b. परफॉरमेंस: बदले code में UI जाम / leak / बेवजह का काम
        if (full) perf()
        }

        // 4. टेस्टर ⇄ डिबगर चक्र
        // "आगे करो": पिछली जाँच पास थी = दोबारा नहीं; फ़ेल थी और code तब से नहीं बदला = वही log सीधे डिबगर को
        var passedBefore = r != null && prog.phase >= TeamProgress.PASSED
        var oldFail = if (r != null && !passedBefore && prog.failLog.isNotBlank()) TeamTest(false, prog.failLog, prog.failUrl) else null
        val sigs = mutableListOf<String>()
        val failLogs = mutableListOf<String>()
        while (true) {
            // काम के बीच user की जोड़ी बात: पहले वह, फिर नई जाँच (पुराना पास/फ़ेल अब पुराना)
            if ((inbox?.size ?: 0) > 0) {
                if (!takeAdded()) return finish(false, lastBlock)
                passedBefore = false; oldFail = null
            }
            val t = when {
                passedBefore -> { passedBefore = false; TeamTest(true, "", "", "पिछली बार build + tests पास हो चुके थे", if (git?.prUrl.isNullOrBlank()) TeamTest.BRANCH else "") }
                oldFail != null -> oldFail.also { oldFail = null }
                else -> test(rootCause)
            }
            lastTest = t
            note.state(when (t.ok) { true -> "build/tests पास ✓" + (if (t.url.isNotBlank()) " ${t.url}" else ""); false -> "build/tests fail ✗ — डिबगर जड़ ढूँढ रहा है" + (if (t.url.isNotBlank()) " ${t.url}" else ""); null -> "build/tests का नतीजा अभी नहीं — " + t.note.take(120) },
                when (t.ok) { true -> "PR तैयार करना, फिर merge आपकी हाँ से"; false -> "fail की जड़ ठीक करना"; null -> "checks पूरे होने पर नतीजा देखना" })
            pushNote()
            if (t.ok == true) {
                // QA: build पास - पर क्या काम सच में और पूरा हुआ? (PR से पहले)
                if (full && !qaOk) {
                    val miss = qa()
                    if (miss.isNotEmpty()) {
                        qaRounds++
                        // AGENTS.md नियम 15: वही कमी 3 बार लगातार, या 8 राउंड = रुककर रिपोर्ट
                        qaMiss.add(miss.joinToString("; "))
                        tried.add("QA की कमी ठीक की: " + miss.joinToString("; ").take(120))
                        if (FixLoop.stuck(qaMiss)) return finish(false, "टेस्टर (QA जाँच) ने हूबहू वही कमी ${FixLoop.TEAM_SAME_LIMIT} बार लगातार बताई: " +
                            miss.joinToString("; ").take(300) + stuckNote(miss.joinToString("; ")))
                        if (FixLoop.capped(qaRounds)) return finish(false, "टेस्टर (QA जाँच) के ${FixLoop.TEAM_CAP} सुधार-राउंड के बाद भी कमी बाकी: " +
                            miss.joinToString("; ").take(300) + stuckNote(miss.joinToString("; ")))
                        if (solo) {
                            ev(Agent.MANAGER, "टेस्टर (QA जाँच) ने कमी बताई — वही कोडर पूरा करेगा (राउंड $qaRounds)")
                            if (!soloWork("QA CHECK: THE TASK IS NOT COMPLETE OR WRONG. Missing:\n" + miss.joinToString("\n") { "- $it" } +
                                    (if (qaFix.isNotBlank()) "\nQA suggested: $qaFix" else "") + "\nFix all of it, CHECK, then DONE.", Solo.FIX_TURNS)) return finish(false, lastBlock)
                            save { it.copy(phase = TeamProgress.CODED, steps = emptyList(), stepsDone = 0, failLog = "", failUrl = "") }
                            continue
                        }
                        ev(Agent.MANAGER, "टेस्टर (QA जाँच) ने कमी बताई — प्लानर से सुधार करवा रहा हूँ (राउंड $qaRounds)")
                        val fix = makePlan("QA FOUND THE TASK INCOMPLETE OR WRONG.\nMISSING:\n" + miss.joinToString("\n") { "- $it" } +
                            (if (qaFix.isNotBlank()) "\nQA SUGGESTED FIX: $qaFix" else ""), files.staged().keys.toList())
                            ?: return finish(false, "QA की बताई कमी का प्लान नहीं बना: $lastBlock")
                        if (!execute(fix, emptySet())) return finish(false, lastBlock)
                        save { it.copy(phase = TeamProgress.CODED, steps = emptyList(), stepsDone = 0, failLog = "", failUrl = "") }
                        continue
                    }
                    qaOk = true
                }
                // build/tests (+QA) पास - "आगे करो" पर दोबारा नहीं चलेंगे
                if (t.stage != TeamTest.BRANCH || git?.prUrl?.isNotBlank() == true) save { it.copy(phase = TeamProgress.PASSED, failLog = "", failUrl = "") }
                passNote = t.note
                ev(Agent.TESTER, (if (t.stage == TeamTest.BRANCH) "✓ PR से पहले branch पर build + tests पास" else if (t.stage == TeamTest.PR) "✓ PR का चेक पास" else "✓ टेस्ट + बिल्ड पास") +
                    if (t.url.isNotBlank()) "\n${t.url}" else "")
                // गिट: build पास होने के बाद ही PR (पहले नहीं); फिर उसी PR का अपना चेक भी देखो
                val g = git
                if (g != null && g.prUrl.isBlank()) {
                    val url = g.openPr()
                    if (url.isBlank()) return finish(null, "build पास है पर PR नहीं बन पाया" + if (g.prNote.isNotBlank()) ": ${g.prNote}" else "", "")
                    ev(Agent.GIT, "build पास होने के बाद PR बनाया: $url")
                    pushNote()
                    if (t.stage == TeamTest.BRANCH) { ev(Agent.TESTER, "अब PR का अपना चेक देख रहा हूँ…"); continue }
                }
                break
            }
            if (t.ok == null) return finish(null, t.note.ifBlank { "टेस्ट का नतीजा नहीं मिला" })
            // फ़ेल log याद रखो: रुके तो "आगे करो" पर टेस्ट दोबारा नहीं, सीधे डिबगर
            save { it.copy(phase = TeamProgress.CODED, failLog = t.log.take(TeamProgress.MAX_LOG), failUrl = t.url) }
            val errs = errorLines(t.log)
            ev(Agent.TESTER, "✗ फ़ेल — log पढ़कर असली एरर डिबगर को दिया" + (if (errs.isNotBlank()) "\nअसली एरर:\n$errs" else "") + if (t.url.isNotBlank()) "\n${t.url}" else "")
            sigs.add(FixLoop.signature(t.log))
            // AGENTS.md नियम 15: वही गलती 3 बार लगातार (अटक गए) या 8 fix-राउंड = रुककर user को साफ़ रिपोर्ट
            when (FixLoop.nextTeam(sigs)) {
                FixLoop.Next.STOP_STUCK -> return finish(false, "वही गलती ${FixLoop.TEAM_SAME_LIMIT} बार लगातार लौटी, रणनीति बदलकर भी नहीं गई: ${sigs.last().take(200)}" +
                    stuckNote(sigs.last()))
                FixLoop.Next.STOP_CAP -> return finish(false, "${FixLoop.TEAM_CAP} fix-राउंड के बाद भी build/tests पास नहीं हुए (हर बार गलती बदलती रही): ${sigs.last().take(200)}" +
                    stuckNote(sigs.last()))
                FixLoop.Next.FIX_AGAIN -> {}
            }
            failLogs.add(t.log)
            memory.found("CI failed: " + (errs.ifBlank { FixLoop.signature(t.log) }).lines().take(3).joinToString(" / ").take(300))
            learnFromCi(t.log)
            // नवंबर 2026: "वही गलती" = हूबहू वही, या वही जगह (फ़ाइल/test) - शब्द चाहे अलग हों (PR #252: ui_en.tsv 5 बार)
            val exact = sigs.size >= 2 && sigs[sigs.size - 1] == sigs[sigs.size - 2]
            val repeat = exact || FixLoop.sameSpot(failLogs)
            if (repeat) ev(Agent.MANAGER, (if (exact) "वही गलती दोबारा आई" else "उसी जगह (${FixLoop.sites(t.log).take(3).joinToString().ifBlank { "वही फ़ाइल" }}) फिर fail — शब्द अलग पर गलती वही") +
                " — रणनीति बदल रहा हूँ: डिबगर दूसरी जड़ देखेगा, कोडर पूरी फ़ाइल दोबारा लिखेगा")
            // v4.4: पहले पक्की जाँच - एरर उन्हीं नामों के हैं जो इसी काम की हटाई/आधी फ़ाइल में थे? तो जड़ वही: main से हूबहू वापस
            val restored = try { files.restoreDamaged(t.log) } catch (_: Exception) { emptyList() }
            if (restored.isNotEmpty()) {
                restored.forEach { changes[it.path] = "main से हूबहू वापस (इसी काम ने ${if (it.removed) "हटा दी" else "आधी कर दी"} थी)" }
                ev(Agent.DEBUGGER, "असली जड़ मिली — इसी काम ने ये फ़ाइलें हटाई/आधी कर दी थीं, एरर इन्हीं के नामों के हैं:\n" +
                    restored.joinToString("\n") { "• ${it.path}" + if (it.names.isNotEmpty()) " (${it.names.take(5).joinToString()})" else "" } + "\nमुख्य branch से हूबहू वापस लाई — अब सिर्फ़ ज़रूरी छोटा बदलाव")
            }
            val triedNote = if (repeat && tried.isNotEmpty()) "THE SAME PLACE FAILED AGAIN. These root causes / fixes were ALREADY TRIED and did NOT work - find a DIFFERENT real cause (re-read the exact current file lines, check the real names/types/resources):\n" +
                tried.takeLast(4).joinToString("\n") { "- $it" } + "\n\n" else ""
            // नवंबर 2026 (तेज़): "Unresolved reference" किसी R.<type>.x का है जो रिपो में है ही नहीं = पक्की जड़ + मौजूद नाम (AI अंदाज़ा नहीं)
            val resNote = resourceNote(t.log)
            if (resNote.isNotBlank()) ev(Agent.DEBUGGER, resNote.take(600))
            if (solo) {
                // एक-दिमाग: गलती उसी कोडर को, उसी बातचीत में - उसे पता है उसने क्या बदला और क्या पहले आज़माया
                soloFixes++
                tried.add("सुधार $soloFixes: " + (errs.ifBlank { FixLoop.signature(t.log) }).lines().first().take(150))
                ev(Agent.CODER, "✗ जाँच fail — वही कोडर असली गलती पढ़कर जड़ से ठीक कर रहा है (राउंड $soloFixes)")
                val extra = (if (resNote.isNotBlank()) resNote + "\n" else "") + if (restored.isNotEmpty()) CodeGuard.damageNote(restored, "main") else ""
                if (!soloWork(Solo.failOrder(soloFixes, errs, t.log.take(maxOf(20_000, big / 6)), t.url, extra, tried.dropLast(1), repeat), Solo.FIX_TURNS))
                    return finish(false, lastBlock)
                save { it.copy(phase = TeamProgress.CODED, steps = emptyList(), stepsDone = 0, failLog = "", failUrl = "") }
                continue
            }
            val d = debug("TEST/BUILD FAILED.\n" + triedNote + (if (resNote.isNotBlank()) resNote + "\n\n" else "") + (if (restored.isNotEmpty()) CodeGuard.damageNote(restored, "main") + "\n\n" else "") +
                (if (errs.isNotBlank()) "EXACT ERROR LINES FROM THE LOG:\n$errs\n\n" else "") + "FULL LOG (important part):\n${t.log.take(maxOf(30_000, big / 4))}", repeat)
                ?: fallbackDiag(t.log)
                ?: return finish(false, "डिबगर टेस्ट फ़ेल होने की जड़ नहीं ढूँढ पाया (log में फ़ाइल:लाइन वाला कोई एरर भी नहीं मिला)")
            rootCause = d.second
            tried.add("जड़ मानी: " + d.second.take(160))
            memory.found("Build/test failed; root cause taken: ${d.second.take(250)} | fix: ${d.third.take(120)}")
            save { it.copy(rootCause = rootCause) }
            note.did("जड़ मिली: " + d.second.take(160))
            val fix = makePlan("MINIMAL FIX for the failed test/build.\nROOT CAUSE: ${d.second}\nFIX: ${d.third}", d.first.files)
                ?: return finish(false, "प्लानर सुधार का प्लान नहीं बना पाया: $lastBlock")
            if (!execute(fix, if (repeat) fix.steps.map { it.file }.toSet() else emptySet())) return finish(false, lastBlock)
            // सुधार लागू - पुराना फ़ेल log अब बेकार (अगली बार नई जाँच)
            save { it.copy(phase = TeamProgress.CODED, steps = emptyList(), stepsDone = 0, failLog = "", failUrl = "") }
        }

        // 5. गिट: PR तैयार (merge नहीं)
        var pr = ""
        git?.let { g -> pr = g.ready(); ev(Agent.GIT, "PR तैयार: $pr — merge सिर्फ़ आपकी मंज़ूरी से") }
        return finish(true, "", pr)
    }

    /** नवंबर 2026 (मालिक): काम चलते समय user ने इसी काम में कुछ और जोड़ा - टीम उसे भी करे (स्क्रीन यहाँ डालती है) */
    var inbox: FollowUp.Inbox? = null

    /**
     * नवंबर 2026 (मालिक: "एक ही एजेंट सब काम कर लेगा... पूरा फिनिश करके ही दे"): true = प्लानर → कोडर → रिव्यूअर → डिबगर
     * की हाथ-बदली की जगह एक ही एजेंट ([Solo]) पूरी बातचीत याद रखकर खोजता, पढ़ता, बदलता, जाँचता है. पक्की जाँचें, टेस्टर
     * (CI), QA, गिट (branch/PR) और रिपोर्ट वही रहते हैं. false = पुराना 7 एजेंट क्रम जैसा का तैसा.
     */
    var solo = false
    /** एक-दिमाग वाले कोडर की पूरी बातचीत - हर राउंड (पहला काम, CI fail, QA कमी, नई बात) इसी में जुड़ता है */
    private val soloMsgs = mutableListOf<Pair<String, String>>()
    private var soloFixes = 0

    /**
     * एक-दिमाग वाला कोडर: order बातचीत में जोड़कर AI से कमांड लो, लागू करो, नतीजा वापस - DONE + पक्की जाँच साफ़ होने तक.
     * @return true = बदलाव तैयार (जाँच साफ़), false = नहीं हुआ (वजह [lastBlock] में)
     */
    private suspend fun soloWork(order: String, turns: Int = Solo.MAX_TURNS): Boolean {
        soloMsgs.add("user" to order)
        val formatErrs = mutableListOf<String>()
        val checkErrs = mutableListOf<String>()
        for (turn in 1..turns) {
            compactOld(soloMsgs)
            val reply = try { aiAsk(Agent.CODER, Solo.SYSTEM, soloMsgs) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                lastBlock = "AI से जवाब नहीं मिला: " + (e.message ?: e.javaClass.simpleName).take(200); return false
            }
            soloMsgs.add("model" to reply)
            val acts = Solo.parse(reply)
            if (acts.isEmpty()) {
                formatErrs.add("no-command")
                if (FixLoop.stuck(formatErrs)) { lastBlock = "AI ने ${FixLoop.TEAM_SAME_LIMIT} बार लगातार काम की कोई कमांड नहीं भेजी"; return false }
                soloMsgs.add("user" to Solo.NO_ACTION); continue
            }
            formatErrs.clear()
            val out = StringBuilder()
            val did = mutableListOf<String>()
            var done: Solo.Act.Done? = null
            for (a in acts) {
                when (a) {
                    is Solo.Act.Read -> {
                        val c = files.read(a.path)
                        if (c == null) out.append("----- ${a.path} NOT FOUND -----\n")
                        else { out.append(Solo.numbered(a.path, c, a.from, a.to, fileChars)).append('\n'); readSolo.add(a.path) }
                        did.add("📖 " + a.path.substringAfterLast('/') + (if (a.from != null) ":${a.from}-${a.to}" else ""))
                    }
                    is Solo.Act.Grep -> {
                        val hits = Solo.grep(ws(), a.pattern, a.under)
                        out.append("=== SEARCH ${a.pattern}${if (a.under.isNotEmpty()) " IN ${a.under}" else ""} (${hits.size}${if (hits.size >= Solo.GREP_MAX) "+, narrow it" else ""}) ===\n")
                            .append(hits.joinToString("\n").ifBlank { "(nothing found)" }).append('\n')
                        did.add("🔎 " + a.pattern.take(40))
                    }
                    is Solo.Act.ListFiles -> {
                        val all = files.list().filter { a.prefix.isEmpty() || it.startsWith(a.prefix) }
                        out.append("=== FILES ${a.prefix.ifEmpty { "(all)" }} (${all.size}) ===\n").append(all.take(Solo.LIST_MAX).joinToString("\n"))
                            .append(if (all.size > Solo.LIST_MAX) "\n(… ${all.size - Solo.LIST_MAX} more - use a longer prefix)" else "").append('\n')
                        did.add("📂 " + a.prefix.ifEmpty { "सब फ़ाइलें" })
                    }
                    is Solo.Act.Edit, is Solo.Act.Write, is Solo.Act.Delete -> {
                        val path = when (a) { is Solo.Act.Edit -> a.path; is Solo.Act.Write -> a.path; else -> (a as Solo.Act.Delete).path }
                        val r = soloChange(a, path)
                        out.append(r).append('\n')
                        did.add((if (r.startsWith("OK")) "✏ " else "✗ ") + path.substringAfterLast('/'))
                    }
                    is Solo.Act.Check -> {
                        val probs = soloProblems()
                        out.append(if (probs.isEmpty()) "CHECK: clean ✓ (${files.staged().size} files changed)\n" else "CHECK FOUND PROBLEMS - fix them:\n" + probs.joinToString("\n") { "- $it" } + "\n")
                        did.add(if (probs.isEmpty()) "✓ जाँच साफ़" else "⚠ जाँच: ${probs.size} कमी")
                    }
                    is Solo.Act.Bad -> out.append("FORMAT PROBLEM: ${a.why}\n")
                    is Solo.Act.Ask -> {
                        lastBlock = "कोडर को आपसे एक बात जाननी है: " + a.question.take(400)
                        ev(Agent.CODER, "❓ " + a.question.take(400))
                        return false
                    }
                    is Solo.Act.Done -> done = a
                }
            }
            if (did.isNotEmpty()) ev(Agent.CODER, did.take(12).joinToString(" • ") + if (did.size > 12) " • +${did.size - 12}" else "")
            val d = done
            if (d != null) {
                val probs = soloProblems().toMutableList()
                if (files.staged().isEmpty()) probs.add("NOTHING CHANGED: no file was changed yet - the task needs real code changes")
                if (probs.isEmpty()) {
                    val title = d.title.ifBlank { task.lines().first().take(70) }.take(90)
                    commitMsg = ("DK: " + title).take(100) to title
                    files.staged().keys.forEach { p -> if (p !in changes) changes[p] = title }
                    if (d.summary.isNotBlank()) { soloSummary = d.summary; memory.found("Coder finished: ${d.summary.take(300)}") }
                    ev(Agent.CODER, "✓ बदलाव पूरे — ${files.staged().size} फ़ाइलें" + if (d.summary.isNotBlank()) "\n" + d.summary.take(600) else "")
                    return true
                }
                checkErrs.add(probs.joinToString(" | ").take(400))
                if (FixLoop.stuck(checkErrs) || FixLoop.capped(checkErrs.size)) {
                    lastBlock = "पक्की जाँच की कमी ठीक नहीं हुई: " + probs.joinToString("; ").take(300); return false
                }
                ev(Agent.CODER, "⚠ DONE से पहले पक्की जाँच ने ${probs.size} कमी पकड़ी — ठीक कर रहा हूँ")
                out.append("NOT DONE - the strict check found problems. Fix ALL of them, CHECK, then DONE again:\n").append(probs.joinToString("\n") { "- $it" }).append('\n')
            }
            val left = turns - turn
            soloMsgs.add("user" to out.toString().trim().ifBlank { "(no output)" } + if (left <= Solo.WARN_LEFT) Solo.turnsLeft(left) else "")
        }
        lastBlock = "कोडर $turns जवाबों में काम पूरा नहीं कर पाया" + if (files.staged().isNotEmpty()) " (${files.staged().size} फ़ाइलें बदलीं, पर DONE नहीं हुआ)" else ""
        return false
    }

    /** इस काम में कोडर ने कौन-सी फ़ाइलें पढ़ीं - बिना पढ़े मौजूदा फ़ाइल पूरी दोबारा नहीं लिखनी */
    private val readSolo = mutableSetOf<String>()
    private var soloSummary = ""

    /** एक बदलाव लागू करो (सुरक्षित फ़ाइल / binary / बिना पढ़े पूरी फ़ाइल = मना). @return नतीजा AI के लिए ("OK …" या वजह) */
    private suspend fun soloChange(a: Solo.Act, path: String): String {
        if (!SafePr.askedFor(task, path) && SafePr.isProtected(path)) return "REFUSED $path: protected file (AGENTS.md) - the task did not ask for it"
        if (a !is Solo.Act.Delete && BinaryGuard.isBinary(path)) return "REFUSED $path: binary files (png/webp/jpg/mp3/font…) cannot be written as text. Use a vector drawable / shape XML in res/drawable or draw it in code."
        val cur = files.read(path)
        return when (a) {
            is Solo.Act.Delete -> {
                if (cur == null) "OK $path already does not exist"
                else { files.stage(path, null); changes[path] = "हटाई"; "OK deleted $path" }
            }
            is Solo.Act.Write -> {
                if (cur != null && path !in readSolo && cur.length > 4_000) "REFUSED $path: it already exists and you did not READ it - READ it, then use FIND/REPLACE"
                else {
                    val bad = Syntax.check(path, a.content)
                    files.stage(path, a.content)
                    (if (cur == null) "OK created $path" else "OK rewrote $path") + " (${a.content.lines().size} lines)" + if (bad != null) " - WARNING: $bad" else ""
                }
            }
            is Solo.Act.Edit -> {
                if (cur == null) "FAILED $path: file does not exist (to create it use FILE: + <<<FILE)"
                else {
                    val r = Solo.apply(cur, a.pairs)
                    val now = r.content
                    if (now == null) "FAILED $path (nothing applied):\n" + r.errors.joinToString("\n") { "  - $it" }
                    else if (now == cur) "OK $path (no difference - the REPLACE was the same as FIND)"
                    else {
                        files.stage(path, now)
                        val bad = Syntax.check(path, now)
                        "OK edited $path (${a.pairs.size} change${if (a.pairs.size > 1) "s" else ""})" + if (bad != null) " - WARNING: $bad" else ""
                    }
                }
            }
            else -> "?"
        }
    }

    /** पक्की जाँच + नाम-जाँच (नया code जो नाम बुलाता है वह सच में है?) - सिर्फ़ इस काम की बदली फ़ाइलें */
    private suspend fun soloProblems(): List<String> {
        val staged = files.staged()
        if (staged.isEmpty()) return emptyList()
        val out = staticCheck(staged).toMutableList()
        for ((p, c) in staged) {
            if (c == null || !(p.endsWith(".kt") || p.endsWith(".java"))) continue
            names(p, files.original(p), c).filter { it.sure }.take(8).forEach { out.add("$p: NAME DOES NOT EXIST - " + it.english()) }
        }
        return out
    }

    /** जोड़ी गई बातें उठाकर उनका छोटा प्लान + कोड. false = कोड बदलना fail */
    private suspend fun takeAdded(): Boolean {
        val add = inbox?.drain().orEmpty()
        if (add.isEmpty()) return true
        memory.userWords = memory.userWords.trim() + "\n\nUSER ADDED WHILE THE TEAM WAS WORKING (same task):\n" + add.joinToString("\n") { "- $it" }
        acceptance = acceptance + add
        memory.acceptance = acceptance
        task = task + "\n\n" + FollowUp.forAi(add)
        qaOk = false
        ev(Agent.MANAGER, "📩 आपकी नई बात मिली — इसी काम में जोड़ रहा हूँ:\n" + add.joinToString("\n") { "• $it" })
        if (solo) return soloWork("THE USER ADDED THIS TO THE SAME TASK while you were working - do it too, keep everything already done:\n" +
            add.joinToString("\n") { "- $it" } + "\nThen CHECK and DONE.", Solo.FIX_TURNS)
        val p = makePlan("USER ADDED TO THE SAME TASK WHILE THE TEAM WAS WORKING - do ONLY this addition now, keep everything already done:\n" +
            add.joinToString("\n") { "- $it" } + "\nORIGINAL TASK: $task\nALREADY CHANGED: " + changes.keys.joinToString(), changes.keys.toList())
        if (p == null) { ev(Agent.MANAGER, "⚠ नई बात का प्लान नहीं बना ($lastBlock) — रिपोर्ट में लिखा"); return true }
        return execute(p, emptySet())
    }

    // ------------------------------------------------------------------ प्लानर
    private suspend fun makePlan(what: String, hint: List<String>): Seven.Plan? {
        for (tryNo in 0..1) {
            val p = planOnce(what + if (tryNo == 1) "\nMANAGER: the previous planning failed ($lastBlock). Make the SMALLEST possible plan (1-3 steps)." else "", hint)
            if (p != null) return p
            if (tryNo == 0) ev(Agent.MANAGER, "प्लानर अटका — रणनीति बदलकर छोटा प्लान माँग रहा हूँ")
        }
        return null
    }

    private suspend fun planOnce(what: String, hint: List<String>): Seven.Plan? {
        ev(Agent.PLANNER, "फ़ाइलें देखकर प्लान बना रहा हूँ…")
        val list = files.list()
        val msgs = mutableListOf("user" to ("TASK:\n$what\n" +
            (if (acceptance.isNotEmpty()) "ACCEPTANCE:\n" + acceptance.joinToString("\n") { "- $it" } + "\n" else "") +
            (if (hint.isNotEmpty()) "DEBUGGER SAYS THESE FILES: ${hint.joinToString()}\n" else "") +
            (if (changes.isNotEmpty()) "ALREADY CHANGED IN THIS TASK: ${changes.keys.joinToString()}\n" else "") +
            (if (full && notTouch.isNotEmpty()) "DO NOT CHANGE (user's existing features): " + notTouch.joinToString("; ") + "\n" else "") +
            (if (full && area.isNotEmpty()) "PROMPT ENGINEER - LIKELY AREA (verify by reading):\n" + area.joinToString("\n") { "- $it" } + "\n" else "") +
            notePrompt() +
            (if (full) "REPO MAP (file: classes / functions / ids):\n" + map(ws()) + "\n" else "") +
            "FILE LIST (${list.size}):\n" + list.take(listMax).joinToString("\n") + if (list.isEmpty()) "(empty - create new files)" else ""))
        var usesFixed = false
        var whereFixed = 0
        val last = if (full) 8 else 3
        val sys = Seven.Prompts.PLANNER + (if (full) "\n" + SevenDebug.PLANNER_EXTRA else "") + WorkNote.PROMPT_RULE
        val seen = SevenDebug.Seen()
        for (round in 0..last) {
            if (full) compactOld(msgs)
            val reply = aiAsk(Agent.PLANNER, sys, msgs)
            val terms = SevenPlus.RepoSearch.terms(reply)
            if (terms != null && round < last) {
                ev(Agent.PLANNER, "रिपो में खोज रहा हूँ (कहाँ लिखना है): " + terms.joinToString())
                msgs.add("model" to reply); msgs.add("user" to SevenPlus.RepoSearch.search(ws(), terms) + "\nNow READ the right files ({\"read\":[…]}) or reply with the JSON plan.")
                continue
            }
            val reads = Seven.planReads(reply)
            if (reads != null && round < last) {
                ev(Agent.PLANNER, (if (full) "फ़ाइलें / हिस्से पढ़ रहा हूँ: " else "पूरी फ़ाइलें पढ़ रहा हूँ: ") + reads.joinToString())
                msgs.add("model" to reply); msgs.add("user" to (if (full) readParts(reads, seen) else readFiles(reads)) + "\nNow reply with the JSON plan, or read more files.")
                continue
            }
            val plan = Seven.parsePlan(reply)
            // प्लानर ने जो नाम (uses) बताए - उनकी फ़ाइल खोलकर पक्का: सच में हैं?
            if (plan != null && round < last && !usesFixed) {
                val bad = badUses(plan)
                if (bad.isNotEmpty()) {
                    usesFixed = true
                    ev(Agent.PLANNER, "प्लान के ये नाम फ़ाइल में नहीं मिले — फ़ाइल पढ़कर प्लान ठीक कर रहा हूँ:\n" + bad.joinToString("\n") { "• $it" })
                    msgs.add("model" to reply)
                    msgs.add("user" to "These \"uses\" entries are WRONG (the name is not in that file):\n" + bad.joinToString("\n") { "- $it" } +
                        "\nRead the right files ({\"read\":[…]}) and send the corrected JSON plan with only names that really exist.")
                    continue
                }
            }
            // जगह-जाँच (11-एजेंट): हर कदम की anchor फ़ाइल में सच में है? नई फ़ाइल पहले से तो नहीं?
            if (full && plan != null && round < last && whereFixed < 3) {
                val m = readMap(plan.steps.map { it.file }.distinct())
                val bad = SevenPlus.Where.problems(plan) { m[it] }.ifEmpty { SevenDebug.anchorsSeen(plan, { m[it] }, seen, fileChars) }
                if (bad.isNotEmpty()) {
                    whereFixed++
                    ev(Agent.PLANNER, "प्लान की जगह गलत निकली — खोजकर/पढ़कर सही जगह ढूँढ रहा हूँ:\n" + bad.joinToString("\n") { "• $it" })
                    msgs.add("model" to reply)
                    msgs.add("user" to "WHERE CHECK FAILED (the plan points to the wrong place):\n" + bad.joinToString("\n") { "- $it" } +
                        "\nUse {\"search\":[…]} and {\"read\":[…]} to find the exact place, then send the corrected JSON plan. The anchor must be copied from the file.")
                    continue
                }
            }
            if (plan != null) {
                takeNotes(Seven.json(reply))
                ev(Agent.PLANNER, "प्लान: ${plan.steps.size} कदम\n" + plan.steps.mapIndexed { i, s -> "${i + 1}. ${s.file} — ${s.action}: ${s.change.take(120)}" +
                    if (s.why.isNotBlank()) "\n   ↳ यहाँ क्यों: ${s.why.take(140)}" else "" }.joinToString("\n") +
                    if (plan.risks.isNotEmpty()) "\n⚠ पुराने फ़ीचर का ख़तरा:\n" + plan.risks.joinToString("\n") { "• $it" } else "")
                return plan
            }
            msgs.add("model" to reply); msgs.add("user" to "Reply ONLY the JSON plan: {\"steps\":[{\"file\":…,\"action\":…,\"change\":…,\"anchor\":…}],\"risks\":[…]}")
        }
        lastBlock = "प्लानर का जवाब JSON प्लान नहीं था"
        return null
    }

    /**
     * 11-एजेंट डिबगर: पूरे रिपो में खोज + रिपो का नक्शा + प्रॉम्प्ट इंजीनियर की जगहें + इस काम का diff,
     * फ़ाइलें लाइन नंबर के साथ (बड़ी फ़ाइल का कोई भी हिस्सा), और जड़ की पक्की जाँच (पढ़ी फ़ाइल / सही लाइन).
     */
    private suspend fun debugFull(problem: String, repeat: Boolean): Triple<Diag, String, String>? {
        ev(Agent.DEBUGGER, if (repeat) "पिछला सुधार असली जड़ नहीं था — दूसरी वजह ढूँढ रहा हूँ…" else "असली जड़ ढूँढ रहा हूँ — पहले रिपो में खोजूँगा, फिर कोड पढ़कर पक्का करूँगा…")
        val list = files.list()
        val w = ws()
        val staged = files.staged()
        val foot = try { files.footprint() } catch (_: Exception) { "" }
        val msgs = mutableListOf("user" to ("TASK: $task\n$problem\n" +
            (if (foot.isNotBlank()) foot + "\n" else "") +
            (if (repeat) "NOTE FROM MANAGER: the SAME error came back after the previous fix. That fix did NOT address the real cause. Look for a different cause.\n" else "") +
            (if (area.isNotEmpty()) "PROMPT ENGINEER - LIKELY AREA (verify by searching/reading):\n" + area.joinToString("\n") { "- $it" } + "\n" else "") +
            (if (notTouch.isNotEmpty()) "DO NOT CHANGE (user's existing features): " + notTouch.joinToString("; ") + "\n" else "") +
            (if (staged.isNotEmpty()) "DIFF OF THIS TASK (- removed, + added):\n" + diffText(staged, maxOf(60_000, big / 4)) + "\n" else "") +
            notePrompt() +
            "REPO MAP (file: classes / functions / ids):\n" + map(w) + "\n" +
            "FILE LIST (${list.size}):\n" + list.take(listMax).joinToString("\n")))
        val sys = Seven.Prompts.DEBUGGER + "\n" + SevenDebug.EXTRA + WorkNote.PROMPT_RULE
        val seen = SevenDebug.Seen()
        debugSeen = seen
        val keys = (w.keys + list).toSet()
        val resolve: (String) -> String? = { SevenDebug.resolve(it, keys) }
        // v3.89: log में जिन फ़ाइल:लाइन पर एरर है, वहीं का कोड (लाइन नंबर के साथ) पहले से पहले संदेश में - जो कभी छोटा नहीं होता.
        // पहले डिबगर ख़ुद ढूँढता था और कई बार ग़लत फ़ाइल पकड़ लेता था (PR #105: एरर test फ़ाइल में, इलाज main फ़ाइल का)
        val sites = BuildLog.sites(problem)
        if (sites.isNotEmpty()) {
            val pre = errorContext(sites, seen, keys)
            if (pre.isNotBlank()) {
                msgs[0] = "user" to (msgs[0].second + "\n\nCODE AT THE ERROR LOCATIONS FROM THE LOG (already read for you, line numbers shown - start HERE):\n" + pre)
                ev(Agent.DEBUGGER, "एरर वाली जगहों का कोड पहले ही पढ़ लिया: " + sites.mapNotNull { resolve(it.path) }.distinct().take(5).joinToString())
            }
        }
        var coverAsked = false
        var rejected = 0
        var nudged = 0
        val last = DEBUG_ROUNDS - 1
        for (round in 0..last) {
            val left = last - round
            compactOld(msgs)
            val reply = aiAsk(Agent.DEBUGGER, sys, msgs)
            val terms = SevenPlus.RepoSearch.terms(reply)
            val reads = Seven.planReads(reply)
            if ((terms != null || reads != null) && left <= FORCE_ROUNDS) {
                // खोज/पढ़ाई का हिस्सा ख़त्म: अब जो पढ़ा उसी से फ़ैसला (वरना अंतहीन खोज और आख़िर में कुछ नहीं)
                ev(Agent.MANAGER, "डिबगर बहुत देर से खोज रहा है — अब जो पढ़ा उसी से जड़ बताने को कह रहा हूँ")
                msgs.add("model" to reply)
                msgs.add("user" to "NO MORE SEARCH / READ - the reading budget is used. Decide NOW from what you have read. If the needed behaviour is not implemented anywhere, that IS the root cause (\"kind\":\"missing\"). Reply ONLY the final root cause JSON." + SevenDebug.roundsLeft(left))
                continue
            }
            if (terms != null) {
                ev(Agent.DEBUGGER, "रिपो में खोज रहा हूँ: " + terms.joinToString())
                msgs.add("model" to reply); msgs.add("user" to SevenPlus.RepoSearch.search(ws(), terms) + "\nNow READ the right files/parts ({\"read\":[…]}) or search more." + SevenDebug.roundsLeft(left))
                continue
            }
            if (reads != null) {
                ev(Agent.DEBUGGER, "पढ़ रहा हूँ (लाइन नंबर के साथ): " + reads.joinToString())
                msgs.add("model" to reply); msgs.add("user" to readNumbered(reads, seen, keys) + "\nReply with the root cause JSON, or search/read more." + SevenDebug.roundsLeft(left))
                continue
            }
            val o = Seven.json(reply)
            val root = o?.optString("root_cause").orEmpty().trim()
            if (o != null && root.length >= 8) {
                val probs = SevenDebug.verify(o, seen, resolve) { p -> w[p]?.lines()?.size }.toMutableList()
                // v3.89: एरर वाली फ़ाइल को इलाज छूता ही नहीं? एक बार टोको
                if (!coverAsked) ErrorCover.miss(o, sites, resolve)?.let { probs.add(it); coverAsked = true }
                // v5.1: बताई लाइन का "quote" असली फ़ाइल से मिलाओ
                if (probs.isEmpty()) QuoteCheck.problem(o, resolve) { p -> w[p] }?.let { probs.add(it) }
                if (probs.isNotEmpty() && rejected < SevenDebug.VERIFY_ROUNDS && left > FORCE_ROUNDS) {
                    rejected++
                    ev(Agent.DEBUGGER, "बताई जड़ पक्की नहीं — कोड पढ़कर दोबारा जाँच रहा हूँ:\n" + probs.joinToString("\n") { "• ${it.take(200)}" })
                    msgs.add("model" to reply)
                    msgs.add("user" to "ROOT CAUSE NOT VERIFIED (checked against the real files):\n" + probs.joinToString("\n") { "- $it" } +
                        "\nSearch/read what is needed, then reply with the corrected root cause JSON." + SevenDebug.roundsLeft(left))
                    continue
                }
                val missing = o.optString("kind").trim().equals("missing", true)
                val where = o.optString("where").trim().let { wh -> SevenDebug.location(wh)?.let { (p, l) -> resolve(p)?.let { rp -> if (l != null) "$rp:$l" else rp } } ?: wh }
                val fix = o.optString("fix").trim()
                if (probs.isNotEmpty()) ev(Agent.DEBUGGER, "⚠ जड़ पूरी तरह पक्की नहीं हो पाई (${probs.first().take(150)}) — फिर भी आगे बढ़ रहा हूँ, टेस्ट से पता चलेगा")
                bugs.add(Seven.Bug(o.optString("bug").trim().ifBlank { root.take(150) }, where, root, fix.ifBlank { "-" }))
                takeNotes(o)
                ev(Agent.DEBUGGER, (if (missing) "जड़ मिली (यह काम कोड में कहीं लिखा ही नहीं है): " else "जड़ मिली: ") + "${where.ifBlank { "-" }}\n$root\nसुधार: ${fix.take(200)}")
                val fl = Seven.strings(o.optJSONArray("files")).map { resolve(it) ?: it }
                return Triple(Diag(fl, missing), root, fix)
            }
            if (nudged >= 3) break
            nudged++
            msgs.add("model" to reply); msgs.add("user" to "ROOT CAUSE REQUIRED. Reply ONLY JSON {\"kind\":\"bug|missing\",\"root_cause\":\"file:line - what and why\",\"bug\":…,\"where\":\"file:line\",\"fix\":…,\"files\":[…]}" +
                (if (left > FORCE_ROUNDS) " or {\"search\":[…]} or {\"read\":[…]}." else ".") + SevenDebug.roundsLeft(left))
        }
        return null
    }

    /** डिबगर ने जो फ़ाइलें पढ़ीं (डिबगर अटके तो प्लानर को यही दी जाती हैं) */
    private var debugSeen: SevenDebug.Seen? = null

    /** प्लानर के लिए (11-एजेंट): बिना लाइन नंबर (anchor सीधे कॉपी हो), "path:START-END" वाले हिस्से भी, बड़ी फ़ाइल के साथ रूपरेखा; जो दिखा वह seen में */
    private suspend fun readParts(entries: List<String>, seen: SevenDebug.Seen): String {
        var budget = readBudget
        val out = mutableListOf<String>()
        val keys = files.list()
        for (e in entries) {
            val r0 = SevenDebug.parseRead(e)
            val r = SevenDebug.resolve(r0.path, keys)?.let { r0.copy(path = it) } ?: r0
            val c = try { files.read(r.path) } catch (_: Exception) { null }
            val b = SevenDebug.block(r, c, budget, fileChars, numbers = false)
            budget -= b.chars
            seen.add(b.path, b.from, b.to)
            if (b.chars > 0) note.read(b.path, c, b.from, b.to)
            out.add(b.text)
        }
        return out.joinToString("\n\n")
    }

    /** डिबगर के लिए: लाइन नंबर के साथ, "path:START-END" वाले हिस्से भी; जो दिखा वह seen में */
    private suspend fun readNumbered(entries: List<String>, seen: SevenDebug.Seen, keys: Collection<String>): String {
        var budget = debugBudget
        val out = mutableListOf<String>()
        for (e in entries) {
            val r0 = SevenDebug.parseRead(e)
            val r = SevenDebug.resolve(r0.path, keys)?.let { r0.copy(path = it) } ?: r0
            val c = try { files.read(r.path) } catch (_: Exception) { null }
            val b = SevenDebug.block(r, c, budget, fileChars)
            budget -= b.chars
            seen.add(b.path, b.from, b.to)
            if (b.chars > 0) note.read(b.path, c, b.from, b.to)
            out.add(b.text)
        }
        return out.joinToString("\n\n")
    }

    private suspend fun readFiles(paths: List<String>): String {
        var budget = readBudget
        val out = mutableListOf<String>()
        for (p in paths) {
            val c = files.read(p)
            out.add(when {
                c == null -> "----- $p : FILE NOT FOUND -----"
                budget <= 0 -> "----- $p : (skipped, read budget used) -----"
                else -> { val part = c.take(minOf(budget, fileChars)); budget -= part.length; note.read(p, c, 1, part.lines().size)
                    "----- BEGIN $p (${c.lines().size} lines)${if (part.length < c.length) " TRUNCATED" else ""} -----\n$part\n----- END $p -----" }
            })
        }
        return out.joinToString("\n\n")
    }

    // ------------------------------------------------------------------ डिबगर
    /** @param missing डिबगर: यह काम कोड में कहीं लिखा ही नहीं (टूटी लाइन नहीं, गायब हिस्सा) */
    data class Diag(val files: List<String>, val missing: Boolean = false)

    /** v3.89: एरर-जगहों का कोड, लाइन नंबर के साथ (छोटी फ़ाइल पूरी, बड़ी में ±25 लाइन), ज़्यादा से ज़्यादा 5 फ़ाइल */
    private suspend fun errorContext(sites: List<BuildLog.Site>, seen: SevenDebug.Seen, keys: Collection<String>): String {
        val byFile = LinkedHashMap<String, MutableList<Int>>()
        for (st in sites) { val p = SevenDebug.resolve(st.path, keys) ?: continue; byFile.getOrPut(p) { mutableListOf() }.add(st.line) }
        val entries = mutableListOf<String>()
        for ((p, ls) in byFile.entries.take(5)) {
            val total = try { files.read(p)?.lines()?.size } catch (_: Exception) { null } ?: continue
            if (total <= 220) entries.add(p) else BuildLog.windows(ls, total, 25).take(4).forEach { r -> entries.add("$p:${r.first}-${r.last}") }
        }
        if (entries.isEmpty()) return ""
        return readNumbered(entries, seen, keys)
    }

    /**
     * v3.89: डिबगर हार जाए तब भी काम न रुके - log की फ़ाइल:लाइन + compiler का संदेश ही जड़ मानकर सुधार.
     * (पहले यहाँ "जड़ नहीं ढूँढ पाया" कहकर पूरा काम रुक जाता था.) log में जगह ही न हो तो null.
     */
    private suspend fun fallbackDiag(log: String): Triple<Diag, String, String>? {
        val sites = BuildLog.sites(log).take(8)
        if (sites.isEmpty()) return null
        val keys = files.list()
        val fl = sites.mapNotNull { SevenDebug.resolve(it.path, keys) }.distinct()
        if (fl.isEmpty()) return null
        val root = sites.joinToString("\n") { "${SevenDebug.resolve(it.path, keys) ?: it.path}:${it.line} - ${it.msg}" }
        ev(Agent.MANAGER, "डिबगर पक्की जड़ नहीं बता पाया — log में बताई जगहों से सीधा सुधार करवा रहा हूँ:\n" + root.take(500))
        return Triple(Diag(fl), root, "Fix exactly these build/test errors at these lines (read each file around the line, keep everything else unchanged).")
    }

    /** @return (फ़ाइलें, root cause, fix) */
    private suspend fun debug(problem: String, repeat: Boolean): Triple<Diag, String, String>? {
        if (full) return debugFull(problem, repeat)
        ev(Agent.DEBUGGER, if (repeat) "पिछला सुधार असली जड़ नहीं था — दूसरी वजह ढूँढ रहा हूँ…" else "असली जड़ ढूँढ रहा हूँ…")
        val list = files.list()
        val msgs = mutableListOf("user" to ("TASK: $task\n$problem\n" +
            (if (repeat) "NOTE FROM MANAGER: the SAME error came back after the previous fix. That fix did NOT address the real cause. Look for a different cause.\n" else "") +
            (if (changes.isNotEmpty()) "FILES CHANGED IN THIS TASK: ${changes.keys.joinToString()}\n" else "") +
            notePrompt() +
            "FILE LIST (${list.size}):\n" + list.take(listMax).joinToString("\n")))
        var warned = false
        for (round in 0..4) {
            val reply = aiAsk(Agent.DEBUGGER, Seven.Prompts.DEBUGGER + WorkNote.PROMPT_RULE, msgs)
            val terms = if (full) SevenPlus.RepoSearch.terms(reply) else null
            if (terms != null && round < 4) {
                ev(Agent.DEBUGGER, "रिपो में खोज रहा हूँ: " + terms.joinToString())
                msgs.add("model" to reply); msgs.add("user" to SevenPlus.RepoSearch.search(ws(), terms) + "\nRead the files ({\"read\":[…]}) or reply with the root cause JSON.")
                continue
            }
            val reads = Seven.planReads(reply)
            if (reads != null && round < 4) {
                ev(Agent.DEBUGGER, "पढ़ रहा हूँ: " + reads.joinToString())
                msgs.add("model" to reply); msgs.add("user" to readFiles(reads) + "\nReply with the root cause JSON, or read more files.")
                continue
            }
            val o = Seven.json(reply)
            val root = o?.optString("root_cause").orEmpty().trim()
            if (o != null && root.length >= 8) {
                val where = o.optString("where").trim()
                val fix = o.optString("fix").trim()
                bugs.add(Seven.Bug(o.optString("bug").trim().ifBlank { root.take(150) }, where, root, fix.ifBlank { "-" }))
                takeNotes(o)
                ev(Agent.DEBUGGER, "जड़ मिली: ${where.ifBlank { "-" }}\n$root\nसुधार: ${fix.take(200)}")
                return Triple(Diag(Seven.strings(o.optJSONArray("files"))), root, fix)
            }
            if (warned && round >= 3) break
            warned = true
            msgs.add("model" to reply); msgs.add("user" to "ROOT CAUSE REQUIRED. Reply ONLY JSON {\"root_cause\":\"file:line - what and why\",\"bug\":…,\"where\":…,\"fix\":…,\"files\":[…]} or {\"read\":[…]}.")
        }
        return null
    }

    // ------------------------------------------------------------------ कोडर ⇄ रिव्यूअर, कदम-दर-कदम
    /** @param done "आगे करो": इतने कदम पहले ही हो चुके - वे छोड़ो */
    private suspend fun execute(plan: Seven.Plan, rewrite: Set<String>, done: Int = 0): Boolean {
        curPlan = plan
        memory.plan = plan.steps.map { stepText(it) }; memory.stepsDone = done
        save { it.copy(steps = plan.steps, stepsDone = done) }
        plan.steps.forEachIndexed { i, s ->
            if (i < done) return@forEachIndexed
            ev(Agent.CODER, "कदम ${i + 1}/${plan.steps.size}: ${s.file} — ${s.change.take(140)}")
            if (step(s, s.file in rewrite)) { memory.stepsDone = i + 1; save { it.copy(stepsDone = i + 1) }; note.did(stepText(s)); pushNote(); return@forEachIndexed }
            // मैनेजर: अटके कदम पर दूसरा रास्ता - प्लानर से इसी हिस्से का नया प्लान
            ev(Agent.MANAGER, "कदम ${i + 1} अटका (${lastBlock.take(160)}) — रोककर दूसरा रास्ता: प्लानर से इस हिस्से का नया प्लान")
            val alt = planOnce("ONLY THIS PART of the task: ${s.change}\nFILE: ${s.file}\nThe previous attempt failed: $lastBlock\n" +
                "Plan a DIFFERENT approach (e.g. smaller steps, different location, or rewriting a small file).", listOf(s.file))
                ?: return false
            for ((j, a) in alt.steps.withIndex()) {
                ev(Agent.CODER, "नया रास्ता ${j + 1}/${alt.steps.size}: ${a.file} — ${a.change.take(140)}")
                if (!step(a, forceRewrite = false)) {
                    lastBlock = "कदम \"${s.change.take(100)}\" दो तरीक़ों से भी लागू नहीं हुआ: $lastBlock"
                    return false
                }
            }
            memory.stepsDone = i + 1; memory.found("Step ${i + 1} (${s.file}) failed first (${lastBlock.take(150)}) - done by a different approach")
            save { it.copy(stepsDone = i + 1) }
            note.did(stepText(s) + " (दूसरे रास्ते से)"); pushNote()
        }
        return true
    }

    private suspend fun step(s: Seven.Step, forceRewrite: Boolean): Boolean {
        val cur = files.read(s.file)
        if (s.action == "edit" && cur == null) { lastBlock = "${s.file} मौजूद नहीं"; return false }
        // audit K2: सुरक्षित फ़ाइल (workflows, AGENTS.md, gradle/signing) - यूज़र ने साफ़ न माँगी हो तो code से रोक (सिर्फ़ prompt पर भरोसा नहीं)
        if (!SafePr.askedFor(task, s.file) && SafePr.isProtected(s.file)) {
            lastBlock = "${s.file} सुरक्षित फ़ाइल है (AGENTS.md: छेड़ना मना) — काम में यह फ़ाइल साफ़ नहीं माँगी गई, इसलिए बदलाव रोका"
            ev(Agent.REVIEWER, "✗ " + lastBlock)
            return false
        }
        // नवंबर 2026 (dk_theme_bg.webp टूटी): तस्वीर/font जैसी binary फ़ाइल text से नहीं लिखी जाती - यह कदम छोड़ो
        if (s.action != "delete" && BinaryGuard.isBinary(s.file)) {
            ev(Agent.REVIEWER, "✗ " + BinaryGuard.refusal(s.file) + " — यह कदम छोड़ा")
            return true
        }
        if (s.action == "delete") return deleteStep(s, cur)
        val errors = mutableListOf<String>()
        var misses = 0
        var strat = if (forceRewrite && cur != null && cur.length <= Seven.REWRITE_MAX_CHARS) Seven.Strategy.REWRITE else Seven.Strategy.FIND
        if (strat == Seven.Strategy.REWRITE) ev(Agent.MANAGER, "रणनीति: ${s.file} पूरी पढ़कर पूरी दोबारा लिखवा रहा हूँ" + if (backup.isNotBlank()) " (backup: $backup)" else " (पुरानी फ़ाइल याद रखी है)")
        val defs = definitions(s)
        var msgs = mutableListOf("user" to coderBrief(s, cur, strat, defs))
        // AGENTS.md नियम 15: वही गलती 3 बार लगातार या 8 कोशिशें = रुककर मैनेजर दूसरा रास्ता लेता है
        var attempt = 0
        var lookups = 0
        while (!FixLoop.stuck(errors) && !FixLoop.capped(attempt)) {
            attempt++
            val sys = Seven.Prompts.CODER + if (strat == Seven.Strategy.REWRITE) "\n\n" + Seven.Prompts.CODER_REWRITE else ""
            val reply = aiAsk(Agent.CODER, sys, msgs)
            // नवंबर 2026 (मालिक: "कोडिंग एजेंट मेरे जैसा"): कोडर को नाम पक्का न हो तो खुद रिपो में ढूँढे/पढ़े (3 बार तक, कोशिश नहीं गिनी)
            // - जैसे Perplexity लिखने से पहले rg/read करता है. पहले वह अंदाज़े से नाम बना देता था (onPlus, isImage... feature/dk-1110-0955)
            if (lookups < CODER_LOOKUPS && Seven.isLookup(reply)) {
                lookups++; attempt--
                val terms = SevenPlus.RepoSearch.terms(reply)
                val found = if (terms != null) SevenPlus.RepoSearch.search(ws(), terms)
                    else Seven.planReads(reply)?.let { readFiles(it.take(READS_PER_LOOKUP)) }.orEmpty()
                ev(Agent.CODER, "लिखने से पहले रिपो में देख रहा हूँ: " + (terms ?: Seven.planReads(reply).orEmpty()).joinToString(", ").take(120))
                msgs.add("model" to reply)
                msgs.add("user" to found.ifBlank { "(nothing found)" } + "\nNow write the change in the exact format (FILE: + <<<FIND/<<<REPLACE or <<<FILE)." +
                    if (lookups >= CODER_LOOKUPS) " No more lookups." else "")
                continue
            }
            val edit = Seven.parseEdit(reply, s.file)
            val chk = Seven.check(edit, s, cur, allowRewrite = strat == Seven.Strategy.REWRITE || cur == null)
            var problems = chk.problems
            var forCoder = ""
            // रिव्यूअर की पक्की नाम-जाँच: नया code जो function/class/variable बुलाता है, वह सच में है?
            val miss = if (chk.ok && chk.content != null) names(s.file, cur, chk.content) else emptyList()
            val sure = miss.filter { it.sure }
            if (sure.isNotEmpty()) {
                problems = sure.map { "नाम मौजूद नहीं: " + it.hindi() }
                forCoder = "\nNAME CHECK FAILED (these names do not exist - read the definitions and use the real names):\n" + sure.joinToString("\n") { "- " + it.english() }
            } else if (chk.ok) {
                // रिव्यूअर (AI): पुराना फ़ीचर / compile / कदम पूरा? (+ जो नाम पक्के नहीं, उनका सबूत)
                // नवंबर 2026 (तेज़): छोटा साफ़ बदलाव जो सारी पक्की जाँच पास कर चुका = अलग AI रिव्यू कॉल नहीं ([CoderSpeed.quickPass])
                val quick = fast && CoderSpeed.quickPass(edit.pairs, edit.full != null, cur != null, miss.size,
                    CodeGuard.flips(cur, chk.content).size, CodeGuard.droppedGuards(cur, chk.content).size, attempt)
                if (quick) { clock.reviewSkipped(); ev(Agent.REVIEWER, "छोटा साफ़ बदलाव — पक्की जाँच (FIND एक जगह, syntax, नाम, शर्तें) पास, अलग AI रिव्यू नहीं (तेज़)") }
                val r = if (quick) emptyList() else review(s, cur, edit, chk.content, miss)
                if (r.isEmpty()) {
                    files.stage(s.file, chk.content)
                    changes[s.file] = (changes[s.file]?.let { "$it; " } ?: "") + s.change.take(160)
                    ev(Agent.REVIEWER, "✓ ${s.file}: जाँच पास — बदलाव लागू")
                    return true
                }
                problems = r
            }
            if (chk.findMiss) misses++
            errors.add(problems.joinToString(" | "))
            ev(Agent.REVIEWER, "✗ ${s.file}: वापस कोडर को (कोशिश $attempt)\n" + problems.joinToString("\n") { "• ${it.take(200)}" })
            lastBlock = problems.joinToString("; ").take(300)
            val next = Seven.strategy(errors, misses, cur?.length ?: 0, cur != null)
            if (next != strat) {
                strat = next
                ev(Agent.MANAGER, "एक ही गलती बार-बार / FIND नहीं बन रहा — तरीका बदला: ${s.file} पूरी पढ़कर पूरी दोबारा लिखवा रहा हूँ" +
                    if (backup.isNotBlank()) " (backup: $backup)" else "")
                msgs = mutableListOf("user" to coderBrief(s, cur, strat, defs) + forCoder)
            } else {
                msgs.add("model" to reply)
                msgs.add("user" to "REVIEWER REJECTED (the change was NOT applied):\n" + problems.joinToString("\n") { "- $it" } + forCoder +
                    (if (chk.findMiss && cur != null) "\nHere is the exact current file again - copy FIND character-for-character:\n" + fileBlock(s, cur) else "") +
                    "\nSend the corrected change in the same format.")
            }
        }
        lastBlock = (if (FixLoop.stuck(errors)) "हूबहू वही गलती ${FixLoop.TEAM_SAME_LIMIT} बार लगातार: " else "${FixLoop.TEAM_CAP} कोशिशों के बाद भी लागू नहीं: ") + lastBlock
        return false
    }

    private suspend fun deleteStep(s: Seven.Step, cur: String?): Boolean {
        if (cur == null) { lastBlock = "${s.file} मौजूद नहीं"; return false }
        // v4.4: पक्की जाँच (AI से पहले) - फ़ाइल बाकी रिपो में इस्तेमाल है तो कभी नहीं हटती
        val gone = files.staged().filterValues { it == null }.keys + s.file
        CodeGuard.deleteProblem(s.file, cur, ws(), gone)?.let { p ->
            lastBlock = p; ev(Agent.REVIEWER, "✗ ${s.file} हटाना रोका: यह फ़ाइल अभी भी इस्तेमाल हो रही है\n$p"); return false
        }
        val o = askJson(Agent.REVIEWER, "TASK: $task\nSTEP: delete file ${s.file} — ${s.change}\nFILE LIST:\n" + files.list().take(listMax).joinToString("\n") +
            "\nCould deleting it break an existing feature (is it still used)?")
        val probs = Seven.strings(o?.optJSONArray("problems"))
        if (o != null && !o.optBoolean("pass", false) && probs.isNotEmpty()) { lastBlock = probs.joinToString("; "); ev(Agent.REVIEWER, "✗ ${s.file} हटाना रोका: $lastBlock"); return false }
        files.stage(s.file, null); changes[s.file] = "हटाई: ${s.change.take(120)}"
        ev(Agent.REVIEWER, "✓ ${s.file} हटाने की जाँच पास")
        return true
    }

    private fun fileBlock(s: Seven.Step, cur: String): String {
        val body = if (cur.length <= fileChars) cur else Seven.window(cur, s.anchor)
        return "----- BEGIN ${s.file}${if (body.length < cur.length) " (PART around the change)" else ""} -----\n$body\n----- END ${s.file} -----"
    }

    private fun coderBrief(s: Seven.Step, cur: String?, strat: Seven.Strategy, defs: String = "") = "TASK: $task\nSTEP (${s.action}): ${s.change}\nFILE: ${s.file}\n" +
        handOff(s) +
        (if (defs.isNotBlank()) "DEFINITIONS (real, read from the files - use exactly these names/signatures):\n$defs\n" else "") +
        (if (cur == null) "(new file - send FILE: ${s.file} and <<<FILE … FILE>>>)" else if (strat == Seven.Strategy.REWRITE)
            "FULL CURRENT FILE (rewrite it completely, keep everything else exactly):\n----- BEGIN ${s.file} -----\n$cur\n----- END ${s.file} -----"
        else fileBlock(s, cur))

    /** पूरा समझाकर आगे देना: कोडर/रिव्यूअर को मंज़ूर spec, यह कदम क्यों, पूरा प्लान, शर्तें, क्या नहीं छेड़ना */
    private fun handOff(s: Seven.Step): String = buildString {
        if (s.why.isNotBlank()) append("WHY THIS STEP (planner): ").append(s.why).append('\n')
        if (s.anchor.isNotBlank()) append("WHERE (anchor text near the change): ").append(s.anchor).append('\n')
        if (s.uses.isNotEmpty()) append("USES (from other files): ").append(s.uses.joinToString("; ")).append('\n')
        if (spec.isNotBlank()) append("SPEC (approved by the user):\n").append(spec).append('\n')
        else if (brief.isNotBlank()) append("BRIEF: ").append(brief).append('\n')
        curPlan?.takeIf { it.steps.size > 1 }?.let { p ->
            append("WHOLE PLAN (for context - you do ONLY the STEP above):\n")
            p.steps.forEachIndexed { i, x -> append(if (x === s) "-> " else "   ").append(i + 1).append(". ").append(x.file).append(" - ").append(x.change).append('\n') }
        }
        if (acceptance.isNotEmpty()) append("ACCEPTANCE (the whole task):\n").append(acceptance.joinToString("\n") { "- $it" }).append('\n')
        if (notTouch.isNotEmpty()) append("DO NOT CHANGE (user's existing features): ").append(notTouch.joinToString("; ")).append('\n')
    }

    /** @return समस्याएँ (खाली = पास) */
    private suspend fun review(s: Seven.Step, cur: String?, e: Seven.Edit, after: String?, unsure: List<SevenSymbols.Miss> = emptyList()): List<String> {
        val change = when {
            e.full != null && cur == null -> "NEW FILE ${s.file}:\n" + e.full.take(maxOf(20_000, fileChars))
            e.full != null -> "FULL REWRITE of ${s.file}. Lines before: ${cur!!.lines().size}, after: ${after?.lines()?.size}.\nREMOVED LINES:\n" +
                (cur.lines().toSet() - (after ?: "").lines().toSet()).take(400).joinToString("\n") + "\nADDED LINES:\n" +
                ((after ?: "").lines().toSet() - cur.lines().toSet()).take(600).joinToString("\n")
            else -> e.pairs.mapIndexed { i, (f, r) -> "--- FIND #${i + 1}\n$f\n+++ REPLACE #${i + 1}\n$r" }.joinToString("\n")
        }
        val ctx = if (cur != null && e.full == null) "\nFILE CONTEXT:\n" + (if (cur.length <= fileChars) cur else Seven.window(cur, e.pairs.first().first.lines().first(), 60)) else ""
        // v4.4: शर्त उल्टी हुई? (जैसे `if (!inSeg)` -> `if (inSeg)`) - रिव्यूअर को अलग से दिखाओ
        val flips = CodeGuard.flips(cur, after)
        val dropped = CodeGuard.droppedGuards(cur, after).filter { g -> flips.none { it.contains(g) } }
        val flipNote = (if (flips.isEmpty()) "" else "\nCONDITION FLIPPED by this change (a bug unless the STEP explicitly asks to invert it - check each):\n" + flips.joinToString("\n") { "- $it" }) +
            (if (dropped.isEmpty()) "" else "\nSAFETY / CONDITION REMOVED by this change (old check / retry / fallback / catch is gone - reject unless the STEP asks to remove it or the same protection exists in the new code):\n" + dropped.joinToString("\n") { "- $it" })
        val nameCheck = flipNote + if (unsure.isEmpty()) "" else "\nNAME CHECK (not found in this repository - only valid if it really exists in the external parent class / SDK):\n" +
            unsure.joinToString("\n") { "- " + it.english() }
        // नवंबर 2026: रिव्यूअर को भी नए code की जुड़ी classes की असली API + ज़रूरत हो तो खुद दूसरी फ़ाइलें पूरी पढ़े (पहले बस diff देखता था)
        val api = relatedApi(s.file, after ?: change)
        val apiNote = if (api.isBlank()) "" else "\nREAL API OF REPO CLASSES USED (read from the files - a constructor arg or member not listed is not declared in that class; only an Android/SDK parent class could add it):\n$api"
        val msgs = mutableListOf("user" to "TASK: $task\nSTEP: ${s.change}\nFILE: ${s.file}\n" + handOff(s) + "CHANGE:\n$change$ctx$nameCheck$apiNote" +
            "\n\nIf you must see another file before deciding, reply ONLY {\"read\":[\"path\", ...]} (up to $READS_PER_LOOKUP files, $REVIEW_READS times). Otherwise reply the verdict JSON.")
        var reads = 0; var bad = 0
        while (bad < 2) {
            val reply = aiAsk(Agent.REVIEWER, Seven.Prompts.of(Agent.REVIEWER), msgs)
            val o = Seven.json(reply)
            if (o == null) { bad++; msgs.add("model" to reply); msgs.add("user" to "Reply ONLY valid JSON in the required format."); continue }
            val want = Seven.strings(o.optJSONArray("read"))
            if (want.isNotEmpty() && !o.has("pass") && reads < REVIEW_READS) {
                reads++
                ev(Agent.REVIEWER, "फ़ैसले से पहले पढ़ रहा हूँ: " + want.take(READS_PER_LOOKUP).joinToString(", ").take(140))
                msgs.add("model" to reply)
                msgs.add("user" to readFiles(want.take(READS_PER_LOOKUP)) + "\nNow reply the verdict JSON" + if (reads >= REVIEW_READS) " (no more reads)." else ".")
                continue
            }
            if (want.isNotEmpty() && !o.has("pass")) { bad++; msgs.add("model" to reply); msgs.add("user" to "No more reads. Reply ONLY the verdict JSON now."); continue }
            if (o.optBoolean("pass", false)) return emptyList()
            return Seven.strings(o.optJSONArray("problems")).ifEmpty { listOf("रिव्यूअर ने बदलाव ठीक नहीं माना (वजह नहीं बताई)") }
        }
        // रिव्यूअर का जवाब पढ़ा नहीं जा सका: पक्की (बिना AI) जाँच पास हो चुकी है - वही मान्य
        ev(Agent.REVIEWER, "AI रिव्यू का जवाब नहीं मिला — पक्की जाँच (FIND/REPLACE + syntax) पास है, उसी पर लागू")
        return emptyList()
    }

    // ------------------------------------------------------------------ टेस्टर
    private suspend fun test(rootCause: String): TeamTest {
        ev(Agent.TESTER, "टेस्ट चला रहा हूँ…")
        val staged = files.staged()
        val stat = staticCheck(staged).toMutableList()
        // नवंबर 2026 (तेज़, feature/dk-1112-1236): सुधार ने गलती वाली लाइनें छुई ही नहीं (सिर्फ़ comment/दूसरी लाइनें) = push + CI का
        // 6-10 मिनट वाला राउंड नहीं; उसी असली गलती के साथ सीधे डिबगर को
        if (fast) failBase?.let { fb -> if (failLogCi.isNotBlank()) FixAim.missed(failLogCi, fb, staged)?.let { m ->
            stat.add(m)
            errorLines(failLogCi).takeIf { it.isNotBlank() }?.let { stat.add("EXACT ERROR LINES (previous build):\n$it") }
        } }
        if (stat.isNotEmpty()) return TeamTest(false, "STATIC CHECK FAILED:\n" + stat.joinToString("\n"))
        if (staged.isEmpty()) return TeamTest(false, "NOTHING CHANGED: no file was changed for the task")
        val g = git
        if (g != null) {
            val cm = commitMsg ?: run {
                val o = askJson(Agent.GIT, "TASK: $task\nCHANGES:\n" + changes.entries.joinToString("\n") { "${it.key}: ${it.value}" })
                (o?.optString("commit").orEmpty().ifBlank { "DK: " + task.take(60) } to o?.optString("title").orEmpty().ifBlank { task.take(80) })
                    .also { commitMsg = it }
            }
            val commit = cm.first; val title = cm.second
            ev(Agent.GIT, if (g.prUrl.isBlank()) "branch पर push — PR अभी नहीं, पहले build/tests" else "उसी PR की branch पर push, PR का चेक…")
            val tb = clock.start()
            val r = g.pushAndTest(commit, title, changes.entries.joinToString("\n") { "• ${it.key} — ${it.value}" }, rootCause) { ev(Agent.TESTER, it) }
            ev(Agent.TESTER, "⏱ push + build/tests: ${CoderSpeed.mins(clock.built(tb))}")
            // असली CI fail = यह हालत याद (अगले सुधार में "सीमा ढीली करके पास" पकड़ने को); पास = भूल जाओ
            when (r.ok) { false -> { failBase = HashMap(staged); failLogCi = r.log }; true -> { failBase = null; failLogCi = "" }; null -> {} }
            if (r.ok == null && r.log == NO_CI) {
                noCi = true
                ev(Agent.TESTER, "इस रिपो में GitHub Actions का कोई test/build नहीं — syntax जाँच + ड्राई-रन से जाँच रहा हूँ")
                return dryRun(staged, "रिपो में CI नहीं: syntax जाँच + ड्राई-रन")
            }
            return r
        }
        return dryRun(staged, "syntax जाँच + ड्राई-रन (फ़ोन पर build नहीं चलता)")
    }

    /**
     * push से पहले की पक्की जाँच (AI के बिना): syntax, पुराने tests हटे/कमज़ोर नहीं, सुरक्षित फ़ाइलें, UI text, गायब resource/view.
     * टेस्टर ([test]) और एक-दिमाग वाले कोडर की CHECK/DONE ([soloWork]) - दोनों यही इस्तेमाल करते हैं.
     */
    private suspend fun staticCheck(staged: Map<String, String?>): List<String> {
        val stat = mutableListOf<String>()
        // पहले पक्की जाँच: हर बदली फ़ाइल की syntax + पुराने tests हटे/कमज़ोर तो नहीं
        staged.forEach { (p, c) -> if (c != null) Syntax.check(p, c)?.let { stat.add("$p: $it") } }
        val orig = staged.keys.associateWith { files.original(it) }
        stat.addAll(SafePr.testGuard(staged, orig, SafePr.removesOldFeature(task)))
        // नवंबर 2026: पुराने test का assertion बदलना / test fail होने पर सीमा ढीली करना (PR #249) = रोक, code ठीक करो
        stat.addAll(SafePr.oldAssertsChanged(staged, orig, SafePr.removesOldFeature(task)))
        failBase?.let { stat.addAll(SafePr.goalposts(it, staged, task)) }
        // audit K2: आख़िरी पहरा - कोई सुरक्षित फ़ाइल किसी भी रास्ते से बदली हो (और माँगी न गई हो) तो push नहीं
        SafePr.blockedIn(staged.keys, task).forEach { stat.add("$it: PROTECTED FILE changed but the task did not ask for it — revert this file (AGENTS.md)") }
        stat.addAll(uiTextCheck(staged))
        stat.addAll(prAudit(staged))
        return stat
    }

    /** फ़ोन पर build नहीं चलता: पक्की जाँच (हो चुकी) + टेस्टर का कोड को मन में चलाकर देखना */
    private suspend fun dryRun(staged: Map<String, String?>, note: String): TeamTest {
        val body = staged.entries.joinToString("\n\n") { (p, c) -> "----- $p -----\n" + (c ?: "(deleted)").take(fileChars) }
        val o = askJson(Agent.TESTER, "TASK: $task\nACCEPTANCE:\n" + acceptance.joinToString("\n") + "\nCHANGED FILES:\n$body")
            // audit K3: टेस्टर ने (2 बार माँगने पर भी) जवाब नहीं दिया = टेस्ट हुआ ही नहीं - "पास" नहीं मानना
            ?: return TeamTest(null, "", note = DRY_RUN_MISSING)
        val cases = Seven.strings(o.optJSONArray("cases"))
        if (cases.isNotEmpty()) ev(Agent.TESTER, "जाँचे गए हालात:\n" + cases.take(6).joinToString("\n") { "• $it" })
        return if (o.optBoolean("pass", false)) TeamTest(true, "", note = note)
        else TeamTest(false, "DRY-RUN TEST FAILED: " + o.optString("error").ifBlank { "tester found a failing case" })
    }

    // ------------------------------------------------------------------ परफॉरमेंस एजेंट
    private suspend fun perf() {
        val staged = files.staged()
        if (staged.isEmpty()) return
        ev(Agent.PERF, "बदले code में UI जाम / leak / बेवजह का काम देख रहा हूँ…")
        val hard = mutableListOf<String>()
        val olds = HashMap<String, String?>()
        staged.forEach { (p, c) -> olds[p] = files.original(p); if (c != null) hard.addAll(SevenPlus.Perf.scan(p, olds[p], c)) }
        // नवंबर 2026 (तेज़): छोटा बदलाव + पक्की perf-जाँच साफ़ = अलग AI कॉल नहीं (QA/build वैसे ही चलते हैं)
        if (fast && hard.isEmpty() && CoderSpeed.perfQuick(staged.map { (p, c) -> Triple(p, olds[p], c) })) {
            clock.reviewSkipped(); ev(Agent.PERF, "✓ छोटा बदलाव, पक्की परफॉरमेंस जाँच साफ़ — अलग AI जाँच नहीं (तेज़)"); return
        }
        val o = askJson(Agent.PERF, "TASK: $task\nCHANGED CODE (only new/changed lines matter):\n" + diffText(staged, maxOf(30_000, big / 4)))
        val must = (hard + Seven.strings(o?.optJSONArray("must"))).distinct()
        perfNotes.addAll(Seven.strings(o?.optJSONArray("should")).take(5))
        if (must.isEmpty()) { ev(Agent.PERF, "✓ परफॉरमेंस ठीक" + if (perfNotes.isNotEmpty()) " (छोटे सुझाव रिपोर्ट में)" else ""); return }
        ev(Agent.PERF, "✗ ठीक करना ज़रूरी:\n" + must.joinToString("\n") { "• ${it.take(200)}" })
        val fix = makePlan("PERFORMANCE FIX - keep the behaviour exactly the same, change only this:\n" + must.joinToString("\n") { "- $it" }, staged.keys.toList())
        if (fix == null || !execute(fix, emptySet())) {
            perfNotes.addAll(0, must.map { "(ठीक नहीं हो पाया) $it" })
            ev(Agent.PERF, "⚠ परफॉरमेंस सुधार लागू नहीं हुआ — रिपोर्ट में लिखा, बाकी काम जारी")
            return
        }
        val left = mutableListOf<String>()
        files.staged().forEach { (p, c) -> if (c != null) left.addAll(SevenPlus.Perf.scan(p, files.original(p), c)) }
        if (left.isEmpty()) ev(Agent.PERF, "✓ परफॉरमेंस सुधार लागू") else { perfNotes.addAll(0, left); ev(Agent.PERF, "⚠ कुछ बाकी — रिपोर्ट में") }
    }

    // ------------------------------------------------------------------ QA एजेंट
    /** @return कमियाँ (खाली = काम पूरा और सही) */
    private suspend fun qa(): List<String> {
        val staged = files.staged()
        ev(Agent.QA, "build पास — अब जाँच रहा हूँ कि हर शर्त सच में पूरी हुई, कुछ नकली/अधूरा तो नहीं, पुराने फ़ीचर सलामत हैं…")
        val hard = mutableListOf<String>()
        staged.forEach { (p, c) -> if (c != null) hard.addAll(SevenPlus.Qa.scan(p, files.original(p), c)) }
        val o = askJson(Agent.QA, "USER REQUEST:\n$task\n" + (if (spec.isNotBlank()) "SPEC (approved by the user):\n$spec\n" else if (brief.isNotBlank()) "BRIEF: $brief\n" else "") +
            "ACCEPTANCE:\n" + acceptance.joinToString("\n") { "- $it" } + "\n" +
            (if (notTouch.isNotEmpty()) "MUST STAY UNCHANGED:\n" + notTouch.joinToString("\n") { "- $it" } + "\n" else "") +
            "DIFF:\n" + diffText(staged, maxOf(60_000, big / 4)) + "\n\nCHANGED FILES NOW:\n" +
            staged.entries.joinToString("\n\n") { (p, c) -> "----- $p -----\n" + (c ?: "(deleted)").take(maxOf(30_000, big / 6)) }.take(maxOf(90_000, big / 3)))
        qaChecks = Seven.strings(o?.optJSONArray("checks"))
        qaFix = o?.optString("fix").orEmpty().trim()
        if (qaChecks.isNotEmpty()) ev(Agent.QA, qaChecks.take(8).joinToString("\n") { "• $it" })
        val miss = hard.toMutableList()
        if (o != null && !o.optBoolean("pass", false)) miss.addAll(Seven.strings(o.optJSONArray("missing")).ifEmpty { listOf("QA ने काम अधूरा माना (वजह नहीं बताई)") })
        if (o == null && hard.isEmpty()) ev(Agent.QA, "AI QA का जवाब नहीं मिला — पक्की जाँच (अधूरा/नकली code नहीं) पास")
        if (miss.isEmpty()) ev(Agent.QA, "✓ QA पास — काम पूरा और सही") else ev(Agent.QA, "✗ कमी:\n" + miss.joinToString("\n") { "• ${it.take(200)}" })
        return miss.distinct()
    }

    /** हर बदली फ़ाइल: हटाई / जोड़ी लाइनें */
    private suspend fun diffText(staged: Map<String, String?>, max: Int): String = buildString {
        // v4.4: हटाई/आधी फ़ाइलें सबसे ऊपर, और हर फ़ाइल को बराबर हिस्सा - पहले एक हटाई फ़ाइल की लाइनें पूरी जगह खा जाती थीं,
        // बाकी हटाई फ़ाइलें (Prefs.kt, SettingsActivity.kt) डिबगर को दिखती ही नहीं थीं
        val rows = staged.entries.map { (p, c) -> Triple(p, c, files.original(p)) }
            .sortedByDescending { (_, c, b) -> if (b != null && c == null) 2 else if (CodeGuard.shrunk(b, c)) 1 else 0 }
        val per = (max / rows.size.coerceAtLeast(1)).coerceAtLeast(1_500)
        for ((p, c, before) in rows) {
            val start = length
            append("=== ").append(p).append(when {
                before == null -> " (NEW)"
                c == null -> " (DELETED - whole file, ${CodeGuard.lines(before)} lines were removed)"
                CodeGuard.shrunk(before, c) -> " (MOSTLY EMPTIED: ${CodeGuard.lines(before)} -> ${CodeGuard.lines(c)} lines)"
                else -> ""
            }).append(" ===\n")
            val b = before?.lines().orEmpty(); val a = c?.lines().orEmpty()
            val bs = b.toHashSet(); val asSet = a.toHashSet()
            if (c != null || before == null) {
                val fl = CodeGuard.flips(before, c)
                fl.takeIf { it.isNotEmpty() }?.let { f -> append("!! CONDITION FLIPPED (bug unless the task asks for it):\n"); f.forEach { append("!!   ").append(it).append('\n') } }
                CodeGuard.droppedGuards(before, c).filter { g -> fl.none { it.contains(g) } }.takeIf { it.isNotEmpty() }?.let { d ->
                    append("!! SAFETY / CONDITION REMOVED (check, retry, fallback or catch that no longer exists - a regression unless the task asks to remove it):\n")
                    d.forEach { append("!!   ").append(it).append('\n') }
                }
                b.filter { it !in asSet && it.isNotBlank() }.take(1500).forEach { append("- ").append(it).append('\n') }
                a.filter { it !in bs && it.isNotBlank() }.take(3000).forEach { append("+ ").append(it).append('\n') }
            } else b.filter { it.isNotBlank() }.take(15).forEach { append("- ").append(it).append('\n') }
            if (length - start > per) { setLength(start + per); append("\n…(more lines of $p - read the file)\n") }
            if (length > max) break
        }
    }.take(max)

    private var wsCache: Map<String, String>? = null
    /** पूरा workspace (खोज/नक्शे के लिए) - इस काम के बदलाव समेत */
    private suspend fun ws(): Map<String, String> {
        val base = wsCache ?: (try { workspace() } catch (_: Exception) { emptyMap() }).also { wsCache = it }
        val st = files.staged()
        if (st.isEmpty()) return base
        return HashMap(base).apply { st.forEach { (p, c) -> if (c == null) remove(p) else put(p, c) } }
    }

    // ------------------------------------------------------------------ नाम-जाँच (फ़ाइल पढ़कर, अंदाज़े से नहीं)
    private suspend fun workspace(): Map<String, String> = files.all() ?: run {
        val m = LinkedHashMap<String, String>()
        for (p in files.list().take(listMax)) files.read(p)?.let { m[p] = it }
        m
    }

    private suspend fun names(path: String, before: String?, after: String): List<SevenSymbols.Miss> = try {
        val ws = HashMap(workspace()); ws[path] = after
        SevenSymbols.check(path, before, after, ws, files.whole, ciProven)
    } catch (e: Exception) { emptyList() }

    /**
     * नवंबर 2026 (feature/dk-user-correction-follow-this-1110-1139, 4 CI राउंड): compiler का "Unresolved reference: X" सबूत याद रखो.
     * उस समय की बदली फ़ाइलों में X किस repo-class पर बुलाया गया था -> उस class पर अगला अनजाना नाम भी पक्का रुकेगा
     * (पहले AI रिव्यूअर "Android parent class में होगा" मानकर पास कर देता था).
     */
    private suspend fun learnFromCi(log: String) {
        try {
            val staged = files.staged().filterValues { it != null }.mapValues { it.value!! }
            if (staged.isEmpty()) return
            val p = SevenSymbols.proven(log, staged, workspace())
            if (p.isEmpty) return
            ciProven = ciProven.plus(p)
            memory.found("Compiler proved these names do NOT exist (never use them): ${p.names.take(12).joinToString()}" +
                if (p.types.isNotEmpty()) " (on ${p.types.joinToString()})" else "")
            ev(Agent.REVIEWER, "CI ने ये नाम गढ़े हुए पकड़े: ${p.names.take(8).joinToString()}" +
                (if (p.types.isNotEmpty()) " — अब ${p.types.joinToString()} पर कोई भी अनजाना नाम push से पहले ही रुकेगा" else ""))
        } catch (_: Exception) {}
    }

    private suspend fun readMap(paths: Collection<String>): Map<String, String?> {
        val m = HashMap<String, String?>()
        for (p in paths) m[p] = try { files.read(p) } catch (_: Exception) { null }
        return m
    }

    private suspend fun badUses(plan: Seven.Plan): List<String> {
        val uses = plan.steps.flatMap { it.uses }.distinct()
        if (uses.isEmpty()) return emptyList()
        val m = readMap(uses.map { it.substringBefore(':').trim() }.distinct())
        return SevenSymbols.badUses(uses) { m[it] }
    }

    private suspend fun definitions(s: Seven.Step): String {
        val m = readMap(s.uses.map { it.substringBefore(':').trim() }.distinct())
        val used = if (s.uses.isEmpty()) "" else SevenSymbols.signatures(s.uses) { m[it] }
        val api = relatedApi(s.file, (files.read(s.file).orEmpty()) + "\n" + s.change + "\n" + s.uses.joinToString("\n"))
        return listOf(used, if (api.isBlank()) "" else "REPO CLASSES THIS FILE / STEP USES (real constructors and public members read from the files - use exactly these; a name not listed is not declared there, only an Android/SDK parent class could add it - if unsure, read the file):\n$api")
            .filter { it.isNotBlank() }.joinToString("\n")
    }

    /** नवंबर 2026: इस फ़ाइल का code जिन repo classes को छूता है, उनकी असली API (constructor + public members) */
    private suspend fun relatedApi(path: String, code: String): String = try {
        SevenSymbols.apiOf(path, code, workspace(), maxOf(12_000, big / 25))
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "" }

    /**
     * नवंबर 2026 (PR #252): layout या ui_en.tsv बदला हो तो UiTextTest वाली जाँच push से पहले यहीं (बिना CI, सेकंडों में).
     * पहले हर गायब शब्द के लिए ~10 मिनट का CI राउंड लगता था और हर राउंड में बस एक शब्द पता चलता था.
     * जिस रिपो में assets/ui_en.tsv नहीं, वहाँ कुछ नहीं.
     */
    private suspend fun uiTextCheck(staged: Map<String, String?>): List<String> {
        val touched = staged.keys.any { (it.contains("/res/layout/") && it.endsWith(".xml")) || it.endsWith("assets/ui_en.tsv") }
        if (!touched) return emptyList()
        return try {
            val list = files.list()
            val tsvPath = list.firstOrNull { it.endsWith("src/main/assets/ui_en.tsv") } ?: return emptyList()
            val tsv = files.read(tsvPath) ?: return emptyList()
            val layouts = LinkedHashMap<String, String>()
            for (p in list) if (p.contains("/main/res/layout/") && p.endsWith(".xml")) files.read(p)?.let { layouts[p] = it }
            val miss = UiText.missing(layouts, tsv)
            if (miss.isEmpty()) emptyList()
            else listOf("$tsvPath: ${miss.size} layout text का English नहीं (UiTextTest fail होगा). हर एक के लिए नई लाइन \"हिंदी<TAB>English\" जोड़ें " +
                "(पहले से मौजूद शब्द दोबारा न जोड़ें, layout का text न बदलें):\n" + miss.take(40).joinToString("\n"))
        } catch (_: Exception) { emptyList() }
    }

    /**
     * नवंबर 2026 (PR #252): पूरे बदलाव की पक्की जाँच (PrAudit) - दोहराए शब्द, गायब किए पुराने बटन, बेजान नए बटन,
     * बेकार layout/raw/drawable/dependency. पहले reviewer हर step अलग देखता था, ये पूरे PR को main के मुकाबले देखता है.
     */
    private suspend fun prAudit(staged: Map<String, String?>): List<String> = try {
        val base = files.all()
        val all = base?.let { HashMap(it).apply { staged.forEach { (p, c) -> if (c == null) remove(p) else put(p, c) } } }
        val low = task.lowercase()
        val hide = SafePr.removesOldFeature(task) || listOf("छिपा", "छुपा", "hide", "gone", "chhupa", "chupa").any { low.contains(it) }
        val orig = HashMap<String, String?>(); for (p in staged.keys) orig[p] = files.original(p)
        val audit = PrAudit.audit(staged, { p -> orig[p] }, all, hide)
        // नवंबर 2026: इस्तेमाल किया resource / custom View सच में है? नई फ़ाइल कहीं इस्तेमाल? बिन माँगी नई library?
        val refs = if (all == null || !files.whole) emptyList() else {
            val skipped = try { files.skippedPaths() } catch (_: Exception) { emptySet() }
            RefCheck.missingResources(staged, { p -> orig[p] }, all, skipped, hint = true) + RefCheck.missingViews(staged, { p -> orig[p] }, all) +
                RefCheck.deadNewCode(staged, { p -> orig[p] }, all)
        }
        (audit + refs + RefCheck.unaskedDependencies(staged, { p -> orig[p] }, task)).distinct()
    } catch (_: Exception) { emptyList() }

    /** CI log की गलती = रिपो में न मौजूद resource? (सिर्फ़ GitHub मोड, पूरा रिपो हो तब) */
    private suspend fun resourceNote(log: String): String = try {
        if (!files.whole) "" else RefCheck.ciMissing(log, ws(), try { files.skippedPaths() } catch (_: Exception) { emptySet() })
    } catch (_: Exception) { "" }

    /** log से असली एरर लाइनें (Kotlin "e: file:line", error:, FAILED, Exception). नवंबर 2026: path रिपो वाले (CI/Codespace के नहीं) */
    private fun errorLines(log: String): String = log.lines().map { BuildLog.clean(it).trim() }.filter { l ->
        l.startsWith("e: ") || l.contains("error:", true) || l.contains("Unresolved reference") || l.contains("FAILED") ||
            l.contains("AssertionError") || l.contains("Exception:") || l.startsWith("STATIC CHECK") || l.startsWith("DRY-RUN") || l.startsWith("AUDIT ")
    }.distinct().take(8).joinToString("\n") { it.take(240) }

    // ------------------------------------------------------------------ मदद
    private suspend fun askJson(a: Agent, user: String): JSONObject? {
        val msgs = mutableListOf("user" to user)
        for (t in 0..1) {
            val reply = aiAsk(a, Seven.Prompts.of(a), msgs)
            Seven.json(reply)?.let { return it }
            msgs.add("model" to reply); msgs.add("user" to "Reply ONLY valid JSON in the required format.")
        }
        return null
    }

    /** रुकना पड़ा (वही गलती 3 बार / 8 राउंड): क्या-क्या आज़माया + शक कहाँ है */
    private fun stuckNote(last: String): String = "\nक्या-क्या आज़माया:\n" +
        (tried.takeLast(10).ifEmpty { listOf("कोडर → रिव्यूअर → टेस्टर → डिबगर चक्र") }).joinToString("\n") { "• $it" } +
        "\nशक: जड़ शायद वह नहीं जो अब तक मानी गई (या ज़रूरी code/फ़ाइल रिपो में नहीं) — एरर: ${last.take(200)}"

    private suspend fun finish(testOk: Boolean?, problem: String, pr: String = git?.prUrl.orEmpty()): Outcome {
        if (problem.isNotBlank()) ev(Agent.MANAGER, (if (testOk == null) "⚠ " else "✗ ") + problem)
        sink(Agent.MANAGER, clock.report())   // नवंबर 2026: समय कहाँ गया (अगली बार धीमेपन की वजह साफ़)
        when (testOk) {
            true -> note.state("✅ काम पूरा — build/tests पास, PR review के लिए तैयार", "merge सिर्फ़ आपकी हाँ से (\"हाँ, merge करो\")")
            false -> note.state("❌ रुका: " + problem.take(160), "\"आगे करो\" — यही काम-कॉपी पढ़कर वहीं से")
            null -> note.state("⏳ अधूरा: " + problem.take(160), "checks/नतीजा पूरा होने पर \"आगे करो\"")
        }
        pushNote()
        val lt = lastTest
        val chk = when {
            git == null -> null
            noCi -> Seven.CheckState.NONE
            testOk == true -> Seven.CheckState.PASS
            lt == null -> Seven.CheckState.NONE
            lt.ok == false -> Seven.CheckState.FAIL
            else -> Seven.CheckState.RUNNING
        }
        var rep = Seven.report(task, mode, changes.entries.map { it.key to it.value }, bugs, testOk,
            if (testOk == true) passNote else problem.take(300), pr, backup, chk, lt?.url.orEmpty(),
            if (changes.isEmpty() && pr.isBlank()) "कोई code नहीं बदला, इसलिए PR नहीं बना" else git?.prNote.orEmpty())
        if (full) {
            if (qaChecks.isNotEmpty()) rep += "\n\n✅ QA की जाँच (टेस्टर):\n" + qaChecks.take(8).joinToString("\n") { "• $it" }
            if (perfNotes.isNotEmpty()) rep += "\n\n⚡ परफॉरमेंस के सुझाव (रिव्यूअर, ज़रूरी नहीं):\n" + perfNotes.take(5).joinToString("\n") { "• $it" }
            if (testOk == true && git != null) rep += "\n\n🚀 डिप्लॉयमेंट (गिट/रिलीज़): \"हाँ, merge करो\" / \"Merge करें\" कहने पर ही — PR दोबारा जाँच → merge → APK build → " +
                "release में APK सच में है, यह पक्का करके version बताऊँगा। आपकी हाँ के बिना कुछ merge या release नहीं होगा।"
        }
        if (solo && soloSummary.isNotBlank()) rep += "\n\n🧠 कोडर ने क्या किया:\n" + soloSummary.take(1_200)
        if (testOk != true && problem.isNotBlank()) rep += "\n\n• रुकने की वजह: $problem" +
            if (mode == Seven.Mode.GITHUB) "\n• main अछूता है${if (backup.isNotBlank()) ", backup: $backup" else ""}। आगे क्या करूँ, बताइए।" else ""
        // यूज़र को सिर्फ़ छोटी रिपोर्ट: हुआ/नहीं, API चली/नहीं, वजह, हल. पूरी रिपोर्ट (ऊपर) डिबग लॉग के लिए
        val why = if (testOk == true) "" else problem.ifBlank { lt?.note.orEmpty() }.ifBlank { lt?.log.orEmpty() }
        val short = ShortReport.text(testOk, why, pr.substringBefore(" ("), git != null)
        // v5.2: काम ख़त्म → मैनेजर समझाने वाले से नतीजा बतवाता है (असली नतीजे से, बिना AI - कुछ बनाया हुआ नहीं)
        val told = Seven.resultExplain(testOk, changes.entries.map { it.key to it.value }, bugs, problem, lt?.note.orEmpty(),
            tried, pr.substringBefore(" ("), git != null)
        ev(Agent.MANAGER, "काम ख़त्म → ${Seven.Member.EXPLAINER.label} नतीजा बताएगा")
        // नवंबर 2026 (मालिक): "समझाने वाला सिर्फ़ बदली फ़ाइल बताता है" - अब असली diff पढ़कर: क्या/क्यों बदला, कैसे चलेगा, बग कहाँ था, कैसे जाँचें
        val deep = if (aiResult && (changes.isNotEmpty() || problem.isNotBlank())) explainResult(testOk, problem, lt?.note.orEmpty()) else null
        val shown = if (deep != null) "${Seven.Member.EXPLAINER.icon} ${Seven.Member.EXPLAINER.hi} — क्या हुआ, कैसे हुआ, आसान भाषा में:\n$deep\n\n$told" else told
        ev(Agent.EXPLAINER, shown)
        rep += "\n\n" + shown
        val brief = MergeBrief.text(short, task, changes.entries.map { it.key to it.value }, deep.orEmpty(), told.substringAfter('\n', told),
            testOk, pr.substringBefore(" ("), git != null, if (testOk == true) passNote else "", backup)
        return Outcome(rep, files.staged(), testOk, mode, short = short, brief = brief)
    }
}

/** नॉर्मल मोड की फ़ाइलें: user के भेजे कोड (मूल) + इस काम में बदली/नई (फ़ोन की memory में) */
class MemoryFiles(initial: Map<String, String>) : TeamFiles {
    private val orig = LinkedHashMap(initial)
    private val stagedMap = linkedMapOf<String, String?>()
    override suspend fun list() = (orig.keys + stagedMap.filterValues { it != null }.keys).distinct().filter { stagedMap[it] != null || it !in stagedMap }
    override suspend fun read(path: String) = if (path in stagedMap) stagedMap[path] else orig[path]
    override suspend fun original(path: String) = orig[path]
    override suspend fun stage(path: String, content: String?) { stagedMap[path] = content }
    override fun staged(): Map<String, String?> = LinkedHashMap(stagedMap)
    override suspend fun all(): Map<String, String> = LinkedHashMap<String, String>().apply {
        putAll(orig); stagedMap.forEach { (p, c) -> if (c == null) remove(p) else put(p, c) }
    }
}
