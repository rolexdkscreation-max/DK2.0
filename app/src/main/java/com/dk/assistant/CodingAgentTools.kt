package com.dk.assistant

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import com.dk.assistant.CodingAgent.Call

// CodingAgent का हिस्सा: tools चलाना: पढ़ना, खोज (grep), बदलना (fuzzy replace), bracket जाँच, diff, view.
// यह code पहले CodingAgent.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state CodingAgent में)।

internal fun CodingAgent.allPaths(): List<String> {
    val gone = staged.filterValues { it == null }.keys
    val base = files?.map { it.path } ?: snap?.keys?.toList() ?: emptyList()
    return (base + staged.filterValues { it != null }.keys).distinct().filter { it !in gone }
}

internal fun CodingAgent.runTool(tool: String, path: String, c: Call, onStep: (String) -> Unit): String {
    val o = c.o
    when (tool) {
        "list" -> {
            onStep("फ़ाइलें देखीं: ${path.ifBlank { "/" }}")
            if (files == null) files = gh.tree(repo, branch)
            return allPaths().filter { path.isBlank() || it.startsWith(path.trimEnd('/') + "/") || it == path }
                .take(800).joinToString("\n").ifBlank { "(no files)" }
        }
        "read" -> {
            val many = o.optJSONArray("paths")
            if (many != null && many.length() > 0) {
                val ps = (0 until many.length()).map { many.optString(it).trim().trimStart('/') }.filter { it.isNotBlank() }.take(6)
                onStep("पढ़ रहा हूँ: " + ps.joinToString(", ") { it.substringAfterLast('/') })
                readInTask.addAll(ps)
                val per = (CoderContext.readChars(budget) * 2) / ps.size.coerceAtLeast(1)   // v4.4: मॉडल जितना पढ़ सके
                return ps.joinToString("\n\n") { p ->
                    val t = current(p)
                    "===== $p =====\n" + when {
                        t == null -> "NOT FOUND"
                        t.length > per -> CoderContext.chunk(t, 1, per, p)
                        else -> t
                    }
                }
            }
            val t = current(path) ?: return "NOT FOUND: $path" + similar(path)
            readInTask.add(path)
            val start = o.optInt("start", 0); val end = o.optInt("end", 0)
            if (start > 0 || end > 0) {
                val lines = t.lines()
                val a = start.coerceIn(1, lines.size.coerceAtLeast(1)); val b = (if (end > 0) end else a + 150).coerceIn(a, lines.size)
                onStep("पढ़ रहा हूँ: $path ($a-$b)")
                val part = "(${lines.size} lines total)\n" + (a..b).joinToString("\n") { "$it| ${lines[it - 1]}" }
                // बहुत लंबी range भी एक हिस्से (chunk) से बड़ी नहीं
                return if (part.length > CoderContext.readChars(budget)) CoderContext.chunk(t, a, CoderContext.readChars(budget), path) else part
            }
            onStep("पढ़ रहा हूँ: $path")
            // बड़ी फ़ाइल पूरी नहीं - हिस्सों में (हर हिस्सा line नंबर के साथ, अगला हिस्सा कैसे पढ़ें)
            return if (t.length > CoderContext.readChars(budget)) CoderContext.chunk(t, 1, CoderContext.readChars(budget), path) else t
        }
        "grep", "search" -> {
            val q = o.optString("pattern").ifBlank { o.optString("query") }
            if (q.isBlank()) return "ERROR: pattern missing"
            onStep("खोज: $q")
            return grep(q, path, o.optBoolean("regex", false))
        }
        "write" -> {
            if (path.isBlank()) return "ERROR: path missing"
            val content = c.file ?: return "ERROR: missing <<<FILE ... FILE>>> block"
            // पहले समझो, फिर बदलो: मौजूदा फ़ाइल को बिना पढ़े पूरा नहीं बदलना (नई फ़ाइल ठीक है)
            if (path !in readInTask && !staged.containsKey(path) && current(path) != null)
                return "ERROR: $path already exists and you have not read it in this task. Read it first (or use edit with FIND/REPLACE). Nothing staged."
            // v4.4: पूरी फ़ाइल दोबारा लिखते समय आधी से ज़्यादा गायब = गलती (बचा कोड छूट गया) - edit से छोटा बदलाव करो
            val old = current(path)
            if (CodeGuard.shrunk(old, content))
                return "ERROR: NOT staged. This write would remove ${CodeGuard.lines(old) - CodeGuard.lines(content)} of ${CodeGuard.lines(old)} lines of $path " +
                    "(more than half the file). Keep every other line exactly - use edit with FIND/REPLACE to change only what the task needs."
            remember(path)
            staged[path] = content
            onStep("लिखा: $path (${content.lines().size} लाइन)")
            return "OK staged $path (${content.length} chars)"
        }
        "edit" -> {
            if (c.finds.isEmpty() || c.finds.size != c.replaces.size)
                return "ERROR: need matching <<<FIND ... FIND>>> and <<<REPLACE ... REPLACE>>> blocks (${c.finds.size} FIND, ${c.replaces.size} REPLACE)"
            var cur = current(path) ?: return "NOT FOUND: $path" + similar(path)
            val notes = mutableListOf<String>()
            for ((k, f) in c.finds.withIndex()) {
                val r = c.replaces[k]
                if (f.isEmpty()) return "ERROR: FIND #${k + 1} is empty. Nothing applied."
                val n = cur.split(f).size - 1
                cur = when {
                    n == 1 -> cur.replace(f, r)
                    n > 1 -> return "ERROR: FIND #${k + 1} occurs $n times in $path. Include more surrounding lines. Nothing applied."
                    else -> fuzzyReplace(cur, f, r)?.also { notes.add("#${k + 1} matched ignoring indentation") }
                        ?: return "ERROR: FIND #${k + 1} not found in $path. Read the file again (use start/end) and copy the exact text. Nothing applied." + near(cur, f)
                }
            }
            remember(path); staged[path] = cur
            onStep("बदला: $path" + if (c.finds.size > 1) " (${c.finds.size} जगह)" else "")
            return "OK edited $path (${c.finds.size} change${if (c.finds.size > 1) "s" else ""})" + if (notes.isNotEmpty()) " - " + notes.joinToString() else ""
        }
        "delete" -> {
            val cur = current(path) ?: return "NOT FOUND: $path"
            // v4.4: इस्तेमाल हो रही फ़ाइल कभी नहीं हटती (पहले "option हटाओ" पर पूरी Prefs.kt/SettingsActivity.kt हट गई थीं)
            deleteBlock(path, cur)?.let { return "ERROR: NOT deleted. $it" }
            remember(path); staged[path] = null
            onStep("हटाया: $path"); return "OK staged delete $path"
        }
        "suggest" -> {
            val t = o.optString("title").trim(); val w = o.optString("why").trim(); val tk = o.optString("task").trim()
            if (t.isBlank() || tk.isBlank()) return "ERROR: suggest needs title, why and task"
            onSuggest?.invoke(t, w, tk)
            onStep("सुझाव: $t")
            return "OK, suggestion sent to the user. Do NOT implement it now. Continue the main task."
        }
        "diff" -> {
            onStep("अपने बदलाव जाँच रहा हूँ…")
            return diffAll().ifBlank { "(no staged changes)" }.let { val m = maxOf(40_000, budget / 3); if (it.length > m) it.take(m) + "\n…(diff cut)" else it }
        }
        else -> return "ERROR: unknown tool '$tool'"
    }
}

