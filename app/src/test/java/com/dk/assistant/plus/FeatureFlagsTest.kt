package com.dk.assistant.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** मालिक का नियम: हर नई सुविधा शुरू में बंद - अकेला अपवाद Realistic Typing */
class FeatureFlagsTest {
    @Test fun everyNewFeature_offByDefault_exceptRealisticTyping() {
        for (f in FeatureFlags.ALL) {
            if (f.key == FeatureFlags.REALISTIC_TYPING) assertTrue(f.default)
            else assertFalse("${f.key} शुरू में बंद होना चाहिए", f.default)
        }
        assertFalse(FeatureFlags.defaultOf("unknown_key"))
    }

    @Test fun keysUnique_andPrefixed() {
        val keys = FeatureFlags.ALL.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.startsWith("plus_") })
    }

    @Test fun everySectionHasFlags_andReadyKeysExist() {
        for (s in FeatureFlags.Section.values()) assertTrue(s.name, FeatureFlags.inSection(s).isNotEmpty())
        for (k in FeatureFlags.READY) assertTrue(k, FeatureFlags.byKey(k) != null)
    }
}
