package com.dk.assistant

import com.dk.assistant.OpenSearch.Hit

/** v3.90: [OpenSearch] का इंटरनेट वाला हिस्सा (OkHttp) - parse/compose pure [OpenSearch] में */
object OpenSearchNet {
    // ------------------------------------------------------------------ फ़ोन पर (Android/IO)
    private val http by lazy {
        okhttp3.OkHttpClient.Builder().connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(7, java.util.concurrent.TimeUnit.SECONDS).callTimeout(9, java.util.concurrent.TimeUnit.SECONDS).build()
    }
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private fun get(url: String): String? = try {
        http.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (_: Exception) { null }

    /** सारी बिना-key खोजें साथ-साथ. कुछ न मिले = null. (blocking - IO thread से बुलाओ) */
    fun search(question: String): SearchResult? {
        val q = question.trim()
        if (q.length < 2) return null
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            val fn = pool.submit<List<Hit>> { get(OpenSearch.newsUrl(q))?.let { OpenSearch.parseNews(it) } ?: emptyList() }
            val fh = pool.submit<List<Hit>> { get(OpenSearch.wikiUrl(q, "hi"))?.let { OpenSearch.parseWiki(it, "hi") } ?: emptyList() }
            val fe = pool.submit<List<Hit>> { get(OpenSearch.wikiUrl(q, "en"))?.let { OpenSearch.parseWiki(it, "en") } ?: emptyList() }
            fun <T> f(x: java.util.concurrent.Future<List<T>>): List<T> = try { x.get(10, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { emptyList() }
            val news = f(fn)
            val wiki = (f(fh).take(1) + f(fe).take(1))
            val text = OpenSearch.compose(news, wiki)
            DKLog.d("बिना-key खोज: ${news.size} ख़बरें, ${wiki.size} Wikipedia")
            return if (text.isBlank()) null else SearchResult(text, OpenSearch.sources(news, wiki))
        } finally { pool.shutdownNow() }
    }
}
