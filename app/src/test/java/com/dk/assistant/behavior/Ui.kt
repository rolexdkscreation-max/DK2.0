package com.dk.assistant.behavior

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView

/**
 * व्यवहार वाले tests के छोटे औज़ार: स्क्रीन पर जो दिखता है उसी से बटन / खाना ढूँढो
 * (variable का नाम या code की लाइन नहीं) - code बदले, बँटे या नाम बदले, test तब भी वही जाँचे।
 */
object Ui {
    fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
    fun all(a: Activity): List<View> = all(a.window.decorView)

    /** इस लिखावट वाला switch / checkbox */
    fun switch(a: Activity, text: String): CompoundButton =
        all(a).filterIsInstance<CompoundButton>().firstOrNull { it.text?.toString() == text }
            ?: error("स्क्रीन पर \"$text\" वाला switch नहीं मिला")

    /** इस शीर्षक (label) के ठीक नीचे वाला लिखने का खाना */
    fun field(a: Activity, label: String): EditText {
        for (g in all(a).filterIsInstance<ViewGroup>()) {
            for (i in 0 until g.childCount - 1) {
                val t = g.getChildAt(i)
                if (t is TextView && t !is EditText && t.text?.toString() == label) {
                    (i + 1 until g.childCount).map { g.getChildAt(it) }.firstOrNull { it is EditText }?.let { return it as EditText }
                }
            }
        }
        error("स्क्रीन पर \"$label\" वाला खाना नहीं मिला")
    }

    fun hasText(a: Activity, part: String): Boolean =
        all(a).filterIsInstance<TextView>().any { it.text?.toString()?.contains(part) == true }
}
