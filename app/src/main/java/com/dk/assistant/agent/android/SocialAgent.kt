package com.dk.assistant.agent.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.DKState
import com.dk.assistant.NameMatch
import com.dk.assistant.PhoneActions
import com.dk.assistant.Prefs
import com.dk.assistant.agent.core.ConfirmGate
import com.dk.assistant.plus.FeatureFlags
import com.dk.assistant.agent.social.MediaPolicy
import com.dk.assistant.agent.social.OpenSpec
import com.dk.assistant.agent.social.Platform
import com.dk.assistant.agent.social.SocialAction
import com.dk.assistant.agent.social.SocialPlanner
import com.dk.assistant.agent.social.SharedItem
import com.dk.assistant.agent.social.SocialRequest
import com.dk.assistant.agent.social.SocialRequestParser
import com.dk.assistant.agent.social.SocialSmart
import com.dk.assistant.agent.social.SocialTaskRunner
import com.dk.assistant.agent.social.TaskReport
import com.dk.assistant.agent.social.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * DK AI Part 12: DK लाइव के tools (social_media_task / whatsapp_task / social_media_task_control) यहाँ आते हैं.
 * एक समय में एक काम. हालत बदले = Live को SYSTEM NOTE. caption/संदेश/कॉन्टैक्ट log नहीं होते.
 */
object SocialAgent {
    @Volatile private var runner: SocialTaskRunner? = null
    @Volatile private var job: Job? = null
    @Volatile var lastReport: TaskReport? = null
        private set
    private var seq = 0
    /** v3.67: यह काम WhatsApp संदेश है और मालिक ने "बिना पूछे भेजो" चालू किया है */
    @Volatile private var autoSend = false
    @Volatile private var sending: Triple<String, String, String>? = null

    private fun err(code: String, msg: String) = JSONObject().put("status", "error").put("error", code).put("message", msg).toString()

    fun busy() = job?.isActive == true
    /** 13 नवंबर 2026: DK Island में सोशल काम (कब शुरू / कब खत्म) */
    @Volatile var startedAt = 0L
        private set
    @Volatile var endedAt = 0L
        private set

