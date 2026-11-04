package com.dk.assistant.agent.android

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import com.dk.assistant.DKLog
import com.dk.assistant.agent.social.FileMatch
import com.dk.assistant.agent.social.MediaPolicy
import com.dk.assistant.agent.social.SharedItem
import java.io.File

/**
 * नवंबर 2026: WhatsApp पर भेजने के लिए फ़ोन में फ़ाइल ढूँढो - DK की अपनी बनाई (वेबसाइट / गेम / DK कंप्यूटर की फ़ाइलें),
 * गैलरी (फोटो / वीडियो) और "सभी फ़ाइलें" इजाज़त हो तो Download / Documents वगैरह की हर फ़ाइल. नाम मिलाना [FileMatch] में.
 * सिर्फ़ पढ़ना - कोई फ़ाइल बदली / हटाई नहीं जाती.
 */
object FileFinder {
    sealed class Result {
        data class Found(val item: SharedItem) : Result()
        data class Ask(val code: String, val msg: String) : Result()
    }

    private const val MAX_ROWS = 4000

    fun allFilesAccess(): Boolean = Build.VERSION.SDK_INT < 30 || try { Environment.isExternalStorageManager() } catch (_: Throwable) { false }

    /** IO thread पर बुलाओ */
    fun find(c: Context, raw: String): Result {
        val r = raw.trim()
        if (r.equals("latest_shared", true)) {
            return MediaPolicy.resolve("latest_shared", SharedMedia.all(c), null)?.let { Result.Found(it.copy(name = it.name.ifBlank { "shared" })) }
                ?: Result.Ask("file_not_found", "DK को अभी कोई फ़ाइल share नहीं की गई है")
        }
        val all = own(c) + store(c)
        val hits = FileMatch.best(r, all).distinctBy { it.name + "|" + it.at / 1000 }
        DKLog.d("फ़ाइल खोज: ${all.size} फ़ाइलें देखीं, ${hits.size} मिलीं")
        return when {
            hits.size == 1 -> Result.Found(SharedItem(hits[0].uri, hits[0].mime, hits[0].at, hits[0].name))
            hits.size > 1 -> Result.Ask("file_ambiguous", FileMatch.askWhich(hits))
            !allFilesAccess() -> {
                askAllFiles(c)
                Result.Ask("file_permission", "फ़ोन की सारी फ़ाइलें देखने की इजाज़त नहीं है - इसलिए \"$r\" नहीं मिली। मैंने इजाज़त वाली स्क्रीन खोल दी है: " +
                    "DK के आगे \"सभी फ़ाइलें / All files access\" चालू कीजिए, फिर दोबारा बोलिए।")
            }
            else -> Result.Ask("file_not_found", "\"$r\" नाम की कोई फ़ाइल फ़ोन में नहीं मिली। फ़ाइल का नाम थोड़ा और साफ़ बताइए।")
        }
    }

    private fun askAllFiles(c: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            c.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try { c.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    /** DK की अपनी फ़ाइलें: Websites (वेबसाइट / गेम), DK कंप्यूटर (computer/), share की गई फ़ोटो */
    private fun own(c: Context): List<FileMatch.Cand> {
        val dirs = listOfNotNull(File(c.filesDir, "Websites"), File(c.filesDir, "computer"), c.getExternalFilesDir(null)?.let { File(it, "computer") })
        val out = ArrayList<FileMatch.Cand>()
        for (d in dirs) d.walkTopDown().maxDepth(4).filter { it.isFile }.take(500).forEach { f ->
            try {
                val uri = FileProvider.getUriForFile(c, c.packageName + ".files", f).toString()
                out.add(FileMatch.Cand(f.name, mimeOf(f.name), uri, f.lastModified()))
            } catch (_: Exception) {}
        }
        return out
    }

    /** MediaStore: गैलरी हमेशा (इजाज़त हो तो), सभी फ़ाइलें वाली इजाज़त हो तो Download / Documents भी */
    private fun store(c: Context): List<FileMatch.Cand> {
        val out = ArrayList<FileMatch.Cand>()
        val uri = MediaStore.Files.getContentUri("external")
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.DATA)
        try {
            c.contentResolver.query(uri, cols, null, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")?.use { cur ->
                var n = 0
                while (cur.moveToNext() && n++ < MAX_ROWS) {
                    val id = cur.getLong(0)
                    val name = cur.getString(1) ?: continue
                    val mime = cur.getString(2) ?: mimeOf(name)
                    if (mime.isBlank() || mime == "vnd.android.document/directory") continue
                    val at = cur.getLong(3) * 1000
                    val path = cur.getString(5)
                    val u = when (cur.getInt(4)) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        // PDF / दस्तावेज़: असली फ़ाइल हमारे FileProvider से (दूसरा ऐप MediaStore वाली file uri नहीं पढ़ पाता)
                        else -> path?.let { p -> File(p).takeIf { it.canRead() }?.let { f ->
                            try { FileProvider.getUriForFile(c, c.packageName + ".files", f) } catch (_: Exception) { null } } }
                            ?: ContentUris.withAppendedId(uri, id)
                    }
                    out.add(FileMatch.Cand(name, mime, u.toString(), at))
                }
            }
        } catch (e: Exception) { DKLog.d("फ़ाइल खोज: MediaStore नहीं पढ़ा ${e.javaClass.simpleName}") }
        return out
    }

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "txt", "md" -> "text/plain"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "zip" -> "application/zip"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "application/octet-stream"
    }
}
