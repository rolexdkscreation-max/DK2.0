package com.dk.assistant.ui

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * पूरी थीम (सिर्फ़ एक रंग नहीं): पृष्ठभूमि चित्र + पृष्ठभूमि ढलान + काँच का रंग + मुख्य रंग जोड़ी + शीर्षक का रंग + ORB के रंग.
 *
 * "dk_futuristic" ऐप के अंदर ही है (बिना डाउनलोड). बाकी थीम GitHub repo के `themes/` फ़ोल्डर से डाउनलोड होकर
 * filesDir/themes/<id>/ में रहती हैं; डाउनलोड के बाद ही लागू होती हैं.
 *
 * सिर्फ़ दिखावट: AI, आवाज़, key, prompt कुछ नहीं छूता. चुनी हुई थीम "dk_prefs" में ui_pack नाम से.
 * JSON पढ़ने का हिस्सा (parse/clean) शुद्ध Kotlin + org.json है - JUnit में जाँच होती है.
 */
data class ThemePack(
    val id: String,
    val name: String,
    val desc: String,
    /** पृष्ठभूमि: ऊपर, बीच, नीचे (चित्र न हो/लोड न हो तब) */
    val bg: IntArray,
    /** काँच का आधार (ऊपर/नीचे) - पारदर्शिता यूज़र के slider से */
    val glassA: Int,
    val glassB: Int,
    val accent: Int,
    val partner: Int,
    /** शीर्षक का चमकदार ढलान (जैसे CONTROL CENTER का सुनहरा) */
    val titleA: Int,
    val titleB: Int,
    /** ORB की खाली हालत के रंग */
    val orbA: Int,
    val orbB: Int,
    /** चित्र के ऊपर काली परत 0..0.85 (text साफ़ पढ़ने के लिए) */
    val dim: Float,
    val version: Int = 1
) {
    override fun equals(other: Any?) = other is ThemePack && other.id == id && other.version == version
    override fun hashCode() = id.hashCode() * 31 + version
}

object ThemePacks {
    const val K_PACK = "ui_pack"
    const val DEFAULT_ID = "dk_futuristic"
    const val REPO_RAW = "https://raw.githubusercontent.com/rolexdkscreation-max/DK2.0/main/themes"
    const val MAX_BG_BYTES = 3 * 1024 * 1024

    /** ऐप के अंदर वाली (बिना डाउनलोड) - संदर्भ चित्र जैसी नेवी/सियान/वायलेट + सुनहरा शीर्षक */
    val DEFAULT = ThemePack(
        id = DEFAULT_ID, name = "DK Futuristic", desc = "नेवी HUD कमांड सेंटर • ऐप के अंदर (डिफ़ॉल्ट)",
        bg = intArrayOf(0xFF050A1F.toInt(), 0xFF0A1033.toInt(), 0xFF120A33.toInt()),
        glassA = 0xFF1B2A6E.toInt(), glassB = 0xFF0E1440.toInt(),
        accent = 0xFF3D8BFF.toInt(), partner = 0xFF8B5CF6.toInt(),
        titleA = 0xFFFFE7A3.toInt(), titleB = 0xFFF5B83D.toInt(),
        orbA = 0xFF3D8BFF.toInt(), orbB = 0xFFB15CFF.toInt(), dim = 0.30f
    )

    /** डाउनलोड वाली थीम की सूची (repo के themes/index.json जैसी - इंटरनेट न हो तब भी नाम दिखें) */
    data class Entry(val id: String, val name: String, val desc: String, val accent: Int)
    val CATALOG = listOf(
        Entry("cyber_neon", "Cyber Neon City", "मैजेंटा नियॉन रात का शहर", 0xFFFF3EA5.toInt()),
        Entry("galaxy_violet", "Galaxy Violet", "बैंगनी नेबुला और तारे", 0xFFA855F7.toInt()),
        Entry("emerald_matrix", "Emerald Matrix", "हरा कोड-रेन हैकर लुक", 0xFF22E58B.toInt()),
        Entry("solar_gold", "Solar Gold", "काला-सुनहरा प्रीमियम", 0xFFF5B83D.toInt())
    )

