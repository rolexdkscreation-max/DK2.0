package com.dk.assistant.connect

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * v3.72 कनेक्टर हब - शुद्ध (Android-रहित) हिस्सा, ताकि JVM tests चल सकें.
 *
 * - [Catalog]: हर कनेक्टर, उसके काम (action), ज़रूरी चीज़ें (params) और कौन-सा काम "पूछकर" होगा (risky).
 * - [ToolText]: Gemini Live के `connector_task` tool का विवरण - सिर्फ़ जुड़े कनेक्टर.
 * - [Gate]: भेजना / मिटाना / शेयर / पोस्ट = पहले user से "हाँ" (confirmed=true), पढ़ना/खोजना = सीधे.
 * - [Mcp]: MCP (Model Context Protocol) JSON-RPC संदेश बनाना + जवाब (JSON या SSE) पढ़ना.
 * - [Gmail], [GDocs], [GTime], [DriveQ], [Graph]: API जवाबों को छोटे साफ़ text में बदलना.
 */
data class ToolSpec(val name: String, val params: List<String>, val desc: String, val risky: Boolean = false)

enum class Kind { GOOGLE, META, GITHUB, MCP, AGENT }

data class ConnectorDef(
    val id: String, val title: String, val sub: String, val kind: Kind,
    val scopes: List<String> = emptyList(), val tools: List<ToolSpec> = emptyList()
)

object Catalog {
    private const val G = "https://www.googleapis.com/auth/"

    val DRIVE = ConnectorDef("drive", "Google Drive", "फ़ाइलें खोजो, पढ़ो, नई text फ़ाइल, शेयर, ट्रैश", Kind.GOOGLE, listOf(G + "drive"), listOf(
        ToolSpec("search", listOf("query"), "find files by name/text"),
        ToolSpec("read", listOf("file_id"), "read a file as text (Docs/Sheets exported)"),
        ToolSpec("upload_text", listOf("name", "text"), "create a new text file"),
        ToolSpec("share", listOf("file_id", "email", "role"), "share with an email (role reader/writer); empty email = anyone with link", risky = true),
        ToolSpec("trash", listOf("file_id"), "move a file to trash", risky = true)))

    val GMAIL = ConnectorDef("gmail", "Gmail", "मेल खोजो, पढ़ो, ड्राफ़्ट, भेजो", Kind.GOOGLE, listOf(G + "gmail.modify"), listOf(
        ToolSpec("search", listOf("query", "max"), "search mail (Gmail search syntax, e.g. is:unread, from:x)"),
        ToolSpec("read", listOf("id"), "read one mail"),
        ToolSpec("draft", listOf("to", "subject", "body"), "save a draft (not sent)"),
        ToolSpec("send", listOf("to", "subject", "body"), "send a mail", risky = true)))

    val CALENDAR = ConnectorDef("calendar", "Google Calendar", "आने वाले इवेंट, नया इवेंट, इवेंट हटाओ", Kind.GOOGLE, listOf(G + "calendar"), listOf(
        ToolSpec("list", listOf("days"), "upcoming events for the next N days (default 7)"),
        ToolSpec("create", listOf("title", "start", "end", "description"), "create event; start/end like 2026-10-26T15:00 (local) or 2026-10-26 for all-day"),
        ToolSpec("delete", listOf("event_id"), "delete an event", risky = true)))

    val CONTACTS = ConnectorDef("contacts", "Google Contacts", "नाम से नंबर/ईमेल खोजो", Kind.GOOGLE, listOf(G + "contacts.readonly"), listOf(
        ToolSpec("search", listOf("query"), "find a contact's phone/email")))

    val TASKS = ConnectorDef("tasks", "Google Tasks", "काम की सूची, नया काम, काम पूरा", Kind.GOOGLE, listOf(G + "tasks"), listOf(
        ToolSpec("list", emptyList(), "open tasks"),
        ToolSpec("add", listOf("title", "notes", "due"), "add a task (due like 2026-10-26)"),
        ToolSpec("done", listOf("task_id"), "mark a task completed")))

    val SHEETS_DOCS = ConnectorDef("sheets_docs", "Google Sheets / Docs", "शीट पढ़ो/पंक्ति जोड़ो, डॉक बनाओ/पढ़ो", Kind.GOOGLE,
        listOf(G + "spreadsheets", G + "documents"), listOf(
        ToolSpec("sheet_read", listOf("sheet_id", "range"), "read cells (range like Sheet1!A1:D20)"),
        ToolSpec("sheet_append", listOf("sheet_id", "range", "values"), "append one row; values = JSON array or comma separated"),
        ToolSpec("doc_create", listOf("title", "text"), "create a Google Doc"),
        ToolSpec("doc_read", listOf("doc_id"), "read a Google Doc as text")))

