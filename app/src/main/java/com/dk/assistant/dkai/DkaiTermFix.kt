package com.dk.assistant.dkai

/**
 * 14 नवंबर 2026 (मालिक: "टर्मिनल में कमांड चलाकर error खुद ठीक करो और हर कदम बताओ").
 *
 * DK लाइव का टर्मिनल "काम" (job) - pure, JVM test से जाँचा ([DkaiShell.liveTool] इसे इस्तेमाल करता है):
 *  1. पहली बनाने / बदलने वाली कमांड पर मालिक से एक बार "हाँ" ([DkaiTerm.liveGate]). हाँ मिलते ही [Job] शुरू.
 *  2. उसी काम की अगली कमांड (ठीक करने वाली भी) [JOB_WINDOW_MS] तक, [MAX_STEPS] कदम तक बिना दोबारा पूछे -
 *     सिवाय खतरनाक (rm / mv / overwrite…) के, वह हर बार पूछी जाती है.
 *  3. कमांड fail = [diagnose] error पढ़कर कारण + सुझाव देता है; DK ठीक करने वाली कमांड चलाता है.
 *     लगातार [MAX_FIXES] बार fail = रुककर मालिक को साफ़ बताता है क्या अटका.
 *  4. हर कदम पर [stepNote] - DK एक लाइन में बताता है कि अभी क्या हुआ और आगे क्या करेगा.
 */
object DkaiTermFix {
    const val JOB_WINDOW_MS = 10 * 60_000L
    const val MAX_STEPS = 12
    const val MAX_FIXES = 3

    /** मालिक की "हाँ" से शुरू हुआ काम. [fails] = लगातार कितनी बार fail */
    data class Job(val startedAt: Long, val steps: Int = 0, val fails: Int = 0)

    /** यह कमांड इसी काम में बिना दोबारा पूछे चल सकती है? */
    fun canContinue(job: Job?, risky: Boolean, now: Long): Boolean =
        job != null && !risky && now - job.startedAt in 0..JOB_WINDOW_MS && job.steps < MAX_STEPS && job.fails < MAX_FIXES

    /** कमांड चलने के बाद काम का नया हाल */
    fun after(job: Job, failed: Boolean): Job = job.copy(steps = job.steps + 1, fails = if (failed) job.fails + 1 else 0)

    /** output से पता: fail हुई? (exit code के अलावा DK के built-in जो exit 0 देकर भी "नहीं हुआ" लिखते हैं) */
    fun failed(code: Int, out: String): Boolean {
        if (code != 0) return true
        val low = out.lowercase()
        return FAIL_WORDS.any { low.contains(it) }
    }

    private val FAIL_WORDS = listOf("नहीं हुआ", "नहीं मिला", "index.html नहीं", "नहीं बना", "नहीं खुला", "नहीं मिली", "नहीं पहचाना", "चल नहीं पाई", "not found", "no such file", "permission denied",
        "syntax error", "error:", "failed", "cannot ", "can't ")

    /** एक error = कारण (हिंदी) + ठीक करने का तरीका (AI के लिए) */
    data class Diagnosis(val why: String, val fix: String)

    /** error पढ़कर कारण बताओ (pure). पहचान न हो = null (तब DK खुद output पढ़कर तय करे) */
    fun diagnose(cmd: String, out: String): Diagnosis? {
        val low = out.lowercase()
        val head = DkaiTerm.head(cmd.trim())
        return when {
            low.contains("index.html नहीं") -> Diagnosis("इस फ़ोल्डर में index.html नहीं है",
                "make the app first: `apk make <app description>` (it writes the code and builds the APK), or create index.html and run `apk build` again")
            low.contains("फ़ोल्डर नहीं मिला") || (head == "cd" && (low.contains("no such") || low.contains("नहीं"))) -> Diagnosis("यह फ़ोल्डर मौजूद नहीं",
                "run `ls` to see the real folder names, then use the right name, or `mkdir -p <name>` if it should be created")
            Regex("""(not found|inaccessible or not found)""").containsMatchIn(low) && !low.contains("no such file") -> Diagnosis("'$head' कमांड इस फ़ोन के टर्मिनल में नहीं है",
                "this phone shell has no '$head' (no npm/node/python/gradle). Use a DK built-in instead: apk make/build/install for apps, wget/curl, zip/unzip, tree, sysinfo - or plain toybox commands")
            low.contains("no such file") -> Diagnosis("फ़ाइल या फ़ोल्डर उस रास्ते पर नहीं मिला",
                "run `ls` / `tree` to find the real path, then repeat the command with the correct path")
            low.contains("permission denied") || low.contains("बाहर") -> Diagnosis("उस जगह पर लिखने / चलाने की इजाज़त नहीं",
                "work only inside the Workspace (cd ~). For a script use `sh script.sh` instead of ./script.sh")
            low.contains("syntax error") || low.contains("unexpected") -> Diagnosis("कमांड लिखने में गलती (quote / bracket)",
                "fix the quoting or split it into smaller commands and run again")
            low.contains("unknownhost") || low.contains("unable to resolve") || low.contains("timeout") || low.contains("timed out") -> Diagnosis("इंटरनेट से नहीं जुड़ पाया",
                "check the URL; if it is right, ask the user to check the internet - do not retry more than once")
            low.contains("[30 सेकंड") || low.contains("stopped after 30") -> Diagnosis("कमांड 30 सेकंड में पूरी नहीं हुई",
                "run a smaller / faster step instead")
            else -> null
        }
    }

    /** DK लाइव को: कमांड का असली नतीजा + अब क्या करना है (हर कदम मालिक को बताना) */
    fun stepNote(cmd: String, failed: Boolean, job: Job?, d: Diagnosis?): String = when {
        !failed -> "STEP DONE: `$cmd` worked." +
            (if (job != null) " This is step ${job.steps} of the job he approved. If more steps are needed for what he asked, tell him in ONE short Hindi line what worked and what you do next, then call terminal with the next command (no new yes needed unless it deletes / moves / overwrites). When everything is done, tell him the final result." else
                " Tell him briefly in Hindi what happened.")
        job == null -> "STEP FAILED: `$cmd` did not work." + (d?.let { " Reason: ${it.why}. Fix: ${it.fix}." } ?: " Read the OUTPUT to find why.") +
            " Tell him in ONE short Hindi line what went wrong and what you will try; the fix command will ask for his yes if it changes something."
        job.fails >= MAX_FIXES -> "STOP: `$cmd` failed ${job.fails} times in a row. Do NOT try again. Tell the user in 2 short Hindi lines exactly what is stuck" +
            (d?.let { " (${it.why})" } ?: "") + " and what he can do."
        else -> "STEP FAILED (try ${job.fails} of $MAX_FIXES): `$cmd` did not work." + (d?.let { " Reason: ${it.why}. Fix: ${it.fix}." } ?: " Read the OUTPUT and find the cause yourself.") +
            " Tell him in ONE short Hindi line what went wrong and what you are trying now, then call terminal again at once with the fixing command (it runs without a new yes, unless it deletes / moves / overwrites)."
    }

    /** Terminal स्क्रीन पर दिखने वाली कदम की लाइन */
    fun stepLine(job: Job?, failed: Boolean, d: Diagnosis?): String {
        val n = job?.steps?.let { "कदम $it" } ?: "कदम"
        return if (!failed) "✓ $n ठीक\n" else "✗ $n नहीं हुआ" + (d?.let { " - ${it.why}" } ?: "") +
            (if (job != null && job.fails >= MAX_FIXES) " • DK रुक गया\n" else if (job != null) " • DK ठीक कर रहा है…\n" else "\n")
    }
}