    private val ID_RE = Regex("^[a-z0-9_]{2,32}$")
    fun validId(id: String?) = id != null && ID_RE.matches(id)

    /** "#RRGGBB" / "#AARRGGBB" → Int; गलत हो तो fallback */
    fun color(s: String?, fb: Int): Int {
        val t = s?.trim()?.removePrefix("#") ?: return fb
        if (!Regex("^[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$").matches(t)) return fb
        return if (t.length == 6) (0xFF000000.toInt() or t.toLong(16).toInt()) else t.toLong(16).toInt()
    }

    /**
     * काँच इतना गहरा रखो कि सबसे पारदर्शी पर भी सफ़ेद अक्षर (4.5:1) साफ़ पढ़े जाएँ - कोई भी डाउनलोड वाली थीम
     * चटक/हल्का रंग दे तो भी (NeonTheme.glassTop इसी आधार से).
     */
    fun safeGlass(c: Int): Int {
        var x = c or (0xFF shl 24)
        var i = 0
        while (NeonTheme.luminance(x) > 0.035 && i < 20) { x = NeonTheme.mix(x, 0xFF000000.toInt(), 0.15f); i++ }
        return x
    }
    fun safeBg(c: Int): Int {
        var x = c or (0xFF shl 24)
        var i = 0
        while (NeonTheme.luminance(x) > 0.02 && i < 20) { x = NeonTheme.mix(x, 0xFF000000.toInt(), 0.2f); i++ }
        return x
    }

    /** theme.json → ThemePack (गलत/अधूरी चीज़ें सुरक्षित शुरुआती value से; id गलत हो तो null) */
    fun parse(json: String): ThemePack? = try {
        val o = JSONObject(json)
        val id = o.optString("id")
        if (!validId(id)) null else {
            val d = DEFAULT
            val bgArr = o.optJSONArray("bg")
            val bg = IntArray(3) { i -> safeBg(color(bgArr?.optString(i), d.bg[i])) }
            ThemePack(
                id = id,
                name = o.optString("name", id).take(40).ifBlank { id },
                desc = o.optString("desc", "").take(80),
                bg = bg,
                glassA = safeGlass(color(o.optString("glassTop"), d.glassA)),
                glassB = safeGlass(color(o.optString("glassBottom"), d.glassB)),
                accent = color(o.optString("accent"), d.accent) or (0xFF shl 24),
                partner = color(o.optString("partner"), d.partner) or (0xFF shl 24),
                titleA = color(o.optString("titleA"), d.titleA) or (0xFF shl 24),
                titleB = color(o.optString("titleB"), d.titleB) or (0xFF shl 24),
                orbA = color(o.optString("orbA"), d.orbA) or (0xFF shl 24),
                orbB = color(o.optString("orbB"), d.orbB) or (0xFF shl 24),
                dim = o.optDouble("dim", d.dim.toDouble()).toFloat().let { if (it.isNaN()) d.dim else it.coerceIn(0f, 0.85f) },
                version = o.optInt("version", 1).coerceIn(1, 9999)
            )
        }
    } catch (_: Exception) { null }

    // ------------------------------------------------------------------ फ़ोन पर (Android)
    fun dir(c: Context, id: String) = File(File(c.filesDir, "themes"), id)
    fun installed(c: Context, id: String) = id == DEFAULT_ID ||
        (validId(id) && File(dir(c, id), "theme.json").isFile && File(dir(c, id), "bg.webp").isFile)

