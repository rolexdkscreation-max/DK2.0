package com.dk.assistant.agent.android

import com.dk.assistant.AppLauncher
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.DKState
import com.dk.assistant.Mode
import com.dk.assistant.agent.core.DriverResult
import com.dk.assistant.agent.core.FormFill
import com.dk.assistant.agent.core.ScreenObservation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * नवंबर 2026 (मालिक): किसी भी ऐप / ब्राउज़र में फ़ॉर्म भरना (Live का `form_fill` tool).
 *
 * - read: जो ऐप सामने है (या कहा गया ऐप - एक ही बार खोलता है) उसी में रहकर पूरा पेज ऊपर से नीचे स्क्रॉल करके सारा लिखा और
 *   सारे खाने पढ़ता है, फिर वापस ऊपर. Live मालिक से एक-एक खाना पूछता है.
 * - fill: वही ऐप सामने हो तो उसी में खाना ढूँढकर (ज़रूरत हो तो स्क्रॉल) भरता है. कभी बैक नहीं, कभी दूसरा ऐप नहीं खोलता -
 *   ऐप बदल गया तो कुछ नहीं छूता, Live को बताता है.
 * - submit: सिर्फ़ मालिक की साफ़ "हाँ" (confirm=true) के बाद.
 * एक समय में एक ही काम (Mutex) - दो काम आपस में नहीं टकराते.
 */
object FormFillAgent {
    private val lock = Mutex()
    @Volatile private var pkg: String = ""
    @Volatile private var app: String = ""
    @Volatile private var fields: List<FormFill.Field> = emptyList()
    private val filled = LinkedHashMap<String, String>()
    private const val MAX_SCROLLS = 8

    suspend fun liveTool(svc: DKAccessibilityService, args: JSONObject): String {
        val act = args.optString("action").trim().lowercase()
        if (lock.isLocked) return "BUSY: DK is still filling the previous field - wait a moment and call again."
        return lock.withLock {
            DKState.set(Mode.WORKING)
            try {
                when (act) {
                    "read", "start", "scan", "" -> read(svc, args.optString("app"))
                    "fill", "type", "set" -> fill(svc, args.optString("field"), args.optString("value"))
                    "submit" -> submit(svc, args.optBoolean("confirm", false))
                    "status" -> status()
                    "stop", "done", "cancel" -> stop()
                    else -> "ERROR: unknown action '$act'. Use read / fill / submit / status / stop."
                }
            } catch (e: Exception) { "FAILED: ${e.message?.take(120)}" }
        }
    }

    private fun label(svc: DKAccessibilityService, p: String): String = try {
        svc.packageManager.getApplicationLabel(svc.packageManager.getApplicationInfo(p, 0)).toString()
    } catch (_: Exception) { p }

    private suspend fun screen(d: AccessibilityScreenDriver, own: String): ScreenObservation? =
        d.capture()?.takeIf { it.pkg.isNotBlank() && it.pkg != own }

    private suspend fun read(svc: DKAccessibilityService, appName: String): String {
        val d = AccessibilityScreenDriver(svc)
        var o = screen(d, svc.packageName)
        // ऐप का नाम कहा और वह सामने नहीं - सिर्फ़ एक बार खोलो (बार-बार नहीं)
        if (appName.isNotBlank() && (o == null || !label(svc, o.pkg).contains(appName.trim(), ignoreCase = true))) {
            val r = AppLauncher.open(svc, appName)
            if (r.startsWith("ERROR") || r.startsWith("ASK")) return r
            d.pause(2_500); o = screen(d, svc.packageName)
        }
        o ?: return "FAILED: no app screen is visible (Accessibility on?). Ask the user to open the form page."
        pkg = o.pkg; app = label(svc, o.pkg)
        var fs = FormFill.fields(o); var texts = o.texts()
        // पूरा पेज: नीचे तक स्क्रॉल करके सब पढ़ो, फिर उतना ही ऊपर (उसी ऐप में - बैक नहीं)
        var sig = FormFill.signature(o); var down = 0
        while (down < MAX_SCROLLS) {
            if (d.scroll(true) != DriverResult.OK) break
            d.pause(600)
            val n = screen(d, svc.packageName) ?: break
            if (n.pkg != pkg) break
            val s = FormFill.signature(n); if (s == sig) break
            sig = s; down++
            fs = FormFill.merge(fs, FormFill.fields(n)); texts = FormFill.mergeTexts(texts, n.texts())
        }
        repeat(down) { screen(d, svc.packageName); if (d.scroll(false) != DriverResult.OK) return@repeat; d.pause(350) }
        fields = fs
        synchronized(filled) { filled.clear() }
        DKLog.d("फ़ॉर्म भरना: $app में ${fs.size} खाने मिले")
        return FormFill.describe(app, fs, texts)
    }

    /** इसी ऐप में खाना ढूँढो - दिखे नहीं तो नीचे, फिर ऊपर स्क्रॉल */
    private suspend fun locate(d: AccessibilityScreenDriver, own: String, want: FormFill.Field): Pair<ScreenObservation, Int>? {
        suspend fun look(): Pair<ScreenObservation, Int>? {
            val o = screen(d, own) ?: return null
            if (o.pkg != pkg) return null
            val f = FormFill.fields(o)
            val hit = f.firstOrNull { it.key == want.key } ?: FormFill.match(f, want.label) ?: return null
            val e = o.elements.firstOrNull { it.bounds == hit.box } ?: return null
            return o to e.id
        }
        look()?.let { return it }
        for (dir in listOf(true, false)) repeat(if (dir) MAX_SCROLLS else MAX_SCROLLS * 2) {
            if (screen(d, own)?.pkg != pkg) return null
            if (d.scroll(dir) != DriverResult.OK) return@repeat
            d.pause(450)
            look()?.let { return it }
        }
        return null
    }

