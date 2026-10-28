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
import com.dk.assistant.agent.social.MediaPolicy
import com.dk.assistant.agent.social.OpenSpec
import com.dk.assistant.agent.social.Platform
import com.dk.assistant.agent.social.SocialAction
import com.dk.assistant.agent.social.SocialPlanner
import com.dk.assistant.agent.social.SocialRequestParser
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

    private fun err(code: String, msg: String) = JSONObject().put("status", "error").put("error", code).put("message", msg).toString()

    fun busy() = job?.isActive == true

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
        // media: सिर्फ़ जो यूज़र ने DK को share की
        val media = if (req.action.needsMedia) {
            val want = when (req.action) { SocialAction.POST_VIDEO, SocialAction.POST_REEL -> true; SocialAction.POST_PHOTO -> false; else -> null }
            MediaPolicy.resolve(req.media, SharedMedia.all(svc), want)
                ?: return err("media_not_allowed", "DK के पास कोई ${if (want == true) "वीडियो" else "फोटो"} नहीं है। गैलरी में फ़ाइल खोलकर Share -> DK दबाइए, फिर दोबारा कहिए।")
        } else null
        // WhatsApp: नाम -> नंबर
        var phone: String? = null; var who = req.contact
        if (req.platform == Platform.WHATSAPP && req.action !in setOf(SocialAction.READ_UNREAD, SocialAction.SEARCH_CHAT)) {
            when (val c = contact(svc, req.contact)) {
                is Contact.Found -> { phone = c.digits; who = c.name }
                is Contact.Ask -> return err(c.code, c.msg)
            }
        }
        autoSend = !ownConfirm && req.platform == Platform.WHATSAPP && req.action == SocialAction.SEND_MESSAGE && Prefs.waAutoSend(svc)
        val id = "social-" + (++seq) + "-" + (System.currentTimeMillis() % 100000)
        val task = SocialPlanner.plan(id, req, media, phone, who)
        val driver = AccessibilityScreenDriver(svc)
        val r = SocialTaskRunner(driver, { open(svc, it) }, { rep -> onUpdate(svc, rep) })
        runner = r
        job = svc.scope.launch {
            try { r.run(task) } catch (e: Exception) { DKLog.d("Social: गलती ${e.javaClass.simpleName}") }
            finally { if (runner === r) runner = null }
        }
        DKLog.d("Social: ${req.platform.label} ${req.action.name.lowercase()} शुरू ($id)")
        return JSONObject().put("status", "started").put("task_id", id)
            .put("message", "Started. You will get SYSTEM NOTEs as it progresses. Before anything is posted/sent you get status waiting_confirmation: then ask the user exactly that question and wait for their answer. Never say it is done before the SYSTEM NOTE says completed.")
            .toString()
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
            "cancel", "stop" -> { r.control(SocialTaskRunner.Control.CANCEL); JSONObject().put("status", "cancelling").put("task_id", rep.taskId).toString() }
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
        DKLog.d("Social ${rep.taskId}: ${rep.status.name.lowercase()} (${rep.step}/${rep.totalSteps})")
        if (rep.status == TaskStatus.RUNNING && rep.step > 0) return   // बीच की हर बात नहीं
        // मालिक ने सेटिंग में पहले ही "हाँ" दे रखी है -> सवाल नहीं, सीधा भेजो (पूरा होने का SYSTEM NOTE बाद में आता है)
        if (rep.status == TaskStatus.WAITING_CONFIRMATION && autoSend) {
            runner?.let { autoSend = false; DKLog.d("Social ${rep.taskId}: बिना पूछे भेजो (सेटिंग)"); it.control(SocialTaskRunner.Control.CONFIRM); return }
        }
        val extra = when (rep.status) {
            TaskStatus.WAITING_CONFIRMATION -> " Ask the user exactly this question now and wait. If they say yes call social_media_task_control(command=confirm); if no, command=cancel."
            TaskStatus.WAITING_USER -> " Tell the user this. When they say it is done, call social_media_task_control(command=resume)."
            TaskStatus.SUBMITTED_UNVERIFIED -> " Tell the user honestly it was submitted but not confirmed."
            else -> ""
        }
        try { svc.gemLive.visionNote("SYSTEM NOTE: social task update " + rep.toJson().toString() + extra) } catch (_: Exception) {}
        if (rep.status.finished) DKLog.d("Social ${rep.taskId}: खत्म")
    }

    private fun installed(svc: DKAccessibilityService, pkg: String) = try {
        svc.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) { pkg == Platform.WHATSAPP.pkg && try { svc.packageManager.getPackageInfo("com.whatsapp.w4b", 0); true } catch (_: Exception) { false } }

    private suspend fun open(svc: DKAccessibilityService, o: OpenSpec): Boolean = withContext(Dispatchers.Main) {
        try {
            when (o) {
                is OpenSpec.ShareMedia -> {
                    val uri = Uri.parse(o.uri)
                    val base = Intent(Intent.ACTION_SEND).setType(o.mime).putExtra(Intent.EXTRA_STREAM, uri)
                        .apply { if (o.text.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, o.text) }
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    svc.startActivity(pickTarget(svc, base, o.pkg, o.targetHints)); true
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

    private sealed class Contact {
        data class Found(val name: String, val digits: String) : Contact()
        data class Ask(val code: String, val msg: String) : Contact()
    }

    private suspend fun contact(svc: DKAccessibilityService, name: String): Contact {
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
