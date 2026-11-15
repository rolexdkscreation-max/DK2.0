package com.dk.assistant.plus

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 15 नवंबर 2026 (मालिक: "YouTube की user ID डालने के बाद उसका सब चीज़ चेक कर दे").
 * Settings → DK AI Plus → YouTube चैनल: handle (@name) / channel ID / लिंक.
 *
 *  - YouTube Data API v3 की मुफ़्त key डाली = पक्के आँकड़े: subscribers, कुल views, वीडियो गिनती, नए वीडियो के views/likes/comments
 *    और नए वीडियो के ताज़ा comments. (Google Cloud में मुफ़्त, रोज़ 10,000 units - यह जाँच ~10 units)
 *  - key नहीं = बिना key का रास्ता: चैनल पेज से नाम/ID/subscriber (जितना पेज में दिखे) + RSS से नए 5 वीडियो और उनके views.
 *    comments बिना key नहीं मिलते - साफ़ बताते हैं.
 * सिर्फ़ पढ़ना - कुछ पोस्ट/बदलाव नहीं. Pure parsers -> JVM test.
 */
object YtChannel {
    const val HANDLE = "plus_yt_handle"
    const val KEY = "plus_yt_key"
    const val KEY_URL = "https://console.cloud.google.com/apis/library/youtube.googleapis.com"

    /** इनपुट -> (handle बिना @, या null) to (channel id UC..., या null) */
    data class Who(val handle: String?, val id: String?) { val ok get() = handle != null || id != null }

    private val ID_RX = Regex("(UC[A-Za-z0-9_-]{22})")
    private val HANDLE_RX = Regex("@([A-Za-z0-9._-]{3,30})")

    fun parse(input: String?): Who {
        val t = input?.trim().orEmpty()
        if (t.isEmpty()) return Who(null, null)
        ID_RX.find(t)?.let { return Who(null, it.groupValues[1]) }
        HANDLE_RX.find(t)?.let { return Who(it.groupValues[1], null) }
        val bare = t.substringAfterLast('/').trim()
        return if (Regex("[A-Za-z0-9._-]{3,30}").matches(bare)) Who(bare, null) else Who(null, null)
    }

    data class Video(val id: String, val title: String, val published: String, val views: Long?, val likes: Long?, val comments: Long?)
    data class Report(
        val title: String, val id: String, val subs: Long?, val subsText: String?, val views: Long?, val videos: Long?,
        val latest: List<Video>, val topComments: List<String>, val withKey: Boolean,
    )

    // ------------------------------------------------------------ बिना key
    /** चैनल पेज से channel ID */
    fun pageChannelId(html: String): String? =
        Regex("\"(?:externalId|channelId|browseId)\":\"(UC[A-Za-z0-9_-]{22})\"").find(html)?.groupValues?.get(1)
            ?: Regex("youtube\\.com/channel/(UC[A-Za-z0-9_-]{22})").find(html)?.groupValues?.get(1)

