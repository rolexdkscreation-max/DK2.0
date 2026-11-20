package com.dk.assistant

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import com.dk.assistant.backend.BackendApi
import com.dk.assistant.backend.BackendConfig
import com.dk.assistant.backend.BackendException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 20 नवंबर 2026 (मालिक): DKAI में इमेज बनाना - सिर्फ़ DK सर्वर (Cloudflare Worker) का `/image`,
 * जो Cloudflare Workers AI का `@cf/black-forest-labs/flux-1-schnell` चलाता है. कोई दूसरी कंपनी, कोई fallback, कोई key ऐप में नहीं.
 * सर्वर Firebase login से सिर्फ़ मालिक का Google अकाउंट मानता है.
 *
 * तीन जगह एक ही रास्ता: चैट ([chat]), DK लाइव का `generate_image` ([liveTool]), DK कंप्यूटर का `make_image` ([make]).
 * बनी इमेज: filesDir/dk_images (चैट का इतिहास इसी से - बाद में भी खुलती है). डाउनलोड बटन = Gallery (Pictures/DK AI).
 * FLUX अंग्रेज़ी में सबसे अच्छा - हिंदी/Hinglish बात पहले विस्तृत अंग्रेज़ी वर्णन में ([english]).
 */
object FluxImage {
    /** मालिक के शब्द, बिल्कुल वही */
    const val LIMIT_MSG = "आज की इमेज लिमिट पूरी हो गई, कल सुबह 5:30 बजे के बाद फिर बनेगी।"
    const val LIVE_DONE = "इमेज बन गई है, चैट खोलकर देख लीजिए।"
    const val TEXT_WARN = "ध्यान दें: इमेज बनाने वाला मॉडल (FLUX) तस्वीर के अंदर हिंदी या लंबा लिखा टेक्स्ट ठीक से नहीं लिख पाता — " +
        "अंग्रेज़ी के 1-3 छोटे शब्द ही साफ़ आते हैं। बाकी तस्वीर बना रहा हूँ।"
    const val NO_ENGLISH = "आपकी बात को अंग्रेज़ी वर्णन में नहीं बदल पाया (AI से जुड़ाव नहीं हुआ)। थोड़ी देर बाद फिर माँगिए, या अंग्रेज़ी में लिखकर माँगिए।"
    const val LIMIT_CODE = "IMAGE_LIMIT"
    const val DIR = "dk_images"
    const val TAG = "[dkimg:"
    const val PROMPT_MAX = 2048
    /** Cloudflare: 1024x1024, 4 steps ≈ 172.8 Neurons; रोज़ 10,000 => लगभग 57 (Whisper भी इसी में से) */
    const val PER_DAY = 57

    /** आज की लगभग बची इमेज (-1 = अभी पता नहीं). सर्वर के हर जवाब से ताज़ा */
    @Volatile var leftApprox = -1
        private set
    @Volatile var leftDay = ""
        private set
    @Volatile var busy = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ------------------------------------------------------------------ pure
    private val NOUN = Regex("(इमेज|तस्वीर|तसवीर|फोटो|फ़ोटो|चित्र|पिक्चर|वॉलपेपर|वालपेपर|पोस्टर|लोगो|थंबनेल|पेंटिंग|ड्रॉइंग|स्केच|" +
        "\\bimages?\\b|\\bpictures?\\b|\\bphotos?\\b|\\bpic\\b|\\bwallpapers?\\b|\\bposters?\\b|\\blogos?\\b|\\bthumbnails?\\b|\\bpainting\\b|\\bdrawing\\b|\\bsketch\\b|" +
        "\\btasveer\\b|\\btasvir\\b|\\bphoto\\b)")
    private val VERB = Regex("(बनाओ|बना दो|बना दे|बना दें|बना दीजिए|बना दीजिये|बनाइए|बनाइये|बनाना|बना के|बनाकर|बना सकते|बनाएँ|बनाएं|बनवाओ|" +
        "डिज़ाइन|डिजाइन|ड्रॉ|\\bgenerate\\b|\\bcreate\\b|\\bmake\\b|\\bdraw\\b|\\bdesign\\b|\\bbanao\\b|\\bbana do\\b|\\bbana de\\b|\\bbanaiye\\b)")
    /** ये बातें नई इमेज बनाना नहीं (फोटो खींचना, एडिट, दूसरी फ़ाइल / चार्ट, ChatGPT/Gemini ऐप वाला काम) */
    private val NOT = Regex("(वीडियो|\\bvideo\\b|स्क्रीनशॉट|screenshot|खींच|एडिट|\\bedit\\b|गैलरी|gallery|" +
        "ढूँढ|ढूंढ|\\bsearch\\b|सेल्फी|selfie|कैमरा|camera|\\bpdf\\b|पीडीएफ|excel|एक्सेल|वेबसाइट|website|" +
        "\\bppt\\b|presentation|चार्ट|\\bchart\\b|ग्राफ़|ग्राफ|\\bgraph\\b|chatgpt|चैटजीपीटी|gemini|जेमिनी|कोलाज|collage|" +
        "(इस|यह|ये|मेरी|मेरे|मेरा|इन)\\s+(फोटो|फ़ोटो|तस्वीर|इमेज|पिक्चर))")

