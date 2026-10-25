package com.dk.assistant.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.dk.assistant.DKLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * v3.71: अवतार के चित्र - समझदारी से खोलना ताकि मेमोरी न फूले.
 *  - res/drawable(-nodpi) का PNG सीधे stream से (density वाला बड़ा करना नहीं - drawable/ में रखा 1024x1536 चित्र
 *    xxhdpi फ़ोन पर decodeResource से ~2.6 गुना बड़ा बनकर 40+ MB खाता).
 *  - स्क्रीन जितनी बड़ी है उससे बड़ा नहीं (inSampleSize).
 *  - चेहरे के चित्र में से सिर्फ़ चेहरे का आयत (BitmapRegionDecoder) - पूरा 1024x1536 कभी नहीं खुलता.
 *  - चेहरा एक बार धुंधले किनारों (feather) के साथ तैयार होकर याद रहता है (फ़ोन घुमाने पर दोबारा नहीं बनता).
 *  - कोई चित्र न मिले = null (ऐप नहीं रुकता, अवतार उसकी जगह पास वाला रूप दिखाता है).
 */
object AvatarAssets {
    const val BODY = "avatar_body"
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dk-avatar-load").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 } }
    @Volatile private var sample = 0
    @Volatile var onLoaded: (() -> Unit)? = null

    private fun resId(c: Context, name: String): Int =
        try { c.resources.getIdentifier(name, "drawable", c.packageName) } catch (_: Exception) { 0 }

    /** avatar_body है? (न हो तो ORB ही दिखे) */
    fun available(c: Context): Boolean = resId(c, BODY) != 0

    /** जो चित्र नहीं हैं उनके नाम (सेटिंग्स में बताने के लिए) */
    fun missingFiles(c: Context): List<String> =
        (listOf(BODY) + Face.values().flatMap { it.files() }).filter { resId(c, it) == 0 }

    /** स्क्रीन की बड़ी भुजा के हिसाब से एक ही sample - घुमाने पर बदले नहीं */
    private fun sampleFor(c: Context): Int {
        if (sample > 0) return sample
        val dm = c.resources.displayMetrics
        val big = maxOf(dm.widthPixels, dm.heightPixels); val small = minOf(dm.widthPixels, dm.heightPixels)
        // खड़े फ़ोन पर चित्र की ऊँचाई ~ बड़ी भुजा, चौड़ाई ~ छोटी भुजा
        sample = AvatarGeometry.sampleSize(AvatarGeometry.IMG_W, AvatarGeometry.IMG_H, small, big)
        return sample
    }

    /** तैयार हो तो चित्र, वरना null + पीछे खोलना शुरू */
    fun get(c: Context, name: String): Bitmap? {
        cache[name]?.let { return it }
        if (name in missing) return null
        if (loading.add(name)) {
            val app = c.applicationContext
            worker.execute {
                try {
                    val b = if (name == BODY) decodeBody(app) else decodeFace(app, name)
                    if (b != null) cache[name] = b else missing.add(name)
                } catch (e: Throwable) {        // OOM भी - ऐप न रुके
                    missing.add(name); DKLog.d("अवतार चित्र $name नहीं खुला: ${e.javaClass.simpleName}")
                } finally { loading.remove(name) }
                onLoaded?.invoke()
            }
        }
        return null
    }

    fun has(name: String) = cache.containsKey(name)
    fun isMissing(name: String) = name in missing

    /** एक चेहरे के सारे रूप पहले से (भाव बदलने पर झटका न लगे) */
    fun preload(c: Context, face: Face) { face.files().forEach { get(c, it) } }

    private fun bounds(c: Context, id: Int): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.resources.openRawResource(id).use { BitmapFactory.decodeStream(it, null, o) }
        return o.outWidth to o.outHeight
    }

    private fun decodeBody(c: Context): Bitmap? {
        val id = resId(c, BODY).takeIf { it != 0 } ?: return null
        val o = BitmapFactory.Options().apply { inSampleSize = sampleFor(c); inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return c.resources.openRawResource(id).use { BitmapFactory.decodeStream(it, null, o) }
    }

    private fun decodeFace(c: Context, name: String): Bitmap? {
        val id = resId(c, name).takeIf { it != 0 } ?: return null
        val (w, h) = bounds(c, id)
        if (w <= 0 || h <= 0) return null
        // चित्र 1024x1536 न हो तो भी वही अनुपात
        val sx = w / AvatarGeometry.IMG_W.toFloat(); val sy = h / AvatarGeometry.IMG_H.toFloat()
        val r = Rect((AvatarGeometry.FACE_L * sx).toInt(), (AvatarGeometry.FACE_T * sy).toInt(),
            (AvatarGeometry.FACE_R * sx).toInt(), (AvatarGeometry.FACE_B * sy).toInt())
        val o = BitmapFactory.Options().apply { inSampleSize = sampleFor(c); inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val crop = c.resources.openRawResource(id).use { s ->
            @Suppress("DEPRECATION")
            val d = BitmapRegionDecoder.newInstance(s, false) ?: return null
            try { d.decodeRegion(r, o) } finally { d.recycle() }
        } ?: return null
        return feather(crop)
    }

    /** किनारे नरम: superellipse mask (DST_IN) - जोड़ नहीं दिखता */
    private fun feather(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val out = if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, true)
        if (out !== src) src.recycle()
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val a = (AvatarGeometry.faceAlpha((x + 0.5f) / w, (y + 0.5f) / h) * 255).toInt()
            px[y * w + x] = a shl 24
        }
        val mask = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        mask.recycle()
        return out
    }
}