// ---------------------------------------------------------------- files
/** Staged ho to wahi, phir repo ki copy, warna GitHub se */
internal fun CodingAgent.current(path: String): String? =
    if (staged.containsKey(path)) staged[path]
    else original.getOrPut(path) { snap?.get(path) ?: if (snap != null && files?.none { it.path == path } == true) null else gh.read(repo, path, branch) }

internal fun CodingAgent.remember(path: String) { if (!original.containsKey(path)) original[path] = try { snap?.get(path) ?: gh.read(repo, path, branch) } catch (_: Exception) { null } }

internal fun CodingAgent.similar(path: String): String {
    val name = path.substringAfterLast('/')
    val hits = allPaths().filter { it.endsWith("/$name") || it.substringAfterLast('/').equals(name, true) }.take(5)
    return if (hits.isEmpty()) "" else "\nDid you mean: " + hits.joinToString()
}

/** Saari files me khojo: path:line: text */
internal fun CodingAgent.grep(q: String, folder: String, regex: Boolean): String {
    val rx = if (regex) try { Regex(q) } catch (e: Exception) { return "ERROR: bad regex: ${e.message}" } else null
    val out = StringBuilder(); var hits = 0; var filesHit = 0
    val paths = allPaths().filter { folder.isBlank() || it.startsWith(folder.trimEnd('/') + "/") }
    if (snap == null && staged.isEmpty()) {
        // repo ki copy nahi hai -> GitHub search (kam bharosemand)
        val r = try { gh.search(repo, q) } catch (_: Exception) { emptyList() }
        val byName = paths.filter { it.contains(q, true) }
        return (r + byName).distinct().take(40).joinToString("\n").ifBlank { "No results." }
    }
    for (p in paths) {
        val t = (if (staged.containsKey(p)) staged[p] else snap?.get(p)) ?: continue
        var inFile = 0
        t.lineSequence().forEachIndexed { i, line ->
            val m = if (rx != null) rx.containsMatchIn(line) else line.contains(q, true)
            if (m && hits < 120 && inFile < 25) { out.append("$p:${i + 1}: ${line.trim().take(220)}\n"); hits++; inFile++ }
        }
        if (inFile > 0) filesHit++
        if (p.contains(q, true) && inFile == 0) out.append("$p (file name matches)\n")
    }
    return if (out.isEmpty()) "No results for '$q'." else (if (hits >= 120) "(first 120 matches)\n" else "") + out.toString()
}