    val YOUTUBE = ConnectorDef("youtube", "YouTube", "वीडियो खोजो, मेरे वीडियो, मेरी playlists", Kind.GOOGLE, listOf(G + "youtube.readonly"), listOf(
        ToolSpec("search", listOf("query"), "search videos"),
        ToolSpec("my_videos", emptyList(), "the user's own uploaded videos with views"),
        ToolSpec("playlists", emptyList(), "the user's playlists")))

    val GITHUB = ConnectorDef("github", "GitHub", "repos, फ़ाइलें, कोड खोजो (कोडिंग एजेंट वाला token)", Kind.GITHUB, tools = listOf(
        ToolSpec("repos", emptyList(), "list the user's repositories"),
        ToolSpec("files", listOf("repo", "branch"), "list files of a repo (owner/name)"),
        ToolSpec("read", listOf("repo", "path", "branch"), "read a file"),
        ToolSpec("search", listOf("repo", "query"), "search code in a repo")))

    val FB_PAGE = ConnectorDef("fb_page", "Facebook Page", "अपने पेज की पोस्ट, कमेंट, जवाब, नई पोस्ट (Graph API)", Kind.META, tools = listOf(
        ToolSpec("pages", emptyList(), "the user's Facebook Pages"),
        ToolSpec("posts", listOf("page_id"), "recent posts of a page"),
        ToolSpec("comments", listOf("post_id"), "comments on a post"),
        ToolSpec("post", listOf("page_id", "message", "link"), "publish a text/link post on the page", risky = true),
        ToolSpec("photo", listOf("page_id", "image_url", "caption"), "publish a photo from a public image URL", risky = true),
        ToolSpec("reply", listOf("comment_id", "message"), "reply to a comment", risky = true)))

    val INSTAGRAM = ConnectorDef("instagram", "Instagram (Business/Creator)", "प्रोफ़ाइल, पोस्ट, कमेंट, जवाब, फ़ोटो पोस्ट (Graph API)", Kind.META, tools = listOf(
        ToolSpec("profile", emptyList(), "username, followers, posts count"),
        ToolSpec("media", emptyList(), "recent posts with likes/comments"),
        ToolSpec("comments", listOf("media_id"), "comments on a post"),
        ToolSpec("publish_image", listOf("image_url", "caption"), "post a photo from a PUBLIC image URL", risky = true),
        ToolSpec("reply", listOf("comment_id", "message"), "reply to a comment", risky = true)))

    /** सिर्फ़ जानकारी के कार्ड - काम पहले से चल रहा फ़ोन-एजेंट (social_media_task) करता है */
    val FB_PERSONAL = ConnectorDef("fb_personal", "Facebook (निजी प्रोफ़ाइल)", "फ़ोन-एजेंट से: Facebook ऐप खोलकर पोस्ट/मैसेज/कमेंट", Kind.AGENT)
    val IG_PERSONAL = ConnectorDef("ig_personal", "Instagram (निजी अकाउंट)", "फ़ोन-एजेंट से: Instagram ऐप खोलकर पोस्ट/स्टोरी/मैसेज", Kind.AGENT)

    val GOOGLE = listOf(DRIVE, GMAIL, CALENDAR, CONTACTS, TASKS, SHEETS_DOCS, YOUTUBE)
    val ALL = GOOGLE + listOf(GITHUB, FB_PAGE, INSTAGRAM, FB_PERSONAL, IG_PERSONAL)

    fun byId(id: String): ConnectorDef? = ALL.firstOrNull { it.id == id.trim().lowercase() }

    /** मॉडल कभी "google_drive" / "Gmail" / "facebook" लिख दे - सही id */
    fun resolve(raw: String): String {
        val s = raw.trim().lowercase().replace(' ', '_').replace('-', '_')
        return when {
            byId(s) != null -> s
            s.startsWith("mcp_") -> s
            "drive" in s -> "drive"
            "gmail" in s || s == "mail" || s == "email" -> "gmail"
            "calendar" in s -> "calendar"
            "contact" in s -> "contacts"
            "task" in s -> "tasks"
            "sheet" in s || "doc" in s -> "sheets_docs"
            "youtube" in s -> "youtube"
            "github" in s -> "github"
            "insta" in s -> "instagram"
            "facebook" in s || s == "fb" || "page" in s -> "fb_page"
            else -> s
        }
    }
}

