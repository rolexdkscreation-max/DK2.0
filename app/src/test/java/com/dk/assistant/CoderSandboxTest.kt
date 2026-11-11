package com.dk.assistant

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** नवंबर 2026: GitHub Codespaces sandbox - माँग (dk-run ref) -> नतीजा (dk-result) -> TeamTest; न चले तो null (पुराना CI) */
class CoderSandboxTest {
    private val repo = "u/DK2.0"
    private val sha = "32632ffc4a68d8164294bc403a63babaabd7a66b"

    private fun b64(s: String) = java.util.Base64.getMimeEncoder().encodeToString(s.toByteArray())

    /** नकली GitHub: codespace की हालत, नतीजा कब मिले, कौन-सी कॉल हुईं */
    class FakeApi(var states: ArrayDeque<String>, var resultAfter: Int, val resultText: String, val existing: Boolean = true, val failWith: Int = 0) : CoderSandbox.Api {
        val calls = mutableListOf<String>()
        var created: JSONObject? = null
        private var polls = 0
        override fun call(method: String, path: String, body: JSONObject?): String {
            calls += "$method $path"
            if (failWith != 0) throw GitHub.Err(failWith, "x")
            return when {
                method == "GET" && path.startsWith("/repos/u/DK2.0/codespaces?") -> JSONObject().put("codespaces", JSONArray().apply {
                    put(JSONObject().put("name", "other").put("display_name", "x").put("state", "Available").put("repository", JSONObject().put("full_name", "u/other")))
                    if (existing) put(JSONObject().put("name", "cs1").put("display_name", "dk-sandbox").put("state", states.first()).put("repository", JSONObject().put("full_name", "u/DK2.0")))
                }).toString()
                method == "GET" && path.endsWith("/codespaces/machines") -> JSONObject().put("machines", JSONArray()
                    .put(JSONObject().put("name", "standardLinux32gb").put("cpus", 4))
                    .put(JSONObject().put("name", "basicLinux32gb").put("cpus", 2))).toString()
                method == "POST" && path == "/repos/u/DK2.0/codespaces" -> { created = body; JSONObject().put("name", "cs1").put("state", "Provisioning").toString() }
                method == "GET" && path == "/user/codespaces/cs1" -> JSONObject().put("state", if (states.size > 1) states.removeFirst() else states.first()).toString()
                method == "GET" && path.startsWith("/repos/u/DK2.0/contents/dk-result.txt") ->
                    if (++polls >= resultAfter) JSONObject().put("content", java.util.Base64.getMimeEncoder().encodeToString(resultText.toByteArray())).toString()
                    else throw GitHub.Err(404, "नहीं मिला")
                else -> "{}"
            }
        }
    }

    private fun box(api: FakeApi): CoderSandbox { var t = 0L; return CoderSandbox(api, repo, { t }, { t += it }) }

    @Test fun passFlow() = runBlocking {
        val api = FakeApi(ArrayDeque(listOf("Available")), 2, "DK-RESULT OK\nsecs=74\nsha=$sha\n-----\nBUILD SUCCESSFUL")
        val steps = mutableListOf<String>()
        val t = box(api).check(sha, { steps += it })!!
        assertEquals(true, t.ok)
        assertEquals(TeamTest.BRANCH, t.stage)
        assertTrue(t.note.contains("74s"))
        assertTrue(api.calls.contains("POST /repos/u/DK2.0/git/refs"))
        assertTrue(api.calls.contains("DELETE /repos/u/DK2.0/git/refs/heads/dk-run/32632ffc4a68"))
        assertTrue(api.calls.contains("DELETE /repos/u/DK2.0/git/refs/heads/dk-result/32632ffc4a68"))
        assertTrue(steps.any { it.contains("पास") })
    }

