package com.dk.assistant

import com.dk.assistant.agent.master.FormRules
import com.dk.assistant.agent.master.FormRules.Submit
import com.dk.assistant.agent.master.FormRules.Type
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 25 नवंबर 2026 (मालिक: Universal AI Action Agent, चरण 5 - फ़ॉर्म एजेंट) */
class FormAgentTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun fieldTypes() {
        assertEquals(Type.IDENTITY, FormRules.type("Aadhaar Number*"))
        assertEquals(Type.IDENTITY, FormRules.type("आधार नंबर"))
        assertEquals(Type.IDENTITY, FormRules.type("PAN Card No"))
        assertEquals(Type.IDENTITY, FormRules.type("Bank Account Number"))
        assertEquals(Type.IDENTITY, FormRules.type("IFSC Code"))
        assertEquals(Type.CAPTCHA, FormRules.type("Enter Captcha"))
        assertEquals(Type.CONTACT, FormRules.type("Mobile No."))
        assertEquals(Type.CONTACT, FormRules.type("Pin Code"))
        assertEquals(Type.NORMAL, FormRules.type("Full Name"))
        assertEquals(Type.NORMAL, FormRules.type("Company Name"))
    }

    @Test fun numbers_onlyFromOwner() {
        assertTrue(FormRules.fromOwner("9876543210", "मेरा नंबर 98765 43210 है"))
        assertTrue(FormRules.fromOwner("98765-43210", "number 9876543210 likho"))
        assertTrue(FormRules.fromOwner("422001", "पिनकोड चार दो दो शून्य शून्य एक"))
        assertTrue(FormRules.fromOwner("422001", "पिनकोड ४२२००१"))
        // मालिक ने नहीं बोला = AI का बनाया नंबर नहीं भरता
        assertFalse(FormRules.fromOwner("9123456780", "मेरा नंबर 98765 43210 है"))
        assertFalse(FormRules.fromOwner("1234 5678 9012", "आधार भर दो"))
        // नाम / तारीख / छोटे अंक यहाँ नहीं जाँचते
        assertTrue(FormRules.fromOwner("Rahul Sharma", ""))
        assertTrue(FormRules.fromOwner("15/08/1995", ""))
        assertTrue(FormRules.fromOwner("25", ""))
    }

    @Test fun mask_hidesIdentity() {
        assertEquals("XXXXXXXX9012", FormRules.mask("123456789012"))
        assertEquals("XXXX", FormRules.mask("12"))
        val rb = FormRules.readBack(linkedMapOf("Full Name" to "Rahul", "Aadhaar Number" to "123456789012"))
        assertTrue(rb.contains("Full Name: Rahul"))
        assertTrue(rb.contains("XXXXXXXX9012"))
        assertFalse(rb.contains("123456789012"))
    }

    @Test fun submitButton_payNever() {
        assertEquals(Submit.PAY, FormRules.submitKind("Pay"))
        assertEquals(Submit.PAY, FormRules.submitKind("Proceed to Pay"))
        assertEquals(Submit.BOOK, FormRules.submitKind("Book"))
        assertEquals(Submit.NORMAL, FormRules.submitKind("Submit"))
        assertEquals(Submit.NORMAL, FormRules.submitKind("Next"))
    }

    @Test fun readNote_strict() {
        val n = FormRules.readNote(listOf("Full Name", "Aadhaar Number", "Enter Captcha"))
        assertTrue(n.contains("never invent"))
        assertTrue(n.contains("Aadhaar Number"))
        assertTrue(n.contains("Enter Captcha"))
        assertTrue(FormRules.NOT_FROM_OWNER.startsWith("NOT_FROM_OWNER"))
    }

    @Test fun wiring_onlyWhenMasterOn() {
        val a = src("agent/android/FormFillAgent.kt")
        assertTrue(a.contains("if (DkMaster.on(svc)) masterFill(svc, want, value)"))
        assertTrue(a.contains("if (master) masterSubmit(svc, b.text.ifBlank { b.desc })"))
        assertTrue(a.contains("\"form_submit\", submitAskedAt"))
        // पुराना रास्ता वैसे ही
        assertTrue(a.contains("PRIVATE FIELD"))
        assertTrue(a.contains("WAITING_CONFIRMATION"))
        assertTrue(src("SettingsBrain.kt").contains("फ़ॉर्म एजेंट"))
    }
}
