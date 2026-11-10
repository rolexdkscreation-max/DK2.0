package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * नवंबर 2026 (feature/dk-user-correction-follow-this-1110-1139): कोडिंग एजेंट ने चैट के + बटन के लिए 4 CI राउंड में
 * हर बार नया गढ़ा नाम लिखा (setMedia, attachMedia, onPlusClick, plusBtn, onAttachClick, clearMedia) - DKComposer एक
 * LinearLayout है, इसलिए नाम-जाँच "पक्का नहीं" कहकर AI रिव्यूअर पर छोड़ देती थी. ये tests पक्का करते हैं कि अब ऐसा न हो.
 */
class SevenSymbolsCiProofTest {
    private val composer = """package app
import android.widget.LinearLayout
class DKComposer(
    private val act: AppCompatActivity,
    hintText: String,
    maxLines: Int = 5,
    attach: (() -> Unit)? = null
) : LinearLayout(act) {
    val edt: EditText
    var onSend: () -> Unit = {}
    private fun iconBtn(icon: Int, click: () -> Unit): ImageButton = ImageButton(act)
    init { setPadding(1, 2, 3, 4) }
}
"""
    private val media = """package app
data class CoderMedia(val kind: String, val label: String, val frames: List<String>, val video: String? = null)
"""
    private val chat = """package app
class ChatActivity : AppCompatActivity() {
    private lateinit var composer: DKComposer
    fun make() {
        composer = DKComposer(this, "hint", maxLines = 4)
    }
}
"""
    private fun repo(chatAfter: String) = mapOf("app/DKChatUi.kt" to composer, "app/CoderMedia.kt" to media, "app/ChatActivity.kt" to chatAfter)
    private fun add(code: String) = chat.replace("    fun make() {", "    fun more() {\n$code\n    }\n    fun make() {")
    private val p = "app/ChatActivity.kt"

    @Test fun wrongNamedConstructorArg_caughtBeforeCi_realOnePasses() {
        val bad = chat.replace("maxLines = 4)", "maxLines = 4, onAttachClick = { v -> show(v) })")
        val m = SevenSymbols.check(p, chat, bad, repo(bad), whole = true)
        val miss = m.single { it.name.startsWith("DKComposer(") }
        assertTrue(miss.sure)
        assertTrue(miss.known.contains("attach"))                     // असली नाम दिखे
        val ok = chat.replace("maxLines = 4)", "maxLines = 4, attach = { show() })")
        assertTrue(SevenSymbols.check(p, chat, ok, repo(ok), whole = true).none { it.name.startsWith("DKComposer(") })
    }

    @Test fun dataClassNamedArgs_checked() {
        val bad = add("        val x = CoderMedia(kind = \"i\", label = \"a\", frames = listOf(), name = \"n\")")
        assertTrue(SevenSymbols.check(p, chat, bad, repo(bad), whole = true).any { it.sure && it.name == "CoderMedia(name = …)" })
        val ok = add("        val x = CoderMedia(kind = \"i\", label = \"a\", frames = listOf(), video = null)")
        assertTrue(SevenSymbols.check(p, chat, ok, repo(ok), whole = true).none { it.name.startsWith("CoderMedia(") })
    }

    @Test fun privateMemberFromOtherFile_caught() {
        val bad = add("        val b = composer.iconBtn")
        val miss = SevenSymbols.check(p, chat, bad, repo(bad), whole = true).single()
        assertTrue(miss.sure)
        assertTrue(miss.hindi(), miss.hindi().contains("private"))
        assertFalse(miss.known.contains("iconBtn"))
        assertTrue(miss.known.contains("onSend"))
    }

    @Test fun inventedMemberOnViewSubclass_unsureBeforeCi_sureAfterCi() {
        val first = add("        composer.setMedia(m)")
        // CI से पहले: LinearLayout में हो सकता है -> पक्का नहीं (पुराना व्यवहार बरकरार)
        assertFalse(SevenSymbols.check(p, chat, first, repo(first), whole = true).single().sure)
        // CI ने पकड़ा: Unresolved reference: setMedia (composer = DKComposer पर)
        val log = "e: file:///home/runner/work/DK2.0/DK2.0/app/ChatActivity.kt:5:18 Unresolved reference: setMedia\n> Task :app:compileDebugKotlin FAILED"
        val proven = SevenSymbols.proven(log, mapOf(p to first), repo(first))
        assertEquals(setOf("setMedia"), proven.names)
        assertEquals(setOf("DKComposer"), proven.types)
        // अगला राउंड: नया गढ़ा नाम उसी class पर -> push से पहले ही पक्का रुकता है, असली नाम सुझाए जाते हैं
        val second = add("        composer.attachMedia(m)\n        composer.onPlusClick = { }")
        val m2 = SevenSymbols.check(p, chat, second, repo(second), whole = true, proven = proven)
        assertTrue(m2.toString(), m2.any { it.sure && it.name == "composer.attachMedia()" })
        assertTrue(m2.toString(), m2.any { it.sure && it.name == "composer.onPlusClick" })
        assertTrue(m2.first().known.contains("onSend"))
        // वही गलत नाम दोबारा -> पक्का
        assertTrue(SevenSymbols.check(p, chat, first, repo(first), whole = true, proven = proven).single().sure)
        // Android parent class का असली नाम (रिपो में पहले से इस्तेमाल) - नहीं रोकते
        val real = add("        composer.setPadding(0, 0, 0, 0)")
        assertTrue(SevenSymbols.check(p, chat, real, repo(real), whole = true, proven = proven).none { it.sure })
    }

    @Test fun k2StyleMessage_andNamedParamError_parsed() {
        val f = chat.replace("maxLines = 4)", "maxLines = 4, onAttachClick = { })")
        val log = "e: ChatActivity.kt:5:60 Unresolved reference 'clearMedia'.\ne: ChatActivity.kt:5:40 Cannot find a parameter with this name: onAttachClick"
        val pr = SevenSymbols.proven(log, mapOf(p to f), repo(f))
        assertTrue(pr.names.containsAll(listOf("clearMedia", "onAttachClick")))
        assertTrue(pr.types.contains("DKComposer"))
    }

    @Test fun ctorParams_primaryAndSecondary() {
        assertEquals(listOf("act", "hintText", "maxLines", "attach"), SevenSymbols.ctorParams(composer, "DKComposer"))
        val two = "class Foo(val a: Int) {\n    constructor(b: String, c: Int) : this(1)\n}"
        assertEquals(listOf("a", "b", "c"), SevenSymbols.ctorParams(two, "Foo"))
        assertEquals(null, SevenSymbols.ctorParams("class Bar : Base() {}", "Bar"))
    }
}
