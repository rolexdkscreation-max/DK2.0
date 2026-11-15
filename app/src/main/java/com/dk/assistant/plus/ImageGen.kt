package com.dk.assistant.plus

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 15 नवंबर 2026 (मालिक, Optional Settings → Image Generation): बिना key के मुफ़्त Pollinations (default),
 * या अपनी OpenAI-compatible images API (base URL + key) - Settings में डालें तो वही.
 * तस्वीर: Gallery (Pictures/DK AI) में, या "प्राइवेट वॉल्ट" चालू हो तो ऐप के अंदर (filesDir/vault) - गैलरी में नहीं.
 */
object ImageGen {
    const val BASE = "plus_image_base"
    const val KEY = "plus_image_key"
    const val MODEL = "plus_image_model"

    /** Pure: Pollinations का URL */
    fun pollinationsUrl(prompt: String, w: Int = 1024, h: Int = 1024, seed: Long? = null): String {
        val p = URLEncoder.encode(prompt.trim().take(800), "UTF-8").replace("+", "%20")
        return "https://image.pollinations.ai/prompt/$p?width=${w.coerceIn(256, 2048)}&height=${h.coerceIn(256, 2048)}&nologo=true" +
            (seed?.let { "&seed=$it" } ?: "")
    }

    data class Out(val uri: Uri?, val file: File?, val error: String?)

    suspend fun generate(c: Context, prompt: String): Out = withContext(Dispatchers.IO) {
        if (prompt.isBlank()) return@withContext Out(null, null, "क्या बनाना है, बताइए")
        val http = com.dk.assistant.NetFix.builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
        val base = com.dk.assistant.Prefs.str(c, BASE, "").trim().trimEnd('/')
        val key = com.dk.assistant.Prefs.str(c, KEY, "").trim()
        val bytes = try {
            if (base.startsWith("https://") && key.isNotBlank()) {
                val body = JSONObject().put("prompt", prompt).put("n", 1).put("size", "1024x1024").put("response_format", "b64_json")
                    .apply { com.dk.assistant.Prefs.str(c, MODEL, "").takeIf { it.isNotBlank() }?.let { put("model", it) } }
                http.newCall(Request.Builder().url("$base/images/generations").header("Authorization", "Bearer $key")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                    val t = r.body?.string().orEmpty()
                    if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                    android.util.Base64.decode(JSONObject(t).getJSONArray("data").getJSONObject(0).getString("b64_json"), 0)
                }
            } else {
                http.newCall(Request.Builder().url(pollinationsUrl(prompt)).build()).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                    r.body?.bytes() ?: throw java.io.IOException("खाली")
                }
            }
        } catch (e: Exception) { return@withContext Out(null, null, "तस्वीर नहीं बनी: ${e.message}") }
        save(c, bytes)
    }

    private fun save(c: Context, bytes: ByteArray): Out {
        val name = "DK_AI_${System.currentTimeMillis()}.jpg"
        if (FeatureFlags.on(c, FeatureFlags.FACE_VAULT)) {
            val f = File(File(c.filesDir, "vault").apply { mkdirs() }, name); f.writeBytes(bytes)
            return Out(null, f, null)
        }
        return try {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DK AI")
            }
            val uri = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: throw java.io.IOException("Gallery")
            c.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            Out(uri, null, null)
        } catch (e: Exception) {
            val f = File(File(c.filesDir, "images").apply { mkdirs() }, name); f.writeBytes(bytes)
            Out(null, f, null)
        }
    }
}
