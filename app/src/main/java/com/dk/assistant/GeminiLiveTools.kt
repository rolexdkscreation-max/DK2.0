package com.dk.assistant

import com.dk.assistant.GeminiLive.Companion.ANSWER_TOOLS
import com.dk.assistant.GeminiLive.Companion.LEAK_ID
import com.dk.assistant.GeminiLive.Companion.PREP_SEARCH_MS
import com.dk.assistant.GeminiLive.Companion.PREP_WEATHER_MS
import com.dk.assistant.GeminiLive.Companion.TOOL_ANSWER_MS
import com.dk.assistant.GeminiLive.Prep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.WebSocket
import org.json.JSONArray
import org.json.JSONObject

/** v3.152: इमेज बनाना + डाउनलोड + देखना (ChatGPT/Gemini ~30-90s) */
internal const val IMAGE_TASK_MS = 190_000L

// GeminiLive का हिस्सा: Live के tools: खोज, मौसम/जगह, notification, एजेंट, call, tool call चलाना.
// यह code पहले GeminiLive.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state GeminiLive में)।

internal suspend fun GeminiLive.callTool(action: String, from: String): String {
    val a = action.trim().lowercase()
    if (a !in CallReply.ACTIONS) return "Unknown action '$action' - use one of: ${CallReply.ACTIONS.joinToString()}"
    val now = System.currentTimeMillis()
    val pick = listOf(CallReply.ANSWER, CallReply.ANSWER_SPEAKER, CallReply.REJECT)
    // v3.41: phone (caption) aur Gemini ek saath -> pehla jeete, doosra "ho gaya" (dono saath uthayein/kaatein nahi)
    synchronized(callLock) {
        callDone?.let { (d, at) ->
            if (now - at < 15_000 && d in pick && a in pick) return "Already done ($d) a moment ago - do not repeat it."
        }
        if (a in pick) callDone = a to now
    }
    // नवंबर 2026 (मालिक): उठाया / काटा = उसी पल DK का बोलना बंद (Live या बैकअप) - "पूरा नाम / नंबर" फिर से नहीं
    if (a in pick) { svc.hushForCall(); if (isPlaying()) flushPlayback() }
    val r = svc.liveCallAction(a)
    if (a in pick && (r.startsWith("ERROR") || r == "NO_CALL")) synchronized(callLock) { if (callDone?.second == now) callDone = null }
    if (a != CallReply.WHO && a !in pick && !r.startsWith("ERROR") && r != "NO_CALL") callDone = a to now
    log("कॉल ($from): $a -> $r")
    return if (a == CallReply.WHO) (if (r.startsWith("CALLER:")) r else "Caller name is not known.") else CallReply.resultNote(a, r)
}

/** v3.75: web_search tool - फ़ोन खुद Google Search (Gemini REST) करके छोटा जवाब + स्रोत लाता है */
internal suspend fun GeminiLive.webSearchTool(query: String): String {
    val q = query.trim().ifBlank { turnUser.trim() }
    if (q.length < 2) return "Ask the user what to search."
    DKState.hint(LivePromise.hintFor("web_search", q))
    setMode(Mode.THINKING)
    // v3.87: Live की खोज = Live की अपनी Gemini key पहले; fail = ख़ुद एक बार दोबारा (छोटी, तेज़ खोज)
    // v3.93: सामान्य ज्ञान / गणित / कहानी (ताज़ा शब्द नहीं) = खोज नहीं, अपनी जानकारी से
    if (WebSearch.skip(q)) {
        log("web_search: \"${q.take(40)}\" - सामान्य सवाल, खोज नहीं")
        return "NO_SEARCH_NEEDED: this is general knowledge (not live or changing). Answer it now from your own knowledge, briefly. Do not call web_search for it again."
    }
    val brain = BrainManager(svc).also { it.fromLive = true }
    // v3.88: यूज़र के बोलते ही शुरू हुई खोज उसी बात की है = वही नतीजा (इंतज़ार कम)
    prepFail = null
    var r: SearchResult? = preparedSearch(q)?.also { log("web_search: पहले से तैयार नतीजा इस्तेमाल किया") }
    var why = SearchGuard.Fail.OTHER
    // v3.90: अभी-अभी (यूज़र के बोलते ही) वही पूरी Google खोज fail हुई = वही दोबारा भेजना बेकार (सीमा और भरती, समय जाता),
    // सीधे बचाव वाली खोज (बिना-key वेबसाइटें + ज़रूरत हो तो एक छोटी Gemini खोज)
    val pf = prepFail
    if (r == null && pf != null) {
        why = pf; log("web_search: पहले वाली खोज fail (${SearchGuard.hindi(pf)}) - सीधे बचाव वाली खोज")
        val fb = fallbackSearch(q, why, brain); r = fb.first; why = fb.second
        if (r == null) why = pf
    }
    if (r == null && pf == null) {
        r = try { withTimeoutOrNull(LiveSearch.totalTimeoutMs(Prefs.searchOwnKey(svc).isNotBlank()) + chainExtraMs()) { brain.search(q, deep = false) } }
        catch (e: CancellationException) { throw e } catch (e: Exception) {
            why = brain.lastSearchFail
            log("web_search fail (कोशिश 1): ${SearchGuard.hindi(why)} - ${e.javaClass.simpleName} ${e.message?.take(120)}")
            null
        }
        if (r == null && why == SearchGuard.Fail.OTHER) why = SearchGuard.Fail.TIMEOUT
        if (r != null && r.text.isBlank()) { r = null; why = SearchGuard.Fail.EMPTY }
        // v3.90: Google खोज न चली तो हार नहीं - बिना key वाली दूसरी वेबसाइटें (Google News, Wikipedia) + एक छोटी दोबारा खोज, साथ-साथ
        if (r == null) { val fb = fallbackSearch(q, why, brain); r = fb.first; why = fb.second }
    }
    val res = r
    if (res == null) {
        log("web_search: \"${q.take(40)}\" -> दो कोशिश के बाद भी नहीं (${SearchGuard.hindi(why)})")
        DKState.hint(when (why) {
            SearchGuard.Fail.NO_KEY -> "${WebSearch.ALL_FAILED_LINE} - सेटिंग → खोज में Tavily, Exa या Gemini key डालें"
            SearchGuard.Fail.BAD_KEY -> "${WebSearch.ALL_FAILED_LINE} - खोज की key ग़लत है, सेटिंग → खोज में जाँचें"
            SearchGuard.Fail.QUOTA -> "${WebSearch.ALL_FAILED_LINE} - खोज की limit खत्म है"
            SearchGuard.Fail.NETWORK -> "${WebSearch.ALL_FAILED_LINE} - इंटरनेट की दिक्कत है"
            else -> WebSearch.ALL_FAILED_LINE
        })
        return SearchGuard.failResult(q, why)
    }
    // स्रोत सिर्फ़ तब, जब सच में खोज से जवाब आया
    if (res.sources.isNotEmpty()) toolSources = Grounding.merge(toolSources, res.sources)
    log("web_search: \"${q.take(40)}\" -> ${res.text.length} अक्षर, ${res.sources.size} स्रोत")
    return "SEARCH RESULT (verified from several websites): " + res.text.take(3200) + "\n" + LiveSearch.sourcesLine(res.sources) +
        "\nSpeak the answer from this result now, briefly and naturally. Do not read URLs."
}