    private suspend fun fill(svc: DKAccessibilityService, field: String, value: String): String {
        if (pkg.isBlank() || fields.isEmpty()) return "NO FORM READ YET: call form_fill action=read first (DK reads the whole form on the screen)."
        val want = FormFill.match(fields, field) ?: return "NOT FOUND: no field named '$field'. Fields are:\n" +
            fields.mapIndexed { i, f -> FormFill.line(i, f) }.joinToString("\n") + "\nAsk the user which one."
        if (want.secret) return "PRIVATE FIELD '${want.label}': DK does not type passwords / OTP / PIN. Ask the user to type it himself on the screen."
        val d = AccessibilityScreenDriver(svc)
        val now = screen(d, svc.packageName)
        if (now == null || now.pkg != pkg) return "PAUSED: the form app ($app) is not on the screen now - DK did not switch apps or press back. " +
            "Ask the user to bring $app back, then call action=fill again."
        val (o, id) = locate(d, svc.packageName, want) ?: return "NOT VISIBLE: could not find '${want.label}' on the $app screen (stayed in the app). Ask the user to scroll to it, then call fill again."
        val e = o.elements[id]
        val r = when (want.kind) {
            FormFill.Kind.TEXT -> d.setText(o, e, value.trim())
            FormFill.Kind.CHECK -> {
                val y = FormFill.yes(value)
                if (y == false && synchronized(filled) { filled[want.key] } == null) DriverResult.OK   // पहले से टिक नहीं किया = छोड़ो
                else d.click(o, e)
            }
            FormFill.Kind.CHOICE -> {
                if (d.click(o, e) != DriverResult.OK) DriverResult.FAILED else {
                    d.pause(800)
                    val l = screen(d, svc.packageName)
                    val opt = l?.elements?.firstOrNull { it.text.isNotBlank() && FormFill.norm(it.text) == FormFill.norm(value) }
                        ?: l?.elements?.firstOrNull { it.text.isNotBlank() && FormFill.norm(it.text).contains(FormFill.norm(value)) }
                    if (l == null || opt == null) {
                        return "OPTIONS OPEN for '${want.label}' but '$value' is not in the list. Options visible: " +
                            l?.texts().orEmpty().take(25).joinToString(" | ") + ". Ask the user which one, then call fill again with that exact option."
                    }
                    d.click(l, opt)
                }
            }
        }
        if (r != DriverResult.OK) return "FAILED to fill '${want.label}' ($r). Stayed in $app. Ask the user to tap that field once, then call fill again."
        d.pause(400)
        synchronized(filled) { filled[want.key] = value.trim() }
        val left = fields.filter { f -> !f.secret && f.value.isBlank() && synchronized(filled) { f.key !in filled } }
        DKLog.d("फ़ॉर्म भरना: '${want.label}' भरा")
        return "FILLED '${want.label}' = \"${value.trim()}\" in $app (same screen, no back). " +
            (if (left.isEmpty()) "All fields are done - read back the values and ask 'सबमिट कर दूँ?'. Only after yes: action=submit confirm=true."
            else "Next empty field: '${left.first().label}' - ask the user for it (one short question). Remaining: " + left.joinToString(", ") { it.label })
    }

    private suspend fun submit(svc: DKAccessibilityService, confirm: Boolean): String {
        if (!confirm) return "WAITING_CONFIRMATION: read back what was filled and ask the user 'सबमिट कर दूँ?'. Only after a clear yes call action=submit confirm=true."
        if (pkg.isBlank()) return "NO FORM READ YET."
        val d = AccessibilityScreenDriver(svc)
        repeat(MAX_SCROLLS + 1) { i ->
            val o = screen(d, svc.packageName) ?: return "FAILED: screen not readable."
            if (o.pkg != pkg) return "PAUSED: the form app ($app) is not on the screen - nothing submitted."
            FormFill.submitButton(o)?.let { b ->
                val r = d.click(o, b)
                d.pause(1_200)
                val after = screen(d, svc.packageName)
                return if (r == DriverResult.OK) "SUBMITTED: pressed '${b.text.ifBlank { b.desc }}' in $app. Screen now says: " +
                    after?.texts().orEmpty().take(30).joinToString(" | ").take(900) + ". Tell the user what happened in short Hindi."
                else "FAILED to press the submit button. Ask the user to press it."
            }
            if (i < MAX_SCROLLS && d.scroll(true) != DriverResult.OK) return "NOT FOUND: no Submit / Next / Save button on the $app screen. Ask the user to press it."
            d.pause(450)
        }
        return "NOT FOUND: no Submit / Next / Save button. Ask the user to press it."
    }

    private fun status(): String {
        if (pkg.isBlank()) return "No form is open. Call action=read when the user is on a form."
        val f = synchronized(filled) { filled.toMap() }
        return "FORM in $app: " + fields.mapIndexed { i, x -> FormFill.line(i, x.copy(value = f[x.key] ?: x.value)) }.joinToString("\n")
    }

    private fun stop(): String {
        pkg = ""; app = ""; fields = emptyList(); synchronized(filled) { filled.clear() }
        return "FORM FILL stopped (nothing was submitted by DK unless the user said yes)."
    }
}
