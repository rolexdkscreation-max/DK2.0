package com.dk.assistant.livewall

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.LocationManager
import com.dk.assistant.Quiet
import java.io.File
import java.security.MessageDigest

/**
 * 22 नवंबर 2026: लाइव वॉलपेपर - डाउनलोड, जाँच, सेव, चुनना, लगाना (फ़ोन वाला हिस्सा).
 * फ़ाइलें filesDir/livewall/<id>/ में (ऐप के अंदर नहीं - इंटरनेट से). डाउनलोड अधूरा/गलत हो तो पुराना वैसा ही रहता है.
 */
object LiveWallStore {
    private const val PREFS = "dk_livewall"
    private const val K_SEL = "selected"
    private const val K_TARGET = "target"
    private const val K_LAT = "lat"
    private const val K_LON = "lon"
    private const val K_LOC_AUTO = "loc_auto"
    private const val K_PENDING_LOCK = "pending_lock"
    private const val K_CATALOG = "catalog_json"
    private const val K_ANIM = "anim"
    const val TARGET_BOTH = "both"
    const val TARGET_HOME = "home"
    const val DEFAULT_LAT = 18.63
    const val DEFAULT_LON = 73.80

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dir(c: Context, id: String) = File(File(c.filesDir, "livewall"), id)

    /** catalog.json नहीं मिला (इंटरनेट नहीं) तब भी सूची में पहला वॉलपेपर दिखे - फ़ाइलें फिर भी डाउनलोड ही होती हैं */
    fun items(c: Context): List<SkyCatalog.Item> {
        val saved = p(c).getString(K_CATALOG, null)?.let { SkyCatalog.parse(it) }.orEmpty()
        val base = SkyCatalog.parse(LiveWallBuiltin.CATALOG_JSON)
        return (saved + base.filter { b -> saved.none { it.id == b.id } }).filter { it.supported }
    }

    /** background thread पर. नई सूची मिली = true */
    fun refreshCatalog(c: Context): Boolean {
        for (u in listOf(SkyCatalog.CATALOG, SkyCatalog.CATALOG_BRANCH)) {
            try {
                val s = String(get(u, 256 * 1024), Charsets.UTF_8)
                if (SkyCatalog.parse(s).isNotEmpty()) { p(c).edit().putString(K_CATALOG, s).apply(); return true }
            } catch (e: Exception) { Quiet.note(e) }
        }
        return false
    }

    fun installed(c: Context, it: SkyCatalog.Item): Boolean {
        val d = dir(c, it.id)
        val mark = File(d, ".ok")
        return mark.isFile && mark.readText().trim() == it.version.toString() && it.files.all { f -> File(d, f.name).isFile }
    }

    fun installedAny(c: Context, id: String): Boolean = File(dir(c, id), ".ok").isFile && File(dir(c, id), "scene.json").isFile

    fun selectedId(c: Context): String = p(c).getString(K_SEL, null) ?: LiveWallBuiltin.FIRST_ID
    fun select(c: Context, id: String) { p(c).edit().putString(K_SEL, id).apply() }

    fun target(c: Context): String = p(c).getString(K_TARGET, TARGET_BOTH) ?: TARGET_BOTH
    fun setTarget(c: Context, t: String) { p(c).edit().putString(K_TARGET, t).apply() }

    /** 22 नवंबर 2026: बैटरी - [LiveWallPower.MODE_SAVE] (डिफ़ॉल्ट) या [LiveWallPower.MODE_SMOOTH] */
    fun animMode(c: Context): String = p(c).getString(K_ANIM, LiveWallPower.MODE_SAVE) ?: LiveWallPower.MODE_SAVE
    fun setAnimMode(c: Context, m: String) { p(c).edit().putString(K_ANIM, m).apply() }

    fun remove(c: Context, id: String) { if (SkyCatalog.validId(id)) dir(c, id).deleteRecursively() }

