package com.dk.assistant.tools

import android.bluetooth.BluetoothManager
import android.content.Intent
import android.net.wifi.WifiManager
import android.provider.Settings
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.NameMatch
import com.dk.assistant.NotifStore
import com.dk.assistant.OfflineCommands
import com.dk.assistant.agent.core.ConfirmGate
import com.dk.assistant.plus.A11yRetry
import com.dk.assistant.plus.LocNow
import com.dk.assistant.plus.Sos
import com.dk.assistant.template.TemplateRunner
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * 15 नवंबर 2026 (मालिक, हिस्सा 1): [ToolRegistry] के हर tool को DK के पुराने executors से चलाना + Retry Loop.
 *
 * हर कदम: करो -> स्क्रीन / फ़ोन की असली हालत से जाँचो -> न हुआ तो अगला तरीका.
 * तरीकों का क्रम (जहाँ लागू): node क्लिक -> टेक्स्ट से खोज -> coordinates पर टैप -> Intent (सिस्टम पेज/पैनल).
 * ज़्यादा से ज़्यादा [ToolPolicy.MAX_TRIES] बार; कॉल / मैसेज / SMS जैसे काम सिर्फ़ एक बार ([ToolPolicy.ONCE]) - दोहराने से दूसरा मैसेज चला जाता.
 */
object ToolExec {

    data class Out(val ok: Boolean, val text: String, val tries: Int, val by: String, val question: String? = null)

    private class Way(val name: String, val act: suspend () -> String)

    private fun a(type: String, vararg kv: Pair<String, Any?>) = JSONObject().put("type", type).apply { kv.forEach { (k, v) -> if (v != null) put(k, v) } }

    /** @param ask बोलकर पूछो, जवाब लौटाओ (null = जवाब नहीं) */
    suspend fun run(svc: DKAccessibilityService, name: String, args: JSONObject, ask: suspend (String) -> String?): Out {
        ToolRegistry.missingArg(name, args)?.let { return Out(false, "ERROR: $it", 0, "") }
        val ways = ways(svc, name, args, ask)
        val verify = verifier(svc, name, args)
        val max = ToolPolicy.tries(name)
        var last = ""
        var tries = 0
        // तरीके कम हों तो पहला तरीका ही दोबारा (थोड़ा रुककर) - कुल MAX_TRIES
        val plan = (0 until max).map { ways[it.coerceAtMost(ways.size - 1)] }
        for (w in plan) {
            tries++
            last = try { w.act() } catch (e: Exception) { "ERROR: ${e.message ?: e.javaClass.simpleName}" }
            val kind = ToolPolicy.classify(last)
            if (kind == ToolPolicy.Kind.ASK) return Out(false, last, tries, w.name, ToolPolicy.plain(last))
            if (kind == ToolPolicy.Kind.OK) {
                delay(SETTLE_MS)
                val ok = try { verify(last) } catch (_: Exception) { true }
                DKLog.d("टूल $name: ${w.name} -> ${if (ok) "✓" else "जाँच में नहीं दिखा"} (${last.take(80)})")
                if (ok) return Out(true, last, tries, w.name)
                last = "STEP_FAILED: काम करने के बाद फ़ोन पर बदलाव नहीं दिखा"
            } else DKLog.d("टूल $name: ${w.name} -> ${last.take(100)}")
            if (tries < plan.size) delay(RETRY_GAP_MS)
        }
        return Out(false, last, tries, plan.lastOrNull()?.name ?: "")
    }

    private const val SETTLE_MS = 700L
    private const val RETRY_GAP_MS = 600L

