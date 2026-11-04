package com.dk.assistant

import com.dk.assistant.TaskSense.Reply
import com.dk.assistant.TaskSense.Slot
import com.dk.assistant.TaskSense.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * मालिक (नवंबर 2026): "DK समझे - बात कमांड है, सवाल है या जवाब. 'WhatsApp पर मैसेज भेजो' = पूछे किसे और क्या भेजूँ;
 * 'यह डीके है, WhatsApp पर धीरज कुमार को हेलो मैसेज भेजो' = सीधे भेजे. कोई भी काम हो, वैसे ही."
 */
class TaskSenseTest {
    private fun filled(r: Reply) = (r as Reply.Filled).need

    @Test fun ownerExampleAsksWhoThenWhat() {
        var n = TaskSense.need("WhatsApp पर मैसेज भेजो")!!
        assertEquals(Task.MESSAGE, n.task)
        assertEquals("WhatsApp पर किसे मैसेज भेजूँ?", n.question)
        n = filled(TaskSense.answer(n, "धीरज कुमार"))
        assertEquals("धीरज कुमार को क्या मैसेज भेजूँ?", n.question)
        n = filled(TaskSense.answer(n, "हेलो"))
        assertTrue(n.done)
        assertEquals("WhatsApp पर धीरज कुमार को मैसेज भेजो कि हेलो", n.command)
    }

    @Test fun ownerExampleFullSentenceNoQuestion() {
        assertNull(TaskSense.need("यह डीके है, WhatsApp पर धीरज कुमार को हेलो मैसेज भेजो"))
        val d = TaskSense.detect("यह डीके है, WhatsApp पर धीरज कुमार को हेलो मैसेज भेजो")!!
        assertEquals("धीरज कुमार", d.have[Slot.CONTACT]); assertEquals("हेलो", d.have[Slot.TEXT]); assertEquals("WhatsApp", d.via)
        assertNull(TaskSense.need("डीके, मम्मी को मैसेज भेजो कि मैं आ रहा हूँ"))
    }

    @Test fun messageOnlyNameAsksText() {
        val n = TaskSense.need("धीरज कुमार को मैसेज भेजो")!!
        assertEquals(listOf(Slot.TEXT), n.missing)
        assertEquals("धीरज कुमार को क्या मैसेज भेजूँ?", n.question)
        assertEquals("धीरज कुमार", TaskSense.need("धीरज कुमार का मैसेज करो")!!.have[Slot.CONTACT])
    }

    @Test fun nameAndTextInOneAnswer() {
        val n = filled(TaskSense.answer(TaskSense.need("मैसेज भेजो")!!, "धीरज को बोलो कि कल मिलते हैं"))
        assertTrue(n.done)
        assertEquals("धीरज", n.have[Slot.CONTACT]); assertEquals("कल मिलते हैं", n.have[Slot.TEXT])
    }

    @Test fun cancelAndNewCommand() {
        val n = TaskSense.need("मैसेज भेजो")!!
        assertTrue(TaskSense.answer(n, "रहने दो") is Reply.Cancel)
        assertTrue(TaskSense.answer(n, "छोड़ो") is Reply.Cancel)
        val r = TaskSense.answer(n, "नहीं, मम्मी को कॉल करो")
        assertEquals(Reply.NewCommand("मम्मी को कॉल करो"), r)
    }

