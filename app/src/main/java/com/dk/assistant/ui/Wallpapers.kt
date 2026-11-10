package com.dk.assistant.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * नवंबर 2026: वॉलपेपर - पूरे ऐप के पीछे वाला चित्र (होम, सेटिंग, चैट, कंप्यूटर... हर स्क्रीन का AuroraView).
 *
 * "" = थीम वाला चित्र (पहले जैसा), wp_1..wp_5 = ऐप के अंदर वाले, custom = गैलरी से चुना गया
 * (filesDir/wallpaper/custom.jpg, फ़ोन में ही - कहीं भेजा नहीं जाता).
 * थीम के रंग/काँच/ओर्ब वैसे ही रहते हैं; सिर्फ़ पीछे का चित्र बदलता है.
 */
object Wallpapers {
    const val K_WALL = "ui_wallpaper"
    const val NONE = ""
    const val CUSTOM = "custom"

    data class Wall(val id: String, val name: String, val res: Int)

    val BUILT_IN = listOf(
        Wall("wp_1", "उत्तरी रोशनी", com.dk.assistant.R.drawable.dk_wp_1),
        Wall("wp_2", "गैलेक्सी", com.dk.assistant.R.drawable.dk_wp_2),
        Wall("wp_3", "पहाड़ों की शाम", com.dk.assistant.R.drawable.dk_wp_3),
        Wall("wp_4", "काला-सुनहरा", com.dk.assistant.R.drawable.dk_wp_4),
        Wall("wp_5", "रात का जंगल", com.dk.assistant.R.drawable.dk_wp_5)
    )

    /** गैलरी वाला चित्र इससे बड़ा नहीं रखा जाता (लंबी तरफ़, px) */
    const val CUSTOM_MAX = 1920

    // ------------------------------------------------------------------ शुद्ध नियम (JUnit)
    fun valid(id: String?) = id == NONE || id == CUSTOM || BUILT_IN.any { it.id == id }

    /** गलत/पुराना मान = थीम वाला; custom पर फ़ाइल न हो तो भी थीम वाला */
    fun resolve(id: String?, customExists: Boolean): String = when {
        id == null || !valid(id) -> NONE
        id == CUSTOM && !customExists -> NONE
        else -> id
    }

    /** Look में रखा जाने वाला निशान: custom पर फ़ाइल का समय भी, ताकि नया चित्र चुनते ही हर स्क्रीन बदले */
    fun key(id: String, customStamp: Long = 0L) = if (id == CUSTOM) "$CUSTOM@$customStamp" else id
    fun idOf(key: String) = key.substringBefore('@')

    /** चित्र के ऊपर कितना गहरा परदा (अक्षर साफ़ पढ़ने के लिए). अपने चित्र अक्सर ज़्यादा चमकीले होते हैं */
    fun dim(key: String, packDim: Float): Float = when (idOf(key)) {
        NONE -> packDim
        CUSTOM -> 0.42f
        else -> 0.22f
    }

    /** पीछे की रंगीन रोशनी (aurora गोले) वॉलपेपर पर बहुत हल्की - ताकि पुराना नीला न दिखे */
    fun blobFactor(key: String, hasPhoto: Boolean): Float = when {
        !hasPhoto -> 1f
        idOf(key) == NONE -> 0.55f
        else -> 0.18f
    }

    /** decode के लिए inSampleSize: लंबी तरफ़ max से ज़्यादा न रहे (2 की घात) */
    fun sampleSize(w: Int, h: Int, max: Int): Int {
        if (w <= 0 || h <= 0 || max <= 0) return 1
        var s = 1
        while (maxOf(w, h) / s > max * 2) s *= 2
        return s
    }

    /** बड़ा हो तो छोटा करने का पैमाना (1 = वैसा ही) */
    fun scaleFor(w: Int, h: Int, max: Int): Float {
        val m = maxOf(w, h)
        return if (m <= max || m <= 0) 1f else max.toFloat() / m
    }

    // ------------------------------------------------------------------ फ़ोन पर
    fun customFile(c: Context) = File(File(c.filesDir, "wallpaper"), "custom.jpg")

