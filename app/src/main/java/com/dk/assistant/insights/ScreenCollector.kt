package com.dk.assistant.insights

import com.dk.assistant.agent.core.BlockerDetector
import com.dk.assistant.agent.core.DriverResult
import com.dk.assistant.agent.core.Expectation
import com.dk.assistant.agent.core.ScreenDriver
import com.dk.assistant.agent.core.ScreenObservation
import com.dk.assistant.agent.core.UiElement
import org.json.JSONArray
import org.json.JSONObject

/**
 * स्क्रीन वाला तरीका: DK खुद ऐप खोलकर (आपके फ़ोन में पहले से लॉग-इन) डैशबोर्ड / Insights / YouTube Studio पढ़ती है।
 *
 * हर कदम: स्क्रीन के सारे लिखे हिस्से (+ अगर इजाज़त है तो स्क्रीनशॉट) AI को -> AI बताता है
 *  (1) इस स्क्रीन पर कौन-से आँकड़े दिखे (record) और (2) आगे क्या दबाना / स्क्रॉल करना है (actions)।
 * नंबर हमेशा स्क्रीन से ही - AI को साफ़ मना है कि अंदाज़ा लगाए।
 *
 * Android के बिना (ScreenDriver + brain नकली देकर unit test में चलता है)।
 */
