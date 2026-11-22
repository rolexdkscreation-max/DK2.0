package com.dk.assistant

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 22 नवंबर 2026 (मालिक): कितने भी काम, समझदार क्रम, सिर्फ़ पूछा हुआ, नाम से पूरा सार */
class TaskQueuePlanTest {

    private fun item(text: String) = TaskQueue.split(text).single()

    @Test fun ownerExample_navigationFirstSongLast() {
        val raw = "बैटरी कितनी है बताओ, फिर अरिजीत का गाना चलाओ, फिर राहुल को व्हाट्सऐप करो कि मैं आ रहा हूँ, फिर नासिक का नेविगेशन लगाओ"
        val items = TaskQueue.split(raw)
        assertEquals(items.joinToString { it.domain }, listOf("battery", "youtube", "whatsapp", "maps"), items.map { it.domain })
        val plan = TaskQueue.plan(items, raw)
        assertEquals(listOf("battery", "maps", "whatsapp", "youtube"), plan.map { it.domain })
        assertEquals("youtube", plan.last().domain)
    }

    @Test fun planKeepsEveryTask_twentyTasks() {
        val parts = (1..10).flatMap { listOf("टॉर्च जलाओ", "अलार्म लगाओ $it बजे का") }
        val raw = parts.joinToString(", फिर ")
        val items = TaskQueue.split(raw)
        // एक जैसे काम लगातार हों तो एक में जुड़ते हैं - इसलिए अलग-अलग: टॉर्च / अलार्म बारी-बारी
        assertEquals(20, items.size)
        val plan = TaskQueue.plan(items, raw)
        assertEquals(20, plan.size)
        assertEquals(items.toSet(), plan.toSet())
    }

    @Test fun planIsStableForSameTier() {
        val raw = "टॉर्च जलाओ, वाईफाई बंद करो, अलार्म लगाओ 6 बजे, ब्लूटूथ चालू करो"
        val items = TaskQueue.split(raw)
        assertEquals(items, TaskQueue.plan(items, raw))
    }

    @Test fun explicitFirstAndLastWin() {
        val raw = "सबसे पहले अरिजीत का गाना चलाओ, फिर टॉर्च जलाओ, फिर आख़िर में राहुल को कॉल करो, फिर नासिक का नेविगेशन लगाओ"
        val plan = TaskQueue.plan(TaskQueue.split(raw), raw).map { it.domain }
        assertEquals(listOf("youtube", "torch", "maps", "call"), plan)
    }

    @Test fun sameOrderWhenOwnerSaysSo() {
        val raw = "इसी क्रम में करो: अरिजीत का गाना चलाओ, फिर टॉर्च जलाओ"
        val items = TaskQueue.split(raw)
        assertEquals(items, TaskQueue.plan(items, raw))
    }

    @Test fun lastReelIsNotLastInOrder() {
        assertEquals(2, TaskQueue.tier(TaskQueue.Item("लास्ट रील का insight खोल के बताओ", "instagram")))
        assertEquals(2, TaskQueue.tier(TaskQueue.Item("सबसे लास्ट वाली रील की reach बताओ", "instagram")))
        assertEquals(9, TaskQueue.tier(TaskQueue.Item("लास्ट में गाना चलाओ", "youtube")))
        assertEquals(9, TaskQueue.tier(TaskQueue.Item("सबसे बाद में टॉर्च जलाओ", "torch")))
        assertEquals(0, TaskQueue.tier(TaskQueue.Item("10 मिनट बाद में याद दिलाना", "reminder")))
    }

    @Test fun instagramChecksStayOneScopedTask() {
        val items = TaskQueue.split("Instagram खोलो और followers चेक करो, फिर टॉर्च जलाओ")
        assertEquals(listOf("instagram", "torch"), items.map { it.domain })
        assertTrue(items[0].text.contains("followers"))
        assertTrue(TaskQueue.homeAfter(items[0]))
        assertTrue(TaskQueue.FOCUS_NOTE.contains("ONLY exactly this task"))
    }

    @Test fun homeOnlyAfterCheckingTasks() {
        assertTrue(TaskQueue.homeAfter(TaskQueue.Item("इंस्टाग्राम पर लास्ट रील की reach बताओ", "instagram")))
        assertFalse(TaskQueue.homeAfter(TaskQueue.Item("इंस्टाग्राम खोलो", "instagram")))
        assertFalse(TaskQueue.homeAfter(TaskQueue.Item("अरिजीत का गाना चलाओ", "youtube")))
        assertFalse(TaskQueue.homeAfter(TaskQueue.Item("नासिक का नेविगेशन लगाओ", "maps")))
        assertFalse(TaskQueue.homeAfter(TaskQueue.Item("राहुल को कॉल करो", "call")))
    }