    /** Pure: चैट में लिखी बात = नई इमेज बनाने की माँग? */
    fun wanted(t: String): Boolean {
        val s = t.lowercase().trim()
        if (s.length < 4 || s.length > 1500) return false
        return NOUN.containsMatchIn(s) && VERB.containsMatchIn(s) && !NOT.containsMatchIn(s)
    }

    fun hasHindi(t: String) = Regex("[\\u0900-\\u097F]").containsMatchIn(t)

    private val TEXT_ASK = Regex("(लिखा|लिखो|लिख दो|लिखें|लिखकर|लिखे|लिखना|टेक्स्ट|कोट|स्लोगन|कैप्शन|शीर्षक|नाम हो|नाम लिख|" +
        "\\btext\\b|\\bquote\\b|\\bslogan\\b|\\bcaption\\b|\\bwritten\\b|\\bsaying\\b|\\bheading\\b|\\btitle\\b|\\blikha\\b|\\blikho\\b)")
    private val QUOTED = Regex("[\"“”'‘’]([^\"“”'‘’]{1,300})[\"“”'‘’]")

    /** Pure: तस्वीर में हिंदी या लंबा लिखा माँगा = पहले बताने वाली बात; नहीं = null */
    fun textWarning(t: String): String? {
        if (!TEXT_ASK.containsMatchIn(t.lowercase())) return null
        val q = QUOTED.findAll(t).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()
        if (q.isEmpty()) return TEXT_WARN                // लिखा क्या है पता नहीं (अक्सर हिंदी) - पहले बता दो
        return if (q.any { hasHindi(it) || it.length > 20 || it.split(Regex("\\s+")).size > 3 }) TEXT_WARN else null
    }

    /** Pure: AI के जवाब से साफ़ अंग्रेज़ी prompt (heading, ``` , बाहर के quotes हटें) */
    fun cleanPrompt(raw: String): String {
        var s = raw.trim().replace(Regex("```[a-zA-Z]*"), "").trim()
        s = s.replace(Regex("^(english\\s+)?(image\\s+)?prompt\\s*[:：-]\\s*", RegexOption.IGNORE_CASE), "")
        if (s.length > 1 && s.first() in "\"“'" && s.last() in "\"”'") s = s.substring(1, s.length - 1)
        return s.replace(Regex("\\s+"), " ").trim().take(PROMPT_MAX)
    }

    /** Pure: चैट में इमेज वाला संदेश (सादा text - चैट की फ़ाइल/इतिहास में अपने-आप सेव रहता है) */
    fun tag(file: String, caption: String): String = "इमेज: " + caption.trim().replace(Regex("\\s+"), " ").take(200) + "\n" + TAG + file + "]"

    /** Pure: संदेश में इमेज हो तो (फ़ाइल का नाम, कैप्शन); नहीं = null */
    fun parse(text: String): Pair<String, String>? {
        val m = Regex("\\[dkimg:([A-Za-z0-9_.-]{1,80})]").find(text) ?: return null
        val cap = text.substring(0, m.range.first).trim().removePrefix("इमेज:").trim()
        return m.groupValues[1] to cap
    }

    /** Pure: छोटे अक्षरों वाली लाइन - आज की लगभग बची गिनती */
    fun leftText(n: Int): String = when {
        n < 0 -> "आज की इमेज: रोज़ लगभग $PER_DAY (सुबह 5:30 पर नई)"
        n == 0 -> "आज की इमेज लिमिट पूरी · सुबह 5:30 के बाद नई"
        else -> "आज लगभग $n इमेज और बन सकती हैं · सुबह 5:30 पर नई"
    }

    /** Pure: Cloudflare का दिन (00:00 UTC = भारत सुबह 5:30) */
    fun utcDay(now: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(now))

