package com.dk.assistant.plus

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 15 नवंबर 2026 (मालिक: "इमेज के लिए हर फ्री API की अलग key डालने का option अंदर ही रहे - डालना है तो डालें, नहीं तो नहीं").
 *
 * मुफ़्त तस्वीर बनाने वाले कई रास्ते, हर एक की अपनी (वैकल्पिक) key - Settings → DK कंप्यूटर → इमेज बनाना:
 *  - [Provider.POLLINATIONS] - नवंबर 2026 से हर तस्वीर के लिए मुफ़्त `sk_` key ज़रूरी (enter.pollinations.ai). key नहीं = छोड़ दो.
 *  - [Provider.HORDE] - AI Horde (स्वयंसेवक GPU). बिना key भी (anonymous `0000000000`), पर धीमा/छोटा. अपनी मुफ़्त key = जल्दी.
 *  - [Provider.HUGGINGFACE] - Hugging Face का मुफ़्त token (हर महीने थोड़ा मुफ़्त). token नहीं = छोड़ दो.
 *  - [Provider.CUSTOM] - कोई भी OpenAI-compatible images API (base URL + key + model). पुरानी सेटिंग ([BASE]/[KEY]/[MODEL]) वही.
 *
 * चुना रास्ता पहले, न चले तो बाकी जिनकी key है (Horde हमेशा आख़िरी सहारा). कोई भारी फ़ाइल ऐप में नहीं.
 * तस्वीर: Gallery (Pictures/DK AI) में, या "प्राइवेट वॉल्ट" चालू हो तो ऐप के अंदर (filesDir/vault).
 */
object ImageGen {
    // पुरानी keys (PR #341) - नाम नहीं बदले
    const val BASE = "plus_image_base"
    const val KEY = "plus_image_key"
    const val MODEL = "plus_image_model"

    /** "auto" या [Provider.id] */
    const val PROVIDER = "plus_image_provider"
    const val AUTO = "auto"
    const val HF_MODEL = "plus_image_hf_model"
    const val HF_DEFAULT_MODEL = "black-forest-labs/FLUX.1-schnell"
    const val HORDE_ANON = "0000000000"

    enum class Provider(val id: String, val title: String, val keyPref: String, val keyUrl: String, val keyNeeded: Boolean) {
        POLLINATIONS("pollinations", "Pollinations (FLUX)", "plus_image_key_pollinations", "https://enter.pollinations.ai", true),
        HORDE("horde", "AI Horde (बिना key भी)", "plus_image_key_horde", "https://aihorde.net/register", false),
        HUGGINGFACE("hf", "Hugging Face", "plus_image_key_hf", "https://huggingface.co/settings/tokens", true),
        CUSTOM("custom", "अपनी OpenAI-compatible API", KEY, "", true);

        companion object { fun of(id: String?): Provider? = values().firstOrNull { it.id == id } }
    }

