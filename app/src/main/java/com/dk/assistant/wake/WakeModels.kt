package com.dk.assistant.wake

import android.content.Context
import com.dk.assistant.DKLog
import com.dk.assistant.Prefs
import com.dk.assistant.plus.models.ModelCatalog
import com.dk.assistant.plus.models.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 16 नवंबर 2026: वेक वर्ड के मॉडल - कोई भी APK में नहीं (ऐप का साइज़ नहीं बढ़ता). पहली बार इंटरनेट से
 * ([ModelManager]: progress, आधे से आगे (resume), SHA-256 जाँच), फिर बिना इंटरनेट.
 *
 * नया संस्करण: रिपो की `models/wake/wake_models.json` ([MANIFEST_URL]) में version बढ़ा = Settings में "अपडेट करो".
 * नया पूरा और SHA सही होने पर ही बदलता है - बीच में फेल = पुराना वैसा ही चलता रहता है.
 */
object WakeModels {
    const val MANIFEST_URL = "https://raw.githubusercontent.com/rolexdkscreation-max/DK2.0/main/models/wake/wake_models.json"
    private const val P_MANIFEST = "wake2_manifest"

    /** सूची: openWakeWord के दो साझा मॉडल + हमारे दो classifier (v1) - [ModelCatalog.WAKE] */
    val BUILTIN: List<ModelCatalog.Asset> get() = ModelCatalog.WAKE

    fun version(c: Context): Int = manifest(c)?.version ?: ModelCatalog.WAKE_VERSION

    data class Manifest(val version: Int, val assets: List<ModelCatalog.Asset>)

    /** pure: manifest JSON -> Asset (गलत / अधूरा = null) */
    fun parse(json: String): Manifest? = try {
        val o = JSONObject(json)
        val v = o.getInt("version")
        val arr = o.getJSONArray("files")
        val list = (0 until arr.length()).map { i ->
            val f = arr.getJSONObject(i)
            val urls = f.optJSONArray("urls")?.let { u -> (0 until u.length()).map { u.getString(it) } } ?: listOf(f.getString("url"))
            ModelCatalog.Asset(f.getString("id"), ModelCatalog.Kind.WAKE, f.optString("title", f.getString("id")), f.optString("sub", ""),
                f.getString("file"), urls, f.getString("sha256").lowercase(), f.getLong("bytes"), f.optString("license", ""))
        }
        val m = Manifest(v, list)
        if (ModelCatalog.problems(list).isEmpty() && REQUIRED.all { id -> list.any { it.id == id } }) m else null
    } catch (_: Exception) { null }

    /** ये 4 हर manifest में होने चाहिए */
    val REQUIRED = listOf("oww_mel", "oww_emb", WakeRules.Word.HEY.id, WakeRules.Word.HELLO.id)

    private fun manifest(c: Context): Manifest? = Prefs.str(c, P_MANIFEST, "").takeIf { it.isNotBlank() }?.let { parse(it) }

    fun assets(c: Context): List<ModelCatalog.Asset> = manifest(c)?.assets ?: BUILTIN
    private fun asset(c: Context, id: String) = assets(c).first { it.id == id }

    fun melFile(c: Context) = ModelManager.file(c, asset(c, "oww_mel"))
    fun embFile(c: Context) = ModelManager.file(c, asset(c, "oww_emb"))
    fun headFile(c: Context, w: WakeRules.Word): File = ModelManager.file(c, asset(c, w.id))

    fun ready(c: Context) = assets(c).all { ModelManager.ready(c, it) }
    fun totalBytes(c: Context) = assets(c).sumOf { it.bytes }
    fun missing(c: Context) = assets(c).filter { !ModelManager.ready(c, it) }

    /** पहली बार (वेक वर्ड चालू हो और मॉडल न हों) - पीछे से WorkManager, स्क्रीन बंद हो तब भी */
    fun ensure(c: Context) {
        if (manifest(c) != null) return              // अपडेट वाला सेट Settings के बटन से ही
        missing(c).forEach { ModelManager.enqueue(c, it) }
    }

