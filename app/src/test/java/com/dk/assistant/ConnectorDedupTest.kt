package com.dk.assistant

import com.dk.assistant.connect.ConnectorList
import com.dk.assistant.connect.Kind
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 25 नवंबर 2026 (मालिक: "Gmail का दो ... सब चीज का दो-दो दिख रहा है, तो दो-दो नहीं दिखना चाहिए") */
class ConnectorDedupTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun eachConnectorOnce_inList() {
        val ids = ConnectorList.rows().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun detailOnlyForTapped() {
        assertEquals(Kind.GOOGLE, ConnectorList.detailKind("gmail"))
        assertEquals(Kind.GOOGLE, ConnectorList.detailKind("youtube"))
        assertEquals(Kind.META, ConnectorList.detailKind("meta"))
        assertEquals(Kind.MCP, ConnectorList.detailKind("mcp"))
        assertNull(ConnectorList.detailKind(null))
        assertNull(ConnectorList.detailKind("g_setup"))
    }

    @Test fun page_hasNoOldDuplicateRows() {
        val a = src("connect/ConnectorsActivity.kt")
        assertFalse(a.contains("Catalog.GOOGLE.forEach { d -> googleCard(d) }"))
        assertTrue(a.contains("detailFor(openId)"))
    }
}
