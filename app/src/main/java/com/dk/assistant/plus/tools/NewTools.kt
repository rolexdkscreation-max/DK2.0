package com.dk.assistant.plus.tools

import android.content.Context
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.FaceWhoActivity
import com.dk.assistant.plus.FeatureFlags
import com.dk.assistant.plus.GroupReport
import com.dk.assistant.plus.Mail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 15 नवंबर 2026 (मालिक: "जो भी बना रहे हो, उसके बारे में DK को पता हो - बोलूँ तो चला सके").
 * नई सुविधाओं के DK लाइव tools. हर एक का अपना flag (शुरू में बंद) - बंद हो तो Live को दिखता ही नहीं.
 * भेजना / हटाना = पहले यूज़र को सुनाओ, "हाँ" पर ही ([TwoStep]).
 */
object TwoStep {
    data class P(val key: String, val at: Long)
    @Volatile private var p: P? = null
    const val WINDOW_MS = 2 * 60_000L

    /** true = पहले पूछा था, वही काम, 2 मिनट के अंदर -> करो. false = अभी पूछो (pending रखा) */
    fun ok(key: String, confirmed: Boolean, now: Long): Boolean {
        val old = p
        if (confirmed && old != null && old.key == key && now - old.at in 0..WINDOW_MS) { p = null; return true }
        p = P(key, now); return false
    }
}

private fun bool(d: String) = JSONObject().put("type", "BOOLEAN").put("description", d)
private fun int(d: String) = JSONObject().put("type", "INTEGER").put("description", d)

object FaceWhoTool : PlusTool {
    override val name = "face_who"
    override val flag = FeatureFlags.FACE_ON
    override fun declaration() = PlusTools.decl(name,
        "Look through the phone's FRONT camera for ~10 seconds and tell WHO is in front of it: the Boss (owner), one of the saved known people " +
            "(family/friends saved in Settings → चेहरा पहचान), or an unknown face. Use it for 'देखो कौन है', 'मुझे पहचानो', 'ये कौन है', 'who is this'. " +
            "Answer only from the RESULT line, in one short Hindi line. No photo is saved.", JSONObject())

    override suspend fun run(c: Context, args: JSONObject): String = FaceWhoActivity.ask(c)
}

object EmailReadTool : PlusTool {
    override val name = "email_read"
    override val flag = FeatureFlags.EMAIL_ON
    override fun declaration() = PlusTools.decl(name,
        "Read the user's latest emails from his inbox (IMAP, his own App Password set in Settings → DK कंप्यूटर → ईमेल). " +
            "Use for 'मेरे ईमेल पढ़ो', 'कोई नया मेल आया?', 'last mail kisne bheja'. Summarise in short Hindi: who sent it, subject, one line of what it says. " +
            "Each email has a uid - keep it if the user wants to reply or delete.",
        JSONObject().put("count", int("how many newest emails, 1-10 (default 5)")).put("unread_only", bool("true = only unread (default true)")))

    override suspend fun run(c: Context, args: JSONObject): String = withContext(Dispatchers.IO) {
        try { Mail.Rules.summary(Mail.read(c, args.optInt("count", 5), args.optBoolean("unread_only", true))) }
        catch (e: Exception) { "ERROR: ${e.message}. Tell the user briefly." }
    }
}

object EmailSendTool : PlusTool {
    override val name = "email_send"
    override val flag = FeatureFlags.EMAIL_ON
    override fun declaration() = PlusTools.decl(name,
        "Send an email (or a reply) from the user's own email (SMTP, App Password). Needs the exact recipient email address. " +
            "ALWAYS first call with confirmed=false: then read the To, subject and full text to the user and ask 'भेज दूँ?'. " +
            "Only if he clearly says yes, call again with the SAME to/subject/body and confirmed=true. For a reply, subject = 'Re: <old subject>'.",
        JSONObject().put("to", PlusTools.str("recipient email address")).put("subject", PlusTools.str("subject"))
            .put("body", PlusTools.str("the email text, in the language the user wants")).put("confirmed", bool("true only after the user said yes")),
        listOf("to", "subject", "body"))

