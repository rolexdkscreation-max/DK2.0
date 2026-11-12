package com.dk.assistant.insights

import org.json.JSONArray
import org.json.JSONObject

/**
 * नवंबर 2026 (मालिक: "रिव्यू खोले तो पूरा चीज़ उसे दिखना चाहिए - मैं बाद में पूछूँगा कितने फ़ॉलोवर, रीच क्या है,
 * लास्ट वीडियो पे कितना व्यू गया, सबसे पॉपुलर कौन सा - वो सब उसे पता होना चाहिए").
 *
 * जड़: रिव्यू के बाद सिर्फ़ लंबी हिंदी रिपोर्ट (text) सेव होती थी - असली आँकड़े (PlatformData) फेंक दिए जाते थे,
 * इसलिए बाद के सवाल पर DK को या तो फिर से पूरा रिव्यू (कुछ मिनट) करना पड़ता था या अंदाज़ा.
 * अब: हर रिव्यू के असली आँकड़े JSON में सेव + "पूछो" वाले सवाल का तुरंत जवाब इन्हीं से (ऐप खोले बिना).
 * Android import नहीं -> JVM test.
 */
object InsightsFacts {
    /** इतने पुराने आँकड़े "ताज़ा" (फिर से रिव्यू की ज़रूरत नहीं) */
    const val FRESH_MS = 6 * 60 * 60_000L

    data class Saved(val at: Long, val data: List<PlatformData>)

    fun toJson(all: List<PlatformData>, at: Long): String = JSONObject().put("at", at).put("platforms", JSONArray().apply {
        all.filter { !it.empty }.forEach { d ->
            put(JSONObject().put("platform", d.platform.id).put("account", d.account.toJson())
                .put("posts", JSONArray().apply { d.posts.take(40).forEach { put(it.toJson()) } })
                .put("sources", JSONArray(d.sources)))
        }
    }).toString()

    fun fromJson(s: String?): Saved? {
        if (s.isNullOrBlank()) return null
        return try {
            val o = JSONObject(s)
            val arr = o.optJSONArray("platforms") ?: return null
            val list = (0 until arr.length()).mapNotNull { i ->
                val p = arr.optJSONObject(i) ?: return@mapNotNull null
                val pl = Platform.values().firstOrNull { it.id == p.optString("platform") } ?: return@mapNotNull null
                val posts = p.optJSONArray("posts")
                val ps = (0 until (posts?.length() ?: 0)).mapNotNull { j -> posts?.optJSONObject(j)?.let { Post.fromJson(pl, it, it.optString("source", "screen")) } }
                val src = p.optJSONArray("sources")
                PlatformData(pl, ps, Account.fromJson(p.optJSONObject("account")), sources = (0 until (src?.length() ?: 0)).map { src!!.optString(it) })
            }
            if (list.isEmpty()) null else Saved(o.optLong("at"), list)
        } catch (_: Exception) { null }
    }

    /** ताज़ा पुराना मिलाओ: नए रिव्यू में जो प्लेटफ़ॉर्म नहीं था, उसके पुराने आँकड़े रहें */
    fun keep(old: Saved?, now: List<PlatformData>): List<PlatformData> {
        val fresh = now.filter { !it.empty }
        val ids = fresh.map { it.platform }.toSet()
        return fresh + old?.data.orEmpty().filter { it.platform !in ids }
    }

    fun fresh(s: Saved?, now: Long): Boolean = s != null && now - s.at in 0..FRESH_MS

    private fun n(v: Long?): String = v?.let { Num.short(it) } ?: "-"

    fun ago(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return when {
            m < 1 -> "अभी-अभी"
            m < 60 -> "$m मिनट पहले"
            m < 48 * 60 -> "${m / 60} घंटे पहले"
            else -> "${m / (24 * 60)} दिन पहले"
        }
    }

    /** सबसे ज़्यादा व्यू वाली (views न हों तो reach) */
    fun top(posts: List<Post>): Post? = posts.filter { (it.views ?: it.reach) != null }.maxByOrNull { it.views ?: it.reach ?: 0 }

    /** सबसे नई (तारीख़ से, न हो तो पहली - स्क्रीन/API नई से पुरानी देते हैं) */
    fun latest(posts: List<Post>): Post? = Insights.ordered(posts).firstOrNull()

    private fun post(p: Post): String = "\"${p.title.take(70).ifBlank { "(बिना नाम)" }}\"" +
        (if (p.date.isNotBlank()) " (${p.date})" else "") + " - व्यू ${n(p.views)}" +
        (p.reach?.let { ", रीच ${n(it)}" } ?: "") + (p.likes?.let { ", लाइक ${n(it)}" } ?: "") + (p.comments?.let { ", कमेंट ${n(it)}" } ?: "") +
        (p.shares?.let { ", शेयर ${n(it)}" } ?: "")