object ToolText {
    const val NAME = "connector_task"
    const val FALLBACK = "Use the user's connected accounts."

    private fun line(id: String, tools: List<ToolSpec>, max: Int = 30): String =
        "$id: " + tools.take(max).joinToString("; ") { t ->
            t.name + "(" + t.params.joinToString(",") + ")" + (if (t.risky) "[ASK FIRST]" else "") +
                (if (t.desc.isNotBlank()) " - " + t.desc.take(90) else "")
        }

    /**
     * जुड़े कनेक्टर का पूरा विवरण. connected = built-in ids, mcp = server id -> (नाम, tools).
     * कोई नहीं जुड़ा = null (tool जोड़ा ही नहीं जाता).
     */
    fun describe(connected: List<ConnectorDef>, mcp: Map<String, Pair<String, List<ToolSpec>>> = emptyMap()): String? {
        val withTools = connected.filter { it.tools.isNotEmpty() }
        if (withTools.isEmpty() && mcp.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("Work in the user's connected accounts/apps (their own Gmail, Drive, Calendar, Facebook Page, GitHub, MCP servers...). ")
        sb.append("Call with connector, action and args (a JSON object as a string, e.g. {\"query\":\"invoice\"}). ")
        sb.append("Reading/searching/listing needs no permission - just do it. ")
        sb.append("Actions marked [ASK FIRST] send, post, share or delete something: first tell the user in one short line exactly what will happen (to whom / what text) and wait for a clear yes; only then call again with confirmed=true. Never set confirmed=true without that yes. ")
        sb.append("Use ids returned by earlier search/list results. For the user's PERSONAL Facebook profile or personal Instagram use social_media_task instead. Connected: ")
        withTools.forEach { sb.append(line(it.id, it.tools)).append(" | ") }
        mcp.forEach { (id, p) -> sb.append(line(id, p.second, 25)).append(" (MCP server '" + p.first.take(40) + "') | ") }
        return sb.toString().trimEnd(' ', '|').take(6000)
    }
}

object Gate {
    sealed class Verdict {
        object Go : Verdict()
        data class Ask(val message: String) : Verdict()
        data class Bad(val message: String) : Verdict()
    }

    /** tools = उस कनेक्टर के काम; action न मिले = साफ़ गलती (मॉडल अंदाज़ा न लगाए) */
    fun check(connector: String, tools: List<ToolSpec>, action: String, args: JSONObject, confirmed: Boolean): Verdict {
        val t = tools.firstOrNull { it.name.equals(action.trim(), ignoreCase = true) }
            ?: return Verdict.Bad("FAILED: unknown action '$action' for $connector. Available: " + tools.joinToString(", ") { it.name })
        if (!t.risky || confirmed) return Verdict.Go
        return Verdict.Ask("NEEDS USER CHOICE - confirmation, not done yet: $connector.${t.name} " + summary(args) +
            ". Tell the user in one short line exactly what will be done and ask yes/no. Only if they clearly say yes, call connector_task again with the same arguments and confirmed=true.")
    }

    fun summary(args: JSONObject): String {
        val parts = args.keys().asSequence().map { k -> k + "=" + args.opt(k).toString().replace('\n', ' ').take(120) }.toList()
        return if (parts.isEmpty()) "" else "(" + parts.joinToString(", ") + ")"
    }
}

object Args {
    /** मॉडल args को JSON string भेजता है; खाली/टूटा भी संभालो */
    fun parse(raw: Any?): JSONObject = when (raw) {
        null -> JSONObject()
        is JSONObject -> raw
        else -> {
            val s = raw.toString().trim()
            if (s.isEmpty() || s == "null") JSONObject()
            else try { JSONObject(s) } catch (_: Exception) {
                // ```json ... ``` या बीच में JSON
                val a = s.indexOf('{'); val b = s.lastIndexOf('}')
                if (a >= 0 && b > a) try { JSONObject(s.substring(a, b + 1)) } catch (_: Exception) { JSONObject().put("query", s) }
                else JSONObject().put("query", s)
            }
        }
    }

