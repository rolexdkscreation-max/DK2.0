package com.dk.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 20 नवंबर 2026 (3.338 लॉग "Can't downgrade database from version 2 to 1"): पुराना APK भी याददाश्त सेव करे, कुछ न मिटे */
class LocalMemoryDowngradeTest {
    @Test fun downgrade_keepsEverything() {
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
        val code = java.io.File(root, "com/dk/assistant/LocalMemory.kt").readText()
        val body = code.substringAfter("override fun onDowngrade(").substringBefore("\n        }")
        assertTrue(code.contains("override fun onDowngrade("))
        for (bad in listOf("DROP", "DELETE", "delete(", "super.onDowngrade")) assertFalse(bad, body.contains(bad))
        // दोबारा upgrade भी सुरक्षित
        assertTrue(code.contains("CREATE TABLE IF NOT EXISTS turns_backup_v1"))
        assertTrue(code.contains("if (!hasSession)"))
    }
}
