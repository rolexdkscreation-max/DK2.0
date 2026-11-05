package com.dk.assistant.insights

import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * API वाला तरीका (ज़्यादा पक्का): आधिकारिक API के जवाब -> [Post] / [Account].
 *  - YouTube Data API v3 + YouTube Analytics API v2 (Google खाता, सेटिंग्स → कनेक्टर → YouTube)
 *  - Instagram Graph API (Business/Creator, Facebook Page से जुड़ा) - media + media insights
 *  - Facebook Graph API (Page) - posts / reels + insights
 * Network ConnectorRunner में; यहाँ सिर्फ़ पढ़ाई (unit test में चलती है)।
 * Meta हर कुछ महीने metric के नाम बदलता है - इसलिए हर metric "मिला तो लो, नहीं तो छोड़ो"।
 */
object InsightsApi {

    // ------------------------------------------------------------ YouTube
    /** ISO 8601 "PT1M5S" -> सेकंड */
    fun isoDuration(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        val m = Regex("P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matchEntire(s.trim()) ?: return null
        val (d, h, mi, se) = m.destructured
        return (d.toDoubleOrNull() ?: 0.0) * 86400 + (h.toDoubleOrNull() ?: 0.0) * 3600 + (mi.toDoubleOrNull() ?: 0.0) * 60 + (se.toDoubleOrNull() ?: 0.0)
    }

    /** "2026-11-01T10:00:00Z" -> "4 दिन पहले" (आज के हिसाब से) */
    fun agoText(iso: String?, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): String {
        if (iso.isNullOrBlank()) return ""
        val d = try { OffsetDateTime.parse(iso.trim()).toLocalDate() } catch (_: Exception) {
            try { OffsetDateTime.parse(iso.trim().replace(Regex("\\+0000$"), "+00:00")).toLocalDate() } catch (_: Exception) {
                try { LocalDate.parse(iso.take(10)) } catch (_: Exception) { return iso.take(10) }
            }
        }
        val n = ChronoUnit.DAYS.between(d, today)
        return when { n <= 0 -> "आज"; n == 1L -> "कल"; else -> "$n दिन पहले" } + " (" + d.format(DateTimeFormatter.ISO_LOCAL_DATE) + ")"
    }

    /** videos?part=snippet,statistics,contentDetails -> Post (सूची के क्रम में) */
    fun ytVideos(json: JSONObject?, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): List<Post> {
        val items = json?.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }.map { v ->
            val sn = v.optJSONObject("snippet"); val st = v.optJSONObject("statistics"); val cd = v.optJSONObject("contentDetails")
            val dur = isoDuration(cd?.optString("duration"))
            Post(
                Platform.YOUTUBE, title = sn?.optString("title").orEmpty(), date = agoText(sn?.optString("publishedAt"), today),
                type = if (dur != null && dur <= 180) "short" else "video",
                views = st?.optString("viewCount")?.toLongOrNull(), likes = st?.optString("likeCount")?.toLongOrNull(),
                comments = st?.optString("commentCount")?.toLongOrNull(), durationSec = dur, notes = "id=" + v.optString("id"), source = "api",
            )
        }
    }

    /**
     * YouTube Analytics reports (dimensions=video) -> हर वीडियो के आँकड़े.
     * columnHeaders के नाम से पढ़ते हैं (क्रम पर भरोसा नहीं)।
     */
    fun ytAnalyticsByVideo(json: JSONObject?): Map<String, JSONObject> {
        val heads = json?.optJSONArray("columnHeaders") ?: return emptyMap()
        val names = (0 until heads.length()).map { heads.optJSONObject(it)?.optString("name").orEmpty() }
        val rows = json.optJSONArray("rows") ?: return emptyMap()
        val vi = names.indexOf("video").takeIf { it >= 0 } ?: return emptyMap()
        val out = LinkedHashMap<String, JSONObject>()
        for (r in 0 until rows.length()) {
            val row = rows.optJSONArray(r) ?: continue
            val o = JSONObject()
            names.forEachIndexed { i, n -> if (i != vi) o.put(n, row.opt(i)) }
            out[row.optString(vi)] = o
        }
        return out
    }

    /** Analytics के आँकड़े Post में भरो (id= वाले notes से मिलान) */
    fun ytMerge(posts: List<Post>, byVideo: Map<String, JSONObject>): List<Post> = posts.map { p ->
        val id = p.notes.substringAfter("id=", "").substringBefore(';').trim()
        val a = byVideo[id] ?: return@map p
        fun l(k: String) = a.opt(k)?.toString()?.toDoubleOrNull()
        p.copy(
            views = l("views")?.toLong() ?: p.views,
            watchTimeSec = l("estimatedMinutesWatched")?.let { it * 60 } ?: p.watchTimeSec,
            avgWatchSec = l("averageViewDuration") ?: p.avgWatchSec,
            retentionPct = l("averageViewPercentage") ?: p.retentionPct,
            follows = l("subscribersGained")?.let { g -> (g - (l("subscribersLost") ?: 0.0)).toLong() } ?: p.follows,
            shares = l("shares")?.toLong() ?: p.shares,
            likes = l("likes")?.toLong() ?: p.likes, comments = l("comments")?.toLong() ?: p.comments,
        )
    }

