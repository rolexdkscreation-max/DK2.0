package com.dk.assistant.voice

import com.dk.assistant.plus.FeatureFlags
import com.dk.assistant.plus.models.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 21 नवंबर 2026: परिवार की आवाज़ें + अवेयरनेस मोड + सबसे सटीक मॉडल (ReDimNet2-B6) */
class VoiceFamilyTest {
    private fun f(p: String) = listOf(File("src/main/$p"), File("app/src/main/$p")).first { it.exists() }
    private fun src(n: String) = f("java/com/dk/assistant/$n").readText()

    /** dim 192 का इकाई vector: index i पर ज़्यादा, बाकी थोड़ा शोर (बीज से तय) */
    private fun v(i: Int, noise: Float = 0.03f, seed: Int = 1): FloatArray {
        val r = java.util.Random(seed.toLong() * 1000 + i)
        val a = FloatArray(VoicePrint.DIM) { (r.nextGaussian() * noise).toFloat() }
        a[i] += 1f
        return VoicePrint.normalize(a)
    }
    private fun member(id: String, name: String, i: Int) =
        VoiceFamily.Member(id, name, (1..6).map { v(i, seed = it) }, 0.45f)

    @Test fun picksRightPersonWithMargin() {
        val fam = listOf(member("m1", "मम्मी", 3), member("m2", "पापा", 7), member("m3", "भैया", 11))
        val p = VoiceFamily.identify(0.10f, 0.50f, fam, v(7, seed = 99))
        assertEquals("पापा", p.name)
        assertFalse(p.owner)
        assertEquals("m2", p.memberId)
        assertTrue(p.score > 0.7f)
    }

    @Test fun ownerWinsWhenBossSpeaks() {
        val fam = listOf(member("m1", "मम्मी", 3))
        val p = VoiceFamily.identify(0.82f, 0.50f, fam, v(40, seed = 5))
        assertTrue(p.owner)
        assertEquals(VoiceFamily.OWNER_NAME, p.name)
    }

    @Test fun strangerOrCloseCall_noName() {
        val fam = listOf(member("m1", "मम्मी", 3), member("m2", "पापा", 7))
        assertNull(VoiceFamily.identify(0.05f, 0.50f, fam, v(60, seed = 4)).name)       // अनजान
        // दो लोग लगभग बराबर = नाम नहीं (ग़लत नाम से अच्छा)
        val m = VoiceFamily.Member("a", "भैया", listOf(v(1)), 0.4f)
        val close = VoiceFamily.decide(0.70f, 0.50f, listOf(Triple(m, 0.68f, 0.40f)))
        assertNull(close.name)
        val clear = VoiceFamily.decide(0.30f, 0.50f, listOf(Triple(m, 0.68f, 0.40f)))
        assertEquals("भैया", clear.name)
        // अपनी सीमा से नीचे = नहीं
        assertNull(VoiceFamily.decide(null, 0.50f, listOf(Triple(m, 0.35f, 0.40f))).name)
        assertNull(VoiceFamily.decide(null, 0.50f, emptyList()).name)
        assertTrue(VoiceFamily.testLine(null).contains("फिर से"))
    }

    @Test fun encodeDecodeRoundTrip_andNames() {
        val list = listOf(member("m1", "मम्मी", 3), member("m2", "चाचा जी", 9))
        val back = VoiceFamily.decode(VoiceFamily.encode(list))
        assertEquals(listOf("मम्मी", "चाचा जी"), back.map { it.name })
        assertEquals(6, back[1].templates.size)
        assertEquals(0.45f, back[0].th, 1e-3f)
        assertTrue(VoiceFamily.decode("टूटा\tडेटा").isEmpty())
        assertNull(VoiceFamily.cleanName("  "))
        assertNull(VoiceFamily.cleanName("बॉस"))
        assertNull(VoiceFamily.cleanName("x".repeat(30)))
        assertEquals("भैया जी", VoiceFamily.cleanName(" भैया\tजी "))
        // उसी नाम वाला बदलता है, नया नहीं जुड़ता
        val up = VoiceFamily.upsert(list, member("m9", "मम्मी", 5))
        assertEquals(2, up.size)
        assertEquals("m9", up.first { it.name == "मम्मी" }.id)
        assertEquals(1, VoiceFamily.remove(up, "m2").size)
        assertTrue(VoiceFamily.RELATIONS.take(3) == listOf("मम्मी", "पापा", "भैया"))
        assertEquals(6, VoiceFamily.PHRASES.size)
    }

