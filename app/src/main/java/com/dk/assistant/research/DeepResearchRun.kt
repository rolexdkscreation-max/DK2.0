package com.dk.assistant.research

import android.content.Context
import com.dk.assistant.AiException
import com.dk.assistant.BrainManager
import com.dk.assistant.DKLog
import com.dk.assistant.GeminiClient
import com.dk.assistant.Prefs
import com.dk.assistant.research.DeepResearch.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 14 नवंबर 2026 (मालिक): डीप रिसर्च चलाने वाला. [step] = चैट में अभी क्या हो रहा ("Exa, Tavily, Linkup पर खोज रहा हूँ…").
 * कोई टूल error / 429 / क्रेडिट खत्म = इस रिसर्च में छोड़ो, यूज़र को रोको मत.
 * चारों न चलें = Gemini की अपनी Google खोज, साफ़ लिखकर कि गहरी खोज नहीं हो पाई.
 */
class DeepResearchRun(private val c: Context, private val sites: (List<String>) -> Unit = {}, private val step: (String) -> Unit) {
    class Result(val text: String, val sources: List<Pair<String, String>>, val deep: Boolean)

    private val dead = ConcurrentHashMap<Tool, String>()
    private fun alive(t: Tool, key: String) = key.isNotBlank() && !dead.containsKey(t)

    private fun kill(t: Tool, e: Exception) {
        val code = (e as? AiException)?.code ?: 0
        if (!dead.containsKey(t)) {
            dead[t] = DeepResearch.failNote(t, code)
            DKLog.d("डीप रिसर्च: ${t.label} छोड़ा ($code)")
        }
    }

    private val exaKey = Prefs.exaSearchKey(c)
    private val tavilyKey = Prefs.tavilySearchKey(c)
    private val linkupKey = Prefs.linkupKey(c)
    private val fireKey = Prefs.firecrawlKey(c)
    // मालिक: "जो भी key डाली है सब पर fallback" - खोज वाली keys के बाद AI दिमाग में कहीं भी डली Gemini keys
    private val geminiKeys: List<String> = (BrainManager(c).searchKeys() + Prefs.key(c, "gemini")).filter { it.isNotBlank() }.distinct()
        .let { it + com.dk.assistant.BrainPin.spareGemini(c, it) }

    /**
     * Gemini से सीधा लंबा जवाब (keys/models बदल-बदलकर); कोई न चले = null.
     * 14 नवंबर 2026 (मालिक: "बहुत देर लगाता है"): पहले हर key पर 3.8 Flash दो बार + हर बार 45 सेकंड तक इंतज़ार.
     * अब [fast] काम (सवाल तोड़ना / कमी देखना) = हल्का मॉडल, कम सोच, छोटी समय-सीमा; हर काम की कुल समय-सीमा [limitMs].
     */
    private fun gemini(prompt: String, maxTokens: Int, json: Boolean, fast: Boolean = false, limitMs: Long = 120_000): String? {
        val models = com.dk.assistant.OneKeyLanes.order(com.dk.assistant.OneKeyLanes.Lane.HEAVY, (if (fast) GeminiClient.BACKUP_MODELS else GeminiClient.DEEP_MODELS + GeminiClient.BACKUP_MODELS.reversed()).distinct())   // 15 नवंबर 2026: एक key में भारी लेन
            .let { if (fast) it else com.dk.assistant.dkai.SelectedModel.geminiOrder(c, "डीप रिसर्च का जवाब", it) }   // 20 नवंबर 2026: चिप का Gemini पहले
        val until = System.currentTimeMillis() + limitMs
        for (k in geminiKeys) for (m in models) {
            if (System.currentTimeMillis() > until) return null
            try { return DeepResearchNet.gemini(k, m, prompt, maxTokens, json, fast) }
            catch (e: AiException) { if (!fast) com.dk.assistant.dkai.SelectedModel.failedIf(c, m, e); if (e.code == 401 || e.code == 403) break }
            catch (e: Exception) { if (!fast) com.dk.assistant.dkai.SelectedModel.failedIf(c, m, e) }
        }
        return null
    }

    /** ऐप में अपनी खोज key नहीं, DK सर्वर से खोज/पढ़ाई */
    @Volatile private var server = false
    private val SERVER = "@dk-server"

    /** ताज़ा सवाल = इस तारीख़ ("yyyy-MM-dd") से नए पेज; खाली = कोई सीमा नहीं */
    @Volatile private var since = ""

