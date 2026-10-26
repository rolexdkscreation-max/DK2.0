package com.dk.assistant.connect

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v3.72: MCP client (Model Context Protocol, Streamable HTTP).
 * User कोई भी MCP server URL (+ token) डाले -> initialize -> tools/list -> उसके सारे tools DK लाइव में.
 * जवाब JSON या SSE (text/event-stream) दोनों में आ सकता है - [Mcp.parse] दोनों पढ़ता है.
 * Mcp-Session-Id server दे तो आगे हर कॉल में वही भेजते हैं; session खत्म (404) = फिर से initialize.
 */
object McpClient {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()

    class Conn(val url: String, val token: String, var session: String)

    private fun post(cn: Conn, body: String, id: Int?): JSONObject? {
        val b = Request.Builder().url(cn.url)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", Mcp.PROTOCOL)
            .header("User-Agent", "DK-Assistant")
            .post(body.toRequestBody(JSON))
        if (cn.token.isNotBlank()) b.header("Authorization", if (cn.token.contains(' ')) cn.token else "Bearer ${cn.token}")
        if (cn.session.isNotBlank()) b.header("Mcp-Session-Id", cn.session)
        http.newCall(b.build()).execute().use { r ->
            r.header("Mcp-Session-Id")?.let { cn.session = it }
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ConnectorRunner.HttpErr(r.code, when (r.code) {
                401, 403 -> "MCP server refused the token"
                404 -> "session expired"
                else -> text.take(200)
            })
            return if (id == null) null else Mcp.parse(text, r.header("Content-Type"), id)
        }
    }

    private fun handshake(cn: Conn): JSONObject? {
        cn.session = ""
        val init = post(cn, Mcp.request(1, "initialize", Mcp.initParams()), 1)
        init?.optJSONObject("error")?.let { throw IllegalStateException("MCP initialize: " + it.optString("message")) }
        try { post(cn, Mcp.notification("notifications/initialized"), null) } catch (_: Exception) {}
        return init?.optJSONObject("result")
    }

    /** Settings "जोड़ो": जुड़कर tools की सूची (server का नाम भी) */
    fun discover(url: String, token: String): Pair<String, List<ToolSpec>> {
        val cn = Conn(url.trim(), token.trim(), "")
        val info = handshake(cn)
        val list = post(cn, Mcp.request(2, "tools/list", JSONObject()), 2)
        list?.optJSONObject("error")?.let { throw IllegalStateException("MCP tools/list: " + it.optString("message")) }
        val name = info?.optJSONObject("serverInfo")?.optString("name").orEmpty()
        return name to Mcp.tools(list?.optJSONObject("result"))
    }

    /** Live tool: एक tool चलाओ (session बचा हो तो वही, वरना नया) */
    fun call(c: Context, s: ConnectorStore.McpServer, tool: String, args: JSONObject): String {
        val cn = Conn(s.url, ConnectorStore.secret(c, "mcp_tok_${s.id}"), ConnectorStore.mcpSession(c, s.id))
        val name = s.tools.firstOrNull { it.name.equals(tool, true) }?.name ?: tool
        fun once(): String {
            if (cn.session.isBlank()) handshake(cn)
            val msg = post(cn, Mcp.request(3, "tools/call", Mcp.callParams(name, args)), 3)
            return Mcp.text(msg)
        }
        val out = try { once() } catch (e: ConnectorRunner.HttpErr) {
            if (e.code == 404 || e.code == 400) { cn.session = ""; once() } else throw e
        }
        ConnectorStore.setMcpSession(c, s.id, cn.session)
        return out
    }
}