    /** Pure: बची गिनती आज की है? (कल की गिनती सुबह 5:30 के बाद नहीं दिखती) */
    fun leftNow(now: Long = System.currentTimeMillis()): Int = if (leftDay == utcDay(now)) leftApprox else -1

    fun isLimit(e: Throwable): Boolean = (e as? BackendException)?.reason == LIMIT_CODE

    /** हर गलती का छोटा हिंदी संदेश (लिमिट = मालिक के शब्द बिल्कुल वही) */
    fun errorHi(e: Throwable): String = when {
        isLimit(e) -> LIMIT_MSG
        e is BackendException -> e.say.ifBlank { "इमेज नहीं बन पाई — थोड़ी देर बाद फिर कोशिश करें।" }
        e is java.net.UnknownHostException -> "इंटरनेट नहीं मिल रहा। नेट चालू करके फिर माँगिए।"
        e is java.net.SocketTimeoutException -> "DK सर्वर से इमेज आने में बहुत देर लगी — थोड़ी देर बाद फिर माँगिए।"
        e.message == NO_ENGLISH -> NO_ENGLISH
        else -> "इमेज नहीं बन पाई — थोड़ी देर बाद फिर कोशिश करें।"
    }

    const val PROMPT_SYS = "You write prompts for the FLUX.1-schnell image model. Turn the user's picture request (it may be Hindi, Hinglish or English) " +
        "into ONE detailed ENGLISH image description: the main subject with every detail the user gave (people, animals, objects, colours, numbers, clothes, expressions), " +
        "setting / background, art style or photo style, composition and camera angle, lighting, colour palette, mood, quality words. 50-110 words. " +
        "Never add subjects the user did not ask for. Ignore request words like 'बनाओ / make / please'. " +
        "If the user wants words written in the picture, keep them in double quotes exactly as given. " +
        "Reply with ONLY the prompt text - no heading, no quotes around it, no explanation."

    // ------------------------------------------------------------------ काम
    private const val ON_ONCE = "dk_flux_on_once"

    /**
     * चालू? (Settings › DK कंप्यूटर › इमेज बनाना का स्विच = [FeatureFlags.IMAGE_GEN]). नई सुविधा आने पर पहली बार अपने-आप चालू
     * (पहले यह स्विच पुराने मुफ़्त रास्तों का था और शुरू में बंद रहता था) - उसके बाद आपका चुना ही चलता है.
     */
    fun on(c: Context): Boolean {
        if (!Prefs.bool(c, ON_ONCE, false)) {
            Prefs.edit(c) { putBoolean(ON_ONCE, true) }
            com.dk.assistant.plus.FeatureFlags.set(c, com.dk.assistant.plus.FeatureFlags.IMAGE_GEN, true)
        }
        return com.dk.assistant.plus.FeatureFlags.on(c, com.dk.assistant.plus.FeatureFlags.IMAGE_GEN)
    }

    const val OFF_MSG = "इमेज बनाना अभी बंद है — Settings › DK कंप्यूटर › इमेज बनाना में चालू कीजिए।"

    fun dir(c: Context): File = File(c.filesDir, DIR).apply { mkdirs() }
    fun file(c: Context, name: String): File = File(dir(c), File(name).name)

    /** हिंदी / छोटी बात -> विस्तृत अंग्रेज़ी वर्णन (पहले से लंबा अंग्रेज़ी = वैसा ही). AI न मिले: अंग्रेज़ी = वैसा ही, हिंदी = [NO_ENGLISH] */
    suspend fun english(c: Context, words: String): String {
        val w = words.trim()
        if (!hasHindi(w) && w.split(Regex("\\s+")).size >= 25) return cleanPrompt(w)
        val r = try { BrainManager(c).quickText(PROMPT_SYS, w) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Quiet.note(e); null }
        val p = r?.let { cleanPrompt(it) }.orEmpty()
        if (p.length >= 8) return p
        if (!hasHindi(w)) return cleanPrompt(w)
        throw IOException(NO_ENGLISH)
    }

    class Made(val file: File, val bytes: ByteArray, val left: Int)