    /** दो अवधि (पिछले 28 दिन बनाम उससे पहले के 28) का कुल -> खाता */
    fun ytTotals(now: JSONObject?, before: JSONObject?, subscribers: Long?): Account {
        fun total(j: JSONObject?, k: String): Double? {
            val heads = j?.optJSONArray("columnHeaders") ?: return null
            val i = (0 until heads.length()).firstOrNull { heads.optJSONObject(it)?.optString("name") == k } ?: return null
            val row = j.optJSONArray("rows")?.optJSONArray(0) ?: return null
            return row.opt(i)?.toString()?.toDoubleOrNull()
        }
        val v = total(now, "views"); val vb = total(before, "views")
        val gained = total(now, "subscribersGained"); val lost = total(now, "subscribersLost")
        return Account(
            period = "पिछले 28 दिन", views = v?.toLong(), watchTimeSec = total(now, "estimatedMinutesWatched")?.let { it * 60 },
            followers = subscribers, followersChange = gained?.let { (it - (lost ?: 0.0)).toLong() },
            viewsChangePct = if (v != null && vb != null && vb > 0) Math.round((v - vb) / vb * 1000) / 10.0 else null,
            interactions = listOfNotNull(total(now, "likes"), total(now, "comments"), total(now, "shares")).takeIf { it.isNotEmpty() }?.sum()?.toLong(),
        )
    }

    /** insightTrafficSourceType -> "YT_SHORTS 60%, SUBSCRIBER 20%, ..." */
    fun ytTraffic(json: JSONObject?): String {
        val rows = json?.optJSONArray("rows") ?: return ""
        val list = (0 until rows.length()).mapNotNull { rows.optJSONArray(it) }.map { it.optString(0) to (it.opt(1)?.toString()?.toDoubleOrNull() ?: 0.0) }
        val sum = list.sumOf { it.second }.takeIf { it > 0 } ?: return ""
        return list.sortedByDescending { it.second }.take(6).joinToString(", ") { (k, n) -> "${trafficHi(k)} ${Math.round(n / sum * 100)}%" }
    }

    private fun trafficHi(k: String) = when (k.uppercase()) {
        "YT_SHORTS", "SHORTS" -> "Shorts फ़ीड"
        "SUBSCRIBER" -> "सब्सक्राइबर/होम"
        "YT_SEARCH" -> "YouTube सर्च"
        "RELATED_VIDEO" -> "सुझाए गए वीडियो"
        "BROWSE", "YT_OTHER_PAGE" -> "होम/ब्राउज़"
        "EXT_URL" -> "बाहरी लिंक"
        "NO_LINK_OTHER" -> "सीधे/अन्य"
        "YT_CHANNEL" -> "चैनल पेज"
        "PLAYLIST" -> "प्लेलिस्ट"
        "NOTIFICATION" -> "नोटिफ़िकेशन"
        "END_SCREEN" -> "एंड स्क्रीन"
        "HASHTAGS" -> "हैशटैग"
        else -> k.lowercase()
    }

    // ------------------------------------------------------------ Instagram
    /** /{ig}/media?fields=id,caption,media_type,media_product_type,timestamp,like_count,comments_count,permalink */
    fun igMedia(json: JSONObject?, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): List<Post> {
        val d = json?.optJSONArray("data") ?: return emptyList()
        return (0 until d.length()).mapNotNull { d.optJSONObject(it) }.map { o ->
            val kind = o.optString("media_product_type").ifBlank { o.optString("media_type") }.uppercase()
            Post(
                Platform.INSTAGRAM, title = o.optString("caption").replace(Regex("\\s+"), " ").take(100).ifBlank { o.optString("permalink") },
                date = agoText(o.optString("timestamp"), today), type = if (kind.contains("REEL")) "reel" else kind.lowercase(),
                likes = o.optLongOrNull("like_count"), comments = o.optLongOrNull("comments_count"), notes = "id=" + o.optString("id"), source = "api",
            )
        }
    }

