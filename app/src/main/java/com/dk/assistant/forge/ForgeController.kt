package com.dk.assistant.forge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.dk.assistant.ApiScopes
import com.dk.assistant.DKLog
import com.dk.assistant.OneKeyGate
import com.dk.assistant.Lang
import com.dk.assistant.Prefs
import com.dk.assistant.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ForgePhase { IDLE, BUILDING, DONE, FAILED, CANCELLED }

data class ForgeMetrics(val chars: Int = 0, val sections: Int = 0, val elapsedSec: Int = 0, val tokensPerSec: Int = 0)

data class ForgeUiState(
    val phase: ForgePhase = ForgePhase.IDLE,
    val prompt: String = "",
    val isEdit: Boolean = false,
    val stage: ForgeStage = ForgeStage.STRUCTURE,
    val progress: Int = 0,
    val metrics: ForgeMetrics = ForgeMetrics(),
    val sections: List<String> = emptyList(),
    val log: List<String> = emptyList(),
    val logSeq: Int = 0,
    val buildId: Int = 0,
    val file: File? = null,
    val error: String? = null,
)

/**
 * DK AI ब्रीफ़ Part 14: Website Forge - पूरी प्रक्रिया (एक ही बार में एक वेबसाइट).
 * स्क्रीन से अलग अपनी CoroutineScope; ForgeActivity सिर्फ़ state देखती है.
 */
object ForgeController {
    sealed class StartResult(val message: String) {
        class Started(m: String) : StartResult(m)
        class MissingKey(m: String) : StartResult(m)
        class Offline(m: String) : StartResult(m)
        class Busy(m: String) : StartResult(m)
        class NothingToEdit(m: String) : StartResult(m)
    }

    const val DIR = "Websites"
    const val EXTRA_OPEN_FORGE = "dk_open_forge"
    private const val CH = "dk_forge"
    private const val NOTIF_ID = 7401

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(ForgeUiState())
    val state: StateFlow<ForgeUiState> = _state.asStateFlow()

    /** true = कोई स्क्रीन (ForgeActivity) खुलनी चाहिए (Live/चैट से शुरू हुआ) */
    @Volatile var pendingOpen = false
    /** ForgeActivity दिख रही है? (नहीं = पूरा होने पर notification) */
    @Volatile var screenVisible = false

    private var job: Job? = null
    private var lastRequest: Pair<String, Boolean>? = null       // (prompt/change, isEdit)
    private val partial = StringBuilder()
    private var buildSeq = 0

    fun partialHtml(): String = synchronized(partial) { partial.toString() }

    /** Forge के लिए Gemini key: चैट वाली Gemini key, नहीं तो DK लाइव वाली */
    fun apiKey(c: Context): String =
        Prefs.scopeKey(c, ApiScopes.CHAT, "gemini").ifBlank { Prefs.scopeKey(c, ApiScopes.CODER, "gemini") }.ifBlank { Prefs.ttsGeminiKey(c) }

    fun websitesDir(c: Context): File = File(c.filesDir, DIR).apply { mkdirs() }

