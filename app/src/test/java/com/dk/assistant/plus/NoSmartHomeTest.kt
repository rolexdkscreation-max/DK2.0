package com.dk.assistant.plus

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** 15 नवंबर 2026 (मालिक): Smart Home ("DK Home" / "Maya Home") पूरी तरह हटाया - कहीं वापस न आए */
class NoSmartHomeTest {
    @Test fun noSmartHomeCodeOrSettings() {
        val root = File("src/main")
        val hits = root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { f -> f.readText().let { it.contains("SmartHome") || it.contains("DK Home") || it.contains("Maya Home") || it.contains("plus_smart_home") } }
            .map { it.path }.toList()
        assertFalse("Smart Home बचा है: $hits", hits.isNotEmpty())
    }
}
