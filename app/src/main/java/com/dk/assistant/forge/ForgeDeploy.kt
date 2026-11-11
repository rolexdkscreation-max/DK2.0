package com.dk.assistant.forge

import android.content.Context
import com.dk.assistant.DKLog
import com.dk.assistant.GitHub
import java.io.File

/**
 * नवंबर 2026 (मालिक: "डिप्लॉय करने को बोला जाए तो डिप्लॉय करेगी, वरना नहीं"): बनी हुई वेबसाइट को इंटरनेट पर (GitHub Pages).
 * सिर्फ़ तब जब मालिक ने साफ़ "डिप्लॉय / पब्लिश / ऑनलाइन डालो" बोला हो ([userAsked]) - वरना कुछ भी बाहर नहीं जाता.
 * GitHub token वही जो Settings → कोडिंग एजेंट में है. एक वेबसाइट = एक repo (दोबारा डिप्लॉय = उसी में नया commit).
 */
object ForgeDeploy {
    private val ASK = listOf("deploy", "डिप्लॉय", "डिप्लोय", "डेप्लॉय", "publish", "पब्लिश", "ऑनलाइन डाल", "ऑनलाइन कर", "online kar", "online daal",
        "इंटरनेट पर डाल", "इंटरनेट पे डाल", "लाइव कर", "live kar", "host kar", "होस्ट कर", "github pages", "गिटहब पेज")
    private val NOT = listOf("मत", "नहीं", "ना करो", "don't", "dont", "do not", "mat ", "nahi")

    /** मालिक ने सच में डिप्लॉय कहा? ("डिप्लॉय मत करो" = नहीं) */
    fun userAsked(said: String?): Boolean {
        val t = " " + said.orEmpty().lowercase() + " "
        if (ASK.none { t.contains(it) }) return false
        return NOT.none { t.contains(it) }
    }

    /** "website_20261104_101010.html" + विवरण -> "dk-site-chai-point" जैसा repo नाम */
    fun repoName(prompt: String, file: String): String {
        val words = com.dk.assistant.NameMatch.latin(prompt.lowercase()).lowercase()
            .replace(Regex("[^a-z0-9]+"), " ").split(' ').filter { it.length >= 3 && it !in STOP }.take(3)
        val tail = Regex("(\\d{8})_(\\d{6})").find(file)?.let { it.groupValues[1].takeLast(4) + it.groupValues[2].take(4) } ?: "site"
        return ("dk-site-" + (words.joinToString("-").ifBlank { "web" }) + "-" + tail).take(60)
    }
    private val STOP = setOf("the", "website", "web", "site", "page", "banao", "bana", "make", "build", "create", "for", "and", "with", "ek", "ki", "ka", "ke",
        "wali", "wala", "mera", "meri", "mere", "chahiye", "vebasait", "vebasaait", "banaao", "banaa")

    sealed class Result {
        data class Live(val url: String, val repo: String) : Result()
        data class Fail(val msg: String) : Result()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("dk_forge_deploy", Context.MODE_PRIVATE)
    fun lastUrl(c: Context, f: File): String? = prefs(c).getString("url:" + f.name, null)

    /** IO thread पर बुलाओ */
    fun deploy(c: Context, f: File, prompt: String, reuseLast: Boolean): Result {
        val gh = GitHub.fromPrefs(c) ?: return Result.Fail("इंटरनेट पर डालने के लिए GitHub token चाहिए - Settings → कोडिंग एजेंट में GitHub token डालिए (Contents, Pages और Administration: Read and write)।")
        return try {
            val login = gh.me()
            // बदली हुई (edit) वेबसाइट = पिछला वाला repo ही; नई वेबसाइट = नया repo
            val key = "last_repo"
            var repo = if (reuseLast) prefs(c).getString(key, null) else null
            if (repo == null || !gh.repoExists(repo)) repo = gh.createRepo(repoName(prompt, f.name), "DK Website Forge: " + prompt.take(120)).ifBlank { "$login/${repoName(prompt, f.name)}" }
            val branch = gh.defaultBranch(repo)
            try { gh.commit(repo, branch, mapOf("index.html" to f.readText()), "DK Website Forge: ${f.name}") } catch (_: GitHub.NoChange) { /* वही वेबसाइट पहले से */ }
            val url = gh.enablePages(repo, branch).ifBlank { "https://${login.lowercase()}.github.io/${repo.substringAfter('/')}/" }
            prefs(c).edit().putString(key, repo).putString("url:" + f.name, url).apply()
            DKLog.d("Forge deploy: $repo -> $url")
            Result.Live(url, repo)
        } catch (e: Exception) {
            DKLog.d("Forge deploy नहीं हुआ: ${e.message}")
            Result.Fail("वेबसाइट इंटरनेट पर नहीं डल पाई: " + (e.message ?: e.javaClass.simpleName).take(220))
        }
    }
}
