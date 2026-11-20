package com.dk.assistant.plus

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/**
 * 15 नवंबर 2026 (मालिक: "इमेज के लिए हर फ्री API की अलग key डालने का option अंदर ही रहे - डालना है तो डालें, नहीं तो नहीं").
 *
 * मुफ़्त तस्वीर बनाने वाले कई रास्ते, हर एक की अपनी (वैकल्पिक) key - Settings → DK कंप्यूटर → इमेज बनाना:
 *  - [Provider.POLLINATIONS] - नवंबर 2026 से हर तस्वीर के लिए मुफ़्त `sk_` key ज़रूरी (enter.pollinations.ai). key नहीं = छोड़ दो.
 *  - [Provider.HORDE] - AI Horde (स्वयंसेवक GPU). बिना key भी (anonymous `0000000000`), पर धीमा/छोटा. अपनी मुफ़्त key = जल्दी.
 *  - [Provider.HUGGINGFACE] - Hugging Face का मुफ़्त token (हर महीने थोड़ा मुफ़्त). token नहीं = छोड़ दो.
 *  - [Provider.CUSTOM] - कोई भी OpenAI-compatible images API (base URL + key + model). पुरानी सेटिंग ([BASE]/[KEY]/[MODEL]) वही.
 *
 * 20 नवंबर 2026 (मालिक): ये सब रास्ते अब बंद - [generate] सिर्फ़ DK सर्वर का Cloudflare FLUX ([com.dk.assistant.FluxImage]).
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

    /**
     * 20 नवंबर 2026 (मालिक: "कोई दूसरा provider और कोई fallback मत जोड़ो", "अलग API वाला option हटा दो"):
     * अब सिर्फ़ DK सर्वर का Cloudflare FLUX ([com.dk.assistant.FluxImage]) - Pollinations / Horde / HF / अपनी API अब नहीं चलतीं
     * (ऊपर के pure हिस्से सिर्फ़ पुराने tests के लिए हैं). बनी इमेज Gallery (या प्राइवेट वॉल्ट) में, जैसे पहले.
     */
    suspend fun generate(c: Context, prompt: String): Out = withContext(Dispatchers.IO) {
        if (prompt.isBlank()) return@withContext Out(null, null, "क्या बनाना है, बताइए")
        try {
            val m = com.dk.assistant.FluxImage.make(c, com.dk.assistant.FluxImage.english(c, prompt))
            save(c, m.bytes).copy(via = "Cloudflare FLUX")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { Out(null, null, com.dk.assistant.FluxImage.errorHi(e)) }
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