    /** sheet_append की values: JSON array या "a, b, c" */
    fun row(v: Any?): JSONArray {
        if (v is JSONArray) return v
        val s = v?.toString()?.trim().orEmpty()
        if (s.startsWith("[")) try { return JSONArray(s) } catch (_: Exception) {}
        return JSONArray().apply { s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { put(it) } }
    }
}

object Mcp {
    const val PROTOCOL = "2025-06-18"

    fun request(id: Int, method: String, params: JSONObject? = null): String =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).apply { if (params != null) put("params", params) }.toString()

    fun notification(method: String): String = JSONObject().put("jsonrpc", "2.0").put("method", method).toString()

    fun initParams(): JSONObject = JSONObject().put("protocolVersion", PROTOCOL)
        .put("capabilities", JSONObject())
        .put("clientInfo", JSONObject().put("name", "DK-Assistant").put("version", "3.72"))

    /**
     * Streamable HTTP का जवाब: सीधा JSON, या SSE ("event: message\ndata: {...}").
     * जिस संदेश का id मेल खाए वही (न मिले तो पहला जिसमें result/error हो).
     */
    fun parse(body: String, contentType: String?, wantId: Int? = null): JSONObject? {
        val t = body.trim()
        val msgs = mutableListOf<JSONObject>()
        val sse = contentType?.contains("event-stream", true) == true || (!t.startsWith("{") && !t.startsWith("[") && "data:" in t)
        if (!sse) {
            try {
                if (t.startsWith("[")) { val a = JSONArray(t); for (i in 0 until a.length()) a.optJSONObject(i)?.let { msgs += it } }
                else msgs += JSONObject(t)
            } catch (_: Exception) {}
        } else {
            val buf = StringBuilder()
            fun flush() { if (buf.isNotEmpty()) { try { msgs += JSONObject(buf.toString()) } catch (_: Exception) {}; buf.setLength(0) } }
            for (ln in t.lines()) {
                when {
                    ln.startsWith("data:") -> { if (buf.isNotEmpty()) buf.append('\n'); buf.append(ln.removePrefix("data:").trimStart()) }
                    ln.isBlank() -> flush()
                }
            }
            flush()
        }
        val withPayload = msgs.filter { it.has("result") || it.has("error") }
        return withPayload.firstOrNull { wantId != null && it.optInt("id", -1) == wantId } ?: withPayload.firstOrNull()
    }

    private val RISKY_WORDS = Regex("(send|delete|remove|post|publish|create|update|write|share|merge|push|pay|transfer|cancel|archive|move|edit|reply|comment|upload|invite)", RegexOption.IGNORE_CASE)

    /** tools/list का result -> ToolSpec. readOnlyHint=true = सीधा, destructive/लिखने वाला नाम = पूछकर */
    fun tools(result: JSONObject?): List<ToolSpec> {
        val a = result?.optJSONArray("tools") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            val props = o.optJSONObject("inputSchema")?.optJSONObject("properties")
            val params = props?.keys()?.asSequence()?.toList()?.take(8) ?: emptyList()
            val ann = o.optJSONObject("annotations")
            val risky = when {
                ann?.optBoolean("readOnlyHint", false) == true -> false
                ann?.optBoolean("destructiveHint", false) == true -> true
                else -> RISKY_WORDS.containsMatchIn(o.optString("name"))
            }
            ToolSpec(o.optString("name"), params, o.optString("description").replace('\n', ' ').take(140), risky)
        }.filter { it.name.isNotBlank() }
    }

    fun toolsToJson(list: List<ToolSpec>): String = JSONArray().apply {
        list.forEach { t -> put(JSONObject().put("n", t.name).put("p", JSONArray(t.params)).put("d", t.desc).put("r", t.risky)) }
    }.toString()

    fun toolsFromJson(s: String?): List<ToolSpec> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            val p = o.optJSONArray("p") ?: JSONArray()
            ToolSpec(o.optString("n"), (0 until p.length()).map { p.optString(it) }, o.optString("d"), o.optBoolean("r"))
        }
    } catch (_: Exception) { emptyList() }

    /** tools/call का result -> text (content[].text, structuredContent, isError) */
    fun text(msg: JSONObject?): String {
        if (msg == null) return "MCP server gave no answer."
        msg.optJSONObject("error")?.let { return "MCP error: " + it.optString("message").ifBlank { it.toString() }.take(400) }
        val r = msg.optJSONObject("result") ?: return msg.toString().take(2000)
        val parts = mutableListOf<String>()
        r.optJSONArray("content")?.let { c ->
            for (i in 0 until c.length()) {
                val o = c.optJSONObject(i) ?: continue
                when (o.optString("type")) {
                    "text" -> parts += o.optString("text")
                    "resource" -> o.optJSONObject("resource")?.optString("text")?.takeIf { it.isNotBlank() }?.let { parts += it }
                    "resource_link" -> parts += (o.optString("name") + " " + o.optString("uri")).trim()
                    "image" -> parts += "[image]"
                }
            }
        }
        if (parts.isEmpty()) r.optJSONObject("structuredContent")?.let { parts += it.toString() }
        val out = parts.joinToString("\n").ifBlank { "done (empty result)" }
        return if (r.optBoolean("isError")) "MCP tool error: $out" else out
    }

    /** मॉडल को tool नाम नहीं मिलना चाहिए जो server पर नहीं; args = JSON */
    fun callParams(tool: String, args: JSONObject): JSONObject = JSONObject().put("name", tool).put("arguments", args)
}

