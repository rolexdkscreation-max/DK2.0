package com.dk.assistant.agent.android

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.dk.assistant.DKLog
import com.dk.assistant.agent.social.ClipImage
import com.dk.assistant.agent.social.ClipTokenizer
import com.dk.assistant.agent.social.GalleryRank
import com.dk.assistant.agent.social.Int4Table
import com.dk.assistant.agent.social.SharedItem
import com.dk.assistant.plus.models.ModelCatalog
import com.dk.assistant.plus.models.ModelManager
import java.io.File
import java.io.RandomAccessFile
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * 16 नवंबर 2026 (मालिक: "गैलरी से कौन सी फोटो पिक करनी है, वो खुद करे"): MobileCLIP2-S0 से गैलरी खोज, फ़ोन पर ही.
 * मॉडल APK में नहीं - [ModelCatalog.SOCIAL_CLIP] (~102 MB) Settings से डाउनलोड. फोटो कहीं भेजी नहीं जाती.
 * हर फोटो/वीडियो का embedding एक बार बनता है और `filesDir/social_clip/index.bin` में रहता है (अगली बार तेज़).
 * सिर्फ़ पढ़ना - कोई फ़ाइल बदली / हटाई नहीं जाती. IO / Default thread पर बुलाओ.
 */
object GalleryClip {
    /** पहली बार में कितनी नई फोटो का embedding (बाकी अगली बार / "गैलरी पहचान तैयार करो" बटन से) */
    const val MAX_NEW_PER_SEARCH = 120
    const val MAX_IMAGES = 600
    const val MAX_VIDEOS = 120
    private const val MAX_CACHE = 2000

    sealed class Result {
        data class Found(val item: SharedItem, val score: Float) : Result()
        data class Ask(val code: String, val msg: String) : Result()
    }

    private val lock = Any()
    @Volatile private var vision: OrtSession? = null
    @Volatile private var text: OrtSession? = null
    @Volatile private var tok: ClipTokenizer? = null
    @Volatile var broken: String? = null
        private set

    val supported: Boolean get() = com.dk.assistant.voice.VoiceExec.supported

    fun modelsReady(c: Context) = ModelCatalog.SOCIAL_CLIP.all { ModelManager.ready(c, it) }

    fun mediaPermission(c: Context): Boolean = if (Build.VERSION.SDK_INT >= 33)
        c.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
    else c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun videoPermission(c: Context): Boolean = if (Build.VERSION.SDK_INT >= 33)
        c.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
    else mediaPermission(c)

    fun close() = synchronized(lock) {
        try { vision?.close() } catch (_: Exception) {}; try { text?.close() } catch (_: Exception) {}
        vision = null; text = null; tok = null; broken = null
    }