    suspend fun run(question: String): Result = withContext(Dispatchers.IO) {
        val topic = DeepResearch.topic(question)
        val nowYear = Calendar.getInstance().get(Calendar.YEAR)
        val today = SimpleDateFormat("d MMMM yyyy", Locale("hi", "IN")).format(Date())
        var searchers = listOf(Tool.EXA to exaKey, Tool.TAVILY to tavilyKey, Tool.LINKUP to linkupKey).filter { it.second.isNotBlank() }
        // 18 नवंबर 2026 (मालिक): ऐप में अपनी Exa / Tavily key नहीं = पहले DK सर्वर (Worker) की keys; वहाँ भी नहीं = सामान्य जवाब
        server = searchers.isEmpty() && com.dk.assistant.AiRouter.backendReady() && com.dk.assistant.AiRouter.serverAiOk()
        if (server) { searchers = DeepResearch.SERVER_TOOLS.map { it to SERVER }; DKLog.d("डीप रिसर्च: अपनी खोज key नहीं - DK सर्वर से") }

        if (searchers.isEmpty()) return@withContext shallow(question, "खोज की कोई key नहीं (Exa / Tavily / Linkup)")

        // 1. सवाल तोड़ो
        step("सवाल को छोटे हिस्सों में तोड़ रहा हूँ…")
        // 14 नवंबर 2026 (मालिक: "आज / कल की तारीख़ से रिसर्च करे"): आज की तारीख़ AI को, ताज़ा सवाल पर खोज में तारीख़ की सीमा
        val todayEn = SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH).format(Date())
        since = if (DeepResearch.fresh(question)) SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(Date(System.currentTimeMillis() - 45L * 86_400_000L)) else ""
        var subs = gemini(DeepResearch.planPrompt(topic, todayEn), 600, json = true, fast = true, limitMs = 25_000)?.let { DeepResearch.parsePlan(it, topic) } ?: DeepResearch.fallbackSubs(topic)

        val pages = ArrayList<DeepResearch.Page>()
        val seen = HashSet<String>()
        for (round in 1..DeepResearch.MAX_ROUNDS) {
            val budget = DeepResearch.MAX_PAGES - pages.size
            if (budget <= 0 || subs.isEmpty()) break
            // 2. एक साथ खोज (हर टूल को अलग शब्द)
            val names = searchers.filter { alive(it.first, it.second) }.joinToString(", ") { it.first.label }
            if (names.isEmpty()) break
            step(if (round == 1) "$names पर एक साथ खोज रहा हूँ…" else "कमी पूरी करने के लिए फिर खोज रहा हूँ (चक्कर $round/${DeepResearch.MAX_ROUNDS})…")
            val hits = search(subs)
            if (hits.isEmpty()) break
            // 14 नवंबर 2026: खोज में मिली असली वेबसाइटें - चैट के लाइव कार्ड में
            sites(hits.map { it.url })
            // 3. duplicate हटाओ, चुनो, पूरा पढ़ो
            val links = DeepResearch.merge(hits)
            val want = if (round == 1) DeepResearch.MAX_PAGES.coerceAtMost(budget).coerceAtLeast(minOf(DeepResearch.MIN_PICK, budget)) else minOf(5, budget)
            val chosen = DeepResearch.pick(links, seen, budget, nowYear, want)
            if (chosen.isEmpty()) break
            chosen.forEach { seen.add(DeepResearch.normalize(it.url)) }
            step("${chosen.size} वेबसाइटें पूरी पढ़ रहा हूँ: " + chosen.take(4).joinToString(", ") { DeepResearch.domain(it.url) } + if (chosen.size > 4) "…" else "")
            pages.addAll(read(chosen))
            // 5. कमी हो तो फिर (ज़्यादा से ज़्यादा 3 चक्कर)
            if (round == DeepResearch.MAX_ROUNDS || pages.size >= DeepResearch.MAX_PAGES) break
            // तेज़: काफ़ी पूरे पेज पढ़ लिए = दूसरा चक्कर नहीं (पहले हर बार कमी देखने + फिर खोजने में मिनट लगते थे)
            if (pages.count { it.full } >= DeepResearch.MIN_PICK) break
            step("जाँच रहा हूँ — कोई हिस्सा छूटा तो नहीं…")
            val gaps = gemini(DeepResearch.gapPrompt(topic, pages), 300, json = true, fast = true, limitMs = 20_000)?.let { DeepResearch.parseGaps(it) }.orEmpty()
            if (gaps.isEmpty()) break
            subs = gaps.map { DeepResearch.Kind.MAIN to it }
        }

