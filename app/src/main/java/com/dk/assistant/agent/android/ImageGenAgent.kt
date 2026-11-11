package com.dk.assistant.agent.android

import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.GeminiModels
import com.dk.assistant.Prefs
import com.dk.assistant.agent.core.ImageApp
import com.dk.assistant.agent.core.ImageGenFlow
import com.dk.assistant.agent.core.ImageGenHost
import com.dk.assistant.agent.core.ImageGenResult
import com.dk.assistant.agent.core.ImagePrompt
import com.dk.assistant.agent.core.VisionPoint
import com.dk.assistant.swipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * v3.152: DK लाइव का image_task - असली ChatGPT / Gemini ऐप में इमेज बनाना, डाउनलोड करना, और
 * "तस्वीर में क्या दिख रहा है" बताना (डाउनलोड हुई फ़ाइल, न मिले तो स्क्रीनशॉट -> Gemini vision उसी key से)।
 * तस्वीर Live को भी दिखाई जाती है (sendFrame) - ताकि आप उसके बारे में आगे पूछ सकें।
 */
object ImageGenAgent {
    private val http by lazy {
        com.dk.assistant.NetFix.builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()
    }

    internal class Host(private val svc: DKAccessibilityService) : ImageGenHost {
        override fun installed(pkg: String) = try { svc.packageManager.getLaunchIntentForPackage(pkg) != null } catch (_: Exception) { false }
        override suspend fun openApp(pkg: String): Boolean {
            val i = svc.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            return try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                withContext(Dispatchers.Main) { svc.startActivity(i) }
                svc.waitForeground(7_000) { it == pkg }
            } catch (e: Exception) { DKLog.d("ImageGen: ऐप नहीं खुला - ${e.message}"); false }
        }
        override suspend fun longPress(x: Int, y: Int) = svc.swipe(x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), 750)
        override suspend fun newestImageSince(startMs: Long): String? = newest(svc, startMs)?.second
        override fun now() = System.currentTimeMillis()
        override suspend fun imeEnter(): Boolean {
            if (Build.VERSION.SDK_INT < 30) return false
            val f = try { svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null } ?: return false
            return try { f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) } catch (_: Exception) { false }
        }
        override fun canLook() = canSee(svc)
        override suspend fun look(goal: String): Pair<Int, Int>? = lookAt(svc, goal)
        /** नवंबर 2026: फ़ोन का कोई भी ऐप नाम से (launcher का नाम - पूरा वही, फिर शुरू/अंदर) */
        override fun findApp(name: String): Pair<String, String>? {
            val q = name.trim().lowercase().removeSuffix(" app").removeSuffix(" ऐप").trim()
            if (q.length < 2) return null
            val apps = try { com.dk.assistant.AppLauncher.appList(svc) } catch (_: Exception) { return null }
            return apps.firstOrNull { it.first.lowercase() == q } ?: apps.firstOrNull { it.first.lowercase().startsWith(q) }
                ?: apps.firstOrNull { q.length >= 4 && it.first.lowercase().contains(q) }
        }
    }

    /** नवंबर 2026: DK की AI बातचीत ([AiTalkAgent]) के लिए - Live वाली Gemini key से सिर्फ़ text (DK कंप्यूटर की key न हो तब) */
    internal fun textAsk(svc: DKAccessibilityService, ask: String): String? =
        gemini(svc, ask, null, json = true, maxTokens = 1500, models = (listOf(GeminiModels.CODER) + GeminiModels.LIGHT_CHAIN).distinct())

    /**
     * स्क्रीन देखना: मालिक ने खुद चालू किया हो (ScreenGuard) + Android 11+ + key हो.
     * v3.156: या "आपके कहे काम में बटन देखकर दबाए" (Prefs.taskLook) - यह सिर्फ़ इसी image/ask काम के बीच, आपके कहने पर.
     */
    fun canSee(svc: DKAccessibilityService): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && (Prefs.screenshot(svc) || Prefs.taskLook(svc)) && apiKey(svc) != null

    private fun apiKey(svc: DKAccessibilityService) = Prefs.liveGeminiKeys(svc).firstOrNull { it.isNotBlank() }

    /** स्क्रीन देखकर बताओ चीज़ कहाँ है -> pixel (x, y) */
    suspend fun lookAt(svc: DKAccessibilityService, goal: String): Pair<Int, Int>? {
        if (!canSee(svc)) return null
        val b64 = svc.screenshotBase64() ?: return null
        val txt = withContext(Dispatchers.IO) { gemini(svc, VisionPoint.ask(goal), b64, json = true, maxTokens = 120, models = (listOf(GeminiModels.CODER) + GeminiModels.LIGHT_CHAIN).distinct()) }
        val dm = svc.resources.displayMetrics
        val pt = VisionPoint.parse(txt, dm.widthPixels, dm.heightPixels)
        DKLog.d("ImageGen देखकर: \"${goal.take(40)}\" -> ${pt ?: "नहीं दिखा"}")
        return pt
    }

    /** आपकी बात -> पेशेवर image prompt (वही key). न बने = नियम वाला */
    suspend fun craft(svc: DKAccessibilityService, user: String, given: String): String {
        if (ImagePrompt.isDetailed(given)) return given.trim()
        val src = listOf(user, given).filter { it.isNotBlank() }.distinct().joinToString(" | ")
        val llm = withContext(Dispatchers.IO) { gemini(svc, ImagePrompt.ask(src), null, json = false, maxTokens = 400, models = GeminiModels.LIGHT_CHAIN) }
        return ImagePrompt.clean(llm, src)
    }

    /** गैलरी में start के बाद आई सबसे नई तस्वीर: (uri id, नाम). इजाज़त नहीं = null */
    private fun newest(svc: DKAccessibilityService, startMs: Long): Pair<Long, String>? = try {
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED)
        svc.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Images.Media.DATE_ADDED} >= ?", arrayOf(((startMs - 5_000) / 1000).toString()),
            "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            if (c.moveToFirst()) c.getLong(0) to c.getString(1).orEmpty() else null
        }
    } catch (_: Exception) { null }

    /** डाउनलोड हुई तस्वीर छोटी JPEG (768px) - नहीं तो null */
    private fun fileJpeg(svc: DKAccessibilityService, startMs: Long): ByteArray? {
        val id = newest(svc, startMs)?.first ?: return null
        return try {
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            svc.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= 768) sample *= 2
            val bmp = svc.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            bmp?.let { val bos = ByteArrayOutputStream(); it.compress(Bitmap.CompressFormat.JPEG, 75, bos); bos.toByteArray() }
        } catch (_: Exception) { null }
    }

    /** Gemini REST (वही key): text + (वैकल्पिक) JPEG base64 */
    private fun gemini(svc: DKAccessibilityService, ask: String, jpegB64: String?, json: Boolean, maxTokens: Int, models: List<String>): String? {
        val key = apiKey(svc) ?: return null
        val parts = JSONArray().put(JSONObject().put("text", ask))
        if (jpegB64 != null) parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", jpegB64)))
        val cfg = JSONObject().put("maxOutputTokens", maxTokens).put("temperature", if (json) 0.1 else 0.7)
        if (json) cfg.put("responseMimeType", "application/json")
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts))).put("generationConfig", cfg)
        for (m in models) {
            try {
                val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                    .header("x-goog-api-key", key).post(body.toString().toRequestBody("application/json".toMediaType())).build()
                val txt = http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty()).optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")?.let { p -> (0 until p.length()).joinToString("") { p.optJSONObject(it)?.takeIf { o -> !o.optBoolean("thought") }?.optString("text").orEmpty() } }
                }
                if (!txt.isNullOrBlank()) return txt.trim()
            } catch (e: Exception) { DKLog.d("ImageGen Gemini $m: ${e.message}") }
        }
        return null
    }

    /** तस्वीर में क्या है - 2-3 छोटे हिंदी वाक्य */
    private fun describe(svc: DKAccessibilityService, jpeg: ByteArray, prompt: String): String? {
        val ask = "This image was generated for the request: \"$prompt\". Describe what is actually visible in it in 2-3 short, simple Hindi sentences " +
            "(main subject, colours, background, any text written in it). If it is not an image result but an app screen, say what the screen shows. No preamble."
        return gemini(svc, ask, Base64.encodeToString(jpeg, Base64.NO_WRAP), json = false, maxTokens = 300, models = GeminiModels.LIGHT_CHAIN)
    }

    /** Live tool का नतीजा (Live इसे आवाज़ में बताता है) */
    /**
     * v3.156 मालिक: "ChatGPT में prompt लिख देता है पर भेज नहीं पाता" - कोई भी सवाल/लिखा हुआ (इमेज नहीं)
     * असली ChatGPT / Gemini ऐप में लिखो, भेजो, जवाब पूरा होने तक रुको, जवाब पढ़कर लौटाओ.
     */
    suspend fun ask(svc: DKAccessibilityService, args: JSONObject): String {
        val prompt = args.optString("prompt").ifBlank { args.optString("request") }.trim()
        if (prompt.isBlank()) return "ERROR: क्या लिखना / पूछना है, यह नहीं बताया. Ask the user what to type."
        val app = ImageApp.parse(args.optString("app"))
        DKLog.d("AiAsk: ${app?.hi ?: "auto"} (देखना=${canSee(svc)}) prompt: ${prompt.take(160)}")
        val r = ImageGenFlow(AccessibilityScreenDriver(svc), Host(svc)).ask(app, prompt)
        DKLog.d("AiAsk: नतीजा ok=${r.ok} app=${r.app?.hi} तरीका=[${r.how}] ${r.error}")
        if (r.screenDump.isNotBlank()) DKLog.d("AiAsk स्क्रीन: ${r.screenDump.take(1500)}")
        if (!r.ok) return "ERROR: " + r.error.ifBlank { "भेज नहीं पाया" } +
            (if (r.blocker != null) ". The user must finish that screen himself, then ask again." else "") +
            " Tell the user exactly this reason in one short Hindi line."
        if (r.appReply.isBlank()) return "SENT: the prompt was typed AND sent in ${r.app?.hi}, but DK could not read the answer. " +
            "Tell the user it is sent and the answer is on the screen in ${r.app?.hi}."
        return "SENT_AND_ANSWERED: DK typed and sent the prompt in ${r.app?.hi}. ${r.app?.hi} replied: \"${r.appReply}\". " +
            "Tell the user the gist of this reply in short Hindi (do not invent anything beyond it). The app is open on the screen now."
    }

    suspend fun run(svc: DKAccessibilityService, args: JSONObject, show: (ByteArray) -> Unit = {}): String {
        val given = args.optString("prompt").ifBlank { args.optString("request") }.trim()
        val user = args.optString("user_words").trim()
        if (given.isBlank() && user.isBlank()) return "ERROR: क्या इमेज बनानी है, यह नहीं बताया. Ask the user what picture he wants."
        val app = ImageApp.parse(args.optString("app"))
        val download = !args.has("download") || args.optBoolean("download", true)
        val start = System.currentTimeMillis()
        // v3.153: जैसा बोला वैसा नहीं - पहले पेशेवर prompt
        val prompt = try { craft(svc, user, given) } catch (e: Exception) { ImagePrompt.fallback(given.ifBlank { user }) }
        DKLog.d("ImageGen: ${app?.hi ?: "auto"} (download=$download, देखना=${canSee(svc)}) prompt: ${prompt.take(160)}")
        val r: ImageGenResult = ImageGenFlow(AccessibilityScreenDriver(svc), Host(svc)).run(app, prompt, download)
        DKLog.d("ImageGen: नतीजा ok=${r.ok} app=${r.app?.hi} downloaded=${r.downloaded} pressed=${r.pressedSave} तरीका=[${r.how}] ${r.error}")
        if (r.screenDump.isNotBlank()) DKLog.d("ImageGen स्क्रीन: ${r.screenDump.take(1500)}")
        if (!r.ok) {
            return "ERROR: " + r.error.ifBlank { "इमेज नहीं बन पाई" } +
                (if (r.appReply.isNotBlank()) ". ${r.app?.hi} ने लिखा: \"${r.appReply}\"" else "") +
                (if (r.blocker != null) ". The user must finish that screen himself, then ask again." else "") +
                " Tell the user exactly this reason in one short Hindi line."
        }
        val jpeg = withContext(Dispatchers.IO) { if (r.downloaded) fileJpeg(svc, start) else null }
            ?: (if (canSee(svc)) svc.screenshotBase64()?.let { Base64.decode(it, Base64.NO_WRAP) } else null)
        jpeg?.let { try { show(it) } catch (_: Exception) {} }
        val seen = jpeg?.let { withContext(Dispatchers.IO) { describe(svc, it, prompt) } }
        val dl = when {
            !download -> "not asked"
            r.downloaded -> "yes, saved in the phone gallery (${r.file})"
            r.pressedSave -> "the Download/Save button was pressed, but DK could not confirm it in the gallery (no photo permission?) - say it is probably saved"
            else -> "NO - the Download button was not found; the image is open in ${r.app?.hi}"
        }
        return "IMAGE_DONE: ${r.app?.hi} generated the image. Professional prompt DK wrote: \"${prompt.take(300)}\". Download: $dl. " +
            (if (seen != null) "WHAT THE IMAGE SHOWS (DK really looked at it): $seen. " else "DK could not look at the image itself" +
                (if (!canSee(svc) && !r.downloaded) " (स्क्रीन देखना बंद है - user can say \"स्क्रीन देखना चालू करो\")" else "") + " - do NOT invent what it shows. ") +
            "Tell the user in 1-2 short Hindi lines that it is ready, whether it was downloaded, and what it shows; then ask what to do next with it " +
            "(e.g. share on WhatsApp, set as wallpaper, make changes). The ${r.app?.hi} app is open on the screen now - further work inside it = phone_task."
    }
}
