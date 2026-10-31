package com.dk.assistant.inspector

import org.json.JSONArray
import org.json.JSONObject

/**
 * DK Inspector - सिर्फ़ पढ़ने वाला (read-only) जाँचकर्ता।
 *
 * यह फ़ाइल pure Kotlin है (Android पर निर्भर नहीं) ताकि JVM unit test से पूरी जाँची जा सके।
 * Inspector मौजूदा Debug डेटा को ही पढ़ता है:
 *   - DKLog की लाइनें ("HH:mm:ss.SSS संदेश")
 *   - GeminiLive.stats वाली लाइन (जुड़ा/गिरे/मिक गैप/…)
 *   - LatencyBoard की पंक्तियाँ + GeminiLive की "देरी के हिस्से:" लॉग लाइन
 * कोई नया लॉग सिस्टम नहीं बनाता, app का source code कभी नहीं बदलता।
 */
object InspectorCore {

    // ------------------------------------------------------------------ प्रकार

    enum class Severity(val hi: String, val rank: Int) {
        CRITICAL("Critical (बहुत गंभीर)", 4), HIGH("High (गंभीर)", 3), MEDIUM("Medium (मध्यम)", 2), LOW("Low (हल्का)", 1)
    }

    /** पक्का बनाम शक - शक वाली वजह को कभी "पक्का" नहीं लिखा जाता */
    enum class Certainty(val hi: String) {
        CONFIRMED("Confirmed error (पक्की गलती - लॉग में सीधा लिखा है)"),
        SUSPECTED("Suspected cause (शक - सबूत अधूरा है)"),
        PERF("Performance warning (धीमापन)"),
        INFO("Informational (सिर्फ़ जानकारी)")
    }

    enum class Status(val hi: String) {
        NEW("New (नया)"), INVESTIGATING("Investigating (जाँच जारी)"), AWAITING_APPROVAL("Awaiting Approval (आपकी मंज़ूरी बाकी)"),
        FIXING("Fixing (Coding Agent ठीक कर रहा)"), RESOLVED("Resolved (हल - जाँच से पक्का)"), REOPENED("Reopened (फिर से आया)")
    }

    enum class Component(val hi: String) {
        LIVE_CONNECTION("Gemini Live कनेक्शन"), MICROPHONE("माइक / आवाज़ इनपुट"), AUDIO_OUTPUT("आवाज़ आउटपुट / playback"),
        TTS("TTS (बोलने वाली आवाज़)"), LIVE_RESPONSE("Gemini Live जवाब"), API("API / key / limit"),
        LATENCY("देरी (latency)"), CODING_AGENT("Coding Agent"), DK_COMPUTER("DK Computer"),
        CRASH("App crash / exception"), APP("App (दूसरा हिस्सा)")
    }

    const val NOT_AVAILABLE = "NOT AVAILABLE"

    data class Event(val idx: Int, val time: String, val msg: String)

    /** एक नियम: लॉग की लाइन किस गड़बड़ी की है */
    data class Rule(
        val id: String, val component: Component, val certainty: Certainty, val severity: Severity,
        val match: Regex, val title: String, val cause: String, val impact: String,
        val fix: String, val code: String, val test: String,
        /** true = हर अलग संदेश अलग bug (generic नियम); false = इस नियम की सारी लाइनें एक bug */
        val perMessage: Boolean = false,
    )

    /** एक जाँच में मिला नतीजा (अभी store में नहीं मिला) */
    data class Finding(
        val fingerprint: String, val rule: Rule, val count: Int, val firstTime: String, val lastTime: String,
        val message: String, val evidence: List<String>, val sequence: List<String>, val severity: Severity,
    )

    /** store में रखा हुआ bug (पूरे लॉग की नकल नहीं - बस कुछ छिपाई हुई लाइनें) */
    data class Incident(
        val id: String, val fingerprint: String, val ruleId: String, val title: String,
        val component: Component, val severity: Severity, val certainty: Certainty,
        val firstSeen: Long, val lastSeen: Long, val count: Int, val status: Status,
        val message: String, val evidence: List<String>, val sequence: List<String>,
        val cause: String, val impact: String, val fix: String, val code: String, val test: String,
        val notifiedAt: Long = 0, val notifiedSeverity: Int = 0,
        val reopenCount: Int = 0, val fixVersion: String = "", val quietChecks: Int = 0,
        val resolvedAt: Long = 0, val resolvedNote: String = "", val linkedTo: String = "",
    )

    // ------------------------------------------------------------------ गुप्त जानकारी छिपाना

