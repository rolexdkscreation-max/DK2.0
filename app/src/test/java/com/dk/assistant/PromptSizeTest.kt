package com.dk.assistant

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 20 नवंबर 2026 (फ़ोन log: स्थिर हिस्सा 17,472 token - निर्देश 44,862 अक्षर): Live के निर्देशों का हर हिस्सा कितना बड़ा है.
 * सिर्फ़ नापता है (कोई assert नहीं) - नतीजा build/prompt-size.txt में, CI log में छपता है.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PromptSizeTest {
    private val c: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun measure() {
        val parts = linkedMapOf<String, () -> String>(
            "SearchPolicy.rules" to { SearchPolicy.rules("web_search") },
            "TaskSense.PROMPT" to { TaskSense.PROMPT },
            "ActFirstRule.TEXT" to { ActFirstRule.TEXT },
            "ScreenSeeRule.TEXT" to { ScreenSeeRule.TEXT },
            "AssistantBrand.IDENTITY_RULE" to { AssistantBrand.IDENTITY_RULE },
            "NameRule.block" to { NameRule.block(Prefs.userName(c), Prefs.assistantName(c)) },
            "BrainManager.TALK_RULES" to { BrainManager.TALK_RULES },
            "TalkStyle.EMOTION" to { TalkStyle.EMOTION },
            "TalkStyle.FRIEND_HI" to { TalkStyle.FRIEND_HI },
            "TalkStyle.NATURAL" to { TalkStyle.NATURAL },
            "DoneGate.PROMPT" to { DoneGate.PROMPT },
            "TruthCheck.PROMPT" to { TruthCheck.PROMPT },
            "FriendStyle.persona" to { FriendStyle.persona(true, Prefs.userName(c)) },
            "CareTime.block" to { CareTime.block() },
            "CalmPersona.block" to { CalmPersona.block() },
            "DkPersona.pick(+Girlfriend)" to { DkPersona.pick(c, GirlfriendPersona.block(Prefs.userName(c))) },
            "GirlfriendPersona.block" to { GirlfriendPersona.block(Prefs.userName(c)) },
            "SpeakingStyle.block" to { SpeakingStyle.block(Prefs.userName(c)) },
            "DkPersona.finalBlock" to { DkPersona.finalBlock(c, Prefs.userName(c)) },
            "AssistantPersona.block" to { AssistantPersona.block(c) },
            "WorkChatMode.block" to { WorkChatMode.block(live = true) },
            "TeacherBoard.block" to { TeacherBoard.block() },
            "DkOnly.PROMPT" to { DkOnly.PROMPT },
            "SideTalk.PROMPT" to { SideTalk.PROMPT },
            "TaskFirst.prompt" to { TaskFirst.prompt(true) },
            "GeminiLive.FAST_START" to { GeminiLive.FAST_START },
            "GeminiLive.SPLIT_NOTE" to { GeminiLive.SPLIT_NOTE },
            "HistoryOnDemand.RULE" to { HistoryOnDemand.RULE },
        )
        val sb = StringBuilder("### Live निर्देश - हिस्सों का साइज़ (अक्षर • ~token)\n")
        Prefs.setLivePromptLite(c, false)
        val whole = try { BrainManager(c).liveSystemPrompt(withMemory = false) } catch (e: Throwable) { "ERR ${e.javaClass.simpleName}: ${e.message}" }
        Prefs.setLivePromptLite(c, true)
        val lite = try { BrainManager(c).liveSystemPrompt(withMemory = false) } catch (e: Throwable) { "ERR ${e.javaClass.simpleName}: ${e.message}" }
        sb.append("कुल liveSystemPrompt(false) पूरे निर्देश: ${whole.length} • ~${HistoryOnDemand.estimateTokens(whole)}\n")
        sb.append("कुल छोटे निर्देश (LivePromptLite): ${lite.length} • ~${HistoryOnDemand.estimateTokens(lite)}\n")
        val rows = parts.map { (n, f) -> n to (try { f() } catch (e: Throwable) { "ERR ${e.javaClass.simpleName}" }) }
        for ((n, s) in rows.sortedByDescending { it.second.length })
            sb.append("${s.length.toString().padStart(6)} • ~${HistoryOnDemand.estimateTokens(s).toString().padStart(5)}  $n\n")
        val known = rows.filter { it.first != "GirlfriendPersona.block" && !it.first.startsWith("GeminiLive.") && it.first != "HistoryOnDemand.RULE" }.sumOf { it.second.length }
        sb.append("बाकी (BrainManager में सीधी लाइनें आदि): ${whole.length - known}\n")
        // दोहराव: एक ही वाक्य कितनी बार
        val sentences = whole.split(Regex("(?<=[.!?।])\\s+")).map { it.trim() }.filter { it.length > 40 }
        val dup = sentences.groupingBy { it }.eachCount().filter { it.value > 1 }
        sb.append("दोहराए वाक्य: ${dup.size} (${dup.entries.sumOf { it.key.length * (it.value - 1) }} अक्षर)\n")
        for ((k, v) in dup.entries.sortedByDescending { it.key.length * it.value }.take(15)) sb.append("  ×$v ${k.take(110)}\n")
        val out = sb.toString()
        println(out)
        try { File("build").mkdirs(); File("build/prompt-size.txt").writeText(out); File("build/live-prompt.txt").writeText(whole); File("build/live-prompt-lite.txt").writeText(lite) } catch (e: Exception) { println(e.message) }
    }
}
