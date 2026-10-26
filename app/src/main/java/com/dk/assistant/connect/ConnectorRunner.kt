package com.dk.assistant.connect

import android.content.Context
import com.dk.assistant.DKLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * v3.72: `connector_task` का असली काम - हर कनेक्टर की API कॉल.
 * नतीजा हमेशा छोटा साफ़ text (मॉडल बोलकर बताए). Token कभी log में नहीं.
 * भेजना/मिटाना/शेयर/पोस्ट = [Gate] पहले "हाँ" माँगता है.
 */
object ConnectorRunner {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    class HttpErr(val code: Int, msg: String) : java.io.IOException(msg)

    /** Live tool यहीं से (background thread) */
    fun run(c: Context, connectorRaw: String, action: String, argsRaw: Any?, confirmed: Boolean): String {
        val id = Catalog.resolve(connectorRaw)
        val args = Args.parse(argsRaw)
        val mcp = ConnectorStore.mcpServers(c).firstOrNull { it.id == id || it.name.equals(connectorRaw.trim(), true) }
        val def = Catalog.byId(id)
        val tools = mcp?.tools ?: def?.tools
        if (tools == null || (mcp == null && def?.let { d -> d !in ConnectorStore.connected(c) } != false)) {
            val names = ConnectorStore.connected(c).map { it.id } + ConnectorStore.mcpServers(c).map { it.id }
            return "FAILED: connector '$connectorRaw' is not connected. Connected: ${names.joinToString().ifBlank { "none" }}. Tell the user to connect it in सेटिंग्स → कनेक्टर."
        }
        when (val v = Gate.check(mcp?.id ?: id, tools, action, args, confirmed)) {
            is Gate.Verdict.Bad -> return v.message
            is Gate.Verdict.Ask -> return v.message
            Gate.Verdict.Go -> {}
        }
        DKLog.d("कनेक्टर: ${mcp?.id ?: id}.$action")
        return try {
            Clip.of(when {
                mcp != null -> McpClient.call(c, mcp, action.trim(), args)
                def!!.kind == Kind.GOOGLE -> google(c, def, action.trim().lowercase(), args)
                def.kind == Kind.META -> meta(c, def.id, action.trim().lowercase(), args)
                def.kind == Kind.GITHUB -> github(c, action.trim().lowercase(), args)
                else -> "This connector works through social_media_task."
            })
        } catch (e: NeedLogin) {
            "FAILED: Google permission for ${def?.title} is needed again - tell the user to open सेटिंग्स → कनेक्टर → ${def?.title} → खाता जोड़ो."
        } catch (e: HttpErr) {
            DKLog.d("कनेक्टर ${mcp?.id ?: id}.$action HTTP ${e.code}")
            "FAILED (HTTP ${e.code}): ${e.message}"
        } catch (e: Exception) {
            DKLog.d("कनेक्टर ${mcp?.id ?: id}.$action fail: ${e.javaClass.simpleName}")
            "FAILED: ${e.message?.take(200) ?: e.javaClass.simpleName}"
        }
    }

    class NeedLogin : Exception("login")

