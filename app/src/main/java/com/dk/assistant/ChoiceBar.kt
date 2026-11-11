package com.dk.assistant

import android.content.Context
import android.text.TextUtils
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * नवंबर 2026 (मालिक): लिखने के डिब्बे के ऊपर "आगे क्या करें" के बटन (↳ हाँ, PR merge कर दो …).
 * छूना = वही बात सीधे भेजो; देर तक दबाना = डिब्बे में भर दो (बदलकर भेजें); ✏️ = खुद लिखो.
 * Coding Agent और DK कंप्यूटर दोनों यही इस्तेमाल करते हैं. नियम [FollowUp] में.
 */
class ChoiceBar(ctx: Context) : LinearLayout(ctx) {
    private val dp = resources.displayMetrics.density
    /** छूआ गया बटन */
    var onPick: (FollowUp.Choice) -> Unit = {}
    /** डिब्बे में भरना (खाली = सिर्फ़ लिखने के लिए खोलो) */
    var onEdit: (String) -> Unit = {}
    private var shownList: List<FollowUp.Choice>? = null

    init { orientation = VERTICAL; visibility = View.GONE }

    /** वही बटन दोबारा = कुछ नहीं (स्क्रीन बार-बार बनती है - टिमटिमाए नहीं) */
    fun show(list: List<FollowUp.Choice>) {
        if (list == shownList) return
        shownList = list
        removeAllViews()
        visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        list.forEach { ch -> addView(row(ch), LayoutParams(-1, -2).apply { topMargin = (5 * dp).toInt() }) }
    }

    private fun row(ch: FollowUp.Choice) = TextView(context).apply {
        text = "↳  " + ch.label
        textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        setBackgroundResource(if (ch.act == FollowUp.Act.TYPE) R.drawable.glass_pill else R.drawable.glass_card)
        val ph = (14 * dp).toInt(); val pv = (9 * dp).toInt(); setPadding(ph, pv, ph, pv)
        contentDescription = ch.label
        isClickable = true; isFocusable = true
        setOnClickListener { if (ch.act == FollowUp.Act.TYPE) onEdit("") else onPick(ch) }
        // देर तक दबाओ = भेजने से पहले बदलना
        setOnLongClickListener { if (ch.act == FollowUp.Act.SEND) { onEdit(ch.text); true } else false }
    }

    companion object {
        /**
         * भेजें बटन: काम चलते समय डिब्बा खाली = रोकें (⏸), कुछ लिखा = भेजें (बात काम में जुड़े या कतार में).
         * लिखते ही निशान बदलता है.
         */
        fun wireSend(edt: EditText, send: ImageButton, busy: () -> Boolean) {
            edt.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { icon(edt, send, busy()) }
            })
        }

        fun stops(edt: EditText, busy: Boolean) = busy && edt.text.isNullOrBlank()

        fun icon(edt: EditText, send: ImageButton, busy: Boolean) {
            val stop = stops(edt, busy)
            send.setImageResource(if (stop) android.R.drawable.ic_media_pause else R.drawable.ic_send)
            send.contentDescription = if (stop) "रोकें" else if (busy) "काम में जोड़ें / बाद में करें" else "भेजें"
        }
    }
}