    /** Pure: इस क्रम में आज़माओ. [chosen] = AUTO या provider id; [usable] = key/पता भरा है? */
    fun order(chosen: String?, usable: (Provider) -> Boolean): List<Provider> {
        val first = Provider.of(chosen)
        val rest = listOf(Provider.CUSTOM, Provider.POLLINATIONS, Provider.HUGGINGFACE, Provider.HORDE)
        return (listOfNotNull(first) + rest).distinct().filter { it == Provider.HORDE || usable(it) }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Pure: Pollinations का URL (नया gen.pollinations.ai - key ज़रूरी) */
    fun pollinationsUrl(prompt: String, w: Int = 1024, h: Int = 1024, seed: Long? = null, key: String = ""): String =
        "https://gen.pollinations.ai/image/${enc(prompt.trim().take(800))}?model=flux&width=${w.coerceIn(256, 2048)}" +
            "&height=${h.coerceIn(256, 2048)}&nologo=true" + (seed?.let { "&seed=$it" } ?: "") +
            (if (key.isNotBlank()) "&key=${enc(key.trim())}" else "")

    /** Pure: AI Horde async काम का body */
    fun hordeBody(prompt: String, w: Int = 512, h: Int = 512): JSONObject = JSONObject()
        .put("prompt", prompt.trim().take(1000))
        .put("params", JSONObject().put("width", w / 64 * 64).put("height", h / 64 * 64).put("steps", 20).put("n", 1))
        .put("nsfw", false).put("censor_nsfw", true).put("r2", true)

    fun hordeId(raw: String): String? = try { JSONObject(raw).optString("id").takeIf { it.isNotBlank() } } catch (_: Exception) { null }
    fun hordeDone(raw: String): Boolean = try { JSONObject(raw).optBoolean("done") } catch (_: Exception) { false }
    fun hordeImg(raw: String): String? = try {
        JSONObject(raw).optJSONArray("generations")?.optJSONObject(0)?.optString("img")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    /** Pure: Hugging Face Inference का पता */
    fun hfUrl(model: String): String = "https://router.huggingface.co/hf-inference/models/" + model.trim().ifBlank { HF_DEFAULT_MODEL }

    /** Pure: OpenAI-compatible जवाब -> b64 (या url) */
    fun openAiImage(raw: String): Pair<String?, String?> = try {
        val d: JSONArray = JSONObject(raw).getJSONArray("data")
        val o = d.getJSONObject(0)
        o.optString("b64_json").takeIf { it.isNotBlank() } to o.optString("url").takeIf { it.isNotBlank() }
    } catch (_: Exception) { null to null }

    /** Pure: गलती छोटे हिंदी में (key कभी नहीं दिखती) */
    fun why(code: Int): String = when (code) {
        401, 403 -> "key गलत या नहीं डाली"
        402 -> "मुफ़्त सीमा खत्म"
        429 -> "अभी बहुत ज़्यादा माँग - थोड़ी देर बाद"
        in 500..599 -> "सर्वर अभी ठीक नहीं"
        else -> "HTTP $code"
    }

    data class Out(val uri: Uri?, val file: File?, val error: String?, val via: String = "")

    fun key(c: Context, p: Provider): String = com.dk.assistant.Prefs.str(c, p.keyPref, "").trim()

    fun usable(c: Context, p: Provider): Boolean = when (p) {
        Provider.HORDE -> true
        Provider.CUSTOM -> com.dk.assistant.Prefs.str(c, BASE, "").trim().startsWith("https://") && key(c, p).isNotBlank()
        else -> key(c, p).isNotBlank()
    }

    suspend fun generate(c: Context, prompt: String): Out = withContext(Dispatchers.IO) {
        if (prompt.isBlank()) return@withContext Out(null, null, "क्या बनाना है, बताइए")
        val http = com.dk.assistant.NetFix.builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
        val fails = mutableListOf<String>()
        for (p in order(com.dk.assistant.Prefs.str(c, PROVIDER, AUTO), { usable(c, it) })) {
            try {
                val bytes = when (p) {
                    Provider.CUSTOM -> custom(c, http, prompt)
                    Provider.POLLINATIONS -> get(http, Request.Builder().url(pollinationsUrl(prompt, key = key(c, p))).build())
                    Provider.HUGGINGFACE -> get(http, Request.Builder().url(hfUrl(com.dk.assistant.Prefs.str(c, HF_MODEL, "")))
                        .header("Authorization", "Bearer ${key(c, p)}").header("Accept", "image/png")
                        .post(JSONObject().put("inputs", prompt.take(1000)).toString().toRequestBody(JSON)).build())
                    Provider.HORDE -> horde(http, prompt, key(c, p).ifBlank { HORDE_ANON })
                }
                if (bytes.size < 200) throw IOException("खाली तस्वीर")
                return@withContext save(c, bytes).copy(via = p.title)
            } catch (e: Exception) {
                fails += "${p.title}: ${e.message ?: "नहीं चला"}"
            }
        }
        Out(null, null, "तस्वीर नहीं बनी - " + fails.joinToString("; "))
    }

    private val JSON = "application/json".toMediaType()

    private fun get(http: OkHttpClient, req: Request): ByteArray = http.newCall(req).execute().use { r ->
        if (!r.isSuccessful) throw IOException(why(r.code))
        r.body?.bytes() ?: throw IOException("खाली")
    }

    private fun custom(c: Context, http: OkHttpClient, prompt: String): ByteArray {
        val base = com.dk.assistant.Prefs.str(c, BASE, "").trim().trimEnd('/')
        val body = JSONObject().put("prompt", prompt).put("n", 1).put("size", "1024x1024").put("response_format", "b64_json")
            .apply { com.dk.assistant.Prefs.str(c, MODEL, "").takeIf { it.isNotBlank() }?.let { put("model", it) } }
        val raw = http.newCall(Request.Builder().url("$base/images/generations").header("Authorization", "Bearer ${key(c, Provider.CUSTOM)}")
            .post(body.toString().toRequestBody(JSON)).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException(why(r.code)); r.body?.string().orEmpty()
        }
        val (b64, url) = openAiImage(raw)
        if (b64 != null) return android.util.Base64.decode(b64, 0)
        if (url != null) return get(http, Request.Builder().url(url).build())
        throw IOException("जवाब में तस्वीर नहीं")
    }

    private suspend fun horde(http: OkHttpClient, prompt: String, key: String): ByteArray {
        val api = "https://aihorde.net/api/v2"
        fun req(path: String) = Request.Builder().url("$api$path").header("apikey", key).header("Client-Agent", "DKAI:1.0:github.com/rolexdkscreation-max")
        val raw = http.newCall(req("/generate/async").post(hordeBody(prompt).toString().toRequestBody(JSON)).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException(why(r.code)); r.body?.string().orEmpty()
        }
        val id = hordeId(raw) ?: throw IOException("काम नहीं मिला")
        val until = System.currentTimeMillis() + 150_000
        while (System.currentTimeMillis() < until) {
            delay(4_000)
            val chk = http.newCall(req("/generate/check/$id").build()).execute().use { it.body?.string().orEmpty() }
            if (!hordeDone(chk)) continue
            val st = http.newCall(req("/generate/status/$id").build()).execute().use { it.body?.string().orEmpty() }
            val img = hordeImg(st) ?: throw IOException("तस्वीर नहीं मिली")
            return if (img.startsWith("http")) get(http, Request.Builder().url(img).build()) else android.util.Base64.decode(img, 0)
        }
        throw IOException("बहुत देर (कतार लंबी) - अपनी मुफ़्त Horde key डालें")
    }

    /** Pure: बाइट से असली प्रकार (PNG / WEBP / JPEG) -> (ext, mime) */
    fun kind(b: ByteArray): Pair<String, String> = when {
        b.size > 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "png" to "image/png"
        b.size > 12 && b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte() -> "webp" to "image/webp"
        else -> "jpg" to "image/jpeg"
    }

    private fun save(c: Context, bytes: ByteArray): Out {
        val (ext, mime) = kind(bytes)
        val name = "DK_AI_${System.currentTimeMillis()}.$ext"
        if (FeatureFlags.on(c, FeatureFlags.FACE_VAULT)) {
            val f = File(File(c.filesDir, "vault").apply { mkdirs() }, name); f.writeBytes(bytes)
            return Out(null, f, null)
        }
        return try {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DK AI")
            }
            val uri = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: throw IOException("Gallery")
            c.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            Out(uri, null, null)
        } catch (e: Exception) {
            val f = File(File(c.filesDir, "images").apply { mkdirs() }, name); f.writeBytes(bytes)
            Out(null, f, null)
        }
    }
}
