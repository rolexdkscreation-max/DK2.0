package com.dk.assistant

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 15 नवंबर 2026 (मालिक): "टच मोड जो फ़ोटो खींचता है वो ऐप में दिखती नहीं, सिर्फ़ टाइम दिखता है. फ़ोटो दिखनी चाहिए - कितनी भी बार
 * खींची हो, किसी की भी हो - और अच्छे से क्लिक करके अच्छी फ़ोटो रहे".
 *
 * - Settings → टच मोड → "घुसपैठिए का रिकॉर्ड": सारी फ़ोटो की पट्टी (नई पहले), हर एक के नीचे तारीख-समय.
 * - छूने पर बड़ी फ़ोटो: ← / → से बाकी, शेयर, गैलरी में सेव, मिटाएँ.
 * - अच्छी फ़ोटो: असली कैमरा फ़ोटो ([FaceCam.snap], पूरा resolution); न हो सके तो पहचान वाला फ़्रेम, पर धुँधला (हिलता) फ़्रेम
 *   छोड़कर ([sharpness]). सेव 1600 px तक, JPEG 92.
 * फ़ोटो सिर्फ़ फ़ोन के अंदर (filesDir/guard) - ज़्यादा से ज़्यादा [GuardRules.MAX_PHOTOS].
 */
object GuardPhotos {
    const val MAX_SIDE = 1600
    const val QUALITY = 92
    /** इससे कम = धुँधला / हिलता फ़्रेम (Laplacian variance, 0-255 gray) */
    const val SHARP_MIN = 60.0
    /** इतनी देर तक साफ़ फ़्रेम न मिले तो जो है वही (फ़ोटो छूटनी नहीं चाहिए) */
    const val WAIT_SHARP_MS = 2_000L

    // ------------------------------------------------------------------ pure
    fun fitSide(w: Int, h: Int, max: Int): Pair<Int, Int> {
        val m = maxOf(w, h); if (m <= max || m <= 0) return w to h
        val s = max.toDouble() / m
        return (w * s).toInt().coerceAtLeast(1) to (h * s).toInt().coerceAtLeast(1)
    }

    /** तेज़ी (sharpness): gray पर Laplacian का variance. [px] ARGB, [w]x[h]. बड़ा = साफ़ */
    fun sharpness(px: IntArray, w: Int, h: Int): Double {
        if (w < 3 || h < 3 || px.size < w * h) return 0.0
        fun g(i: Int): Int { val p = px[i]; return (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000 }
        var sum = 0.0; var sq = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = (g(i - 1) + g(i + 1) + g(i - w) + g(i + w) - 4 * g(i)).toDouble()
            sum += l; sq += l * l; n++
        }
        if (n == 0) return 0.0
        val m = sum / n; return sq / n - m * m
    }

    /** "intruder_1731650000000.jpg" -> ms (null = नाम अलग) */
    fun timeOf(name: String): Long? = Regex("""intruder_(\d{10,})\.jpg""").matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()

    fun label(ms: Long): String = SimpleDateFormat("dd MMM, hh:mm:ss a", Locale("hi", "IN")).format(Date(ms))

    // ------------------------------------------------------------------ फ़ोन पर
    /** छोटी झलक (memory बचाकर) */
    fun thumb(f: File, side: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= side) s *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (_: Throwable) { null }

    private fun timeText(f: File) = label(timeOf(f.name) ?: f.lastModified())

