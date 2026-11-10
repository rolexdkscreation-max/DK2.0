package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** नवंबर 2026: PR #252 वाली गलतियाँ push से पहले पकड़ना (PrAudit) */
class PrAuditTest {
    private val act = "app/src/main/java/com/dk/assistant/MainActivity.kt"
    private val lay = "app/src/main/res/layout/activity_main.xml"
    private val tsv = "app/src/main/assets/ui_en.tsv"

    private fun run(changed: Map<String, String?>, before: Map<String, String>, extra: Map<String, String> = emptyMap(), hide: Boolean = false): List<String> {
        val all = HashMap(before).apply { putAll(extra); changed.forEach { (p, c) -> if (c == null) remove(p) else put(p, c) } }
        return PrAudit.audit(changed, { before[it] }, all, hide)
    }

    @Test fun duplicateTsvKey_flagged_oldDuplicatesIgnored() {
        val old = "नमस्ते\tHello\nपुराना\tOld\nपुराना\tOld\n"
        val now = old + "नमस्ते\tHi\nनया\tNew\n"
        val r = PrAudit.dupKeys(tsv, now, old)
        assertEquals(r.toString(), 1, r.size)
        assertTrue(r[0], r[0].contains("\"नमस्ते\"") && !r[0].contains("\"पुराना\"") && !r[0].contains("\"नया\""))
        assertTrue(PrAudit.dupKeys(tsv, old, old).isEmpty())
    }

    @Test fun hiddenOldButton_flagged_unlessAskedOrToggled() {
        val oldL = """<LinearLayout><ImageButton android:id="@+id/btnHomeCam" android:src="@drawable/c"/></LinearLayout>"""
        val newL = """<LinearLayout><ImageButton android:id="@+id/btnHomeCam" android:visibility="gone" android:src="@drawable/c"/></LinearLayout>"""
        val code = "findViewById<View>(R.id.btnHomeCam).setOnClickListener { cam() }"
        val r = run(mapOf(lay to newL), mapOf(lay to oldL, act to code))
        assertTrue(r.toString(), r.any { it.contains("btnHomeCam") && it.contains("gone") })
        assertTrue(run(mapOf(lay to newL), mapOf(lay to oldL, act to code), hide = true).isEmpty())
        val toggled = code + "\nfindViewById<View>(R.id.btnHomeCam).visibility = View.VISIBLE"
        assertTrue(run(mapOf(lay to newL), mapOf(lay to oldL, act to toggled)).isEmpty())
    }

    @Test fun deadNewButtons_flagged() {
        val oldL = """<LinearLayout><Button android:id="@+id/btnSend" android:text="भेजो"/></LinearLayout>"""
        val newL = """<LinearLayout><Button android:id="@+id/btnSend" android:text="भेजो"/>
            <ImageButton android:contentDescription="Mic"/>
            <Button android:id="@+id/btnDockTools" android:text="टूल"/>
            <Button android:id="@+id/btnOk" android:text="ठीक"/>
            <Button android:text="xml" android:onClick="go"/></LinearLayout>"""
        val code = "R.id.btnSend.let{}; findViewById<View>(R.id.btnOk).setOnClickListener{}"
        val r = run(mapOf(lay to newL), mapOf(lay to oldL, act to code))
        assertTrue(r.toString(), r.any { it.contains("ImageButton") && it.contains("android:id") })
        assertTrue(r.toString(), r.any { it.contains("btnDockTools") })
        assertTrue(r.toString(), r.none { it.contains("btnOk") || it.contains("btnSend") || it.contains("\"xml\"") })
        // पहले से बिना id का बटन (इस काम का नहीं) - नहीं पकड़ना
        val oldDead = """<LinearLayout><Button android:text="पुराना"/></LinearLayout>"""
        assertTrue(run(mapOf(lay to oldDead + " "), mapOf(lay to oldDead, act to code)).isEmpty())
    }

    @Test fun orphanLayoutRawAndDependency_flagged() {
        val chat = "app/src/main/res/layout/activity_chat.xml"
        val used = "app/src/main/res/layout/item_row.xml"
        val raw = "app/src/main/res/raw/energy_orb.json"
        val gradle = "app/build.gradle.kts"
        val oldG = "dependencies {\n implementation(\"androidx.core:core-ktx:1.12.0\")\n}"
        val newG = "dependencies {\n implementation(\"androidx.core:core-ktx:1.12.0\")\n implementation(\"com.airbnb.android:lottie:6.4.0\")\n implementation(\"com.squareup.okhttp3:okhttp:4.12.0\")\n}"
        val code = "import okhttp3.OkHttpClient\nimport com.squareup.okhttp3.X\nval v = layoutInflater.inflate(R.layout.item_row, null)\nimport androidx.core.view.isVisible"
        val r = run(mapOf(chat to "<FrameLayout/>", used to "<FrameLayout/>", raw to "{}", gradle to newG),
            mapOf(gradle to oldG, act to code))
        assertTrue(r.toString(), r.any { it.contains("activity_chat") })
        assertTrue(r.toString(), r.none { it.contains("item_row") })
        assertTrue(r.toString(), r.any { it.contains("energy_orb") })
        assertTrue(r.toString(), r.any { it.contains("lottie") })
        assertTrue(r.toString(), r.none { it.contains("okhttp") || it.contains("core-ktx") })
    }

    @Test fun noWorkspace_onlyLocalChecks() {
        val r = PrAudit.audit(mapOf("app/src/main/res/raw/a.json" to "{}", tsv to "क\tA\nक\tB\n"), { null }, null)
        assertEquals(r.toString(), 1, r.size)
        assertTrue(r[0].contains(tsv))
    }

    @Test fun teamRunsAuditInStaticCheck() {
        val src = java.io.File("src/main/java/com/dk/assistant/SevenTeam.kt").readText()
        assertTrue(src.contains("stat.addAll(prAudit(staged))"))
        assertTrue(Seven.Prompts.CODER.contains("visibility=\"gone\"") && Seven.Prompts.QA.contains("made up"))
    }
}
