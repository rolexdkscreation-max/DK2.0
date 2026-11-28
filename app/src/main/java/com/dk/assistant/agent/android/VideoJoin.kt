package com.dk.assistant.agent.android

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.dk.assistant.DKLog
import java.nio.ByteBuffer

/**
 * 28 नवंबर 2026 (DK स्टूडियो): 8-8 सेकंड के भाग -> एक वीडियो, फ़ोन पर ही (कोई सर्वर / ऐप नहीं).
 * MP4 के टुकड़े बिना दोबारा encode किए जोड़ता है (MediaExtractor + MediaMuxer). सारे भाग एक ही ऐप से = एक जैसा format.
 * format अलग / Android 10 से पुराना = null (भाग अलग-अलग गैलरी में रहते हैं - कुछ नहीं बिगड़ता).
 * नतीजा: गैलरी -> Movies/DK.
 */
object VideoJoin {
    private const val BUF = 4 * 1024 * 1024

    private class Tracks(val video: Int, val audio: Int, val vf: MediaFormat?, val af: MediaFormat?)

    private fun tracks(x: MediaExtractor): Tracks {
        var v = -1; var a = -1; var vf: MediaFormat? = null; var af: MediaFormat? = null
        for (i in 0 until x.trackCount) {
            val f = x.getTrackFormat(i); val m = f.getString(MediaFormat.KEY_MIME).orEmpty()
            if (v < 0 && m.startsWith("video/")) { v = i; vf = f }
            if (a < 0 && m.startsWith("audio/")) { a = i; af = f }
        }
        return Tracks(v, a, vf, af)
    }

    private fun same(a: MediaFormat?, b: MediaFormat?): Boolean {
        if (a == null || b == null) return a == null && b == null
        if (a.getString(MediaFormat.KEY_MIME) != b.getString(MediaFormat.KEY_MIME)) return false
        val k = if (a.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")) listOf(MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT)
            else listOf(MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT)
        return k.all { (if (a.containsKey(it)) a.getInteger(it) else -1) == (if (b.containsKey(it)) b.getInteger(it) else -1) }
    }

    /** भाग जोड़कर एक MP4 (गैलरी में). न हो पाए = null */
    fun join(c: Context, parts: List<Uri>, title: String): Uri? {
        if (parts.size < 2 || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        // पहले सब जाँचो - format एक जैसा?
        val t0 = open(c, parts[0])?.let { x -> tracks(x).also { x.release() } } ?: return null
        if (t0.video < 0) return null
        for (p in parts.drop(1)) {
            val x = open(c, p) ?: return null
            val t = tracks(x); x.release()
            if (!same(t0.vf, t.vf)) { DKLog.d("VideoJoin: भागों का video format अलग - नहीं जोड़ा"); return null }
        }
        val name = "DK_" + title.replace(Regex("[^\\p{L}\\p{N}]+"), "_").take(40).trim('_') + "_" + System.currentTimeMillis() / 1000 + ".mp4"
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DK")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val out = try { c.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) } catch (_: Exception) { null } ?: return null
        var ok = false
        try {
            c.contentResolver.openFileDescriptor(out, "rw")?.use { pfd ->
                val mux = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                rotation(c, parts[0])?.let { mux.setOrientationHint(it) }
                val mv = mux.addTrack(t0.vf!!)
                val ma = if (t0.af != null) mux.addTrack(t0.af) else -1
                mux.start()
                val buf = ByteBuffer.allocate(BUF)
                val info = MediaCodec.BufferInfo()
                var offset = 0L
                for (p in parts) {
                    val x = open(c, p) ?: continue
                    val t = tracks(x)
                    var end = offset
                    if (t.video >= 0) x.selectTrack(t.video)
                    val audioOk = ma >= 0 && t.audio >= 0 && same(t0.af, t.af)
                    if (audioOk) x.selectTrack(t.audio)
                    while (true) {
                        info.size = x.readSampleData(buf, 0)
                        if (info.size < 0) break
                        val tr = x.sampleTrackIndex
                        info.offset = 0
                        info.presentationTimeUs = offset + x.sampleTime
                        @Suppress("WrongConstant")
                        info.flags = if (x.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        val to = if (tr == t.video) mv else if (audioOk && tr == t.audio) ma else -1
                        if (to >= 0) { mux.writeSampleData(to, buf, info); if (info.presentationTimeUs > end) end = info.presentationTimeUs }
                        x.advance()
                    }
                    x.release()
                    offset = end + 33_333           // एक frame का फ़ासला (30fps)
                }
                mux.stop(); mux.release()
                ok = offset > 0
            }
        } catch (e: Exception) { DKLog.d("VideoJoin गड़बड़: ${e.javaClass.simpleName} ${e.message}") }
        return try {
            if (ok) { c.contentResolver.update(out, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null); out }
            else { c.contentResolver.delete(out, null, null); null }
        } catch (_: Exception) { null }
    }

    private fun open(c: Context, u: Uri): MediaExtractor? = try { MediaExtractor().apply { setDataSource(c, u, null) } } catch (_: Exception) { null }

    private fun rotation(c: Context, u: Uri): Int? = try {
        val r = MediaMetadataRetriever()
        try { r.setDataSource(c, u); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?.takeIf { it != 0 } } finally { r.release() }
    } catch (_: Exception) { null }
}
