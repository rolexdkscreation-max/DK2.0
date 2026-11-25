package com.dk.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 25 नवंबर 2026 (मालिक: "English सेट है तो पूरी चीज़ English में ही; और Custom - दुनिया की कोई भी भाषा"):
 * ऐप के जिन हिंदी text का शब्दकोश (ui_en.tsv) में अनुवाद नहीं, उनका अनुवाद फ़ोन पर ही (Google ML Kit, बिना key,
 * पहली बार भाषा-पैक इंटरनेट से ~30 MB, फिर बिना इंटरनेट). जो भाषा ML Kit में नहीं, उसके लिए DK का अपना AI
 * ([com.dk.assistant.dkai.DkaiPick.ask]). अनुवाद फ़ोन में याद (filesDir/ui_mt_<भाषा>.tsv) - दोबारा नहीं माँगते.
 * एक अलग धागे पर, थोड़ा-थोड़ा जोड़कर; तैयार होते ही सामने वाली स्क्रीन ([UiLang.refresh]) नई लिखावट में.
 */
object UiMt {
    private class Job(val lang: String, val key: String, val text: String, val from: String)

    @Volatile private var app: Context? = null
    private val tables = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private val pending = LinkedHashMap<String, Job>()
    private val failed = ConcurrentHashMap<String, Long>()
    private val translators = HashMap<String, Translator>()
    @Volatile private var worker: Thread? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Settings → भाषा में दिखने वाली हालत ("भाषा-पैक उतर रहा है…") */
    @Volatile var status: String = ""
        private set

    fun init(c: Context) { app = c.applicationContext }

    /** ML Kit यह भाषा फ़ोन पर ही बदल सकता है? (नहीं = DK का AI) */
    fun offline(lang: String): Boolean = ARM64 && try { TranslateLanguage.fromLanguageTag(lang) != null } catch (_: Throwable) { false }

