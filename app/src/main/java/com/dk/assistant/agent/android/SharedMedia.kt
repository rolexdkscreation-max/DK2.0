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
 * फ़ाइल DK की अपनी जगह (filesDir/shared_media) में कॉपी, आख़िरी [KEEP] ही रखते हैं.
 * 25 नवंबर 2026 (मालिक: "10 फोटो भेजूँ तो सब पता हो"): गैलरी में कई चुनकर एक साथ share = एक batch (सब एक साथ पोस्ट हो सकें).
 */
object SharedMedia {
    private const val DIR = "shared_media"
    const val KEEP = 30
    /** एक बार में इतनी तक (गैलरी से 50 चुन लें तो भी) */
    const val MAX_AT_ONCE = 30
    @Volatile private var seqNo = 0
    private const val MAX_BYTES = 300L * 1024 * 1024

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("dk_shared_media", Context.MODE_PRIVATE)

    fun all(c: Context): List<SharedItem> = try {
        val a = JSONArray(prefs(c).getString("items", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { SharedItem(it.getString("uri"), it.getString("mime"), it.getLong("at"), batch = it.optLong("batch", 0L)) }
    } catch (_: Exception) { emptyList() }

    /** IO thread पर बुलाओ */
    fun add(c: Context, src: Uri, mime: String): SharedItem? = addMany(c, listOf(src to mime)).firstOrNull()

    /** IO thread पर बुलाओ. एक साथ आई सब = एक batch; जो कॉपी न हो पाई वह छोड़ दी (बाक़ी रहती हैं) */
    @Synchronized
    fun addMany(c: Context, items: List<Pair<Uri, String>>): List<SharedItem> {
        val batch = System.currentTimeMillis()
        val got = items.take(MAX_AT_ONCE).mapIndexedNotNull { i, (u, m) -> copy(c, u, m, batch + i, batch) }
        if (got.isEmpty()) return emptyList()
        val dir = File(c.filesDir, DIR)
        val list = (got.reversed() + all(c)).take(KEEP)
        // पुरानी फ़ाइलें हटाओ
        val keepNames = list.map { Uri.parse(it.uri).lastPathSegment }
        dir.listFiles()?.filter { it.name !in keepNames }?.forEach { it.delete() }
        prefs(c).edit().putString("items", JSONArray().apply { list.forEach { put(JSONObject().put("uri", it.uri).put("mime", it.mime).put("at", it.at).put("batch", it.batch)) } }.toString()).apply()
        return got
    }

    private fun copy(c: Context, src: Uri, mime: String, at: Long, batch: Long): SharedItem? {
        if (!(mime.startsWith("image/") || mime.startsWith("video/"))) return null
        val dir = File(c.filesDir, DIR).apply { mkdirs() }
        val ext = when { mime.contains("png") -> "png"; mime.contains("webp") -> "webp"; mime.startsWith("video/") -> "mp4"; else -> "jpg" }
        val f = File(dir, "dk_${at}_${++seqNo}.$ext")
        try {
            var n = 0L
            c.contentResolver.openInputStream(src)?.use { inp -> f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) { val r = inp.read(buf); if (r < 0) break; n += r; if (n > MAX_BYTES) error("बहुत बड़ी फ़ाइल"); out.write(buf, 0, r) }
            } } ?: return null
        } catch (e: Exception) { f.delete(); DKLog.d("Share->DK: कॉपी नहीं हुई ${e.javaClass.simpleName}"); return null }
        val uri = FileProvider.getUriForFile(c, c.packageName + ".files", f).toString()
        return SharedItem(uri, mime, at, batch = batch)
    }
}
