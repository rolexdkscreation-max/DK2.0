package com.dk.assistant

import com.dk.assistant.license.AccessKeyRules
import com.dk.assistant.license.KeyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DK AI Part 13: access key के pure नियम */
class AccessKeyRulesTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun d(configured: Boolean = true, key: String? = "DK-ABCD-1234", plan: String = "monthly",
                  exp: Long = now + 10 * day, active: Boolean = true, blocked: Boolean = false) =
        AccessKeyRules.decide(configured, key, plan, exp, active, blocked, now)

    @Test fun notConfigured_alwaysAllowed() {
        assertEquals(KeyStatus.NOT_CONFIGURED, d(configured = false, key = null, active = false, blocked = true))
        assertTrue(KeyStatus.NOT_CONFIGURED.allowed)
    }

    @Test fun decide_allStates() {
        assertEquals(KeyStatus.ACTIVE, d())
        assertEquals(KeyStatus.NO_KEY, d(key = null))
        assertEquals(KeyStatus.NO_KEY, d(key = " "))
        assertEquals(KeyStatus.BLOCKED, d(blocked = true))
        assertEquals(KeyStatus.BLOCKED, d(blocked = true, exp = now - day))   // block सबसे ऊपर
        assertEquals(KeyStatus.INACTIVE, d(active = false))
        assertEquals(KeyStatus.EXPIRED, d(exp = now - 1))
        assertEquals(KeyStatus.EXPIRED, d(exp = now))
        assertEquals(KeyStatus.ACTIVE, d(exp = 0))
        assertEquals(KeyStatus.ACTIVE, d(plan = "lifetime", exp = now - day))
        for (s in listOf(KeyStatus.NO_KEY, KeyStatus.BLOCKED, KeyStatus.INACTIVE, KeyStatus.EXPIRED)) assertFalse(s.allowed)
        assertTrue(KeyStatus.ACTIVE.allowed)
    }

    @Test fun messages_nameTheAdmin() {
        val en = AccessKeyRules.refusal(KeyStatus.INACTIVE, en = true)
        assertEquals("Access key inactive, please contact admin ${AssistantBrand.OWNER_NAME}.", en)
        for (s in KeyStatus.entries.filter { !it.allowed }) {
            assertTrue(AccessKeyRules.refusal(s).contains(AssistantBrand.OWNER_NAME))
            assertTrue(AccessKeyRules.statusText(s, "", 0, now).contains(AssistantBrand.OWNER_NAME))
        }
        assertTrue(AccessKeyRules.statusText(KeyStatus.NOT_CONFIGURED, "", 0, now, en = true).contains("not configured"))
        assertEquals("Active • monthly • 3 days left", AccessKeyRules.statusText(KeyStatus.ACTIVE, "monthly", now + 2 * day + 5, now, en = true))
    }

    @Test fun info_flags() {
        val i = AccessKeyRules.info(true, "DK-ABCD-1234", "monthly", now + day, true, false, now)
        assertTrue(i.active); assertTrue(i.hasRememberedKey); assertFalse(i.blocked)
        val b = AccessKeyRules.info(true, "DK-ABCD-1234", "monthly", now + day, true, true, now)
        assertFalse(b.active); assertTrue(b.blocked); assertEquals(KeyStatus.BLOCKED, b.status)
    }

    @Test fun normalizeKey() {
        assertEquals("DK-ABCD-1234", AccessKeyRules.normalizeKey("  dk-abcd-1234 "))
        assertEquals("DKABCD1234", AccessKeyRules.normalizeKey("dk abcd 1234"))
        assertNull(AccessKeyRules.normalizeKey("short"))
        assertNull(AccessKeyRules.normalizeKey("-DKABCD1234"))
        assertNull(AccessKeyRules.normalizeKey("DK_ABCD;1234"))
        assertNull(AccessKeyRules.normalizeKey(null))
    }

    @Test fun liveCheck_atMostEvery3Hours() {
        assertTrue(AccessKeyRules.shouldLiveCheck(0, now))
        assertFalse(AccessKeyRules.shouldLiveCheck(now - 60_000, now))
        assertTrue(AccessKeyRules.shouldLiveCheck(now - AccessKeyRules.CHECK_INTERVAL_MS, now))
        assertTrue(AccessKeyRules.shouldLiveCheck(now - 60_000, now, force = true))
        assertTrue(AccessKeyRules.shouldLiveCheck(now + day, now))           // घड़ी पीछे गई
    }

    @Test fun parseRemote() {
        val r = AccessKeyRules.parseRemote("""{"ok":true,"active":true,"plan":"monthly","expires_at":123,"blocked":false}""")
        assertNotNull(r); assertTrue(r!!.active); assertEquals(123L, r.expiresAt); assertEquals("monthly", r.plan)
        val b = AccessKeyRules.parseRemote("""{"ok":true,"active":true,"blocked":true}""")!!
        assertFalse(b.active); assertTrue(b.blocked)                          // block = तुरंत बंद
        assertFalse(AccessKeyRules.parseRemote("""{"ok":false,"message":"bad key"}""")!!.ok)
        assertNull(AccessKeyRules.parseRemote("<html>502</html>"))
        assertNull(AccessKeyRules.parseRemote("""{"hello":1}"""))
        assertNull(AccessKeyRules.parseRemote(null))
    }

    @Test fun parseConfig_httpsOnly() {
        assertEquals("https://x.example/key", AccessKeyRules.parseConfig("""{"endpoint":" https://x.example/key "}"""))
        assertNull(AccessKeyRules.parseConfig("""{"endpoint":"http://x.example/key"}"""))
        assertNull(AccessKeyRules.parseConfig("""{}"""))
        assertNull(AccessKeyRules.parseConfig("not json"))
    }

    @Test fun deviceHash_stableAndNotRaw() {
        val h = AccessKeyRules.deviceHash("abc123")
        assertEquals(h, AccessKeyRules.deviceHash("abc123"))
        assertEquals(32, h.length)
        assertFalse(h.contains("abc123"))
        assertFalse(h == AccessKeyRules.deviceHash("abc124"))
    }
}
