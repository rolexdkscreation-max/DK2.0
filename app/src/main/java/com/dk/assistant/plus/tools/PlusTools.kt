package com.dk.assistant.plus.tools

import android.content.Context
import com.dk.assistant.plus.FeatureFlags
import com.dk.assistant.plus.ImageGen
import com.dk.assistant.plus.Termux
import com.dk.assistant.plus.YtChannel
import org.json.JSONObject

/**
 * 15 नवंबर 2026 (मालिक: "ऐसा रेडी करो कि कोई भी फीचर बाद में जोड़ना हो तो जोड़ सकें, पुराना बिगाड़ना न पड़े").
 *
 * DK का tool plug-in: नया बोलकर चलने वाला काम = एक [PlusTool] बनाओ और [ALL] में एक लाइन जोड़ो. बस.
 *  - Live setup ([com.dk.assistant.GeminiLiveSetup]) चालू tools की [declarations] खुद जोड़ता है
 *    (वे [com.dk.assistant.LiveToolSlim] के dk_agent router के पीछे जाते हैं - setup भारी नहीं होता).
 *  - Live का tool runner ([com.dk.assistant.runTools]) पहले [runOrNull] पूछता है - हमारा नहीं तो null, पुराना रास्ता वैसा ही.
 *  - हर tool का अपना FeatureFlag - शुरू में बंद; बंद tool Live को दिखता ही नहीं.
 * पुरानी फ़ाइलों में सिर्फ़ दो छोटी जोड़ (hook), कोई पुराना tool नहीं बदला.
 */
interface PlusTool {
    /** Live tool का नाम (पुराने tools से अलग - [PlusToolsTest] जाँचता है) */
    val name: String
    /** [FeatureFlags] की key - बंद = tool Live को नहीं दिखता */
    val flag: String
    /** Gemini functionDeclaration (name/description/parameters) */
    fun declaration(): JSONObject
    suspend fun run(c: Context, args: JSONObject): String
}

object PlusTools {
    val ALL: List<PlusTool> = listOf(ImageTool, YouTubeTool, TermuxTool)

    fun byName(name: String): PlusTool? = ALL.firstOrNull { it.name == name }

    fun enabled(c: Context): List<PlusTool> = ALL.filter { FeatureFlags.on(c, it.flag) }

    fun declarations(c: Context): List<JSONObject> = enabled(c).map { it.declaration() }

    /** हमारा tool और चालू = चलाओ; हमारा नहीं = null (पुराना रास्ता) */
    suspend fun runOrNull(c: Context, name: String, args: JSONObject): String? {
        val t = byName(name) ?: return null
        if (!FeatureFlags.on(c, t.flag)) return "This ability is turned OFF. Tell the user and offer to turn it ON for him with dk_settings (two-step confirmation) - " +
            "or he can do it in DK Settings (image: DK कंप्यूटर -> इमेज बनाना; YouTube: सोशल रिव्यू -> YouTube चैनल जाँच; Termux: कोडिंग एजेंट -> Termux ऑटोमेशन)."
        return try { t.run(c, args) } catch (e: Exception) { "ERROR: ${e.message ?: "failed"}" }
    }

    internal fun decl(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()): JSONObject = JSONObject()
        .put("name", name).put("description", desc)
        .put("parameters", JSONObject().put("type", "OBJECT").put("properties", props)
            .apply { if (required.isNotEmpty()) put("required", org.json.JSONArray(required)) })

    internal fun str(desc: String): JSONObject = JSONObject().put("type", "STRING").put("description", desc)
}

/** मुफ़्त API से तस्वीर (Pollinations / AI Horde / Hugging Face / अपनी) - ChatGPT/Gemini ऐप वाले image_task से अलग, सीधा API */
object ImageTool : PlusTool {
    override val name = "free_image"
    override val flag = FeatureFlags.IMAGE_GEN
    override fun declaration() = PlusTools.decl(name,
        "Make a picture directly with DK's own free image API (no other app opens) and save it to the phone gallery. " +
            "Use it when the user asks to make/draw/generate an image or photo (\"एक शेर की तस्वीर बनाओ\"). " +
            "Write prompt in English, detailed. After the result, tell the user in one short line that it is saved in the gallery.",
        org.json.JSONObject().put("prompt", PlusTools.str("what to draw, in English, detailed")), listOf("prompt"))

    override suspend fun run(c: Context, args: JSONObject): String {
        val r = ImageGen.generate(c, args.optString("prompt"))
        return r.error?.let { "ERROR: $it. Tell the user briefly; free image keys can be added in DK Settings -> DK कंप्यूटर -> इमेज बनाना." }
            ?: "DONE: image made via ${r.via} and saved ${if (r.file != null) "inside DK (private)" else "in the gallery (Pictures/DK AI)"}."
    }
}

/** YouTube चैनल की जाँच - सिर्फ़ पढ़ना */
object YouTubeTool : PlusTool {
    override val name = "youtube_channel"
    override val flag = FeatureFlags.YT_CHANNEL
    override fun declaration() = PlusTools.decl(name,
        "Check a YouTube channel's numbers: subscribers, total views, video count, latest videos with views/likes/comments and newest comments. " +
            "Use it for 'मेरे चैनल का हाल बताओ', 'कितने subscriber हुए', 'नए वीडियो पर कितने views'. Leave channel empty for the user's own saved channel. " +
            "Answer only from the exact numbers returned, in short Hindi.",
        org.json.JSONObject().put("channel", PlusTools.str("@handle, channel ID or link; empty = user's own channel")))

    override suspend fun run(c: Context, args: JSONObject): String {
        val r = YtChannel.check(c, args.optString("channel"))
        return r.error?.let { "ERROR: $it" } ?: YtChannel.text(r.report!!)
    }
}

/** 15 नवंबर 2026: DK लाइव बोलकर Termux में कमांड चलाए (Termux ऑटोमेशन चालू हो तब) - खतरनाक कमांड नहीं */
object TermuxTool : PlusTool {
    override val name = "termux_run"
    override val flag = FeatureFlags.TERMUX
    override fun declaration() = PlusTools.decl(name,
        "Run a shell command in the Termux app on this phone (Termux automation). Use it when the user asks to run a command / script / " +
            "install a package in Termux (\"Termux में python चलाओ\", \"pkg install git करो\"). Before running, tell the user the exact command in one line. " +
            "Never run commands that delete data, format, reboot or use su - those are blocked. Output shows inside Termux (background=false opens its window).",
        org.json.JSONObject().put("command", PlusTools.str("the exact bash command"))
            .put("background", org.json.JSONObject().put("type", "BOOLEAN").put("description", "true = run silently (default), false = open Termux window")),
        listOf("command"))

    override suspend fun run(c: Context, args: JSONObject): String {
        val cmd = args.optString("command").trim()
        if (cmd.isBlank()) return "ERROR: no command. Ask the user what to run."
        if (Termux.Rules.dangerous(cmd)) return "BLOCKED: this command can delete data or harm the phone. Tell the user DK will not run it by voice - he can run it himself in Termux."
        return Termux.run(c, cmd, background = args.optBoolean("background", true))?.let { "ERROR: $it Tell the user briefly." }
            ?: "SENT: the command is running in Termux. Tell the user briefly."
    }
}