object Gmail {
    private val b64url = java.util.Base64.getUrlEncoder().withoutPadding()

    private fun header(v: String): String =
        if (v.all { it.code in 32..126 }) v
        else "=?UTF-8?B?" + java.util.Base64.getEncoder().encodeToString(v.toByteArray(Charsets.UTF_8)) + "?="

    /** RFC 2822 संदेश (हिंदी subject/body भी) -> Gmail API का raw (base64url) */
    fun raw(to: String, subject: String, body: String): String {
        val mime = "To: " + to.replace("\r", "").replace("\n", "") + "\r\n" +
            "Subject: " + header(subject.replace("\r", "").replace("\n", " ")) + "\r\n" +
            "MIME-Version: 1.0\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\n" +
            java.util.Base64.getMimeEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
        return b64url.encodeToString(mime.toByteArray(Charsets.UTF_8))
    }

    fun headerOf(payload: JSONObject?, name: String): String {
        val h = payload?.optJSONArray("headers") ?: return ""
        for (i in 0 until h.length()) { val o = h.optJSONObject(i) ?: continue; if (o.optString("name").equals(name, true)) return o.optString("value") }
        return ""
    }

    private fun decode(data: String): String = try {
        String(java.util.Base64.getUrlDecoder().decode(data.replace('+', '-').replace('/', '_').trimEnd('=')), Charsets.UTF_8)
    } catch (_: Exception) { "" }

    /** संदेश का text: पहले text/plain, न हो तो text/html से tags हटाकर */
    fun bodyText(payload: JSONObject?): String {
        if (payload == null) return ""
        fun find(p: JSONObject, mime: String): String? {
            if (p.optString("mimeType").startsWith(mime)) p.optJSONObject("body")?.optString("data")?.takeIf { it.isNotBlank() }?.let { return decode(it) }
            val parts = p.optJSONArray("parts") ?: return null
            for (i in 0 until parts.length()) parts.optJSONObject(i)?.let { find(it, mime) }?.let { return it }
            return null
        }
        find(payload, "text/plain")?.let { return it.trim() }
        find(payload, "text/html")?.let { return Html.strip(it) }
        return payload.optJSONObject("body")?.optString("data")?.let { decode(it) }?.trim().orEmpty()
    }
}

object Html {
    fun strip(h: String): String = h.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>"), "\n").replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim()
}

object GDocs {
    /** documents.get का body.content -> सादा text */
    fun text(doc: JSONObject?): String {
        val c = doc?.optJSONObject("body")?.optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until c.length()) {
            val els = c.optJSONObject(i)?.optJSONObject("paragraph")?.optJSONArray("elements") ?: continue
            for (j in 0 until els.length()) els.optJSONObject(j)?.optJSONObject("textRun")?.optString("content")?.let { sb.append(it) }
        }
        return sb.toString().trim()
    }
}