    @Test fun otherTasks() {
        assertEquals("किसे कॉल करूँ?", TaskSense.need("कॉल करो")!!.question)
        assertEquals("पापा को कॉल करो", filled(TaskSense.answer(TaskSense.need("फोन लगाओ")!!, "पापा")).command)
        assertEquals("WhatsApp पर किसे वीडियो कॉल करूँ?", TaskSense.need("व्हाट्सएप पर वीडियो कॉल करो")!!.question)
        assertNull(TaskSense.need("मम्मी को कॉल करो"))
        assertNull(TaskSense.need("कॉल उठाओ")); assertNull(TaskSense.need("फोन लॉक करो")); assertNull(TaskSense.need("कॉल काटो"))

        var r = TaskSense.need("मुझे याद दिलाना")!!
        assertEquals("किस बात की याद दिलाऊँ?", r.question)
        r = filled(TaskSense.answer(r, "कल सुबह दवाई लेनी है"))
        assertTrue(r.done); assertEquals("दवाई लेनी है", r.have[Slot.TEXT])
        assertEquals(listOf(Slot.WHEN), TaskSense.need("याद दिलाना कि दूध लाना है")!!.missing)
        assertNull(TaskSense.need("कल सुबह 7 बजे दूध लाना याद दिलाना"))

        assertEquals("कौन-सा ऐप खोलूँ?", TaskSense.need("ऐप खोलो")!!.question)
        assertNull(TaskSense.need("WhatsApp खोलो"))
        assertEquals("YouTube पर क्या सर्च करूँ?", TaskSense.need("यूट्यूब पर सर्च करो")!!.question)
        assertEquals("YouTube पर पवन सिंह के गाने सर्च करो", filled(TaskSense.answer(TaskSense.need("यूट्यूब पर सर्च करो")!!, "पवन सिंह के गाने")).command)
        assertNull(TaskSense.need("यूट्यूब पर पवन सिंह के गाने सर्च करो"))
        assertEquals("कहाँ जाना है?", TaskSense.need("रास्ता बताओ")!!.question)
        assertNull(TaskSense.need("नासिक का रास्ता बताओ"))
        assertEquals("किसे ईमेल भेजूँ?", TaskSense.need("ईमेल भेजो")!!.question)
    }

    @Test fun notTasksOrUnsureStayAsBefore() {
        for (t in listOf("मेरे मैसेज पढ़ो", "किसका मैसेज आया", "मैसेज मत भेजो", "धीरज मैसेज भेजो", "अलार्म लगाओ फिर मैसेज भेजो",
            "क्या तुम मैसेज भेज सकते हो?", "क्या तुम याद दिला सकते हो", "आज मौसम कैसा है", "टॉर्च जलाओ", ""))
            assertNull(t, TaskSense.need(t))
    }

    @Test fun kindOfMessage() {
        assertEquals(TaskSense.Kind.QUESTION, TaskSense.kind("आज मौसम कैसा है"))
        assertEquals(TaskSense.Kind.QUESTION, TaskSense.kind("भारत की राजधानी क्या है"))
        assertEquals(TaskSense.Kind.QUESTION, TaskSense.kind("मौसम बताओ"))
        assertEquals(TaskSense.Kind.TASK, TaskSense.kind("WhatsApp खोलो"))
        assertEquals(TaskSense.Kind.TASK, TaskSense.kind("टॉर्च जलाओ"))
        assertEquals(TaskSense.Kind.TASK, TaskSense.kind("WhatsApp पर मैसेज भेजो"))
        assertEquals(TaskSense.Kind.CHAT, TaskSense.kind("मैं आज बहुत खुश हूँ"))
        assertEquals(TaskSense.Kind.REPLY, TaskSense.kind("धीरज कुमार", "WhatsApp पर किसे मैसेज भेजूँ?", 5_000))
        assertEquals(TaskSense.Kind.REPLY, TaskSense.kind("कल आऊँगा", "धीरज को क्या मैसेज भेजूँ?", 5_000))
        assertEquals(TaskSense.Kind.TASK, TaskSense.kind("मम्मी को कॉल करो", "किसे मैसेज भेजूँ?", 5_000))
        // बहुत पुराना सवाल = जवाब नहीं
        assertEquals(TaskSense.Kind.CHAT, TaskSense.kind("धीरज कुमार", "किसे मैसेज भेजूँ?", 10 * 60_000))
    }

    @Test fun liveNoteAndPrompt() {
        val note = TaskSense.liveNote(TaskSense.need("कॉल करो")!!)
        assertTrue(note.startsWith("NEED_INFO:")); assertTrue(note.contains("किसे कॉल करूँ?"))
        assertTrue(TaskSense.PROMPT.contains("UNDERSTAND FIRST"))
    }

    @Test fun wiring() {
        fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()
        assertTrue(src("AgentRunner.kt").contains("TaskSense.need(goal)"))
        assertTrue(src("AgentRunner.kt").contains("TaskSense.replyNote(lastDk.text)"))
        assertTrue(src("GeminiLiveQueue.kt").contains("return TaskSense.liveNote(n)"))
        assertTrue(src("LiveFast.kt").contains("if (TaskSense.need(t) != null) return null"))
        assertTrue(src("BrainManager.kt").split("TaskSense.PROMPT").size >= 3)
    }
}
