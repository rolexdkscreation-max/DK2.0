package com.dk.assistant

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.webkit.WebView
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * DK कंप्यूटर के टूल: web search (Tavily / Brave / Gemini), वेब पेज पढ़ना, JavaScript चलाना,
 * फ़ाइलें (md/txt/csv/html/json + PDF), फ़ाइल खोलना/शेयर करना, और बाद में काम चलाना (रिमाइंडर).
 */
object ComputerTools {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()
    private val JSON = "application/json".toMediaType()
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36 DK-Computer"

    // ------------------------------------------------------------------ search
    data class Hit(val title: String, val url: String, val snippet: String)
    data class SearchOut(val via: String, val hits: List<Hit>, val summary: String = "")

    /** कौन-कौन से search चल सकते हैं, क्रम में (Settings की पसंद पहले) */
    fun searchOrder(choice: String, hasTavily: Boolean, hasBrave: Boolean, hasGemini: Boolean): List<String> {
        val avail = buildList { if (hasTavily) add("tavily"); if (hasBrave) add("brave"); if (hasGemini) add("gemini") }
        return if (choice in avail) listOf(choice) + avail.filter { it != choice } else avail
    }

    /** Search; एक fail हो तो अगला. [onIssue] = रिपोर्ट के लिए (दिक्कत, हल) */
    fun search(c: Context, query: String, n: Int, onIssue: (String, String) -> Unit): SearchOut {
        val order = searchOrder(Prefs.computerSearch(c), Prefs.tavilyKey(c).isNotBlank(), Prefs.braveKey(c).isNotBlank(),
            Prefs.key(c, "gemini").isNotBlank())
        if (order.isEmpty()) throw IllegalStateException("Search के लिए कोई key नहीं — Settings → DK कंप्यूटर में Tavily/Brave key या AI दिमाग में Gemini key डालें")
        var last: Exception? = null
        for ((i, s) in order.withIndex()) {
            try {
                val out = when (s) {
                    "tavily" -> tavily(Prefs.tavilyKey(c), query, n)
                    "brave" -> brave(Prefs.braveKey(c), query, n)
                    else -> gemini(c, query)
                }
                if (i > 0 && last != null) onIssue("${order[i - 1]} search नहीं चला: ${short(last)}", "अपने आप $s search पर स्विच किया")
                return out
            } catch (e: Exception) { last = e; DKLog.d("Computer: $s search fail ${e.message?.take(100)}") }
        }
        throw last ?: IllegalStateException("search fail")
    }

    private fun short(e: Exception) = (e as? AiException)?.let { CoderAi.reason(it) } ?: (e.message ?: e.javaClass.simpleName).take(80)

