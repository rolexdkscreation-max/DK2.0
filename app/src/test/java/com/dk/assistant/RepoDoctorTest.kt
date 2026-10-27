package com.dk.assistant

import com.dk.assistant.RepoDoctor.Act
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** रिपो डॉक्टर: पूरे रिपो की हालत → सही अगला कदम (कुछ भी user की हाँ के बिना नहीं) */
class RepoDoctorTest {

    private val now = 10_000_000_000L
    private val min = 60_000L

    private fun pr(n: Int, sha: String, draft: Boolean = false, mergeable: Boolean? = true, behind: Int = 0) =
        RepoDoctor.Pr(n, "काम $n", "https://pr/$n", "feature/$n", "main", draft, sha, "dk", 0, mergeable, true, behind)

    private fun run(id: Long, name: String, sha: String, status: String = "completed", conclusion: String = "success",
                    startedAgoMin: Long = 5, event: String = "pull_request", branch: String = "feature/x") =
        RepoDoctor.RunI(id, name, status, conclusion, "https://run/$id", event, branch, sha, now - startedAgoMin * min)

    private val freshRel = SevenPlus.Deploy.Rel("v75", "DK 3.35", "main", listOf("DK.apk"), "https://rel/75", now - 10 * min)

    private fun snap(prs: List<RepoDoctor.Pr>, runs: List<RepoDoctor.RunI>, rel: SevenPlus.Deploy.Rel? = freshRel,
                     mainMs: Long = now - 30 * min, branches: List<RepoDoctor.Branch> = emptyList(), myPr: Int = 0, busy: Boolean = false) =
        RepoDoctor.Snap("me/DK2.0", "main", "m1", mainMs, prs, runs, rel, branches, myPr, busy)

    @Test fun eachPrGetsTheRightNextStep() {
        val s = snap(
            listOf(pr(1, "a"), pr(2, "b"), pr(3, "c"), pr(4, "d", mergeable = false), pr(5, "e", draft = true, behind = 2), pr(6, "f")),
            listOf(
                run(1, "Check", "a", "in_progress", "", startedAgoMin = 3, branch = "feature/1"),               // चल रहा
                run(2, "Check", "b", "in_progress", "", startedAgoMin = 90, branch = "feature/2"),              // फँसा
                run(3, "Check", "c", conclusion = "failure"),                              // fail
                run(4, "Check", "d"),                                                      // पास पर conflict
                run(5, "Check", "e"),                                                      // पास + draft + पीछे
                run(7, "Build DK APK", "m1", event = "workflow_dispatch", branch = "main") // main ठीक
                // PR 6: कोई check नहीं
            ))
        val items = RepoDoctor.analyze(s, now).associateBy { it.pr }
        assertEquals(Act.WAIT, items[1]!!.act)
        assertEquals(Act.RESTART, items[2]!!.act); assertTrue(items[2]!!.state.contains("90 मिनट"))
        assertEquals(Act.FIX, items[3]!!.act); assertEquals(3L, items[3]!!.run!!.id)
        assertEquals(Act.CONFLICT, items[4]!!.act)
        assertEquals(Act.MERGE, items[5]!!.act); assertTrue(items[5]!!.state.contains("draft") && items[5]!!.state.contains("2 commit"))
        assertEquals(Act.CHECK, items[6]!!.act)
        assertNull(items[null])                                                            // main + release ठीक = कुछ नहीं
    }

    @Test fun newestRunPerWorkflowWins_rerunThatPassedIsNotFail() {
        val s = snap(listOf(pr(1, "a")), listOf(run(1, "Check", "a", conclusion = "failure", startedAgoMin = 20), run(2, "Check", "a", startedAgoMin = 5)))
        assertEquals(Act.MERGE, RepoDoctor.analyze(s, now).first { it.pr == 1 }.act)
    }

    @Test fun myOwnPrWhileTeamIsWorking_isLeftAlone() {
        val s = snap(listOf(pr(9, "z")), listOf(run(1, "Check", "z", conclusion = "failure")), myPr = 9, busy = true)
        assertEquals(Act.WAIT, RepoDoctor.analyze(s, now).first { it.pr == 9 }.act)
    }

    @Test fun mainBuild_failedRunningOrReleaseBehind() {
        val failed = snap(emptyList(), listOf(run(1, "Build DK APK", "m1", conclusion = "failure", event = "push", branch = "main")))
        assertEquals(Act.FIX, RepoDoctor.analyze(failed, now).single().act)
        val running = snap(emptyList(), listOf(run(1, "Build DK APK", "m1", "in_progress", "", startedAgoMin = 2, event = "push", branch = "main")))
        assertEquals(Act.WAIT, RepoDoctor.analyze(running, now).single().act)
        // main पर नया commit (merge) पर release उससे पुरानी और कोई build नहीं चला = release बाकी (आज PR #39/#40/#41 के बाद यही हुआ)
        val behind = snap(emptyList(), emptyList(), rel = freshRel.copy(publishedMs = now - 120 * min), mainMs = now - 30 * min)
        assertEquals(Act.RELEASE, RepoDoctor.analyze(behind, now).single().act)
        assertTrue(RepoDoctor.analyze(snap(emptyList(), emptyList()), now).isEmpty())
    }