    /** tool: social_media_task / whatsapp_task. return = मॉडल के लिए JSON */
    /** ownConfirm = true: भेजने से पहले पूछना बुलाने वाला (टेम्पलेट कमांड) खुद करता है, फिर [confirmByDk]. false = पहले जैसा */
    suspend fun start(svc: DKAccessibilityService, args: JSONObject, force: Platform? = null, ownConfirm: Boolean = false): String {
        if (busy()) return err("busy", "Another social task (${lastReport?.taskId}) is running. Use social_media_task_control status/cancel first.")
        val req = when (val p = SocialRequestParser.parse(args, force)) {
            is SocialRequestParser.Parsed.Err -> return err("bad_request", p.why)
            is SocialRequestParser.Parsed.Ok -> p.r
        }
        if (req.action != SocialAction.READ_UNREAD && !installed(svc, req.platform.pkg))
            return err("app_not_installed", "${req.platform.label} इस फ़ोन में इंस्टॉल नहीं है")
        // 16 नवंबर 2026: सोशल पोस्ट स्पेशलिस्ट (YouTube Shorts / गैलरी से खुद चुनना / कैप्शन / गाना / टैग) - Settings का स्विच चालू हो तभी
        val smartOn = FeatureFlags.on(svc, FeatureFlags.SOCIAL_SPECIALIST)
        if (req.smart && !smartOn)
            return err("specialist_off", "यह काम \"सोशल पोस्ट स्पेशलिस्ट\" से होता है - Settings > सोशल मीडिया में \"सोशल पोस्ट स्पेशलिस्ट\" चालू कीजिए")
        var batch: List<SharedItem> = emptyList()
        val fromGallery = smartOn && req.action.needsMedia && (req.mediaQuery.isNotEmpty() || req.media.trim().lowercase() == "latest_gallery")
        val wantGallery = when {
            req.platform == Platform.YOUTUBE -> true
            req.action == SocialAction.POST_VIDEO || req.action == SocialAction.POST_REEL -> true
            req.action == SocialAction.POST_PHOTO -> false
            else -> null
        }
        // media: सिर्फ़ जो यूज़र ने DK को share की (या स्पेशलिस्ट चालू हो और बोला हो तो गैलरी से)
        val media = if (fromGallery) {
            if (req.mediaQuery.isNotEmpty()) when (val f = withContext(Dispatchers.IO) { GalleryClip.find(svc, req.mediaQuery, wantGallery, req.mediaDays) }) {
                is GalleryClip.Result.Found -> f.item
                is GalleryClip.Result.Ask -> return err(f.code, f.msg)
            } else {
                if (!(if (wantGallery == true) GalleryClip.videoPermission(svc) else GalleryClip.mediaPermission(svc)))
                    return err("gallery_permission", "गैलरी की इजाज़त नहीं है - Settings > सोशल मीडिया > सोशल पोस्ट स्पेशलिस्ट में \"गैलरी की इजाज़त दो\" दबाइए")
                withContext(Dispatchers.IO) { GalleryClip.latest(svc, wantGallery) }
                    ?: return err("gallery_empty", "गैलरी में कोई ${if (wantGallery == true) "वीडियो" else "फोटो"} नहीं मिली")
            }
        } else if (req.action.needsMedia) {
            // 25 नवंबर 2026: कई share कीं ("जो भेजा वो सब") - Instagram/Facebook पोस्ट में फोटो+वीडियो दोनों चलते हैं
            val manyAsk = MediaPolicy.isMany(req.media)
            val want = when (req.action) {
                SocialAction.POST_VIDEO, SocialAction.POST_REEL -> if (manyAsk && SocialPlanner.canOnePost(req)) null else true
                SocialAction.POST_PHOTO -> if (manyAsk) null else false
                else -> if (req.platform == Platform.YOUTUBE) true else null
            }
            if (manyAsk) {
                val list = MediaPolicy.resolveMany(req.media, SharedMedia.all(svc), want, SharedMedia.KEEP)
                if (list.isEmpty()) return err("media_not_allowed", "DK के पास कोई ${if (want == true) "वीडियो" else "फोटो/वीडियो"} नहीं है। गैलरी में फोटो/वीडियो चुनकर Share -> DK दबाइए, फिर दोबारा कहिए।")
                batch = list
            }
            batch.firstOrNull() ?: MediaPolicy.resolve(req.media, SharedMedia.all(svc), want)
                ?: return err("media_not_allowed", "DK के पास कोई ${if (want == true) "वीडियो" else "फोटो"} नहीं है। गैलरी में फ़ाइल खोलकर Share -> DK दबाइए, फिर दोबारा कहिए।")
        } else if (req.action == SocialAction.SEND_FILE) {
            // नवंबर 2026: बोली गई फ़ाइल फ़ोन में ढूँढो (DK की बनाई / गैलरी / Download…)
            when (val f = withContext(Dispatchers.IO) { FileFinder.find(svc, req.file) }) {
                is FileFinder.Result.Found -> f.item
                is FileFinder.Result.Ask -> return err(f.code, f.msg)
            }
        } else null
        // WhatsApp: नाम -> नंबर
        var phone: String? = null; var who = req.contact
        if (req.platform == Platform.WHATSAPP && req.action !in setOf(SocialAction.READ_UNREAD, SocialAction.SEARCH_CHAT, SocialAction.POST_STORY)) {
            when (val c = contact(svc, req.contact)) {
                is Contact.Found -> { phone = c.digits; who = c.name }
                is Contact.Ask -> return err(c.code, c.msg)
            }
        }
        // नवंबर 2026: "गलती से चला गया, डिलीट करो" के लिए याद रखो किसे क्या भेजा (भेजना पूरा होने पर)
        sending = if (req.platform == Platform.WHATSAPP && req.action == SocialAction.SEND_MESSAGE && phone != null) Triple(who, phone, req.message) else null
        // 18 नवंबर 2026 (मालिक: "मैंने खुद बोला है तो तुरंत भेज दे"): सेटिंग चालू, या मालिक ने अपनी हाल की बात में खुद भेजने को कहा,
        // या अभी-अभी आवाज़ में "हाँ" दी (ConfirmGate) = दोबारा "भेज दूँ?" नहीं. अनजान (मालिक ने कहा ही नहीं) = पहले जैसा पूछो.
        val ownerAsked = !com.dk.assistant.agent.core.LiveWaGate.ownerNeedsAsk(DKState.ownerSaid(), com.dk.assistant.plus.ConfirmGate.untrustedNow()) ||
            com.dk.assistant.plus.ConfirmGate.justPassed("WhatsApp send")
        autoSend = !ownConfirm && req.platform == Platform.WHATSAPP && (req.action == SocialAction.SEND_MESSAGE || req.action == SocialAction.SEND_FILE) &&
            (Prefs.waAutoSend(svc) || ownerAsked)
        val id = "social-" + (++seq) + "-" + (System.currentTimeMillis() % 100000)
        val ready = if (smartOn && req.smart && media != null) smart(svc, req, media, fromGallery) else req
        // 25 नवंबर 2026: कई फोटो/वीडियो - Instagram/Facebook पोस्ट = एक ही पोस्ट में (carousel); स्टोरी/स्टेटस/रील = एक-एक करके, एक "हाँ" से सब
        val plan = SocialBatch.split(ready, batch)
        val task = SocialPlanner.plan(id, ready, media, phone, who, many = plan.onePost, seq = 1 to plan.queue.size.coerceAtLeast(1))
        val driver = AccessibilityScreenDriver(svc)
        val r = SocialTaskRunner(driver, { open(svc, it) }, { rep -> onUpdate(svc, rep) })
        runner = r
        startedAt = System.currentTimeMillis(); endedAt = 0L; lastReport = null
        batchLeft = (plan.queue.size - 1).coerceAtLeast(0); batchAuto = false
        job = svc.scope.launch {
            var rep: TaskReport? = null
            try { rep = r.run(task) } catch (e: Exception) { DKLog.d("Social: गलती ${e.javaClass.simpleName}") }
            finally { if (runner === r) runner = null }
            if (plan.queue.size > 1) runQueue(svc, id, ready, plan.queue, rep, driver)
        }
        DKLog.d("Social: ${req.platform.label} ${req.action.name.lowercase()} शुरू ($id, ${if (plan.queue.size > 1) "${plan.queue.size} एक-एक करके" else if (plan.onePost.size > 1) "${plan.onePost.size} एक पोस्ट में" else "एक"})")
        return JSONObject().put("status", "started").put("task_id", id)
            .apply { if (batch.size > 1) put("items", batch.size).put("items_label", com.dk.assistant.agent.social.MediaPolicy.countLabel(batch)) }
            .apply { if (plan.dropped > 0) put("not_used", plan.dropped).put("why_not_used", "Instagram takes at most ${SocialPlanner.onePostMax(req.platform)} in one post") }
            .put("message", "Started. You will get SYSTEM NOTEs as it progresses. Before anything is posted/sent you get status waiting_confirmation: then ask the user exactly that question and wait for their answer. Never say it is done before the SYSTEM NOTE says completed.")
            .toString()
    }

