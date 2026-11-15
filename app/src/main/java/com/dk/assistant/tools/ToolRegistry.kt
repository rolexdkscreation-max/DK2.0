package com.dk.assistant.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * 15 नवंबर 2026 (मालिक, हिस्सा 1): "ToolRegistry - हर tool का नाम, विवरण और function एक जगह; सिर्फ़ 20-30 ज़रूरी tools".
 *
 * यह pure हिस्सा है (Android import नहीं) - सिर्फ़ सूची और Gemini function calling के declarations ([declarations]).
 * चलाने वाला हिस्सा [ToolExec] में है, और वह कोई काम दोबारा नहीं लिखता - DK के पुराने executors
 * ([com.dk.assistant.DKAccessibilityService.perform], OfflineExec, NativeActionEngine, TemplateRunner) को ही बुलाता है.
 * JVM test: [ToolRegistryTest].
 */
object ToolRegistry {

    data class Param(val name: String, val type: String, val desc: String, val required: Boolean = true, val enum: List<String> = emptyList())

    data class Tool(val name: String, val desc: String, val params: List<Param> = emptyList())

    // ---- नाम (बाकी code इन्हीं से)
    const val OPEN_APP = "open_app"
    const val CALL = "call_contact"
    const val WHATSAPP = "send_whatsapp"
    const val SMS = "send_sms"
    const val TAP_TEXT = "tap_text"
    const val SCROLL = "scroll"
    const val BACK = "go_back"
    const val HOME = "go_home"
    const val LOCK = "lock_screen"
    const val SCREENSHOT = "screenshot"
    const val FLASHLIGHT = "flashlight"
    const val WIFI = "wifi"
    const val BLUETOOTH = "bluetooth"
    const val AIRPLANE = "airplane_mode"
    const val VOLUME = "volume"
    const val BRIGHTNESS = "brightness"
    const val MEDIA = "music"
    const val YOUTUBE = "youtube_search"
    const val ALARM = "set_alarm"
    const val TIMER = "set_timer"
    const val READ_SCREEN = "read_screen"
    const val TYPE = "type_text"
    const val NOTIFICATIONS = "read_notifications"
    const val LOCATION = "send_location"
    const val BATTERY = "battery"
    // काम पूरा / user से पूछना (loop का नियंत्रण, फ़ोन का काम नहीं)
    const val DONE = "done"
    const val ASK = "ask_user"

    private fun s(n: String, d: String, req: Boolean = true, enum: List<String> = emptyList()) = Param(n, "string", d, req, enum)
    private fun i(n: String, d: String, req: Boolean = true) = Param(n, "integer", d, req)
    private fun b(n: String, d: String, req: Boolean = true) = Param(n, "boolean", d, req)

