package com.dk.assistant.agent.android

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.dk.assistant.CoderAi
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.DKState
import com.dk.assistant.LocalMemory
import com.dk.assistant.Prefs
import com.dk.assistant.TaskStatus
import com.dk.assistant.agent.core.ImageApp
import com.dk.assistant.agent.core.ImageGenFlow
import com.dk.assistant.agent.core.ImageGenHost
import com.dk.assistant.agent.core.VideoGenFlow
import com.dk.assistant.agent.core.VideoPlan
import com.dk.assistant.agent.core.VideoStudio
import com.dk.assistant.agent.core.WebRunResult
import com.dk.assistant.agent.core.WebVideoRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 28 नवंबर 2026 (मालिक): "DK, Gemini पर जाओ, मेरी कहानी का 1 मिनट का 2D / 3D / इमोशनल वीडियो बनाओ - किरदार वही रहें,
 * भाग-भाग में बनाओ, और मुझसे बात भी करते रहो" + "Chrome में कोई भी वीडियो वेबसाइट (मैं खोलकर लॉग-इन करूँगा, लिंक दूँगा) - वहाँ बनाओ".
 * DK लाइव का video_task tool.
 *
 * DK खुद कहानी + किरदारों की पक्की पहचान लिखता है ([VideoStudio]), फिर हर 8 सेकंड का भाग:
 *  - Gemini ऐप (मालिक का Google AI Pro) में उसी chat में ([VideoGenFlow]) - या
 *  - किसी भी वेबसाइट पर (DK की AI हर कदम स्क्रीन देखकर, [WebVideoRun]).
 * हर भाग फ़ोन में डाउनलोड, आख़िर में सब जोड़कर एक वीडियो ([VideoJoin], गैलरी -> Movies/DK). पीछे चलता है -
 * DK लाइव बात करता रहता है; हर कदम [TaskStatus] में, खत्म होने पर SYSTEM NOTE ([liveNote]).
 * लॉग-इन / limit = रुककर सच बताता है; "आगे बढ़ो" (action=resume) = जहाँ रुका था वहीं से.
 */
