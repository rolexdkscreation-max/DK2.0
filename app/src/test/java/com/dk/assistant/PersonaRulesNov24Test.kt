package com.dk.assistant

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.TimeZone

/** 24 नवंबर 2026 (मालिक का पूरा स्वभाव-निर्देश) + रात में Live निर्देश 29,000 से ऊपर जाने की जड़ */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PersonaRulesNov24Test {
    private val c: Context get() = ApplicationProvider.getApplicationContext()

    private fun src(name: String): String = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Test fun eachPersonaHasOwnersNewWays() {
        val f = DkPersona.block(DkPersona.P.FRIENDLY)
        assertTrue(f.contains("खाना खाया?") && f.contains("पानी पिया?") && f.contains("stop him kindly") && f.contains("never a taunt"))
        val n = DkPersona.block(DkPersona.P.NATKHAT)
        assertTrue(n.contains("nicknames") && n.contains("playful nudge") && n.contains("Work always comes first"))
        val p = DkPersona.block(DkPersona.P.PRO)
        assertTrue(p.contains("congratulate") && p.contains("धन्यवाद सर"))
        val k = DkPersona.block(DkPersona.P.CARING)
        assertTrue(k.contains("बस आज एक काम") && k.contains("गहरी साँस") && k.contains("14416"))
        // पुराने नियम वही
        assertTrue(f.contains("NO romance") && n.contains("never vulgar") && p.contains("no romance") && k.contains("No teasing"))
    }

    @Test fun switchingAnnouncesNewStyle() {
        for (p in DkPersona.P.values()) assertTrue(DkPersona.finalFor(p, "धीरज").contains("ONLY the new style"))
        assertTrue(DkPersona.quick(DkPersona.P.NATKHAT).ack.contains("शरारती मोड में आ गई"))
        assertTrue(DkPersona.quick(DkPersona.P.GIRLFRIEND).ack.contains("बाबू"))
        assertTrue(DkPersona.finalFor(DkPersona.P.GIRLFRIEND, "धीरज").length < 3_500)
    }

    @Test fun girlfriendAndLimitsOnlyInBigPrompts() {
        val g = PersonaRules.block(DkPersona.P.GIRLFRIEND)
        assertTrue(g.contains("डार्लिंग") && g.contains("good-morning") && g.contains("good-night") && g.contains("OWN short, sweet song"))
        assertTrue(g.contains("क्या तुम AI हो?") && g.contains("family or friends") && g.contains("harmful or illegal"))
        assertFalse(PersonaRules.block(DkPersona.P.PRO).contains("डार्लिंग"))
        assertTrue(src("BrainManager.kt").split("PersonaRules.block(DkPersona.effective(ctx))").size - 1 == 3)
    }

    @Test fun romanticStaysDecent() {
        val b = RomanticPersona.BLOCK
        assertTrue(b.contains("इतनी रात तक क्यों जागे हो?") && b.contains("sweet scolding") && b.contains("smiling joke") && b.contains("sung with feeling"))
        assertTrue(b.contains("NO sexual, explicit") && b.contains("never control"))
        assertTrue(DkPersona.finalFor(DkPersona.P.GIRLFRIEND, "धीरज").contains("NO sexual, explicit or vulgar"))
    }

    @Test fun liteUnderLimitAtEveryHour() {
        // जड़: CareTime में समय के हिसाब से लाइन छोटी-बड़ी, रात 12-4 बजे सबसे लंबी - तब 29,000 पार
        val ist = TimeZone.getTimeZone("Asia/Kolkata")
        val base = 1_793_124_720_000L
        val sizes = (0 until 24).map { CareTime.block(base + it * 3_600_000L, ist).length }
        assertTrue("घंटों में फ़र्क ${sizes.max() - sizes.min()}", sizes.max() - sizes.min() < 120)
        Prefs.setLivePromptLite(c, true)
        val p = BrainManager(c).liveSystemPrompt(withMemory = false)
        val now = CareTime.block().length
        assertTrue("सबसे बुरे घंटे पर ${p.length - now + sizes.max()}", p.length - now + sizes.max() < 29_000)
        assertFalse(LivePromptLite.rules(true, true).contains("- TIME:"))
        assertTrue(p.contains("current_time"))
    }

    @Test fun girlfriendNeverByName_othersMay() {
        val g = DkPersona.finalFor(DkPersona.P.GIRLFRIEND, "धीरज")
        assertTrue(g.contains("\"डार्लिंग\"") && g.contains("NEVER his name") && !g.contains("हाँ धीरज") && !g.contains("MOSTLY by his name"))
        val f = DkPersona.finalFor(DkPersona.P.FRIENDLY, "धीरज")
        assertTrue(f.contains("\"धीरज बॉस\" is fine too") && f.contains("No बाबू"))
        assertTrue(PersonaRules.GIRLFRIEND.contains("never his name"))
    }

    @Test fun romanticOnlyAfterBossFace() {
        val r = RomanticPersona
        r.lock()
        assertFalse(r.unlocked())
        assertTrue(r.LOCKED.contains("सिर्फ़ अपने असली बॉस से") && r.LOCKED.contains("romantic_verify") && r.LOCKED.contains("DENIED"))
        r.passBoss(1_000L)
        assertTrue(r.unlocked(1_000L + 60_000L))
        assertFalse(r.unlocked(1_000L + r.UNLOCK_MS + 1))
        r.lock()
        assertFalse(r.unlocked(1_000L))
        val d = src("DkPersona.kt")
        assertTrue(d.contains("if (RomanticPersona.unlocked()) RomanticPersona.BLOCK else RomanticPersona.LOCKED"))
        assertTrue(d.contains("RomanticPersona.unlocked(), JarvisMode"))   // पास होते ही Live नए निर्देशों से
        val t = com.dk.assistant.plus.tools.PlusTools.byName("romantic_verify")!!
        assertTrue(t.flag == com.dk.assistant.plus.FeatureFlags.FACE_ON)
        val n = src("plus/tools/NewTools.kt")
        assertTrue(n.contains("BossCheckActivity.verify(c)") && n.contains("RomanticPersona.passBoss()"))
        assertTrue(src("RomanticPersona.kt").contains("        lock()\n"))   // बंद = फिर ताला
        // अश्लील की सीमा वैसी ही
        assertTrue(r.BLOCK.contains("NO sexual, explicit"))
    }
}
