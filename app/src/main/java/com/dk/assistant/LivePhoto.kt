package com.dk.assistant

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 14 नवंबर 2026 (मालिक: "फोटो डालूँ तो DK लाइव बोलता है मेरे पास फोटो पढ़ने की क्षमता नहीं, कोई टूल नहीं").
 * जड़: Live के पास फोटो देखने का कोई tool नहीं था (सिर्फ़ स्क्रीन / कैमरा). अब look_photo:
 *  1) DKAI चैट में + से जोड़ी आख़िरी फोटो ([DkaiChatFiles.remember], 30 मिनट तक), वरना
 *  2) गैलरी की सबसे नई फोटो (फोटो की इजाज़त हो तो)
 * -> Gemini vision (DK लाइव / चैट वाली Gemini key) -> साफ़ जवाब. फोटो न मिले = सच बताओ, अंदाज़ा नहीं.
 */
object LivePhoto {
    const val DESC = "Look at a PHOTO and answer about it: the photo the user just attached in the DK chat (+ button), " +
        "or else the newest photo in his gallery. Use it whenever the user says फोटो देखो / फोटो पढ़ो / इस फोटो में क्या है / " +
        "ये तस्वीर पढ़ो / photo में क्या लिखा है / I sent a photo. You CAN see photos with this tool - never say you cannot read photos. " +
        "Tell ONLY what the result says; never invent."
    /** चैट में जोड़ी फोटो इतनी देर तक \"अभी वाली\" मानी जाए */
    const val FRESH_MS = 30 * 60_000L
    const val MAX_SIDE = 1024

    /** कौन सी फोटो देखें (pure, JUnit) */
    enum class From { CHAT, GALLERY, NONE }
    fun pick(chatAt: Long, hasChat: Boolean, now: Long, galleryOk: Boolean): From = when {
        hasChat && now - chatAt in 0..FRESH_MS -> From.CHAT
        galleryOk -> From.GALLERY
        else -> From.NONE
    }

    /** फोटो साथ हो तो फोटो समझने वाले पहले, बाकी क्रम वही (pure) */
    fun <T> visionFirst(list: List<T>, isVision: (T) -> Boolean): List<T> = list.filter(isVision) + list.filterNot(isVision)

    fun prompt(question: String, from: From): String =
        "The user (Hindi/English) asked about a photo" + (if (from == From.CHAT) " he attached in the chat" else " (the newest photo in his gallery)") +
            ": \"" + question.take(300) + "\"\nLook carefully at the attached image. Describe what is really visible and read any text in it exactly. " +
            "Answer his question from the image only; if something is not visible say \"नहीं दिख रहा\" - never guess. " +
            "Reply in simple Hindi (Devanagari), short spoken sentences, no markdown, max 120 words."

    suspend fun liveTool(c: Context, question: String): String = withContext(Dispatchers.IO) {
        val chat = com.dk.assistant.dkai.DkaiChatFiles.lastImage
        val from = pick(com.dk.assistant.dkai.DkaiChatFiles.lastImageAt, chat != null, System.currentTimeMillis(), true)
        val img = when (from) {
            From.CHAT -> chat
            else -> try { latestGallery(c) } catch (e: Exception) { DKLog.d("फोटो देखना: गैलरी नहीं पढ़ी ${e.javaClass.simpleName}"); null }
        }
        val src = if (img != null && from == From.CHAT) From.CHAT else if (img != null) From.GALLERY else From.NONE
        if (img == null) return@withContext "NO PHOTO FOUND: no photo was attached in the DK chat recently and the gallery could not be read " +
            "(photo permission may be off). Tell the user to attach the photo with the + button in the DK chat (or allow photo access) and ask again. Do NOT say you cannot see photos."
        val keys = (Prefs.liveGeminiKeys(c) + Prefs.key(c, "gemini")).filter { it.isNotBlank() }.distinct()
        if (keys.isEmpty()) return@withContext "PHOTO FOUND but no Gemini key is saved for vision. Tell the user to add a Gemini key in Settings."
        DKLog.d("फोटो देखना: ${if (src == From.CHAT) "चैट में जोड़ी फोटो" else "गैलरी की नई फोटो"}")
        val ans = ScreenLook.vision(keys, prompt(question, src), img)
            ?: return@withContext "COULD NOT SEE: the vision model did not answer. Tell the user honestly and ask him to try again."
        "PHOTO SEEN JUST NOW (" + (if (src == From.CHAT) "photo attached in chat" else "newest gallery photo") + "). Tell the user exactly this, briefly, in his language:\n" + ans
    }

    private fun latestGallery(c: Context): String? {
        val cr = c.contentResolver
        val uri: Uri = c.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")?.use { q ->
            if (q.moveToFirst()) ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, q.getLong(0)) else null
        } ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (o.outWidth / (s * 2) >= MAX_SIDE && o.outHeight / (s * 2) >= MAX_SIDE) s *= 2
        val b = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) } ?: return null
        val bo = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 82, bo); b.recycle()
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
    }
}

/** 14 नवंबर 2026: DK Live का deep_research tool - विवरण + नतीजे की लाइन (pure, JUnit) */
object LiveDeep {
    /** डीप रिसर्च (कई वेबसाइट + पूरे पेज) ज़्यादा से ज़्यादा इतनी देर; फिर साधारण ताज़ा खोज */
    const val TIMEOUT_MS = 150_000L
    const val DESC = "DEEP RESEARCH: searches many websites (Exa, Tavily, Linkup), reads 8-15 full pages (Firecrawl), keeps only facts that " +
        "at least 2 different websites confirm, and returns a Hindi report with dates and sources. Use it when the user says डीप रिसर्च / " +
        "गहरी खोज / पूरी रिसर्च करो / research karo, or asks something that needs many websites joined together (comparison, full latest " +
        "details). Takes 30-120 seconds - first say one short line like 'डीप रिसर्च कर रहा हूँ, थोड़ा समय लगेगा'. For a quick fact use web_search."

    fun result(report: String, deep: Boolean): String =
        (if (deep) "DEEP RESEARCH REPORT (many websites, full pages read, cross-checked): " else
            "SEARCH RESULT (deep research could not run - this is from a normal Google search; tell the user that briefly): ") +
            WebCite.strip(report).take(3400) +
            "\nSpeak the main answer from this report now in short natural Hindi (2-4 sentences + the confidence line). Mention the dates. " +
            "Do not read URLs; you may name the websites."
}