    @Test fun failGivesRealCompilerLines() = runBlocking {
        val api = FakeApi(ArrayDeque(listOf("Available")), 1,
            "DK-RESULT FAIL\nsecs=40\nsha=$sha\ne: file:///w/app/src/main/java/com/dk/assistant/X.kt:12:5 Unresolved reference: foo\n-----\nBUILD FAILED")
        val t = box(api).check(sha, {})!!
        assertEquals(false, t.ok)
        assertTrue(t.log, t.log.contains("X.kt:12:5 Unresolved reference: foo"))
        assertFalse(t.log.contains("secs="))
    }

    @Test fun startsStoppedCodespaceAndWaits() = runBlocking {
        val api = FakeApi(ArrayDeque(listOf("Shutdown", "Starting", "Available")), 1, "DK-RESULT OK\nsecs=5")
        assertEquals(true, box(api).check(sha, {})?.ok)
        assertTrue(api.calls.contains("POST /user/codespaces/cs1/start"))
    }

    @Test fun createsWithSmallestMachineWhenMissing() = runBlocking {
        val api = FakeApi(ArrayDeque(listOf("Available")), 1, "DK-RESULT OK\nsecs=5", existing = false)
        val steps = mutableListOf<String>()
        box(api).check(sha, { steps += it })
        val b = api.created!!
        assertEquals("basicLinux32gb", b.getString("machine"))
        assertEquals("main", b.getString("ref"))
        assertEquals(".devcontainer/devcontainer.json", b.getString("devcontainer_path"))
        assertEquals("dk-sandbox", b.getString("display_name"))
        assertTrue(steps.any { it.contains("पहली बार") })
    }

    @Test fun noResultInTimeFallsBackToCi() = runBlocking {
        val api = FakeApi(ArrayDeque(listOf("Available")), Int.MAX_VALUE, "")
        val steps = mutableListOf<String>()
        assertNull(box(api).check(sha, { steps += it }))
        assertTrue(api.calls.contains("DELETE /repos/u/DK2.0/git/refs/heads/dk-run/32632ffc4a68"))
        assertTrue(steps.last().contains("GitHub Actions"))
    }

    @Test fun neverStartsFallsBackToCi() = runBlocking {
        val api = FakeApi(ArrayDeque(listOf("Starting")), 1, "DK-RESULT OK")
        assertNull(box(api).check(sha, {}))
        assertFalse(api.calls.any { it.startsWith("POST /repos/u/DK2.0/git/refs") })
    }

    @Test fun noPermissionDisablesOnceAndFallsBack() = runBlocking {
        val api = FakeApi(ArrayDeque(listOf("Available")), 1, "", failWith = 403)
        val b = box(api)
        assertNull(b.check(sha, {}))
        assertTrue(b.off, b.off.contains("Codespaces की इजाज़त"))
        val n = api.calls.size
        assertNull(b.check(sha, {}))
        assertEquals(n, api.calls.size)    // बंद = दोबारा कोई कॉल नहीं
    }

    @Test fun pureHelpers() {
        assertEquals("32632ffc4a68", Sandbox.key(sha.uppercase()))
        assertNull(Sandbox.parse("random text"))
        assertNull(Sandbox.parse(""))
        assertEquals(Sandbox.R(true, 12, "ok"), Sandbox.parse("DK-RESULT OK\nsecs=12\nsha=abc\nok"))
        assertEquals("hi", Sandbox.decode(JSONObject().put("content", b64("hi"))))
        assertNull(Sandbox.decode(JSONObject()))
        assertTrue(Sandbox.ready("Available")); assertFalse(Sandbox.ready("Starting"))
        assertTrue(Sandbox.needsStart("Shutdown")); assertTrue(Sandbox.dead("Failed"))
        assertNull(Sandbox.machine(JSONArray()))
        val dead = JSONArray().put(JSONObject().put("display_name", "dk-sandbox").put("state", "Failed").put("repository", JSONObject().put("full_name", repo)))
        assertNull(Sandbox.pick(dead, repo))
        assertTrue(Sandbox.why(GitHub.Err(402, "")).contains("फ़्री घंटे"))
        assertEquals("", Sandbox.toTest(Sandbox.R(true, 1, "x")).log)
    }
}
