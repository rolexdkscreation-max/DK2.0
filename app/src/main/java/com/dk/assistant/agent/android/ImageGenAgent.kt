package com.dk.assistant.agent.android

import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.GeminiModels
import com.dk.assistant.Prefs
import com.dk.assistant.agent.core.ImageApp
import com.dk.assistant.agent.core.ImageGenFlow
import com.dk.assistant.agent.core.ImageGenHost
import com.dk.assistant.agent.core.ImageGenResult
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

    private class Host(private val svc: DKAccessibilityService) : ImageGenHost {
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

    /** Gemini vision (वही key): तस्वीर में क्या है - 2-3 छोटे हिंदी वाक्य */
    private fun describe(svc: DKAccessibilityService, jpeg: ByteArray, prompt: String): String? {
        val key = Prefs.liveGeminiKeys(svc).firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val ask = "This image was generated for the request: \"$prompt\". Describe what is actually visible in it in 2-3 short, simple Hindi sentences " +
            "(main subject, colours, background, any text written in it). If it is not an image result but an app screen, say what the screen shows. No preamble."
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
            .put(JSONObject().put("text", ask))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 300))
        for (m in GeminiModels.LIGHT_CHAIN) {
            try {
                val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                    .header("x-goog-api-key", key).post(body.toString().toRequestBody("application/json".toMediaType())).build()
                val txt = http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty()).optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")?.let { p -> (0 until p.length()).joinToString("") { p.optJSONObject(it)?.takeIf { o -> !o.optBoolean("thought") }?.optString("text").orEmpty() } }
                }
                if (!txt.isNullOrBlank()) return txt.trim()
            } catch (e: Exception) { DKLog.d("ImageGen vision $m: ${e.message}") }
        }
        return null
    }

    /** Live tool का नतीजा (Live इसे आवाज़ में बताता है) */
    suspend fun run(svc: DKAccessibilityService, args: JSONObject, show: (ByteArray) -> Unit = {}): String {
        val prompt = args.optString("prompt").ifBlank { args.optString("request") }.trim()
        val app = ImageApp.parse(args.optString("app"))
        val download = !args.has("download") || args.optBoolean("download", true)
        val start = System.currentTimeMillis()
        DKLog.d("ImageGen: ${app?.hi ?: "auto"} - \"${prompt.take(60)}\" (download=$download)")
        val r: ImageGenResult = ImageGenFlow(AccessibilityScreenDriver(svc), Host(svc)).run(app, prompt, download)
        DKLog.d("ImageGen: नतीजा ok=${r.ok} app=${r.app?.hi} downloaded=${r.downloaded} pressed=${r.pressedSave} ${r.error}")
        if (!r.ok) {
            return "ERROR: " + r.error.ifBlank { "इमेज नहीं बन पाई" } +
                (if (r.appReply.isNotBlank()) ". ${r.app?.hi} ने लिखा: \"${r.appReply}\"" else "") +
                (if (r.blocker != null) ". The user must finish that screen himself, then ask again." else "") +
                " Tell the user exactly this reason in one short Hindi line."
        }
        val jpeg = withContext(Dispatchers.IO) { if (r.downloaded) fileJpeg(svc, start) else null }
            ?: svc.screenshotBase64()?.let { Base64.decode(it, Base64.NO_WRAP) }
        jpeg?.let { try { show(it) } catch (_: Exception) {} }
        val seen = jpeg?.let { withContext(Dispatchers.IO) { describe(svc, it, prompt) } }
        val dl = when {
            !download -> "not asked"
            r.downloaded -> "yes, saved in the phone gallery (${r.file})"
            r.pressedSave -> "the Download/Save button was pressed, but DK could not confirm it in the gallery (no photo permission?) - say it is probably saved"
            else -> "NO - the Download button was not found; the image is open in ${r.app?.hi}"
        }
        return "IMAGE_DONE: ${r.app?.hi} generated the image. Download: $dl. " +
            (if (seen != null) "WHAT THE IMAGE SHOWS (DK really looked at it): $seen. " else "DK could not look at the image itself - do NOT invent what it shows. ") +
            "Tell the user in 1-2 short Hindi lines that it is ready, whether it was downloaded, and what it shows; then ask what to do next with it " +
            "(e.g. share on WhatsApp, set as wallpaper, make changes). The ${r.app?.hi} app is open on the screen now - further work inside it = phone_task."
    }
}