    /** एक-एक करके बाक़ी वाले (पहले वाले पर मालिक की असली "हाँ" मिल चुकी - उसी सवाल में सब बताया था) */
    @Volatile private var batchAuto = false
    @Volatile private var batchLeft = 0

    private suspend fun runQueue(svc: DKAccessibilityService, id: String, req: SocialRequest, queue: List<SharedItem>, first: TaskReport?, driver: AccessibilityScreenDriver) {
        var done = if (first != null && SocialBatch.ok(first.status)) 1 else 0
        var last = first
        if (done == 1) {
            for ((k, item) in queue.drop(1).withIndex()) {
                val n = k + 2
                kotlinx.coroutines.delay(2500)   // पिछला अपलोड शुरू हो जाए, ऐप सँभल जाए
                if (stopAsked) break
                batchLeft = queue.size - n; batchAuto = true
                val t = SocialPlanner.plan("$id-$n", req, item, null, req.contact, seq = n to queue.size)
                val r = SocialTaskRunner(driver, { open(svc, it) }, { rep -> onUpdate(svc, rep) })
                runner = r
                last = try { r.run(t) } catch (e: Exception) { DKLog.d("Social: गलती ${e.javaClass.simpleName}"); null }
                if (runner === r) runner = null
                if (last == null || !SocialBatch.ok(last.status)) break
                done++
            }
        }
        batchAuto = false; batchLeft = 0; stopAsked = false
        val note = SocialBatch.summary(req.platform.label, done, queue.size, last?.status)
        DKLog.d("Social $id: एक-एक करके $done/${queue.size}")
        try { svc.gemLive.visionNote("SYSTEM NOTE: social batch finished - $note Tell the user exactly this, honestly.") } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
    }

    @Volatile private var stopAsked = false