class ScreenCollector(
    private val driver: ScreenDriver,
    /** ऐप खोलो (पैकेज) - खुला = true */
    private val openApp: suspend (String) -> Boolean,
    /** ऐप फ़ोन में है? */
    private val installed: (String) -> Boolean,
    /** prompt (+ स्क्रीनशॉट अपने आप, अगर इजाज़त) -> AI का JSON जवाब */
    private val brain: suspend (String) -> String?,
    private val log: (String) -> Unit = {},
    /** हर प्लेटफ़ॉर्म पर ज़्यादा से ज़्यादा कदम (हर कदम = एक AI जवाब) */
    private val maxSteps: Int = InsightsSpeed.MAX_STEPS,
    private val cancelled: () -> Boolean = { false },
    private val progress: (String) -> Unit = {},
    /** नवंबर 2026: दबाने से ठीक पहले उस जगह स्क्रीन पर निशान (x, y) - मालिक देखे DK कहाँ दबा रहा है */
    private val mark: (Int, Int) -> Unit = { _, _ -> },
) {
    /** कितनी रील/वीडियो के अंदर जाकर पूरी Insights देखें (मालिक: "रिसेंटली में तीन-चार") */
    var detailTarget = 4
    /** सूची में कम से कम इतने आइटम (तुलना के लिए) */
    var listTarget = 8

    suspend fun collect(p: Platform): PlatformData {
        val pkg = p.pkgs.firstOrNull { installed(it) }
            ?: return PlatformData(p, problems = listOf("${p.hi} ऐप फ़ोन में नहीं मिला" + if (p == Platform.YOUTUBE) " (YouTube Studio ऐप डालें तो पूरे आँकड़े मिलेंगे)" else ""))
        progress("${p.hi} ऐप आपके सामने खोल रही हूँ")
        if (!openApp(pkg)) return PlatformData(p, problems = listOf("${p.hi} ऐप नहीं खुला"))
        progress("${p.hi} खुल गया - अब अंदर आँकड़े ढूँढ रही हूँ")
        driver.pause(InsightsSpeed.OPEN_SETTLE_MS)
        val studio = pkg == "com.google.android.apps.youtube.creator"
        var data = PlatformData(p, sources = listOf("स्क्रीन (${if (studio) "YouTube Studio" else p.en} ऐप)"))
        val detailed = LinkedHashSet<String>()
        val history = ArrayDeque<String>()
        var lastSig = ""
        var same = 0
        var nulls = 0
        for (step in 1..maxSteps) {
            if (cancelled()) { data = data.copy(problems = data.problems + "बीच में रोका गया"); break }
            val obs = driver.capture()
            if (obs == null) { driver.pause(800); continue }
            BlockerDetector.detect(obs)?.let { b ->
                log("Insights ${p.en}: रुकावट ${b.name}")
                return data.copy(problems = data.problems + b.hi)
            }
            // दूसरे ऐप में चला गया (गलती से लिंक / विज्ञापन) = वापस
            if (obs.pkg.isNotBlank() && obs.pkg != pkg && obs.pkg !in SYSTEM_OK) {
                log("Insights ${p.en}: दूसरा ऐप ${obs.pkg} - वापस")
                driver.back(); driver.pause(900)
                if (step > 2 && driver.capture()?.pkg != pkg) { openApp(pkg); driver.pause(1_500) }
                continue
            }
            val sig = Expectation.signature(obs)
            same = if (sig == lastSig) same + 1 else 0
            lastSig = sig
            val prompt = InsightsPlan.prompt(p, studio, obs, data, detailed, history.toList(), step, maxSteps, detailTarget, listTarget, same)
            val reply = InsightsPlan.parseReply(brain(prompt))
            if (reply == null) {
                nulls++
                log("Insights ${p.en}: AI का जवाब नहीं समझा ($nulls)")
                if (nulls >= 3) { data = data.copy(problems = data.problems + "AI से जवाब नहीं मिला"); break }
                continue
            }
            nulls = 0
            // आँकड़े जोड़ो
            val got = PlatformData(p, reply.posts.mapNotNull { Post.fromJson(p, it) }, Account.fromJson(reply.account), reply.statusChecked)
            if (!got.empty || got.statusChecked) data = data.merge(got)
            reply.detailOf?.let { t -> if (t.isNotBlank()) detailed += Post.norm(t).take(36) }
            if (reply.posts.isNotEmpty() || reply.account != null) progress("${p.hi}: ${data.posts.size} ${p.itemHi} के आँकड़े मिले")
            // नवंबर 2026: AI ने स्क्रीन पर जो देखा (हिंदी में) - पट्टी पर, ताकि मालिक को दिखे DK ऐप के अंदर क्या कर रहा है
            if (reply.say.isNotBlank()) progress("${p.hi} • ${reply.say.take(70)}")
            // नवंबर 2026 (फटाफट): सब मिल गया = AI के done का इंतज़ार नहीं
            val overview = data.account.views != null || data.account.reach != null || data.account.watchTimeSec != null
            if (InsightsSpeed.enough(overview, data.posts.size, listTarget, detailed.size, detailTarget, data.statusChecked)) {
                log("Insights ${p.en}: सारे आँकड़े मिल गए (कदम $step) - आगे नहीं"); break
            }
            log("Insights ${p.en} कदम $step: +${reply.posts.size} आइटम, कुल ${data.posts.size}, अंदर देखे ${detailed.size}; ${reply.why.take(80)}")
            if (reply.done) break
            if (same >= 4) {
                // स्क्रीन बदल ही नहीं रही - एक बार पीछे, फिर भी नहीं = बस
                if (same >= 7) { data = data.copy(problems = data.problems + "स्क्रीन आगे नहीं बढ़ी"); break }
                driver.back(); settle(sig); history.addLast("back (stuck)"); continue
            }
            // कदम चलाओ (एक जवाब में ज़्यादा से ज़्यादा 3)
            var cur = sig
            for (a in reply.actions.take(3)) {
                progress("${p.hi} • कदम $step: " + InsightsPlan.actHi(a, obs))
                InsightsPlan.point(a, obs)?.let { (x, y) -> try { mark(x, y) } catch (_: Exception) {} }
                val r = run(obs, a)
                history.addLast(a.describe() + (if (r == DriverResult.OK) "" else " [failed]"))
                while (history.size > 8) history.removeFirst()
                // नवंबर 2026 (फटाफट): तय 1.1 सेकंड नहीं - स्क्रीन बदलते ही आगे
                if (a.kind == "wait") driver.pause(1_200) else cur = settle(cur)
                if (r != DriverResult.OK && a.kind == "click") break
            }
            if (reply.actions.isEmpty()) driver.pause(500)
        }
        return data
    }

    /** टैप/स्क्रॉल के बाद: स्क्रीन बदलते ही (+ थोड़ा रुककर) लौटो, ज़्यादा से ज़्यादा [InsightsSpeed.SETTLE_MAX_MS] */
    private suspend fun settle(before: String): String {
        var waited = 0L
        while (waited < InsightsSpeed.SETTLE_MAX_MS) {
            driver.pause(InsightsSpeed.POLL_MS); waited += InsightsSpeed.POLL_MS
            val now = driver.capture() ?: continue
            val sig = Expectation.signature(now)
            if (sig != before) { driver.pause(InsightsSpeed.SETTLE_AFTER_MS); return sig }
        }
        return before
    }

    private suspend fun run(obs: ScreenObservation, a: InsightsPlan.Act): DriverResult = when (a.kind) {
        "click" -> {
            val e: UiElement? = obs.elements.firstOrNull { it.id == a.id }
            when {
                e != null -> driver.click(obs, e).let { r -> if (r == DriverResult.OK) r else driver.tapAt(e.bounds.centerX, e.bounds.centerY) }
                a.x != null && a.y != null -> driver.tapAt(a.x, a.y)
                else -> DriverResult.NOT_FOUND
            }
        }
        "tap" -> if (a.x != null && a.y != null) driver.tapAt(a.x, a.y) else DriverResult.NOT_FOUND
        "scroll" -> if (a.dir == "up") { driver.scroll(false) } else driver.scroll(true)
        "back" -> driver.back()
        "wait" -> DriverResult.OK
        else -> DriverResult.UNSUPPORTED
    }

    companion object {
        /** ये ऊपर आ जाएँ तो ठीक (कीबोर्ड / सिस्टम / DK खुद) */
        val SYSTEM_OK = setOf("com.android.systemui", "com.google.android.inputmethod.latin", "com.dk.assistant", "android")
    }
}