    private val SECRET_RX = listOf(
        Regex("AIza[0-9A-Za-z_\\-]{20,}"),
        Regex("sk-(?:or-|ant-|proj-)?[A-Za-z0-9_\\-]{16,}"),
        Regex("gh[pousr]_[A-Za-z0-9]{20,}"),
        Regex("github_pat_[A-Za-z0-9_]{20,}"),
        Regex("gsk_[A-Za-z0-9]{20,}"),
        Regex("tvly-[A-Za-z0-9_\\-]{10,}"),
        Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]{8,}"),
        Regex("(?i)((?:api[_-]?key|key|token|access_token|password|passwd|secret|auth)\\s*[=:]\\s*)[^\\s&\"',]+"),
        Regex("\\b[A-Fa-f0-9]{32,}\\b"),
    )
    /** इन शब्दों के बाद अक्सर आपकी असली बातचीत होती है - रिपोर्ट में नहीं जाती */
    private val TALK_RX = Regex("((?:caption|सुना|पहली बात भेजी|बात भेजी|Gemini को बताया|user ने कहा|सवाल|transcript|आपने कहा)\\s*[:：]\\s*).+")

    fun redact(s: String): String {
        var t = s
        SECRET_RX.forEachIndexed { i, rx ->
            t = if (i == 7) rx.replace(t) { it.groupValues[1] + "«छिपाया»" } else rx.replace(t, "«छिपाया»")
        }
        t = TALK_RX.replace(t) { it.groupValues[1] + "«बातचीत छिपाई»" }
        return t
    }

    // ------------------------------------------------------------------ लॉग पढ़ना

    private val LINE_RX = Regex("^(\\d{2}:\\d{2}:\\d{2}\\.\\d{3}) (.*)$")

    /** DKLog.all() → घटनाएँ (क्रम और समय वैसे ही रहते हैं; बिना-समय की लाइन पिछली में जुड़ती है) */
    fun parseLog(text: String): List<Event> {
        val out = ArrayList<Event>()
        for (raw in text.lines()) {
            if (raw.isBlank()) continue
            val m = LINE_RX.find(raw)
            if (m != null) out += Event(out.size, m.groupValues[1], m.groupValues[2])
            else if (out.isNotEmpty()) { val l = out.removeAt(out.size - 1); out += l.copy(msg = (l.msg + " " + raw.trim()).take(800)) }
            else out += Event(0, "--:--:--.---", raw.trim())
        }
        return out
    }

    // ------------------------------------------------------------------ नियम

    val RULES: List<Rule> = listOf(
        Rule("live_mic_permission", Component.MICROPHONE, Certainty.CONFIRMED, Severity.CRITICAL,
            Regex("(?i)(माइक परमिशन नहीं|RECORD_AUDIO.*(denied|नहीं)|mic permission)"),
            "माइक की परमिशन नहीं है", "Android ने DK को माइक इस्तेमाल करने की अनुमति नहीं दी।",
            "DK Live आपकी आवाज़ सुन ही नहीं पाएगा।", "फ़ोन Settings → Apps → DK → Permissions → Microphone → Allow।",
            "कोड बदलाव ज़रूरी नहीं (यह फ़ोन की सेटिंग है)।", "परमिशन देकर DK Live चालू करें, Debug में 'माइक परमिशन: मिली है' दिखे।"),
        Rule("live_reconnect_failed", Component.LIVE_CONNECTION, Certainty.CONFIRMED, Severity.CRITICAL,
            Regex("(रीकनेक्शन और रिट्राई विफल|दोबारा जुड़ना बंद|reconnect.*(fail|विफल))"),
            "Gemini Live दोबारा नहीं जुड़ पाया", "बार-बार कोशिश के बाद भी Gemini Live से कनेक्शन नहीं बना।",
            "DK Live पूरी तरह बंद/चुप हो जाता है।", "इंटरनेट देखें; Gemini key/limit देखें; लगातार हो तो रिपोर्ट Coding Agent को दें।",
            "GeminiLive.kt का reconnect हिस्सा (LiveConnectNotice / scheduleReconnect) जाँचना।",
            "Wi-Fi बंद-चालू करके DK Live चलाएँ; लॉग में 'दोबारा जुड़ा' आए और यह गलती न आए।"),
        Rule("live_setup_failed", Component.LIVE_CONNECTION, Certainty.CONFIRMED, Severity.HIGH,
            Regex("(?i)(setup fail|setup नहीं हुआ|setupComplete नहीं|session.*(शुरू नहीं|init.*fail))"),
            "Gemini Live session शुरू नहीं हुआ", "WebSocket खुला पर Gemini ने setup पूरा नहीं किया (model नाम/key/config)।",
            "DK Live जुड़ता नहीं या तुरंत टूटता है।", "Gemini key और Live model जाँचें; दूसरी key/model आज़माएँ।",
            "GeminiLive.kt setup message / model चुनने वाला हिस्सा।", "DK Live चालू करें; 'setupComplete' के बाद बातचीत हो।"),
        Rule("live_conn_drop", Component.LIVE_CONNECTION, Certainty.CONFIRMED, Severity.HIGH,
            Regex("(?i)(connection टूटा|server ने बंद किया|जल्दी टूटा|connection गलती|websocket.*(fail|closed|error)|onFailure|जुड़ नहीं पाया)"),
            "Gemini Live का कनेक्शन टूटा", "WebSocket कनेक्शन बीच में बंद हुआ (नेटवर्क, server close या key की दिक्कत)।",
            "बात बीच में कटती है, DK कुछ देर चुप रहता है।", "नेटवर्क देखें; बार-बार हो तो close code/कारण के साथ रिपोर्ट Coding Agent को दें।",
            "GeminiLive.kt WebSocketListener onClosing/onFailure + reconnect।", "5 मिनट लगातार बात करें; 'connection टूटा' न आए या 1 सेकंड में दोबारा जुड़े।"),
        Rule("api_limit", Component.API, Certainty.CONFIRMED, Severity.HIGH,
            Regex("(?i)(limit खत्म|quota|RESOURCE_EXHAUSTED|\\b429\\b|rate.?limit)"),
            "API की limit/quota खत्म", "API company ने और अनुरोध लेने से मना किया (रोज़/मिनट की सीमा)।",
            "उस API वाला फ़ीचर रुकता है या दूसरे model पर चला जाता है।", "दूसरी key जोड़ें या limit reset होने दें; Extra API रखें।",
            "कोड बदलाव ज़रूरी नहीं, fallback पहले से है - fallback न चले तो जाँच।", "थोड़ी देर बाद फिर चलाएँ; 429 न आए।"),
        Rule("api_auth", Component.API, Certainty.CONFIRMED, Severity.HIGH,
            Regex("(?i)(\\b401\\b|\\b403\\b|PERMISSION_DENIED|invalid.{0,6}(api)?.?key|API key not valid|key गलत|key नहीं मिली|unauthorized)"),
            "API key गलत या मना हुई", "API ने key को अमान्य/बिना-अनुमति बताया।",
            "उस API वाला फ़ीचर काम नहीं करता।", "Settings → AI Brain में सही key डालें (key कभी रिपोर्ट में नहीं दिखती)।",
            "कोड बदलाव ज़रूरी नहीं।", "key बदलकर वही फ़ीचर चलाएँ; 401/403 न आए।"),
        Rule("mic_read_error", Component.MICROPHONE, Certainty.CONFIRMED, Severity.HIGH,
            Regex("(माइक पढ़ने में गलती|16kHz PCM16 mono नहीं देता|AudioRecord.*(fail|error|गलती))"),
            "माइक से आवाज़ पढ़ने में गलती", "AudioRecord ने आवाज़ देना बंद किया/शुरू नहीं किया (दूसरा app माइक ले सकता है)।",
            "DK आपकी बात नहीं सुनता।", "दूसरे app (call, recorder) बंद करें; DK Live बंद-चालू करें।",
            "GeminiLive.kt / mic capture वाला हिस्सा।", "DK Live में 1 मिनट बोलें; 'पढ़ने की गलती: 0' रहे।"),
        Rule("mic_silent", Component.MICROPHONE, Certainty.SUSPECTED, Severity.MEDIUM,
            Regex("(माइक से \\d+ सेकंड से बिल्कुल आवाज़ नहीं|मिक गैप)"),
            "माइक से आवाज़ नहीं आ रही", "शक: माइक कोई दूसरा app ले गया या फ़ोन ने माइक बंद रखा (पक्का नहीं)।",
            "DK सुन नहीं पाता या देर से सुनता है।", "दूसरे app बंद करें; माइक परमिशन देखें।",
            "पहले सबूत चाहिए - mic gap के समय कौन सा app माइक ले रहा था।", "DK Live चालू कर बोलें; यह संदेश न आए।"),
        Rule("audio_decode", Component.AUDIO_OUTPUT, Certainty.CONFIRMED, Severity.MEDIUM,
            Regex("(?i)(audio decode गलती|AudioTrack.*(fail|error)|playback.*(fail|गलती))"),
            "जवाब की आवाज़ चलाने में गलती", "Gemini से आया audio phone पर चल नहीं पाया।",
            "DK का जवाब कटा-कटा या बिना आवाज़।", "volume/Bluetooth देखें; बार-बार हो तो Coding Agent को दें।",
            "GeminiLive.kt audio playback / decode।", "5 जवाब सुनें; यह गलती न आए।"),
        Rule("tts_fail", Component.TTS, Certainty.CONFIRMED, Severity.HIGH,
            Regex("(?i)(आवाज़ नहीं बनी|tts.{0,30}(fail|error|गलती|नहीं चला)|TextToSpeech.*(error|fail))"),
            "बोलने वाली आवाज़ (TTS) नहीं बनी", "TTS engine/API ने आवाज़ नहीं बनाई।",
            "DK लिखकर जवाब देता है पर बोलता नहीं।", "TTS engine/आवाज़ सेटिंग देखें; दूसरी आवाज़ चुनें।",
            "TTS वाला हिस्सा (बोलने का engine)।", "कोई सवाल पूछें; आवाज़ में जवाब आए।"),
        Rule("live_unheard", Component.LIVE_RESPONSE, Certainty.SUSPECTED, Severity.MEDIUM,
            Regex("(Gemini ने आवाज़ नहीं सुनी|Gemini ने नहीं सुना)"),
            "Gemini ने आपकी आवाज़ नहीं सुनी", "शक: आवाज़ धीमी थी, माइक टुकड़े गिरे, या server ने बात खत्म नहीं पकड़ी।",
            "आप बोलते हैं पर DK जवाब नहीं देता।", "साफ़ और पास से बोलें; बार-बार हो तो रिपोर्ट दें।",
            "पहले सबूत: उसी समय 'गिरे' और 'मिक गैप' के आँकड़े।", "10 बार बोलें; हर बार जवाब आए।"),
        Rule("live_stall", Component.LIVE_RESPONSE, Certainty.CONFIRMED, Severity.HIGH,
            Regex("(सुना पर .{0,30}जवाब (शुरू नहीं|अटका)|अटकी बात|Gemini से कुछ नहीं आया|बचाव भी भेजा, पर|जवाब अटका)"),
            "Gemini ने सुना पर जवाब अटक गया", "आपकी बात पहुँची पर Gemini ने जवाब शुरू नहीं किया।",
            "DK सुनकर भी चुप रहता है।", "बार-बार हो तो पूरी रिपोर्ट Coding Agent को दें।",
            "GeminiLive.kt reply watchdog / turn-complete हिस्सा।", "10 सवाल पूछें; हर बार 3 सेकंड में जवाब शुरू हो।"),
        Rule("live_handler", Component.LIVE_RESPONSE, Certainty.CONFIRMED, Severity.MEDIUM,
            Regex("(server संदेश संभालने में गलती|JSON नहीं समझा|tool जवाब नहीं भेज पाया)"),
            "Gemini का संदेश/tool जवाब संभालने में गलती", "DK ने Gemini का कोई संदेश समझा नहीं या tool का नतीजा नहीं भेजा।",
            "कोई काम अधूरा रह सकता है या जवाब रुक सकता है।", "पूरी रिपोर्ट Coding Agent को दें।",
            "GeminiLive.kt onMessage / tool dispatch।", "वही काम दोबारा बोलें; गलती न आए।"),
        Rule("search_fail", Component.API, Certainty.CONFIRMED, Severity.MEDIUM,
            Regex("(?i)(web_search fail|खोज नहीं चली|search.{0,20}(fail|गलती))"),
            "इंटरनेट खोज नहीं चली", "Gemini/Tavily/OpenRouter/Exa में से खोज नहीं हुई।",
            "ताज़ा जानकारी वाले सवाल अधूरे।", "search keys (Tavily/Exa) देखें।",
            "ComputerTools search chain।", "कोई ताज़ा खबर पूछें; जवाब आए।"),
        Rule("coder_stopped", Component.CODING_AGENT, Certainty.CONFIRMED, Severity.MEDIUM,
            Regex("(एजेंट टीम रुकी|कोडिंग एजेंट.{0,30}(गलती|रुक|fail))"),
            "Coding Agent का काम बीच में रुका", "कोडिंग टीम किसी गलती से रुकी।",
            "कोड वाला काम पूरा नहीं हुआ।", "Coding screen में कारण देखें; key/GitHub token जाँचें।",
            "SevenRunner / CoderTeam।", "वही काम फिर दें; पूरा हो।"),
        Rule("computer_fail", Component.DK_COMPUTER, Certainty.CONFIRMED, Severity.MEDIUM,
            Regex("(DK (कंप्यूटर|Computer).{0,40}(गलती|fail|नहीं हो पाया))"),
            "DK Computer का काम नहीं हुआ", "DK Computer का कोई कदम असफल रहा।",
            "DK Computer वाला काम अधूरा।", "Computer screen में कदम देखें।",
            "ComputerTools / ComputerRunner।", "वही काम फिर दें।"),
        Rule("crash", Component.CRASH, Certainty.CONFIRMED, Severity.HIGH,
            Regex("(FATAL|[A-Za-z]+Exception|OutOfMemoryError|StackOverflowError|crash)"),
            "App में exception आया", "कोड में exception (गलती) उठा - लॉग में उसका नाम लिखा है।",
            "जिस हिस्से में आया वह काम रुक सकता है।", "पूरी रिपोर्ट Coding Agent को दें।",
            "जिस फ़ाइल/काम में exception आया - Coding Agent खुद ढूँढकर पक्का करे।", "वही काम दोबारा करें; exception न आए।",
            perMessage = true),
        Rule("generic_error", Component.APP, Certainty.SUSPECTED, Severity.LOW,
            Regex("(?i)(\\berror\\b|\\bfailed\\b|गलती|गड़बड़|विफल|नहीं हो पाया)"),
            "चेतावनी/गलती का संदेश", "लॉग में गलती लिखी है पर पक्की वजह इस संदेश से नहीं पता।",
            "असर पक्का नहीं।", "दोबारा हो तो Deep Inspection चलाएँ और रिपोर्ट देखें।",
            "पहले सबूत चाहिए।", "वही काम दोबारा करें।", perMessage = true),
    )

    /** इन संदेशों में "गलती" शब्द है पर ये गलती नहीं (जानकारी हैं) */
    private val NOT_ERROR = Regex("(गलती: 0|गलती 0|पढ़ने की गलती: 0|0 गलती|बिना गलती|गलती नहीं|गलती ठीक|no error)")

    fun ruleFor(msg: String): Rule? {
        if (NOT_ERROR.containsMatchIn(msg) && !Regex("(?i)(exception|fail|टूटा|विफल)").containsMatchIn(msg)) return null
        return RULES.firstOrNull { it.match.containsMatchIn(msg) }
    }

    /** संदेश का स्थिर रूप: अंक/ids/URL हटाकर - एक ही गलती हर बार एक ही fingerprint */
    fun normalize(msg: String): String = redact(msg).lowercase()
        .replace(Regex("https?://\\S+"), "<url>")
        .replace(Regex("\\b[0-9a-f]{8,}\\b"), "<id>")
        .replace(Regex("\\d+"), "#")
        .replace(Regex("\"[^\"]{0,80}\""), "<s>")
        .replace(Regex("\\s+"), " ").trim().take(90)

    fun fingerprint(rule: Rule, msg: String): String =
        if (rule.perMessage) rule.id + ":" + normalize(msg) else rule.id

    /** स्थिर छोटा hash (FNV-1a) - Bug ID */
    fun hash(s: String): String {
        var h = 0x811C9DC5.toInt()
        for (ch in s) { h = h xor ch.code; h *= 0x01000193 }
        return (h.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0').uppercase()
    }

    fun bugId(fp: String): String = "DKI-" + hash(fp).take(6)

    fun lineKey(e: Event): String = hash(e.time + "|" + e.msg)

    /**
     * घटनाएँ → नतीजे। [seen] = पिछली जाँच में पढ़ी जा चुकी लाइनें (वही लाइन दोबारा नहीं गिनी जाती)।
     */
    fun analyze(events: List<Event>, seen: Set<String> = emptySet()): List<Finding> {
        data class Acc(val rule: Rule, var count: Int, val first: Event, var last: Event, val ev: ArrayList<String>, val seq: List<String>)
        val acc = LinkedHashMap<String, Acc>()
        for (e in events) {
            if (lineKey(e) in seen) continue
            val r = ruleFor(e.msg) ?: continue
            val fp = fingerprint(r, e.msg)
            val a = acc[fp]
            val line = e.time + " " + redact(e.msg).take(300)
            if (a == null) {
                // पहले की घटनाएँ (क्रम) - ताकि पता चले गलती से पहले क्या हुआ
                val seq = events.subList((e.idx - 6).coerceAtLeast(0), e.idx + 1).map { it.time + " " + redact(it.msg).take(200) }
                acc[fp] = Acc(r, 1, e, e, arrayListOf(line), seq)
            } else {
                a.count++; a.last = e
                if (a.ev.size < 8) a.ev += line else { a.ev[7] = line }
            }
        }
        return acc.map { (fp, a) ->
            // बार-बार = गंभीरता एक दर्जा ऊपर (Critical से ऊपर नहीं)
            val sev = if (a.count >= 5 && a.rule.severity.rank < Severity.CRITICAL.rank)
                Severity.values().first { it.rank == a.rule.severity.rank + 1 } else a.rule.severity
            Finding(fp, a.rule, a.count, a.first.time, a.last.time, redact(a.last.msg).take(300), a.ev, a.seq, sev)
        }
    }

    // ------------------------------------------------------------------ Live हाल (GeminiLive.stats)

    data class LiveStats(val raw: String, val values: Map<String, String>) {
        fun int(k: String): Int? = values[k]?.let { Regex("-?\\d+").find(it)?.value?.toIntOrNull() }
    }

    fun parseStats(raw: String): LiveStats {
        val m = LinkedHashMap<String, String>()
        for (part in raw.split('•', '\n')) {
            val p = part.trim()
            val i = p.indexOf(':')
            if (i <= 0) continue
            m[p.substring(0, i).trim()] = p.substring(i + 1).trim()
        }
        return LiveStats(raw, m)
    }

    /** stats + माइक परमिशन से Live का हाल (सिर्फ़ जो सच में मापा गया) */
    fun liveFindings(st: LiveStats, micPermission: Boolean?, hasKey: Boolean?): List<Finding> {
        val out = ArrayList<Finding>()
        fun add(ruleId: String, n: Int, msg: String) {
            val r = RULES.first { it.id == ruleId }
            out += Finding("stats:" + ruleId + ":" + msg.substringBefore(":"), r, n, "--", "--", msg, listOf("Live हाल: " + redact(msg)), emptyList(), r.severity)
        }
        if (micPermission == false) add("live_mic_permission", 1, "माइक परमिशन नहीं मिली")
        if (hasKey == false) add("api_auth", 1, "Gemini key नहीं मिली (Settings में key नहीं है)")
        st.int("पढ़ने की गलती")?.takeIf { it > 0 }?.let { add("mic_read_error", it, "पढ़ने की गलती: $it") }
        st.int("मिक गैप")?.takeIf { it >= 3 }?.let { add("mic_silent", it, "मिक गैप: $it") }
        st.int("Gemini ने नहीं सुना")?.takeIf { it >= 2 }?.let { add("live_unheard", it, "Gemini ने नहीं सुना: $it") }
        (st.int("सुना पर जवाब अटका") ?: st.int("जवाब अटका"))?.takeIf { it >= 1 }?.let { add("live_stall", it, "सुना पर जवाब अटका: $it") }
        st.int("दोबारा जुड़ा")?.takeIf { it >= 3 }?.let { add("live_conn_drop", it, "दोबारा जुड़ा: $it बार") }
        st.int("अटका जवाब (turnComplete नहीं)")?.takeIf { it >= 1 }?.let { add("live_stall", it, "अटका जवाब (turnComplete नहीं): $it") }
        st.int("बात पर server से कुछ नहीं")?.takeIf { it >= 2 }?.let { add("live_stall", it, "बात पर server से कुछ नहीं: $it") }
        st.int("server संदेश की गलती")?.takeIf { it >= 1 }?.let { add("live_handler", it, "server संदेश की गलती: $it") }
        return out
    }

    // ------------------------------------------------------------------ देरी (latency)

    /** LatencyBoard की एक पंक्ति (ms; -1 = मापा नहीं गया) */
    data class Lat(val engine: String, val firstPiece: Long, val firstVoice: Long, val play: Long)

    data class Breakdown(val total: Long, val endpoint: Long, val generation: Long, val playback: Long, val rtt: Long, val backlogKb: Long)

    private val BD_RX = Regex("कुल (-?\\d+)ms = बोलना-खत्म-पहचान (-?\\d+)ms \\+ generation\\(\\+network\\) (-?\\d+)ms \\+ playback (-?\\d+)ms \\[([^\\]]*)]")

    fun parseBreakdowns(events: List<Event>): List<Breakdown> = events.mapNotNull { e ->
        val m = BD_RX.find(e.msg) ?: return@mapNotNull null
        val net = m.groupValues[5]
        val rtt = Regex("RTT~(\\d+)ms").find(net)?.groupValues?.get(1)?.toLongOrNull() ?: -1
        val kb = Regex("अटका (\\d+)KB").find(net)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        Breakdown(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLong(), m.groupValues[4].toLong(), rtt, kb)
    }

    data class Stage(val name: String, val ms: Long?) {
        fun show(): String = if (ms == null || ms < 0) "$name: $NOT_AVAILABLE" else "$name: ${ms}ms"
    }

    data class LatencyReport(val samples: Int, val stages: List<Stage>, val slowest: String?, val total: Long?, val warning: Finding?)

    private fun avg(xs: List<Long>): Long? = xs.filter { it >= 0 }.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size }

    fun latency(rows: List<Lat>, bds: List<Breakdown>): LatencyReport {
        val piece = avg(rows.map { it.firstPiece })
        val voice = avg(rows.filter { it.firstVoice >= 0 && it.firstPiece >= 0 }.map { it.firstVoice - it.firstPiece })
        val play = avg(rows.filter { it.play >= 0 && it.firstVoice >= 0 }.map { it.play - it.firstVoice })
        val total = avg(rows.map { it.play }) ?: avg(bds.map { it.total })
        val stages = listOf(
            Stage("आवाज़ पहचान (speech detection / बोलना खत्म पहचाना)", avg(bds.map { it.endpoint })),
            Stage("STT (आवाज़ → लिखा)", null), // Gemini Live सीधे audio लेता है - अलग STT समय मापा ही नहीं जाता
            Stage("Request भेजना (network RTT)", avg(bds.map { it.rtt })),
            Stage("AI generation (+network, साथ में मापा)", avg(bds.map { it.generation })),
            Stage("पहला जवाब टुकड़ा (बोलना खत्म → पहला टुकड़ा)", piece),
            Stage("TTS बनना (पहला टुकड़ा → पहली आवाज़)", voice),
            Stage("Playback शुरू (पहली आवाज़ → speaker)", play ?: avg(bds.map { it.playback })),
            Stage("कुल (बोलना खत्म → आवाज़ सुनाई दी)", total),
        )
        val measured = stages.dropLast(1).filter { it.ms != null && it.ms > 0 }
        val slow = measured.maxByOrNull { it.ms!! }
        val n = maxOf(rows.size, bds.size)
        var warn: Finding? = null
        if (total != null && total > 2500) {
            val sev = if (total > 6000) Severity.HIGH else if (total > 4000) Severity.MEDIUM else Severity.LOW
            val r = Rule("latency_high", Component.LATENCY, Certainty.PERF, sev, Regex("$^"),
                "जवाब आने में देरी (औसत ${total}ms)",
                "मापे गए हिस्सों में सबसे बड़ा: ${slow?.name ?: NOT_AVAILABLE}" + (slow?.ms?.let { " (${it}ms)" } ?: "") + "। जो हिस्से मापे नहीं गए उनकी वजह पक्की नहीं।",
                "DK देर से बोलता है।", "नेटवर्क तेज़ रखें; तेज़ Live model चुनें; लगातार हो तो रिपोर्ट दें।",
                "सबसे धीमे हिस्से वाला कोड (Coding Agent पक्का करे)।", "5 सवाल पूछें; Debug → देरी का हिसाब में कुल 2500ms से कम।")
            warn = Finding("latency_high", r, n, "--", "--", stages.last().show(), stages.map { it.show() }, emptyList(), sev)
        }
        return LatencyReport(n, stages, slow?.name, total, warn)
    }

    // ------------------------------------------------------------------ store के साथ मिलाना

    data class Merge(val incidents: List<Incident>, val fresh: List<Incident>, val reopened: List<Incident>)

    fun merge(old: List<Incident>, found: List<Finding>, now: Long, appVersion: String): Merge {
        val byFp = old.associateBy { it.fingerprint }.toMutableMap()
        val fresh = ArrayList<Incident>(); val reopened = ArrayList<Incident>()
        val touched = HashSet<String>()
        for (f in found) {
            touched += f.fingerprint
            val o = byFp[f.fingerprint]
            if (o == null) {
                val n = Incident(bugId(f.fingerprint), f.fingerprint, f.rule.id, f.rule.title, f.rule.component, f.severity,
                    f.rule.certainty, now, now, f.count, Status.NEW, f.message, f.evidence, f.sequence,
                    f.rule.cause, f.rule.impact, f.rule.fix, f.rule.code, f.rule.test)
                byFp[f.fingerprint] = n; fresh += n
            } else {
                val wasResolved = o.status == Status.RESOLVED
                // stats वाले नतीजे गिनती का कुल होते हैं - जोड़ते नहीं, सबसे बड़ा रखते हैं
                val cnt = if (f.fingerprint.startsWith("stats:") || f.fingerprint == "latency_high") maxOf(o.count, f.count) else o.count + f.count
                val st = when {
                    wasResolved -> Status.REOPENED
                    o.status == Status.NEW -> Status.INVESTIGATING
                    else -> o.status
                }
                val sev = if (f.severity.rank > o.severity.rank) f.severity else o.severity
                val n = o.copy(lastSeen = now, count = cnt, status = st, severity = sev, message = f.message,
                    evidence = (o.evidence + f.evidence).takeLast(8), quietChecks = 0,
                    reopenCount = o.reopenCount + if (wasResolved) 1 else 0,
                    resolvedNote = if (wasResolved) "पहले हल माना गया था (${o.resolvedNote}) पर फिर आया।" else o.resolvedNote)
                byFp[f.fingerprint] = n
                if (wasResolved) reopened += n
            }
        }
        // जो इस बार नहीं दिखे: शांत जाँच गिनो + हल की पुष्टि
        val out = byFp.values.map { i ->
            if (i.fingerprint in touched) i else verifyResolved(i.copy(quietChecks = i.quietChecks + 1), now, appVersion)
        }.sortedWith(compareByDescending<Incident> { it.status != Status.RESOLVED }.thenByDescending { it.severity.rank }.thenByDescending { it.lastSeen })
        return Merge(out.take(60), fresh, reopened)
    }

    const val RESOLVE_QUIET_CHECKS = 3
    const val RESOLVE_QUIET_MS = 24 * 3600_000L

    /**
     * हल तभी: Coding Agent को दिया गया (FIXING), app का version बदला (fix लगा),
     * और उसके बाद कम से कम 3 जाँच + 24 घंटे तक गलती दोबारा नहीं दिखी।
     */
    fun verifyResolved(i: Incident, now: Long, appVersion: String): Incident {
        if (i.status != Status.FIXING) return i
        if (i.fixVersion.isBlank() || i.fixVersion == appVersion) return i
        if (i.quietChecks < RESOLVE_QUIET_CHECKS || now - i.lastSeen < RESOLVE_QUIET_MS) return i
        return i.copy(status = Status.RESOLVED, resolvedAt = now,
            resolvedNote = "सत्यापित: नए version ($appVersion) में ${i.quietChecks} जाँच और 24 घंटे तक दोबारा नहीं दिखा")
    }

    /** DK Live को बताना है? - सिर्फ़ ज़रूरी नई/फिर-से आई/ज़्यादा गंभीर हुई गड़बड़ी, एक बार */
    fun shouldNotify(i: Incident): Boolean {
        if (i.status == Status.RESOLVED) return false
        val important = i.severity.rank >= Severity.HIGH.rank || (i.certainty == Certainty.CONFIRMED && i.count >= 3 && i.severity.rank >= Severity.MEDIUM.rank)
        if (!important) return false
        if (i.notifiedAt == 0L) return true
        if (i.status == Status.REOPENED && i.lastSeen > i.notifiedAt) return true
        return i.severity.rank > i.notifiedSeverity
    }

    // ------------------------------------------------------------------ रिपोर्ट

    private fun fmt(t: Long): String = if (t <= 0) "--" else
        java.text.SimpleDateFormat("dd MMM yyyy, HH:mm:ss", java.util.Locale.US).format(java.util.Date(t))

    fun fullReport(i: Incident): String = buildString {
        appendLine("DK INSPECTOR BUG REPORT")
        appendLine("Bug ID: ${i.id}")
        appendLine("Title: ${i.title}")
        appendLine("Detection timestamp: ${fmt(i.firstSeen)} (आख़िरी बार: ${fmt(i.lastSeen)}, कुल ${i.count} बार)")
        appendLine("Affected component: ${i.component.hi}")
        appendLine("Severity: ${i.severity.hi}")
        appendLine("Type: ${i.certainty.hi}")
        appendLine("Status: ${i.status.hi}")
        if (i.linkedTo.isNotBlank()) appendLine("Linked to: ${i.linkedTo}")
        appendLine("Fingerprint: ${i.fingerprint.take(120)}")
        appendLine()
        appendLine("Actual error message:"); appendLine("  ${i.message}")
        appendLine()
        appendLine("Relevant log entries:"); i.evidence.forEach { appendLine("  $it") }
        if (i.sequence.isNotEmpty()) { appendLine(); appendLine("Event sequence (गलती से पहले क्या हुआ):"); i.sequence.forEach { appendLine("  $it") } }
        appendLine()
        appendLine(if (i.certainty == Certainty.CONFIRMED) "Root cause (लॉग से पक्का):" else "Suspected cause (शक - पक्का नहीं):")
        appendLine("  ${i.cause}")
        appendLine("Evidence: ऊपर की लॉग लाइनें (${i.evidence.size}) - API key/token/बातचीत छिपाई गई है।")
        appendLine("User impact: ${i.impact}")
        appendLine("Recommended solution: ${i.fix}")
        appendLine("Required code changes: ${i.code}")
        appendLine("Testing procedure: ${i.test}")
        if (i.resolvedNote.isNotBlank()) appendLine("Note: ${i.resolvedNote}")
    }.trimEnd()

    /** DK Live के लिए आसान हिंदी */
    fun liveSummary(i: Incident): String = buildString {
        append("DK Inspector ने एक ${if (i.status == Status.REOPENED) "फिर से आई" else "नई"} गड़बड़ी पकड़ी है (${i.id}, ${i.severity.hi}). ")
        append("क्या हुआ: ${i.title} (${i.count} बार). ")
        append(if (i.certainty == Certainty.CONFIRMED) "क्यों: ${i.cause} " else "शायद क्यों (पक्का नहीं): ${i.cause} ")
        append("किस फ़ीचर पर असर: ${i.component.hi} - ${i.impact} ")
        append("कैसे ठीक हो: ${i.fix} ")
        append("पूरी रिपोर्ट Settings → Debug → DK Inspector में है.")
    }

    /** मंज़ूरी के बाद Coding Agent को जाने वाला काम - Coding Agent खुद वजह पक्की करे */
    fun coderTask(i: Incident): String = buildString {
        appendLine("DK Inspector की bug रिपोर्ट (मालिक ने Coding Agent को देने की मंज़ूरी दी)।")
        appendLine("ज़रूरी नियम: पहले खुद कोड पढ़कर वजह पक्की करो - यह रिपोर्ट सिर्फ़ सबूत है, आख़िरी सच नहीं।")
        appendLine("अगर कोड में वजह पक्की न हो तो कुछ मत बदलो, बताओ कि और कौन सा लॉग/टेस्ट चाहिए।")
        appendLine("पुराना चलता कोड मत तोड़ो, मौजूदा tests मत हटाओ/कमज़ोर मत करो, fix के बाद tests + build चलाओ, PR बनाओ पर merge मत करो।")
        appendLine()
        append(fullReport(i))
    }

    data class Snapshot(
        val incidents: List<Incident>, val latency: LatencyReport?, val stats: LiveStats?,
        val micPermission: Boolean?, val events: List<Event>, val lastInspection: Long,
    )

    /** "Ask DK Inspector" - असली डेटा से जवाब; डेटा कम हो तो साफ़ बताए क्या चाहिए */
    fun answer(q: String, s: Snapshot): String {
        val t = q.lowercase()
        Regex("dki-[0-9a-f]{6}").find(t)?.let { m ->
            val i = s.incidents.firstOrNull { it.id.equals(m.value, true) }
            return i?.let { fullReport(it) } ?: "${m.value.uppercase()} नाम की कोई रिपोर्ट नहीं मिली। Bug Reports सूची से सही ID लें।"
        }
        val open = s.incidents.filter { it.status != Status.RESOLVED }
        val sb = StringBuilder()
        val topic: Component? = when {
            Regex("(slow|late|latency|देर|धीम|time|समय|जल्दी|fast)").containsMatchIn(t) -> Component.LATENCY
            Regex("(mic|माइक|सुन|hear|मिक)").containsMatchIn(t) -> Component.MICROPHONE
            Regex("(tts|बोल नहीं|आवाज़ नहीं|voice|sound|speaker|audio|playback)").containsMatchIn(t) -> Component.AUDIO_OUTPUT
            Regex("(connect|जुड़|टूट|disconnect|reconnect|कनेक्ट|websocket)").containsMatchIn(t) -> Component.LIVE_CONNECTION
            Regex("(api|key|limit|quota|429)").containsMatchIn(t) -> Component.API
            Regex("(coder|coding|कोडिंग)").containsMatchIn(t) -> Component.CODING_AGENT
            Regex("(computer|कंप्यूटर)").containsMatchIn(t) -> Component.DK_COMPUTER
            Regex("(crash|exception|बंद हो)").containsMatchIn(t) -> Component.CRASH
            else -> null
        }
        if (topic == Component.LATENCY) {
            val l = s.latency
            if (l == null || l.samples == 0) return "देरी का कोई माप अभी नहीं है ($NOT_AVAILABLE)। चाहिए: DK Live चालू करके 3-5 सवाल बोलकर पूछें, फिर 'Run Deep Inspection' दबाएँ - तब 'देरी का हिसाब' में माप आएँगे।"
            sb.appendLine("देरी (${l.samples} माप से, औसत):")
            l.stages.forEach { sb.appendLine("• " + it.show()) }
            sb.appendLine(if (l.slowest != null) "सबसे ज़्यादा समय: ${l.slowest} (मापे गए हिस्सों में)।" else "कौन सा हिस्सा धीमा है यह मापा नहीं जा सका।")
            if (l.stages.any { it.ms == null }) sb.appendLine("$NOT_AVAILABLE वाले हिस्से app मापता ही नहीं - उनके बारे में अंदाज़ा नहीं लगाया गया।")
            l.warning?.let { sb.appendLine("सुझाव: " + it.rule.fix) }
            return sb.toString().trimEnd()
        }
        val rel = when (topic) {
            null -> open
            Component.AUDIO_OUTPUT -> open.filter { it.component == Component.AUDIO_OUTPUT || it.component == Component.TTS }
            Component.MICROPHONE -> open.filter { it.component == Component.MICROPHONE || it.ruleId == "live_unheard" }
            Component.LIVE_CONNECTION -> open.filter { it.component == Component.LIVE_CONNECTION }
            else -> open.filter { it.component == topic }
        }
        val wantsFix = Regex("(fix|ठीक|हल|solution|कैसे|सुधार)").containsMatchIn(t)
        val wantsHistory = Regex("(पहले|before|again|दोबारा|फिर से|history|still|अभी भी)").containsMatchIn(t)
        if (rel.isEmpty()) {
            val resolved = s.incidents.filter { it.status == Status.RESOLVED && (topic == null || it.component == topic) }
            if (resolved.isNotEmpty() && wantsHistory) return "इस बारे में खुली गड़बड़ी नहीं। पहले की हल हुई: " + resolved.joinToString("; ") { "${it.id} ${it.title} (${it.resolvedNote})" }
            val need = when (topic) {
                Component.MICROPHONE -> "DK Live चालू करके 1 मिनट बोलें (माइक के आँकड़े 'मिक गैप/पढ़ने की गलती' तभी बनते हैं), फिर Deep Inspection चलाएँ।"
                Component.AUDIO_OUTPUT -> "DK Live से 3 सवाल पूछें ताकि playback/TTS लॉग बने, फिर Deep Inspection चलाएँ।"
                Component.LIVE_CONNECTION -> "DK Live चालू रखें; कनेक्शन टूटे तो उसी समय Deep Inspection चलाएँ (लॉग app बंद होने पर मिट जाते हैं)।"
                else -> "जिस समय दिक्कत हो उसी समय (app बंद किए बिना) 'Run Deep Inspection' दबाएँ - लॉग सिर्फ़ फ़ोन की memory में 300 लाइन तक रहते हैं।"
            }
            val logs = s.events.size
            return "अभी के डेटा में ${topic?.hi ?: "किसी हिस्से"} की कोई गड़बड़ी नहीं मिली (लॉग लाइनें पढ़ीं: $logs" +
                (if (s.lastInspection > 0) ", आख़िरी जाँच: ${fmt(s.lastInspection)}" else ", अभी तक कोई जाँच नहीं") + ")। पक्का जवाब देने के लिए चाहिए: $need"
        }
        sb.appendLine("${topic?.hi ?: "सभी हिस्से"}: ${rel.size} खुली गड़बड़ी (असली लॉग से):")
        rel.take(5).forEach { i ->
            sb.appendLine("• ${i.id} [${i.severity.name}] ${i.title} - ${i.count} बार, आख़िरी ${fmt(i.lastSeen)}")
            sb.appendLine("  संदेश: ${i.message.take(160)}")
            sb.appendLine("  " + (if (i.certainty == Certainty.CONFIRMED) "वजह (पक्की): " else "वजह (शक, पक्की नहीं): ") + i.cause)
            if (wantsFix || topic != null) sb.appendLine("  हल: ${i.fix}")
            if (wantsHistory) sb.appendLine("  हाल: ${i.status.hi}" + if (i.reopenCount > 0) ", ${i.reopenCount} बार हल होकर फिर आया" else "")
        }
        if (rel.any { it.certainty != Certainty.CONFIRMED }) sb.appendLine("शक वाली वजह पक्की करने के लिए: वही काम दोबारा करें और तुरंत Deep Inspection चलाएँ।")
        return sb.toString().trimEnd()
    }

    // ------------------------------------------------------------------ JSON (store के लिए)

    fun toJson(i: Incident): JSONObject = JSONObject().apply {
        put("id", i.id); put("fp", i.fingerprint); put("rule", i.ruleId); put("title", i.title)
        put("comp", i.component.name); put("sev", i.severity.name); put("cert", i.certainty.name)
        put("first", i.firstSeen); put("last", i.lastSeen); put("count", i.count); put("status", i.status.name)
        put("msg", i.message); put("ev", JSONArray(i.evidence)); put("seq", JSONArray(i.sequence))
        put("cause", i.cause); put("impact", i.impact); put("fix", i.fix); put("code", i.code); put("test", i.test)
        put("notified", i.notifiedAt); put("notSev", i.notifiedSeverity); put("reopen", i.reopenCount)
        put("fixVer", i.fixVersion); put("quiet", i.quietChecks); put("resolvedAt", i.resolvedAt)
        put("resolvedNote", i.resolvedNote); put("linked", i.linkedTo)
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

    fun fromJson(o: JSONObject): Incident? = try {
        Incident(o.getString("id"), o.getString("fp"), o.optString("rule"), o.optString("title"),
            Component.valueOf(o.optString("comp", "APP")), Severity.valueOf(o.optString("sev", "LOW")),
            Certainty.valueOf(o.optString("cert", "SUSPECTED")), o.optLong("first"), o.optLong("last"), o.optInt("count"),
            Status.valueOf(o.optString("status", "NEW")), o.optString("msg"), o.optJSONArray("ev").strings(),
            o.optJSONArray("seq").strings(), o.optString("cause"), o.optString("impact"), o.optString("fix"),
            o.optString("code"), o.optString("test"), o.optLong("notified"), o.optInt("notSev"), o.optInt("reopen"),
            o.optString("fixVer"), o.optInt("quiet"), o.optLong("resolvedAt"), o.optString("resolvedNote"), o.optString("linked"))
    } catch (e: Exception) { null }

    fun listToJson(xs: List<Incident>): String = JSONArray().apply { xs.forEach { put(toJson(it)) } }.toString()

    fun listFromJson(s: String?): List<Incident> = try {
        if (s.isNullOrBlank()) emptyList() else JSONArray(s).let { a -> (0 until a.length()).mapNotNull { fromJson(a.getJSONObject(it)) } }
    } catch (e: Exception) { emptyList() }
}