    /**
     * 16 नवंबर 2026: फोटो/वीडियो देखकर कैप्शन + हैशटैग (+ "auto" गाना), और आख़िरी सवाल में सुनाने वाली बात.
     * Gemini न चले तो पोस्ट नहीं रुकती - पर सवाल में साफ़ बताया जाता है कि कैप्शन नहीं बना.
     */
    private suspend fun smart(svc: DKAccessibilityService, req: SocialRequest, media: SharedItem, fromGallery: Boolean): SocialRequest {
        val video = media.mime.startsWith("video/")
        val notes = SocialPlanner.unsupported(req).toMutableList()
        var caption = req.caption; var about = ""; var tags = emptyList<String>(); var song = req.song; var shown = req.caption
        if (req.autoCaption || req.song == SocialSmart.SONG_AUTO) {
            val ask = SocialSmart.Ask(autoCaption = req.autoCaption, captionGiven = req.caption, hint = req.captionHint,
                song = req.song, tags = req.tags, language = req.captionLanguage.ifBlank { "hinglish" })
            val seen = withContext(Dispatchers.IO) { SocialCaption.make(svc, media, req.platform, req.action, ask) }
            val o = seen.out
            if (o == null) {
                if (req.autoCaption) notes += "कैप्शन अपने-आप नहीं बन पाया (${seen.why})"
                if (req.song == SocialSmart.SONG_AUTO) { notes += "गाना नहीं चुन पाया"; song = "" }
            } else {
                about = o.about
                if (req.autoCaption) { shown = o.caption; tags = o.hashtags; caption = SocialSmart.compose(req.platform, o.caption, o.hashtags) }
                if (req.song == SocialSmart.SONG_AUTO) { song = o.song; if (song.isBlank()) notes += "गाना नहीं चुन पाया" }
            }
            if (req.platform == Platform.YOUTUBE && seen.durationMs > 180_000) notes += "वीडियो 3 मिनट से लंबा है - YouTube इसे Short नहीं मानेगा"
        } else if (req.platform == Platform.YOUTUBE && caption.isNotBlank()) caption = SocialSmart.compose(req.platform, caption, emptyList())
        val label = if (fromGallery) SocialSmart.mediaLabel(media.at, System.currentTimeMillis(), video) else if (video) "यह वीडियो" else "यह फोटो"
        val canTag = req.platform != Platform.YOUTUBE && req.action != SocialAction.POST_STORY
        val canSong = req.action != SocialAction.POST_STORY
        val extra = SocialSmart.summary(label, about, shown, tags, if (canSong) song else "", if (canTag) req.tags else emptyList()) + notes.joinToString("") { "; $it" }
        DKLog.d("Social: स्पेशलिस्ट तैयार (${tags.size} हैशटैग, गाना ${if (song.isBlank()) "नहीं" else "हाँ"}, ${req.tags.size} टैग)")
        return req.copy(caption = caption, song = song, confirmExtra = extra)
    }

    /** टेम्पलेट कमांड: DK ने खुद यूज़र से पूछ लिया (या पुष्टि वाला switch बंद है) -> भेजो. false = कोई काम पुष्टि के इंतज़ार में नहीं */
    fun confirmByDk(): Boolean {
        val r = runner ?: return false
        if (lastReport?.status != TaskStatus.WAITING_CONFIRMATION) return false
        r.control(SocialTaskRunner.Control.CONFIRM); return true
    }

    /** tool: social_media_task_control(command, task_id) */
    fun control(args: JSONObject): String {
        val cmd = args.optString("command").trim().lowercase()
        val r = runner
        val rep = lastReport
        if (cmd == "status" || cmd.isEmpty()) return rep?.toJson()?.toString() ?: err("no_task", "No social task yet")
        if (r == null || rep == null || rep.status.finished) return err("no_running_task", "No social task is running")
        val tid = args.optString("task_id").trim()
        if (tid.isNotEmpty() && tid != rep.taskId) return err("wrong_task", "Running task is ${rep.taskId}")
        return when (cmd) {
            "cancel", "stop" -> { stopAsked = batchLeft > 0; r.control(SocialTaskRunner.Control.CANCEL); JSONObject().put("status", "cancelling").put("task_id", rep.taskId).toString() }
            "resume", "continue" -> { r.control(SocialTaskRunner.Control.RESUME); JSONObject().put("status", "resuming").put("task_id", rep.taskId).toString() }
            "confirm", "yes" -> {
                if (rep.status != TaskStatus.WAITING_CONFIRMATION) return err("not_waiting", "The task is not waiting for confirmation (status ${rep.status.name.lowercase()})")
                // मॉडल का कहना काफ़ी नहीं - यूज़र ने पूछने के बाद सच में "हाँ" कहा हो
                if (!ConfirmGate.accept(r.askedAt, DKState.lastUserAt, DKState.lastUser))
                    return err("no_user_yes", "Refused: the user has not clearly said yes after the question. Ask: \"${rep.message}\" and call confirm only after they say yes.")
                r.control(SocialTaskRunner.Control.CONFIRM)
                JSONObject().put("status", "confirmed").put("task_id", rep.taskId).toString()
            }
            else -> err("bad_command", "command must be status, confirm, cancel or resume")
        }
    }

