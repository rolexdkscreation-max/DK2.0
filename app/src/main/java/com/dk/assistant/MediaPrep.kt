package com.dk.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * कोडिंग एजेंट के लिए चुनी गई फ़ोटो / वीडियो तैयार करना (background thread पर चलाएँ).
 * फ़ोटो: लंबी साइड ≤ 1600px, JPEG. वीडियो: बराबर दूरी के 8 frames (≤ 1024px) + छोटा हो तो पूरा वीडियो (Gemini पूरा देख सके).
 */
object MediaPrep {
    private const val PHOTO_SIDE = 1600
    private const val FRAME_SIDE = 1024
    private const val THUMB_SIDE = 160

    class Prepared(val media: CoderMedia, val thumb: Bitmap?)

    fun prepare(c: Context, uri: Uri, n: Int): Prepared? {
        val mime = c.contentResolver.getType(uri).orEmpty()
        return if (mime.startsWith("video/")) video(c, uri, mime, n) else photo(c, uri, n)
    }

    private fun photo(c: Context, uri: Uri, n: Int): Prepared? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_SIDE) sample *= 2
        val raw = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val bmp = scale(raw, PHOTO_SIDE)
        val m = CoderMedia(CoderMedia.IMAGE, "photo $n (${bmp.width}x${bmp.height})", listOf(jpeg(bmp, 85)))
        val t = scale(bmp, THUMB_SIDE)
        if (bmp !== raw) raw.recycle()
        return Prepared(m, t)
    }

    private fun video(c: Context, uri: Uri, mime: String, n: Int): Prepared? {
        val mr = MediaMetadataRetriever()
        try {
            mr.setDataSource(c, uri)
            val dur = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var thumb: Bitmap? = null
            val frames = CoderMedia.frameTimes(dur).mapNotNull { ms ->
                val f = mr.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@mapNotNull null
                val s = scale(f, FRAME_SIDE)
                if (thumb == null) thumb = scale(s, THUMB_SIDE)
                jpeg(s, 80).also { if (s !== f) s.recycle(); f.recycle() }
            }
            if (frames.isEmpty()) return null
            // छोटा वीडियो पूरा भी (आवाज़/हरकत समेत - सिर्फ़ Gemini देख सकता है)
            val size = try { c.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L } catch (_: Exception) { -1L }
            val full = if (size in 1..CoderMedia.VIDEO_INLINE_MAX.toLong())
                c.contentResolver.openInputStream(uri)?.use { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) } else null
            val m = CoderMedia(CoderMedia.VIDEO, "video $n", frames, full, mime.ifBlank { "video/mp4" }, dur)
            return Prepared(m, thumb)
        } finally {
            try { mr.release() } catch (_: Exception) {}
        }
    }

    private fun scale(b: Bitmap, side: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= side) return b
        val r = side.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * r).toInt().coerceAtLeast(1), (b.height * r).toInt().coerceAtLeast(1), true)
    }

    private fun jpeg(b: Bitmap, q: Int): String {
        val o = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, q, o)
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
    }
}