/** हर प्लेटफ़ॉर्म पर कहाँ क्या मिलता है + AI के लिए prompt + उसके जवाब की पढ़ाई */
object InsightsPlan {

    data class Act(val kind: String, val id: Int? = null, val x: Int? = null, val y: Int? = null, val dir: String = "down") {
        fun describe() = when (kind) { "click" -> "click #$id"; "tap" -> "tap $x,$y"; "scroll" -> "scroll $dir"; else -> kind }
    }

    /** पट्टी पर दिखने वाला हिंदी कदम: किस बटन/लिखे हिस्से पर दबा रही है, स्क्रॉल, वापस */
    fun actHi(a: Act, obs: ScreenObservation?): String = when (a.kind) {
        "click" -> {
            val e = obs?.elements?.firstOrNull { it.id == a.id }
            val name = (e?.text?.takeIf { it.isNotBlank() } ?: e?.desc.orEmpty()).replace(Regex("\\s+"), " ").trim().take(40)
            if (name.isNotBlank()) "\"$name\" दबा रही हूँ" else "स्क्रीन पर एक बटन दबा रही हूँ"
        }
        "tap" -> "स्क्रीन पर दबा रही हूँ"
        "scroll" -> if (a.dir == "up") "ऊपर स्क्रॉल कर रही हूँ" else "नीचे स्क्रॉल करके और देख रही हूँ"
        "back" -> "वापस पिछली स्क्रीन पर जा रही हूँ"
        "wait" -> "स्क्रीन खुलने का इंतज़ार"
        else -> "अगला कदम"
    }