    /** Settings में: सारी फ़ोटो की पट्टी. [changed] = कुछ मिटा तो (गिनती ताज़ा) */
    fun strip(a: AppCompatActivity, box: LinearLayout, changed: () -> Unit) {
        val d = a.resources.displayMetrics.density
        val files = Guard.photos(a)
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt()) }
        val sc = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = true; addView(row) }
        box.addView(sc, LinearLayout.LayoutParams(-1, -2))
        if (files.isEmpty()) {
            row.addView(TextView(a).apply { text = UiLang.s("अभी कोई फ़ोटो नहीं — टच मोड में कोई फ़ोन छुएगा तो उसकी फ़ोटो यहाँ दिखेगी"); textSize = 13f; setTextColor(0xB3FFFFFF.toInt()) })
            return
        }
        val w = (96 * d).toInt(); val h = (128 * d).toInt()
        files.forEachIndexed { i, f ->
            val iv = ImageView(a).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF111111.toInt())
                background = GradientDrawable().apply { setColor(0xFF111111.toInt()); setStroke((1 * d).toInt(), 0x6600E5FF); cornerRadius = 8 * d }
                clipToOutline = true
                contentDescription = UiLang.s("घुसपैठिए की फ़ोटो ${i + 1}")
                setOnClickListener { viewer(a, files, i) { changed() } }
            }
            val cell = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(iv, LinearLayout.LayoutParams(w, h))
                addView(TextView(a).apply { text = timeText(f); textSize = 10f; setTextColor(0xCCFFFFFF.toInt()); gravity = Gravity.CENTER; maxWidth = w })
            }
            row.addView(cell, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = (8 * d).toInt() })
            Thread { val b = thumb(f, h); a.runOnUiThread { if (b != null) iv.setImageBitmap(b) } }.apply { isDaemon = true }.start()
        }
    }

    /** बड़ी फ़ोटो: ← / →, शेयर, गैलरी में सेव, मिटाएँ */
    fun viewer(a: AppCompatActivity, start: List<File>, index: Int, changed: () -> Unit) {
        val d = a.resources.displayMetrics.density
        val files = start.toMutableList(); if (files.isEmpty()) return
        var i = index.coerceIn(0, files.size - 1)
        val wrap = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val iv = ImageView(a).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        val info = TextView(a).apply { textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt()) }
        wrap.addView(iv, LinearLayout.LayoutParams(-1, (a.resources.displayMetrics.heightPixels * 0.6f).toInt()))
        wrap.addView(info)
        fun show() {
            val f = files[i]
            info.text = UiLang.s("${i + 1} / ${files.size}  •  ${timeText(f)}")
            Thread { val b = thumb(f, 1600); a.runOnUiThread { iv.setImageBitmap(b) } }.apply { isDaemon = true }.start()
        }
        fun btn(t: String, on: () -> Unit) = Button(a).apply { text = UiLang.s(t); isAllCaps = false; setOnClickListener { on() } }
        var dlg: AlertDialog? = null
        val nav = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        nav.addView(btn("← पिछली") { if (i > 0) { i--; show() } })
        nav.addView(btn("अगली →") { if (i < files.size - 1) { i++; show() } })
        wrap.addView(nav)
        val acts = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        acts.addView(btn("शेयर") { share(a, files[i]) })
        acts.addView(btn("गैलरी में सेव") { toGallery(a, files[i]) })
        acts.addView(btn("मिटाएँ") {
            files[i].delete(); files.removeAt(i); changed()
            if (files.isEmpty()) dlg?.dismiss() else { i = i.coerceAtMost(files.size - 1); show() }
        })
        wrap.addView(acts)
        show()
        dlg = AlertDialog.Builder(a).setView(wrap).setPositiveButton(UiLang.s("बंद करें"), null).show()
    }

    private fun share(a: AppCompatActivity, f: File) = try {
        val u = androidx.core.content.FileProvider.getUriForFile(a, a.packageName + ".files", f)
        a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, u)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), UiLang.s("फ़ोटो भेजें")))
    } catch (e: Exception) { UiLang.toast(a, "शेयर नहीं हुई: ${e.message}", Toast.LENGTH_SHORT).show() }

    /** फ़ोन की गैलरी (Pictures/DK Touch Guard) में कॉपी */
    private fun toGallery(a: AppCompatActivity, f: File) {
        try {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, f.name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DK Touch Guard")
            }
            val u = a.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: throw IllegalStateException("जगह नहीं मिली")
            a.contentResolver.openOutputStream(u)?.use { o -> f.inputStream().use { it.copyTo(o) } }
            UiLang.toast(a, "गैलरी में सेव (Pictures/DK Touch Guard)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { UiLang.toast(a, "गैलरी में सेव नहीं हुई: ${e.message}", Toast.LENGTH_SHORT).show() }
    }
}