    fun savedSites(c: Context): List<File> =
        websitesDir(c).listFiles { f -> f.isFile && f.name.endsWith(".html") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun online(c: Context): Boolean = try {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) { true }

    fun start(c: Context, prompt: String): StartResult = begin(c.applicationContext, prompt.trim(), isEdit = false)

    fun edit(c: Context, change: String): StartResult {
        if (_state.value.file?.exists() != true) return StartResult.NothingToEdit(Lang.pick("बदलने के लिए अभी कोई वेबसाइट खुली नहीं है।", "There is no website open to edit."))
        return begin(c.applicationContext, change.trim(), isEdit = true)
    }

    fun retry(c: Context): StartResult {
        val (p, e) = lastRequest ?: return StartResult.NothingToEdit(Lang.pick("दोबारा बनाने के लिए कुछ नहीं है।", "Nothing to rebuild."))
        return begin(c.applicationContext, p, e)
    }

    fun cancel() {
        job?.cancel()
        job = null
        if (_state.value.phase == ForgePhase.BUILDING) _state.value = _state.value.copy(phase = ForgePhase.CANCELLED, error = null)
    }

    fun reset() { cancel(); _state.value = ForgeUiState(); synchronized(partial) { partial.setLength(0) } }

    fun openSaved(f: File) {
        cancel()
        _state.value = ForgeUiState(phase = ForgePhase.DONE, prompt = f.nameWithoutExtension, file = f, progress = 100, stage = ForgeStage.FINISH, buildId = ++buildSeq)
    }

    private fun begin(c: Context, text: String, isEdit: Boolean): StartResult {
        OneKeyGate.check(c, OneKeyGate.Work.WEBSITE)?.let { return StartResult.MissingKey(it) }
        if (text.isBlank()) return StartResult.NothingToEdit(Lang.pick("कैसी वेबसाइट बनानी है, यह बताइए।", "Tell me what website to build."))
        if (_state.value.phase == ForgePhase.BUILDING) return StartResult.Busy(Lang.pick("एक वेबसाइट अभी बन रही है, पहले वह पूरी होने दें।", "A website is already being built; let it finish first."))
        val key = apiKey(c)
        if (key.isBlank()) return StartResult.MissingKey(Lang.pick("वेबसाइट बनाने के लिए सेटिंग्स में Gemini key डालें।", "Add a Gemini key in Settings to build websites."))
        if (!online(c)) return StartResult.Offline(Lang.pick("इंटरनेट नहीं है, वेबसाइट नहीं बन सकती।", "No internet, the website can't be built."))
        val current = if (isEdit) _state.value.file?.takeIf { it.exists() }?.readText() else null
        if (isEdit && current == null) return StartResult.NothingToEdit(Lang.pick("बदलने के लिए अभी कोई वेबसाइट खुली नहीं है।", "There is no website open to edit."))
        lastRequest = text to isEdit
        val id = ++buildSeq
        synchronized(partial) { partial.setLength(0) }
        _state.value = ForgeUiState(phase = ForgePhase.BUILDING, prompt = text, isEdit = isEdit, buildId = id,
            file = if (isEdit) _state.value.file else null, log = listOf(ForgeStage.STRUCTURE.label), logSeq = 1)
        pendingOpen = true
        job = scope.launch { run(c, key, text, current, id) }
        return StartResult.Started(Lang.pick("ठीक है, Forge खुल रहा है - आपकी वेबसाइट अभी बनती दिखेगी।", "Okay, opening the Forge - you'll see your website being built."))
    }

    private suspend fun run(c: Context, key: String, text: String, current: String?, id: Int) {
        val t0 = System.currentTimeMillis()
        val base = if (current != null) ForgeSystemPrompt.forEdit(current, text) else ForgeSystemPrompt.forNewSite(text)
        var lastEmit = 0L
        var lastStage = ForgeStage.STRUCTURE
        var lastKb = 0
        var seenSections = 0
        val log = ArrayList<String>().apply { add(ForgeStage.STRUCTURE.label) }

        fun emit(force: Boolean) {
            val now = System.currentTimeMillis()
            if (!force && now - lastEmit < 120) return
            lastEmit = now
            val html = partialHtml()
            val stage = ForgeStageDetector.detect(html)
            val secs = ForgeStageDetector.sectionNames(html)
            if (stage != lastStage) { log += stage.label; lastStage = stage }
            if (secs.size > seenSections) { secs.drop(seenSections).forEach { log += "Laying out $it" }; seenSections = secs.size }
            val kb = html.length / 10_240
            if (kb > lastKb) { log += "${kb * 10} KB"; lastKb = kb }
            val el = ((now - t0) / 1000).toInt()
            val tps = if (el > 0) html.length / 4 / el else 0
            if (_state.value.buildId != id) return
            _state.value = _state.value.copy(
                stage = stage, progress = ForgeStageDetector.progress(stage, html.length),
                metrics = ForgeMetrics(html.length, secs.size, el, tps), sections = secs,
                log = log.takeLast(12), logSeq = log.size,
            )
        }

        suspend fun once(prompt: String): Pair<String, String?> {
            synchronized(partial) { partial.setLength(0) }
            val fr = WebsiteForgeClient.stream(key, prompt) { t -> synchronized(partial) { partial.append(t) }; emit(false) }
            return partialHtml() to fr
        }

        try {
            var (raw, finish) = try { once(base) } catch (e: WebsiteForgeClient.ForgeHttpException) {
                if (!SseParser.isOverloaded(e.code, e.reason)) throw e
                log += "Gemini busy - retrying"; delay(4000); once(base)
            }
            if (ForgeHtml.needsRetry(raw) && (raw.length < ForgeHtml.MIN_TRUNCATED_OK || finish == "RECITATION")) {
                DKLog.d("Forge: दोबारा कोशिश (finish=$finish, ${raw.length} chars)")
                log += "Retrying for a complete page"
                val r2 = once(ForgeSystemPrompt.withRetryHint(base))
                if (r2.first.length >= raw.length || !ForgeHtml.needsRetry(r2.first)) { raw = r2.first; finish = r2.second }
            }
            val html = ForgeHtml.finalize(raw)
                ?: throw IllegalStateException(Lang.pick("Gemini वेबसाइट पूरी करने से पहले रुक गया। दोबारा बनाएँ।", "Gemini stopped before finishing. Please rebuild."))
            val f = File(websitesDir(c), "website_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".html")
            f.writeText(html)
            emit(true)
            if (_state.value.buildId != id) return
            _state.value = _state.value.copy(phase = ForgePhase.DONE, stage = ForgeStage.FINISH, progress = 100, file = f, error = null,
                metrics = _state.value.metrics.copy(chars = html.length))
            DKLog.d("Forge: वेबसाइट तैयार (${html.length / 1024} KB, ${(System.currentTimeMillis() - t0) / 1000}s)")
            if (!screenVisible) notifyReady(c)
            com.dk.assistant.LiveReport.note(com.dk.assistant.LiveConnect.webDoneNote(true, text, null))   // v3.102
        } catch (e: CancellationException) {
            throw e
        } catch (e: WebsiteForgeClient.ForgeHttpException) {
            fail(id, SseParser.friendlyError(e.code, e.reason))
        } catch (e: java.io.IOException) {
            fail(id, Lang.pick("Gemini तक नहीं पहुँच पाए - इंटरनेट जाँचें।", "Couldn't reach Gemini - check the internet."))
        } catch (e: Exception) {
            fail(id, e.message ?: Lang.pick("वेबसाइट नहीं बन पाई।", "The website couldn't be built."))
        }
    }

    private fun fail(id: Int, msg: String) {
        if (_state.value.buildId != id) return
        DKLog.d("Forge: नहीं बनी - $msg")
        _state.value = _state.value.copy(phase = ForgePhase.FAILED, error = msg)
        com.dk.assistant.LiveReport.note(com.dk.assistant.LiveConnect.webDoneNote(false, _state.value.prompt, msg))   // v3.102
    }

    private fun notifyReady(c: Context) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(NotificationChannel(CH, "DK Website Forge", NotificationManager.IMPORTANCE_DEFAULT))
            val open = Intent(c, ForgeActivity::class.java).putExtra(EXTRA_OPEN_FORGE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(c, NOTIF_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            @Suppress("DEPRECATION")
            val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, CH) else android.app.Notification.Builder(c)
            nm.notify(NOTIF_ID, b.setSmallIcon(R.drawable.ic_dk).setContentTitle(Lang.pick("आपकी वेबसाइट तैयार है", "Your website is ready"))
                .setContentText(_state.value.prompt.take(80)).setAutoCancel(true).setContentIntent(pi).build())
        } catch (_: Exception) { }
    }
}