    /** स्क्रीन पर निशान की जगह (दबाने वाले कदम पर ही) */
    fun point(a: Act, obs: ScreenObservation?): Pair<Int, Int>? = when (a.kind) {
        "click" -> obs?.elements?.firstOrNull { it.id == a.id }?.let { it.bounds.centerX to it.bounds.centerY } ?: if (a.x != null && a.y != null) a.x to a.y else null
        "tap" -> if (a.x != null && a.y != null) a.x to a.y else null
        else -> null
    }

    data class Reply(
        val posts: List<JSONObject>,
        val account: JSONObject?,
        val statusChecked: Boolean,
        val detailOf: String?,
        val actions: List<Act>,
        val done: Boolean,
        val why: String,
        /** नवंबर 2026: AI का छोटा हिंदी वाक्य - अभी स्क्रीन पर क्या देख/कर रही है (पट्टी के लिए) */
        val say: String = "",
    )

    /** ऐप के अंदर रास्ता (2026 के UI पर; बदला हो तो AI स्क्रीन देखकर ढूँढे) */
    fun guide(p: Platform, studio: Boolean): String = when (p) {
        Platform.INSTAGRAM -> """
            INSTAGRAM (creator/business account):
            1) OVERVIEW: tap the Profile tab (bottom-right avatar) -> "Professional dashboard" (or "Insights" button on profile) -> read the overview
               for the last 30 days: Views, Accounts reached / Reach, Interactions, Followers, net follows and the % change vs previous period.
            2) CONTENT LIST: in the dashboard open "Content you shared" / "Top content" / Reels and choose "Views" as sort if possible; read every
               reel visible with its view count and date; scroll to see more. Alternative: on the profile, the Reels tab shows view counts on each tile.
            3) DETAILS: open the most recent 3-4 reels one by one -> "View insights" (or the ... menu -> Insights) -> read Views, Reach / Accounts reached,
               Watch time, Average watch time, Skip rate, Likes, Comments, Shares, Saves, Follows, and "Where your views came from" (Feed/Explore/Reels/Profile).
               Then press back to return.
            4) ACCOUNT STATUS: Profile -> menu (≡) -> "Account status" (or Settings and activity -> Account status / "What can't be recommended").
               Record whatever it says (e.g. "No issues" or which content can't be recommended).
        """.trimIndent()
        Platform.FACEBOOK -> """
            FACEBOOK (profile in professional mode or a Page):
            1) OVERVIEW: open the Menu (≡ / profile picture) -> "Professional dashboard" (or switch to your Page -> "Professional dashboard" / "Insights").
               Read Views, Viewers / Reach, Content interactions, Net follows, and % change for the last 28 days.
            2) CONTENT LIST: dashboard -> "Content" / "Content performance" / Reels -> read each reel/post with Views/Reach and date; scroll for more.
            3) DETAILS: open the most recent 3-4 reels/posts -> "See insights" / "View insights" -> read Views, Reach, 3-second views, Minutes viewed,
               Average watch time, Reactions, Comments, Shares, Saves, Follows, Distribution / traffic info. Then back.
            4) ACCOUNT STATUS: Professional dashboard -> "Account status" / "Monetization" / "Recommendations" -> record any warnings about recommendations,
               violations or copyright, or "no restrictions".
        """.trimIndent()
        Platform.YOUTUBE -> if (studio) """
            YOUTUBE STUDIO app:
            1) OVERVIEW: the Dashboard / Analytics tab -> "Overview": Views, Watch time (hours), Subscribers for the last 28 days and the change vs usual,
               "Realtime" views, "Latest video performance" ranking (e.g. "1 of 10").
            2) CONTENT LIST: Content tab -> Videos and Shorts -> read each item with views, date, likes/comments; scroll for more.
               Analytics -> Content tab also shows Impressions, Click-through rate (CTR), Average view duration.
            3) DETAILS: open the most recent 3-4 videos/shorts -> Analytics -> read Views, Watch time, Average view duration, Average percentage viewed,
               Impressions, CTR, Subscribers gained, "Shown in feed / Chose to view" (Shorts), Traffic sources ("How viewers find this"). Then back.
            4) ACCOUNT STATUS: Channel / Settings / Notifications for copyright claims, Community Guidelines strikes, monetization; record them or "no issues".
        """.trimIndent() else """
            YOUTUBE app (YouTube Studio not installed):
            1) Tap "You" (bottom-right) -> "Your videos" to see own videos with views and date; scroll for more.
            2) Open the most recent 3-4 items -> "Analytics" / view details if available; read views, likes, comments, watch time, avg view duration.
            3) If an "Open YouTube Studio" option is shown, do not install anything - just collect what is visible.
        """.trimIndent()
    }