    fun tavily(key: String, q: String, n: Int): SearchOut {
        val body = JSONObject().put("query", q).put("max_results", n.coerceIn(1, 10)).put("search_depth", "basic")
        val req = Request.Builder().url("https://api.tavily.com/search").header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(JSON)).build()
        return SearchOut("Tavily", parseTavily(exec(req)))
    }

    fun parseTavily(raw: String): List<Hit> {
        val arr = JSONObject(raw).optJSONArray("results") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }.map {
            Hit(it.optString("title"), it.optString("url"), it.optString("content").take(600))
        }.filter { it.url.isNotBlank() }
    }

    fun brave(key: String, q: String, n: Int): SearchOut {
        val url = "https://api.search.brave.com/res/v1/web/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", q).addQueryParameter("count", n.coerceIn(1, 10).toString()).build()
        val req = Request.Builder().url(url).header("Accept", "application/json").header("X-Subscription-Token", key).build()
        return SearchOut("Brave", parseBrave(exec(req)))
    }

    fun parseBrave(raw: String): List<Hit> {
        val arr = JSONObject(raw).optJSONObject("web")?.optJSONArray("results") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }.map {
            Hit(stripTags(it.optString("title")), it.optString("url"), stripTags(it.optString("description")).take(600))
        }.filter { it.url.isNotBlank() }
    }

    private fun gemini(c: Context, q: String): SearchOut {
        val r = GeminiClient(Prefs.key(c, "gemini"), Prefs.model(c, "gemini")).search(
            "You are a web research tool. Search Google and return a dense factual summary (facts, numbers, dates, names) for the query. " +
                "Keep the language of the query. No chit-chat.", emptyList(), q, deep = false)
        return SearchOut("Google (Gemini)", r.sources.map { (t, u) -> Hit(t, u, "") }, r.text)
    }

    fun formatSearch(o: SearchOut): String = buildString {
        append("Search via ${o.via}\n")
        if (o.summary.isNotBlank()) append("SUMMARY: ").append(o.summary.take(3000)).append('\n')
        o.hits.forEachIndexed { i, h -> append("${i + 1}. ${h.title}\n   ${h.url}\n"); if (h.snippet.isNotBlank()) append("   ${h.snippet}\n") }
        if (o.hits.isEmpty() && o.summary.isBlank()) append("(कोई नतीजा नहीं)")
    }.trimEnd()

    // ------------------------------------------------------------------ वेब पेज पढ़ना
    fun readUrl(url: String, max: Int = 9000): String {
        val u = url.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        val req = Request.Builder().url(u).header("User-Agent", UA).header("Accept", "text/html,application/json,text/plain,*/*").build()
        return http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} ($u)")
            val type = r.header("Content-Type").orEmpty()
            val bytes = r.body?.byteStream()?.use { it.readNBytesCompat(2_000_000) } ?: ByteArray(0)
            if (type.contains("pdf") || type.startsWith("image/") || type.contains("octet-stream"))
                return "(यह $type फ़ाइल है — पढ़ी नहीं जा सकती)"
            val s = String(bytes, Charsets.UTF_8)
            val text = if (type.contains("html") || s.trimStart().startsWith("<")) htmlToText(s) else s
            "URL: $u\n" + text.take(max) + if (text.length > max) "\n…(बाकी ${text.length - max} अक्षर छोड़े)" else ""
        }
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024); var total = 0
        while (total < n) { val k = read(buf, 0, minOf(buf.size, n - total)); if (k <= 0) break; out.write(buf, 0, k); total += k }
        return out.toByteArray()
    }

    /** HTML -> पढ़ने लायक text (title + मुख्य हिस्सा, लिंक के पते साथ) */
    fun htmlToText(html: String): String {
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.let { decode(it).trim() }.orEmpty()
        var s = html
            .replace(Regex("(?is)<(script|style|noscript|svg|head|nav|footer|form|iframe)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?is)<a\\s[^>]*href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>")) { m ->
                val t = stripTags(m.groupValues[2]).trim(); if (t.isBlank()) " " else "$t (${m.groupValues[1]})"
            }
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|li|tr|h[1-6]|section|article|table|ul|ol)>"), "\n")
            .replace(Regex("(?i)<li[^>]*>"), "• ")
            .replace(Regex("(?i)<h[1-6][^>]*>"), "\n## ")
        s = decode(stripTags(s))
        s = s.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        return (if (title.isNotBlank()) "TITLE: $title\n" else "") + s
    }

    fun stripTags(s: String) = s.replace(Regex("(?s)<[^>]+>"), "")

    private fun decode(s: String) = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }

    private fun exec(req: Request): String = http.newCall(req).execute().use { r ->
        val s = r.body?.string() ?: ""
        if (!r.isSuccessful) throw AiException(r.code, "HTTP ${r.code}: " + s.take(160), GeminiModels.retryDelaySec(s))
        s
    }

    // ------------------------------------------------------------------ JavaScript
    /** JS के लिए लपेटा हुआ code: console.log पकड़ो, आखिरी value लौटाओ */
    fun wrapJs(code: String): String =
        "(function(){var __l=[];var console={log:function(){__l.push(Array.prototype.map.call(arguments,function(a){" +
            "return (typeof a==='object')?JSON.stringify(a):String(a)}).join(' '))}};console.error=console.log;console.warn=console.log;" +
            "try{var __r=eval(${JSONObject.quote(code)});return JSON.stringify({r:(__r===undefined?null:__r),l:__l})}" +
            "catch(e){return JSON.stringify({e:String(e),l:__l})}})()"

    /** evaluateJavascript का जवाब (JSON string के अंदर JSON) -> पढ़ने लायक */
    fun jsResult(raw: String?): String {
        if (raw == null || raw == "null") return "(कोई नतीजा नहीं)"
        val inner = try { JSONArray("[$raw]").getString(0) } catch (_: Exception) { raw }
        return try {
            val o = JSONObject(inner)
            val logs = o.optJSONArray("l")?.let { a -> (0 until a.length()).joinToString("\n") { a.optString(it) } }.orEmpty()
            val head = if (o.has("e")) "ERROR: " + o.optString("e") else "RESULT: " + (o.opt("r")?.toString() ?: "null")
            (if (logs.isNotBlank()) "LOG:\n$logs\n" else "") + head
        } catch (_: Exception) { inner }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun runJs(c: Context, code: String, timeoutMs: Long = 20_000): String = withContext(Dispatchers.Main) {
        val wv = WebView(c.applicationContext)
        try {
            wv.settings.javaScriptEnabled = true
            wv.settings.allowFileAccess = false
            wv.settings.allowContentAccess = false
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<String> { cont ->
                    wv.evaluateJavascript(wrapJs(code)) { r -> if (cont.isActive) cont.resume(jsResult(r)) }
                }
            } ?: "ERROR: ${timeoutMs / 1000}s में पूरा नहीं हुआ (लंबा loop?)"
        } finally { try { wv.destroy() } catch (_: Exception) {} }
    }

    // ------------------------------------------------------------------ फ़ाइलें
    fun dir(c: Context): File = File(c.getExternalFilesDir(null) ?: c.filesDir, "computer").apply { mkdirs() }

    /** सुरक्षित नाम: कोई फ़ोल्डर/.. नहीं */
    fun safeName(name: String, fallbackExt: String = ".md"): String {
        var n = name.substringAfterLast('/').substringAfterLast('\\').trim()
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}._ -]"), "_").replace(Regex("\\.{2,}"), ".").trim('.', ' ')
        if (n.isBlank()) n = "file"
        if (!n.contains('.')) n += fallbackExt
        return n.take(80)
    }

    fun write(c: Context, name: String, content: String): File {
        val f = File(dir(c), safeName(name)); f.writeText(content); return f
    }

    fun read(c: Context, name: String, max: Int = 12000): String {
        val f = File(dir(c), safeName(name))
        if (!f.exists()) return "ERROR: ${f.name} नहीं मिली। मौजूद: " + list(c).joinToString { it.name }.ifBlank { "(कोई नहीं)" }
        if (f.extension.lowercase() == "pdf") return "(${f.name} PDF है, ${f.length() / 1024} KB)"
        val t = f.readText(); return t.take(max) + if (t.length > max) "\n…(बाकी छोड़ा)" else ""
    }

    fun list(c: Context): List<File> = (dir(c).listFiles()?.filter { it.isFile } ?: emptyList()).sortedByDescending { it.lastModified() }

    /** Markdown/text -> A4 PDF (हिंदी भी, Android का अपना text इंजन) */
    fun makePdf(c: Context, name: String, title: String, content: String): File {
        val f = File(dir(c), safeName(name.removeSuffix(".pdf") + ".pdf"))
        val doc = PdfDocument()
        val W = 595; val H = 842; val M = 48; val w = W - 2 * M
        val body = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 11.5f; color = Color.rgb(30, 30, 30) }
        val head = TextPaint(body).apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(20, 40, 90) }
        val big = TextPaint(head).apply { textSize = 20f }
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M.toFloat()
        }
        fun draw(text: String, paint: TextPaint, gapAfter: Float) {
            val lay = StaticLayout.Builder.obtain(text, 0, text.length, paint, w).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1.05f).build()
            // लाइन-दर-लाइन: पेज भर जाए तो नया पेज (लंबा पैराग्राफ़ भी कटे नहीं)
            for (line in 0 until lay.lineCount) {
                val top = lay.getLineTop(line); val bot = lay.getLineBottom(line)
                if (page == null || y + (bot - top) > H - M) newPage()
                val cv = page!!.canvas
                cv.save(); cv.translate(M.toFloat(), y - top); cv.clipRect(0, top, w, bot); lay.draw(cv); cv.restore()
                y += (bot - top)
            }
            y += gapAfter
        }
        newPage()
        if (title.isNotBlank()) draw(title, big, 12f)
        for (raw in content.replace("\r", "").split('\n')) {
            val l = raw.trimEnd()
            when {
                l.isBlank() -> y += 6f
                l.startsWith("#") -> draw(l.trimStart('#').trim(), head, 6f)
                else -> draw(cleanMd(l), body, 3f)
            }
        }
        page?.let { doc.finishPage(it) }
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    /** **bold**, [t](url), `code` -> सादा text (PDF के लिए) */
    fun cleanMd(l: String) = l.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("\\[([^\\]]+)]\\((https?://[^)]+)\\)"), "$1 ($2)")
        .replace(Regex("`([^`]+)`"), "$1").replace(Regex("^\\s*[-*] "), "• ")

    fun mime(f: File) = when (f.extension.lowercase()) {
        "pdf" -> "application/pdf"; "html", "htm" -> "text/html"; "csv" -> "text/csv"; "json" -> "application/json"
        "md" -> "text/markdown"; else -> "text/plain"
    }

    fun uri(c: Context, f: File) = FileProvider.getUriForFile(c, c.packageName + ".files", f)

    fun open(c: Context, f: File) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri(c, f), mime(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { c.startActivity(i) } catch (_: Exception) {
            // md/csv खोलने वाला ऐप नहीं - text की तरह
            c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri(c, f), "text/plain").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun share(c: Context, f: File) {
        val i = Intent(Intent.ACTION_SEND).setType(mime(f)).putExtra(Intent.EXTRA_STREAM, uri(c, f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        c.startActivity(Intent.createChooser(i, f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ------------------------------------------------------------------ बाद में चलाना
    /** "HH:mm" या minutes -> कब (ms). आज का समय निकल गया हो तो कल */
    fun whenMs(now: Long, minutes: Int?, at: String?): Long? {
        if (minutes != null && minutes > 0) return now + minutes * 60_000L
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(at?.trim().orEmpty()) ?: return null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        if (h > 23 || mi > 59) return null
        val cal = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, mi); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    fun schedule(c: Context, task: String, at: Long): String {
        val am = c.getSystemService(AlarmManager::class.java) ?: return "ERROR: AlarmManager नहीं"
        val id = (at / 1000 % 100000).toInt() + 20000
        val pi = PendingIntent.getBroadcast(c, id, Intent(c, ComputerAlarm::class.java).putExtra("task", task),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // exact alarm की इजाज़त ज़रूरी नहीं - कुछ मिनट आगे-पीछे चल सकता है
        if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.set(AlarmManager.RTC_WAKEUP, at, pi)
        val t = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale("hi", "IN")).format(java.util.Date(at))
        return "तय हो गया: $t पर यह काम चलेगा — \"${task.take(80)}\" (फ़ोन restart हुआ तो रद्द हो जाएगा)"
    }
}

/** तय समय पर DK कंप्यूटर का काम चलाओ (नतीजा notification + कंप्यूटर स्क्रीन में) */
class ComputerAlarm : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val t = i.getStringExtra("task")?.takeIf { it.isNotBlank() } ?: return
        ComputerSession.start(c.applicationContext, "⏰ तय किया हुआ काम: $t", background = true)
    }
}
