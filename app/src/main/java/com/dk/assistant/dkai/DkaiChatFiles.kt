package com.dk.assistant.dkai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 14 नवंबर 2026 (मालिक): चैट के + से जुड़ी फोटो / PDF / text फ़ाइल चैट में ही - DK Computer में नहीं.
 * फोटो (5 तक) एक साफ़ चित्र (grid) में जुड़कर फोटो पढ़ने वाले AI को; text फ़ाइल का लिखा सवाल के साथ.
 */
object DkaiChatFiles {
    const val MAX_TEXT = 6000
    const val CELL = 640
    const val PDF_PAGES = 2

    class Ready(val imageB64: String?, val names: List<String>, val texts: List<Pair<String, String>>, val images: Int)

    /** कितनी फोटो -> (कॉलम, पंक्ति) */
    fun grid(n: Int): Pair<Int, Int> = when {
        n <= 1 -> 1 to 1
        n == 2 -> 2 to 1
        else -> 2 to (n + 1) / 2
    }

    fun isText(mime: String, name: String): Boolean =
        mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") ||
            Regex("\\.(txt|md|csv|json|xml|kt|java|py|js|ts|html|css|log)$", RegexOption.IGNORE_CASE).containsMatchIn(name)

    const val SYSTEM = "तुम DK AI हो। यूज़र ने चैट में फोटो/फ़ाइल भेजी है। जो दिख रहा है / लिखा है उसी के आधार पर हिंदी में साफ़ जवाब दो। " +
        "जो फोटो/फ़ाइल में नहीं है, उसे अपने मन से मत जोड़ो। जवाब सिर्फ़ JSON में: {\"say\": \"जवाब\"}"

    /** AI को जाने वाला सवाल (फ़ाइलों के नाम + text फ़ाइलों का लिखा) */
    fun prompt(question: String, names: List<String>, texts: List<Pair<String, String>>, images: Int): String {
        val q = question.trim().ifEmpty { if (images > 0) "इस फोटो में क्या है? साफ़-साफ़ समझाइए।" else "इस फ़ाइल में क्या है? छोटा सार बताइए।" }
        val sb = StringBuilder()
        if (names.isNotEmpty()) sb.append("जुड़ी फ़ाइलें: ").append(names.joinToString(", ")).append('\n')
        if (images > 0) sb.append("साथ में ").append(images).append(" फोटो/पन्ने (एक चित्र में जुड़े) भेजे हैं।\n")
        for ((n, t) in texts) sb.append("\n--- ").append(n).append(" ---\n").append(t.take(MAX_TEXT)).append('\n')
        sb.append("\nसवाल: ").append(q)
        return sb.toString()
    }

    /** JSON {"say": …} या सीधा लिखा - दोनों से जवाब */
    fun parseSay(raw: String): String {
        val t = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (t.startsWith("{")) {
            val end = t.lastIndexOf('}')
            if (end > 0) try {
                val s = org.json.JSONObject(t.substring(0, end + 1)).optString("say")
                if (s.isNotBlank()) return s.trim()
            } catch (_: Exception) {}
            Regex("\"say\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(t)?.let { return it.groupValues[1].replace("\\n", "\n").replace("\\\"", "\"").trim() }
        }
        return t
    }

    /** IO thread पर: फ़ाइलें पढ़ो, फोटो जोड़ो */
    fun prepare(c: Context, uris: List<Uri>): Ready {
        val cr = c.contentResolver
        val bmps = ArrayList<Bitmap>()
        val names = ArrayList<String>()
        val texts = ArrayList<Pair<String, String>>()
        for (u in uris.take(DkaiHomeCore.MAX_ATTACH)) {
            val name = nameOf(c, u)
            val mime = (try { cr.getType(u) } catch (_: Exception) { null }) ?: ""
            names.add(name)
            try {
                when {
                    mime.startsWith("image/") || Regex("\\.(jpe?g|png|webp|heic|gif|bmp)$", RegexOption.IGNORE_CASE).containsMatchIn(name) ->
                        decode(c, u)?.let { bmps.add(it) }
                    mime == "application/pdf" || name.endsWith(".pdf", true) -> bmps.addAll(pdfPages(c, u))
                    isText(mime, name) -> cr.openInputStream(u)?.use { s ->
                        val b = ByteArray(MAX_TEXT * 3); val n = s.read(b).coerceAtLeast(0)
                        texts.add(name to String(b, 0, n, Charsets.UTF_8))
                    }
                }
            } catch (_: Throwable) {}
        }
        val pics = bmps.take(DkaiHomeCore.MAX_ATTACH)
        val b64 = if (pics.isEmpty()) null else try { collage(pics) } catch (_: Throwable) { null }
        bmps.forEach { try { it.recycle() } catch (_: Exception) {} }
        return Ready(b64, names, texts, pics.size)
    }

    private fun nameOf(c: Context, u: Uri): String {
        val n: String? = try {
            c.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { q ->
                if (q.moveToFirst()) q.getString(0) else null
            }
        } catch (_: Exception) { null }
        return n ?: (u.lastPathSegment ?: "file")
    }

    private fun decode(c: Context, u: Uri): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (o.outWidth / (s * 2) >= CELL * 2 && o.outHeight / (s * 2) >= CELL * 2) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        return c.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o2) }
    }

    private fun pdfPages(c: Context, u: Uri): List<Bitmap> {
        // PdfRenderer को seek होने वाली फ़ाइल चाहिए - cache में कॉपी
        val f = File(c.cacheDir, "dkai_chat.pdf")
        c.contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { i.copyTo(it) } } ?: return emptyList()
        val out = ArrayList<Bitmap>()
        android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                for (i in 0 until minOf(PDF_PAGES, r.pageCount)) r.openPage(i).use { p ->
                    val w = CELL * 2; val h = (w.toFloat() * p.height / p.width.coerceAtLeast(1)).toInt().coerceIn(1, w * 2)
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    out.add(b)
                }
            }
        }
        try { f.delete() } catch (_: Exception) {}
        return out
    }

    /** कई फोटो -> एक JPEG (base64) */
    private fun collage(list: List<Bitmap>): String {
        val (cols, rows) = grid(list.size)
        val cell = if (list.size == 1) CELL * 2 else CELL
        val out = Bitmap.createBitmap(cols * cell, rows * cell, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out); cv.drawColor(Color.WHITE)
        list.forEachIndexed { i, b ->
            val x = (i % cols) * cell; val y = (i / cols) * cell
            val sc = minOf(cell.toFloat() / b.width, cell.toFloat() / b.height)
            val w = (b.width * sc).toInt(); val h = (b.height * sc).toInt()
            val dst = android.graphics.Rect(x + (cell - w) / 2, y + (cell - h) / 2, x + (cell - w) / 2 + w, y + (cell - h) / 2 + h)
            cv.drawBitmap(b, null, dst, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        val bo = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 82, bo)
        out.recycle()
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
    }
}
