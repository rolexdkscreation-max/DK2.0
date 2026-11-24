package com.dk.assistant

import com.dk.assistant.connect.Catalog
import com.dk.assistant.connect.ConnectorList
import com.dk.assistant.connect.ConnectorList.Filter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 24 नवंबर 2026 (मालिक: कनेक्टर सूची फ़ोटो जैसी - आइकन, नाम, विवरण, + / हटाओ, खोज) */
class ConnectorListTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun everyConnectableThing_hasRow_andOwnIcon() {
        val rows = ConnectorList.rows()
        val ids = rows.map { it.id }
        for (d in Catalog.GOOGLE + listOf(Catalog.GITHUB, Catalog.FB_PAGE, Catalog.INSTAGRAM)) assertTrue(d.id, d.id in ids)
        assertTrue("youtube" in ids && "mcp" in ids)
        assertEquals(ids.size, rows.map { it.icon }.toSet().size)
        assertNotEquals(ConnectorList.icon("youtube"), ConnectorList.icon("mcp"))
    }

    @Test fun search_byNameHindiAndConnected() {
        val all = ConnectorList.rows()
        assertEquals(listOf("youtube"), ConnectorList.filter(all, "यूट्यूब", Filter.ALL) { false }.map { it.id })
        assertTrue(ConnectorList.filter(all, "GMAIL", Filter.ALL) { false }.any { it.id == "gmail" })
        assertTrue(ConnectorList.filter(all, "insta", Filter.ALL) { false }.any { it.id == "instagram" })
        assertEquals(all.size, ConnectorList.filter(all, "  ", Filter.ALL) { false }.size)
        assertEquals(listOf("drive"), ConnectorList.filter(all, "", Filter.CONNECTED) { it == "drive" }.map { it.id })
    }

    @Test fun plusOrRemove() {
        assertEquals(R.drawable.ic_cn_plus, ConnectorList.actionIcon(false))
        assertEquals(R.drawable.ic_cn_remove, ConnectorList.actionIcon(true))
    }

    @Test fun activity_showsList_withSearch_andAsksBeforeRemove() {
        val a = src("connect/ConnectorsActivity.kt")
        assertTrue(a.indexOf("listCard()") in 1 until a.indexOf("section(\"g_setup\""))
        assertTrue(a.contains("R.drawable.cn_tile") && a.contains("R.drawable.cn_search"))
        assertTrue(a.contains("askRemove(r) else add(r)"))
        // Google (YouTube समेत) = OAuth (पासवर्ड कभी नहीं)
        assertTrue(a.contains("Kind.GOOGLE -> Catalog.ALL.firstOrNull { it.id == r.id }?.let { googleConnect(it) }"))
        assertTrue(!a.contains("password", ignoreCase = true) || a.contains("TYPE_TEXT_VARIATION_PASSWORD"))
    }
}