    /** ML Kit की .so सिर्फ़ arm64 वाले APK में (build.gradle.kts - MB बचाने को); बाकी फ़ोनों पर DK का AI */
    private val ARM64: Boolean by lazy { try { android.os.Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a") } catch (_: Throwable) { false } }

    /** तैयार अनुवाद (नहीं = null) */
    fun get(lang: String, key: String): String? = table(lang)[key]

    /**
     * अनुवाद माँगो. [key] = code वाला असली हिंदी text (याद इसी से), [text] = जिसका अनुवाद हो, [from] = उसकी भाषा ("hi" / "en").
     */
    fun want(lang: String, key: String, text: String, from: String) {
        if (key.length > MAX_LEN || app == null) return
        val id = "$lang\u0001$key"
        failed[id]?.let { if (System.currentTimeMillis() - it < RETRY_MS) return }
        synchronized(pending) {
            if (pending.containsKey(id) || pending.size >= MAX_PENDING) return
            pending[id] = Job(lang, key, text, from)
        }
        kick()
    }

    private fun kick() {
        synchronized(this) {
            if (worker?.isAlive == true) return
            worker = Thread({ loop() }, "dk-ui-mt").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
        }
    }

    private fun loop() {
        while (true) {
            try { Thread.sleep(GATHER_MS) } catch (_: InterruptedException) { return }
            val batch = synchronized(pending) {
                val b = pending.values.take(BATCH)
                b.forEach { pending.remove("${it.lang}\u0001${it.key}") }
                b
            }
            if (batch.isEmpty()) { status = ""; synchronized(this) { worker = null }; return }
            var any = false
            for ((lang, jobs) in batch.groupBy { it.lang }) {
                val got = try { if (offline(lang)) mlkit(lang, jobs) else ai(lang, jobs) } catch (e: Throwable) {
                    DKLog.d("UI अनुवाद ($lang) नहीं हुआ: ${e.message?.take(100)}"); emptyMap()
                }
                val t = table(lang)
                for (j in jobs) {
                    val out = got[j.key]?.takeIf { it.isNotBlank() }
                    if (out == null) { failed["$lang\u0001${j.key}"] = System.currentTimeMillis(); continue }
                    t[j.key] = out; any = true
                    if (!j.key.any { it.isDigit() }) save(lang, j.key, out)   // बदलते नंबर वाले text फ़ाइल में नहीं
                }
            }
            if (any) main.post { UiLang.refresh() }
        }
    }

    private fun mlkit(lang: String, jobs: List<Job>): Map<String, String> {
        val target = TranslateLanguage.fromLanguageTag(lang) ?: return emptyMap()
        val out = HashMap<String, String>()
        for ((from, list) in jobs.groupBy { it.from }) {
            val src = TranslateLanguage.fromLanguageTag(from) ?: TranslateLanguage.HINDI
            if (src == target) { list.forEach { out[it.key] = it.text }; continue }
            val tr = synchronized(translators) {
                translators.getOrPut("$src>$target") {
                    Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(target).build())
                }
            }
            status = "भाषा-पैक तैयार हो रहा है (पहली बार इंटरनेट से)…"
            Tasks.await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()), DOWNLOAD_MIN, TimeUnit.MINUTES)
            status = "अनुवाद हो रहा है…"
            for (j in list) {
                try { out[j.key] = Tasks.await(tr.translate(j.text), TEXT_SEC, TimeUnit.SECONDS) }
                catch (e: Exception) { DKLog.d("UI अनुवाद: ${e.message?.take(80)}") }
            }
        }
        return out
    }

    /** ML Kit में न हो वह भाषा: DK का AI (JSON सूची में, उसी क्रम में) */
    private fun ai(lang: String, jobs: List<Job>): Map<String, String> {
        val c = app ?: return emptyMap()
        val name = WorldLangs.english(lang)
        status = "$name में अनुवाद (DK का AI)…"
        val arr = org.json.JSONArray(); jobs.forEach { arr.put(it.text) }
        val sys = "You translate short mobile app UI strings. Reply ONLY with a JSON array of strings, same length and order. " +
            "Keep brand names (DK, DKAI, Gemini, WhatsApp...), numbers, emoji and symbols like • ✓ › as they are."
        val reply = kotlinx.coroutines.runBlocking {
            com.dk.assistant.dkai.DkaiPick.ask(c, sys, "Translate each string into $name ($lang):\n$arr")
        } ?: return emptyMap()
        val js = reply.substring(reply.indexOf('[').coerceAtLeast(0), (reply.lastIndexOf(']') + 1).coerceAtLeast(0))
        val res = try { org.json.JSONArray(js) } catch (_: Exception) { return emptyMap() }
        val out = HashMap<String, String>()
        for (i in jobs.indices) res.optString(i).takeIf { it.isNotBlank() }?.let { out[jobs[i].key] = it }
        return out
    }

    private fun table(lang: String): ConcurrentHashMap<String, String> = tables.getOrPut(lang) {
        ConcurrentHashMap<String, String>().also { m ->
            try {
                val f = file(lang)
                if (f != null && f.exists()) f.forEachLine(Charsets.UTF_8) { line ->
                    val i = line.indexOf('\t')
                    if (i > 0) m[unesc(line.substring(0, i))] = unesc(line.substring(i + 1))
                }
            } catch (e: Exception) { Quiet.note(e) }
        }
    }

    private fun save(lang: String, k: String, v: String) {
        try { file(lang)?.appendText(esc(k) + "\t" + esc(v) + "\n", Charsets.UTF_8) } catch (e: Exception) { Quiet.note(e) }
    }

    private fun file(lang: String): File? = app?.let { File(it.filesDir, "ui_mt_" + lang.filter { c -> c.isLetterOrDigit() || c == '-' } + ".tsv") }
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
    private fun unesc(s: String) = s.replace("\\t", "\t").replace("\\n", "\n").replace("\\\\", "\\")

    /** भाषा बदली - पिछली भाषा के बाकी काम छोड़ो */
    fun clearPending() { synchronized(pending) { pending.clear() }; failed.clear() }

    private const val MAX_LEN = 600
    private const val MAX_PENDING = 600
    private const val BATCH = 40
    private const val GATHER_MS = 350L
    private const val RETRY_MS = 10 * 60_000L
    private const val DOWNLOAD_MIN = 10L
    private const val TEXT_SEC = 20L
}
