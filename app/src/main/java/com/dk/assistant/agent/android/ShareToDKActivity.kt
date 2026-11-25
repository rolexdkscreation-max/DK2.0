package com.dk.assistant.agent.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.DKState
import com.dk.assistant.UiLang
import com.dk.assistant.agent.social.MediaPolicy
import kotlin.concurrent.thread

/**
 * गैलरी -> Share -> "DK को दें": फ़ोटो/वीडियो DK के पास, फिर बोलो "इसे Instagram पर डाल दो".
 * 25 नवंबर 2026 (मालिक: "10 फोटो भेजूँ तो सब DK को पता हो"): गैलरी में कई चुनकर share (ACTION_SEND_MULTIPLE) भी -
 * सब एक batch में, फिर "जो भेजा वो सब Instagram पर डाल दो" = media all_shared.
 */
class ShareToDKActivity : Activity() {
    /** 23 नवंबर 2026 (मालिक): टैबलेट पर पूरा UI स्क्रीन के अनुपात में बड़ा; फ़ोन पर कुछ नहीं बदलता ([UiFit.tablet]) */
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(com.dk.assistant.UiFit.tablet(newBase)) }

    @Suppress("DEPRECATION")
    private fun streams(i: Intent): List<Uri> = when (i.action) {
        Intent.ACTION_SEND -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> {
            val list: List<Uri>? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            // कुछ गैलरी सिर्फ़ ClipData में देती हैं
            list?.filterNotNull()?.takeIf { it.isNotEmpty() }
                ?: i.clipData?.let { cd -> (0 until cd.itemCount).mapNotNull { cd.getItemAt(it)?.uri } }.orEmpty()
        }
        else -> emptyList()
    }.distinct()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        val uris = try { i?.let { streams(it) }.orEmpty() } catch (e: Exception) { DKLog.d("Share->DK: पढ़ा नहीं ${e.javaClass.simpleName}"); emptyList() }
        if (uris.isEmpty()) { finish(); return }
        val type = i?.type.orEmpty()
        val app = applicationContext
        val cr = contentResolver
        val many = uris.size > 1
        thread(name = "dk-share-in") {
            val pairs = uris.map { u -> u to (try { cr.getType(u) } catch (_: Exception) { null } ?: type) }
            val got = SharedMedia.addMany(app, pairs)
            val skipped = uris.size.coerceAtMost(SharedMedia.MAX_AT_ONCE) - got.size
            val extra = uris.size - SharedMedia.MAX_AT_ONCE
            DKLog.d("Share->DK: ${uris.size} आईं, ${got.size} रखीं")
            if (got.isNotEmpty()) tellLive(got.size, MediaPolicy.countLabel(got))
            runOnUiThread {
                val msg = when {
                    got.isEmpty() -> "यह फ़ाइल DK नहीं ले पाया (सिर्फ़ फोटो/वीडियो)"
                    !many -> "DK को मिल गया - अब बोलिए: \"इसे Instagram पर पोस्ट कर दो\""
                    else -> "DK को ${MediaPolicy.countLabel(got)} मिल गईं" +
                        (if (skipped > 0) " ($skipped नहीं ले पाया)" else "") +
                        (if (extra > 0) " - एक बार में ${SharedMedia.MAX_AT_ONCE} तक" else "") +
                        " - अब बोलिए: \"ये सब Instagram पर डाल दो\""
                }
                Toast.makeText(app, UiLang.s(msg), Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    /** DK लाइव चालू हो तो उसे भी पता चले कि अभी क्या-क्या मिला (वह गिनती सही बोले, बाद में media=all_shared) */
    private fun tellLive(n: Int, label: String) {
        if (!DKState.live) return
        val svc = DKAccessibilityService.instance ?: return
        try {
            svc.gemLive.visionNote("SYSTEM NOTE: the user just shared $n item(s) to DK from his gallery ($label). " +
                (if (n > 1) "When he asks to post/put them (Instagram, Facebook, YouTube, WhatsApp status), call social_media_task with media='all_shared' (all of these together). " else "Use media='latest_shared' for it. ") +
                "Briefly tell him in his language how many you got and ask what to do only if he has not said it yet. Never say anything was posted before the task SYSTEM NOTE says completed.", evidence = false)
        } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
    }
}