    private fun open(c: Context): Boolean {
        if (vision != null && text != null && tok != null) return true
        synchronized(lock) {
            if (vision != null && text != null && tok != null) return true
            if (!supported || !modelsReady(c)) return false
            return try {
                val o = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2); setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
                val env = OrtEnvironment.getEnvironment()
                vision = env.createSession(ModelManager.file(c, ModelCatalog.CLIP_VISION).absolutePath, o)
                text = env.createSession(ModelManager.file(c, ModelCatalog.CLIP_TEXT).absolutePath, o)
                tok = ModelManager.file(c, ModelCatalog.CLIP_BPE).inputStream().use { ClipTokenizer.load(it) }
                broken = null; true
            } catch (e: Throwable) {
                broken = e.javaClass.simpleName
                DKLog.d("गैलरी पहचान: मॉडल नहीं खुला (${e.javaClass.simpleName})")
                try { vision?.close() } catch (_: Exception) {}; try { text?.close() } catch (_: Exception) {}
                vision = null; text = null; tok = null; false
            }
        }
    }

    /** अंग्रेज़ी वर्णन -> 512 (unit) */
    fun embedText(c: Context, desc: String): FloatArray? {
        if (!open(c)) return null
        val ids = tok!!.encode(GalleryRank.prompt(desc))
        val x = FloatArray(ClipTokenizer.CTX * Int4Table.DIM)
        return try {
            RandomAccessFile(ModelManager.file(c, ModelCatalog.CLIP_WORDS), "r").use { f ->
                val row = ByteArray(Int4Table.ROW)
                for (p in ids.indices) {
                    f.seek(ids[p].toLong() * Int4Table.ROW); f.readFully(row)
                    Int4Table.unpack(row, 0, x, p * Int4Table.DIM)
                }
            }
            val env = OrtEnvironment.getEnvironment()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, ClipTokenizer.CTX.toLong(), Int4Table.DIM.toLong())).use { tx ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(Int4Table.eotIndex(ids).toLong())), longArrayOf(1)).use { te ->
                    text!!.run(mapOf("x" to tx, "eot" to te)).use { r -> out(r[0].value) }
                }
            }
        } catch (e: Throwable) { DKLog.d("गैलरी पहचान: वाक्य नहीं समझा (${e.javaClass.simpleName})"); null }
    }

    /** तस्वीर -> 512 (unit) */
    fun embedImage(c: Context, bmp: Bitmap): FloatArray? {
        if (!open(c)) return null
        return try {
            val p = ClipImage.plan(bmp.width, bmp.height)
            val scaled = Bitmap.createScaledBitmap(bmp, p[0], p[1], true)
            val crop = Bitmap.createBitmap(scaled, p[2], p[3], ClipImage.SIZE, ClipImage.SIZE)
            val px = IntArray(ClipImage.SIZE * ClipImage.SIZE)
            crop.getPixels(px, 0, ClipImage.SIZE, 0, 0, ClipImage.SIZE, ClipImage.SIZE)
            if (crop !== scaled) crop.recycle(); if (scaled !== bmp) scaled.recycle()
            val s = vision!!
            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(ClipImage.chw(px)),
                longArrayOf(1, 3, ClipImage.SIZE.toLong(), ClipImage.SIZE.toLong())).use { t ->
                s.run(mapOf(s.inputNames.first() to t)).use { r -> out(r[0].value) }
            }
        } catch (e: Throwable) { DKLog.d("गैलरी पहचान: फोटो नहीं समझी (${e.javaClass.simpleName})"); null }
    }

    @Suppress("UNCHECKED_CAST")
    private fun out(v: Any?): FloatArray? {
        val o = when (v) { is Array<*> -> (v as Array<FloatArray>).firstOrNull(); is FloatArray -> v; else -> null }
        return o?.takeIf { it.size == Int4Table.DIM && it.all { x -> x.isFinite() } }?.let { GalleryRank.normalize(it) }
    }

    // ------------------------------------------------------------ गैलरी
    /** सबसे नई फोटो/वीडियो (मॉडल नहीं चाहिए). wantVideo null = दोनों */
    fun recent(c: Context, wantVideo: Boolean?, days: Int = 0): List<GalleryRank.Item> {
        val out = ArrayList<GalleryRank.Item>()
        val since = if (days > 0) System.currentTimeMillis() / 1000 - days * 86_400L else 0L
        fun q(base: Uri, mimeDef: String, max: Int) {
            val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED)
            try {
                c.contentResolver.query(base, cols, if (since > 0) MediaStore.MediaColumns.DATE_ADDED + " >= ?" else null,
                    if (since > 0) arrayOf(since.toString()) else null, MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use { cur ->
                    var n = 0
                    while (cur.moveToNext() && n++ < max) {
                        val u = ContentUris.withAppendedId(base, cur.getLong(0)).toString()
                        out.add(GalleryRank.Item(u, cur.getString(2) ?: mimeDef, cur.getLong(3) * 1000, cur.getString(1).orEmpty()))
                    }
                }
            } catch (e: Exception) { DKLog.d("गैलरी पहचान: MediaStore नहीं पढ़ा ${e.javaClass.simpleName}") }
        }
        if (wantVideo != true && mediaPermission(c)) q(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/jpeg", MAX_IMAGES)
        if (wantVideo != false && videoPermission(c)) q(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/mp4", MAX_VIDEOS)
        return out.sortedByDescending { it.at }
    }

    fun latest(c: Context, wantVideo: Boolean?): SharedItem? = recent(c, wantVideo).firstOrNull()?.let { SharedItem(it.uri, it.mime, it.at, it.name) }

    @Suppress("DEPRECATION")
    fun thumb(c: Context, it: GalleryRank.Item, px: Int = 384): Bitmap? = try {
        val u = Uri.parse(it.uri)
        if (Build.VERSION.SDK_INT >= 29) c.contentResolver.loadThumbnail(u, Size(px, px), null)
        else {
            val id = ContentUris.parseId(u)
            if (it.mime.startsWith("video/")) MediaStore.Video.Thumbnails.getThumbnail(c.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
            else MediaStore.Images.Thumbnails.getThumbnail(c.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
        }
    } catch (_: Throwable) { null }

    private fun cacheFile(c: Context) = File(File(c.filesDir, "social_clip").apply { mkdirs() }, "index.bin")
    private fun loadCache(c: Context): MutableMap<String, FloatArray> =
        LinkedHashMap(try { cacheFile(c).takeIf { it.isFile }?.readBytes()?.let { GalleryRank.decodeCache(it) } ?: emptyMap() } catch (_: Exception) { emptyMap() })
    private fun saveCache(c: Context, m: Map<String, FloatArray>) = try {
        val keep = if (m.size > MAX_CACHE) m.entries.toList().takeLast(MAX_CACHE).associate { it.key to it.value } else m
        val tmp = File(cacheFile(c).path + ".tmp"); tmp.writeBytes(GalleryRank.encodeCache(keep)); tmp.renameTo(cacheFile(c))
    } catch (_: Exception) { false }

    /** जिन फोटो का embedding नहीं है, उनका बनाओ (नई पहले). return = कितनी फोटो तैयार */
    fun index(c: Context, items: List<GalleryRank.Item>, maxNew: Int, progress: (Int, Int) -> Unit = { _, _ -> }): Map<String, FloatArray> {
        val cache = loadCache(c)
        val need = items.filter { GalleryRank.key(it.uri, it.at) !in cache }.take(maxNew)
        var done = 0
        for (it in need) {
            val b = thumb(c, it) ?: continue
            embedImage(c, b)?.let { e -> cache[GalleryRank.key(it.uri, it.at)] = e }
            b.recycle()
            progress(++done, need.size)
        }
        if (need.isNotEmpty()) saveCache(c, cache)
        return cache
    }

    /** Settings बटन: पूरी गैलरी (सीमा तक) पहले से तैयार */
    fun warm(c: Context, progress: (Int, Int) -> Unit): Int {
        if (!open(c)) return -1
        val items = recent(c, null)
        index(c, items, items.size, progress)
        return items.size
    }

    /** "समुद्र किनारे वाली फोटो" (अंग्रेज़ी में) -> सबसे सही फोटो/वीडियो */
    fun find(c: Context, desc: String, wantVideo: Boolean?, days: Int = 0): Result {
        if (!supported) return Result.Ask("clip_unsupported", "यह फ़ोन (64-bit ARM नहीं) गैलरी-पहचान मॉडल नहीं चला सकता. गैलरी में फ़ाइल खोलकर Share -> DK करें")
        if (!modelsReady(c)) return Result.Ask("clip_model_missing", "गैलरी-पहचान मॉडल अभी डाउनलोड नहीं है - Settings -> सोशल -> सोशल पोस्ट स्पेशलिस्ट में \"मॉडल डाउनलोड करो\" दबाएँ")
        if (!mediaPermission(c)) return Result.Ask("gallery_permission", "गैलरी देखने की इजाज़त नहीं है - Settings -> सोशल -> सोशल पोस्ट स्पेशलिस्ट में \"गैलरी की इजाज़त दो\" दबाएँ")
        val q = embedText(c, desc) ?: return Result.Ask("clip_failed", "गैलरी-पहचान मॉडल नहीं चल पाया" + (broken?.let { " ($it)" } ?: ""))
        val items = recent(c, wantVideo, days)
        if (items.isEmpty()) return Result.Ask("gallery_empty", if (wantVideo == true) "गैलरी में कोई वीडियो नहीं मिला" else "गैलरी में कोई फोटो नहीं मिली")
        val cache = index(c, items, MAX_NEW_PER_SEARCH)
        val pairs = items.mapNotNull { i -> cache[GalleryRank.key(i.uri, i.at)]?.let { i to it } }
        val hit = GalleryRank.best(q, pairs)
        DKLog.d("गैलरी पहचान: ${items.size} में से ${pairs.size} देखीं, सबसे अच्छा ${GalleryRank.rank(q, pairs).firstOrNull()?.score}")
        return if (hit == null) Result.Ask("media_not_found", "\"$desc\" जैसी कोई ${if (wantVideo == true) "वीडियो" else "फोटो"} गैलरी में पक्की नहीं मिली" +
            (if (pairs.size < items.size) " (अभी ${pairs.size}/${items.size} देखीं - Settings में \"गैलरी पहचान तैयार करो\" दबाकर फिर कहें)" else "") + ". थोड़ा और साफ़ बताइए")
        else Result.Found(SharedItem(hit.item.uri, hit.item.mime, hit.item.at, hit.item.name), hit.score)
    }
}
