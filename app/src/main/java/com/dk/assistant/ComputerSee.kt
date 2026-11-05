package com.dk.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * v5 मालिक: DK कंप्यूटर "देख" भी सके - फ़ोटो, स्क्रीनशॉट, PDF के पन्ने, वेब की तस्वीर (look टूल).
 * तस्वीर छोटी करके JPEG में AI (Gemini / vision मॉडल) को भेजते हैं.
 */
object ComputerSee {
    const val MAX_SIDE = 1400
    const val PDF_PAGES = 6

    private val http by lazy { NetFix.builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }

    /** लंबी तरफ़ max px तक छोटा (अनुपात वही) */
    fun fit(w: Int, h: Int, max: Int = MAX_SIDE): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 0 to 0
        val s = minOf(1.0, max.toDouble() / maxOf(w, h))
        return maxOf(1, (w * s).toInt()) to maxOf(1, (h * s).toInt())
    }

    /** PDF के कौन-से पन्ने: "3" / "2-5" / खाली = शुरू के [PDF_PAGES] */
    fun pages(spec: String?, count: Int): List<Int> {
        if (count <= 0) return emptyList()
        val s = spec?.trim().orEmpty()
        val m = Regex("^(\\d+)\\s*-\\s*(\\d+)$").find(s)
        val r = when {
            m != null -> (m.groupValues[1].toInt()..m.groupValues[2].toInt())
            s.toIntOrNull() != null -> s.toInt()..s.toInt()
            else -> 1..PDF_PAGES
        }
        return r.map { it - 1 }.filter { it in 0 until count }.take(PDF_PAGES)
    }

    private fun jpeg(b: Bitmap): String {
        val (w, h) = fit(b.width, b.height)
        val s = if (w != b.width || h != b.height) Bitmap.createScaledBitmap(b, w, h, true) else b
        val bo = ByteArrayOutputStream(); s.compress(Bitmap.CompressFormat.JPEG, 82, bo)
        if (s !== b) s.recycle()
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun pdf(f: File, spec: String?): List<String> {
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(pfd).use { r ->
            return pages(spec, r.pageCount).map { i ->
                r.openPage(i).use { p ->
                    val (w, h) = fit(p.width * 2, p.height * 2)
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); b.eraseColor(Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    jpeg(b).also { b.recycle() }
                }
            }
        }
    }

    /** file (DK कंप्यूटर फ़ोल्डर में) या url -> AI को भेजने लायक तस्वीरें */
    fun media(c: Context, file: String?, url: String?, pageSpec: String?): Pair<CoderMedia?, String> {
        if (!file.isNullOrBlank()) {
            val f = File(ComputerTools.dir(c), ComputerTools.safeName(file))
            if (!f.exists()) return null to "ERROR: ${f.name} नहीं मिली। मौजूद: " + ComputerTools.list(c).joinToString { it.name }.take(400)
            return when (f.extension.lowercase()) {
                "pdf" -> { val fr = pdf(f, pageSpec); CoderMedia(CoderMedia.IMAGE, f.name, fr) to "PDF ${f.name}: ${fr.size} पन्ने" }
                "png", "jpg", "jpeg", "webp", "gif", "bmp" -> {
                    val b = decode(f.readBytes()) ?: return null to "ERROR: तस्वीर खुली नहीं"
                    CoderMedia(CoderMedia.IMAGE, f.name, listOf(jpeg(b).also { b.recycle() })) to "PHOTO ${f.name}"
                }
                else -> null to "ERROR: look सिर्फ़ फ़ोटो/PDF देखता है - text फ़ाइल के लिए read_file"
            }
        }
        if (!url.isNullOrBlank()) {
            if (!url.startsWith("http")) return null to "ERROR: url http(s) से शुरू हो"
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) DK-Computer").build()
            val bytes = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null to "ERROR: HTTP ${r.code}"
                val len = r.body?.contentLength() ?: -1
                if (len > 15L * 1024 * 1024) return null to "ERROR: फ़ाइल बहुत बड़ी"
                r.body?.bytes() ?: ByteArray(0)
            }
            if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "%PDF") {
                val tmp = File(c.cacheDir, "look.pdf").apply { writeBytes(bytes) }
                return try { val fr = pdf(tmp, pageSpec); CoderMedia(CoderMedia.IMAGE, "pdf", fr) to "PDF: ${fr.size} पन्ने" } finally { tmp.delete() }
            }
            val b = decode(bytes) ?: return null to "ERROR: यह तस्वीर/PDF नहीं है (पेज के लिए read_url / browse)"
            return CoderMedia(CoderMedia.IMAGE, url.takeLast(40), listOf(jpeg(b).also { b.recycle() })) to "PHOTO from url"
        }
        return null to "ERROR: look को file या url चाहिए"
    }
}
