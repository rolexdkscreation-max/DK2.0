package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DKWorkTest {
    private fun r(mode: Mode, hint: String = "", streaming: Boolean = false, tasks: List<WorkResolver.Task> = emptyList(),
                  voice: Boolean = false, focus: String? = null) = WorkResolver.resolve(mode, hint, streaming, tasks, voice, focus)

    private val coder = WorkResolver.Task(TaskStatus.CODER, WorkState.WORKING, "कोडिंग टीम • कोड लिखने वाला", 10)
    private val pc = WorkResolver.Task(TaskStatus.PC, WorkState.WORKING, "DK कंप्यूटर", 20)

    @Test fun idle_whenNothingRuns() {
        assertEquals(WorkState.IDLE, r(Mode.IDLE).state)
        assertFalse(r(Mode.IDLE).busy)
    }

    @Test fun voice_threeStates() {
        assertEquals(WorkState.LISTENING, r(Mode.LISTENING, voice = true).state)
        assertEquals(WorkState.THINKING, r(Mode.THINKING, voice = true).state)
        assertEquals(WorkState.SPEAKING, r(Mode.SPEAKING, voice = true).state)
    }

    @Test fun nonVoiceScreens_ignoreWakeListening() {
        assertEquals(WorkState.IDLE, r(Mode.LISTENING).state)
        // सुनते समय भी पीछे का काम दिखे
        assertEquals(WorkState.WORKING, r(Mode.LISTENING, tasks = listOf(coder)).state)
    }

    @Test fun chat_streamingIsWriting() {
        val s = r(Mode.THINKING, streaming = true)
        assertEquals(WorkState.SPEAKING, s.state)
        assertEquals("जवाब लिख रहा है…", s.label)
    }

    @Test fun webSearch_label() {
        val s = r(Mode.THINKING, hint = "इंटरनेट पर खोज रहा है…")
        assertEquals(WorkState.THINKING, s.state)
        assertEquals("इंटरनेट पर खोज रहा है…", s.label)
    }

    @Test fun phoneTask_isWorking() {
        assertEquals(WorkState.WORKING, r(Mode.WORKING).state)
    }

    @Test fun manyTasks_oneIndicator_untilLastEnds() {
        val both = r(Mode.IDLE, tasks = listOf(pc, coder))
        assertTrue(both.busy); assertEquals(2, both.count)
        assertEquals("कोडिंग टीम • कोड लिखने वाला  +1", both.label)       // सबसे पुराना पहले
        assertTrue(r(Mode.IDLE, tasks = listOf(pc)).busy)                   // एक खत्म - फिर भी चल रहा
        assertFalse(r(Mode.IDLE, tasks = emptyList()).busy)                 // आख़िरी खत्म = रुका
    }

    @Test fun focusScreen_showsOwnTaskFirst() {
        val s = r(Mode.THINKING, tasks = listOf(coder, pc), focus = TaskStatus.PC)
        assertEquals("DK कंप्यूटर", s.label)
        assertEquals(3, s.count)
    }

    @Test fun roleLabels() {
        assertEquals("कोड लिखने वाला • काम शुरू…", WorkResolver.roleLabel("बिल्डर: काम शुरू…"))
        assertEquals("बग जाँचने वाला • जाँच शुरू (राउंड 1)…", WorkResolver.roleLabel("रिव्यूअर: जाँच शुरू (राउंड 1)…"))
        assertEquals("कोऑर्डिनेटर • GitHub पर push…", WorkResolver.roleLabel("कोऑर्डिनेटर: GitHub पर push…"))
        assertEquals("बग जाँचने वाला • जड़ मिली", WorkResolver.roleLabel("🐞 डिबगर: जड़ मिली"))
        assertEquals("कोड लिखने वाला • फ़ाइल बदली", WorkResolver.roleLabel("⌨ कोडर: फ़ाइल बदली"))
        assertEquals("डिप्लॉयमेंट • merge कर रहा हूँ", WorkResolver.roleLabel("🚀 डिप्लॉयमेंट: merge कर रहा हूँ"))
        assertEquals("बिना नाम का कदम", WorkResolver.roleLabel("बिना नाम का कदम"))
    }
}