/** FIND exact nahi mila: har line ke aage-peeche ke space chhodkar milao (sirf ek jagah mile to) */
internal fun CodingAgent.fuzzyReplace(cur: String, find: String, rep: String): String? {
    val fl = find.lines().map { it.trim() }.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
    if (fl.isEmpty()) return null
    val cl = cur.lines()
    val hits = (0..cl.size - fl.size).filter { s -> fl.indices.all { cl[s + it].trim() == fl[it] } }
    if (hits.size != 1) return null
    val s = hits[0]
    return (cl.subList(0, s) + rep.lines() + cl.subList(s + fl.size, cl.size)).joinToString("\n")
}

/** FIND na mile to sabse milti-julti jagah dikhao (model ko sahi text copy karne me madad) */
internal fun CodingAgent.near(cur: String, find: String): String {
    val first = find.lines().map { it.trim() }.firstOrNull { it.length > 6 } ?: return ""
    val cl = cur.lines()
    val i = cl.indexOfFirst { it.trim() == first }.takeIf { it >= 0 } ?: cl.indexOfFirst { it.contains(first.take(25)) }.takeIf { it >= 0 } ?: return ""
    return "\nClosest place (lines ${i + 1}-${minOf(cl.size, i + 8)}):\n" + (i until minOf(cl.size, i + 8)).joinToString("\n") { cl[it] }
}

/** Kotlin/Java: {} () [] ka hisaab (string/comment ke andar wale chhodkar). null = theek */
internal fun CodingAgent.brackets(src: String): String? {
    val stack = ArrayDeque<Pair<Char, Int>>()
    var i = 0; var line = 1
    val n = src.length
    val pairs = mapOf('}' to '{', ')' to '(', ']' to '[')
    while (i < n) {
        val ch = src[i]
        when {
            ch == '\n' -> line++
            src.startsWith("//", i) -> { while (i < n && src[i] != '\n') i++; continue }
            src.startsWith("/*", i) -> { val e = src.indexOf("*/", i + 2); if (e < 0) return "unclosed /* comment (line $line)"; line += src.substring(i, e).count { it == '\n' }; i = e + 2; continue }
            src.startsWith("\"\"\"", i) -> { val e = src.indexOf("\"\"\"", i + 3); if (e < 0) return "unclosed \"\"\" string (line $line)"; line += src.substring(i, e).count { it == '\n' }; i = e + 3; while (i < n && src[i] == '"') i++; continue }
            ch == '"' -> {
                // saada string: \ escape; ${ ... } ke andar ke quotes ko bhi sambhalo
                i++; var depth = 0
                while (i < n) {
                    val c = src[i]
                    if (c == '\\') { i += 2; continue }
                    if (c == '\n' && depth == 0) return "string not closed on line $line"
                    if (c == '$' && i + 1 < n && src[i + 1] == '{') { depth++; i += 2; continue }
                    if (depth > 0 && c == '}') { depth--; i++; continue }
                    if (depth > 0 && c == '"') { val e = src.indexOf('"', i + 1); if (e < 0) break; i = e + 1; continue }
                    if (c == '"' && depth == 0) break
                    i++
                }
            }
            ch == '\'' -> {
                val e = if (i + 1 < n && src[i + 1] == '\\') src.indexOf('\'', i + 2) else i + 2
                if (e in (i + 1)..(i + 8) && e < n && src[e] == '\'') { i = e + 1; continue }
            }
            ch == '{' || ch == '(' || ch == '[' -> stack.addLast(ch to line)
            ch == '}' || ch == ')' || ch == ']' -> {
                val top = stack.removeLastOrNull() ?: return "extra '$ch' on line $line"
                if (top.first != pairs[ch]) return "'${top.first}' from line ${top.second} closed by '$ch' on line $line"
            }
        }
        i++
    }
    return stack.lastOrNull()?.let { "'${it.first}' opened on line ${it.second} is never closed" }
}

