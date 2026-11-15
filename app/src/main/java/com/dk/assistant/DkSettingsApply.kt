package com.dk.assistant

import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * v5: DK लाइव के `dk_settings` tool का फ़ोन वाला हिस्सा - सेटिंग पढ़ना / (दो बार पक्का करके) बदलना / सेटिंग्स का हिस्सा खोलना.
 * नियम और दो-बार-पक्का [DkSettings] में (JVM test).
 */
object DkSettingsApply {
    /** 15 नवंबर 2026: हर सुविधा अपनी असली जगह से पढ़ी जाती है (Prefs / FeatureFlags / अपना setter) */
    private fun prefOn(c: Context, it: DkSettings.Item): Boolean = try {
        when (it.store) {
            DkSettings.STORE_FLAGS -> com.dk.assistant.plus.FeatureFlags.on(c, it.pref)
            DkSettings.STORE_CUSTOM -> when (it.id) {
                "autoreply" -> com.dk.assistant.autoreply.AutoReply.on(c)
                "autonomous" -> com.dk.assistant.auto.AutoPrefs.on(c)
                "friday" -> AssistantPersona.active(c) == AssistantPersona.A.FRIDAY
                "venom" -> AssistantPersona.active(c) == AssistantPersona.A.VENOM
                "god_mode" -> GuardModes.mode(c) == GuardModes.Mode.GOD
                "stealth" -> GuardModes.mode(c) == GuardModes.Mode.STEALTH
                else -> false
            }
            else -> DkSettings.isOn(it, Prefs.bool(c, it.pref, it.def))
        }
    } catch (_: Exception) { false }

    /** एक ही जगह लिखना - बोलकर बदलना और मेनू का स्विच दोनों यही इस्तेमाल करते हैं */
    private fun write(c: Context, it: DkSettings.Item, on: Boolean) {
        when (it.store) {
            DkSettings.STORE_FLAGS -> com.dk.assistant.plus.FeatureFlags.set(c, it.pref, on)
            DkSettings.STORE_CUSTOM -> when (it.id) {
                "autoreply" -> com.dk.assistant.autoreply.AutoReply.setOn(c, on)
                "autonomous" -> com.dk.assistant.auto.AutoPrefs.setOn(c, on)
                // 15 नवंबर 2026: एक समय में एक ही रूप - बंद करने पर सिर्फ़ वही बंद जो चालू था
                "friday", "venom" -> {
                    val a = if (it.id == "friday") AssistantPersona.A.FRIDAY else AssistantPersona.A.VENOM
                    if (on) AssistantPersona.set(c, a) else if (AssistantPersona.active(c) == a) AssistantPersona.set(c, null)
                }
                "god_mode", "stealth" -> {
                    val m = if (it.id == "god_mode") GuardModes.Mode.GOD else GuardModes.Mode.STEALTH
                    if (on) GuardModes.set(c, m) else if (GuardModes.mode(c) == m) GuardModes.set(c, GuardModes.Mode.NORMAL)
                }
            }
            else -> Prefs.edit(c) {
                putBoolean(it.pref, DkSettings.prefFor(it, on))
                (if (on) it.alsoOn else it.alsoOff).forEach { (k, v) -> putBoolean(k, v) }
            }
        }
    }

    /** चालू करने के बाद फ़ोन की कौन-सी इजाज़त अभी बाकी है (सर्विस से इजाज़त माँगी नहीं जा सकती - यूज़र को बताओ) */
    private fun missingPerms(c: Context, it: DkSettings.Item): List<String> {
        val need = when (it.id) {
            "sos" -> listOf(android.Manifest.permission.SEND_SMS, android.Manifest.permission.CALL_PHONE, android.Manifest.permission.ACCESS_FINE_LOCATION)
            "face", "watchman" -> listOf(android.Manifest.permission.CAMERA)
            "driving" -> listOf(android.Manifest.permission.ANSWER_PHONE_CALLS, android.Manifest.permission.SEND_SMS, android.Manifest.permission.READ_PHONE_STATE)
            else -> emptyList()
        }
        return need.filter { p -> c.checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED }
    }

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
                val miss = if (on) missingPerms(c, item) else emptyList()
                "done: '${item.name}' अब ${DkSettings.onOff(on)} है। Tell the user briefly that it is done." +
                    (if (miss.isEmpty()) "" else " Phone permission still missing (${miss.joinToString { p -> p.substringAfterLast('.') }}) - tell the user and offer to open Settings (action=open, setting='${item.id}') where touching the switch asks for it.")
            }
            else -> DkSettings.catalog { prefOn(c, it) }
        }
    }

    private fun apply(c: Context, it: DkSettings.Item, on: Boolean) {
        write(c, it, on)
        DKLog.d("DK ने सेटिंग बदली (यूज़र ने दो बार पक्का किया): ${it.name} -> ${DkSettings.onOff(on)}")
        try { if (it.pref == "duck_media" && !on) Ducker.releaseNow() } catch (_: Exception) {}
        try { DKAccessibilityService.instance?.startWakeLoopIfEnabled() } catch (_: Exception) {}
    }

    /**
     * नवंबर 2026: Settings के मुख्य मेनू वाले कार्ड का स्विच (यूज़र ने खुद छुआ - दो बार पूछना नहीं).
     * वही pref + alsoOn/alsoOff जो अंदर वाले हिस्से का स्विच / बोलकर बदलना लिखता है.
     */
    fun setFromUi(c: Context, id: String, on: Boolean): Boolean {
        val it = DkSettings.ITEMS.firstOrNull { x -> x.id == id } ?: return false
        write(c, it, on)
        DKLog.d("Settings मेनू से बदली: ${it.name} -> ${DkSettings.onOff(on)}")
        try { DKAccessibilityService.instance?.startWakeLoopIfEnabled() } catch (_: Exception) {}
        return true
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
