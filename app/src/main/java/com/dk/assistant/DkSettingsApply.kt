package com.dk.assistant

import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * v5: DK लाइव के `dk_settings` tool का फ़ोन वाला हिस्सा - सेटिंग पढ़ना / (दो बार पक्का करके) बदलना / सेटिंग्स का हिस्सा खोलना.
 * नियम और दो-बार-पक्का [DkSettings] में (JVM test).
 */
object DkSettingsApply {
    private fun prefOn(c: Context, it: DkSettings.Item): Boolean =
        DkSettings.isOn(it, Prefs.bool(c, it.pref, it.def))

    fun run(c: Context, args: JSONObject): String {
        val action = args.optString("action", "list").lowercase().trim()
        val q = args.optString("setting", "").trim()
        return when (action) {
            "open" -> open(c, q)
            "set", "on", "off", "toggle" -> {
                val item = DkSettings.find(q)
                    ?: return "not_found: no switchable setting matches '$q'. Use action=list to see all settings, or action=open to open that part of Settings."
                val on = when (action) { "on" -> true; "off" -> false; "toggle" -> !prefOn(c, item); else -> args.optBoolean("on", true) }
                if (prefOn(c, item) == on) return "already_${if (on) "on" else "off"}: '${item.name}' पहले से ${DkSettings.onOff(on)} है। Tell the user."
                val now = System.currentTimeMillis()
                if (!args.optBoolean("confirmed", false) || !DkSettings.confirmOk(item, on, now)) return DkSettings.ask(item, on, now)
                apply(c, item, on)
                "done: '${item.name}' अब ${DkSettings.onOff(on)} है। Tell the user briefly that it is done."
            }
            else -> DkSettings.catalog { prefOn(c, it) }
        }
    }

    private fun apply(c: Context, it: DkSettings.Item, on: Boolean) {
        Prefs.edit(c) {
            putBoolean(it.pref, DkSettings.prefFor(it, on))
            (if (on) it.alsoOn else it.alsoOff).forEach { (k, v) -> putBoolean(k, v) }
        }
        DKLog.d("DK ने सेटिंग बदली (यूज़र ने दो बार पक्का किया): ${it.name} -> ${DkSettings.onOff(on)}")
        try { if (it.pref == "duck_media" && !on) Ducker.releaseNow() } catch (_: Exception) {}
        try { DKAccessibilityService.instance?.startWakeLoopIfEnabled() } catch (_: Exception) {}
    }

    /** सेटिंग्स का वह हिस्सा / कॉलम खोलो (खोलना = कुछ बदलता नहीं, इसलिए पक्का करना नहीं) */
    private fun open(c: Context, q: String): String {
        val hit = SettingsSearch.search(q, 1).firstOrNull()
            ?: DkSettings.find(q)?.let { SettingsSearch.E(it.section, "") }
        val i = when (hit?.section) {
            null -> Intent(c, SettingsActivity::class.java)
            SettingsSearch.ACT_AUTO -> Intent(c, com.dk.assistant.auto.AutoSettingsActivity::class.java)
            SettingsSearch.ACT_TEMPLATES -> Intent(c, com.dk.assistant.template.TemplateSettingsActivity::class.java)
            SettingsSearch.ACT_CONNECTORS -> Intent(c, com.dk.assistant.connect.ConnectorsActivity::class.java)
            SettingsSearch.ACT_STORY -> Intent(c, StoryWriterActivity::class.java)
            else -> Intent(c, SettingsActivity::class.java).putExtra("section", hit.section).also { if (hit.card.isNotBlank()) it.putExtra("focus", hit.card) }
        }
        return try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "opened: " + (hit?.let { SettingsSearch.place(it) + if (it.card.isNotBlank()) " → ${it.card}" else "" } ?: "Settings") + ". Tell the user briefly."
        } catch (e: Exception) { "could_not_open: ${e.message}" }
    }
}