internal fun CodingAgent.diff(a0: String, b0: String): String {
    val a = a0.lines(); val b = b0.lines()
    var pre = 0
    while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
    var suf = 0
    while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
    val am = a.subList(pre, a.size - suf); val bm = b.subList(pre, b.size - suf)
    // (line no, op, text)  op: ' ' '-' '+'
    val ops = ArrayList<Triple<Int, Char, String>>()
    if (am.size.toLong() * bm.size <= 2_500_000L) {
        val n = am.size; val m = bm.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            dp[i][j] = if (am[i] == bm[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        var i = 0; var j = 0
        while (i < n || j < m) {
            when {
                i < n && j < m && am[i] == bm[j] -> { ops.add(Triple(pre + j + 1, ' ', am[i])); i++; j++ }
                j < m && (i >= n || dp[i][j + 1] >= dp[i + 1][j]) -> { ops.add(Triple(pre + j + 1, '+', bm[j])); j++ }
                else -> { ops.add(Triple(pre + j + 1, '-', am[i])); i++ }
            }
        }
    } else {
        am.forEach { ops.add(Triple(pre + 1, '-', it)) }
        bm.forEachIndexed { k, t -> ops.add(Triple(pre + k + 1, '+', t)) }
    }
    val ctxBefore = a.subList(maxOf(0, pre - 2), pre).mapIndexed { k, t -> Triple(maxOf(0, pre - 2) + k + 1, ' ', t) }
    val ctxAfter = b.subList(b.size - suf, minOf(b.size, b.size - suf + 2)).mapIndexed { k, t -> Triple(b.size - suf + k + 1, ' ', t) }
    val all = ctxBefore + ops + ctxAfter
    // sirf badlaav ke aas-paas ki 2 line
    val keep = BooleanArray(all.size)
    all.forEachIndexed { k, t -> if (t.second != ' ') for (x in (k - 2)..(k + 2)) if (x in all.indices) keep[x] = true }
    val sb = StringBuilder(); var gap = false
    all.forEachIndexed { k, t ->
        if (keep[k]) { if (gap) sb.append("@@ line ${t.first}\n"); gap = false; sb.append(t.second).append(t.third).append('\n') }
        else gap = true
    }
    return sb.toString().trimEnd()
}

/**
 * AI को भेजने वाला छोटा इतिहास: शुरू की repo list + अभी का काम + पिछले कुछ संदेश (पूरा chat history हर बार नहीं).
 * staged फ़ाइलों का नाम हमेशा साथ, ताकि छोटा करने पर भी एजेंट को पता रहे कि क्या बदल चुका.
 */
internal fun CodingAgent.view(): List<Pair<String, String>> {
    // repo के नियम (AGENTS.md आदि) छोटा करने पर भी साथ रहें - पर तभी जब वे पुराने हिस्से में चले गए हों
    val rules = if (projectCtx.isBlank() || msgs.size <= CoderContext.KEEP_TAIL + 6) "" else "PROJECT RULES (short):\n" + projectCtx.take(3000) + "\n\n"
    val st = if (staged.isEmpty()) "" else "STAGED (not pushed yet, the app pushes after done): " + staged.keys.joinToString()
    // v4.4: मॉडल की पूरी क्षमता (पहले हमेशा 60 हज़ार - बड़ी फ़ाइलें/लंबे काम का इतिहास कट जाता था)
    return CoderContext.compact(msgs, budget, CoderContext.KEEP_TAIL, (rules + st).trim())
}
