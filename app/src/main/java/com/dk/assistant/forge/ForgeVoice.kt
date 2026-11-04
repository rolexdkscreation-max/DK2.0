package com.dk.assistant.forge

import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.DKState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * नवंबर 2026: बनी हुई वेबसाइट पर आवाज़ से काम (DK लाइव का website_action tool):
 * edit = "हेडर का रंग नीला कर दो" (Forge दोबारा उसी वेबसाइट को बदलता है), open = फ़ोन पर ही (लोकल सर्वर) Chrome में,
 * link = लोकल लिंक, share = लिंक / फ़ाइल WhatsApp पर किसी को, deploy = इंटरनेट पर (सिर्फ़ मालिक के साफ़ कहने पर).
 */
object ForgeVoice {
    /** अभी वाली वेबसाइट (Forge में खुली), नहीं तो सबसे नई सेव की हुई */
    fun current(svc: DKAccessibilityService): File? =
        ForgeController.state.value.file?.takeIf { it.exists() } ?: ForgeController.savedSites(svc).firstOrNull()

    suspend fun run(svc: DKAccessibilityService, a: JSONObject): String {
        val act = a.optString("action").trim().lowercase()
        if (ForgeController.state.value.phase == ForgePhase.BUILDING && act != "status")
            return "busy: the website is still being built - tell the user to wait a moment; you get a SYSTEM NOTE when it is ready."
        val f = current(svc) ?: return "no_website: DK has not built any website yet. Ask the user what website to build (build_website)."
        return when (act) {
            "edit", "change", "update" -> {
                val change = a.optString("change").ifBlank { a.optString("details") }.trim()
                if (change.length < 3) return "Ask the user what exactly to change on the website."
                if (ForgeController.state.value.file?.exists() != true) ForgeController.openSaved(f)
                val r = ForgeController.edit(svc, change)
                if (r is ForgeController.StartResult.Started) { ForgeLauncher.open(svc); "edit_started: Website Forge is changing the website now (\"${change.take(80)}\") - tell the user briefly; you get a SYSTEM NOTE when it is done." }
                else "edit_not_started: ${r.message} Tell the user exactly this."
            }
            "open", "show", "chrome", "serve" -> {
                val url = LocalSite.openInChrome(svc, f) ?: return "not_opened: the local server or Chrome could not start. Tell the user."
                val lan = LocalSite.lanUrl(f.name)
                "opened_in_chrome: the website is running on the phone itself and is open in Chrome ($url)." +
                    (lan?.let { " Others on the same Wi-Fi can open $it ." } ?: "") + " Tell the user to check it."
            }
            "link", "url" -> {
                val p = LocalSite.ensure(svc)
                if (p == 0) return "no_link: the local server could not start."
                val pub = ForgeDeploy.lastUrl(svc, f)
                "links: on this phone ${LocalSite.localUrl(f.name)}" + (LocalSite.lanUrl(f.name)?.let { " ; same Wi-Fi $it" } ?: " ; (no Wi-Fi - only on this phone)") +
                    (pub?.let { " ; internet (deployed) $it" } ?: " ; not on the internet (only if he asks to deploy)") + ". Tell the user the link briefly."
            }
            "share", "send", "send_link" -> {
                val contact = a.optString("contact").trim()
                if (contact.isEmpty()) return "Ask the user whom to send the website to."
                if (a.optBoolean("as_file", false)) {
                    return com.dk.assistant.agent.android.SocialAgent.start(svc, JSONObject().put("action", "send_file").put("contact", contact)
                        .put("file", f.nameWithoutExtension), com.dk.assistant.agent.social.Platform.WHATSAPP)
                }
                val pub = ForgeDeploy.lastUrl(svc, f)
                val link = pub ?: run { if (LocalSite.ensure(svc) == 0) null else LocalSite.lanUrl(f.name) }
                    ?: return "no_link: there is no shareable link (no Wi-Fi and not deployed). Offer: send the website file itself (as_file=true) or deploy it if he wants."
                val r = com.dk.assistant.agent.android.SocialAgent.start(svc, JSONObject().put("action", "send_message").put("contact", contact)
                    .put("message", "मेरी वेबसाइट देखिए: $link"), com.dk.assistant.agent.social.Platform.WHATSAPP)
                if (pub == null) "$r (NOTE for you: this is a local Wi-Fi link - it opens only on the same Wi-Fi as this phone. Mention this; for everyone, he can say deploy.)" else r
            }
            "deploy", "publish" -> {
                if (!ForgeDeploy.userAsked(DKState.lastUser) && !ForgeDeploy.userAsked(a.optString("user_words")))
                    return "not_deployed: the user did not clearly ask to deploy / publish. Do NOT deploy on your own - only when he says so."
                DKLog.d("Forge: इंटरनेट पर डाल रहा हूँ - ${f.name}")
                when (val r = withContext(Dispatchers.IO) { ForgeDeploy.deploy(svc, f, ForgeController.state.value.prompt.ifBlank { f.nameWithoutExtension }, ForgeController.state.value.isEdit) }) {
                    is ForgeDeploy.Result.Live -> "deployed: the website is on the internet at ${r.url} (GitHub Pages, repo ${r.repo}). It can take 1-2 minutes to appear. Tell the user; if he wants, share it (website_action share)."
                    is ForgeDeploy.Result.Fail -> "deploy_failed: ${r.msg} Tell the user exactly this. Do NOT say it is live."
                }
            }
            else -> "Unknown website action. Use edit, open, link, share or deploy."
        }
    }
}
