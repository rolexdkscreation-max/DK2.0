package com.dk.assistant.agent.android

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.dk.assistant.DKLog
import com.dk.assistant.agent.social.SharedItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * यूज़र ने गैलरी से "Share -> DK" किया = वही फ़ोटो/वीडियो DK पोस्ट कर सकता है. और कोई फ़ाइल नहीं.
 * फ़ाइल DK की अपनी जगह (filesDir/shared_media) में कॉपी, आख़िरी 8 ही रखते हैं.
 */
object SharedMedia {
    private const val DIR = "shared_media"
    private const val KEEP = 8
    private const val MAX_BYTES = 300L * 1024 * 1024

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("dk_shared_media", Context.MODE_PRIVATE)

    fun all(c: Context): List<SharedItem> = try {
        val a = JSONArray(prefs(c).getString("items", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { SharedItem(it.getString("uri"), it.getString("mime"), it.getLong("at")) }
    } catch (_: Exception) { emptyList() }

    /** IO thread पर बुलाओ */
    fun add(c: Context, src: Uri, mime: String): SharedItem? {
        if (!(mime.startsWith("image/") || mime.startsWith("video/"))) return null
        val dir = File(c.filesDir, DIR).apply { mkdirs() }
        val ext = when { mime.contains("png") -> "png"; mime.contains("webp") -> "webp"; mime.startsWith("video/") -> "mp4"; else -> "jpg" }
        val f = File(dir, "dk_${System.currentTimeMillis()}.$ext")
        try {
            var n = 0L
            c.contentResolver.openInputStream(src)?.use { inp -> f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) { val r = inp.read(buf); if (r < 0) break; n += r; if (n > MAX_BYTES) error("बहुत बड़ी फ़ाइल"); out.write(buf, 0, r) }
            } } ?: return null
        } catch (e: Exception) { f.delete(); DKLog.d("Share->DK: कॉपी नहीं हुई ${e.javaClass.simpleName}"); return null }
        val uri = FileProvider.getUriForFile(c, c.packageName + ".files", f).toString()
        val item = SharedItem(uri, mime, System.currentTimeMillis())
        val list = (listOf(item) + all(c)).take(KEEP)
        // पुरानी फ़ाइलें हटाओ
        val keepNames = list.map { Uri.parse(it.uri).lastPathSegment }
        dir.listFiles()?.filter { it.name !in keepNames }?.forEach { it.delete() }
        prefs(c).edit().putString("items", JSONArray().apply { list.forEach { put(JSONObject().put("uri", it.uri).put("mime", it.mime).put("at", it.at)) } }.toString()).apply()
        return item
    }
}
