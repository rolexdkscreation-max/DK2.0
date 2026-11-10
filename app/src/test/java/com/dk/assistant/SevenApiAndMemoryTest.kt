package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** नवंबर 2026: कोडर/रिव्यूअर को जुड़ी classes की असली API + मैनेजर की पूरी याददाश्त (TeamMemory) */
class SevenApiAndMemoryTest {
    private val composer = """
        package com.dk.assistant
        import android.widget.LinearLayout
        /** chat का नीचे वाला डिब्बा */
        class DKComposer(
            act: android.app.Activity,
            hintText: String,
            maxLines: Int = 5,
            attach: (() -> Unit)? = null
        ) : LinearLayout(act) {
            val input: android.widget.EditText = make()
            var onSend: ((String) -> Unit)? = null
            private val secret = 1
            private fun iconBtn(r: Int) = r
            fun setBusy(b: Boolean) {
                val local = 3
                if (b) { input.isEnabled = false }
            }
            fun text(): String = input.text.toString()
            companion object {
                const val MAX = 9
                private const val HIDDEN = 2
            }
        }
    """.trimIndent()
    private val media = "package com.dk.assistant\ndata class CoderMedia(val kind: String, val label: String, val frames: List<ByteArray>, val video: ByteArray? = null)\n"
    private val repo = mapOf("app/DKChatUi.kt" to composer, "app/CoderMedia.kt" to media)

    @Test fun apiShowsRealConstructorAndPublicMembersOnly() {
        val code = "class ChatActivity { fun f() { val c = DKComposer(this, \"x\", attach = { }); c.setBusy(true); CoderMedia(\"photo\", \"a\", emptyList()) } }"
        val api = SevenSymbols.apiOf("app/ChatActivity.kt", code, repo)
        assertTrue(api, api.contains("app/DKChatUi.kt:"))
        assertTrue(api, api.contains("class DKComposer( act: android.app.Activity, hintText: String, maxLines: Int = 5, attach: (() -> Unit)? = null ) : LinearLayout(act)"))
        assertTrue(api, api.contains("fun setBusy(b: Boolean)"))
        assertTrue(api, api.contains("var onSend"))
        assertTrue(api, api.contains("const val MAX = 9"))
        assertTrue(api, api.contains("fun text(): String"))
        assertFalse(api, api.contains("iconBtn"))
        assertFalse(api, api.contains("secret"))
        assertFalse(api, api.contains("HIDDEN"))
        assertFalse(api, api.contains("local"))
        assertTrue(api, api.contains("data class CoderMedia(val kind: String, val label: String, val frames: List<ByteArray>, val video: ByteArray? = null)"))
    }

    @Test fun apiSkipsOwnTypesAndRespectsLimit() {
        val api = SevenSymbols.apiOf("app/DKChatUi.kt", composer, repo)
        assertFalse(api, api.contains("DKComposer("))
        val small = SevenSymbols.apiOf("app/X.kt", "fun f() = DKComposer(a, b)", repo, maxChars = 300)
        assertTrue(small.length <= 302)
    }

    @Test fun memoryFullKeepsEverything() {
        val m = TeamMemory()
        m.userWords = "chat में + बटन लगाओ जिससे फ़ोटो भेज सकें"
        m.spec = "DKComposer के attach से menu"
        m.plan = listOf("ChatActivity.kt - attach", "ui_en.tsv - text")
        m.stepsDone = 1
        m.found("CI: Unresolved reference attachMedia on DKComposer")
        m.event("CODER", "कदम 1 पूरा\nदूसरी लाइन")
        val f = m.full()
        assertTrue(f.contains(m.userWords))
        assertTrue(f.contains("[x] 1. ChatActivity.kt"))
        assertTrue(f.contains("[>] 2. ui_en.tsv"))
        assertTrue(f.contains("attachMedia"))
        assertTrue(f.contains("CODER: कदम 1 पूरा"))
        assertFalse(f.contains("दूसरी लाइन"))
        assertEquals(f, m.short(100_000))
    }

    @Test fun memoryShortFitsAndKeepsUserWords() {
        val m = TeamMemory()
        m.userWords = "मेरे ऐप में वीडियो भेजने का बटन"
        m.spec = "x".repeat(20_000)
        repeat(40) { m.found("finding $it " + "y".repeat(300)) }
        repeat(40) { m.event("TESTER", "event $it") }
        val s = m.short(4_000)
        assertTrue(s.length <= 4_000)
        assertTrue(s.contains(m.userWords))
        assertTrue(s.contains("finding 39"))
        // AI का सारांश: खाली/बहुत बड़ा = पक्का छोटा रूप; user के शब्द गायब = आगे जोड़ो
        assertEquals(s, m.accept("", 4_000))
        assertEquals(s, m.accept("z".repeat(5_000), 4_000))
        val a = m.accept("The team is adding a send-video button. Step 2 of 3 left.", 4_000)
        assertTrue(a.startsWith("USER'S OWN WORDS"))
        assertTrue(a.contains(m.userWords))
    }

    @Test fun memoryBudgetIsBounded() {
        assertEquals(4_000, TeamMemory.maxFor(10_000))
        assertEquals(20_000, TeamMemory.maxFor(500_000))
    }
}