object GTime {
    private val LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    /**
     * Calendar का start/end: "2026-10-26" = पूरा दिन ({date}), "2026-10-26T15:00" = समय ({dateTime,timeZone}).
     * end न हो: समय वाले = +1 घंटा, पूरे दिन वाले = अगला दिन.
     */
    fun eventTimes(start: String, end: String?, tz: String): Pair<JSONObject, JSONObject>? {
        val s = start.trim(); val e = end?.trim().orEmpty()
        if (s.length == 10) {
            val d = try { LocalDate.parse(s) } catch (_: Exception) { return null }
            val ed = if (e.length == 10) try { LocalDate.parse(e) } catch (_: Exception) { d.plusDays(1) } else d.plusDays(1)
            return JSONObject().put("date", d.toString()) to JSONObject().put("date", (if (ed.isAfter(d)) ed else d.plusDays(1)).toString())
        }
        val sd = local(s) ?: return null
        val edt = local(e)?.takeIf { it.isAfter(sd) } ?: sd.plusHours(1)
        return JSONObject().put("dateTime", sd.format(LOCAL)).put("timeZone", tz) to JSONObject().put("dateTime", edt.format(LOCAL)).put("timeZone", tz)
    }

    fun local(v: String): LocalDateTime? {
        var s = v.trim().replace(' ', 'T')
        if (s.isEmpty()) return null
        // offset/Z हटाओ - timeZone अलग भेजते हैं
        s = s.replace(Regex("(Z|[+-]\\d{2}:?\\d{2})$"), "")
        if (s.length == 16) s += ":00"
        return try { LocalDateTime.parse(s.take(19)) } catch (_: Exception) { null }
    }

    /** Tasks API का due (सिर्फ़ तारीख़ मायने रखती है) */
    fun taskDue(v: String?): String? {
        val s = v?.trim().orEmpty()
        if (s.length < 10) return null
        return try { LocalDate.parse(s.take(10)).toString() + "T00:00:00.000Z" } catch (_: Exception) { null }
    }
}

object DriveQ {
    /** user के शब्द -> Drive files.list का q. पहले से Drive query हो तो वैसी ही */
    fun build(query: String): String {
        val q = query.trim()
        if (Regex("\\b(contains|mimeType|modifiedTime|trashed|in parents)\\b|=").containsMatchIn(q)) return q
        if (q.isEmpty()) return "trashed = false"
        val e = q.replace("\\", "\\\\").replace("'", "\\'")
        return "(name contains '$e' or fullText contains '$e') and trashed = false"
    }

    fun exportMime(mime: String): String? = when (mime) {
        "application/vnd.google-apps.document" -> "text/plain"
        "application/vnd.google-apps.spreadsheet" -> "text/csv"
        "application/vnd.google-apps.presentation" -> "text/plain"
        "application/vnd.google-apps.drawing" -> null
        else -> null
    }

    fun isText(mime: String) = mime.startsWith("text/") || mime in setOf("application/json", "application/xml", "application/javascript", "application/x-yaml")
}

object Graph {
    const val VERSION = "v26.0"
    const val BASE = "https://graph.facebook.com/$VERSION"

    data class Page(val id: String, val name: String, val token: String, val igId: String, val igUser: String)

    /** /me/accounts का जवाब -> पेज (टोकन सहित) */
    fun pages(json: JSONObject?): List<Page> {
        val a = json?.optJSONArray("data") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            val ig = o.optJSONObject("instagram_business_account")
            Page(o.optString("id"), o.optString("name"), o.optString("access_token"), ig?.optString("id").orEmpty(), ig?.optString("username").orEmpty())
        }.filter { it.id.isNotBlank() }
    }

    fun pagesToJson(p: List<Page>): String = JSONArray().apply {
        p.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("token", it.token).put("ig", it.igId).put("igu", it.igUser)) }
    }.toString()

    fun pagesFromJson(s: String?): List<Page> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Page(it.optString("id"), it.optString("name"), it.optString("token"), it.optString("ig"), it.optString("igu")) }
    } catch (_: Exception) { emptyList() }

    /** Graph की गलती (error.message) साफ़ शब्दों में */
    fun error(body: String): String? = try {
        JSONObject(body).optJSONObject("error")?.let { e ->
            val code = e.optInt("code")
            val hint = when (code) {
                190 -> " (token expired or invalid - paste a new Page token in Settings → कनेक्टर)"
                10, 200 -> " (permission missing - the token needs pages_manage_posts / instagram_content_publish etc.)"
                4, 17, 32 -> " (rate limit - try again later)"
                else -> ""
            }
            e.optString("message").take(300) + hint
        }
    } catch (_: Exception) { null }
}

object Clip {
    /** लंबे नतीजे मॉडल के लिए छोटे */
    fun of(s: String, max: Int = 3500): String = if (s.length <= max) s else s.take(max - 40) + "\n…(${s.length - max + 40} more chars cut)"
}
