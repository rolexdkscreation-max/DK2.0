package com.dk.assistant.plus.models

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dk.assistant.DKLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 15 नवंबर 2026: सारे भारी मॉडल का एक ही डाउनलोडर ([ModelCatalog]).
 *  - WorkManager: स्क्रीन बंद हो या ऐप पीछे चला जाए तब भी डाउनलोड चलता रहे (सिर्फ़ इंटरनेट हो तब)
 *  - Resume: आधी फ़ाइल `.part` में रहती है, अगली बार `Range` से वहीं से आगे
 *  - SHA-256: पूरी फ़ाइल मिलाई जाती है, न मिले = मिटाकर गलती
 *  - Progress: [state] (UI सुनता है)
 *  - जगह: `filesDir/models/<fileName>` (APK में कुछ नहीं)
 */
object ModelManager {

    sealed class St {
        object None : St()
        data class Running(val done: Long, val total: Long) : St() { val pct get() = if (total > 0) (done * 100 / total).toInt() else 0 }
        object Ready : St()
        data class Failed(val why: String) : St()
        /** 18 नवंबर 2026: मालिक ने रोका - आधी फ़ाइल `.part` में सुरक्षित, "फिर शुरू" = वहीं से आगे */
        data class Paused(val done: Long, val total: Long) : St() { val pct get() = if (total > 0) (done * 100 / total).toInt() else 0 }
    }

    /** जिन मॉडल को मालिक ने रोका है (डाउनलोड का loop इसे देखकर चुपचाप रुकता है, गलती नहीं दिखाता) */
    private val paused = java.util.Collections.synchronizedSet(HashSet<String>())

    private val states = HashMap<String, MutableStateFlow<St>>()
    fun state(id: String): StateFlow<St> = synchronized(states) { states.getOrPut(id) { MutableStateFlow(St.None) } }
    private fun put(id: String, s: St) { (state(id) as MutableStateFlow).value = s }

    fun dir(c: Context): File = File(c.filesDir, "models").apply { mkdirs() }
    fun file(c: Context, a: ModelCatalog.Asset) = File(dir(c), a.fileName)
    private fun part(c: Context, a: ModelCatalog.Asset) = File(dir(c), a.fileName + ".part")

    /** फ़ाइल फ़ोन में है और साइज़ सही (SHA डाउनलोड के समय एक बार जाँचा जा चुका) */
    fun ready(c: Context, a: ModelCatalog.Asset): Boolean = file(c, a).let { it.isFile && it.length() == a.bytes }

    fun refresh(c: Context) {
        cleanupRemoved(c)
        ModelCatalog.ALL.forEach { a ->
            if (state(a.id).value !is St.Running) put(a.id, restState(ready(c, a), part(c, a).length(), a.bytes))
        }
    }

    /** हटाई गई सुविधा (3D कैरेक्टर) की पुरानी डाउनलोड फ़ाइलें मिटाओ - न मिलें / न मिटें तो भी कोई crash नहीं */
    fun cleanupRemoved(c: Context) {
        for (n in ModelCatalog.REMOVED_FILES) try { File(dir(c), n).delete(); File(dir(c), "$n.part").delete() } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
        try { listOf("dk_model_char_robot", "dk_model_char_cesium_man", "dk_model_char_brainstem", "dk_model_char_fox", "dk_model_lib_gltf_loader", "dk_model_lib_bg_utils")
            .forEach { WorkManager.getInstance(c).cancelUniqueWork(it) } } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
    }

    /** 21 नवंबर 2026: हटे Whisper की पुरानी मॉडल फ़ाइल (और आधी .part) - कितने bytes पड़े हैं (0 = कुछ नहीं) */
    fun oldWhisperBytes(c: Context): Long = File(dir(c), ModelCatalog.OLD_WHISPER_FILE).length() + File(dir(c), ModelCatalog.OLD_WHISPER_FILE + ".part").length()

    /** Settings के बटन से: पुरानी Whisper फ़ाइल मिटाओ, कितनी जगह खाली हुई (bytes) */
    fun deleteOldWhisper(c: Context): Long {
        val had = oldWhisperBytes(c)
        try { WorkManager.getInstance(c).cancelUniqueWork("dk_model_whisper_small_q5_1") } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
        File(dir(c), ModelCatalog.OLD_WHISPER_FILE).delete(); File(dir(c), ModelCatalog.OLD_WHISPER_FILE + ".part").delete()
        return had - oldWhisperBytes(c)
    }

    fun usedBytes(c: Context): Long = dir(c).listFiles()?.sumOf { it.length() } ?: 0L