        if (pages.isEmpty()) return@withContext shallow(question, dead.values.joinToString("; ").ifBlank { "कोई वेबसाइट नहीं मिली" })

        // 6. Gemini की हिंदी रिपोर्ट
        step("${pages.size} स्रोत मिलाकर हिंदी रिपोर्ट लिख रहा हूँ…")
        val prompt = DeepResearch.writerPrompt(question, pages, today, dead.values.toList())
        var report: String? = gemini(prompt, 4096, json = false)
        if (report == null) report = try { BrainManager(c).quickText("तुम DK AI हो। सिर्फ़ दिए गए स्रोतों से हिंदी रिपोर्ट लिखो।", prompt, com.dk.assistant.OneKeyLanes.Lane.HEAVY) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        if (report.isNullOrBlank()) return@withContext Result("रिपोर्ट लिखने वाला AI नहीं चला — नीचे पढ़े गए स्रोत हैं।\n\n" + DeepResearch.sourceList(pages) + "\n\nभरोसा: अधूरा",
                pages.map { it.title.ifBlank { DeepResearch.domain(it.url) } to it.url }, true)
        Result(DeepResearch.finish(report!!, pages), pages.map { it.title.ifBlank { DeepResearch.domain(it.url) } to it.url }, true)
    }

    /** हर उप-सवाल x हर ज़िंदा टूल - सब एक साथ */
    private suspend fun search(subs: List<Pair<DeepResearch.Kind, String>>): List<DeepResearch.Hit> = coroutineScope {
        val jobs = ArrayList<kotlinx.coroutines.Deferred<List<DeepResearch.Hit>>>()
        if (server) {
            for ((kind, q) in subs) for (t in DeepResearch.SERVER_TOOLS) if (alive(t, SERVER)) jobs.add(async {
                try { DeepResearch.parseServer(com.dk.assistant.backend.BackendApi.search(c, DeepResearch.phrase(t, kind, q), deep = true, only = DeepResearch.serverId(t)), t) }
                catch (e: Exception) { kill(t, e); emptyList() }
            })
            return@coroutineScope jobs.awaitAll().flatten()
        }
        for ((kind, q) in subs) {
            if (alive(Tool.EXA, exaKey)) jobs.add(async {
                try { DeepResearchNet.exa(exaKey, DeepResearch.phrase(Tool.EXA, kind, q), since = since) } catch (e: Exception) { kill(Tool.EXA, e); emptyList() }
            })
            if (alive(Tool.TAVILY, tavilyKey)) jobs.add(async {
                try { DeepResearchNet.tavily(tavilyKey, DeepResearch.phrase(Tool.TAVILY, kind, q), news = kind == DeepResearch.Kind.NEWS, since = since) } catch (e: Exception) { kill(Tool.TAVILY, e); emptyList() }
            })
            if (alive(Tool.LINKUP, linkupKey)) jobs.add(async {
                try { DeepResearchNet.linkup(linkupKey, DeepResearch.phrase(Tool.LINKUP, kind, q), since = since) } catch (e: Exception) { kill(Tool.LINKUP, e); emptyList() }
            })
        }
        jobs.awaitAll().flatten()
    }