    private fun onUpdate(svc: DKAccessibilityService, rep: TaskReport) {
        lastReport = rep
        // 13 नवंबर 2026 (मालिक): YouTube / फेसबुक / इंस्टाग्राम का काम DK Island के अंदर (हर कदम)
        if (rep.status.finished && endedAt == 0L) endedAt = System.currentTimeMillis()
        com.dk.assistant.DKIsland.poke()
        if (rep.status == TaskStatus.COMPLETED || rep.status == TaskStatus.SUBMITTED_UNVERIFIED)
            sending?.let { (n, d, m) -> com.dk.assistant.agent.social.WaDelete.remember(n, d, m); sending = null }
        DKLog.d("Social ${rep.taskId}: ${rep.status.name.lowercase()} (${rep.step}/${rep.totalSteps})")
        if (rep.status == TaskStatus.RUNNING && rep.step > 0) return   // बीच की हर बात नहीं
        // मालिक ने सेटिंग में पहले ही "हाँ" दे रखी है -> सवाल नहीं, सीधा भेजो (पूरा होने का SYSTEM NOTE बाद में आता है)
        // 25 नवंबर 2026: एक-एक करके वाले बाक़ी आइटम - पहले सवाल में "एक हाँ = सब" कहकर पूछा था और असली हाँ मिली थी
        if (rep.status == TaskStatus.WAITING_CONFIRMATION && batchAuto) {
            runner?.let { DKLog.d("Social ${rep.taskId}: batch - पहले वाली हाँ से"); it.control(SocialTaskRunner.Control.CONFIRM); return }
        }
        // बीच वाला (और बाक़ी हैं) पूरा हुआ - Live को छोटी बात, आख़िरी नतीजा runQueue देता है
        if (batchLeft > 0 && (rep.status == TaskStatus.COMPLETED || rep.status == TaskStatus.SUBMITTED_UNVERIFIED)) {
            try { svc.gemLive.visionNote("SYSTEM NOTE: social batch item ${rep.taskId} ${rep.status.name.lowercase()}, $batchLeft more to go - continuing automatically. Do not say all are done yet.") } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
            return
        }
        if (rep.status == TaskStatus.WAITING_CONFIRMATION && autoSend) {
            runner?.let { autoSend = false; DKLog.d("Social ${rep.taskId}: बिना पूछे भेजो (सेटिंग / मालिक ने खुद कहा)"); it.control(SocialTaskRunner.Control.CONFIRM); return }
        }
        val extra = when (rep.status) {
            TaskStatus.WAITING_CONFIRMATION -> " Ask the user exactly this question now and wait. If they say yes call social_media_task_control(command=confirm); if no, command=cancel."
            TaskStatus.WAITING_USER -> " Tell the user this. When they say it is done, call social_media_task_control(command=resume)."
            TaskStatus.SUBMITTED_UNVERIFIED -> " Tell the user honestly it was submitted but not confirmed."
            else -> ""
        }
        try { svc.gemLive.visionNote("SYSTEM NOTE: social task update " + rep.toJson().toString() + extra) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
        if (rep.status.finished) DKLog.d("Social ${rep.taskId}: खत्म")
    }

    private fun installed(svc: DKAccessibilityService, pkg: String) = try {
        svc.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) { pkg == Platform.WHATSAPP.pkg && try { svc.packageManager.getPackageInfo("com.whatsapp.w4b", 0); true } catch (_: Exception) { false } }

    internal suspend fun open(svc: DKAccessibilityService, o: OpenSpec): Boolean = withContext(Dispatchers.Main) {
        try {
            when (o) {
                is OpenSpec.ShareMedia -> {
                    val uri = Uri.parse(o.uri)
                    // 25 नवंबर 2026: एक पोस्ट में कई (Instagram carousel / Facebook) - सबकी पढ़ने की इजाज़त ClipData से
                    val list = o.uris.map { Uri.parse(it) }
                    val raw = if (list.size > 1) Intent(Intent.ACTION_SEND_MULTIPLE).setType(o.mime)
                        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(list))
                        .apply {
                            clipData = android.content.ClipData.newRawUri("", list[0]).also { cd -> list.drop(1).forEach { cd.addItem(android.content.ClipData.Item(it)) } }
                        }
                    else Intent(Intent.ACTION_SEND).setType(o.mime).putExtra(Intent.EXTRA_STREAM, uri)
                    val base = raw
                        .apply { if (o.text.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, o.text) }
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (o.jid.isNotEmpty()) {
                        // WhatsApp: सीधे उसी की चैट (jid) - चुनने वाली सूची नहीं
                        val pkg = if (installedPkg(svc, o.pkg)) o.pkg else "com.whatsapp.w4b"
                        svc.startActivity(Intent(base).setPackage(pkg).putExtra("jid", o.jid))
                    } else svc.startActivity(pickTarget(svc, base, o.pkg, o.targetHints))
                    true
                }
                is OpenSpec.ShareText -> {
                    val base = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, o.text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    svc.startActivity(pickTarget(svc, base, o.pkg, o.targetHints)); true
                }
                is OpenSpec.Url -> {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(o.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val pkg = if (installedPkg(svc, o.pkg)) o.pkg else "com.whatsapp.w4b"
                    svc.startActivity(i.setPackage(pkg)); true
                }
                is OpenSpec.Launch -> svc.packageManager.getLaunchIntentForPackage(o.pkg)?.let { svc.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } ?: false
                OpenSpec.None -> true
            }
        } catch (e: Exception) { DKLog.d("Social: ऐप नहीं खुला ${e.javaClass.simpleName}"); false }
    }

    private fun installedPkg(svc: DKAccessibilityService, pkg: String) = try { svc.packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }

    /** Instagram के कई share निशाने (Feed / Stories / Reels / Chats) - सही वाला चुनो */
    private fun pickTarget(svc: DKAccessibilityService, base: Intent, pkg: String, hints: List<String>): Intent {
        val i = Intent(base).setPackage(pkg)
        val list = try { svc.packageManager.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY) } catch (_: Exception) { emptyList() }
        val labeled = list.map { it to (it.loadLabel(svc.packageManager)?.toString().orEmpty() + " " + it.activityInfo.name).lowercase() }
        val best = hints.firstNotNullOfOrNull { h -> labeled.firstOrNull { it.second.contains(h.lowercase()) } }?.first
            ?: labeled.firstOrNull { !it.second.contains("direct") && !it.second.contains("chat") && !it.second.contains("message") }?.first
        return if (best != null) Intent(base).setClassName(best.activityInfo.packageName, best.activityInfo.name) else i
    }

