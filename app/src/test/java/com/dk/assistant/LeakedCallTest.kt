package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v4.1: Live ने tool call बुलाने की जगह `phone_task{…}<ctrl43>` लिख दिया - फ़ोन खुद चलाए, text कभी न दिखे/बोले */
class LeakedCallTest {
    private val owner = "phone_task{request:YouTube खोलो और पवन सिंह का गाना हकीकत सर्च और प्ले करो}<ctrl43>"

    @Test fun ownerScreenshotCase() {
        val (clean, calls) = LeakedCall.extract(owner)
        assertEquals("", clean)
        assertEquals(1, calls.size)
        assertEquals("phone_task", calls[0].name)
        assertEquals("YouTube खोलो और पवन सिंह का गाना हकीकत सर्च और प्ले करो", calls[0].args["request"])
    }

    @Test fun streamedInPiecesRunsOnceAndShowsNothing() {
        val got = ArrayList<LeakedCall.Call>(); var opened = 0
        val f = LeakedCall.Filter(onOpen = { opened++ }, onCall = { got.add(it) })
        val out = StringBuilder()
        for (p in listOf("ठीक है। ", "phone", "_ta", "sk{req", "uest:YouTube खोलो और ", "पवन सिंह का गाना चलाओ}", "<ctr", "l43>")) {
            out.append(f.push(p))
            assertFalse("text में call का टुकड़ा नहीं दिखना चाहिए: $out", out.contains("phone") || out.contains("{") || out.contains("<"))
        }
        out.append(f.finish())
        assertEquals("ठीक है।", out.toString().trim())
        assertEquals(1, got.size)
        assertEquals(1, opened)
        assertEquals("YouTube खोलो और पवन सिंह का गाना चलाओ", got[0].args["request"])
        assertTrue(f.sawCall())
    }

    @Test fun callFiresBeforeTurnEnds() {
        val got = ArrayList<LeakedCall.Call>()
        val f = LeakedCall.Filter(onCall = { got.add(it) })
        f.push("phone_task{\"request\":\"टॉर्च जलाओ\"}")
        assertEquals(1, got.size)          // finish() से पहले ही = काम तुरंत
        assertEquals("टॉर्च जलाओ", got[0].args["request"])
    }

    @Test fun otherShapes() {
        assertEquals("नासिक मौसम", LeakedCall.extract("web_search(query=\"नासिक मौसम\")").second.single().args["query"])
        assertEquals("YouTube खोलो", LeakedCall.extract("print(default_api.phone_task(request='YouTube खोलो'))").second.single().args["request"])
        assertEquals("YouTube खोलो", LeakedCall.extract("phone_task{YouTube खोलो}").second.single().args["request"])
        val wa = LeakedCall.extract("whatsapp_task{action:send, contact:राहुल, message:कल मिलते हैं}").second.single()
        assertEquals("send", wa.args["action"]); assertEquals("राहुल", wa.args["contact"]); assertEquals("कल मिलते हैं", wa.args["message"])
        assertEquals("current_time", LeakedCall.extract("current_time{}").second.single().name)
    }

    @Test fun unclosedCallStillRunsAtTurnEnd() {
        val (clean, calls) = LeakedCall.extract("phone_task{request:कैमरा खोलो")
        assertEquals("", clean)
        assertEquals("कैमरा खोलो", calls.single().args["request"])
    }

    @Test fun ctrlTokensRemoved() {
        assertEquals("हो गया सर", LeakedCall.clean("हो गया सर<ctrl43>"))
        assertEquals("हो गया", LeakedCall.clean("<ctrl100>हो गया"))
    }

    @Test fun normalRepliesUntouched() {
        val f = LeakedCall.Filter()
        val parts = listOf("हाँ सर, ", "YouTube पर ", "गाना चल ", "रहा है। phone ", "पर देखिए।")
        val out = StringBuilder(); for (p in parts) out.append(f.push(p)); out.append(f.finish())
        assertEquals(parts.joinToString(""), out.toString())
        assertFalse(f.sawCall())
        assertEquals("Camera is on now.", LeakedCall.clean("Camera is on now."))
        assertEquals("मैंने web search किया।", LeakedCall.clean("मैंने web search किया।"))
        assertFalse(LeakedCall.has("आज मौसम अच्छा है (धूप)"))
        assertTrue(LeakedCall.has(owner))
    }

    @Test fun emptyCallNotRun() {
        assertTrue(LeakedCall.extract("phone_task{}").second.isEmpty())
    }
}
