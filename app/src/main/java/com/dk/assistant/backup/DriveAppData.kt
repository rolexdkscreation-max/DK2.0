package com.dk.assistant.backup

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * नवंबर 2026: user के Google Drive का छिपा appDataFolder (सिर्फ़ DK का; scope drive.appdata).
 * बड़ी याद के लिए resumable upload - अधूरा अपलोड पुरानी फ़ाइल नहीं बिगाड़ता (Drive फ़ाइल तभी बदलता है जब पूरा पहुँचे).
 */
class DriveAppData(private val token: String, private val http: OkHttpClient = CLIENT) : MemoryBackup.Store {

    class HttpError(val code: Int, msg: String) : Exception(msg)

    private fun req(url: String) = Request.Builder().url(url).header("Authorization", "Bearer $token")

    private fun run(r: Request): Pair<String, okhttp3.Headers> = http.newCall(r).execute().use { res ->
        val body = res.body?.string().orEmpty()
        if (!res.isSuccessful) throw HttpError(res.code, "Drive ${res.code}: " + body.take(200))
        body to res.headers
    }

    override fun find(name: String): MemoryBackup.Remote? {
        val q = enc("name = '${name.replace("'", "\\'")}' and trashed = false")
        val url = "$API/files?spaces=appDataFolder&pageSize=10&q=$q&fields=" + enc("files(id,name,modifiedTime,size)")
        return MemoryBackup.pickLatest(run(req(url).get().build()).first, name)
    }

    override fun upload(existingId: String?, name: String, bytes: ByteArray): String {
        // 1) अपलोड की जगह (session) माँगो
        val meta = if (existingId == null) JSONObject().put("name", name).put("parents", JSONArray().put("appDataFolder")) else JSONObject()
        val start = if (existingId == null) "$UPLOAD/files?uploadType=resumable&fields=id"
            else "$UPLOAD/files/${enc(existingId)}?uploadType=resumable&fields=id"
        val body = meta.toString().toRequestBody(JSON)
        val r0 = req(start).header("X-Upload-Content-Type", GZ).header("X-Upload-Content-Length", bytes.size.toString())
            .let { if (existingId == null) it.post(body) else it.patch(body) }.build()
        val location = run(r0).second["Location"] ?: throw HttpError(0, "Drive ने अपलोड की जगह नहीं दी")
        // 2) पूरी फ़ाइल
        val out = run(Request.Builder().url(location).put(bytes.toRequestBody(GZ.toMediaType())).build()).first
        return try { JSONObject(out).optString("id").ifBlank { existingId.orEmpty() } } catch (_: Exception) { existingId.orEmpty() }
    }

    override fun download(id: String): ByteArray =
        http.newCall(req("$API/files/${enc(id)}?alt=media").get().build()).execute().use { res ->
            if (!res.isSuccessful) throw HttpError(res.code, "Drive ${res.code}: " + res.body?.string().orEmpty().take(200))
            res.body?.bytes() ?: ByteArray(0)
        }

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val GZ = "application/gzip"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val CLIENT by lazy {
            com.dk.assistant.NetFix.builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(180, TimeUnit.SECONDS).build()
        }
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** Drive की आम गलतियाँ साफ़ हिंदी में */
        fun explain(e: Throwable): String = when {
            e is HttpError && e.code == 401 -> "Google की इजाज़त ख़त्म हो गई - दोबारा कोशिश करें"
            e is HttpError && e.code == 403 && (e.message.orEmpty().contains("accessNotConfigured") || e.message.orEmpty().contains("has not been used")) ->
                "Google Drive API चालू नहीं (ऐप बनाने वाले को Cloud Console में Drive API Enable करना है)"
            e is HttpError && e.code == 403 && e.message.orEmpty().contains("storageQuota") -> "Google Drive में जगह भरी हुई है"
            e is HttpError && e.code == 403 -> "Google Drive ने मना किया (403) - Drive की इजाज़त दोबारा दें"
            e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketTimeoutException -> "इंटरनेट नहीं मिला - नेट चालू करके दोबारा करें"
            else -> "Drive से काम नहीं हुआ: " + (e.message ?: e.javaClass.simpleName).take(160)
        }
    }
}
