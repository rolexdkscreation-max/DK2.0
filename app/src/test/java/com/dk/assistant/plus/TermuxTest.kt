package com.dk.assistant.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxTest {
    @Test fun shellQuote_safe() {
        assertEquals("'abc'", Termux.Rules.q("abc"))
        assertEquals("'a'\\''b'", Termux.Rules.q("a'b"))
    }

    @Test fun names_and_ports() {
        assertTrue(Termux.Rules.safeName("my_site-1")); assertFalse(Termux.Rules.safeName("../etc")); assertFalse(Termux.Rules.safeName("a b"))
        assertTrue(Termux.Rules.validPort(8080)); assertFalse(Termux.Rules.validPort(80)); assertFalse(Termux.Rules.validPort(70000))
    }

    @Test fun serverCmd_localOnly() {
        val s = Termux.Rules.serverCmd("site", 8080, Termux.Rules.Kind.STATIC)
        assertTrue(s.contains("http.server 8080 --bind 127.0.0.1"))
        assertEquals("http://127.0.0.1:8080", Termux.Rules.localUrl(8080))
        assertTrue(runCatching { Termux.Rules.serverCmd("../x", 8080, Termux.Rules.Kind.STATIC) }.isFailure)
    }
}
