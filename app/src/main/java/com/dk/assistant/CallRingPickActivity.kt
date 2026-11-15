package com.dk.assistant

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings

/**
 * 15 नवंबर 2026 (मालिक: "जो रिंग बजना चाहिए, वो सेटिंग में"): फ़ोन की रिंगटोन चुनो (Android की अपनी सूची).
 * चुनी रिंग फ़ोन की रिंगटोन बन जाती है - इसके लिए "सिस्टम सेटिंग बदलें" इजाज़त (एक बार) चाहिए.
 * पारदर्शी, बिना UI.
 */
class CallRingPickActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (b != null) return
        if (Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(this)) {
            UiLang.toast(this, "पहले \"सिस्टम सेटिंग बदलें\" की इजाज़त दें, फिर दोबारा रिंग चुनें", android.widget.Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            finish(); return
        }
        val cur = try { RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE) } catch (_: Exception) { null }
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "DK कॉलिंग: रिंग चुनें")
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, cur)
        try { @Suppress("DEPRECATION") startActivityForResult(i, 7) } catch (_: Exception) {
            UiLang.toast(this, "इस फ़ोन में रिंग चुनने की सूची नहीं खुली", android.widget.Toast.LENGTH_SHORT).show(); finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7 && resultCode == RESULT_OK) {
            @Suppress("DEPRECATION") val u: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (u != null) try {
                RingtoneManager.setActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE, u)
                val name = try { RingtoneManager.getRingtone(this, u)?.getTitle(this).orEmpty() } catch (_: Exception) { "" }
                UiLang.toast(this, "रिंग बदल दी${if (name.isNotBlank()) ": $name" else ""}", android.widget.Toast.LENGTH_SHORT).show()
                DKLog.d("कॉल की रिंग बदली: $name")
            } catch (e: Exception) { UiLang.toast(this, "रिंग नहीं बदली: ${e.message}", android.widget.Toast.LENGTH_SHORT).show() }
        }
        finish()
    }
}
