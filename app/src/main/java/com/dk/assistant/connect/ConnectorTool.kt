package com.dk.assistant.connect

import android.content.Context
import org.json.JSONObject

/**
 * v3.72: Gemini Live का एक tool `connector_task` - जुड़े कनेक्टर अपने-आप इसमें (हर नए Live सत्र पर).
 * कोई कनेक्टर नहीं जुड़ा = tool जोड़ा ही नहीं जाता (पुराना व्यवहार बिल्कुल वैसा).
 */
object ConnectorTool {
    fun declaration(c: Context): JSONObject? {
        val desc = try {
            ToolText.describe(ConnectorStore.connected(c), ConnectorStore.mcpServers(c).associate { it.id to (it.name to it.tools) })
        } catch (_: Exception) { null } ?: return null
        return declarationFor(desc)
    }

    fun declarationFor(desc: String): JSONObject = JSONObject().put("name", ToolText.NAME)
        .put("description", desc)
        .put("parameters", JSONObject().put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("connector", JSONObject().put("type", "STRING").put("description", "Connector id from the list, e.g. gmail, drive, calendar, fb_page, mcp_1"))
                .put("action", JSONObject().put("type", "STRING").put("description", "Action name from the list"))
                .put("args", JSONObject().put("type", "STRING").put("description", "JSON object string with the action's parameters, e.g. {\"query\":\"is:unread\",\"max\":5}"))
                .put("confirmed", JSONObject().put("type", "BOOLEAN").put("description", "true ONLY after the user clearly said yes to an [ASK FIRST] action")))
            .put("required", org.json.JSONArray().put("connector").put("action")))

    /** GeminiLive.runTools से (IO thread पर बुलाओ) */
    fun run(c: Context, args: JSONObject): String =
        ConnectorRunner.run(c, args.optString("connector"), args.optString("action"), args.opt("args"), args.optBoolean("confirmed", false))
}