    fun selectedId(c: Context): String {
        val id = c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).getString(K_PACK, DEFAULT_ID) ?: DEFAULT_ID
        return if (installed(c, id)) id else DEFAULT_ID
    }

    fun load(c: Context, id: String): ThemePack {
        if (id == DEFAULT_ID || !validId(id)) return DEFAULT
        return try { parse(File(dir(c, id), "theme.json").readText()) ?: DEFAULT } catch (_: Exception) { DEFAULT }
    }

    fun select(c: Context, id: String) {
        val safe = if (installed(c, id)) id else DEFAULT_ID
        c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).edit().putString(K_PACK, safe).apply()
        NeonTheme.load(c)
    }

    fun remove(c: Context, id: String) {
        if (id == DEFAULT_ID || !validId(id)) return
        dir(c, id).deleteRecursively()
        if (c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).getString(K_PACK, DEFAULT_ID) == id) select(c, DEFAULT_ID)
    }

    /** background thread पर बुलाओ. सफल = null, नहीं तो छोटा कारण (हिंदी में) */
    fun download(c: Context, id: String): String? {
        if (!validId(id) || id == DEFAULT_ID) return "गलत थीम"
        val tmp = File(File(c.filesDir, "themes"), ".$id.part")
        return try {
            tmp.deleteRecursively(); tmp.mkdirs()
            val json = String(get("$REPO_RAW/$id/theme.json", 64 * 1024), Charsets.UTF_8)
            val p = parse(json) ?: return "थीम फ़ाइल ठीक नहीं"
            if (p.id != id) return "थीम फ़ाइल ठीक नहीं"
            val img = get("$REPO_RAW/$id/bg.webp", MAX_BG_BYTES)
            // चित्र सच में खुलता है या नहीं
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(img, 0, img.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return "चित्र ठीक नहीं"
            File(tmp, "theme.json").writeText(json)
            File(tmp, "bg.webp").writeBytes(img)
            val d = dir(c, id); d.deleteRecursively()
            if (!tmp.renameTo(d)) return "सेव नहीं हुआ"
            null
        } catch (e: java.net.UnknownHostException) { "इंटरनेट नहीं" }
        catch (e: java.io.FileNotFoundException) { "थीम अभी सर्वर पर नहीं" }
        catch (e: Exception) { "डाउनलोड नहीं हुआ" }
        finally { tmp.deleteRecursively() }
    }

    private fun get(url: String, max: Int): ByteArray {
        val con = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        con.connectTimeout = 12000; con.readTimeout = 20000
        try {
            if (con.responseCode != 200) throw java.io.FileNotFoundException(url)
            val out = java.io.ByteArrayOutputStream()
            con.inputStream.use { ins ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) throw java.io.IOException("too big")
                }
            }
            return out.toByteArray()
        } finally { con.disconnect() }
    }

    /** पृष्ठभूमि चित्र (छोटा करके, एक ही बार) - AuroraView इस्तेमाल करता है */
    @Volatile private var bmpKey = ""
    @Volatile private var bmp: android.graphics.Bitmap? = null
    fun background(c: Context, p: ThemePack, maxW: Int, wallpaper: String = Wallpapers.NONE): android.graphics.Bitmap? {
        val key = "${p.id}:${p.version}|$wallpaper"
        bmp?.let { if (bmpKey == key) return it }
        // वॉलपेपर चुना है तो वही (न खुले तो थीम वाला चित्र)
        val b = (if (wallpaper != Wallpapers.NONE) Wallpapers.decode(c, wallpaper, maxW) else null) ?: try {
            val o = android.graphics.BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 }
            if (p.id == DEFAULT_ID) {
                o.inScaled = false
                android.graphics.BitmapFactory.decodeResource(c.resources, com.dk.assistant.R.drawable.dk_theme_bg, o)
            } else {
                val f = File(dir(c, p.id), "bg.webp")
                val bo = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.path, bo)
                var s = 1; while (bo.outWidth / (s * 2) >= maxW.coerceAtLeast(360)) s *= 2
                o.inSampleSize = s
                android.graphics.BitmapFactory.decodeFile(f.path, o)
            }
        } catch (_: Throwable) { null }
        bmp = b; bmpKey = key
        return b
    }
}
