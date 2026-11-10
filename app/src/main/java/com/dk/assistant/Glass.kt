package com.dk.assistant

import android.animation.AnimatorInflater
import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ScrollView
import android.widget.HorizontalScrollView

/**
 * Liquid Glass design system - ek jagah: rang, dialog theme aur dabaane ki animation.
 * Sirf DIKHAWAT badalta hai; kisi view ka click/kaam nahi chhuta.
 */
object Glass {
    /** Saare AlertDialog isi glass theme me (Android 12+ par peeche blur) */
    val DIALOG = R.style.Theme_DK_Dialog

    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT2 = 0xD9FFFFFF.toInt()
    const val TEXT3 = 0xA6FFFFFF.toInt()
    const val ACCENT = 0xFFD6EEFF.toInt()

    private val TAG_KEY = R.id.glass_press   // view par nishaan: animation lag chuki
    /** नए बटन/बुलबुले पर press animation कितनी देर में (कई layout = एक जाँच) */
    const val DECORATE_GAP_MS = 150L

    /**
     * Screen ke har dabne-wale view (Button, ImageButton, clickable card/row/TextView) par halki "press" animation.
     * Baad me jude views (list, chat bubble) bhi pakde jaate hain. Touch listener nahi lagaya - sirf StateListAnimator,
     * isliye kisi ka onClick / onTouch nahi badalta.
     */
    fun install(a: Activity) {
        val root = a.window?.decorView ?: return
        // नवंबर 2026 (स्मूथ): पहले हर layout पर (टाइप करना, हर सेकंड का status) पूरी स्क्रीन का हर view दोबारा जाँचा जाता था.
        // अब कई layout मिलकर एक जाँच, थोड़ा रुककर - press animation वैसी ही, बस बेवजह का काम नहीं
        var queued = false
        val pass = Runnable { queued = false; try { decorate(root) } catch (_: Exception) {} }
        root.viewTreeObserver.addOnGlobalLayoutListener { if (!queued) { queued = true; root.postDelayed(pass, DECORATE_GAP_MS) } }
        root.post { decorate(root) }
    }

    private fun decorate(v: View) {
        if (v is OrbView || v is AuroraView) return
        if (v.isClickable && v.getTag(TAG_KEY) == null && v !is EditText && v !is CompoundButton &&
            v !is ScrollView && v !is HorizontalScrollView && !(v is android.widget.TextView && v.isTextSelectable) && v.stateListAnimator == null) {
            v.setTag(TAG_KEY, true)
            try { v.stateListAnimator = AnimatorInflater.loadStateListAnimator(v.context, R.animator.press_scale) } catch (_: Exception) {}
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) decorate(v.getChildAt(i))
    }
}