    /** एक प्लेटफ़ॉर्म का पक्का हाल - Live इसी से जवाब दे */
    fun sheet(d: PlatformData): String = buildString {
        val a = d.account
        append("${d.platform.en} (${d.platform.hi})")
        if (a.name.isNotBlank() || a.handle.isNotBlank()) append(" - ${listOf(a.name, a.handle).filter { it.isNotBlank() }.joinToString(" ")}")
        append(":\n")
        append("- फ़ॉलोअर/सब्सक्राइबर: ${n(a.followers)}" + (a.followersChange?.let { " (बदलाव ${if (it >= 0) "+" else ""}${n(it)})" } ?: ""))
        a.following?.let { append(", फ़ॉलोइंग: ${n(it)}") }
        a.postsCount?.let { append(", कुल पोस्ट/वीडियो: ${n(it)}") }
        append("\n")
        if (a.views != null || a.reach != null || a.interactions != null)
            append("- खाते के कुल (${a.period.ifBlank { "हाल का समय" }}): व्यू ${n(a.views)}, रीच ${n(a.reach)}, इंटरैक्शन ${n(a.interactions)}" +
                (a.viewsChangePct?.let { ", व्यू बदलाव ${"%.0f".format(java.util.Locale.US, it)}%" } ?: "") + "\n")
        latest(d.posts)?.let { append("- सबसे नई ${d.platform.itemHi}: ${post(it)}\n") }
        top(d.posts)?.let { append("- सबसे पॉपुलर (सबसे ज़्यादा व्यू): ${post(it)}\n") }
        val vs = d.posts.mapNotNull { it.views }
        if (vs.isNotEmpty()) append("- पढ़ी गई ${d.posts.size} ${d.platform.itemHi} का औसत व्यू ${n(vs.sum() / vs.size)}, बीच वाला (median) ${n(Insights.median(vs))}\n")
        if (a.monetization.isNotBlank()) append("- मोनेटाइज़ेशन: ${a.monetization.take(160)}\n")
        if (a.recommendation.isNotBlank()) append("- रिकमेंडेशन: ${a.recommendation.take(160)}\n")
        if (d.posts.isNotEmpty()) append("- सारी (नई से पुरानी): " + Insights.ordered(d.posts).take(10).joinToString(" | ") { "\"${it.title.take(36)}\" ${n(it.views)}" } + "\n")
    }

    /**
     * "कितने फ़ॉलोअर हैं / लास्ट वीडियो पर कितने व्यू / सबसे पॉपुलर कौन सा" - सेव आँकड़ों से तुरंत जवाब (Live के लिए).
     * [only] = जिन प्लेटफ़ॉर्म का पूछा (खाली = सब जो सेव हैं).
     */
    fun answer(s: Saved?, only: List<Platform>, now: Long): String {
        if (s == null) return "NO SAVED NUMBERS: DK has not reviewed this account yet, so it does not know these numbers. Tell the user honestly and offer: " +
            "'अभी रिव्यू कर दूँ?' - on yes call social_insights action=start (or background=true if he wants it without opening the app). Never guess numbers."
        val list = s.data.filter { only.isEmpty() || it.platform in only }
        if (list.isEmpty()) return "NO SAVED NUMBERS for ${only.joinToString { it.en }}: the last review did not include it. Tell the user honestly and offer to review it now. Never guess numbers."
        val old = !fresh(s, now)
        return "SAVED REVIEW NUMBERS (read by DK ${ago(now - s.at)}; these are real numbers DK read from the app/API - answer ONLY from them, " +
            "short and exact, in Hindi; if the asked number is '-' say DK could not read it):\n" + list.joinToString("\n") { sheet(it) } +
            (if (old) "\nThese numbers are older than 6 hours - after answering, mention they are from ${ago(now - s.at)} and offer a fresh review." else "")
    }

    /**
     * फटाफट: API से पूरे आँकड़े आ गए (खाता + 6+ आइटम) और यूज़र ने खाते की हालत (मोनेटाइज़ेशन/रिकमेंडेशन/कमेंट) नहीं पूछी
     * = स्क्रीन पर दोबारा पढ़ने की ज़रूरत नहीं (कई मिनट बचते हैं).
     */
    fun screenNeeded(apiData: PlatformData, request: String): Boolean {
        if (apiData.empty) return true
        if (apiData.posts.size < 6 || apiData.account.followers == null) return true
        val t = Num.nk(request).lowercase()
        val wantsStatus = listOf("मोनेटाइ", "monetiz", "रिकमेंड", "recommend", "स्टेटस", "status", "बैन", "ban", "कमेंट", "comment", "स्ट्राइक", "strike", "पॉलिसी", "policy")
            .any { t.contains(Num.nk(it).lowercase()) }
        return wantsStatus
    }
}
