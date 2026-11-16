package com.dk.assistant.agent.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import com.dk.assistant.DKLog
import com.dk.assistant.GeminiModels
import com.dk.assistant.NetFix
import com.dk.assistant.OneKeyLanes
import com.dk.assistant.Prefs
import com.dk.assistant.agent.social.Platform
import com.dk.assistant.agent.social.SharedItem
import com.dk.assistant.agent.social.SocialAction
import com.dk.assistant.agent.social.SocialSmart
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 16 नवंबर 2026 (मालिक: "वीडियो देखेगा, किस चीज़ पर है, उसके हिसाब से कैप्शन और हैशटैग"):
 * फोटो (1 तस्वीर) या वीडियो (शुरू से आख़िर तक 4 frame) Gemini को दिखाकर कैप्शन + हैशटैग (+ माँगा हो तो गाना).
 * Gemini key वही जो DK में पहले से है. न चले = null (तब DK साफ़ बताता है कि कैप्शन नहीं बना). IO thread पर बुलाओ.
 */
object SocialCaption {
    private val http by lazy { NetFix.builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS).build() }

    data class Seen(val out: SocialSmart.Out?, val durationMs: Long, val why: String = "")

    fun make(c: Context, m: SharedItem, p: Platform, a: SocialAction, ask: SocialSmart.Ask): Seen {
        val video = m.mime.startsWith("video/")
        val (frames, dur) = if (video) videoFrames(c, Uri.parse(m.uri)) else (listOfNotNull(photo(c, Uri.parse(m.uri))) to 0L)
        if (frames.isEmpty()) return Seen(null, dur, "फोटो/वीडियो खुल नहीं पाया")
        val keys = Prefs.liveGeminiKeys(c)
        if (keys.isEmpty()) return Seen(null, dur, "Gemini key नहीं है")
        val parts = JSONArray().put(JSONObject().put("text", SocialSmart.prompt(p, a, video, frames.size, ask)))
        frames.forEach { parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", it))) }
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 900).put("temperature", 0.6).put("responseMimeType", "application/json")).toString()
        val models = OneKeyLanes.order(OneKeyLanes.Lane.PHONE, (listOf(GeminiModels.CODER) + GeminiModels.LIGHT_CHAIN).distinct()).take(4)
        for (key in keys.take(2)) for (model in models) {
            try {
                val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    .header("x-goog-api-key", key).post(body.toRequestBody("application/json".toMediaType())).build()
                val txt = http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty()).optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")?.let { ps -> (0 until ps.length()).joinToString("") { ps.optJSONObject(it)?.takeIf { o -> !o.optBoolean("thought") }?.optString("text").orEmpty() } }
                }
                SocialSmart.parse(txt, p, ask)?.let { DKLog.d("सोशल कैप्शन: $model से बना (${it.hashtags.size} हैशटैग)"); return Seen(it, dur) }
            } catch (e: Exception) { DKLog.d("सोशल कैप्शन: $model ${e.javaClass.simpleName}") }
        }
        return Seen(null, dur, "Gemini ने जवाब नहीं दिया")
    }

    private fun jpeg(b: Bitmap, max: Int = 768): String {
        val s = minOf(1f, max.toFloat() / maxOf(b.width, b.height))
        val sc = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true) else b
        val bo = ByteArrayOutputStream(); sc.compress(Bitmap.CompressFormat.JPEG, 82, bo)
        if (sc !== b) sc.recycle()
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
    }

    private fun photo(c: Context, u: Uri): String? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1; while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 768) sample *= 2
        c.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?.let { b -> jpeg(b).also { b.recycle() } }
    } catch (_: Throwable) { null }

    /** 10% / 35% / 60% / 85% पर frame, और लंबाई (ms) */
    private fun videoFrames(c: Context, u: Uri): Pair<List<String>, Long> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(c, u)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val list = listOf(0.10, 0.35, 0.60, 0.85).mapNotNull { f ->
                try { r.getFrameAtTime((dur * f * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { b -> jpeg(b, 640).also { b.recycle() } } } catch (_: Throwable) { null }
            }
            list to dur
        } catch (_: Throwable) { emptyList<String>() to 0L } finally { try { r.release() } catch (_: Throwable) {} }
    }
}