    // ================================================================ HTTP
    internal fun exec(b: Request.Builder, errParse: (String) -> String? = { null }): String {
        http.newCall(b.header("User-Agent", "DK-Assistant").build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                if (r.code == 401) throw HttpErr(401, errParse(body) ?: "login expired or token invalid")
                throw HttpErr(r.code, errParse(body) ?: googleErr(body) ?: body.take(200))
            }
            return body
        }
    }

    private fun googleErr(body: String): String? = try { JSONObject(body).optJSONObject("error")?.optString("message")?.take(250) } catch (_: Exception) { null }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ================================================================ Google
    private fun gToken(c: Context, d: ConnectorDef): String = when (val r = GoogleAuth.tokenBlocking(c, d.scopes)) {
        is GoogleAuth.Res.Token -> r.value
        is GoogleAuth.Res.NeedsUi -> throw NeedLogin()
        is GoogleAuth.Res.Fail -> throw IllegalStateException(r.message)
    }

    private class G(val token: String) {
        fun req(url: String) = Request.Builder().url(url).header("Authorization", "Bearer $token")
        fun get(url: String) = JSONObject(exec(req(url)))
        fun getRaw(url: String) = exec(req(url))
        fun post(url: String, body: JSONObject) = JSONObject(exec(req(url).post(body.toString().toRequestBody(JSON))).ifBlank { "{}" })
        fun patch(url: String, body: JSONObject) = JSONObject(exec(req(url).patch(body.toString().toRequestBody(JSON))).ifBlank { "{}" })
        fun delete(url: String) { exec(req(url).delete()) }
        fun postBody(url: String, body: RequestBody) = JSONObject(exec(req(url).post(body)).ifBlank { "{}" })
    }

    private fun google(c: Context, d: ConnectorDef, a: String, x: JSONObject): String {
        val g = G(gToken(c, d))
        return when (d.id) {
            "drive" -> drive(g, a, x)
            "gmail" -> gmail(g, a, x)
            "calendar" -> calendar(g, a, x)
            "contacts" -> contacts(g, x)
            "tasks" -> tasks(g, a, x)
            "sheets_docs" -> sheetsDocs(g, a, x)
            "youtube" -> youtube(g, a, x)
            else -> "unknown"
        }
    }

    private const val DRV = "https://www.googleapis.com/drive/v3"

    private fun drive(g: G, a: String, x: JSONObject): String = when (a) {
        "search" -> {
            val o = g.get("$DRV/files?pageSize=10&orderBy=modifiedTime%20desc&fields=" + enc("files(id,name,mimeType,modifiedTime,webViewLink)") + "&q=" + enc(DriveQ.build(x.optString("query"))))
            val f = o.optJSONArray("files") ?: JSONArray()
            if (f.length() == 0) "No files found." else (0 until f.length()).joinToString("\n") { i ->
                val it = f.getJSONObject(i); "${it.optString("name")} | id=${it.optString("id")} | ${it.optString("mimeType").substringAfterLast('.')} | ${it.optString("modifiedTime").take(10)}"
            }
        }
        "read" -> {
            val id = x.optString("file_id").ifBlank { x.optString("id") }
            val m = g.get("$DRV/files/${enc(id)}?fields=id,name,mimeType,size,webViewLink")
            val mime = m.optString("mimeType")
            val text = DriveQ.exportMime(mime)?.let { g.getRaw("$DRV/files/${enc(id)}/export?mimeType=" + enc(it)) }
                ?: if (DriveQ.isText(mime)) g.getRaw("$DRV/files/${enc(id)}?alt=media") else null
            "${m.optString("name")} ($mime)\n" + (text ?: "Not a text file - link: ${m.optString("webViewLink")}")
        }
        "upload_text" -> {
            val name = x.optString("name").ifBlank { "DK note.txt" }
            val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                .addPart(JSONObject().put("name", name).put("mimeType", "text/plain").toString().toRequestBody(JSON))
                .addPart(x.optString("text").toRequestBody("text/plain; charset=utf-8".toMediaType())).build()
            val o = g.postBody("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,webViewLink", body)
            "Created ${o.optString("name")} (id=${o.optString("id")}) ${o.optString("webViewLink")}"
        }
        "share" -> {
            val id = x.optString("file_id"); val email = x.optString("email").trim()
            val role = x.optString("role").lowercase().let { if (it == "writer" || it == "editor") "writer" else "reader" }
            val p = if (email.isEmpty()) JSONObject().put("type", "anyone").put("role", "reader") else JSONObject().put("type", "user").put("role", role).put("emailAddress", email)
            g.post("$DRV/files/${enc(id)}/permissions?sendNotificationEmail=${email.isNotEmpty()}", p)
            val link = g.get("$DRV/files/${enc(id)}?fields=webViewLink").optString("webViewLink")
            if (email.isEmpty()) "Shared: anyone with the link can view. $link" else "Shared with $email as $role. $link"
        }
        "trash" -> { g.patch("$DRV/files/${enc(x.optString("file_id"))}", JSONObject().put("trashed", true)); "Moved to trash (can be restored from Drive trash for 30 days)." }
        else -> "unknown action"
    }

    private const val GM = "https://gmail.googleapis.com/gmail/v1/users/me"

    private fun gmail(g: G, a: String, x: JSONObject): String = when (a) {
        "search" -> {
            val max = x.optInt("max", 5).coerceIn(1, 10)
            val ids = g.get("$GM/messages?maxResults=$max&q=" + enc(x.optString("query").ifBlank { "in:inbox" })).optJSONArray("messages") ?: JSONArray()
            if (ids.length() == 0) "No mails found." else (0 until ids.length()).joinToString("\n") { i ->
                val id = ids.getJSONObject(i).optString("id")
                val m = g.get("$GM/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date")
                val p = m.optJSONObject("payload")
                "id=$id | ${Gmail.headerOf(p, "From").take(60)} | ${Gmail.headerOf(p, "Subject").take(90)} | ${Gmail.headerOf(p, "Date").take(22)} | ${m.optString("snippet").take(120)}"
            }
        }
        "read" -> {
            val m = g.get("$GM/messages/${enc(x.optString("id"))}?format=full")
            val p = m.optJSONObject("payload")
            "From: ${Gmail.headerOf(p, "From")}\nSubject: ${Gmail.headerOf(p, "Subject")}\nDate: ${Gmail.headerOf(p, "Date")}\n\n" + Gmail.bodyText(p).take(3000)
        }
        "draft" -> {
            val o = g.post("$GM/drafts", JSONObject().put("message", JSONObject().put("raw", Gmail.raw(x.optString("to"), x.optString("subject"), x.optString("body")))))
            "Draft saved (id=${o.optString("id")}). Not sent."
        }
        "send" -> {
            val to = x.optString("to").trim()
            if ("@" !in to) "Need a valid email address in 'to' (use contacts.search to find it)."
            else { val o = g.post("$GM/messages/send", JSONObject().put("raw", Gmail.raw(to, x.optString("subject"), x.optString("body")))); "Sent to $to (id=${o.optString("id")})." }
        }
        else -> "unknown action"
    }

    private const val CAL = "https://www.googleapis.com/calendar/v3/calendars/primary/events"

    private fun calendar(g: G, a: String, x: JSONObject): String {
        return when (a) {
        "list" -> {
            val days = x.optInt("days", 7).coerceIn(1, 60)
            val now = java.time.OffsetDateTime.now()
            val o = g.get("$CAL?singleEvents=true&orderBy=startTime&maxResults=20&timeMin=" + enc(now.toString()) + "&timeMax=" + enc(now.plusDays(days.toLong()).toString()))
            val it = o.optJSONArray("items") ?: JSONArray()
            if (it.length() == 0) "No events in the next $days days." else (0 until it.length()).joinToString("\n") { i ->
                val e = it.getJSONObject(i); val s = e.optJSONObject("start")
                "${(s?.optString("dateTime")?.ifBlank { null } ?: s?.optString("date")).orEmpty().take(16).replace('T', ' ')} | ${e.optString("summary")} | id=${e.optString("id")}" +
                    (e.optString("location").takeIf { l -> l.isNotBlank() }?.let { l -> " | $l" } ?: "")
            }
        }
        "create" -> {
            val tz = java.util.TimeZone.getDefault().id
            val t = GTime.eventTimes(x.optString("start"), x.optString("end"), tz) ?: return "Bad start time - use 2026-10-26T15:00 or 2026-10-26."
            val o = g.post(CAL, JSONObject().put("summary", x.optString("title").ifBlank { "DK event" }).put("description", x.optString("description")).put("start", t.first).put("end", t.second))
            "Event created: ${o.optString("summary")} (id=${o.optString("id")}) ${o.optString("htmlLink")}"
        }
        "delete" -> { g.delete("$CAL/${enc(x.optString("event_id"))}"); "Event deleted." }
        else -> "unknown action"
        }
    }

    private fun contacts(g: G, x: JSONObject): String {
        val base = "https://people.googleapis.com/v1/people:searchContacts?readMask=names,phoneNumbers,emailAddresses&pageSize=10&query="
        try { g.get(base) } catch (_: Exception) {}   // Google की सलाह: पहले खाली खोज (cache गरम)
        val r = g.get(base + enc(x.optString("query"))).optJSONArray("results") ?: JSONArray()
        if (r.length() == 0) return "No contact found."
        return (0 until r.length()).joinToString("\n") { i ->
            val p = r.getJSONObject(i).optJSONObject("person") ?: JSONObject()
            fun first(arr: String, f: String) = p.optJSONArray(arr)?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString(f) }.filter { it.isNotBlank() }.joinToString(", ") }.orEmpty()
            "${first("names", "displayName")} | ${first("phoneNumbers", "value")} | ${first("emailAddresses", "value")}"
        }
    }

    private const val TK = "https://tasks.googleapis.com/tasks/v1/lists/@default/tasks"

    private fun tasks(g: G, a: String, x: JSONObject): String = when (a) {
        "list" -> {
            val it = g.get("$TK?showCompleted=false&maxResults=30").optJSONArray("items") ?: JSONArray()
            if (it.length() == 0) "No open tasks." else (0 until it.length()).joinToString("\n") { i ->
                val t = it.getJSONObject(i); "${t.optString("title")} | id=${t.optString("id")}" + (t.optString("due").takeIf { d -> d.isNotBlank() }?.let { d -> " | due ${d.take(10)}" } ?: "")
            }
        }
        "add" -> {
            val b = JSONObject().put("title", x.optString("title")).put("notes", x.optString("notes"))
            GTime.taskDue(x.optString("due"))?.let { b.put("due", it) }
            "Task added (id=${g.post(TK, b).optString("id")})."
        }
        "done" -> { g.patch("$TK/${enc(x.optString("task_id"))}", JSONObject().put("status", "completed")); "Marked done." }
        else -> "unknown action"
    }

    private fun sheetsDocs(g: G, a: String, x: JSONObject): String = when (a) {
        "sheet_read" -> {
            val range = x.optString("range").ifBlank { "A1:Z50" }
            val v = g.get("https://sheets.googleapis.com/v4/spreadsheets/${enc(x.optString("sheet_id"))}/values/${enc(range)}").optJSONArray("values") ?: JSONArray()
            if (v.length() == 0) "Empty." else (0 until v.length()).joinToString("\n") { i -> v.optJSONArray(i)?.let { r -> (0 until r.length()).joinToString(" | ") { r.optString(it) } }.orEmpty() }
        }
        "sheet_append" -> {
            val range = x.optString("range").ifBlank { "A1" }
            val o = g.post("https://sheets.googleapis.com/v4/spreadsheets/${enc(x.optString("sheet_id"))}/values/${enc(range)}:append?valueInputOption=USER_ENTERED",
                JSONObject().put("values", JSONArray().put(Args.row(x.opt("values")))))
            "Row added at ${o.optJSONObject("updates")?.optString("updatedRange").orEmpty()}."
        }
        "doc_create" -> {
            val o = g.post("https://docs.googleapis.com/v1/documents", JSONObject().put("title", x.optString("title").ifBlank { "DK document" }))
            val id = o.optString("documentId")
            val text = x.optString("text")
            if (text.isNotBlank()) g.post("https://docs.googleapis.com/v1/documents/$id:batchUpdate", JSONObject().put("requests", JSONArray().put(
                JSONObject().put("insertText", JSONObject().put("location", JSONObject().put("index", 1)).put("text", text)))))
            "Doc created (id=$id) https://docs.google.com/document/d/$id/edit"
        }
        "doc_read" -> {
            val o = g.get("https://docs.googleapis.com/v1/documents/${enc(x.optString("doc_id"))}")
            o.optString("title") + "\n\n" + GDocs.text(o).ifBlank { "(empty)" }
        }
        else -> "unknown action"
    }

    private const val YT = "https://www.googleapis.com/youtube/v3"

    private fun youtube(g: G, a: String, x: JSONObject): String {
        return when (a) {
        "search" -> {
            val it = g.get("$YT/search?part=snippet&type=video&maxResults=5&q=" + enc(x.optString("query"))).optJSONArray("items") ?: JSONArray()
            (0 until it.length()).joinToString("\n") { i -> val o = it.getJSONObject(i); val s = o.optJSONObject("snippet")
                "${s?.optString("title")} | ${s?.optString("channelTitle")} | https://youtu.be/${o.optJSONObject("id")?.optString("videoId")}" }.ifBlank { "No videos found." }
        }
        "my_videos" -> {
            val ch = (g.get("$YT/channels?part=contentDetails,statistics,snippet&mine=true").optJSONArray("items") ?: JSONArray()).optJSONObject(0)
                ?: return "No YouTube channel on this Google account."
            val up = ch.optJSONObject("contentDetails")?.optJSONObject("relatedPlaylists")?.optString("uploads").orEmpty()
            val st = ch.optJSONObject("statistics")
            val head = "${ch.optJSONObject("snippet")?.optString("title")}: ${st?.optString("subscriberCount")} subscribers, ${st?.optString("videoCount")} videos, ${st?.optString("viewCount")} views"
            val pl = g.get("$YT/playlistItems?part=snippet&maxResults=10&playlistId=" + enc(up)).optJSONArray("items") ?: JSONArray()
            val ids = (0 until pl.length()).mapNotNull { k -> pl.getJSONObject(k).optJSONObject("snippet")?.optJSONObject("resourceId")?.optString("videoId") }
            val stats = if (ids.isEmpty()) JSONArray() else g.get("$YT/videos?part=snippet,statistics&id=" + enc(ids.joinToString(","))).optJSONArray("items") ?: JSONArray()
            head + "\n" + (0 until stats.length()).joinToString("\n") { i -> val v = stats.getJSONObject(i)
                "${v.optJSONObject("snippet")?.optString("title")} | ${v.optJSONObject("statistics")?.optString("viewCount")} views | ${v.optJSONObject("statistics")?.optString("likeCount")} likes | https://youtu.be/${v.optString("id")}" }
        }
        "playlists" -> {
            val it = g.get("$YT/playlists?part=snippet,contentDetails&mine=true&maxResults=20").optJSONArray("items") ?: JSONArray()
            (0 until it.length()).joinToString("\n") { i -> val o = it.getJSONObject(i)
                "${o.optJSONObject("snippet")?.optString("title")} | ${o.optJSONObject("contentDetails")?.optInt("itemCount")} videos | id=${o.optString("id")}" }.ifBlank { "No playlists." }
        }
        else -> "unknown action"
        }
    }

    // ================================================================ Meta (Facebook Page / Instagram)
    private fun gr(url: String) = Request.Builder().url(url)
    private fun gget(path: String, token: String): JSONObject =
        JSONObject(exec(gr(Graph.BASE + path + (if ('?' in path) "&" else "?") + "access_token=" + enc(token))) { Graph.error(it) })
    private fun gpost(path: String, token: String, form: Map<String, String>): JSONObject {
        val b = okhttp3.FormBody.Builder(); form.forEach { (k, v) -> if (v.isNotBlank()) b.add(k, v) }; b.add("access_token", token)
        return JSONObject(exec(gr(Graph.BASE + path).post(b.build())) { Graph.error(it) })
    }

    /** token से पेज (टोकन सहित) - Settings "जोड़ो" पर भी यही. User token = /me/accounts, Page token = /me */
    fun loadPages(token: String): List<Graph.Page> {
        val f = "id,name,access_token,instagram_business_account{id,username}"
        val list = try { Graph.pages(gget("/me/accounts?limit=50&fields=" + enc(f), token)) } catch (e: HttpErr) { emptyList() }
        if (list.isNotEmpty()) return list
        val me = gget("/me?fields=" + enc("id,name,instagram_business_account{id,username}"), token)
        val ig = me.optJSONObject("instagram_business_account")
        if (!me.has("instagram_business_account") && me.optString("id").isBlank()) return emptyList()
        return listOf(Graph.Page(me.optString("id"), me.optString("name"), token, ig?.optString("id").orEmpty(), ig?.optString("username").orEmpty()))
    }

    private fun meta(c: Context, id: String, a: String, x: JSONObject): String {
        var pages = ConnectorStore.metaPages(c)
        if (pages.isEmpty()) { pages = loadPages(ConnectorStore.secret(c, "meta_token")); ConnectorStore.setMetaPages(c, pages) }
        if (pages.isEmpty()) return "No Facebook Page found for this token."
        fun page(): Graph.Page = x.optString("page_id").let { pid -> pages.firstOrNull { it.id == pid || it.name.equals(pid, true) } } ?: pages.first()
        return if (id == "fb_page") when (a) {
            "pages" -> pages.joinToString("\n") { "${it.name} | page_id=${it.id}" + (if (it.igId.isNotBlank()) " | Instagram @${it.igUser}" else "") }
            "posts" -> { val p = page(); val d = gget("/${p.id}/posts?limit=8&fields=" + enc("id,message,created_time,permalink_url"), p.token).optJSONArray("data") ?: JSONArray()
                (0 until d.length()).joinToString("\n") { i -> val o = d.getJSONObject(i); "${o.optString("created_time").take(10)} | ${o.optString("message").take(120)} | post_id=${o.optString("id")}" }.ifBlank { "No posts." } }
            "comments" -> { val p = page(); val d = gget("/${enc(x.optString("post_id"))}/comments?limit=20&fields=" + enc("id,message,from,created_time"), p.token).optJSONArray("data") ?: JSONArray()
                (0 until d.length()).joinToString("\n") { i -> val o = d.getJSONObject(i); "${o.optJSONObject("from")?.optString("name") ?: "someone"}: ${o.optString("message").take(150)} | comment_id=${o.optString("id")}" }.ifBlank { "No comments." } }
            "post" -> { val p = page(); val o = gpost("/${p.id}/feed", p.token, mapOf("message" to x.optString("message"), "link" to x.optString("link"))); "Posted on ${p.name} (post_id=${o.optString("id")})." }
            "photo" -> { val p = page(); val o = gpost("/${p.id}/photos", p.token, mapOf("url" to x.optString("image_url"), "caption" to x.optString("caption"))); "Photo posted on ${p.name} (id=${o.optString("post_id").ifBlank { o.optString("id") }})." }
            "reply" -> { val p = page(); val o = gpost("/${enc(x.optString("comment_id"))}/comments", p.token, mapOf("message" to x.optString("message"))); "Replied (id=${o.optString("id")})." }
            else -> "unknown action"
        } else {
            val p = pages.firstOrNull { it.igId.isNotBlank() } ?: return "No Instagram Business/Creator account is linked to your Facebook Page."
            val ig = p.igId; val t = p.token
            when (a) {
                "profile" -> { val o = gget("/$ig?fields=" + enc("username,name,followers_count,follows_count,media_count,biography"), t)
                    "@${o.optString("username")} | ${o.optInt("followers_count")} followers | ${o.optInt("follows_count")} following | ${o.optInt("media_count")} posts\n${o.optString("biography").take(200)}" }
                "media" -> { val d = gget("/$ig/media?limit=8&fields=" + enc("id,caption,media_type,permalink,timestamp,like_count,comments_count"), t).optJSONArray("data") ?: JSONArray()
                    (0 until d.length()).joinToString("\n") { i -> val o = d.getJSONObject(i)
                        "${o.optString("timestamp").take(10)} | ${o.optString("media_type")} | ${o.optInt("like_count")} likes | ${o.optInt("comments_count")} comments | ${o.optString("caption").take(80)} | media_id=${o.optString("id")}" }.ifBlank { "No posts." } }
                "comments" -> { val d = gget("/${enc(x.optString("media_id"))}/comments?fields=" + enc("id,text,username,timestamp"), t).optJSONArray("data") ?: JSONArray()
                    (0 until d.length()).joinToString("\n") { i -> val o = d.getJSONObject(i); "@${o.optString("username")}: ${o.optString("text").take(150)} | comment_id=${o.optString("id")}" }.ifBlank { "No comments." } }
                "publish_image" -> {
                    val url = x.optString("image_url").trim()
                    if (!url.startsWith("https://")) return "Instagram needs a PUBLIC https image URL. For a photo from the phone, use social_media_task (Instagram app) instead."
                    val cont = gpost("/$ig/media", t, mapOf("image_url" to url, "caption" to x.optString("caption"))).optString("id")
                    // कंटेनर तैयार होने तक (आमतौर पर तुरंत) - 10s तक
                    for (i in 0 until 5) {
                        val s = try { gget("/$cont?fields=status_code", t).optString("status_code") } catch (_: Exception) { "" }
                        if (s == "FINISHED" || s.isBlank()) break
                        if (s == "ERROR" || s == "EXPIRED") return "Instagram could not process the image ($s)."
                        Thread.sleep(2000)
                    }
                    "Posted on Instagram @${p.igUser} (media_id=${gpost("/$ig/media_publish", t, mapOf("creation_id" to cont)).optString("id")})."
                }
                "reply" -> { val o = gpost("/${enc(x.optString("comment_id"))}/replies", t, mapOf("message" to x.optString("message"))); "Replied (id=${o.optString("id")})." }
                else -> "unknown action"
            }
        }
    }

    // ================================================================ GitHub (कोडिंग एजेंट वाला GitHub.kt)
    private fun github(c: Context, a: String, x: JSONObject): String {
        val gh = com.dk.assistant.GitHub.fromPrefs(c) ?: return "GitHub token missing - सेटिंग्स → कोडिंग एजेंट में GitHub token डालें."
        val repo = x.optString("repo").ifBlank { com.dk.assistant.Prefs.ghRepo(c) }
        fun branch() = x.optString("branch").ifBlank { gh.defaultBranch(repo) }
        return when (a) {
            "repos" -> gh.repos().take(30).joinToString("\n") { "${it.fullName} (${it.branch})" + if (it.private) " private" else "" }.ifBlank { "No repositories." }
            "files" -> gh.tree(repo, branch()).take(200).joinToString("\n") { it.path }
            "read" -> gh.read(repo, x.optString("path"), branch()) ?: "File not found."
            "search" -> gh.search(repo, x.optString("query")).joinToString("\n").ifBlank { "Nothing found." }
            else -> "unknown action"
        }
    }

    // ================================================================ Settings "जाँचो" बटन
    /** हर कनेक्टर का एक छोटा पढ़ने वाला काम - असली जुड़ाव की जाँच */
    fun probe(c: Context, d: ConnectorDef): String = when (d.id) {
        "drive" -> run(c, d.id, "search", "{\"query\":\"\"}", false)
        "gmail" -> run(c, d.id, "search", "{\"query\":\"in:inbox\",\"max\":2}", false)
        "calendar" -> run(c, d.id, "list", "{\"days\":3}", false)
        "contacts" -> run(c, d.id, "search", "{\"query\":\"a\"}", false)
        "tasks" -> run(c, d.id, "list", "{}", false)
        "sheets_docs" -> try { G(gToken(c, d)); "Google Sheets / Docs: इजाज़त मिली हुई है." } catch (e: Exception) { "Failed: ${e.message}" }
        "youtube" -> run(c, d.id, "playlists", "{}", false)
        "github" -> run(c, d.id, "repos", "{}", false)
        "fb_page" -> run(c, d.id, "pages", "{}", false)
        "instagram" -> run(c, d.id, "profile", "{}", false)
        else -> "—"
    }
}
