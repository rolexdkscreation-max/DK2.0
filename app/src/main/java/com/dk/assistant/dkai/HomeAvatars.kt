package com.dk.assistant.dkai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * 23 नवंबर 2026 (मालिक): होम के बीच में ORB या 3D अवतार - Settings › दिखावट › "DKAI का orb" से बदले.
 *
 *  - अवतार APK में नहीं आता: इंटरनेट से (GitHub repo का `avatars/` फ़ोल्डर) एक बार डाउनलोड, फिर फ़ोन में (filesDir/avatars) रहता है.
 *  - फ़ाइल न मिले / डाउनलोड न हो = कोई क्रैश नहीं, होम पर ORB.
 *  - सूची `avatars/index.json` से - बाद में 4-5 और लड़के-लड़कियाँ बस वहाँ एक पंक्ति जोड़कर, ऐप बदले बिना.
 *  - चुनाव pref [PREF] में ("orb" या "avatar:<id>") - ऐप दोबारा खोलने पर भी वही.
 */
object HomeAvatars {
    /**
     * [res] = ऐप के अंदर (res/drawable-nodpi) वाली तस्वीर का नाम - null = इंटरनेट वाली.
     * [orb] = ऑर्ब वाला आर्टवर्क (आवाज़ के साथ धड़के). [badge] = बीच में लिखा छोटा नाम (जैसे तितली ऑर्ब पर "DK").
     * [subject] = तस्वीर में कैरेक्टर / ऑर्ब का हिस्सा (0..1: बायाँ, ऊपर, दायाँ, नीचे) - यह कभी नहीं कटता.
     */
    data class Avatar(val id: String, val name: String, val gender: String, val file: String,
                      val res: String? = null, val orb: Boolean = false, val badge: String? = null,
                      val subject: List<Float> = FULL_SUBJECT,
                      val badgeY: Float = BADGE_Y, val hqSubject: List<Float>? = null, val hqBadgeY: Float? = null,
                      /** 26 नवंबर 2026: वैकल्पिक rig (आँखें) - [CharacterRig.fits] वाली तस्वीर पर ही पलक; न हो = साँस + रोशनी ही */
                      val rig: CharacterRig? = null,
                      /**
                       * 27 नवंबर 2026 (मालिक: "पूरी Home स्क्रीन पर फुल-स्क्रीन फोटो, चेहरा कभी न कटे"): [focus] = (x, y) 0..1 -
                       * स्क्रीन की ~36% ऊँचाई पर; [face] = चेहरे का आयत (बायाँ, ऊपर, दायाँ, नीचे) 0..1 - कभी न कटे / न ढके.
                       * [hqFocus]/[hqFace] = HQ तस्वीर वाले मान ([hqLook]). [land] = चौड़ी स्क्रीन (टैबलेट landscape) की अलग
                       * तस्वीर (वैकल्पिक) + उसके [landFocus]/[landFace]. न हों = [CharacterFrame] के डिफ़ॉल्ट.
                       */
                      val focus: List<Float>? = null, val face: List<Float>? = null,
                      val hqFocus: List<Float>? = null, val hqFace: List<Float>? = null,
                      val land: String? = null, val landFocus: List<Float>? = null, val landFace: List<Float>? = null)

    const val PREF = "home_center"
    const val ORB = "orb"
    private const val AV = "avatar:"
    const val BASE = "https://raw.githubusercontent.com/rolexdkscreation-max/DK2.0/main/avatars/"
    const val INDEX = "index.json"
    /** इससे बड़ी फ़ाइल नहीं (गलत / नुकसान वाली फ़ाइल से बचाव) */
    const val MAX_BYTES = 6 * 1024 * 1024