    /** स्क्रीन -> AI के पढ़ने लायक सूची ([id] text | desc (बटन) @ऊपर से जगह) */
    fun screenText(obs: ScreenObservation, max: Int = 140): String = buildString {
        obs.elements.asSequence().filter { (it.text.isNotBlank() || it.desc.isNotBlank()) && it.bounds.bottom > 0 && it.bounds.top < obs.height }
            .take(max).forEach { e ->
                append('[').append(e.id).append("] ")
                append(e.text.replace(Regex("\\s+"), " ").take(90))
                if (e.desc.isNotBlank() && e.desc != e.text) append(" | ").append(e.desc.replace(Regex("\\s+"), " ").take(90))
                if (e.clickable) append(" (tap)")
                if (e.scrollable) append(" (scroll)")
                append(" @y=").append(((e.bounds.centerY.toDouble() / obs.height.coerceAtLeast(1)) * 100).toInt()).append("%\n")
            }
    }

    fun prompt(
        p: Platform, studio: Boolean, obs: ScreenObservation, data: PlatformData, detailed: Set<String>, history: List<String>,
        step: Int, maxSteps: Int, detailTarget: Int, listTarget: Int, sameScreen: Int,
    ): String = buildString {
        append("You are DK, an assistant controlling the user's own Android phone (they asked you to). Goal: collect the REAL analytics of their ")
        append(p.en).append(" account so DK can review why reach is up/down. You only READ - never post, delete, edit, like, follow, buy, boost, ")
        append("change settings or reply to anyone. Skip ads/promote/boost buttons.\n\n")
        append(guide(p, studio)).append("\n\n")
        append("PROGRESS (step ").append(step).append(" of ").append(maxSteps).append("):\n")
        append("- overview collected: ").append(if (data.account.views != null || data.account.reach != null || data.account.watchTimeSec != null) "YES" else "no").append('\n')
        append("- items in list collected: ").append(data.posts.size).append(" (want at least ").append(listTarget).append(")\n")
        append("- items opened for full insights: ").append(detailed.size).append(" (want ").append(detailTarget).append(")")
        if (detailed.isNotEmpty()) append(" already opened: ").append(detailed.joinToString("; ") { "\"$it\"" })
        append('\n')
        append("- account status checked: ").append(if (data.statusChecked) "YES" else "no").append('\n')
        if (data.posts.isNotEmpty()) append("- already recorded titles: ").append(data.posts.take(20).joinToString("; ") { "\"${it.title.take(30)}\"" }).append('\n')
        if (history.isNotEmpty()) append("- your last actions: ").append(history.joinToString(", ")).append('\n')
        if (sameScreen >= 2) append("- WARNING: the screen has not changed for ").append(sameScreen).append(" steps - your last action did nothing. Try something different (another element, scroll, or back).\n")
        if (step >= maxSteps - 3) append("- FEW STEPS LEFT: record what is visible and finish (done=true).\n")
        append("\nCURRENT SCREEN (package ").append(obs.pkg).append(", ids are tappable references):\n")
        append(screenText(obs))
        append("\nA screenshot of the same screen may also be attached.\n\n")
        append("Reply with ONLY a JSON object:\n")
        append("{\"posts\":[{\"title\":\"caption/title first words\",\"date\":\"as shown e.g. 3d / 2 Nov\",\"type\":\"reel|video|short|post\",")
        append("\"views\":\"\",\"reach\":\"\",\"likes\":\"\",\"comments\":\"\",\"shares\":\"\",\"saves\":\"\",\"follows\":\"\",\"impressions\":\"\",\"ctr\":\"\",")
        append("\"avg_watch\":\"\",\"watch_time\":\"\",\"retention\":\"\",\"skip_rate\":\"\",\"duration\":\"\",\"traffic\":\"\",\"notes\":\"\"}],\n")
        append(" \"account\":{\"period\":\"\",\"views\":\"\",\"reach\":\"\",\"interactions\":\"\",\"followers\":\"\",\"followers_change\":\"\",\"watch_time\":\"\",")
        append("\"views_change\":\"\",\"reach_change\":\"\",\"status\":\"\",\"notes\":\"\"} or null,\n")
        append(" \"status_checked\":false, \"detail_of\":\"title of the item whose full insights are on this screen, else empty\",\n")
        append(" \"actions\":[{\"do\":\"click\",\"id\":12} | {\"do\":\"scroll\",\"dir\":\"down|up\"} | {\"do\":\"back\"} | {\"do\":\"wait\"} | {\"do\":\"tap\",\"x\":540,\"y\":1200}],\n")
        append(" \"done\":false, \"why\":\"one short line: what you see and what you do next\",\n")
        append(" \"say\":\"the same in short simple Hindi for the user watching the screen (max 10 words), e.g. 'प्रोफ़ेशनल डैशबोर्ड खोल रही हूँ' / 'रील के व्यूज़ पढ़ रही हूँ'\"}\n")
        append("RULES: copy numbers EXACTLY as shown (\"1.2K\", \"12,345\", \"4.5%\", \"0:07\"); leave a field empty if not visible - never guess. ")
        append("Only include posts whose numbers are on THIS screen. Be FAST: use up to 3 actions per reply when they are safe in a row (e.g. click a tab then scroll), and go straight to the needed page. Set status_checked=true only when the account status page is visible. ")
        append("Set done=true when overview + enough list items + ").append(detailTarget).append(" detailed items + status are collected, or nothing more can be found.")
    }