    // ------------------------------------------------------------------ हर tool के तरीके
    private fun ways(svc: DKAccessibilityService, name: String, g: JSONObject, ask: suspend (String) -> String?): List<Way> {
        val p: suspend (JSONObject) -> String = { svc.perform(it) }
        val on = g.optBoolean("on", true)
        return when (name) {
            ToolRegistry.OPEN_APP -> {
                val app = g.optString("name")
                listOf(
                    Way("Intent") { p(a("open_app", "name" to app)) },
                    Way("होम से फिर Intent") { p(a("home")); delay(500); p(a("open_app", "name" to app)) },
                    Way("स्क्रीन पर टेक्स्ट टैप") { if (svc.clickVisibleText(app) || A11yRetry.tapBounds(svc, app)) "ok" else "STEP_FAILED: \"$app\" स्क्रीन पर नहीं दिखा" },
                )
            }
            ToolRegistry.CALL -> listOf(Way("कॉल") { p(a("call_contact", "name" to g.optString("name"))) })
            ToolRegistry.WHATSAPP -> listOf(Way("WhatsApp") {
                val r = TemplateRunner.waSend(svc, g.optString("name"), g.optString("message"), ask)
                if (r.contains("भेज दिया")) "SAID: $r" else "STEP_FAILED: $r"
            })
            ToolRegistry.SMS -> listOf(Way("SMS") { sms(svc, g.optString("name"), g.optString("message"), ask) })
            ToolRegistry.TAP_TEXT -> {
                val t = g.optString("text")
                val latin = NameMatch.latin(t).trim()
                listOf(
                    Way("node क्लिक") { if (svc.clickVisibleText(t)) "ok" else "STEP_FAILED: \"$t\" का बटन नहीं मिला" },
                    Way("टेक्स्ट से खोज") { if ((latin.isNotBlank() && latin != t && svc.clickVisibleText(latin)) || svc.clickVisibleText(t.lowercase())) "ok" else "STEP_FAILED: \"$t\" लिखा नहीं मिला" },
                    Way("coordinates टैप") { if (A11yRetry.tapBounds(svc, t) || (latin.isNotBlank() && A11yRetry.tapBounds(svc, latin))) "ok" else "STEP_FAILED: \"$t\" स्क्रीन पर नहीं दिखा" },
                )
            }
            ToolRegistry.SCROLL -> listOf(Way("स्क्रॉल") { p(a("scroll", "direction" to g.optString("direction", "down"))) })
            ToolRegistry.BACK -> listOf(Way("Back") { p(a("back")) })
            ToolRegistry.HOME -> listOf(Way("Home") { p(a("home")) })
            ToolRegistry.LOCK -> listOf(Way("Lock") { p(a("lock")) })
            ToolRegistry.SCREENSHOT -> listOf(Way("Screenshot") { p(a("screenshot_save")) })
            ToolRegistry.FLASHLIGHT -> listOf(
                Way("सीधा") { p(a("torch", "on" to on)) },
                Way("क्विक सेटिंग्स") { quickTile(svc, listOf("Flashlight", "Torch", "टॉर्च", "फ़्लैशलाइट")) },
            )
            ToolRegistry.WIFI -> listOf(
                Way("Wi-Fi पैनल") { if (wifiOn(svc) == on) "ok" else { p(a("panel", "name" to "wifi")); delay(1200); switchOrFail(svc, "Wi") } },
                Way("Wi-Fi सेटिंग्स") { p(a("open_settings", "page" to "wifi")); delay(1200); switchOrFail(svc, "Wi") },
                Way("क्विक सेटिंग्स") { quickTile(svc, listOf("Wi-Fi", "Wi‑Fi", "Internet", "इंटरनेट", "वाई-फ़ाई")) },
            )
            ToolRegistry.BLUETOOTH -> listOf(
                Way("Bluetooth सेटिंग्स") { if (btOn(svc) == on) "ok" else { p(a("open_settings", "page" to "bluetooth")); delay(1200); switchOrFail(svc, "Bluetooth") } },
                Way("क्विक सेटिंग्स") { quickTile(svc, listOf("Bluetooth", "ब्लूटूथ")) },
                Way("Intent") {
                    if (!on) "STEP_FAILED: Android, Bluetooth बंद करने का सीधा Intent नहीं देता"
                    else try { svc.startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); delay(1200); svc.clickVisibleText("Allow", "अनुमति दें", "चालू करें"); "ok" } catch (e: Exception) { "ERROR: ${e.message}" }
                },
            )
            ToolRegistry.AIRPLANE -> listOf(
                Way("एयरप्लेन सेटिंग्स") { if (airplaneOn(svc) == on) "ok" else { p(a("open_settings", "page" to "airplane")); delay(1200); switchOrFail(svc, "plane") } },
                Way("टेक्स्ट से खोज") { if (svc.clickVisibleText("Airplane mode", "Aeroplane mode", "Flight mode", "एयरप्लेन मोड", "हवाई जहाज़ मोड")) "ok" else "STEP_FAILED: एयरप्लेन मोड का बटन नहीं मिला" },
                Way("क्विक सेटिंग्स") { quickTile(svc, listOf("Airplane", "Aeroplane", "Flight", "एयरप्लेन", "फ़्लाइट")) },
            )
            ToolRegistry.VOLUME -> listOf(Way("आवाज़") { p(a("volume", "direction" to g.optString("direction", "up"), "level" to g.optInt("level", -1), "stream" to "music")) })
            ToolRegistry.BRIGHTNESS -> listOf(Way("ब्राइटनेस") { p(a("brightness", "direction" to g.optString("direction", "up"), "level" to g.optInt("level", -1))) })
            ToolRegistry.MEDIA -> listOf(Way("मीडिया") { p(a("media", "key" to g.optString("key", "play_pause"))) })
            ToolRegistry.YOUTUBE -> {
                val q = g.optString("query")
                listOf(
                    Way("YouTube ऐप") { p(a("youtube_search", "query" to q)) },
                    Way("Intent (वेब लिंक)") { p(a("open_url", "url" to "https://www.youtube.com/results?search_query=" + android.net.Uri.encode(q))) },
                )
            }
            ToolRegistry.ALARM -> listOf(Way("घड़ी ऐप") { p(a("alarm_set", "hour" to g.optInt("hour", -1), "minute" to g.optInt("minute", 0))) })
            ToolRegistry.TIMER -> listOf(Way("घड़ी ऐप") { p(a("timer_set", "seconds" to g.optInt("seconds", 0))) })
            ToolRegistry.READ_SCREEN -> listOf(Way("स्क्रीन") {
                val s = svc.readScreen()
                com.dk.assistant.plus.ConfirmGate.markUntrusted()   // 16 नवंबर 2026 (G2): स्क्रीन की लिखावट = जानकारी, हुक्म नहीं
                "APP: ${s.packageName}\n" + com.dk.assistant.agent.core.OutsideText.wrap("SCREEN", s.text.take(3500).ifBlank { "(स्क्रीन पर कुछ नहीं पढ़ा गया)" })
            })
            ToolRegistry.TYPE -> listOf(Way("टाइप") { p(a("type", "text" to g.optString("text"))) })
            ToolRegistry.NOTIFICATIONS -> listOf(
                Way("नोटिफ़िकेशन सूची") {
                    val list = NotifStore.all().take(10)
                    if (list.isEmpty()) "STEP_FAILED: DK की सूची में कोई नोटिफ़िकेशन नहीं"
                    else { com.dk.assistant.plus.ConfirmGate.markUntrusted(); "SAID: " + com.dk.assistant.agent.core.OutsideText.wrap("NOTIFICATIONS", list.joinToString("\n") { "${it.app}: ${it.title} — ${it.text.take(120)}" }) }
                },
                Way("नोटिफ़िकेशन पट्टी") {
                    p(a("notifications")); delay(900)
                    val t = svc.readScreen().text.take(3000); p(a("back"))
                    if (t.isBlank()) "STEP_FAILED: पट्टी पढ़ी नहीं गई" else { com.dk.assistant.plus.ConfirmGate.markUntrusted(); "SAID: " + com.dk.assistant.agent.core.OutsideText.wrap("NOTIFICATIONS", t) }
                },
            )
            ToolRegistry.LOCATION -> listOf(Way("लोकेशन") {
                val loc = LocNow.get(svc) ?: return@Way if (LocNow.permitted(svc)) "STEP_FAILED: GPS से लोकेशन नहीं मिली" else "ERROR_PERM: लोकेशन की इजाज़त नहीं है"
                val msg = "मेरी अभी की लोकेशन: " + Sos.Rules.mapsLink(loc.latitude, loc.longitude)
                if (g.optString("via", "whatsapp") == "sms") sms(svc, g.optString("name"), msg, ask)
                else TemplateRunner.waSend(svc, g.optString("name"), msg, ask).let { if (it.contains("भेज दिया")) "SAID: लोकेशन भेज दी।" else "STEP_FAILED: $it" }
            })
            ToolRegistry.BATTERY -> listOf(Way("बैटरी") { p(a("info", "what" to "battery")) })
            else -> listOf(Way("—") { "ERROR: ऐसा कोई tool नहीं: $name" })
        }
    }

    // ------------------------------------------------------------------ जाँच (verify)
    private fun verifier(svc: DKAccessibilityService, name: String, g: JSONObject): suspend (String) -> Boolean {
        val before = A11yRetry.signature(svc)
        val on = g.optBoolean("on", true)
        return when (name) {
            ToolRegistry.OPEN_APP -> { _ -> svc.currentPackage().let { it.isNotBlank() && it != svc.packageName } }
            ToolRegistry.TAP_TEXT -> { _ -> A11yRetry.signature(svc) != before }
            ToolRegistry.WIFI -> { _ -> wifiOn(svc)?.let { it == on } ?: true }
            ToolRegistry.BLUETOOTH -> { _ -> btOn(svc)?.let { it == on } ?: true }
            ToolRegistry.AIRPLANE -> { _ -> airplaneOn(svc)?.let { it == on } ?: true }
            else -> { _ -> true }
        }
    }

    private fun wifiOn(svc: DKAccessibilityService): Boolean? = try { svc.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled } catch (_: Exception) { null }
    @Suppress("MissingPermission")
    private fun btOn(svc: DKAccessibilityService): Boolean? = try { svc.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled } catch (_: Exception) { null }
    private fun airplaneOn(svc: DKAccessibilityService): Boolean? = try { Settings.Global.getInt(svc.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 } catch (_: Exception) { null }

    private fun switchOrFail(svc: DKAccessibilityService, label: String): String =
        if (A11yRetry.tapSwitch(svc, label)) "ok" else "STEP_FAILED: स्विच नहीं मिला"

    private suspend fun quickTile(svc: DKAccessibilityService, names: List<String>): String {
        svc.perform(a("quick_settings")); delay(900)
        val hit = svc.clickVisibleText(*names.toTypedArray()) || names.any { A11yRetry.tapBounds(svc, it) }
        delay(500); svc.perform(a("back"))
        return if (hit) "ok" else "STEP_FAILED: क्विक सेटिंग्स में बटन नहीं मिला"
    }

    /** SMS: पुराना OfflineExec रास्ता (पहले पूछो, फिर भेजो) */
    private suspend fun sms(svc: DKAccessibilityService, name: String, msg: String, ask: suspend (String) -> String?): String {
        val r = svc.perform(a("sms_send", "name" to name, "text" to msg))
        if (!r.startsWith("ASK") || OfflineCommands.pending == null) return r
        val ans = ask(ToolPolicy.plain(r)).orEmpty()
        return svc.perform(a("sms_confirm", "yes" to ConfirmGate.isYes(ans)))
    }
}