    internal sealed class Contact {
        data class Found(val name: String, val digits: String) : Contact()
        data class Ask(val code: String, val msg: String) : Contact()
    }

    internal suspend fun contact(svc: DKAccessibilityService, name: String): Contact {
        // सीधे नंबर बोला
        SocialPlanner.waDigits(name)?.takeIf { name.count { it.isDigit() } >= 10 }?.let { return Contact.Found(name, it) }
        if (svc.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return Contact.Ask("contact_permission", "कॉन्टैक्ट पढ़ने की इजाज़त नहीं है - सेटिंग्स में कॉल/कॉन्टैक्ट इजाज़त दीजिए")
        val all = withContext(Dispatchers.IO) { try { PhoneActions.contacts(svc) } catch (_: Exception) { emptyList() } }
        val sure = NameMatch.best(name, all, { it.name }, min = 75)
        val hits = sure.ifEmpty { NameMatch.best(name, all, { it.name }, min = 60) }
        val uniq = hits.distinctBy { it.number.filter { ch -> ch.isDigit() }.takeLast(10) }
        return when {
            uniq.isEmpty() -> Contact.Ask("contact_not_found", "\"$name\" नाम का कोई कॉन्टैक्ट नहीं मिला")
            sure.isEmpty() || uniq.size > 1 -> Contact.Ask("contact_ambiguous", "\"$name\" से मिलते कई/अधूरे नाम: " + uniq.take(5).joinToString(", ") { it.name } + "। किसे? पूरा नाम पूछिए")
            else -> SocialPlanner.waDigits(uniq[0].number)?.let { Contact.Found(uniq[0].name, it) }
                ?: Contact.Ask("contact_no_number", "${uniq[0].name} का नंबर WhatsApp के लायक नहीं है")
        }
    }
}