    fun parseReply(raw: String?): Reply? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val a = s.indexOf('{'); val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        val o = try { JSONObject(s.substring(a, b + 1)) } catch (_: Exception) { return null }
        val posts = (o.optJSONArray("posts") ?: JSONArray()).let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }
        val acc = o.optJSONObject("account")?.takeIf { it.length() > 0 && it.keys().asSequence().any { k -> it.optString(k).isNotBlank() && it.optString(k) != "null" } }
        val acts = (o.optJSONArray("actions") ?: JSONArray()).let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val x = arr.optJSONObject(i) ?: return@mapNotNull null
                val kind = x.optString("do", x.optString("type")).lowercase().trim()
                when (kind) {
                    "click", "tap_id", "press" -> Act("click", id = x.optInt("id", -1).takeIf { it >= 0 }, x = x.optInt("x", -1).takeIf { it >= 0 }, y = x.optInt("y", -1).takeIf { it >= 0 })
                    "tap" -> if (x.has("id") && !x.has("x")) Act("click", id = x.optInt("id")) else Act("tap", x = x.optInt("x", -1).takeIf { it >= 0 }, y = x.optInt("y", -1).takeIf { it >= 0 })
                    "scroll", "swipe" -> Act("scroll", dir = if (x.optString("dir", x.optString("direction")).lowercase() == "up") "up" else "down")
                    "back" -> Act("back")
                    "wait" -> Act("wait")
                    else -> null
                }
            }
        }
        return Reply(posts, acc, o.optBoolean("status_checked", false), o.optString("detail_of").takeIf { it.isNotBlank() && it != "null" },
            acts, o.optBoolean("done", false), o.optString("why"), o.optString("say").takeIf { it != "null" }.orEmpty().trim())
    }
}
