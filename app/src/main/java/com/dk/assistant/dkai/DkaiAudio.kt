package com.dk.assistant.dkai

import android.content.Context
import android.net.Uri
import com.dk.assistant.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 13 नवंबर 2026 (मालिक - M8 Attach Audio): ऑडियो फ़ाइल -> Gemini से शब्द-ब-शब्द text */
object DkaiAudio {
    private const val MAX = 18 * 1024 * 1024
    private val http by lazy { NetFix.builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(150, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build() }
    private val MODELS = listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.5-flash-lite")

    fun nameOf(c: Context, u: Uri): String? = try {
        c.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) { null } ?: u.lastPathSegment?.substringAfterLast('/')

    /** blocking - IO thread से बुलाएँ */
    fun transcribe(c: Context, u: Uri): String {
        val bytes = c.contentResolver.openInputStream(u)?.use { inp ->
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024); var n: Int
            while (inp.read(buf).also { n = it } > 0) { out.write(buf, 0, n); if (out.size() > MAX) throw IllegalStateException("ऑडियो 18MB से बड़ा है") }
            out.toByteArray()
        } ?: throw IllegalStateException("फ़ाइल नहीं खुली")
        var mime = c.contentResolver.getType(u) ?: "audio/mpeg"
        if (!mime.startsWith("audio/")) mime = "audio/mpeg"
        val keys = (listOf(Prefs.key(c, "gemini"), Prefs.ttsGeminiKey(c)) + Prefs.liveGeminiKeys(c)).filter { it.isNotBlank() }.distinct()
        if (keys.isEmpty()) throw IllegalStateException("Gemini key नहीं - सेटिंग्स → AI दिमाग में डालें")
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
            .put(JSONObject().put("text", "Transcribe this audio exactly, word for word, in its original language and script. Reply with only the transcript."))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", b64))))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 8192).put("temperature", 0.0)).toString()
        var last = "Gemini से जवाब नहीं मिला"
        for (k in keys) for (m in MODELS) {
            try {
                val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                    .header("x-goog-api-key", k).post(body.toRequestBody("application/json".toMediaType())).build()
                http.newCall(req).execute().use { r ->
                    val s = r.body?.string().orEmpty()
                    if (!r.isSuccessful) { last = "Gemini ${r.code}"; if (r.code == 400 || r.code == 413) throw IllegalStateException(last); return@use }
                    val p = JSONObject(s).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return@use
                    val sb = StringBuilder()
                    for (i in 0 until p.length()) { val x = p.optJSONObject(i) ?: continue; if (!x.optBoolean("thought")) sb.append(x.optString("text")) }
                    if (sb.isNotBlank()) return sb.toString().trim()
                }
            } catch (e: IllegalStateException) { throw e } catch (e: Exception) { last = e.message ?: last }
        }
        throw IllegalStateException(last)
    }
}