    /** पढ़ने का क्रम: Firecrawl -> Tavily extract -> Exa contents -> (कुछ न चले) खोज का छोटा अंश */
    private suspend fun read(links: List<DeepResearch.Link>): List<DeepResearch.Page> = coroutineScope {
        val got = ConcurrentHashMap<String, DeepResearch.Page>()
        // 18 नवंबर 2026: सर्वर वाली खोज = पेज भी सर्वर से पढ़ो (Tavily extract / Exa contents / Firecrawl)
        if (server) try {
            links.chunked(8).forEach { part ->
                val m = com.dk.assistant.backend.BackendApi.read(c, part.map { it.url })
                for (l in part) m[l.url]?.let { got[DeepResearch.normalize(l.url)] = DeepResearch.Page(l.title, l.url, it, l.date, true) }
                step("पढ़ लिया: ${got.size}/${links.size} वेबसाइटें")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { DKLog.d("डीप रिसर्च: सर्वर से पेज नहीं पढ़े (${e.message?.take(60)})") }
        if (alive(Tool.FIRECRAWL, fireKey)) {
            val gate = Semaphore(8)   // तेज़: 8 पेज एक साथ (हर पेज की सीमा 22 सेकंड)
            links.map { l -> async {
                gate.withPermit {
                    if (!alive(Tool.FIRECRAWL, fireKey)) return@withPermit
                    try {
                        DeepResearchNet.firecrawl(fireKey, l.url)?.let { (t, md, d) ->
                            got[DeepResearch.normalize(l.url)] = DeepResearch.Page(t.ifBlank { l.title }, l.url, md, l.date.ifBlank { d }, true)
                            step("पढ़ लिया: ${DeepResearch.domain(l.url)} (${got.size}/${links.size})")
                        }
                    } catch (e: Exception) { val code = (e as? AiException)?.code ?: 0; if (code == 401 || code == 402 || code == 403 || code == 429) kill(Tool.FIRECRAWL, e) }
                }
            } }.awaitAll()
        }
        var left = links.filter { !got.containsKey(DeepResearch.normalize(it.url)) }
        if (left.isNotEmpty() && alive(Tool.TAVILY, tavilyKey)) {
            try {
                val m = DeepResearchNet.tavilyExtract(tavilyKey, left.map { it.url })
                for (l in left) m[DeepResearch.normalize(l.url)]?.let { got[DeepResearch.normalize(l.url)] = DeepResearch.Page(l.title, l.url, it, l.date, true) }
            } catch (e: Exception) { kill(Tool.TAVILY, e) }
            left = links.filter { !got.containsKey(DeepResearch.normalize(it.url)) }
        }
        if (left.isNotEmpty() && alive(Tool.EXA, exaKey)) {
            try {
                val m = DeepResearchNet.exaContents(exaKey, left.map { it.url })
                for (l in left) m[DeepResearch.normalize(l.url)]?.let { got[DeepResearch.normalize(l.url)] = DeepResearch.Page(l.title, l.url, it, l.date, true) }
            } catch (e: Exception) { kill(Tool.EXA, e) }
            left = links.filter { !got.containsKey(DeepResearch.normalize(it.url)) }
        }
        // पूरा पेज न खुले तो छोटा अंश (रिपोर्ट में साफ़ लिखा जाता है: "सिर्फ़ छोटा अंश")
        for (l in left) if (l.snippet.isNotBlank()) got[DeepResearch.normalize(l.url)] = DeepResearch.Page(l.title, l.url, l.snippet, l.date, false)
        links.mapNotNull { got[DeepResearch.normalize(it.url)] }
    }

    /**
     * चारों टूल फेल / key नहीं = सामान्य जवाब (DK की अपनी जानकारी से).
     * 18 नवंबर 2026 (मालिक: "Google डीप रिसर्च वाले नहीं देगा, हटा दो"): पहले यहाँ Gemini की Google Search थी - मुफ़्त key पर
     * वह 429 देती है, इसलिए अब बिना खोज वाला सादा Gemini (HYDROGEN पहले) / बाकी AI, साफ़ लिखकर कि गहरी खोज नहीं हो पाई.
     */
    private suspend fun shallow(question: String, why: String): Result {
        step("गहरी खोज नहीं हो पाई — अपनी जानकारी से जवाब लिख रहा हूँ…")
        DKLog.d("डीप रिसर्च: shallow ($why)")
        val ask = "तुम DK AI हो। हिंदी में अपनी जानकारी से पूरा जवाब दो: 1. सीधा जवाब 2. मुख्य बातें 3. अलग राय 4. भरोसा। " +
            "आज का भाव / स्कोर / ताज़ा आँकड़े मत गढ़ो - वहाँ साफ़ कहो कि अभी जाँच नहीं हो पाई।\n\nसवाल: " + DeepResearch.topic(question)
        var text = gemini(ask, 2048, json = false, limitMs = 60_000)
        if (text.isNullOrBlank()) text = try { BrainManager(c).quickText("तुम DK AI हो। हिंदी में जवाब दो।", ask, com.dk.assistant.OneKeyLanes.Lane.HEAVY) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { com.dk.assistant.Quiet.note(e); null }
        if (!text.isNullOrBlank()) return Result(text.trim() + "\n\n" + DeepResearch.SHALLOW_NOTE + " ($why)", emptyList(), false)
        return Result("गहरी खोज नहीं हो पाई ($why) और जवाब लिखने वाला AI भी अभी नहीं चला। Settings → AI दिमाग → \"गहरी खोज\" में Exa / Tavily / Linkup / Firecrawl की key डालें।\nभरोसा: अधूरा", emptyList(), false)
    }
}