    /**
     * 24 नवंबर 2026 (मालिक: "ORB और कैरेक्टर धुंधले - हाई क्वालिटी, इंटरनेट से डाउनलोड, APK में नहीं"):
     * असली वजह - ऐप के अंदर वाले आर्टवर्क कोलाज से कटे सिर्फ़ 90-190px के टुकड़े हैं; बड़ा करने पर धुंधले.
     * अब हर आर्टवर्क की 2048x2048 (2-5 MB) वाली तस्वीर repo के `avatars/<id>_hq.png` में - चुनते ही / होम पर
     * एक बार डाउनलोड, फिर फ़ोन में. जब तक न आए (या इंटरनेट न हो) तब तक वही छोटी तस्वीर - कभी खाली नहीं.
     */
    const val HQ_SUFFIX = "_hq.png"
    const val HQ_MAX_BYTES = 12 * 1024 * 1024

    val FULL_SUBJECT: List<Float> = listOf(0f, 0f, 1f, 1f)
    /** HQ (2048x2048) कैरेक्टर: बीच में, नीचे से कटे - चेहरा / बाल / हाथ यहीं */
    val HQ_CHAR: List<Float> = listOf(0.08f, 0.03f, 0.92f, 1f)
    /** तितली ऑर्ब में गोले का बीच (ऊपर से, तस्वीर की ऊँचाई का हिस्सा) - "DK" यहीं (होम और Settings प्रीव्यू दोनों) */
    const val BADGE_Y = 0.47f

    /**
     * 23 नवंबर 2026 (मालिक का कोलाज): ऐप के अंदर आने वाले आर्टवर्क - कोलाज से बिना resize / compress काटे, res/drawable-nodpi में.
     * पहले 8 कैरेक्टर, फिर 3 ऑर्ब. डाउनलोड की ज़रूरत नहीं, इंटरनेट के बिना भी.
     */
    val BUNDLED = listOf(
        Avatar("dk_art_hearts", "दिल वाली", "girl", "dk_art_hearts.png", "dk_art_hearts", subject = listOf(0.22f, 0f, 0.82f, 1f), hqSubject = HQ_CHAR, hqFocus = listOf(0.47f, 0.40f), hqFace = listOf(0.33f, 0.22f, 0.62f, 0.58f),
            rig = CharacterRig.square(0.373f, 0.353f, 0.066f, 0.044f, 0.514f, 0.371f, 0.066f, 0.044f)),
        Avatar("dk_art_headphone_orb", "हेडफ़ोन", "girl", "dk_art_headphone_orb.png", "dk_art_headphone_orb", subject = listOf(0.14f, 0f, 0.86f, 1f), hqSubject = HQ_CHAR, hqFocus = listOf(0.50f, 0.46f), hqFace = listOf(0.32f, 0.33f, 0.70f, 0.62f)),
        Avatar("dk_art_glasses_bun", "चश्मा", "girl", "dk_art_glasses_bun.png", "dk_art_glasses_bun", subject = listOf(0.2f, 0.05f, 0.85f, 1f), hqSubject = HQ_CHAR, hqFocus = listOf(0.49f, 0.50f), hqFace = listOf(0.32f, 0.33f, 0.66f, 0.66f),
            rig = CharacterRig.square(0.409f, 0.481f, 0.085f, 0.053f, 0.563f, 0.503f, 0.085f, 0.053f)),
        Avatar("dk_art_cat_ears", "बिल्ली-कान", "girl", "dk_art_cat_ears.png", "dk_art_cat_ears", subject = listOf(0.15f, 0f, 0.85f, 1f), hqSubject = HQ_CHAR, hqFocus = listOf(0.48f, 0.40f), hqFace = listOf(0.32f, 0.27f, 0.64f, 0.55f),
            rig = CharacterRig.square(0.533f, 0.355f, 0.075f, 0.040f)),
        Avatar("dk_art_glasses_dark", "चश्मा (काली)", "girl", "dk_art_glasses_dark.png", "dk_art_glasses_dark", subject = listOf(0.12f, 0f, 0.92f, 1f), hqSubject = HQ_CHAR, hqFocus = listOf(0.42f, 0.43f), hqFace = listOf(0.23f, 0.27f, 0.60f, 0.62f),
            rig = CharacterRig.square(0.323f, 0.382f, 0.066f, 0.040f, 0.442f, 0.422f, 0.066f, 0.040f)),
        Avatar("dk_art_butterfly_headphones", "तितली + हेडफ़ोन", "girl", "dk_art_butterfly_headphones.png", "dk_art_butterfly_headphones", subject = listOf(0.2f, 0f, 0.86f, 1f), hqSubject = HQ_CHAR, hqFocus = listOf(0.56f, 0.36f), hqFace = listOf(0.36f, 0.24f, 0.70f, 0.48f)),
        Avatar("dk_art_sleepy", "नींद वाली", "girl", "dk_art_sleepy.png", "dk_art_sleepy", subject = listOf(0.2f, 0f, 0.92f, 1f), hqSubject = HQ_CHAR, hqFocus = listOf(0.53f, 0.47f), hqFace = listOf(0.38f, 0.32f, 0.72f, 0.64f)),
        Avatar("dk_art_butterfly_girl", "तितली वाली", "girl", "dk_art_butterfly_girl.png", "dk_art_butterfly_girl", subject = listOf(0.15f, 0f, 0.85f, 1f), hqSubject = HQ_CHAR, hqFocus = listOf(0.49f, 0.32f), hqFace = listOf(0.36f, 0.18f, 0.62f, 0.47f),
            rig = CharacterRig.square(0.427f, 0.317f, 0.067f, 0.040f, 0.564f, 0.301f, 0.067f, 0.040f)),
        Avatar("orb_art_butterfly", "तितली ऑर्ब (DK)", "orb", "orb_art_butterfly.png", "orb_art_butterfly", orb = true, badge = "DK",
            subject = listOf(0.02f, 0f, 0.7f, 1f), hqSubject = listOf(0.15f, 0.1f, 0.85f, 0.9f), hqBadgeY = 0.56f),
        Avatar("orb_art_wave_rings", "वेव ऑर्ब (छल्ले)", "orb", "orb_art_wave_rings.png", "orb_art_wave_rings", orb = true,
            subject = listOf(0.08f, 0.05f, 0.94f, 1f), hqSubject = listOf(0.12f, 0.12f, 0.88f, 0.9f)),
        Avatar("orb_art_dk_wave", "DK वेव ऑर्ब", "orb", "orb_art_dk_wave.png", "orb_art_dk_wave", orb = true,
            subject = listOf(0.12f, 0.18f, 0.88f, 0.86f), hqSubject = listOf(0.18f, 0.18f, 0.82f, 0.82f)),
    )