    fun selectedId(c: Context): String {
        val raw = c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).getString(K_WALL, NONE)
        return resolve(raw, customFile(c).isFile)
    }

    fun currentKey(c: Context): String {
        val id = selectedId(c)
        return key(id, if (id == CUSTOM) customFile(c).lastModified() else 0L)
    }

    fun select(c: Context, id: String) {
        val safe = resolve(id, customFile(c).isFile)
        c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).edit().putString(K_WALL, safe).apply()
        NeonTheme.load(c)
    }

    /** background thread पर बुलाओ. सफल = null, नहीं तो छोटा कारण */
    fun saveCustom(c: Context, uri: Uri): String? {
        return try {
            val bmp = decodeUri(c, uri) ?: return "यह चित्र खुल नहीं पाया"
            val sc = scaleFor(bmp.width, bmp.height, CUSTOM_MAX)
            val out = if (sc < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt().coerceAtLeast(1),
                (bmp.height * sc).toInt().coerceAtLeast(1), true) else bmp
            val f = customFile(c); f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "custom.tmp")
            tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
            f.setLastModified(System.currentTimeMillis())
            c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).edit().putString(K_WALL, CUSTOM).apply()
            null
        } catch (_: OutOfMemoryError) { "चित्र बहुत बड़ा है" } catch (_: Exception) { "चित्र सेव नहीं हो पाया" }
    }

    fun removeCustom(c: Context) {
        customFile(c).delete()
        if (c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).getString(K_WALL, NONE) == CUSTOM) select(c, NONE)
    }

    /** Android 9+ पर ImageDecoder फ़ोटो का घुमाव (EXIF) खुद सीधा करता है; पुराने पर सीधा decode */
    private fun decodeUri(c: Context, uri: Uri): Bitmap? {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val src = android.graphics.ImageDecoder.createSource(c.contentResolver, uri)
            return android.graphics.ImageDecoder.decodeBitmap(src) { d, info, _ ->
                val s = info.size
                val sc = scaleFor(s.width, s.height, CUSTOM_MAX)
                if (sc < 1f) d.setTargetSize((s.width * sc).toInt().coerceAtLeast(1), (s.height * sc).toInt().coerceAtLeast(1))
                d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bo) }
        val o = BitmapFactory.Options().apply { inSampleSize = sampleSize(bo.outWidth, bo.outHeight, CUSTOM_MAX) }
        return c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
    }

    /** AuroraView के लिए (ThemePacks.background से): null = थीम वाला चित्र ही */
    fun decode(c: Context, key: String, maxW: Int): Bitmap? {
        val id = idOf(key)
        if (id == NONE) return null
        val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        return try {
            if (id == CUSTOM) {
                val f = customFile(c); if (!f.isFile) return null
                val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, bo)
                var s = 1; while (bo.outWidth / (s * 2) >= maxW.coerceAtLeast(360)) s *= 2
                o.inSampleSize = s
                BitmapFactory.decodeFile(f.path, o)
            } else {
                val w = BUILT_IN.firstOrNull { it.id == id } ?: return null
                o.inScaled = false
                BitmapFactory.decodeResource(c.resources, w.res, o)
            }
        } catch (_: Throwable) { null }
    }

    /** सेटिंग की छोटी झलक के लिए */
    fun thumb(c: Context, id: String, w: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        when (id) {
            NONE -> {
                o.inSampleSize = 4; o.inScaled = false
                val pid = ThemePacks.selectedId(c)
                if (pid == ThemePacks.DEFAULT_ID) BitmapFactory.decodeResource(c.resources, com.dk.assistant.R.drawable.dk_theme_bg, o)
                else BitmapFactory.decodeFile(File(ThemePacks.dir(c, pid), "bg.webp").path, o)
            }
            CUSTOM -> customFile(c).takeIf { it.isFile }?.let { f ->
                val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, bo)
                o.inSampleSize = sampleSize(bo.outWidth, bo.outHeight, w * 2)
                BitmapFactory.decodeFile(f.path, o)
            }
            else -> BUILT_IN.firstOrNull { it.id == id }?.let { o.inSampleSize = 4; o.inScaled = false; BitmapFactory.decodeResource(c.resources, it.res, o) }
        }
    } catch (_: Throwable) { null }
}