    /**
     * /{media}/insights?metric=... -> आँकड़े. नाम: views (2025 से plays की जगह), reach, saved, shares, total_interactions,
     * likes, comments, follows, ig_reels_avg_watch_time (ms), ig_reels_video_view_total_time (ms)
     */
    fun metaInsights(json: JSONObject?): Map<String, Double> {
        val d = json?.optJSONArray("data") ?: return emptyMap()
        val out = LinkedHashMap<String, Double>()
        for (i in 0 until d.length()) {
            val o = d.optJSONObject(i) ?: continue
            val name = o.optString("name")
            val vals = o.optJSONArray("values")
            val v = vals?.optJSONObject(vals.length() - 1)?.opt("value") ?: o.optJSONObject("total_value")?.opt("value")
            val n = when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); is JSONObject -> v.keys().asSequence().sumOf { k -> v.optDouble(k, 0.0) }; else -> null }
            if (name.isNotBlank() && n != null) out[name] = n
        }
        return out
    }

    fun igApply(p: Post, m: Map<String, Double>): Post = p.copy(
        views = (m["views"] ?: m["plays"] ?: m["ig_reels_aggregated_all_plays_count"])?.toLong() ?: p.views,
        reach = m["reach"]?.toLong() ?: p.reach,
        saves = (m["saved"] ?: m["saves"])?.toLong() ?: p.saves,
        shares = m["shares"]?.toLong() ?: p.shares,
        likes = m["likes"]?.toLong() ?: p.likes, comments = m["comments"]?.toLong() ?: p.comments,
        follows = m["follows"]?.toLong() ?: p.follows,
        avgWatchSec = m["ig_reels_avg_watch_time"]?.let { it / 1000.0 } ?: p.avgWatchSec,
        watchTimeSec = m["ig_reels_video_view_total_time"]?.let { it / 1000.0 } ?: p.watchTimeSec,
    )

    /** /{ig}/insights?metric=reach,views,...&period=day&metric_type=total_value -> खाता */
    fun igAccount(m: Map<String, Double>, followers: Long?, before: Map<String, Double> = emptyMap()): Account {
        fun change(k: String): Double? { val a = m[k]; val b = before[k]; return if (a != null && b != null && b > 0) Math.round((a - b) / b * 1000) / 10.0 else null }
        return Account(
            period = "पिछले 28 दिन", views = m["views"]?.toLong(), reach = m["reach"]?.toLong(),
            interactions = m["total_interactions"]?.toLong(), followers = followers, followersChange = m["follows_and_unfollows"]?.toLong() ?: m["follower_count"]?.toLong(),
            viewsChangePct = change("views"), reachChangePct = change("reach"),
        )
    }

    // ------------------------------------------------------------ Facebook
    /** /{page}/posts?fields=id,message,created_time,permalink_url,shares,reactions.summary(true),comments.summary(true) */
    fun fbPosts(json: JSONObject?, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): List<Post> {
        val d = json?.optJSONArray("data") ?: return emptyList()
        return (0 until d.length()).mapNotNull { d.optJSONObject(it) }.map { o ->
            Post(
                Platform.FACEBOOK, title = (o.optString("message").ifBlank { o.optString("description") }).replace(Regex("\\s+"), " ").take(100).ifBlank { o.optString("permalink_url") },
                date = agoText(o.optString("created_time").ifBlank { o.optString("updated_time") }, today), type = if (o.has("length") || o.optString("permalink_url").contains("/reel")) "reel" else "post",
                likes = o.optJSONObject("reactions")?.optJSONObject("summary")?.optLongOrNull("total_count") ?: o.optJSONObject("likes")?.optJSONObject("summary")?.optLongOrNull("total_count"),
                comments = o.optJSONObject("comments")?.optJSONObject("summary")?.optLongOrNull("total_count"),
                shares = o.optJSONObject("shares")?.optLongOrNull("count"),
                durationSec = o.opt("length")?.toString()?.toDoubleOrNull(),
                notes = "id=" + o.optString("id"), source = "api",
            )
        }
    }

    fun fbApply(p: Post, m: Map<String, Double>): Post = p.copy(
        views = (m["post_video_views"] ?: m["blue_reels_play_count"] ?: m["fb_reels_total_plays"] ?: m["post_impressions"] ?: m["views"])?.toLong() ?: p.views,
        reach = (m["post_impressions_unique"] ?: m["post_video_views_unique"] ?: m["post_total_media_view_unique"])?.toLong() ?: p.reach,
        avgWatchSec = (m["post_video_avg_time_watched"])?.let { it / 1000.0 } ?: p.avgWatchSec,
        watchTimeSec = (m["post_video_view_time"])?.let { it / 1000.0 } ?: p.watchTimeSec,
        follows = m["post_video_followers"]?.toLong() ?: p.follows,
    )

    private fun JSONObject.optLongOrNull(k: String): Long? = if (has(k) && !isNull(k)) opt(k)?.toString()?.toDoubleOrNull()?.toLong() else null

    /** id वाला note दिखाने लायक नहीं - रिपोर्ट से पहले हटाओ */
    fun cleanNotes(p: Post): Post = p.copy(notes = p.notes.split(';').map { it.trim() }.filter { !it.startsWith("id=") && it.isNotBlank() }.joinToString("; "))

    /** API के कई नाम आज़माओ: पहले वाला चल जाए तो उसी का जवाब (Meta नाम बदलता रहता है) */
    inline fun <T> firstWorking(options: List<String>, call: (String) -> T?): T? {
        for (o in options) { val r = try { call(o) } catch (_: Exception) { null }; if (r != null) return r }
        return null
    }
}