    @Test fun followUpStaysRightAfterItsTask() {
        val a = TaskQueue.Item("अरिजीत का गाना सर्च करो", "youtube")
        val b = TaskQueue.Item("उसे राहुल को व्हाट्सऐप करो", "whatsapp")
        val c = TaskQueue.Item("टॉर्च जलाओ", "torch")
        assertEquals(listOf(c, a, b), TaskQueue.plan(listOf(a, b, c)))
    }

    @Test fun detailComesFromPhoneOrAgent() {
        assertEquals("बैटरी 78% है", TaskQueue.detailOf("DONE: battery. Phone says: \"बैटरी 78% है\". Tell the user this honestly"))
        assertEquals("आपके 1,245 followers हैं", TaskQueue.detailOf("FINISHED. Result: आपके 1,245 followers हैं (report only this; do not claim anything more was done)"))
        assertEquals("", TaskQueue.detailOf("DONE: torch on. Tell the user in 2-5 words."))
        assertEquals("आपके 1,245 followers हैं", TaskQueue.outcome("FINISHED. Result: आपके 1,245 followers हैं (report only this)").detail)
    }

    @Test fun reportByNameWithEveryResult() {
        val bat = TaskQueue.Item("बैटरी कितनी है बताओ", "battery")
        val insta = TaskQueue.Item("Instagram खोलो और followers चेक करो", "instagram")
        val song = TaskQueue.Item("अरिजीत का गाना चलाओ", "youtube")
        val r = TaskQueue.report(listOf(
            TaskQueue.Result(bat, true, detail = "बैटरी 78% है"),
            TaskQueue.Result(insta, true, detail = "आपके 1,245 followers हैं"),
            TaskQueue.Result(song, true)), "धीरज")
        assertTrue(r, r.startsWith("धीरज, आपने जो-जो काम बोला था, मैंने एक-एक करके सब पूरा कर दिया।"))
        assertTrue(r, r.contains("1) बैटरी: बैटरी 78% है"))
        assertTrue(r, r.contains("2) इंस्टाग्राम: आपके 1,245 followers हैं"))
        assertTrue(r, r.contains("3) गाना चल रहा है"))
    }

    @Test fun reportHonestAboutFailuresAndStop() {
        val a = TaskQueue.Item("टॉर्च जलाओ", "torch")
        val b = TaskQueue.Item("राहुल को कॉल करो", "call")
        val r = TaskQueue.report(listOf(TaskQueue.Result(a, true), TaskQueue.Result(b, false, "कॉन्टैक्ट नहीं मिला")), "", stopped = true, left = 2)
        assertTrue(r, r.startsWith("बॉस, आपके 2 में से 1 काम पूरे हो गए।"))
        assertTrue(r, r.contains("कॉल नहीं हो पाया (कॉन्टैक्ट नहीं मिला)"))
        assertTrue(r, r.contains("बाक़ी 2 काम रोक दिए"))
        assertFalse(r.contains("सब पूरा कर दिया"))
    }

    @Test fun runnerReportsStepsAndKeepsDetail() = runBlocking {
        val steps = mutableListOf<String>()
        val q = TaskRunQueue(
            runOne = { if (it.domain == "battery") "DONE: battery. Phone says: \"बैटरी 78% है\". Tell" else "DONE: x. Tell the user" },
            prepare = { _, _, _ -> },
            ask = { null },
            onStep = { n, total, item -> steps += TaskQueue.stepLine(n, total, item) }
        )
        q.add(listOf(TaskQueue.Item("बैटरी बताओ", "battery"), TaskQueue.Item("टॉर्च जलाओ", "torch"), TaskQueue.Item("वाईफाई बंद करो", "wifi")))
        q.runAll()
        assertEquals(listOf("1/3 • बैटरी बताओ", "2/3 • टॉर्च जलाओ", "3/3 • वाईफाई बंद करो"), steps)
        assertEquals("बैटरी 78% है", q.results[0].detail)
        assertTrue(q.results.all { it.ok })
    }

    @Test fun livePromptAsksForOneCallAnyCount() {
        val b = WorkChatMode.block(live = true)
        assertTrue(b.contains("phone_task ONCE"))
        assertTrue(b.contains("20 or more"))
        assertTrue(TaskQueue.summaryNote("x").contains("keep every number"))
    }
}
