package com.dk.assistant

import com.dk.assistant.connect.Args
import com.dk.assistant.connect.Box
import com.dk.assistant.connect.Catalog
import com.dk.assistant.connect.Clip
import com.dk.assistant.connect.ConnectorTool
import com.dk.assistant.connect.DriveQ
import com.dk.assistant.connect.GDocs
import com.dk.assistant.connect.GTime
import com.dk.assistant.connect.Gate
import com.dk.assistant.connect.Gmail
import com.dk.assistant.connect.Graph
import com.dk.assistant.connect.Hex
import com.dk.assistant.connect.Html
import com.dk.assistant.connect.Mcp
import com.dk.assistant.connect.ToolSpec
import com.dk.assistant.connect.ToolText
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorLogicTest {

    // ---------------------------------------------------------------- catalog
    @Test fun catalogIdsUniqueAndGoogleHaveScopes() {
        val ids = Catalog.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        Catalog.GOOGLE.forEach { assertTrue(it.id, it.scopes.isNotEmpty() && it.scopes.all { s -> s.startsWith("https://www.googleapis.com/auth/") }) }
        Catalog.ALL.forEach { d -> val n = d.tools.map { it.name }; assertEquals(d.id, n.size, n.toSet().size) }
    }

    @Test fun sendDeleteSharePostAreRisky() {
        fun risky(c: String, a: String) = Catalog.byId(c)!!.tools.first { it.name == a }.risky
        assertTrue(risky("gmail", "send")); assertFalse(risky("gmail", "search")); assertFalse(risky("gmail", "draft"))
        assertTrue(risky("drive", "share")); assertTrue(risky("drive", "trash")); assertFalse(risky("drive", "read"))
        assertTrue(risky("calendar", "delete")); assertTrue(risky("fb_page", "post")); assertTrue(risky("fb_page", "reply"))
        assertTrue(risky("instagram", "publish_image")); assertFalse(risky("instagram", "profile"))
        assertFalse(Catalog.GITHUB.tools.any { it.risky })
    }

    @Test fun personalAccountsHaveNoApiTools() {
        assertTrue(Catalog.FB_PERSONAL.tools.isEmpty()); assertTrue(Catalog.IG_PERSONAL.tools.isEmpty())
    }

    @Test fun resolveLooseNames() {
        assertEquals("drive", Catalog.resolve("Google Drive"))
        assertEquals("gmail", Catalog.resolve("Gmail"))
        assertEquals("gmail", Catalog.resolve("email"))
        assertEquals("fb_page", Catalog.resolve("facebook"))
        assertEquals("instagram", Catalog.resolve("Instagram"))
        assertEquals("sheets_docs", Catalog.resolve("google_docs"))
        assertEquals("tasks", Catalog.resolve("google-tasks"))
        assertEquals("contacts", Catalog.resolve("contacts"))
        assertEquals("mcp_2", Catalog.resolve("mcp_2"))
    }

    // ---------------------------------------------------------------- tool text
    @Test fun describeNullWhenNothingConnected() {
        assertNull(ToolText.describe(emptyList()))
        assertNull(ToolText.describe(listOf(Catalog.FB_PERSONAL)))
    }

    @Test fun describeListsOnlyConnectedAndMarksAskFirst() {
        val d = ToolText.describe(listOf(Catalog.GMAIL), mapOf("mcp_1" to ("Notion" to listOf(ToolSpec("create_page", listOf("title"), "make page", true)))))!!
        assertTrue(d.contains("gmail: search(query,max)"))
        assertTrue(d.contains("send(to,subject,body)[ASK FIRST]"))
        assertFalse(d.contains("drive:"))
        assertTrue(d.contains("mcp_1: create_page(title)[ASK FIRST]"))
        assertTrue(d.contains("Notion"))
        assertTrue(d.contains("social_media_task"))
        assertTrue(d.length <= 6000)
    }

    @Test fun declarationShape() {
        val o = ConnectorTool.declarationFor("x")
        assertEquals("connector_task", o.getString("name"))
        val p = o.getJSONObject("parameters").getJSONObject("properties")
        assertEquals("BOOLEAN", p.getJSONObject("confirmed").getString("type"))
        assertEquals("STRING", p.getJSONObject("args").getString("type"))
        assertEquals(2, o.getJSONObject("parameters").getJSONArray("required").length())
    }

    // ---------------------------------------------------------------- confirm gate
    @Test fun gateAsksBeforeRiskyAndGoesAfterYes() {
        val t = Catalog.GMAIL.tools
        val a = JSONObject().put("to", "a@b.com").put("subject", "hi")
        val v = Gate.check("gmail", t, "send", a, false)
        assertTrue(v is Gate.Verdict.Ask)
        val m = (v as Gate.Verdict.Ask).message
        assertTrue(m.startsWith("NEEDS USER CHOICE"))
        assertTrue(m.contains("a@b.com")); assertTrue(m.contains("confirmed=true"))
        assertTrue(DoneGate.notDoneYet(m))
        assertTrue(Gate.check("gmail", t, "send", a, true) is Gate.Verdict.Go)
        assertTrue(Gate.check("gmail", t, "SEARCH", JSONObject(), false) is Gate.Verdict.Go)
    }

    @Test fun gateUnknownAction() {
        val v = Gate.check("gmail", Catalog.GMAIL.tools, "explode", JSONObject(), true)
        assertTrue(v is Gate.Verdict.Bad)
        assertTrue((v as Gate.Verdict.Bad).message.contains("search"))
        assertTrue(DoneGate.notDoneYet(v.message))
    }

    // ---------------------------------------------------------------- args
    @Test fun argsParsing() {
        assertEquals(0, Args.parse(null).length())
        assertEquals(0, Args.parse("").length())
        assertEquals("x", Args.parse("{\"query\":\"x\"}").getString("query"))
        assertEquals("y", Args.parse("```json\n{\"q\":\"y\"}\n```").getString("q"))
        assertEquals("bill march", Args.parse("bill march").getString("query"))
        assertEquals(5, Args.parse(JSONObject().put("max", 5)).getInt("max"))
        assertEquals(3, Args.row("a, b ,c").length())
        assertEquals(2, Args.row("[\"x\", 2]").length())
        assertEquals(1, Args.row(JSONArray().put("z")).length())
    }

    // ---------------------------------------------------------------- MCP
    @Test fun mcpRequestShape() {
        val o = JSONObject(Mcp.request(7, "tools/list", JSONObject()))
        assertEquals("2.0", o.getString("jsonrpc")); assertEquals(7, o.getInt("id")); assertEquals("tools/list", o.getString("method"))
        val n = JSONObject(Mcp.notification("notifications/initialized"))
        assertFalse(n.has("id"))
        assertEquals("DK-Assistant", Mcp.initParams().getJSONObject("clientInfo").getString("name"))
    }

    @Test fun mcpParsePlainJson() {
        val m = Mcp.parse("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}", "application/json", 2)
        assertNotNull(m); assertTrue(m!!.has("result"))
    }

    @Test fun mcpParseSseAndPickId() {
        val body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n" +
            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":3,\n" +
            "data: \"result\":{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}}\n\n"
        val m = Mcp.parse(body, "text/event-stream", 3)
        assertEquals(3, m!!.getInt("id"))
        assertEquals("hello", Mcp.text(m))
    }

    @Test fun mcpParseGarbage() {
        assertNull(Mcp.parse("<html>oops</html>", "text/html", 1))
        assertEquals("MCP server gave no answer.", Mcp.text(null))
    }

    @Test fun mcpToolsRiskFromAnnotationsAndNames() {
        val r = JSONObject("""{"tools":[
            {"name":"search_pages","description":"find","inputSchema":{"type":"object","properties":{"query":{"type":"string"}}}},
            {"name":"create_page","inputSchema":{"properties":{"title":{},"body":{}}}},
            {"name":"get_doc","annotations":{"destructiveHint":true}},
            {"name":"send_message","annotations":{"readOnlyHint":true}},
            {"description":"no name"}
        ]}""")
        val t = Mcp.tools(r)
        assertEquals(4, t.size)
        assertFalse(t[0].risky); assertEquals(listOf("query"), t[0].params)
        assertTrue(t[1].risky); assertEquals(2, t[1].params.size)
        assertTrue(t[2].risky)
        assertFalse(t[3].risky)
        val back = Mcp.toolsFromJson(Mcp.toolsToJson(t))
        assertEquals(t, back)
    }

    @Test fun mcpTextVariants() {
        assertTrue(Mcp.text(JSONObject("{\"error\":{\"code\":-32602,\"message\":\"bad args\"}}")).contains("bad args"))
        assertTrue(Mcp.text(JSONObject("{\"result\":{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"nope\"}]}}")).startsWith("MCP tool error"))
        assertTrue(Mcp.text(JSONObject("{\"result\":{\"content\":[],\"structuredContent\":{\"a\":1}}}")).contains("\"a\":1"))
        assertEquals("tool", Mcp.callParams("tool", JSONObject()).getString("name"))
    }

    // ---------------------------------------------------------------- Gmail
    @Test fun gmailRawRoundTripHindi() {
        val raw = Gmail.raw("a@b.com", "नमस्ते", "पहली लाइन\nदूसरी")
        assertFalse(raw.contains("=")); assertFalse(raw.contains("+")); assertFalse(raw.contains("/"))
        val mime = String(java.util.Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
        assertTrue(mime.startsWith("To: a@b.com\r\n"))
        assertTrue(mime.contains("Subject: =?UTF-8?B?"))
        val bodyB64 = mime.substringAfter("\r\n\r\n").replace("\r\n", "")
        assertEquals("पहली लाइन\nदूसरी", String(java.util.Base64.getDecoder().decode(bodyB64), Charsets.UTF_8))
        assertTrue(String(java.util.Base64.getUrlDecoder().decode(Gmail.raw("x@y.z", "Plain", "b")), Charsets.UTF_8).contains("Subject: Plain\r\n"))
    }

    @Test fun gmailHeaderInjectionBlocked() {
        val mime = String(java.util.Base64.getUrlDecoder().decode(Gmail.raw("a@b.com\r\nBcc: evil@x.com", "s\r\nBcc: e@x", "b")), Charsets.UTF_8)
        assertFalse(mime.contains("\r\nBcc:"))
    }

    @Test fun gmailBodyPrefersPlainThenHtml() {
        fun b64(s: String) = java.util.Base64.getUrlEncoder().encodeToString(s.toByteArray())
        val p = JSONObject().put("mimeType", "multipart/alternative").put("parts", JSONArray()
            .put(JSONObject().put("mimeType", "text/html").put("body", JSONObject().put("data", b64("<p>HTML</p>"))))
            .put(JSONObject().put("mimeType", "text/plain").put("body", JSONObject().put("data", b64("सादा text")))))
        assertEquals("सादा text", Gmail.bodyText(p))
        val h = JSONObject().put("mimeType", "text/html").put("body", JSONObject().put("data", b64("<div>Hi<br>there &amp; you</div>")))
        assertEquals("Hi\nthere & you", Gmail.bodyText(h))
        val hdr = JSONObject().put("headers", JSONArray().put(JSONObject().put("name", "Subject").put("value", "S1")))
        assertEquals("S1", Gmail.headerOf(hdr, "subject")); assertEquals("", Gmail.headerOf(hdr, "From"))
    }

    @Test fun htmlStripDropsScripts() {
        assertEquals("a b", Html.strip("<script>x()</script>a <b>b</b>"))
    }

    // ---------------------------------------------------------------- Docs / time / Drive
    @Test fun docsText() {
        val d = JSONObject("""{"body":{"content":[{"sectionBreak":{}},{"paragraph":{"elements":[{"textRun":{"content":"Hello "}},{"textRun":{"content":"world\n"}}]}},{"paragraph":{"elements":[{"textRun":{"content":"दूसरा\n"}}]}}]}}""")
        assertEquals("Hello world\nदूसरा", GDocs.text(d))
        assertEquals("", GDocs.text(null))
    }

    @Test fun calendarTimes() {
        val (s, e) = GTime.eventTimes("2026-10-26T15:00", null, "Asia/Kolkata")!!
        assertEquals("2026-10-26T15:00:00", s.getString("dateTime")); assertEquals("Asia/Kolkata", s.getString("timeZone"))
        assertEquals("2026-10-26T16:00:00", e.getString("dateTime"))
        val (s2, e2) = GTime.eventTimes("2026-10-26 15:00", "2026-10-26T17:30", "Asia/Kolkata")!!
        assertEquals("2026-10-26T15:00:00", s2.getString("dateTime")); assertEquals("2026-10-26T17:30:00", e2.getString("dateTime"))
        val (s3, e3) = GTime.eventTimes("2026-10-26", "", "UTC")!!
        assertEquals("2026-10-26", s3.getString("date")); assertEquals("2026-10-27", e3.getString("date"))
        // end पहले हो = +1 घंटा
        assertEquals("2026-10-26T16:00:00", GTime.eventTimes("2026-10-26T15:00", "2026-10-26T14:00", "UTC")!!.second.getString("dateTime"))
        assertEquals("2026-10-26T15:00:00", GTime.eventTimes("2026-10-26T15:00:00+05:30", null, "UTC")!!.first.getString("dateTime"))
        assertNull(GTime.eventTimes("kal", null, "UTC"))
        assertEquals("2026-10-26T00:00:00.000Z", GTime.taskDue("2026-10-26"))
        assertNull(GTime.taskDue("soon"))
    }

    @Test fun driveQuery() {
        assertEquals("(name contains 'bill' or fullText contains 'bill') and trashed = false", DriveQ.build("bill"))
        assertEquals("(name contains 'raj\\'s' or fullText contains 'raj\\'s') and trashed = false", DriveQ.build("raj's"))
        assertEquals("mimeType = 'application/pdf'", DriveQ.build("mimeType = 'application/pdf'"))
        assertEquals("trashed = false", DriveQ.build(" "))
        assertEquals("text/plain", DriveQ.exportMime("application/vnd.google-apps.document"))
        assertEquals("text/csv", DriveQ.exportMime("application/vnd.google-apps.spreadsheet"))
        assertNull(DriveQ.exportMime("image/png"))
        assertTrue(DriveQ.isText("text/markdown")); assertFalse(DriveQ.isText("application/pdf"))
    }

    // ---------------------------------------------------------------- Graph
    @Test fun graphPagesParseAndRoundTrip() {
        val j = JSONObject("""{"data":[{"id":"1","name":"DK Page","access_token":"T1","instagram_business_account":{"id":"17","username":"dk.ig"}},{"id":"2","name":"Other","access_token":"T2"},{"name":"noid"}]}""")
        val p = Graph.pages(j)
        assertEquals(2, p.size)
        assertEquals("17", p[0].igId); assertEquals("dk.ig", p[0].igUser); assertEquals("", p[1].igId)
        assertEquals(p, Graph.pagesFromJson(Graph.pagesToJson(p)))
        assertTrue(Graph.pagesFromJson("garbage").isEmpty())
        assertTrue(Graph.BASE.startsWith("https://graph.facebook.com/v"))
    }

    @Test fun graphErrors() {
        assertTrue(Graph.error("{\"error\":{\"message\":\"Session expired\",\"code\":190}}")!!.contains("token expired"))
        assertTrue(Graph.error("{\"error\":{\"message\":\"no perm\",\"code\":200}}")!!.contains("permission"))
        assertNull(Graph.error("not json"))
    }

    // ---------------------------------------------------------------- store packing / misc
    @Test fun boxPackUnpack() {
        val iv = ByteArray(12) { it.toByte() }; val ct = byteArrayOf(9, 8, 7)
        val (a, b) = Box.unpack(Box.pack(iv, ct))!!
        assertTrue(a.contentEquals(iv)); assertTrue(b.contentEquals(ct))
        assertNull(Box.unpack(ByteArray(0)))
        assertNull(Box.unpack(byteArrayOf(3, 1, 2, 3, 4)))
        assertNull(Box.unpack(Box.pack(iv, ByteArray(0))))
    }

    @Test fun hexAndClip() {
        assertEquals("0A:FF:00", Hex.colon(byteArrayOf(10, -1, 0)))
        assertEquals("abc", Clip.of("abc"))
        val long = "x".repeat(5000)
        val c = Clip.of(long, 1000)
        assertTrue(c.length < 1100); assertTrue(c.contains("more chars cut"))
    }
}