    /** सब फ़ाइलें एक-एक करके, कुल progress के साथ. null = सफल, वरना साफ़ हिंदी कारण */
    suspend fun downloadAll(c: Context, list: List<ModelCatalog.Asset> = assets(c), onProgress: (Long, Long) -> Unit): String? {
        val total = list.sumOf { it.bytes }; var done = 0L
        for (a in list) {
            if (ModelManager.ready(c, a)) { done += a.bytes; onProgress(done, total); continue }
            val base = done
            val watch = kotlinx.coroutines.coroutineScope {
                val job = launch {
                    ModelManager.state(a.id).collect { s -> if (s is ModelManager.St.Running) onProgress(base + s.done, total) }
                }
                val r = ModelManager.download(c, a)
                job.cancel(); r
            }
            if (watch != null) return friendly(watch)
            done += a.bytes; onProgress(done, total)
        }
        return null
    }

    /** pure: तकनीकी गलती -> सरल हिंदी */
    fun friendly(raw: String): String = when {
        raw.contains("SHA", true) -> "फ़ाइल पूरी सही नहीं आई (जाँच में मेल नहीं खाई) - दोबारा कोशिश कीजिए"
        raw.contains("HTTP 404") -> "मॉडल सर्वर पर नहीं मिला - थोड़ी देर बाद \"फिर कोशिश करो\" दबाइए"
        raw.contains("HTTP") -> "सर्वर ने मना किया ($raw) - थोड़ी देर बाद दोबारा"
        raw.contains("अधूरी") -> "डाउनलोड बीच में टूटा - \"फिर कोशिश करो\" दबाइए, वहीं से आगे चलेगा"
        raw.contains("Unable to resolve host", true) || raw.contains("timeout", true) || raw.contains("failed to connect", true) ->
            "इंटरनेट नहीं मिला - नेट चालू करके \"फिर कोशिश करो\" दबाइए (आधा डाउनलोड बचा रहता है)"
        raw.contains("सेव") || raw.contains("ENOSPC", true) || raw.contains("No space", true) -> "फ़ोन में जगह कम है - थोड़ी जगह खाली कीजिए"
        else -> "डाउनलोड नहीं हुआ: $raw"
    }

    /** रिपो में नया version? null = नहीं / पता नहीं चला */
    suspend fun checkUpdate(c: Context): Manifest? = withContext(Dispatchers.IO) {
        try {
            val http = com.dk.assistant.NetFix.builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
            http.newCall(Request.Builder().url(MANIFEST_URL).header("User-Agent", "DK-Wake").build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val m = parse(r.body?.string().orEmpty()) ?: return@withContext null
                if (m.version > version(c)) m else null
            }
        } catch (e: Exception) { DKLog.d("वेक वर्ड: अपडेट जाँच नहीं हुई (${e.javaClass.simpleName})"); null }
    }

    /** नया सेट डाउनलोड + SHA सही = तभी बदलो (पुरानी फ़ाइलें जो अब किसी काम की नहीं, मिटाओ) */
    suspend fun applyUpdate(c: Context, m: Manifest, onProgress: (Long, Long) -> Unit): String? {
        val err = downloadAll(c, m.assets, onProgress)
        if (err != null) return err
        val old = assets(c)
        Prefs.edit(c) { putString(P_MANIFEST, toJson(m)) }
        WakeWord.closeEngine()
        old.filter { o -> m.assets.none { it.fileName == o.fileName } }.forEach { try { ModelManager.file(c, it).delete() } catch (e: Exception) { com.dk.assistant.Quiet.note(e) } }
        DKLog.d("वेक वर्ड: मॉडल v${m.version} लगा")
        return null
    }

    fun toJson(m: Manifest): String = JSONObject().put("version", m.version).put("files", org.json.JSONArray().apply {
        m.assets.forEach { a ->
            put(JSONObject().put("id", a.id).put("title", a.title).put("sub", a.sub).put("file", a.fileName)
                .put("urls", org.json.JSONArray(a.urls)).put("sha256", a.sha256).put("bytes", a.bytes).put("license", a.license))
        }
    }).toString()
}