object VideoStudioAgent {
    const val KEY = "video"
    private const val SITE_PREF = "video_site_url"
    const val FLOW_URL = "https://labs.google/fx/tools/flow"
    private val BROWSERS = setOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "org.mozilla.firefox", "com.microsoft.emmx",
        "com.sec.android.app.sbrowser", "com.brave.browser", "com.opera.browser", "com.kiwibrowser.browser")

    @Volatile private var job: Job? = null
    @Volatile private var stopAsked = false
    @Volatile private var spoken: String? = null
    @Volatile private var lastVideo: Uri? = null
    @Volatile private var resume: Resume? = null
    val busy get() = job?.isActive == true

    /** अधूरा काम (लॉग-इन / limit पर रुका) - "आगे बढ़ो" पर यहीं से */
    private class Resume(val plan: VideoPlan, val web: Boolean, val url: String, val parts: List<String>, val made: Int)

    fun stop(): Boolean {
        val j = job ?: return false
        if (!j.isActive) return false
        stopAsked = true
        j.cancel()
        return true
    }

    suspend fun start(svc: DKAccessibilityService, args: JSONObject): String {
        val action = args.optString("action").trim().lowercase()
        if (action in setOf("stop", "cancel", "रोको")) return if (stop()) "STOPPED: DK stopped making the video. Tell the user in one short line (the parts already made stay in the gallery)." else "Nothing was running. Tell the user no video is being made right now."
        if (action == "status") return TaskStatus.summary()
        if (action == "show") return show(svc)
        if (busy) return "BUSY: DK is already making a video (${TaskStatus.get(KEY)?.last ?: "-"}). Tell the user it is in progress; he can say रोको to stop (video_task action=stop)."
        val said = DKState.lastUser.trimEnd('…').trim()
        val r = resume
        if (action == "resume") {
            if (r == null) return "NOTHING_TO_RESUME: no unfinished video. Ask the user in one short Hindi line what video to make."
            return launch(svc, r.plan, r.web, r.url, r.parts, r.made)
        }
        val story = args.optString("story").ifBlank { args.optString("goal") }.ifBlank { said }.trim()
        if (story.length < 3) return "ERROR: the story / idea is not clear. Ask the user in one short Hindi line what the video should be about."
        val words = "$story $said ${args.optString("style")}"
        val url = args.optString("url").trim().ifBlank { urlIn("$story $said").orEmpty() }
        val site = args.optString("site").trim().lowercase()
        val wantsWeb = url.isNotBlank() || site == "web" || site == "website" || site.contains("flow") ||
            (site.isBlank() && Regex("website|वेबसाइट|वेब साइट|लिंक|link|chrome|क्रोम|browser|ब्राउज़र|ब्राउजर|google flow|गूगल फ्लो|flow पर|फ्लो पर").containsMatchIn(words.lowercase()))
        val total = VideoStudio.seconds(args.optInt("seconds", 0), args.optDouble("minutes", 0.0).takeIf { !it.isNaN() } ?: 0.0, words)
        val style = VideoStudio.style(words)
        val chars = args.optInt("characters", 0)
        val host = ImageGenAgent.Host(svc)
        var webUrl = url
        if (wantsWeb && webUrl.isBlank()) {
            webUrl = when {
                site.contains("flow") || Regex("flow|फ्लो").containsMatchIn(words.lowercase()) -> FLOW_URL
                // मालिक ने वेबसाइट पहले से खोल रखी है (लॉग-इन करके) - वहीं
                AccessibilityScreenDriver(svc).capture()?.pkg in BROWSERS -> ""
                else -> Prefs.str(svc, SITE_PREF, "").ifBlank { return "NEED_LINK: which video website is not clear. Ask the user in one short Hindi line to open the website in Chrome (and log in), or tell its name / link." }
            }
        }
        if (!wantsWeb && !host.installed(ImageApp.GEMINI.pkg)) return "ERROR: Gemini ऐप इस फ़ोन में नहीं है. Tell the user to install Gemini from Play Store and log in with his Google AI Pro account, or open any video website in Chrome and say its name."
        resume = null
        val plan0 = VideoPlan("", style, emptyList(), emptyList(), story)
        DKLog.d("Video: ${if (wantsWeb) "web ${webUrl.ifBlank { "(खुली साइट)" }}" else "Gemini"} • $total सेकंड • $style • \"${story.take(80)}\"")
        return launch(svc, plan0, wantsWeb, webUrl, emptyList(), 0, total, chars)
    }

    private fun launch(svc: DKAccessibilityService, given: VideoPlan, web: Boolean, url: String, done: List<String>, made0: Int, total: Int = given.totalSeconds, chars: Int = 0): String {
        stopAsked = false
        val whereHi = if (web) (hostOf(url) ?: "वेबसाइट") else "Gemini"
        TaskStatus.start(KEY, "DK स्टूडियो (वीडियो)", "DK Studio making a video on $whereHi (on screen)", "${given.story.take(80)} • $total सेकंड")
        LocalMemory.record(svc, "d", "मैंने $whereHi पर वीडियो बनाना शुरू किया: ${given.story}", "video_task")
        job = svc.scope.launch {
            var state = TaskStatus.State.FAILED
            var result = ""
            var plan = given
            val parts = done.toMutableList()
            var made = made0
            try {
                if (!WakeForWork.ensure(svc) { TaskStatus.step(KEY, it) }) {
                    result = "फ़ोन लॉक रहा - वीडियो शुरू नहीं हो पाया (अनलॉक करके फिर बोलिए)"; spoken = result; return@launch
                }
                if (plan.parts.isEmpty()) {
                    TaskStatus.step(KEY, "कहानी, किरदार और भाग लिख रहा हूँ…")
                    val raw = think(svc, VideoStudio.planAsk(plan.story, total, plan.style, chars))
                    plan = VideoStudio.parsePlan(raw, plan.story, total, plan.style)
                    LocalMemory.record(svc, "d", "वीडियो \"${plan.title}\": ${plan.parts.size} भाग • किरदार: ${VideoStudio.sheet(plan)}", "video_task")
                    TaskStatus.step(KEY, "\"${plan.title}\" - ${plan.parts.size} भाग, किरदार: ${plan.chars.joinToString { it.name }}")
                }
                val n = plan.parts.size
                val drv = AccessibilityScreenDriver(svc)
                val vh = VideoHost(svc, ImageGenAgent.Host(svc), parts)
                var stopWhy = ""
                var needUser = false
                if (web && url.isNotBlank() && made == 0) {
                    TaskStatus.step(KEY, "${hostOf(url)} खोल रहा हूँ…")
                    if (!openUrl(svc, url)) { result = "वेबसाइट नहीं खुली ($url)"; spoken = result; return@launch }
                    Prefs.edit(svc) { putString(SITE_PREF, url) }
                }
                for (i in made until n) {
                    if (stopAsked) break
                    TaskStatus.step(KEY, VideoStudio.stepLine(i, n, "बना रहा हूँ…"))
                    DKState.hint(VideoStudio.stepLine(i, n, "वीडियो बन रहा है…"))
                    var file: String? = null
                    if (!web) {
                        val flow = VideoGenFlow(drv, vh)
                        val app = ImageApp.GEMINI.target
                        var r = flow.makeClip(app, VideoStudio.partPrompt(plan, i), fresh = i == 0)
                        if (!r.ok && !r.limit && r.blocker == null && !stopAsked) {
                            DKLog.d("Video: भाग ${i + 1} दोबारा (${r.error} • ${r.how})")
                            TaskStatus.step(KEY, VideoStudio.stepLine(i, n, "एक बार फिर कोशिश…"))
                            r = flow.makeClip(app, VideoStudio.partPrompt(plan, i), fresh = false)
                        }
                        if (r.ok) file = r.file else {
                            needUser = r.blocker != null
                            stopWhy = r.error + (if (r.reply.isNotBlank()) " (Gemini ने लिखा: ${r.reply.take(150)})" else ""); break
                        }
                    } else {
                        val run = WebVideoRun(drv, think = { ask -> webThink(svc, ask) }, newVideo = { s -> vh.newestVideoSince(s) },
                            now = { System.currentTimeMillis() }, withShot = { ImageGenAgent.canSee(svc) }, enter = { vh.imeEnter() },
                            onStep = { s -> TaskStatus.step(KEY, VideoStudio.stepLine(i, n, s.take(120))) })
                        val r: WebRunResult = run.run(VideoStudio.webGoal(plan, i, hostOf(url) ?: "the website open now"))
                        if (r.ok) file = r.file else { needUser = r.needUser; stopWhy = if (r.needUser) "वेबसाइट पर लॉग-इन / जाँच चाहिए - आप कर दीजिए, फिर बोलिए \"आगे बढ़ो\"" else r.msg; break }
                    }
                    made++
                    if (file != null && file.startsWith("content://")) parts += file
                    TaskStatus.step(KEY, VideoStudio.stepLine(i, n, "तैयार ✓"))
                    LocalMemory.record(svc, "d", "वीडियो \"${plan.title}\" का भाग ${i + 1}/$n बना", "video_task")
                }
                var joined = false
                if (parts.size >= 2 && parts.size == made) {
                    TaskStatus.step(KEY, "सारे भाग जोड़कर एक वीडियो बना रहा हूँ…")
                    val out = withContext(Dispatchers.IO) { VideoJoin.join(svc, parts.map { Uri.parse(it) }, plan.title) }
                    if (out != null) { lastVideo = out; joined = true }
                }
                if (!joined) parts.lastOrNull()?.let { lastVideo = Uri.parse(it) }
                if (made < n) resume = Resume(plan, web, url, parts.toList(), made)
                state = when {
                    made == n -> TaskStatus.State.DONE
                    made > 0 || needUser -> TaskStatus.State.NOT_DONE
                    else -> TaskStatus.State.FAILED
                }
                val where = if (joined) "Movies/DK" else if (web) "Download" else "Gemini की फ़ोल्डर / Download"
                spoken = VideoStudio.doneLine(plan, made, joined, where, stopWhy) +
                    (if (made < n) " \"आगे बढ़ो\" बोलेंगे तो बाकी भाग वहीं से बनाऊँगा।" else "") +
                    (if (lastVideo != null) " दिखाऊँ?" else "")
                result = spoken.orEmpty()
                LocalMemory.record(svc, "d", result, "video_task")
            } catch (_: CancellationException) {
                state = TaskStatus.State.CANCELLED
                result = "मालिक के कहने पर रोका - $made भाग बने"
                if (made < plan.parts.size && plan.parts.isNotEmpty()) resume = Resume(plan, web, url, parts.toList(), made)
                spoken = null
            } catch (e: Exception) {
                DKLog.d("Video गड़बड़: ${e.javaClass.simpleName} ${e.message}")
                result = "वीडियो बनाना बीच में रुका - $made भाग बने"
                spoken = result
            } finally {
                TaskStatus.end(KEY, state, result)
            }
        }
        val where = if (web) (hostOf(url)?.let { "the website $it" } ?: "the video website open in the browser") else "the Gemini app"
        return "STARTED: DK is now making the video ON THE SCREEN in $where by itself - it writes the story and fixed characters, then makes it part by part " +
            "(about 8 seconds each, a few minutes per part) and joins them. Say ONE short Hindi line like 'ठीक है बॉस, वीडियो बना रहा हूँ - इसमें थोड़ा समय लगेगा, तब तक हम बात कर सकते हैं' and keep talking normally. " +
            "Progress: task_status. You get a SYSTEM NOTE with the real result when it ends - never claim it is ready before that. रोको = video_task action=stop."
    }

    /** बना हुआ वीडियो खोलो (फ़ोन के वीडियो प्लेयर में) */
    private suspend fun show(svc: DKAccessibilityService): String {
        val u = lastVideo ?: return "NO_VIDEO: no video has been made yet. Tell the user in one short Hindi line."
        return try {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            withContext(Dispatchers.Main) { svc.startActivity(i) }
            "SHOWN: the video is now playing on the screen. Say one short Hindi line."
        } catch (e: Exception) {
            DKLog.d("Video दिखाना: ${e.message}")
            "ERROR: could not open the video player. Tell the user the video is saved in the gallery (Movies/DK)."
        }
    }

    /** DK की अपनी AI (plan): DK कंप्यूटर की keys, न हों तो Live वाली Gemini key */
    private suspend fun think(svc: DKAccessibilityService, ask: String): String? {
        val ai = try { CoderAi.forComputer(svc) { p, f -> DKLog.d("Video AI: $p → $f") } } catch (_: Exception) { null }
        if (ai != null) {
            val t = try { runInterruptible(Dispatchers.IO) { ai.chat("You are a film director. Reply with JSON only.", listOf("user" to ask)) } }
                catch (e: CancellationException) { throw e } catch (e: Exception) { DKLog.d("Video AI गलती: ${e.message}"); null }
            if (!t.isNullOrBlank()) return t
        }
        return runInterruptible(Dispatchers.IO) { ImageGenAgent.textAsk(svc, ask) }
    }

    /** वेबसाइट का एक कदम: Live वाली key से (तस्वीर के साथ, अगर स्क्रीन देखना चालू), नहीं तो DK कंप्यूटर की AI (सिर्फ़ सूची) */
    private suspend fun webThink(svc: DKAccessibilityService, ask: String): String? {
        val shot = if (ImageGenAgent.canSee(svc)) try { svc.screenshotBase64() } catch (_: Exception) { null } else null
        runInterruptible(Dispatchers.IO) { ImageGenAgent.stepAsk(svc, ask, shot) }?.let { return it }
        val ai = try { CoderAi.forComputer(svc) { _, _ -> } } catch (_: Exception) { null } ?: return null
        return try { runInterruptible(Dispatchers.IO) { ai.chat("Reply with JSON only.", listOf("user" to ask)) } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    private suspend fun openUrl(svc: DKAccessibilityService, url: String): Boolean {
        val u = if (url.startsWith("http")) url else "https://$url"
        return try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val chrome = try { svc.packageManager.getPackageInfo("com.android.chrome", 0); true } catch (_: Exception) { false }
            if (chrome) i.setPackage("com.android.chrome")
            withContext(Dispatchers.Main) { svc.startActivity(i) }
            val ok = svc.waitForeground(8_000) { it != svc.packageName && it.isNotBlank() }
            delay(3_500)           // पेज लोड
            ok
        } catch (e: Exception) { DKLog.d("Video: लिंक नहीं खुला - ${e.message}"); false }
    }

    /** pure-ish: बोली / कहानी में लिखा लिंक */
    internal fun urlIn(t: String): String? = Regex("(https?://\\S+|\\b[a-z0-9-]+(\\.[a-z0-9-]+)*\\.(com|ai|app|io|net|org|in|co|studio|video)(/\\S*)?)", RegexOption.IGNORE_CASE)
        .find(t)?.value?.trimEnd('.', ',', '।', ')')

    internal fun hostOf(url: String): String? = url.takeIf { it.isNotBlank() }
        ?.let { try { Uri.parse(if (it.startsWith("http")) it else "https://$it").host?.removePrefix("www.") } catch (_: Exception) { null } }

    /** गैलरी में नया वीडियो - पहले से गिने भाग छोड़कर */
    private class VideoHost(private val svc: DKAccessibilityService, private val base: ImageGenAgent.Host, private val used: List<String>) : ImageGenHost by base {
        override suspend fun newestVideoSince(startMs: Long): String? = withContext(Dispatchers.IO) {
            try {
                val proj = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED)
                svc.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
                    "${MediaStore.Video.Media.DATE_ADDED} >= ?", arrayOf((startMs / 1000 - 2).toString()),
                    "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                    var found: String? = null
                    while (found == null && c.moveToNext()) {
                        val u = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)).toString()
                        if (u !in used) found = u
                    }
                    found
                }
            } catch (_: Exception) { null }
        }
    }

    /** DK लाइव को काम खत्म होने पर (असली नतीजा, ताकि बोलकर बताए) */
    fun liveNote(j: TaskStatus.Job): String? {
        if (j.key != KEY) return null
        val s = spoken?.takeIf { it.isNotBlank() } ?: return null
        spoken = null
        return "SYSTEM NOTE: DK Studio's video work (\"${j.task}\") has REALLY ended (state: ${j.state.en}). " +
            "Tell the user now, naturally and briefly, in the reply language, without adding anything that is not here: \"$s\". " +
            "If he says yes / दिखाओ, call video_task action=show. If he says आगे बढ़ो / continue, call video_task action=resume."
    }
}
