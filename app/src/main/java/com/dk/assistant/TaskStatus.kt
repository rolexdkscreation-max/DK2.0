package com.dk.assistant

import java.util.concurrent.CopyOnWriteArrayList

/**
 * v3.31: पीछे चल रहे कामों (DK कंप्यूटर, कोडिंग टीम) की **असली** हालत - एक जगह.
 * सिर्फ़ वही दर्ज होता है जो सच में हुआ: काम शुरू हुआ, असली कदम (session की "s" लाइनें), इजाज़त का इंतज़ार, खत्म/फ़ेल/रोका.
 * कोई नकली % या अंदाज़ा नहीं (PR #32 जैसा "हर 2 सेकंड 25%" नहीं).
 * DK लाइव: task_status tool + काम खत्म होने पर SYSTEM NOTE. फ़ोन: notification में असली कदम.
 * Android import नहीं -> JVM test.
 */
object TaskStatus {
    const val PC = "pc"
    const val CODER = "coder"

    enum class State(val hi: String, val en: String) {
        RUNNING("चल रहा है", "RUNNING"),
        WAITING("आपकी इजाज़त/जवाब का इंतज़ार", "WAITING FOR THE USER"),
        DONE("पूरा हुआ", "FINISHED"),
        NOT_DONE("खत्म, पर काम पूरा नहीं", "ENDED BUT NOT COMPLETE"),
        FAILED("रुका / फ़ेल", "FAILED"),
        CANCELLED("रोक दिया गया", "CANCELLED"),
    }

    data class Job(
        val key: String, val who: String, val whoEn: String, val task: String, val started: Long,
        val steps: Int = 0, val last: String = "", val lastAt: Long = started,
        val state: State = State.RUNNING, val result: String = "", val ended: Long = 0L,
    ) {
        val active get() = state == State.RUNNING || state == State.WAITING
    }

    fun interface Listener { fun changed(job: Job, ended: Boolean) }

    private val jobs = LinkedHashMap<String, Job>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    fun listen(l: Listener) { listeners.addIfAbsent(l) }
    fun unlisten(l: Listener) { listeners.remove(l) }

    private fun emit(j: Job, ended: Boolean) = listeners.forEach { try { it.changed(j, ended) } catch (_: Exception) {} }

    private fun clean(t: String, max: Int = 200) = t.replace(Regex("\\s+"), " ").trim().take(max)

    fun start(key: String, who: String, whoEn: String, task: String) {
        val j = synchronized(this) { Job(key, who, whoEn, clean(task, 160), clock()).also { jobs[key] = it } }
        emit(j, false)
    }

    /** असली कदम - सिर्फ़ चलते काम पर (बाकी समय कुछ नहीं) */
    fun step(key: String, text: String) {
        val t = clean(text)
        if (t.isBlank()) return
        val j = synchronized(this) {
            val o = jobs[key]?.takeIf { it.active } ?: return
            o.copy(steps = o.steps + 1, last = t, lastAt = clock(), state = State.RUNNING).also { jobs[key] = it }
        }
        emit(j, false)
    }

    fun waiting(key: String, text: String) {
        val j = synchronized(this) {
            val o = jobs[key]?.takeIf { it.active } ?: return
            o.copy(last = clean(text), lastAt = clock(), state = State.WAITING).also { jobs[key] = it }
        }
        emit(j, false)
    }

    fun end(key: String, state: State, result: String) {
        val j = synchronized(this) {
            val o = jobs[key]?.takeIf { it.active } ?: return
            // WAITING = रुका है, खत्म नहीं: ended 0 (summary में "चल रहा/इंतज़ार" जैसा), नतीजा = क्या पूछा
            if (state == State.WAITING) o.copy(state = state, last = clean(result), lastAt = clock(), result = clean(result, 400)).also { jobs[key] = it }
            else o.copy(state = state, result = clean(result, 400), ended = clock())
                .also { jobs[key] = it }
        }
        emit(j, true)
    }

    fun get(key: String): Job? = synchronized(this) { jobs[key] }
    fun all(): List<Job> = synchronized(this) { jobs.values.toList() }