    fun delete(c: Context, a: ModelCatalog.Asset) {
        paused.add(a.id)                                           // चलता डाउनलोड हो तो वह भी रुके
        try { WorkManager.getInstance(c).cancelUniqueWork(work(a)) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
        file(c, a).delete(); part(c, a).delete(); put(a.id, St.None)
        // paused में ही रहे: पुराना (रद्द हो रहा) loop फ़ाइल फिर न लिखे. अगला enqueue / download इसे हटाता है
    }

    /** 18 नवंबर 2026: डाउनलोड रोको - आधी फ़ाइल रहती है ([enqueue] = वहीं से आगे, Range से) */
    fun pause(c: Context, a: ModelCatalog.Asset) {
        if (ready(c, a)) { put(a.id, St.Ready); return }
        paused.add(a.id)
        try { WorkManager.getInstance(c).cancelUniqueWork(work(a)) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
        put(a.id, St.Paused(part(c, a).length(), a.bytes))
    }

    private fun work(a: ModelCatalog.Asset) = "dk_model_${a.id}"

    /** WorkManager से डाउनलोड (पहले से चल रहा हो तो वही रहे) */
    fun enqueue(c: Context, a: ModelCatalog.Asset) {
        if (ready(c, a)) { put(a.id, St.Ready); return }
        paused.remove(a.id)
        put(a.id, St.Running(part(c, a).length(), a.bytes))
        val req = OneTimeWorkRequestBuilder<Worker>()
            .setInputData(workDataOf("id" to a.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(
                if (ModelCatalog.wifiOnly(a)) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork(work(a), ExistingWorkPolicy.KEEP, req)
    }

    /** सीधा (suspend) डाउनलोड - Worker और वह code जो मॉडल तुरंत चाहता है, दोनों यही बुलाते हैं. null = सफल */
    suspend fun download(c: Context, a: ModelCatalog.Asset): String? = withContext(Dispatchers.IO) {
        if (ready(c, a)) { put(a.id, St.Ready); return@withContext null }
        paused.remove(a.id)
        val http = com.dk.assistant.NetFix.builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        var last = "डाउनलोड नहीं हुआ"
        for (url in a.urls) for (attempt in 1..3) {
            try {
                val p = part(c, a)
                if (p.length() > a.bytes) p.delete()
                val have = p.length()
                val rb = Request.Builder().url(url).header("User-Agent", "DK-AI-ModelManager")
                rangeHeader(have)?.let { rb.header("Range", it) }
                http.newCall(rb.build()).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                    val append = resumeAccepted(have, r.code)
                    val body = r.body ?: throw java.io.IOException("खाली जवाब")
                    var done = if (append) have else 0L
                    FileOutputStream(p, append).use { out ->
                        body.byteStream().use { inp ->
                            val buf = ByteArray(64 * 1024); var tick = 0L
                            while (true) {
                                if (a.id in paused) throw PausedEx()
                                val n = inp.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                if (done - tick > 256 * 1024) { tick = done; put(a.id, St.Running(done, a.bytes)) }
                            }
                        }
                    }
                }
                if (p.length() != a.bytes) throw java.io.IOException("अधूरी फ़ाइल (${p.length()}/${a.bytes})")
                val got = sha256(p)
                if (!got.equals(a.sha256, ignoreCase = true)) { p.delete(); throw java.io.IOException("SHA-256 नहीं मिला") }
                val f = file(c, a); f.delete()
                if (!p.renameTo(f)) throw java.io.IOException("फ़ाइल सेव नहीं हुई")
                put(a.id, St.Ready)
                DKLog.d("ModelManager: ${a.id} तैयार (${a.mb})")
                return@withContext null
            } catch (e: PausedEx) {
                put(a.id, St.Paused(part(c, a).length(), a.bytes)); return@withContext PAUSED
            } catch (e: kotlinx.coroutines.CancellationException) {
                // WorkManager ने रोका (रोकें / हटाएँ / Wi-Fi गया) - आधी फ़ाइल रहे, गलती नहीं
                if (a.id in paused) put(a.id, St.Paused(part(c, a).length(), a.bytes))
                throw e
            } catch (e: Exception) {
                if (a.id in paused) { put(a.id, St.Paused(part(c, a).length(), a.bytes)); return@withContext PAUSED }
                last = e.message ?: e.javaClass.simpleName
                DKLog.d("ModelManager: ${a.id} कोशिश $attempt ($url): $last")
                kotlinx.coroutines.delay(backoffMs(attempt))
            }
        }
        put(a.id, St.Failed(last))
        last
    }

    /** [download] का जवाब जब मालिक ने खुद रोका (गलती नहीं) */
    const val PAUSED = "रोका गया"
    private class PausedEx : Exception("paused")

    class Worker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
        override suspend fun doWork(): Result {
            val a = ModelCatalog.byId(inputData.getString("id").orEmpty()) ?: return Result.failure()
            val r = download(applicationContext, a)
            return when (r) { null -> Result.success(); PAUSED -> Result.failure(); else -> Result.retry() }
        }
    }

    // ---------------------------------------------------------------- pure (JVM test)
    /** आधी फ़ाइल है तो उसके बाद से माँगो */
    fun rangeHeader(have: Long): String? = if (have > 0) "bytes=$have-" else null
    /** server ने 206 दिया = जोड़ते जाओ; 200 = server ने पूरी फ़ाइल फिर से भेजी, शुरू से लिखो */
    fun resumeAccepted(have: Long, code: Int): Boolean = have > 0 && code == 206
    /** डाउनलोड न चलते समय क्या दिखे: पूरा = Ready, आधा = Paused (वहीं से आगे), कुछ नहीं = None */
    fun restState(ready: Boolean, partBytes: Long, total: Long): St = when {
        ready -> St.Ready
        partBytes in 1 until total -> St.Paused(partBytes, total)
        else -> St.None
    }
    fun backoffMs(attempt: Int): Long = (1000L shl (attempt - 1).coerceIn(0, 4))

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { inp -> val b = ByteArray(64 * 1024); while (true) { val n = inp.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