/**
 * v3.90: Gemini की खोज fail होने पर बचाव - (1) बिना-key खोज: Google News + Wikipedia, (2) साथ में छोटी Gemini खोज
 * (सिर्फ़ जब दोबारा कोशिश का फ़ायदा हो: धीमा नेट / अनजानी गड़बड़ - सीमा भरी/key ग़लत पर नहीं, वरना वही गलती फिर).
 * Gemini वाला मिले तो वही (ज़्यादा सटीक), वरना बिना-key वाला. कुछ न मिले = (null, असली वजह).
 */
internal suspend fun GeminiLive.fallbackSearch(q: String, why0: SearchGuard.Fail, brain: BrainManager): Pair<SearchResult?, SearchGuard.Fail> = coroutineScope {
    var why = why0
    val open = async(Dispatchers.IO) { try { OpenSearchNet.search(q) } catch (e: Exception) { log("बिना-key खोज fail: ${e.message}"); null } }
    // v3.93: Tavily/Exa वाला क्रम पहले ही सब सेवाएँ आज़मा चुका = Gemini से दोबारा खोज नहीं (एक सवाल पर एक खोज)
    val retry = why in listOf(SearchGuard.Fail.TIMEOUT, SearchGuard.Fail.OTHER, SearchGuard.Fail.EMPTY) && chainExtraMs() == 0L
    val quick = if (!retry) null else try { withTimeoutOrNull(16_000) { brain.quickSearch(q) } }
        catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (brain.lastSearchFail != SearchGuard.Fail.OTHER) why = brain.lastSearchFail
            log("web_search fail (कोशिश 2): ${SearchGuard.hindi(why)} - ${e.message?.take(100)}"); null
        }
    if (quick != null && quick.text.isNotBlank()) { open.cancel(); return@coroutineScope quick to why }
    val o = withTimeoutOrNull(12_000) { open.await() }
    open.cancel()
    if (o != null) {
        log("web_search: Google खोज नहीं चली (${SearchGuard.hindi(why)}) - बिना-key वेबसाइटों से जवाब")
        return@coroutineScope SearchResult(OPEN_NOTE + o.text, o.sources) to why
    }
    null to why
}

/** v3.93: Tavily/Exa पहले आज़माए जाते हैं - उनके लिए कुछ सेकंड और (हर एक ज़्यादा से ज़्यादा ~10 सेकंड) */
internal fun GeminiLive.chainExtraMs(): Long =
    (if (Prefs.tavilySearchKey(svc).isNotBlank()) 10_000L else 0L) + (if (Prefs.exaSearchKey(svc).isNotBlank()) 10_000L else 0L)

/**
 * "हेलो डीके, आज का मौसम बताओ" - "मौसम" सुनते ही लोकेशन + मौसम लाना शुरू. Gemini जब local_info माँगे तो तैयार मिले.
 * ताज़ा खबर/स्कोर = पक्का (final) text आते ही खोज शुरू; Gemini की web_search उसी बात की हो तो वही नतीजा.
 * अंदाज़ा गलत = कोई इस्तेमाल नहीं करता, कुछ देर में अपने-आप पुराना (यूज़र को कुछ नहीं दिखता/सुनाई देता).
 */
