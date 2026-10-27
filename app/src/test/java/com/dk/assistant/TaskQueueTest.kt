package com.dk.assistant

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskQueueTest {

    private val five = "अलार्म लगाओ सुबह 7 बजे का, फिर राहुल को व्हाट्सऐप करो कि मैं लेट आऊँगा, फिर गैलरी से आखिरी फ़ोटो डिलीट करो, " +
        "फिर यूट्यूब पर अरिजीत का गाना सर्च करो, फिर मैप में नासिक स्टेशन खोजकर नेविगेशन शुरू करो"

    @Test fun fiveTasksInSpokenOrder() {
        val items = TaskQueue.split(five)
        assertEquals(items.joinToString { it.domain + ":" + it.text }, 5, items.size)
        assertEquals(listOf("alarm", "whatsapp", "gallery", "youtube", "maps"), items.map { it.domain })
        assertEquals(TaskQueue.Kind.WHATSAPP_SEND, items[1].kind)
        assertEquals("राहुल", items[1].contact)
        assertEquals("मैं लेट आऊँगा", items[1].message)
        assertEquals(TaskQueue.Kind.DELETE, items[2].kind)
        assertFalse(items[2].text.startsWith("फिर"))
    }

    @Test fun moreThanFiveAndThenWords() {
        val t = "टॉर्च जलाओ और फिर वाईफाई बंद करो उसके बाद अलार्म लगाओ 6 बजे, ब्लूटूथ चालू करो, टाइमर लगाओ 5 मिनट का, फिर राहुल को कॉल करो"
        assertEquals(listOf("torch", "wifi", "alarm", "bluetooth", "timer", "call"), TaskQueue.split(t).map { it.domain })
    }

    @Test fun sameAppChainStaysOneTask() {
        assertFalse(TaskQueue.isMulti("Flipkart खोलो, जूते सर्च करो और पहला कार्ट में डालो"))
        assertFalse(TaskQueue.isMulti("यूट्यूब खोलो फिर अरिजीत का गाना सर्च करो"))
        assertFalse(TaskQueue.isMulti("अलार्म लगाओ सुबह 6 बजे का"))
    }

    @Test fun messageWithAndIsNotBroken() {
        val items = TaskQueue.split("राहुल को मैसेज करो कि मैं और अमित आ रहे हैं, फिर टॉर्च जलाओ")
        assertEquals(2, items.size)
        assertEquals("मैं और अमित आ रहे हैं", items[0].message)
    }

    @Test fun riskyAsksOnlyWhenUnclear() {
        val items = TaskQueue.split("अलार्म लगाओ, फिर राहुल को व्हाट्सऐप करो, फिर गैलरी से फ़ोटो डिलीट करो, फिर यूट्यूब पर गाना सर्च करो")
        assertNull(TaskQueue.preAsk(items[0]))
        assertEquals(TaskQueue.Field.MESSAGE, TaskQueue.preAsk(items[1])?.field)
        assertEquals(TaskQueue.Field.WHICH_PHOTO, TaskQueue.preAsk(items[2])?.field)
        assertNull(TaskQueue.preAsk(items[3]))
        assertNull(TaskQueue.preAsk(TaskQueue.split(five)[1]))
        assertNull(TaskQueue.preAsk(TaskQueue.split(five)[2]))
        val photo = TaskQueue.applyAnswer(items[2], TaskQueue.preAsk(items[2])!!, "हाँ")!!
        assertEquals("आखिरी फोटो डिलीट करो", photo.text)
        assertNull(TaskQueue.applyAnswer(items[2], TaskQueue.preAsk(items[2])!!, "नहीं रहने दो"))
    }

    @Test fun stopWords() {
        assertTrue(TaskQueue.isStop("रुको"))
        assertTrue(TaskQueue.isStop("बंद करो"))
        assertTrue(TaskQueue.isStop("रुक जाओ यार"))
        assertTrue(TaskQueue.isStop("stop"))
        assertFalse(TaskQueue.isStop("टॉर्च बंद करो"))
        assertFalse(TaskQueue.isStop("वाईफाई बंद करो"))
    }

    @Test fun summaryLikeSpec() {
        val items = TaskQueue.split(five.replace("सर्च करो", "चलाओ"))
        val s = TaskQueue.summary(items.map { TaskQueue.Result(it, true) })
        assertEquals("अलार्म लग गया, मैसेज भेज दिया, फ़ोटो डिलीट हो गई, गाना चल रहा है, नेविगेशन शुरू है।", s)
    }

    @Test fun queueSkipsStuckTaskAndReportsAtEnd() = runBlocking {
        val done = mutableListOf<String>()
        val resets = mutableListOf<String>()
        val q = TaskRunQueue(
            runOne = { done += it.domain; if (it.domain == "gallery") "FAILED: could not do it (timeout). Tell the user" else "DONE: ok" },
            prepare = { p, n, _ -> resets += "${p.domain}>${n.domain}" },
            ask = { null }
        )
        q.add(TaskQueue.split(five))
        val s = q.runAll()
        assertEquals(listOf("alarm", "whatsapp", "gallery", "youtube", "maps"), done)
        assertEquals(4, resets.size)
        assertTrue(s, s.contains("फ़ोटो डिलीट नहीं हो पाया"))
        assertTrue(s, s.startsWith("अलार्म लग गया, मैसेज भेज दिया"))
        assertTrue(s, s.contains("नेविगेशन शुरू है"))
    }

    @Test fun queueAsksOneShortQuestionForUnclearMessage() = runBlocking {
        val asked = mutableListOf<String>()
        var sent: TaskQueue.Item? = null
        val q = TaskRunQueue(
            runOne = { if (it.kind == TaskQueue.Kind.WHATSAPP_SEND) sent = it; "DONE: ok" },
            prepare = { _, _, _ -> },
            ask = { asked += it; "शाम को मिलते हैं" }
        )
        q.add(TaskQueue.split("अलार्म लगाओ, फिर राहुल को व्हाट्सऐप करो, फिर टॉर्च जलाओ"))
        q.runAll()
        assertEquals(1, asked.size)
        assertEquals("शाम को मिलते हैं", sent?.message)
        assertEquals("राहुल", sent?.contact)
    }

    @Test fun stopHaltsAtOnceAndNewTasksGoToEnd() = runBlocking {
        val done = mutableListOf<String>()
        lateinit var q: TaskRunQueue
        q = TaskRunQueue(
            runOne = {
                done += it.domain
                if (it.domain == "alarm") q.add(TaskQueue.split("टॉर्च जलाओ"))
                if (it.domain == "youtube") q.stop()
                "DONE: ok"
            },
            prepare = { _, _, _ -> }, ask = { null }
        )
        q.add(TaskQueue.split("अलार्म लगाओ, फिर यूट्यूब पर गाना चलाओ, फिर मैप में स्टेशन का रास्ता दिखाओ"))
        val s = q.runAll()
        assertEquals(listOf("alarm", "youtube"), done)
        assertTrue(s, s.contains("रोक दिए"))
    }

    @Test fun outcomeParsing() {
        assertTrue(TaskQueue.outcome("DONE: alarm. Tell the user").ok)
        assertTrue(TaskQueue.outcome("FINISHED. Result: भेज दिया").ok)
        assertFalse(TaskQueue.outcome("STILL RUNNING in the background").ok)
        assertEquals("कौन-सा राहुल?", TaskQueue.outcome("NEEDS USER CHOICE (nothing was done yet): कौन-सा राहुल?. Ask the user exactly").needsUser?.trimEnd('.'))
    }
}

class TaskQueueChatTest {
    @Test fun moodPlusOneTaskIsNotAQueue() {
        assertFalse(TaskQueue.isMulti("यार आज मूड ख़राब है, एक अलार्म लगा दो"))
        assertEquals("alarm", TaskQueue.split("यार आज मूड ख़राब है, एक अलार्म लगा दो").single().domain)
    }
    @Test fun commandWords() {
        assertTrue(TaskQueue.isCommand("अलार्म लगाओ"))
        assertTrue(TaskQueue.isCommand("लाइट जला दो"))
        assertTrue(TaskQueue.isCommand("ऐप खोलो"))
        assertFalse(TaskQueue.isCommand("आज मूड ख़राब है"))
        assertFalse(TaskQueue.isCommand("तुम कैसी हो"))
    }
}