    fun pageTitle(html: String): String? = Regex("<meta property=\"og:title\" content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let(::unesc)

    /** "1.2M subscribers" / "12.5K सदस्य" जैसा जो पेज पर लिखा है (अंदाज़ा, पक्का नहीं) */
    fun pageSubs(html: String): String? =
        Regex("\"content\":\"([0-9][0-9.,]*\\s?[KMBkmb]?\\s?(?:subscribers|subscriber))\"").find(html)?.groupValues?.get(1)
            ?: Regex("\"subscriberCountText\":\\{[^}]*?\"simpleText\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)

    /** RSS (videos.xml) -> नए वीडियो */
    fun parseRss(xml: String, max: Int = 5): Pair<String?, List<Video>> {
        val feedTitle = Regex("<title>([^<]*)</title>").find(xml)?.groupValues?.get(1)?.let(::unesc)
        val out = Regex("<entry>([\\s\\S]*?)</entry>").findAll(xml).take(max).map { m ->
            val e = m.groupValues[1]
            Video(
                id = Regex("<yt:videoId>([^<]+)</yt:videoId>").find(e)?.groupValues?.get(1).orEmpty(),
                title = Regex("<title>([^<]*)</title>").find(e)?.groupValues?.get(1)?.let(::unesc).orEmpty(),
                published = Regex("<published>([^<]{10})").find(e)?.groupValues?.get(1).orEmpty(),
                views = Regex("<media:statistics views=\"(\\d+)\"").find(e)?.groupValues?.get(1)?.toLongOrNull(),
                likes = Regex("<media:starRating count=\"(\\d+)\"").find(e)?.groupValues?.get(1)?.toLongOrNull(),
                comments = null,
            )
        }.toList()
        return feedTitle to out
    }

    // ------------------------------------------------------------ Data API (key)
    fun channelsUrl(w: Who, key: String): String = "https://www.googleapis.com/youtube/v3/channels?part=snippet,statistics,contentDetails" +
        (if (w.id != null) "&id=${w.id}" else "&forHandle=" + enc("@" + w.handle)) + "&key=" + enc(key)

    data class Ch(val id: String, val title: String, val subs: Long?, val views: Long?, val videos: Long?, val uploads: String?)

    fun parseChannels(raw: String): Ch? = try {
        val it0 = JSONObject(raw).optJSONArray("items")?.optJSONObject(0)
        it0?.let { o ->
            val st = o.optJSONObject("statistics")
            Ch(o.optString("id"), o.optJSONObject("snippet")?.optString("title").orEmpty(),
                if (st?.optBoolean("hiddenSubscriberCount") == true) null else st?.optString("subscriberCount")?.toLongOrNull(),
                st?.optString("viewCount")?.toLongOrNull(), st?.optString("videoCount")?.toLongOrNull(),
                o.optJSONObject("contentDetails")?.optJSONObject("relatedPlaylists")?.optString("uploads")?.takeIf { it.isNotBlank() })
        }
    } catch (_: Exception) { null }

    fun parsePlaylist(raw: String): List<Pair<String, String>> = try {
        val a = JSONObject(raw).optJSONArray("items")
        (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val sn = a!!.optJSONObject(i)?.optJSONObject("snippet") ?: return@mapNotNull null
            val id = sn.optJSONObject("resourceId")?.optString("videoId").orEmpty()
            if (id.isBlank()) null else id to sn.optString("title")
        }
    } catch (_: Exception) { emptyList() }

    fun parseVideos(raw: String): List<Video> = try {
        val a = JSONObject(raw).optJSONArray("items")
        (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val o = a!!.optJSONObject(i) ?: return@mapNotNull null
            val st = o.optJSONObject("statistics"); val sn = o.optJSONObject("snippet")
            Video(o.optString("id"), sn?.optString("title").orEmpty(), sn?.optString("publishedAt").orEmpty().take(10),
                st?.optString("viewCount")?.toLongOrNull(), st?.optString("likeCount")?.toLongOrNull(), st?.optString("commentCount")?.toLongOrNull())
        }
    } catch (_: Exception) { emptyList() }

    fun parseComments(raw: String): List<String> = try {
        val a = JSONObject(raw).optJSONArray("items")
        (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val s = a!!.optJSONObject(i)?.optJSONObject("snippet")?.optJSONObject("topLevelComment")?.optJSONObject("snippet") ?: return@mapNotNull null
            val who = s.optString("authorDisplayName"); val t = unesc(s.optString("textOriginal").ifBlank { s.optString("textDisplay") }).replace(Regex("\\s+"), " ").take(140)
            if (t.isBlank()) null else "$who: $t"
        }
    } catch (_: Exception) { emptyList() }

    /** API की गलती -> छोटा हिंदी (key कभी नहीं दिखती) */
    fun apiError(code: Int, raw: String): String {
        val reason = try { JSONObject(raw).optJSONObject("error")?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty() } catch (_: Exception) { "" }
        return when {
            reason == "quotaExceeded" -> "आज की मुफ़्त सीमा खत्म (कल फिर)"
            code == 400 && reason.contains("key", true) || code == 403 && reason.contains("key", true) -> "YouTube key गलत है"
            code == 403 -> "key पर YouTube Data API चालू नहीं"
            else -> "HTTP $code"
        }
    }

    // ------------------------------------------------------------ बोलने/दिखाने लायक
    fun num(n: Long?): String = when {
        n == null -> "-"
        n >= 10_000_000 -> String.format(java.util.Locale.US, "%.1f करोड़", n / 1e7)
        n >= 100_000 -> String.format(java.util.Locale.US, "%.1f लाख", n / 1e5)
        n >= 1_000 -> String.format(java.util.Locale.US, "%.1f हज़ार", n / 1e3)
        else -> n.toString()
    }

    fun text(r: Report): String = buildString {
        append("चैनल: ${r.title.ifBlank { r.id }}\n")
        append("Subscribers: ").append(r.subs?.let(::num) ?: r.subsText ?: "पता नहीं (छुपे हैं या key नहीं)").append('\n')
        if (r.views != null) append("कुल views: ${num(r.views)}\n")
        if (r.videos != null) append("कुल वीडियो: ${r.videos}\n")
        if (r.latest.isNotEmpty()) {
            append("नए वीडियो:\n")
            r.latest.forEach { v ->
                append("• ${v.title.take(70)} (${v.published}) - views ${num(v.views)}")
                if (v.likes != null) append(", likes ${num(v.likes)}")
                if (v.comments != null) append(", comments ${num(v.comments)}")
                append('\n')
            }
        }
        if (r.topComments.isNotEmpty()) { append("नए वीडियो के ताज़ा comments:\n"); r.topComments.forEach { append("• $it\n") } }
        else if (!r.withKey) append("Comments देखने के लिए Settings में मुफ़्त YouTube Data API key डालें।\n")
    }.trimEnd()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun unesc(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    // ------------------------------------------------------------ नेट
    data class Result(val report: Report?, val error: String?)

    suspend fun check(c: Context, input: String? = null): Result = withContext(Dispatchers.IO) {
        val w = parse(input?.takeIf { it.isNotBlank() } ?: com.dk.assistant.Prefs.str(c, HANDLE, ""))
        if (!w.ok) return@withContext Result(null, "पहले Settings → DK AI Plus → YouTube चैनल में अपना @handle या channel ID डालें")
        val http = com.dk.assistant.NetFix.builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
        val key = com.dk.assistant.Prefs.str(c, KEY, "").trim()
        try {
            Result(if (key.isNotBlank()) withKey(http, w, key) else noKey(http, w), null)
        } catch (e: Exception) { Result(null, "YouTube जाँच नहीं हुई: ${e.message}") }
    }

    private fun body(http: OkHttpClient, url: String, api: Boolean = false): String =
        http.newCall(Request.Builder().url(url).header("Accept-Language", "en").build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException(if (api) apiError(r.code, t) else "HTTP ${r.code}")
            t
        }

    private fun withKey(http: OkHttpClient, w: Who, key: String): Report {
        val ch = parseChannels(body(http, channelsUrl(w, key), api = true)) ?: throw IOException("चैनल नहीं मिला")
        val ids = ch.uploads?.let { pl ->
            parsePlaylist(body(http, "https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&maxResults=5&playlistId=$pl&key=${enc(key)}", api = true))
        }.orEmpty()
        val vids = if (ids.isEmpty()) emptyList() else
            parseVideos(body(http, "https://www.googleapis.com/youtube/v3/videos?part=snippet,statistics&id=${ids.joinToString(",") { it.first }}&key=${enc(key)}", api = true))
        val comments = vids.firstOrNull()?.let { v ->
            try { parseComments(body(http, "https://www.googleapis.com/youtube/v3/commentThreads?part=snippet&order=time&maxResults=5&videoId=${v.id}&key=${enc(key)}", api = true)) }
            catch (_: Exception) { emptyList() }      // comments बंद हों तो भी बाकी रिपोर्ट
        }.orEmpty()
        return Report(ch.title, ch.id, ch.subs, null, ch.views, ch.videos, vids, comments, withKey = true)
    }

    private fun noKey(http: OkHttpClient, w: Who): Report {
        var id = w.id; var title: String? = null; var subs: String? = null
        if (w.handle != null) {
            val html = body(http, "https://www.youtube.com/@${w.handle}")
            id = pageChannelId(html) ?: throw IOException("यह handle नहीं मिला")
            title = pageTitle(html); subs = pageSubs(html)
        }
        val (feedTitle, vids) = try { parseRss(body(http, "https://www.youtube.com/feeds/videos.xml?channel_id=$id")) } catch (_: Exception) { null to emptyList() }
        return Report(title ?: feedTitle.orEmpty(), id!!, null, subs, null, null, vids, emptyList(), withKey = false)
    }
}