    /** IO thread पर: सर्वर का /image -> filesDir/dk_images में सेव. कोई दूसरा रास्ता नहीं */
    fun make(c: Context, prompt: String): Made {
        if (!BackendConfig.ready()) throw IOException("DK सर्वर जुड़ा नहीं")
        try {
            val o = BackendApi.image(c, cleanPrompt(prompt))
            val b64 = o.optString("image")
            val bytes = if (b64.isBlank()) ByteArray(0) else Base64.decode(b64, Base64.DEFAULT)
            if (bytes.size < 200) throw IOException("इमेज नहीं बनी - खाली जवाब")
            noteLeft(o)
            val ext = com.dk.assistant.plus.ImageGen.kind(bytes).first
            val f = File(dir(c), "dk_img_${System.currentTimeMillis()}.$ext")
            f.writeBytes(bytes)
            return Made(f, bytes, leftApprox)
        } catch (e: BackendException) {
            if (isLimit(e)) setLeft(0)
            throw e
        }
    }

    private fun setLeft(n: Int) { leftApprox = n; leftDay = utcDay(System.currentTimeMillis()) }
    private fun noteLeft(o: JSONObject) { if (o.has("left_approx") && !o.isNull("left_approx")) setLeft(o.optInt("left_approx", -1)) }

    /** IO thread: आज की लगभग बची गिनती सर्वर से (Settings / चैट की छोटी लाइन) */
    fun refreshLeft(c: Context): Int = try {
        if (BackendConfig.ready()) noteLeft(BackendApi.imageStatus(c))
        leftNow()
    } catch (e: Exception) { Quiet.note(e); leftNow() }

    /** इमेज Gallery (Pictures/DK AI) में - चैट का डाउनलोड बटन */
    fun toGallery(c: Context, f: File): Boolean = try {
        val bytes = f.readBytes()
        val mime = com.dk.assistant.plus.ImageGen.kind(bytes).second
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, f.name); put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DK AI")
        }
        val uri = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: throw IOException("Gallery")
        c.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("Gallery")
        true
    } catch (e: Exception) { Quiet.note(e); false }

    /** चैट: (हिंदी/लंबा लिखा माँगा = पहले बताओ) -> अंग्रेज़ी वर्णन -> /image -> चैट में इमेज */
    suspend fun chat(c: Context, words: String) {
        try {
            textWarning(words)?.let { DKState.addQuiet(it) }
            DKState.hint("इमेज बना रहा हूँ (Cloudflare FLUX)…")
            val p = english(c, words)
            DKLog.d("इमेज (चैट): ${p.take(80)}")
            val m = withContext(Dispatchers.IO) { make(c, p) }
            DKState.addQuiet(tag(m.file.name, words))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            DKLog.d("इमेज नहीं बनी: ${e.message?.take(80)}")
            DKState.replyFailed(errorHi(e))
        } finally { DKState.set(Mode.IDLE) }
    }

    /** DK लाइव का `generate_image`: पीछे बनती है (बातचीत नहीं रुकती), तैयार = चैट में + DK बोले [LIVE_DONE] */
    fun liveTool(c: Context, args: JSONObject): String {
        val prompt = args.optString("prompt").trim()
        val words = args.optString("user_words").trim().ifBlank { prompt }
        if (prompt.isBlank()) return "ERROR: prompt is empty - ask the user in one short line what picture he wants."
        if (busy) return "BUSY: one picture is already being made. Tell the user in one short line that it will be ready in a moment."
        busy = true
        val app = c.applicationContext
        scope.launch {
            val note = try {
                val p = if (hasHindi(prompt) || prompt.length < 40) english(app, prompt) else cleanPrompt(prompt)
                DKLog.d("इमेज (लाइव): ${p.take(80)}")
                val m = make(app, p)
                DKState.addQuiet(tag(m.file.name, words))
                "SYSTEM NOTE: The picture the user asked for is ready and saved in the DK chat section. Say exactly this once, in Hindi: \"$LIVE_DONE\" " +
                    "Then continue the conversation normally."
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                DKLog.d("इमेज (लाइव) नहीं बनी: ${e.message?.take(80)}")
                "SYSTEM NOTE: The picture could not be made. Tell the user exactly this in Hindi, once: \"${errorHi(e)}\" Do not try any other app or service for it."
            } finally { busy = false }
            LiveReport.note(note)
        }
        val warn = textWarning(words)?.let { " The user wants Hindi or long text written inside the picture - first tell him in one short Hindi line that this image model cannot write Hindi or long text correctly (only 1-3 short English words come out clean)." }.orEmpty()
        return "STARTED: DK is making the picture in the background (5-20 seconds).$warn Say only ONE short line like 'ठीक है, इमेज बन रही है' and keep talking normally - do NOT wait or stay silent. " +
            "When it is ready a SYSTEM NOTE will come."
    }
}