    @Test fun otherAgentsWork_isShownAsInfo_neverTouched() {
        val s = snap(listOf(pr(1, "a")), listOf(run(1, "Check", "a"), run(2, "Check", "q", "in_progress", "", branch = "voice/other")),
            branches = listOf(RepoDoctor.Branch("feature/other-agent", 3)))
        val items = RepoDoctor.analyze(s, now)
        val info = items.filter { it.act == Act.INFO }
        assertEquals(2, info.size)
        assertTrue(info.any { it.branch == "feature/other-agent" && it.state.contains("PR नहीं") })
        assertTrue(info.any { it.branch == "voice/other" })
        assertTrue(RepoDoctor.actionable(items).none { it.act == Act.INFO })
    }

    @Test fun report_listsEverything_andAsksForYes() {
        val s = snap(listOf(pr(41, "a"), pr(42, "b")), listOf(run(1, "Check", "a", conclusion = "failure"), run(2, "Check", "b")))
        val items = RepoDoctor.analyze(s, now)
        val r = RepoDoctor.report(s, items)
        assertTrue(r, r.contains("खुले PR: 2") && r.contains("PR #41") && r.contains("PR #42"))
        assertTrue(r, r.contains("मैं यह करूँगा") && r.contains("11 एजेंट") && r.contains("\"हाँ\""))
        val ok = RepoDoctor.report(snap(emptyList(), emptyList()), emptyList())
        assertTrue(ok, ok.contains("कोई PR खुला नहीं") && ok.contains("सब ठीक") && !ok.contains("\"हाँ\""))
    }

    @Test fun userWords_statusFixMerge() {
        for (t in listOf("रिपो चेक करो", "मेरे रिपो में क्या चल रहा है", "कोई PR फँसा है क्या?", "build fail हुआ क्या", "कौन सा PR खुला है", "repo check karo", "पीआर की हालत बताओ"))
            assertTrue(t, RepoDoctor.isStatusAsk(t))
        for (t in listOf("login में बटन जोड़ो", "login fail होता है उसे ठीक करो", "हाँ", "नया फ़ीचर बनाओ"))
            assertFalse(t, RepoDoctor.isStatusAsk(t))
        assertEquals(42, RepoDoctor.prNumber("PR #42 ठीक करो"))
        assertEquals(41, RepoDoctor.prNumber("पीआर 41 merge करो"))
        assertEquals(7, RepoDoctor.prNumber("pr number 7"))
        assertNull(RepoDoctor.prNumber("रिपो चेक करो"))
        assertTrue(RepoDoctor.isFixAsk("PR #42 ठीक करो"))
        assertEquals(true, SafePr.mergeIntent("PR #42 merge करो"))
    }

    @Test fun fixTask_staysOnTheSameBranch_andKeepsFeatures() {
        val i = RepoDoctor.Item(Act.FIX, 42, "PR #42", "fail", run(3, "Check", "c", conclusion = "failure"), "feature/42")
        val t = RepoDoctor.fixTask(i, "main", "e: Unresolved reference: refresh")
        assertTrue(t, t.contains("PR #42") && t.contains("feature/42") && t.contains("ON THIS BRANCH"))
        assertTrue(t.contains("Keep every existing feature") && t.contains("Unresolved reference: refresh"))
    }

    @Test fun oneCheckFailed_otherStillRunning_isFixNotWait() {
        // PR #95 जैसा: compile fail, Emulator वाला अभी चल रहा - पहले "चल रहा" दिखाकर गलती छुप जाती थी
        val s = snap(listOf(pr(95, "h")), listOf(run(1, "PR build check", "h", conclusion = "failure"),
            run(2, "Build DK APK & Emulator Check", "h", "in_progress", "", startedAgoMin = 4)))
        val i = RepoDoctor.analyze(s, now).first { it.pr == 95 }
        assertEquals(Act.FIX, i.act); assertEquals(1L, i.run!!.id)
        assertTrue(i.state, i.state.contains("अभी चल रहा"))
    }

    @Test fun openPrFixesAndWatching_areAutomatic_mergeNeedsYes() {
        val s = snap(listOf(pr(1, "a"), pr(2, "b"), pr(3, "c"), pr(9, "z")), listOf(
            run(1, "Check", "a", "in_progress", "", startedAgoMin = 3),   // चल रहा → नज़र (अपने-आप)
            run(2, "Check", "b", conclusion = "failure"),                // fail → सुधार (अपने-आप)
            run(3, "Check", "c"),                                        // हरा → merge (हाँ से)
            run(4, "Check", "z", conclusion = "failure")), myPr = 9, busy = true)   // मेरी टीम काम कर रही - छूना नहीं
        val items = RepoDoctor.analyze(s, now)
        assertEquals(setOf(1, 2), RepoDoctor.auto(items).map { it.pr }.toSet())
        assertEquals(listOf(Act.MERGE), RepoDoctor.needYes(items).map { it.act })
        val r = RepoDoctor.report(s, items)
        assertTrue(r, r.contains("अपने-आप") && r.contains("merge नहीं") && r.contains("आपकी हाँ से") && r.contains("\"हाँ\""))
        assertTrue(r, r.contains("नज़र रखूँगा"))
    }
}