    private fun ago(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
    }

    /** DK लाइव के task_status tool का जवाब - सिर्फ़ दर्ज सच */
    fun summary(now: Long = clock()): String {
        val list = all().filter { it.active || now - it.ended < 60 * 60_000L }
        if (list.isEmpty()) return "No background task (DK Computer / coding team) has been started recently. Tell the user honestly that nothing is running."
        return list.joinToString("\n") { j ->
            // हालत सबसे पहले; काम का text साफ़ "निर्देश" (हालत नहीं) - v3.39: model निर्देश को ही नतीजा समझ लेता था
            val head = "STATE: ${j.state.en} - ${j.whoEn}" + (if (j.active) " (NOT DONE YET)" else "") +
                " - the request it was given (an instruction, NOT a status): \"${j.task}\""
            when {
                j.state == State.WAITING -> "$head; waiting for the user since ${ago(now - j.lastAt)}; it asked: \"${j.last.ifBlank { "-" }}\""
                j.active -> "$head; running for ${ago(now - j.started)}; ${j.steps} real steps so far" +
                    (if (j.last.isNotBlank()) "; latest step (${ago(now - j.lastAt)} ago): \"${j.last}\"" else "; no step reported yet")
                else -> "$head; ended ${ago(now - j.ended)} ago after ${ago(j.ended - j.started)}; result: \"${j.result.ifBlank { "-" }}\""
            }
        } + "\nTell the user ONLY these facts, briefly, in the reply language. Never invent progress, percentages or results. Say it is done ONLY if the STATE is FINISHED. Never say a PR is merged or an APK is ready/released unless the result says exactly that."
    }

    /** काम खत्म होने पर DK लाइव को (वही बोले, सच) */
    fun endNote(j: Job): String =
        if (j.state == State.WAITING)
            "SYSTEM NOTE: ${j.whoEn} is NOT done - it is WAITING FOR THE USER's approval/answer. It asked: \"${j.result.ifBlank { j.last }.ifBlank { "-" }}\". " +
                "Tell the user briefly in the reply language what it understood and that it needs their \"हाँ\" (or a correction) to start. " +
                "Do NOT say the work is complete, and do not say any APK is ready."
        else "SYSTEM NOTE: the background task given to ${j.whoEn} (\"${j.task}\") has now really ended - state: ${j.state.en}. " +
            "Result: \"${j.result.ifBlank { "-" }}\". Tell the user this briefly in the reply language. " +
            (if (j.state == State.DONE) "" else "Do NOT say the work is complete. ") +
            "Never say a PR is merged or an APK is ready/released unless the result says exactly that. Do not invent details."

    /** notification की लाइन (हिंदी) */
    fun line(j: Job, now: Long = clock()): String {
        val t = ((now - j.started) / 1000).coerceAtLeast(0)
        val time = if (t < 60) "$t सेकंड" else "${t / 60}:${"%02d".format(t % 60)} मिनट"
        return "${j.state.hi} • ${j.steps} कदम • $time" + if (j.last.isNotBlank()) "\n${j.last}" else ""
    }

    /** कोडिंग session की आख़िरी लाइन से सच्ची हालत (7-एजेंट रिपोर्ट: "✅ काम पूरा हुआ" सिर्फ़ चेक पास पर) */
    fun coderState(lastRole: String?, lastText: String?, cancelled: Boolean): State = when {
        cancelled -> State.CANCELLED
        // v3.39: प्रॉम्प्ट इंजीनियर ने समझ बताई और "हाँ" का इंतज़ार - यह "पूरा" नहीं (पहले "d" = FINISHED गिना जाता था)
        lastText.orEmpty().contains("मैंने आपकी बात ऐसे समझी") -> State.WAITING
        lastText.orEmpty().contains("⏳ काम अभी पूरा नहीं") -> State.NOT_DONE
        lastRole == "e" || lastText.orEmpty().contains("❌") -> State.FAILED
        lastRole == "d" -> State.DONE
        else -> State.NOT_DONE
    }

    /** test के लिए */
    fun clear() = synchronized(this) { jobs.clear() }
}