    /** पहले से चुना (कुछ न चुना हो तो): DK के लोगो और साउंड-वेव वाला बड़ा ऑर्ब */
    const val DEFAULT_ID = "orb_art_dk_wave"

    /** मालिक ने हटवाया (पहले वाला अवतार) - इंटरनेट की सूची में हो तब भी नहीं दिखता; चुना हुआ था = अब डिफ़ॉल्ट */
    val REMOVED = setOf("dk_girl")

    /** नेट न हो तब भी Settings में ये सब (ऐप के अंदर वाले) */
    val DEFAULTS = BUNDLED

    // ------------------------------------------------------------------ pure (JVM test)
    /** फ़ाइल का नाम सुरक्षित: सिर्फ़ a-z 0-9 _ - और .png (कोई / या .. नहीं) */
    fun safeFile(n: String?): Boolean = n != null && Regex("^[a-z0-9_\\-]{1,60}\\.png$").matches(n)

    fun safeId(n: String?): Boolean = n != null && Regex("^[a-z0-9_\\-]{1,40}$").matches(n)

    /** index.json -> सूची (गलत पंक्ति छोड़ो, एक id एक बार) */
    fun parse(json: String?): List<Avatar> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = org.json.JSONObject(json).optJSONArray("avatars") ?: return emptyList()
            val out = LinkedHashMap<String, Avatar>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id"); val f = o.optString("file")
                if (!safeId(id) || !safeFile(f) || out.containsKey(id)) continue
                // 27 नवंबर 2026: "focus" [x, y], "face" [l, t, r, b], "land" {file, focus, face} - गलत / न हो = डिफ़ॉल्ट
                val land = o.optJSONObject("land")
                val lf = land?.optString("file")?.takeIf { safeFile(it) }
                out[id] = Avatar(id, o.optString("name").ifBlank { id }, o.optString("gender").ifBlank { "girl" }, f,
                    rig = CharacterRig.parse(o.optJSONObject("rig")),
                    focus = CharacterFrame.okFocus(floats(o.optJSONArray("focus"))), face = CharacterFrame.okFace(floats(o.optJSONArray("face"))),
                    land = lf, landFocus = if (lf == null) null else CharacterFrame.okFocus(floats(land?.optJSONArray("focus"))),
                    landFace = if (lf == null) null else CharacterFrame.okFace(floats(land?.optJSONArray("face"))))
            }
            out.values.toList()
        } catch (_: Exception) { emptyList() }
    }

    private fun floats(a: org.json.JSONArray?): List<Float>? {
        if (a == null) return null
        val out = ArrayList<Float>()
        for (i in 0 until a.length()) { val v = a.optDouble(i, Double.NaN); if (v.isNaN()) return null; out.add(v.toFloat()) }
        return out
    }

    /**
     * 27 नवंबर 2026 (pure): होम की पूरी स्क्रीन वाली तस्वीर के लिए (तस्वीर का नाम, focus, face). [wide] = चौड़ी स्क्रीन
     * (टैबलेट landscape) और अलग [Avatar.land] तस्वीर दी हो = वही; नहीं = वही एक तस्वीर, वही focus नियम.
     */
    fun frameOf(a: Avatar, wide: Boolean): Triple<String, List<Float>, List<Float>> =
        if (wide && a.land != null) Triple(a.land, CharacterFrame.focusOf(a.landFocus, a.landFace), CharacterFrame.faceOf(a.landFocus, a.landFace))
        else Triple(a.file, CharacterFrame.focusOf(a.focus, a.face), CharacterFrame.faceOf(a.focus, a.face))

    /** ऐप के अंदर वाले पहले, फिर डाउनलोड वाले नए (एक id एक बार; हटाए गए कभी नहीं) */
    fun merge(net: List<Avatar>): List<Avatar> = (DEFAULTS + net).filter { it.id !in REMOVED }.distinctBy { it.id }

    /** pref का मान -> दिखने वाला id: कुछ न चुना = डिफ़ॉल्ट ऑर्ब; हटाया गया अवतार = डिफ़ॉल्ट; ORB = null */
    fun shownId(pref: String?): String? = when {
        pref == null -> DEFAULT_ID
        else -> idOf(pref)?.let { if (it in REMOVED) DEFAULT_ID else it }
    }

    /** pref का मान -> अवतार id (ORB = null) */
    fun idOf(pref: String?): String? = pref?.takeIf { it.startsWith(AV) }?.removePrefix(AV)?.takeIf { safeId(it) }

    fun prefFor(id: String?): String = if (id == null) ORB else AV + id

    /** pure: ऐप के अंदर वाले आर्टवर्क की HQ फ़ाइल का नाम (repo `avatars/` और फ़ोन filesDir/avatars) - इंटरनेट वाले का null */
    fun hqName(a: Avatar): String? = if (a.res != null && safeId(a.id)) a.id + HQ_SUFFIX else null

    /** pure: HQ तस्वीर आ गई = उसी तस्वीर के हिसाब से न-कटने वाला हिस्सा और "DK" की जगह */
    fun hqLook(a: Avatar): Avatar = a.copy(subject = a.hqSubject ?: a.subject, badgeY = a.hqBadgeY ?: a.badgeY,
        focus = a.hqFocus ?: a.focus, face = a.hqFace ?: a.face)

    /** PNG फ़ाइल की शुरुआत (8 बाइट) सही? */
    fun isPng(head: ByteArray): Boolean = head.size >= 8 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() &&
        head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte()

    // ------------------------------------------------------------------ फ़ोन
    private fun sp(c: Context) = c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)
    fun dir(c: Context): File = File(c.filesDir, "avatars").apply { mkdirs() }

    fun choice(c: Context): String = choiceOf(try { sp(c).getString(PREF, null) } catch (_: Exception) { null })

    /** pure: रखा pref -> Settings में चुना दिखने वाला मान (कुछ नहीं / हटाया गया = डिफ़ॉल्ट ऑर्ब) */
    fun choiceOf(pref: String?): String = shownId(pref)?.let { prefFor(it) } ?: ORB
    fun chosenId(c: Context): String? = shownId(try { sp(c).getString(PREF, null) } catch (_: Exception) { null })
    fun chooseOrb(c: Context) { sp(c).edit().putString(PREF, ORB).apply(); version++ }
    fun chooseAvatar(c: Context, id: String) { sp(c).edit().putString(PREF, prefFor(id)).apply(); version++ }

    /** बदलाव गिनती - होम लौटते ही नया चुनाव दिखे */
    @Volatile var version = 0; private set

    @Volatile private var listMem: Pair<Long, List<Avatar>>? = null

    /** सूची (फ़ोन में रखी index.json + पहले से तय) - हर ताज़ा पर फ़ाइल दोबारा नहीं पढ़ते, बदली हो तभी */
    fun list(c: Context): List<Avatar> {
        val f = File(dir(c), INDEX)
        val stamp = if (f.exists()) f.lastModified() else 0L
        listMem?.let { if (it.first == stamp) return it.second }
        val l = merge(parse(try { f.takeIf { it.exists() }?.readText() } catch (_: Exception) { null }))
        listMem = stamp to l
        return l
    }

    fun byId(c: Context, id: String?): Avatar? = list(c).firstOrNull { it.id == id }?.let { withHq(c, it) }

    fun hqFile(c: Context, a: Avatar): File? = hqName(a)?.let { File(dir(c), it) }

    /** HQ तस्वीर फ़ोन में आ चुकी? */
    fun hqReady(c: Context, a: Avatar): Boolean = hqFile(c, a)?.let { it.exists() && it.length() > 64 } == true

    fun withHq(c: Context, a: Avatar): Avatar = if (hqReady(c, a)) hqLook(a) else a

    fun file(c: Context, a: Avatar): File = File(dir(c), a.file)

    fun downloaded(c: Context, a: Avatar): Boolean = file(c, a).let { it.exists() && it.length() > 64 }

    private var memKey: String? = null
    private var mem: Bitmap? = null

    /**
     * अवतार की तस्वीर: पहले फ़ोन में डाउनलोड वाली; न हो तो res/drawable-nodpi में उसी नाम की (अगर कभी डाली गई);
     * कुछ नहीं = null (होम पर ORB). कभी क्रैश नहीं.
     */
    fun bitmap(c: Context, a: Avatar, maxPx: Int): Bitmap? {
        if (a.res != null) return hq(c, a, maxPx) ?: bundled(c, a, maxPx)
        val f = file(c, a)
        val key = a.file + "|" + f.lastModified() + "|" + maxPx
        if (key == memKey) mem?.let { return it }
        val b = try {
            if (f.exists()) decode(maxPx) { o -> BitmapFactory.decodeFile(f.path, o) }
            else {
                val res = c.resources.getIdentifier(a.file.removeSuffix(".png"), "drawable", c.packageName)
                if (res != 0) decode(maxPx) { o -> BitmapFactory.decodeResource(c.resources, res, o) } else null
            }
        } catch (e: Throwable) { com.dk.assistant.Quiet.note(e); null }
        if (b != null) { memKey = key; mem = b }
        return b
    }

    private var hqKey: String? = null
    private var hqMem: Bitmap? = null

    /** HQ (डाउनलोड वाली 2048px) तस्वीर - स्क्रीन जितनी ज़रूरत, उतनी ही बड़ी खोलो (memory बचे); न हो = null */
    private fun hq(c: Context, a: Avatar, maxPx: Int): Bitmap? {
        val f = hqFile(c, a)?.takeIf { it.exists() && it.length() > 64 } ?: return null
        val key = a.id + "|" + f.lastModified() + "|" + maxPx
        if (key == hqKey) hqMem?.let { return it }
        val b = try { decode(maxPx) { o -> BitmapFactory.decodeFile(f.path, o) } } catch (e: Throwable) { com.dk.assistant.Quiet.note(e); null }
        if (b == null) { f.delete(); return null }                           // टूटी फ़ाइल = हटाओ, फिर से डाउनलोड होगी
        hqKey = key; hqMem = b
        return b
    }

    private var resKey: String? = null
    private var resMem: Bitmap? = null

    /**
     * ऐप के अंदर वाला आर्टवर्क: drawable-nodpi से असली पिक्सल (सिस्टम कोई scaling नहीं करता), फिर बड़ा दिखाने के लिए
     * [smoothUp] (फ़िल्टर चालू, 2x-2x करके - High quality). फ़ाइल खुद कभी नहीं बदलती.
     */
    fun bundled(c: Context, a: Avatar, maxPx: Int): Bitmap? {
        val key = a.id + "|" + maxPx
        if (key == resKey) resMem?.let { return it }
        val b = try {
            val id = c.resources.getIdentifier(a.res, "drawable", c.packageName)
            if (id == 0) null else BitmapFactory.decodeResource(c.resources, id, BitmapFactory.Options().apply { inScaled = false })
        } catch (e: Throwable) { com.dk.assistant.Quiet.note(e); null } ?: return null
        val out = try { smoothUp(b, upTarget(maxPx)) } catch (e: Throwable) { com.dk.assistant.Quiet.note(e); b }
        resKey = key; resMem = out
        return out
    }

    /** छोटा प्रीव्यू (Settings): असली पिक्सल, बिना बदले */
    fun thumb(c: Context, a: Avatar): Bitmap? = try {
        val hf = hqFile(c, a)?.takeIf { it.exists() && it.length() > 64 }
        if (hf != null) decode(THUMB_PX) { o -> BitmapFactory.decodeFile(hf.path, o) }
        else if (a.res != null) {
            val id = c.resources.getIdentifier(a.res, "drawable", c.packageName)
            if (id == 0) null else BitmapFactory.decodeResource(c.resources, id, BitmapFactory.Options().apply { inScaled = false })
        } else file(c, a).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    } catch (e: Throwable) { com.dk.assistant.Quiet.note(e); null }

    /** Settings की छोटी जाली के लिए HQ तस्वीर इतनी (पूरी 2048px नहीं) */
    const val THUMB_PX = 384

    /** pure: बड़ा करने की हद (बहुत बड़ा bitmap = ज़्यादा memory; 1440px काफ़ी) */
    fun upTarget(maxPx: Int): Int = maxPx.coerceIn(256, 1440)

    /** pure: लंबी भुजा [target] तक पहुँचने के लिए 2x वाले कदम (आख़िरी कदम ठीक target तक) */
    fun upSteps(longSide: Int, target: Int): List<Int> {
        val out = ArrayList<Int>()
        var s = longSide
        while (s < target) { s = minOf(s * 2, target); out.add(s) }
        return out
    }

    /** फ़िल्टर चालू रखकर धीरे-धीरे (2x) बड़ा - एक बार में 15x खींचने से कहीं नरम */
    private fun smoothUp(src: Bitmap, target: Int): Bitmap {
        val long = maxOf(src.width, src.height)
        var b = src
        for (side in upSteps(long, target)) {
            val f = side.toFloat() / long
            val n = Bitmap.createScaledBitmap(b, maxOf(1, (src.width * f).toInt()), maxOf(1, (src.height * f).toInt()), true)
            if (b !== src) b.recycle()
            b = n
        }
        return b
    }

    private fun decode(maxPx: Int, go: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        go(o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxPx) s *= 2
        return go(BitmapFactory.Options().apply { inSampleSize = s })
    }

    private var landKey: String? = null
    private var landMem: Bitmap? = null

    /** 27 नवंबर 2026: चौड़ी स्क्रीन वाली अलग तस्वीर ([Avatar.land]) - फ़ोन में हो तो (स्क्रीन जितनी बड़ी), नहीं = null */
    fun landBitmap(c: Context, a: Avatar, maxPx: Int): Bitmap? {
        val n = a.land ?: return null
        val f = File(dir(c), n).takeIf { it.exists() && it.length() > 64 } ?: return null
        val key = n + "|" + f.lastModified() + "|" + maxPx
        if (key == landKey) landMem?.let { return it }
        val b = try { decode(maxPx) { o -> BitmapFactory.decodeFile(f.path, o) } } catch (e: Throwable) { com.dk.assistant.Quiet.note(e); null }
        if (b == null) { f.delete(); return null }
        landKey = key; landMem = b
        return b
    }

    /** चौड़ी स्क्रीन वाली तस्वीर इंटरनेट से (एक बार). [done] UI thread पर */
    fun fetchLand(c: Context, a: Avatar, done: (Boolean) -> Unit = {}) {
        val app = c.applicationContext
        val n = a.land ?: return
        if (!busy.add("land:" + n)) return
        Thread {
            var ok = false
            try { ok = save(app, n, MAX_BYTES) } catch (e: Throwable) { com.dk.assistant.Quiet.note(e) } finally { busy.remove("land:" + n) }
            main.post { try { done(ok) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) } }
        }.start()
    }

    private val busy = java.util.Collections.synchronizedSet(HashSet<String>())
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** इंटरनेट से: नई सूची + चुना अवतार (पहले से हो तो दोबारा नहीं). [done] UI thread पर (true = तस्वीर तैयार) */
    fun fetch(c: Context, id: String?, done: (Boolean) -> Unit = {}) {
        val app = c.applicationContext
        val key = id ?: "-"
        if (!busy.add(key)) return
        Thread {
            var ok = false
            try {
                refreshIndex(app)
                val a = byId(app, id)
                ok = a != null && (downloaded(app, a) || download(app, a))
            } catch (e: Throwable) { com.dk.assistant.Quiet.note(e) } finally { busy.remove(key) }
            main.post { try { done(ok) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) } }
        }.start()
    }

    /** इंटरनेट से HQ तस्वीर (एक बार, फिर फ़ोन में). [done] UI thread पर (true = HQ तैयार) */
    fun fetchHq(c: Context, a: Avatar, done: (Boolean) -> Unit = {}) {
        val app = c.applicationContext
        val name = hqName(a) ?: return
        if (hqReady(app, a)) { main.post { done(true) }; return }
        if (!busy.add("hq:" + a.id)) return
        Thread {
            var ok = false
            try { ok = save(app, name, HQ_MAX_BYTES) } catch (e: Throwable) { com.dk.assistant.Quiet.note(e) } finally { busy.remove("hq:" + a.id) }
            main.post { try { done(ok) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) } }
        }.start()
    }

    private fun refreshIndex(c: Context) {
        val bytes = get(BASE + INDEX, 256 * 1024) ?: return
        val txt = String(bytes, Charsets.UTF_8)
        if (parse(txt).isEmpty()) return
        val f = File(dir(c), INDEX)
        File(dir(c), "$INDEX.tmp").apply { writeText(txt); renameTo(f) }
    }

    private fun download(c: Context, a: Avatar): Boolean = save(c, a.file, MAX_BYTES)

    /** repo `avatars/[name]` -> फ़ोन; सिर्फ़ सच्ची PNG जो खुलती हो */
    private fun save(c: Context, name: String, max: Int): Boolean {
        if (!safeFile(name)) return false
        val bytes = get(BASE + name, max) ?: return false
        if (!isPng(bytes)) return false
        val tmp = File(dir(c), "$name.tmp")
        tmp.writeBytes(bytes)
        // सच में खुलने वाली तस्वीर है? तभी रखो
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tmp.path, o)
        if (o.outWidth <= 0) { tmp.delete(); return false }
        return tmp.renameTo(File(dir(c), name))
    }

    private fun get(url: String, max: Int): ByteArray? {
        val con = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 12000; readTimeout = 20000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DK-Assistant")
        }
        return try {
            if (con.responseCode !in 200..299) return null
            val out = java.io.ByteArrayOutputStream()
            con.inputStream.use { ins ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n); if (out.size() > max) return null
                }
            }
            out.toByteArray()
        } finally { con.disconnect() }
    }
}