    @Test fun awarenessNotesOnlyOnChange() {
        val log = Awareness.Log()
        val mom = VoiceFamily.Pick("मम्मी", false, 0.8f, 0.3f, "m1")
        val boss = VoiceFamily.Pick(VoiceFamily.OWNER_NAME, true, 0.8f, 0.3f)
        val unk = VoiceFamily.Pick(null, false, 0.3f, 0.2f)
        assertTrue(log.shouldNote(mom, 1_000)); log.noted(mom, 1_000); log.heard(mom, 1_000)
        assertFalse(log.shouldNote(mom, 5_000))                                   // वही आदमी = फिर नहीं
        assertTrue(log.shouldNote(boss, 6_000)); log.noted(boss, 6_000); log.heard(boss, 6_000)
        assertFalse(log.shouldNote(unk, 7_000))                                   // अनजान = नाम नहीं
        assertTrue(log.shouldNote(boss, 6_000 + Awareness.RENOTE_MS + 1))        // बहुत देर बाद फिर
        assertEquals(listOf(VoiceFamily.OWNER_NAME, "मम्मी"), log.recent(8_000))
        assertTrue(log.recent(1_000 + Awareness.RECENT_MS + 10_000).isEmpty())
        val n = Awareness.note(mom, listOf("मम्मी", "पापा"))
        assertTrue(n.contains("मम्मी") && n.contains("NOT the owner") && n.contains("पापा") && n.contains("do not reply"))
        assertTrue(Awareness.note(boss, emptyList()).contains("your owner"))
    }

    @Test fun awarenessPrompt_offIsEmpty_onHasRules() {
        assertEquals("", Awareness.prompt(false, listOf("मम्मी"), "रोहित"))
        val p = Awareness.prompt(true, listOf("मम्मी", "पापा"), "रोहित")
        assertTrue(p.contains("AWARENESS MODE") && p.contains("मम्मी, पापा") && p.contains("रोहित"))
        assertTrue(p.contains("मम्मी जी") && p.contains("mood") && p.contains("only for the owner"))
        assertTrue(Awareness.prompt(true, emptyList(), "").contains("none yet"))
    }

    @Test fun flagOffByDefault_andProModelDownloadedNotBundled() {
        val fl = FeatureFlags.byKey(FeatureFlags.VG_AWARE)
        assertNotNull(fl); assertFalse(fl!!.default)
        assertEquals(FeatureFlags.Section.VOICE_GUARDIAN, fl.section)
        val pro = ModelCatalog.REDIMNET_PRO
        assertTrue(pro in ModelCatalog.ALL && pro.kind == ModelCatalog.Kind.VOICE && pro.sha256.length == 64)
        assertTrue(pro.urls.all { it.startsWith("https://huggingface.co/") })
        assertEquals(listOf(ModelCatalog.REDIMNET_LIGHT.id, ModelCatalog.REDIMNET_HEAVY.id, pro.id), VgRules.MODELS_ALL.map { it.id })
        assertEquals(pro.id, VgRules.model(pro.id).id)
        assertEquals(ModelCatalog.REDIMNET_LIGHT.id, VgRules.DEFAULT_MODEL)    // पुराने सीखे सैंपल बेकार न हों
        assertTrue(VgRules.label(pro).contains("सबसे सटीक"))
    }

    @Test fun wired() {
        val st = src("GeminiLiveSideTalk.kt")
        assertTrue(st.contains("VoiceGuardian.identify(svc, clip)") && st.contains("onAwareWho("))
        assertTrue(st.contains("VoiceGuardian.score(svc, clip)"))                 // बंद = पहले जैसा
        assertTrue(src("GeminiLiveMic.kt").contains("if (whoOn || awareNow()) whoCheckAsync("))
        val aw = src("GeminiLiveAware.kt")
        assertTrue(aw.contains("quietNote(Awareness.note(") && aw.contains("if (ownerOn) return false"))
        assertTrue(src("BrainManager.kt").contains("append(awarenessBlock())"))
        val ui = src("SettingsVoiceFamily.kt")
        assertTrue(ui.contains("FeatureFlags.VG_AWARE") && ui.contains("VoiceGuardian.saveFamily(") && ui.contains("styledTakes(VgStyle.plan(VoiceFamily.PHRASES)"))
        assertTrue(src("SettingsActivity.kt").contains("voiceFamilyCard()"))
        assertTrue(src("SettingsSearch.kt").contains("\"परिवार की आवाज़ें और अवेयरनेस मोड\""))
        assertTrue(src("SettingsVoiceGuardian.kt").contains("VgRules.MODELS_ALL.map"))
        val vg = src("voice/VoiceGuardian.kt")
        assertTrue(vg.contains("\"vg_family_\$id\"") && vg.contains("VoiceVault.seal(VoiceFamily.encode("))
    }
}