    override suspend fun run(c: Context, args: JSONObject): String {
        val to = args.optString("to").trim(); val sub = args.optString("subject").trim(); val body = args.optString("body").trim()
        if (!Mail.Rules.validAddr(to)) return "ERROR: '$to' is not an email address. Ask the user for the exact address."
        if (body.isBlank()) return "ERROR: empty email. Ask the user what to write."
        if (!TwoStep.ok("send|$to|$sub|${body.hashCode()}", args.optBoolean("confirmed"), System.currentTimeMillis()))
            return "confirm_needed: NOT sent yet. Read to the user: To $to, subject '$sub', text: $body - then ask 'भेज दूँ?'. Only on a clear yes call email_send again with the same fields and confirmed=true."
        return withContext(Dispatchers.IO) {
            try { Mail.send(c, to, sub, body); "SENT: the email to $to is sent. Tell the user in one short line." }
            catch (e: Exception) { "ERROR: ${e.message}. Not sent - tell the user." }
        }
    }
}

object EmailDeleteTool : PlusTool {
    override val name = "email_delete"
    override val flag = FeatureFlags.EMAIL_ON
    override fun declaration() = PlusTools.decl(name,
        "Delete one email (for spam etc.) by its uid from email_read. First call with confirmed=false, tell the user which email (sender + subject) " +
            "will be deleted and ask; only on a clear yes call again with confirmed=true.",
        JSONObject().put("uid", int("uid from email_read")).put("what", PlusTools.str("sender + subject, to say back to the user"))
            .put("confirmed", bool("true only after the user said yes")), listOf("uid"))

    override suspend fun run(c: Context, args: JSONObject): String {
        val uid = args.optLong("uid", -1L)
        if (uid <= 0) return "ERROR: no uid - call email_read first."
        if (!TwoStep.ok("del|$uid", args.optBoolean("confirmed"), System.currentTimeMillis()))
            return "confirm_needed: NOT deleted yet. Ask the user: 'सर, ${args.optString("what").ifBlank { "यह ईमेल" }} डिलीट कर दूँ?' Only on yes call again with confirmed=true."
        return withContext(Dispatchers.IO) {
            try { Mail.delete(c, uid); "DELETED: tell the user briefly." } catch (e: Exception) { "ERROR: ${e.message}" }
        }
    }
}

object GroupReportTool : PlusTool {
    override val name = "group_report"
    override val flag = FeatureFlags.WA_GROUP_REPORT
    override fun declaration() = PlusTools.decl(name,
        "WhatsApp group report from a saved template (Settings → ऑटो रिप्लाई → WhatsApp ग्रुप रिपोर्ट). " +
            "action=list: saved reports and their fields. action=fill: put the values the user spoke (or that you read from the camera / screen) into the template " +
            "and get the finished text - ask for any MISSING fields. action=send: after reading the finished text to the user and his clear yes, " +
            "send it to the template's WhatsApp group (confirmed=true). Values like 'कुल: 500, नकद: 200'.",
        JSONObject().put("action", PlusTools.str("list | fill | send")).put("report", PlusTools.str("template name"))
            .put("values", PlusTools.str("field: value pairs, comma separated (or JSON)")).put("confirmed", bool("send only: true after the user said yes")),
        listOf("action"))

    override suspend fun run(c: Context, args: JSONObject): String {
        val list = GroupReport.all(c)
        val action = args.optString("action", "list").lowercase().trim()
        if (action == "list" || list.isEmpty()) return GroupReport.Rules.catalog(list)
        val t = GroupReport.Rules.find(list, args.optString("report")) ?: return "NOT_FOUND: no report named '${args.optString("report")}'. " + GroupReport.Rules.catalog(list)
        val (text, missing) = GroupReport.Rules.fill(t.format, GroupReport.Rules.parseValues(args.optString("values")), GroupReport.today())
        if (missing.isNotEmpty()) return "MISSING: ask the user for: ${missing.joinToString()}. Then call fill again with all values. So far:\n$text"
        if (action != "send") return "FILLED (not sent yet) for group '${t.group}':\n$text\nRead it to the user and ask 'ग्रुप में भेज दूँ?'. On yes call action=send with the same values and confirmed=true."
        if (!TwoStep.ok("grp|${t.name}|${text.hashCode()}", args.optBoolean("confirmed"), System.currentTimeMillis()))
            return "confirm_needed: NOT sent. Read this to the user and ask 'भेज दूँ?':\n$text\nOn yes call action=send again with the same values and confirmed=true."
        val svc = c as? DKAccessibilityService ?: DKAccessibilityService.instance ?: return "ERROR: DK phone control (Accessibility) is off - it is needed to send in WhatsApp."
        return GroupReport.send(svc, t.group, text)?.let { "ERROR: $it. Tell the user." } ?: "SENT: report sent to WhatsApp group '${t.group}'. Tell the user briefly."
    }
}