    /**
     * सारी फ़ाइलें डाउनलोड + SHA-256 जाँच. progress(0..1). सफल = null, नहीं तो छोटा कारण (हिंदी).
     * background thread पर बुलाओ.
     */
    fun download(c: Context, it: SkyCatalog.Item, progress: (Float) -> Unit, cancelled: () -> Boolean = { false }): String? {
        if (!it.supported) return "इसके लिए DK का नया version चाहिए"
        val root = File(c.filesDir, "livewall").apply { mkdirs() }
        val tmp = File(root, ".${it.id}.part")
        return try {
            tmp.deleteRecursively(); tmp.mkdirs()
            val total = it.totalBytes.coerceAtLeast(1)
            var done = 0L
            for (f in it.files) {
                if (cancelled()) return "रोका गया"
                val urls = listOfNotNull(f.url, SkyCatalog.fallbackUrl(f.url))
                var ok = false
                var why = "डाउनलोड नहीं हुआ"
                for (u in urls) {
                    try {
                        val bytes = get(u, (f.bytes + 1024).toInt()) { n -> progress(((done + n).toFloat() / total).coerceIn(0f, 1f)) }
                        if (bytes.size.toLong() != f.bytes) { why = "फ़ाइल अधूरी (${f.name})"; continue }
                        if (sha256(bytes) != f.sha256) { why = "फ़ाइल की जाँच फ़ेल (${f.name})"; continue }
                        File(tmp, f.name).writeBytes(bytes)
                        ok = true; break
                    } catch (e: java.net.UnknownHostException) { why = "इंटरनेट नहीं" }
                    catch (e: java.io.FileNotFoundException) { why = "वॉलपेपर अभी सर्वर पर नहीं" }
                    catch (e: Exception) { why = "डाउनलोड नहीं हुआ" }
                }
                if (!ok) return why
                done += f.bytes
                progress((done.toFloat() / total).coerceIn(0f, 1f))
            }
            File(tmp, ".ok").writeText(it.version.toString())
            val d = dir(c, it.id)
            d.deleteRecursively()
            if (!tmp.renameTo(d)) return "सेव नहीं हुआ"
            null
        } catch (e: Exception) { "डाउनलोड नहीं हुआ" }
        finally { tmp.deleteRecursively() }
    }

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun get(url: String, max: Int, onBytes: ((Long) -> Unit)? = null): ByteArray {
        val con = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        con.connectTimeout = 15000; con.readTimeout = 30000
        try {
            if (con.responseCode != 200) throw java.io.FileNotFoundException(url)
            val out = java.io.ByteArrayOutputStream()
            con.inputStream.use { ins ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) throw java.io.IOException("too big")
                    onBytes?.invoke(out.size().toLong())
                }
            }
            return out.toByteArray()
        } finally { con.disconnect() }
    }

    // ---------------------------------------------------------------- जगह (सूरज-चाँद सही समय पर)
    fun lat(c: Context): Double = p(c).getFloat(K_LAT, DEFAULT_LAT.toFloat()).toDouble()
    fun lon(c: Context): Double = p(c).getFloat(K_LON, DEFAULT_LON.toFloat()).toDouble()
    fun locAuto(c: Context): Boolean = p(c).getBoolean(K_LOC_AUTO, false)

    /** फ़ोन की आख़िरी पता जगह (GPS चालू किए बिना). मिली = true */
    @Suppress("MissingPermission")
    fun refreshLocation(c: Context): Boolean {
        val okFine = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val okCoarse = c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!okFine && !okCoarse) return false
        return try {
            val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val best = lm.getProviders(true).mapNotNull { pr -> try { lm.getLastKnownLocation(pr) } catch (_: Exception) { null } }
                .maxByOrNull { it.time } ?: return false
            p(c).edit().putFloat(K_LAT, best.latitude.toFloat()).putFloat(K_LON, best.longitude.toFloat()).putBoolean(K_LOC_AUTO, true).apply()
            true
        } catch (_: Exception) { false }
    }

    // ---------------------------------------------------------------- लगाना (होम + लॉक)
    fun component(c: Context) = ComponentName(c, LiveWallpaperService::class.java)

    fun isActive(c: Context): Boolean = try {
        WallpaperManager.getInstance(c).wallpaperInfo?.component == component(c)
    } catch (_: Exception) { false }

    /** सिस्टम का "लाइव वॉलपेपर लगाएँ" पन्ना (कई फ़ोन वहीं होम / होम + लॉक पूछते हैं) */
    fun applyIntent(c: Context): Intent {
        p(c).edit().putBoolean(K_PENDING_LOCK, target(c) == TARGET_BOTH).apply()
        return Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component(c))
    }

    fun chooserIntent(): Intent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)

    /**
     * हमारा वॉलपेपर सच में लग गया (preview नहीं) - "होम + लॉक" चुना था तो लॉक स्क्रीन की अलग फ़ोटो हटाओ,
     * ताकि लॉक स्क्रीन पर भी यही लाइव वॉलपेपर दिखे.
     */
    fun onActivated(c: Context) {
        if (!p(c).getBoolean(K_PENDING_LOCK, false)) return
        p(c).edit().putBoolean(K_PENDING_LOCK, false).apply()
        lockToo(c)
    }

    fun lockToo(c: Context): Boolean = try {
        WallpaperManager.getInstance(c).clear(WallpaperManager.FLAG_LOCK); true
    } catch (_: Exception) { false }

    // ---------------------------------------------------------------- दृश्य लोड करना
    class Loaded(val pack: SkyPack, val bitmaps: MutableMap<String, Bitmap>, val moon: IntArray?, val moonSize: Int, val stars: List<SkyScene.Star>)

    /** maxW = स्क्रीन की चौड़ाई (तस्वीरें उतनी ही बड़ी डिकोड - quality पूरी, memory कम) */
    fun load(c: Context, id: String, maxW: Int): Loaded? {
        val d = dir(c, id)
        if (!installedAny(c, id)) return null
        return try {
            val pack = SkyCatalog.parseScene(File(d, "scene.json").readText()) ?: return null
            val bm = HashMap<String, Bitmap>()
            for (l in pack.layers) {
                val f = File(d, l.file); if (!f.isFile) continue
                decode(f, (maxW * l.width).toInt())?.let { bm[l.id] = it }
            }
            var moon: IntArray? = null; var ms = 0
            File(d, pack.moon).takeIf { it.isFile }?.let { f ->
                decode(f, 1024)?.let { b ->
                    ms = minOf(b.width, b.height)
                    moon = IntArray(ms * ms).also { a -> b.getPixels(a, 0, ms, 0, 0, ms, ms) }
                    b.recycle()
                }
            }
            val stars = File(d, pack.stars).takeIf { it.isFile }?.let { SkyScene.parseStars(it.readText()) }.orEmpty()
            Loaded(pack, bm, moon, ms, stars)
        } catch (_: Throwable) { null }
    }

    private fun decode(f: File, targetW: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= targetW) s *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        if (b.width <= targetW * 1.15) return b
        val h = (b.height.toLong() * targetW / b.width).toInt().coerceAtLeast(1)
        val r = Bitmap.createScaledBitmap(b, targetW, h, true)
        if (r !== b) b.recycle()
        return r
    }
}