    val ALL: List<Tool> = listOf(
        Tool(OPEN_APP, "Open an installed app by its name (e.g. WhatsApp, YouTube, Settings).", listOf(s("name", "App name as the user said it"))),
        Tool(CALL, "Phone call a saved contact by name or a phone number.", listOf(s("name", "Contact name or number"))),
        Tool(WHATSAPP, "Send a WhatsApp text message to a contact (DK asks the user to confirm before Send).",
            listOf(s("name", "Contact name"), s("message", "Message text"))),
        Tool(SMS, "Send an SMS to a contact name or number (DK asks the user to confirm before sending).",
            listOf(s("name", "Contact name or number"), s("message", "Message text"))),
        Tool(TAP_TEXT, "Tap the visible button / item whose text or description matches.", listOf(s("text", "Exact visible text on screen"))),
        Tool(SCROLL, "Scroll the current screen.", listOf(s("direction", "Scroll direction", enum = listOf("down", "up", "left", "right")))),
        Tool(BACK, "Press the Android Back button."),
        Tool(HOME, "Go to the home screen."),
        Tool(LOCK, "Lock the phone screen."),
        Tool(SCREENSHOT, "Take a screenshot and save it to the gallery."),
        Tool(FLASHLIGHT, "Turn the flashlight (torch) on or off.", listOf(b("on", "true = on, false = off"))),
        Tool(WIFI, "Open the Wi-Fi panel and turn Wi-Fi on or off.", listOf(b("on", "true = on, false = off"))),
        Tool(BLUETOOTH, "Open Bluetooth settings and turn Bluetooth on or off.", listOf(b("on", "true = on, false = off"))),
        Tool(AIRPLANE, "Open airplane mode settings and switch it.", listOf(b("on", "true = on, false = off"))),
        Tool(VOLUME, "Change media volume.", listOf(s("direction", "up / down / mute / max / set", enum = listOf("up", "down", "mute", "max", "set")),
            i("level", "0-100 when direction=set", req = false))),
        Tool(BRIGHTNESS, "Change screen brightness.", listOf(s("direction", "up / down / max / min / set", enum = listOf("up", "down", "max", "min", "set")),
            i("level", "0-100 when direction=set", req = false))),
        Tool(MEDIA, "Control music / video playback.", listOf(s("key", "Playback command", enum = listOf("play_pause", "play", "pause", "next", "previous")))),
        Tool(YOUTUBE, "Search YouTube for a query.", listOf(s("query", "What to search"))),
        Tool(ALARM, "Set an alarm (24-hour clock).", listOf(i("hour", "0-23"), i("minute", "0-59", req = false))),
        Tool(TIMER, "Start a countdown timer.", listOf(i("seconds", "Timer length in seconds"))),
        Tool(READ_SCREEN, "Read the texts and buttons visible on the screen right now."),
        Tool(TYPE, "Type text into the focused (or first) text box on screen.", listOf(s("text", "Text to type"))),
        Tool(NOTIFICATIONS, "Open the notification shade and read the notifications."),
        Tool(LOCATION, "Send the phone's current location (Google Maps link) to a contact.",
            listOf(s("name", "Contact name or number"), s("via", "whatsapp or sms", req = false, enum = listOf("whatsapp", "sms")))),
        Tool(BATTERY, "Tell the battery level and charging state."),
        Tool(DONE, "Call when the whole task is finished. summary = one short Hindi sentence for the user.", listOf(s("summary", "Short Hindi result"))),
        Tool(ASK, "Ask the user a short question when something is unclear (which contact, what message...).", listOf(s("question", "Short Hindi question"))),
    )

    private val INDEX = ALL.associateBy { it.name }
    fun byName(n: String): Tool? = INDEX[n]

    /** अगर मालिक 20-30 की सीमा न तोड़े: फ़ोन वाले tools (done/ask छोड़कर) */
    val PHONE_TOOLS: List<Tool> get() = ALL.filter { it.name != DONE && it.name != ASK }

    /** Gemini REST `tools: [{functionDeclarations: [...]}]` */
    fun declarations(): JSONArray {
        val fns = JSONArray()
        for (t in ALL) {
            val fn = JSONObject().put("name", t.name).put("description", t.desc)
            if (t.params.isNotEmpty()) {
                val props = JSONObject()
                val req = JSONArray()
                for (p in t.params) {
                    val o = JSONObject().put("type", p.type).put("description", p.desc)
                    if (p.enum.isNotEmpty()) o.put("enum", JSONArray(p.enum))
                    props.put(p.name, o)
                    if (p.required) req.put(p.name)
                }
                val sch = JSONObject().put("type", "object").put("properties", props)
                if (req.length() > 0) sch.put("required", req)
                fn.put("parameters", sch)
            }
            fns.put(fn)
        }
        return JSONArray().put(JSONObject().put("functionDeclarations", fns))
    }

    /** AI ने गलत / अधूरे arguments दिए = पहले ही साफ़ कारण (फ़ोन पर कुछ न चले). null = ठीक है */
    fun missingArg(name: String, args: JSONObject): String? {
        val t = byName(name) ?: return "ऐसा कोई tool नहीं: $name"
        for (p in t.params) if (p.required && (!args.has(p.name) || args.optString(p.name).isBlank()))
            return "$name के लिए \"${p.name}\" नहीं दिया"
        return null
    }
}