internal fun GeminiLive.prefetch(t: String, final: Boolean) {
    val k = SpecIntent.detect(t) ?: return
    val now = System.currentTimeMillis()
    try {
        when (k) {
            SpecIntent.Kind.WEATHER -> {
                if (prepWeather?.let { now - it.at < PREP_WEATHER_MS } == true) return
                if (!LocalInfo.hasPermission(svc)) return
                prepWeather = Prep(k, t, now, svc.scope.async(Dispatchers.IO) {
                    try { LocalInfo.now(svc) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                })
                log("पहले से तैयारी: मौसम (\"${t.take(30)}\")")
            }
            SpecIntent.Kind.SEARCH -> {
                if (!final) return
                val p = prepSearch
                if (p != null && now - p.at < PREP_SEARCH_MS && p.text == t.trim()) return
                val q = t.trim()
                prepSearch = Prep(k, q, now, svc.scope.async(Dispatchers.IO) {
                    val b = BrainManager(svc).also { it.fromLive = true }
                    // v3.90: fail हुई तो वजह भी (सीमा भरी/key ग़लत = web_search फिर वही Google खोज न दोहराए)
                    try { withTimeoutOrNull(LiveSearch.totalTimeoutMs(Prefs.searchOwnKey(svc).isNotBlank())) { b.search(q, deep = false) } ?: SearchGuard.Fail.TIMEOUT }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { b.lastSearchFail }
                })
                log("पहले से तैयारी: खोज (\"${q.take(30)}\")")
            }
        }
    } catch (e: Exception) { log("पहले से तैयारी नहीं हुई: ${e.javaClass.simpleName}") }
}

/** तैयार मौसम (ताज़ा हो तो); नहीं = null (तब पहले जैसा अभी लाओ) */
@Suppress("UNCHECKED_CAST")
internal suspend fun GeminiLive.preparedWeather(): Pair<LocalInfo.Place, String>? {
    val p = prepWeather ?: return null
    if (System.currentTimeMillis() - p.at > PREP_WEATHER_MS) { prepWeather = null; return null }
    return (try { p.job.await() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }) as? Pair<LocalInfo.Place, String>
}

/** तैयार खोज - उसी बात की हो और ताज़ा हो तो; नहीं = null (तब पहले जैसी खोज) */
internal suspend fun GeminiLive.preparedSearch(query: String): SearchResult? {
    val p = prepSearch ?: return null
    if (System.currentTimeMillis() - p.at > PREP_SEARCH_MS || !SpecIntent.sameTopic(query, p.text)) return null
    prepSearch = null       // एक बार ही इस्तेमाल
    val r = try { p.job.await() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    prepFail = r as? SearchGuard.Fail
    return (r as? SearchResult)?.takeIf { it.text.isNotBlank() }
}

/** v4.3: user ने जगह/आसपास पूछा (सिर्फ़ मौसम नहीं) - तब पूरा पता + आसपास की जगहें */
internal fun GeminiLive.wantsWhere(): Boolean = NearbyPlaces.wantsWhere(DKState.lastUser)

internal suspend fun GeminiLive.localInfoTool(find: String = ""): String {
    // v4.3 (मालिक): इजाज़त नहीं / GPS बंद = साफ़ बताओ कि क्या चालू करना है
    if (!LocalInfo.hasPermission(svc)) {
        try {
            svc.startActivity(android.content.Intent(svc, MainActivity::class.java).putExtra("ask_location", true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: Exception) {}
        return "Location permission is OFF, so you cannot know where he is. I opened the DK app to ask for it. Tell him clearly and briefly: allow Location for DK on the screen (\"Allow\" / \"While using the app\"), then ask again."
    }
    if (!LocalInfo.locationOn(svc)) return "The phone's Location (GPS) is turned OFF, so you cannot see where he is. Tell him clearly: turn ON Location from the quick settings (pull down from the top, tap 'Location'), then ask again."
    // नवंबर 2026: "पास में पेट्रोल पंप / ATM / दुकान कहाँ है" = उसी तरह की सबसे पास वाली जगहें
    if (find.isNotBlank()) {
        LocalInfo.find(svc, find)?.let { return it }
        return "Could not get a fresh location fix right now (weak GPS/network). Tell him honestly; suggest stepping near a window/open area with Location ON and asking again."
    }
    if (wantsWhere()) {
        LocalInfo.where(svc)?.let { return it }
        return "Could not get a fresh location fix right now (weak GPS/network). Tell him honestly; suggest stepping near a window/open area with Location ON and asking again."
    }
    val r = preparedWeather()?.also { log("local_info: पहले से तैयार मौसम इस्तेमाल किया") } ?: LocalInfo.now(svc) ?: return "Could not get the current location right now (GPS/location may be off). Tell the user honestly."
    return "Current place: ${r.first.name.ifBlank { "unknown area (%.3f, %.3f)".format(java.util.Locale.US, r.first.lat, r.first.lon) }}. Weather now: ${r.second.substringAfter(" — ", r.second)}"
}

/** नवंबर 2026: अभी की लोकेशन -> Google Maps लिंक वाला संदेश -> वही send_message रास्ता (पूछकर भेजना) */
internal suspend fun GeminiLive.waLocation(args: JSONObject): String {
    if (args.optString("contact").isBlank()) return "NEEDS USER CHOICE: किसे लोकेशन भेजनी है? Ask the user for the name."
    DKState.set(Mode.WORKING)
    if (!LocalInfo.hasPermission(svc)) return "NOT SENT: DK has no location permission. Tell the user to allow Location for DK in phone settings, then ask again."
    val l = LocalInfo.fresh(svc) ?: return "NOT SENT: could not get a fresh location fix right now (Location off / weak GPS). Tell the user honestly; nothing was sent."
    val place = try { withTimeoutOrNull(5_000) { LocalInfo.placeName(svc, l.latitude, l.longitude) }.orEmpty() } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
    args.put("action", "send_message").put("message", com.dk.assistant.agent.social.WaShare.locationText(l.latitude, l.longitude, place))
    log("WhatsApp: लोकेशन का लिंक बनाया (भेजने से पहले पूछेगा)")
    return com.dk.assistant.agent.android.SocialAgent.start(svc, args, com.dk.assistant.agent.social.Platform.WHATSAPP)
}

/** नवंबर 2026: किसी का सेव नंबर (query) -> "Rahul ka number: ..." -> send_message रास्ता (पूछकर भेजना) */
internal suspend fun GeminiLive.waContact(args: JSONObject): String {
    val whose = args.optString("query").trim()
    if (whose.isEmpty()) return "NEEDS USER CHOICE: किसका नंबर भेजना है? Ask the user."
    if (args.optString("contact").isBlank()) return "NEEDS USER CHOICE: $whose का नंबर किसे भेजना है? Ask the user."
    val all = try { PhoneActions.contacts(svc) } catch (_: SecurityException) { return "NOT SENT: DK has no Contacts permission. Tell the user to allow Contacts for DK." }
    val found = all.filter { com.dk.assistant.agent.social.WaScreen.nameMatches(whose, it.name) }.distinctBy { it.number.filter { ch -> ch.isDigit() }.takeLast(10) }
    return when {
        found.isEmpty() -> "NOT FOUND: '$whose' नाम का कोई नंबर फ़ोन में सेव नहीं मिला। Tell the user; nothing was sent."
        found.size > 1 -> "NEEDS USER CHOICE (nothing sent): '$whose' नाम के ${found.size} कॉन्टैक्ट हैं: ${found.take(5).joinToString(", ") { it.name }}. Ask which one, then call send_contact again with that exact name."
        else -> {
            args.put("action", "send_message").put("message", com.dk.assistant.agent.social.WaShare.contactText(found[0].name, found[0].number))
            com.dk.assistant.agent.android.SocialAgent.start(svc, args, com.dk.assistant.agent.social.Platform.WHATSAPP)
        }
    }
}

/** नवंबर 2026 (मालिक: WhatsApp पर DK Hinglish में लिखे): देवनागरी संदेश -> Roman Hinglish (न बने = वही संदेश) */
internal suspend fun GeminiLive.waHinglish(args: JSONObject) {
    val msg = args.optString("message")
    if (!com.dk.assistant.agent.social.WaHinglish.need(msg)) return
    val out = try {
        withTimeoutOrNull(6_000) { BrainManager(svc).quickText(com.dk.assistant.agent.social.WaHinglish.SYSTEM, msg) }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    com.dk.assistant.agent.social.WaHinglish.clean(out, msg)?.let { args.put("message", it); log("WhatsApp संदेश Hinglish में बदला") }
}

/** v3.41: फ़ोन के notification (DKNotifListener -> NotifStore) */
internal fun GeminiLive.notifTool(app: String): String {
    if (!DKNotifListener.enabled(svc)) {
        DKNotifListener.openSettings(svc)
        return "Notification access is OFF. I opened the setting - tell the user to turn ON 'DK' in Notification access, then ask again."
    }
    // v3.51: पहले सिर्फ़ memory देखी जाती थी - Android ने पढ़ने वाला (listener) अलग कर दिया हो / ऐप दोबारा शुरू हुआ हो
    // तो memory खाली = "कोई notification नहीं" (झूठ). अब फ़ोन पर अभी पड़े notification ताज़ा पढ़ो; न पढ़ सको तो सच बताओ.
    val alive = DKNotifListener.refresh(svc)
    val q = app.trim()
    val list = NotifStore.recent(app = q)
    val r = NotifPush.toolAnswer(alive, NotifStore.describe(list, System.currentTimeMillis()), NotifStore.all().size, q,
        NotifStore.describe(list, System.currentTimeMillis()))
    log("notifications (${if (alive) "ताज़ा पढ़ा" else "पढ़ने वाला जुड़ा नहीं"}): ${list.size}")
    return r
}

internal fun GeminiLive.notifReply(to: String, message: String): String {
    val msg = message.trim()
    if (msg.isBlank()) return "No message given - ask the user what to reply."
    if (!DKNotifListener.enabled(svc)) {
        DKNotifListener.openSettings(svc)
        return "Notification access is OFF. I opened the setting - tell the user to turn ON 'DK' in Notification access."
    }
    val n = NotifStore.findForReply(to)
        ?: return "No notification found that can be replied to" + (if (to.isNotBlank()) " for '$to'" else "") + ". Tell the user; offer to send it via phone_task instead."
    val err = DKNotifListener.reply(svc, n.key, msg)
    log("notification reply -> [${n.app}] ${n.title.take(20)}: " + (err ?: "भेज दिया"))
    return if (err == null) "Sent to ${n.title.ifBlank { n.app }} on ${n.app}: \"$msg\""
    else "Not sent: $err. Offer to send it via phone_task (open ${n.app})."
}

/**
 * v3.5+: एजेंट वाले tool (कहानी / वेबसाइट / DK कंप्यूटर / कोडिंग) एक जगह - Gemini के tool call से भी और
 * [agentCheck] से भी (Gemini ने "हाँ कर दिया" कहा पर tool नहीं बुलाया). काम सच में सौंपा गया = उस एजेंट की स्क्रीन
 * खुलती है और वहाँ "🎤 DK लाइव से मिला काम" दिखता है.
 */
internal fun GeminiLive.agentTool(name: String, args: JSONObject): String {
    val agent = LiveAgentRoute.byTool(name) ?: return "unknown function"
    // नवंबर 2026 (मालिक): एक key = सारे काम. रुकता सिर्फ़ तब जब एक-key मोड में key डाली ही नहीं - DK साफ़ बताए + Settings खुले (झूठा "हो गया" नहीं)
    OneKeyGate.LIVE_TOOLS[name]?.takeIf { OneKeyGate.blocked(svc, it) }
        ?.takeIf { !(name == "build_website" && Prefs.forgeKey(svc).isNotBlank()) }?.let { w -> // वेबसाइट की अपनी key = एक-key मोड में भी
        log("एक key मोड: key नहीं डली, ${w.hi} नहीं चलेगा - Settings खोली"); OneKeyGate.openSettings(svc)
        return OneKeyGate.liveNote(w)
    }
    var handoff = true          // false = सिर्फ़ हाँ/नहीं/इजाज़त का जवाब (नया काम नहीं)
    val task = when (name) { "story_task" -> args.optString("topic"); "build_website" -> args.optString("prompt"); else -> args.optString("task") }.trim()
    val result = when (name) {
        "story_task" -> LiveReport.startStory(svc, args.optString("topic"), args.optInt("minutes", 0))
        // नवंबर 2026: पहले ब्रीफ़ (किस काम की / नाम / सेक्शन / स्टाइल / संपर्क) - अधूरा = मॉडल पूछे, Forge अभी नहीं
        "build_website" -> com.dk.assistant.forge.ForgeBrief.liveGate(args)?.also { handoff = false }
            ?: com.dk.assistant.forge.ForgeLauncher.fromVoice(svc, com.dk.assistant.forge.ForgeBrief.fullPrompt(args))
        "computer_task" -> {
            val t = args.optString("task").trim()
            val yn = if (ComputerSession.ask != null) ComputerVoice.yesNo(t) else null
            when {
                yn != null -> { handoff = false; ComputerSession.answer(yn); if (yn) "Permission given - DK Computer continues." else "Permission refused - DK Computer will not do it." }
                !Prefs.computerOn(svc) -> "DK Computer is OFF in settings - tell the user to turn it on (सेटिंग्स → DK कंप्यूटर)."
                t.length < 3 -> "Ask the user what DK Computer should do."
                // नवंबर 2026: ऐप स्टूडियो DK कंप्यूटर से जुड़ा - "स्टूडियो में क्या बन रहा है" / "ऐप बनाओ" = स्टूडियो
                else -> com.dk.assistant.studio.StudioLink.fromLive(svc, t, com.dk.assistant.studio.StudioSession.WHO_COMPUTER, noGithub = true)?.also { handoff = false } ?: run {
                    val wasBusy = ComputerSession.busy
                    ComputerSession.start(svc, t, voice = true)?.let { "Could not start: $it" }
                        ?: if (wasBusy) "Queued - DK Computer will start it after the current task."
                        else "Started. DK Computer shows each step on its screen and will notify when done (result also in the notification)."
                }
            }
        }
        "coding_task" -> {
            val t = args.optString("task")
            // pehle setting jaancho - band ho to merge/sujhav ka faisla bhi nahi (AgentRunner jaisa)
            when {
                !Prefs.coderOn(svc) -> "Coding agent is OFF in settings - tell the user to turn it on (सेटिंग्स → कोडिंग एजेंट)."
                else -> CoderTeam.voiceDecision(svc, t)?.also { handoff = false }
                    // नवंबर 2026: ऐप स्टूडियो कोडिंग एजेंट से जुड़ा (GitHub नहीं जुड़ा = फ़ोन पर स्टूडियो में, CodingActivity जैसा)
                    ?: com.dk.assistant.studio.StudioLink.fromLive(svc, t, com.dk.assistant.studio.StudioSession.WHO_CODER,
                        com.dk.assistant.studio.StudioRoute.noGithub(svc))?.also { handoff = false } ?: when {
                    // 7-एजेंट चालू = पुराना टीम switch ज़रूरी नहीं
                    !Prefs.coderSeven(svc) && !Prefs.coderTeam(svc) -> "Coding team is OFF in settings (सेटिंग्स → कोडिंग एजेंट → टीम)."
                    else -> CoderTeam.start(svc, t, fromVoice = true)?.let { "Could not start: $it" }
                        ?: if (Prefs.coderSeven(svc)) "Started (7-agent team + Explainer). FIRST its manager shows the user what it understood and WAITS: when the user confirms (हाँ / yes), call this tool again with task='हाँ'; if the user corrects or answers its question, call this tool again with exactly that answer; 'रद्द' cancels. It is NOT done yet - real progress: call task_status. You will get a SYSTEM NOTE when it really finishes; never say it is done before that."
                        else "Started. Builder writes, Reviewer checks, Coordinator pushes and will notify when done. It is NOT done yet - real progress: call task_status."
                }
            }
        }
        else -> "unknown function"
    }
    if (handoff && LiveAgentRoute.started(result)) {
        AgentHandoff.mark(agent, task, result)
        if (agent == LiveAgentRoute.Agent.CODING || agent == LiveAgentRoute.Agent.COMPUTER) LiveReport.openAgent(svc, agent)
        log("एजेंट को काम दिया: ${agent.title} - स्क्रीन पर दिख रहा है")
    }
    return result
}

internal fun GeminiLive.runTools(calls: JSONArray) {
    for (i in 0 until calls.length()) {
        val fc = calls.optJSONObject(i) ?: continue
        val id = fc.optString("id"); val callName = fc.optString("name")
        // नवंबर 2026 (Live जाँच #2): dk_agent(tool=..., args_json=...) = वही पुराना tool. जवाब असली call के नाम (callName) से
        val (name, args) = LiveToolSlim.unwrap(callName, fc.optJSONObject("args") ?: JSONObject())
        if (callName != name) log("dk_agent -> $name")
        if (!toolDedup.firstTime(id)) { log("tool: $name - यही call (id) पहले आ चुका, दोबारा नहीं"); continue }
        // DK AI Part 12: caption / संदेश / कॉन्टैक्ट log में नहीं
        log("tool: $name " + if (name == "social_media_task" || name == "whatsapp_task") args.optString("action") else if (name == "connector_task") args.optString("connector") + "." + args.optString("action") else args.toString().take(80))
        val tStart = System.currentTimeMillis()
        if (name in LiveAgentRoute.TOOLS) agentToolAt = tStart      // v3.5+: इस बात पर एजेंट का tool सच में बुलाया
        // v3.39: जिस जुड़ाव पर सवाल आया, जवाब सिर्फ़ उसी पर. बीच में दोबारा जुड़ा = पुराना id नए सत्र को अनजान -> 1007
        val sock = ws
        // LAZY: pehle map me darj, phir shuru - warna tez tool (remember_fact) map me aane se pehle hi remove() kar deta tha
        val j = svc.scope.launch(start = CoroutineStart.LAZY) {
          // v3.75: screen पर दिखे कि DK अभी क्या कर रहा है (सर्च / सोच / फ़ोन का काम)
          LivePromise.hintFor(name, args.optString("query").ifBlank { args.optString("request") }).takeIf { it.isNotEmpty() }?.let { DKState.hint(it) }
          // नवंबर 2026: सोशल रिव्यू चल रहा और यह काम स्क्रीन चलाएगा = रिव्यू रुककर इंतज़ार, यह खत्म होते ही अपने-आप आगे
          val held = com.dk.assistant.insights.InsightsAgent.holdFor(name, args)
          try {
            val result = try { when (name) {
                "phone_task" -> phoneTask(args.optString("request"))
                "task_status" -> TaskStatus.summary()
                "look_screen" -> ScreenLook.liveTool(svc, args.optString("question").ifBlank { DKState.lastUser.trimEnd('…') })
                "app_studio" -> com.dk.assistant.studio.StudioLink.liveTool(svc, args.optString("action"), args.optString("text"))
                "phone_call" -> callTool(args.optString("action"), "Gemini")
                "screen_share" -> LiveVision.setScreen(svc, args.optBoolean("on", true))
                "camera" -> LiveVision.setCamera(svc, args.optBoolean("on", true), args.optString("facing"))
                "screen_guard" -> Guard.liveTool(svc, args.optInt("minutes", 0))
                "dk_features" -> LiveConnect.features(com.dk.assistant.auto.AutoPrefs.on(svc),
                    com.dk.assistant.auto.AutoPrefs.enabledKinds(svc).map { it.title }, Prefs.oneKeyOn(svc) && Prefs.oneKey(svc).isNotBlank())
                "story_task", "build_website", "computer_task", "coding_task" -> agentTool(name, args)
                "website_action" -> com.dk.assistant.forge.ForgeVoice.run(svc, args)
                "make_game" -> com.dk.assistant.forge.GameMaker.start(svc, args.optString("description"), voice = true, change = args.optBoolean("change", false))
                "teacher_mode" -> LiveReport.teacher(svc, args.optBoolean("on", true))
                "dk_settings" -> DkSettingsApply.run(svc, args)
                "dk_inspector" -> com.dk.assistant.inspector.Inspector.liveTool(svc, args)
                "remember_fact" -> if (Memory.addFact(svc, args.optString("fact"))) "saved" else "NOT saved: private info (passwords, PIN, OTP, bank/card/Aadhaar numbers) is never stored. If the user asked you to remember it, tell them gently you do not save such private details." 
                "recall_conversation" -> {
                    val a = LocalMemory.answer(svc, args.optString("question").ifBlank { DKState.lastUser.trimEnd('…') })
                    if (a == LocalRecall.NOT_FOUND) "NOT FOUND in the phone's saved conversation. Say exactly: बॉस, मुझे ये याद नहीं मिल रहा।"
                    else "FOUND on the phone (saved conversation). Tell the user this briefly, in your voice, keep the quoted words exact:\n$a"
                }
                "forget_memory" -> { val n = Memory.forget(svc, args.optString("about")); if (n > 0) "Forgotten ($n place(s) deleted)." else "Nothing saved about that - tell the user it is not in memory." }
                "local_info" -> localInfoTool(args.optString("find"))
                "reel_stats" -> withContext(Dispatchers.IO) { ReelDaily.liveTool(svc) }   // नवंबर 2026: रील का 7 दिन का हिसाब
                "connector_task" -> withContext(Dispatchers.IO) { com.dk.assistant.connect.ConnectorTool.run(svc, args) }
                "notifications" -> notifTool(args.optString("app"))
                "notification_reply" -> notifReply(args.optString("to"), args.optString("message"))
                "social_media_task" -> com.dk.assistant.agent.android.SocialAgent.start(svc, args)
                // v3.152: असली ChatGPT / Gemini ऐप में इमेज - बनाओ, डाउनलोड, और सच में देखकर बताओ
                // नवंबर 2026: सोशल रिव्यू (यूज़र के सामने स्क्रीन पर; नतीजा SYSTEM NOTE से)
                "social_insights" -> com.dk.assistant.insights.InsightsAgent.liveTool(svc, args)
                "image_task" -> {
                    DKState.set(Mode.WORKING)
                    withTimeoutOrNull(IMAGE_TASK_MS) { com.dk.assistant.agent.android.ImageGenAgent.run(svc, args) }
                        ?: "ERROR: the image app took more than ${IMAGE_TASK_MS / 1000} seconds. Tell the user it is still being made in the app and he can check it there."
                }
                // v3.156: ChatGPT / Gemini ऐप में कुछ भी लिखकर भेजो (इमेज नहीं) और जवाब पढ़ो
                "ai_app_prompt" -> {
                    DKState.set(Mode.WORKING)
                    withTimeoutOrNull(IMAGE_TASK_MS) { com.dk.assistant.agent.android.ImageGenAgent.ask(svc, args) }
                        ?: "SENT: the app took too long to answer. Tell the user it is sent and the answer is in the app."
                }
                // नवंबर 2026: किसी भी AI ऐप से DK की खुद की लंबी बातचीत / मुकाबला / सीखना (स्क्रीन पर, पीछे से नतीजा SYSTEM NOTE)
                "ai_talk" -> com.dk.assistant.agent.android.AiTalkAgent.start(svc, args)
                // v3.156: "मुझे शर्ट देखनी है" - फ़ोन में जो shopping ऐप हैं उनमें से (पूछकर) उसी चीज़ को खोजो
                "shop_search" -> com.dk.assistant.ShopOpen.tool(svc, args)
                // नवंबर 2026 (मालिक): फ़ोन लॉक हो तब भी - पहले खोलो (PIN सेव हो तो खुद), काम के बाद फिर लॉक
                "whatsapp_task" -> com.dk.assistant.agent.android.LockedWork.wa(svc, args.optString("action")) { when (val act = args.optString("action").trim().lowercase()) {
                    // नवंबर 2026 (मालिक: "WhatsApp खोल के ही बताएगा"): स्क्रीन पढ़कर; न पढ़ पाए तो पहले जैसा notification से
                    in setOf("read_unread", "unread", "read", "screen_chats", "chats", "screen") -> {
                        DKState.set(Mode.WORKING)
                        com.dk.assistant.agent.android.WaLook.screenChats(svc).takeUnless { it.startsWith("FAILED") }
                            ?: notifTool("WhatsApp")
                    }
                    in setOf("open_chat", "read_chat_screen") -> { DKState.set(Mode.WORKING); com.dk.assistant.agent.android.WaLook.openChat(svc, args.optString("contact")) }
                    in setOf("smart_reply", "reply_for_me", "reply") -> { DKState.set(Mode.WORKING); com.dk.assistant.agent.android.WaLook.smartReply(svc, args.optString("contact"), args.optString("message")) }
                    in setOf("auto_chat_on", "auto_chat", "auto_reply_on") -> com.dk.assistant.agent.android.WaAutoChat.startWithConfirm(args.optString("contact"), args.optInt("minutes", 0), args.optString("message"))
                    in setOf("auto_chat_off", "auto_reply_off") -> com.dk.assistant.agent.android.WaAutoChat.off()
                    // नवंबर 2026 (PR #281 की कमी): "हाँ" के बाद चालू - पहले confirmOk() कहीं से बुलाया ही नहीं जाता था
                    in setOf("auto_chat_confirm", "auto_chat_yes") -> com.dk.assistant.agent.android.WaAutoChat.confirmTool()
                    in setOf("auto_chat_cancel", "auto_chat_no") -> com.dk.assistant.agent.android.WaAutoChat.cancelTool()
                    // नवंबर 2026 (मालिक): चैटिंग मोड - पहले WhatsApp खोलकर कौन-कौन लिख रहा, फिर हर मैसेज पर बॉस से पूछो
                    in setOf("chat_mode_on", "chatting_mode_on", "whatsapp_mode_on") -> {
                        DKState.set(Mode.WORKING)
                        val chats = com.dk.assistant.agent.android.WaLook.screenChats(svc).takeUnless { it.startsWith("FAILED") } ?: ""
                        com.dk.assistant.agent.android.WaAutoChat.chatOn(args.optInt("minutes", 0), chats)
                    }
                    in setOf("chat_mode_off", "chatting_mode_off", "whatsapp_mode_off") -> com.dk.assistant.agent.android.WaAutoChat.chatOff()
                    "chat_pick" -> com.dk.assistant.agent.android.WaAutoChat.chatPick(args.optString("contact"))
                    "chat_self" -> com.dk.assistant.agent.android.WaAutoChat.chatSelf(svc, args.optString("contact"))
                    "chat_boss" -> com.dk.assistant.agent.android.WaAutoChat.chatBoss(args.optString("contact"))
                    "chat_reply" -> { waHinglish(args); com.dk.assistant.agent.android.WaAutoChat.chatReply(svc, args.optString("contact"), args.optString("message")) }
                    // नवंबर 2026 (मालिक): लोकेशन / लाइव लोकेशन / कैमरा फ़ोटो / किसी का नंबर
                    in setOf("send_location", "current_location") -> waLocation(args)
                    "send_live_location" -> { DKState.set(Mode.WORKING); com.dk.assistant.agent.android.WaLook.uiSteps(svc, args.optString("contact"), "लाइव लोकेशन", com.dk.assistant.agent.social.WaShare.LIVE_LOCATION_STEPS) }
                    in setOf("send_camera_photo", "camera_photo") -> { DKState.set(Mode.WORKING); com.dk.assistant.agent.android.WaLook.uiSteps(svc, args.optString("contact"), "कैमरा से खींची फ़ोटो", com.dk.assistant.agent.social.WaShare.CAMERA_STEPS) }
                    "send_contact" -> waContact(args)
                    // नवंबर 2026: भेजा हुआ (गलती वाला) मैसेज सबके लिए डिलीट - मालिक का कहना ही हुक्म, सीधे
                    in setOf("delete_message", "delete", "unsend", "delete_last") -> {
                        DKState.set(Mode.WORKING)
                        val r = com.dk.assistant.agent.android.WaDeleteRunner.run(svc, DKState.lastUser, args.optString("contact"),
                            if (args.has("for_me")) args.optBoolean("for_me") else null, args.optString("message"))
                        log("WhatsApp भेजा मैसेज डिलीट -> ${r.substringBefore(':')}")
                        r
                    }
                    // v4: WhatsApp आवाज़ / वीडियो कॉल - सीधे (स्क्रीन पर बटन ढूँढे बिना)
                    in setOf("voice_call", "video_call", "call", "audio_call") -> {
                        val cmd = WaCall.Cmd(args.optString("contact").trim(), act == "video_call")
                        DKState.set(Mode.WORKING)
                        val r = WaCall.call(svc, cmd.name, cmd.video)
                        log("WhatsApp ${if (cmd.video) "वीडियो " else ""}कॉल \"${cmd.name}\" -> ${r.substringBefore(':')}")
                        LiveFast.quickResult(QuickCommands.Quick(WaCall.toJson(cmd), ""), r, already = false)
                    }
                    // v3.151: पहले बताया नाम/संदेश बातचीत से भरो - दोबारा न पूछे
                    in setOf("send_message", "send", "message") -> {
                        val f = MsgDraft.applyTo(args, MsgDraft.turnsFromState(), System.currentTimeMillis())
                        if (f.fromHistory) log("WhatsApp: बातचीत से भरा - नाम \"${f.contact}\", संदेश ${f.message.length} अक्षर")
                        MsgDraft.missingNote(f) ?: run {
                            waHinglish(args)
                            com.dk.assistant.agent.android.SocialAgent.start(svc, args, com.dk.assistant.agent.social.Platform.WHATSAPP)
                        }
                    }
                    else -> com.dk.assistant.agent.android.SocialAgent.start(svc, args, com.dk.assistant.agent.social.Platform.WHATSAPP)
                } }
                "social_media_task_control" -> com.dk.assistant.agent.android.SocialAgent.control(args)
                "web_search" -> webSearchTool(args.optString("query"))
                "current_time" -> { NowClock.syncAsync(); NowClock.toolAnswer() }
                "deep_think" -> {
                    DKState.set(Mode.THINKING)
                    LiveTextBrain.answer(svc, args.optString("question")) ?: try {
                        withTimeoutOrNull(45_000) { BrainManager(svc).also { it.fromLive = true }.think(args.optString("question")) }
                            ?.takeIf { it.text.isNotBlank() }
                            ?.also { if (it.sources.isNotEmpty()) toolSources = Grounding.merge(toolSources, it.sources) }
                            ?.text ?: "Deep thinking timed out - answer it yourself carefully."
                    } catch (e: Exception) {
                        log("deep_think fail: ${e.message}")
                        "Deep thinking unavailable - answer it yourself carefully."
                    }
                }
                else -> "unknown function"
            } } catch (e: CancellationException) { throw e } catch (e: Exception) {
                // tool toota - phir bhi Gemini ko jawab bhejo, warna model hamesha intezaar karta
                log("tool $name fail: ${e.message}")
                "Tool failed (${e.javaClass.simpleName}) - tell the user it could not be done."
            }
            val resp = JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(
                JSONObject().put("id", id).put("name", callName).put("response", JSONObject().put("result", result.take(if (name == "deep_think" || name == "connector_task" || name == "web_search") 4000 else 1500) + (if (held) com.dk.assistant.insights.InsightsAgent.HOLD_NOTE else ""))))))
            log("tool $name ${(System.currentTimeMillis() - tStart)} ms: ${result.take(if (name == "connector_task") 24 else 80)}")
            if (name != "remember_fact") lastEvidenceAt = System.currentTimeMillis()
            // v3.67: नतीजा "अभी हुआ नहीं" -> इसके बाद Gemini "हो गया" बोले तो वह नहीं बजेगा
            if (name != "remember_fact" && name != "task_status" && DoneGate.notDoneYet(result)) actPending = true
            // ab se deri ginti: tool ka jawab bheja -> pehli awaaz (tool ka samay alag dikhta hai)
            eosAt = System.currentTimeMillis(); firstAudioAt = 0
            val cur = ws
            if (id.startsWith(LEAK_ID)) {
                // v4.1: यह call Live ने लिखी थी (असली toolCall नहीं) - server को इसका id पता नहीं, इसलिए नतीजा SYSTEM NOTE में
                visionNote(leakNote(name, result), evidence = !DoneGate.notDoneYet(result))
            } else if (cur != null && cur === sock) {
                if (!cur.tx(resp.toString())) log("tool जवाब नहीं भेज पाया")
                else if (name in ANSWER_TOOLS) watchToolAnswer(name, result, cur)
            }
            else {
                log("tool $name का जवाब पुराने जुड़ाव का - नए जुड़ाव पर tool जवाब नहीं भेजा (1007 से बचाव)")
                if (cur != null && ready && name != "task_status" && name != "remember_fact") lastEvidenceAt = System.currentTimeMillis()
                if (cur != null && ready && name != "task_status" && name != "remember_fact") try {
                    cur.tx(JSONObject().put("realtimeInput", JSONObject().put("text",
                        "SYSTEM NOTE: the connection was renewed while the user's earlier request ($name) was running. Its result: \"" +
                            result.take(600) + "\". Tell the user briefly only if it is still relevant. Do not invent details.")).toString())
                } catch (_: Exception) {}
            }
          } finally {
            if (held) com.dk.assistant.insights.InsightsAgent.releaseHold()
            // hamesha hatao (fail/cancel par bhi) - warna idle-exit aur watchdog band rehte
            coroutineContext[Job]?.let { me -> toolJobs.remove(id, me) }
            if (toolJobs.isEmpty() && promiseJob?.isActive != true) { DKState.hint(""); DKState.refresh() }
          }
        }
        toolJobs[id] = j
        DKState.refresh()       // v3.90: अब सच में काम चल रहा है = "Thinking" दिखे
        j.start()
    }
}

/**
 * v3.80: नतीजा (खोज / सोच / समय) भेज दिया पर Gemini चुप रह गया = यूज़र "सुनकर रह गया" वाली हालत.
 * [TOOL_ANSWER_MS] तक कुछ न आए (आवाज़/लिखावट/यूज़र की बात) -> एक बार याद दिलाओ कि अब जवाब बोलो.
 */
internal fun GeminiLive.watchToolAnswer(name: String, result: String, sock: WebSocket) {
    val sentAt = System.currentTimeMillis()
    svc.scope.launch {
        delay(TOOL_ANSWER_MS)
        if (stopped || !ready || ws !== sock) return@launch
        if (lastServerAt > sentAt || lastAct > sentAt || generating || isPlaying() || toolJobs.isNotEmpty()) return@launch
        log("tool $name का नतीजा ${TOOL_ANSWER_MS / 1000}s से बोला नहीं गया - Gemini को याद दिलाया")
        DKState.hint("जवाब तैयार कर रहा है…")
        if (DKState.mode != Mode.WORKING) setMode(Mode.THINKING)
        try {
            sock.tx(JSONObject().put("realtimeInput", JSONObject().put("text",
                "SYSTEM NOTE: you already received the result of $name for the user's last question but have not answered yet. " +
                    "Answer the user NOW, briefly and naturally in Hindi, from this result: \"" + result.take(900) + "\"")).toString())
        } catch (_: Exception) {}
        delay(10_000)
        if (toolJobs.isEmpty() && promiseJob?.isActive != true && !generating) DKState.hint("")
    }
}

/** किसी और की आवाज़ पर Gemini ने फ़ोन का काम माँगा - कुछ मत करो, सिर्फ़ जवाब दो कि अनदेखा करे */
internal fun GeminiLive.ignoreTools(calls: JSONArray) {
    val arr = JSONArray()
    for (i in 0 until calls.length()) {
        val fc = calls.optJSONObject(i) ?: continue
        arr.put(JSONObject().put("id", fc.optString("id")).put("name", fc.optString("name"))
            .put("response", JSONObject().put("result", "IGNORED: that voice was not the owner's. Do nothing, say nothing, wait for the owner.")))
    }
    log("किसी और की आवाज़ पर काम माँगा (${calls.length()}) - कुछ नहीं किया")
    try { ws?.tx(JSONObject().put("toolResponse", JSONObject().put("functionResponses", arr)).toString()) } catch (_: Exception) {}
}
