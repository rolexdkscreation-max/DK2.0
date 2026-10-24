package com.dk.assistant.agent.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.dk.assistant.UiLang
import kotlin.concurrent.thread

/** गैलरी -> Share -> "DK को दें": फ़ोटो/वीडियो DK के पास, फिर बोलो "इसे Instagram पर डाल दो" */
class ShareToDKActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        @Suppress("DEPRECATION")
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) i?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i?.getParcelableExtra(Intent.EXTRA_STREAM)
        val mime = i?.type ?: uri?.let { contentResolver.getType(it) } ?: ""
        if (i?.action != Intent.ACTION_SEND || uri == null) { finish(); return }
        val app = applicationContext
        thread(name = "dk-share-in") {
            val item = SharedMedia.add(app, uri, contentResolver.getType(uri) ?: mime)
            runOnUiThread {
                Toast.makeText(app, UiLang.s(if (item != null) "DK को मिल गया - अब बोलिए: \"इसे Instagram पर पोस्ट कर दो\"" else "यह फ़ाइल DK नहीं ले पाया (सिर्फ़ फोटो/वीडियो)"), Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
}
