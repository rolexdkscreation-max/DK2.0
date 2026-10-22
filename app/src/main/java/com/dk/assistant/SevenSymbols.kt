package com.dk.assistant

/**
 * रिव्यूअर की पक्की (बिना AI) जाँच: नए code में जो function / class / variable बुलाया गया है, वह सच में मौजूद है?
 * (user का नियम: "कोई भी नाम इस्तेमाल करने से पहले उसकी फ़ाइल खोलकर पढ़ो, अंदाज़े से नाम मत लिखो")
 *
 * उदाहरण (PR #32): `svc.speak(...)` - svc = DKAccessibilityService, उसमें speak नाम का कुछ नहीं (असली: svc.speaker.speak).
 *
 * सिर्फ़ .kt / .java. सिर्फ़ नई लाइनें जाँचते हैं (पुराना code पहले से चल रहा है).
 * sure = true  -> पक्का नहीं मिला (बदलाव रद्द).
 * sure = false -> रिपो में नहीं मिला पर बाहर की library/Android की parent class में हो सकता है -> AI रिव्यूअर से सबूत के साथ पक्का करवाओ.
 * Android import नहीं -> JVM test.
 */
object SevenSymbols {
    data class Miss(val name: String, val where: String, val sure: Boolean, val known: List<String> = emptyList()) {
        fun hindi() = "\"$name\" मौजूद नहीं — $where" + if (known.isNotEmpty()) " (वहाँ असल में ये हैं: ${known.take(25).joinToString()})" else ""
        fun english() = "$name: $where" + if (known.isNotEmpty()) ". Members that DO exist there: ${known.take(40).joinToString()}" else ""
    }

    private val KEYWORDS = setOf(
        "if", "for", "while", "when", "catch", "return", "super", "this", "fun", "constructor", "init", "throw", "try", "else", "do",
        "is", "in", "as", "object", "typeof", "switch", "new", "synchronized", "assert", "sizeof", "val", "var", "class", "interface",
        "get", "set", "field", "value", "it", "suspend", "inline", "operator", "where", "by", "import", "package", "out",
        // infix (a shl (b), 0 until (n) ...)
        "shl", "shr", "ushr", "and", "or", "xor", "until", "downTo", "step", "to", "rangeTo", "zip", "union", "intersect", "subtract",
    )

    /** Kotlin/Java/kotlinx/Android के आम top-level functions (रिपो में declare नहीं होते) */
    private val BARE_OK = setOf(
        "listOf", "listOfNotNull", "mutableListOf", "arrayListOf", "setOf", "mutableSetOf", "hashSetOf", "linkedSetOf", "sortedSetOf",
        "mapOf", "mutableMapOf", "hashMapOf", "linkedMapOf", "sortedMapOf", "emptyList", "emptyMap", "emptySet", "emptyArray", "arrayOf",
        "arrayOfNulls", "intArrayOf", "longArrayOf", "floatArrayOf", "doubleArrayOf", "byteArrayOf", "charArrayOf", "booleanArrayOf",
        "shortArrayOf", "IntArray", "LongArray", "FloatArray", "DoubleArray", "ByteArray", "CharArray", "BooleanArray", "ShortArray",
        "println", "print", "require", "requireNotNull", "check", "checkNotNull", "error", "repeat", "maxOf", "minOf", "run", "with",
        "apply", "also", "let", "takeIf", "takeUnless", "lazy", "lazyOf", "buildString", "buildList", "buildMap", "buildSet", "TODO",
        "delay", "launch", "async", "withContext", "withTimeout", "withTimeoutOrNull", "runBlocking", "coroutineScope", "supervisorScope",
        "yield", "ensureActive", "runCatching", "synchronized", "sequenceOf", "sequence", "generateSequence", "emptySequence",
        "assertEquals", "assertTrue", "assertFalse", "assertNull", "assertNotNull", "assertSame", "assertNotSame", "assertArrayEquals",
        "fail", "assertThrows", "abs", "sqrt", "pow", "min", "max", "round", "floor", "ceil", "sin", "cos", "tan", "atan2", "exp", "ln",
        "log10", "log2", "hypot", "sign", "toString", "hashCode", "equals", "invoke", "Pair", "Triple", "Regex", "StringBuilder",
        "measureTimeMillis", "measureNanoTime", "thread", "use", "suspendCoroutine", "suspendCancellableCoroutine", "Channel", "flow",
        "flowOf", "MutableStateFlow", "MutableSharedFlow", "CompletableDeferred", "Job", "SupervisorJob", "CoroutineScope", "Mutex",
        "produce", "actor", "select", "awaitAll", "joinAll", "cancel", "isActive", "require", "lazyFast", "contract", "enumValues", "enumValueOf",
    )

    /** Kotlin/Java के आम type (import बिना मिलते हैं) */
    private val TYPE_OK = setOf(
        "String", "Int", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Char", "Unit", "Any", "Nothing", "Number", "Array",
        "List", "MutableList", "ArrayList", "Map", "MutableMap", "HashMap", "LinkedHashMap", "Set", "MutableSet", "HashSet", "LinkedHashSet",
        "Collection", "Iterable", "Sequence", "Pair", "Triple", "Regex", "StringBuilder", "Exception", "RuntimeException", "Throwable",
        "Error", "IllegalStateException", "IllegalArgumentException", "IndexOutOfBoundsException", "NullPointerException",
        "UnsupportedOperationException", "NumberFormatException", "ArithmeticException", "ClassCastException", "InterruptedException",
        "System", "Math", "Thread", "Runnable", "Comparable", "Comparator", "Enum", "Character", "Integer", "Object", "Result",
        "CharSequence", "Lazy", "Charsets", "Annotation", "Deprecated", "Suppress", "JvmStatic", "JvmField", "JvmOverloads", "Volatile",
        "Synchronized", "Transient", "Throws", "Override", "FunctionalInterface", "SafeVarargs", "Test", "Before", "After", "Ignore",
        "UInt", "ULong", "Iterator", "MutableIterator", "ArrayDeque", "Void", "Process", "Runtime", "Cloneable", "AutoCloseable",
        "Retention", "Target", "Metadata", "Experimental", "OptIn", "PublishedApi", "Kotlin", "KotlinVersion",
    )

    /**
     * comments और strings की लिखावट हटाओ (code और "${...}" के अंदर का code रहे), लाइनें वैसी ही रहें
     * (ताकि लाइन नंबर बदलें नहीं).
     */
    internal fun strip(src: String): String {
        val out = StringBuilder(src.length)
        val back = ArrayDeque<Int>()      // template से किस string में लौटना है (1 = "…", 2 = """…""")
        val base = ArrayDeque<Int>()      // template शुरू होते समय { की गहराई
        var depth = 0; var state = 0; var i = 0
        val n = src.length
        while (i < n) {
            val ch = src[i]; val nx = if (i + 1 < n) src[i + 1] else '\u0000'
            when (state) {
                0 -> when {
                    ch == '/' && nx == '/' -> { while (i < n && src[i] != '\n') i++ }
                    ch == '/' && nx == '*' -> {
                        val e = src.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                        src.substring(i, e).forEach { if (it == '\n') out.append('\n') }; i = e
                    }
                    src.startsWith("\"\"\"", i) -> { out.append("\"\""); state = 2; i += 3 }
                    ch == '"' -> { out.append('"'); state = 1; i++ }
                    ch == '\'' -> {
                        var k = i + 1
                        if (k < n && src[k] == '\\') k += 2 else k++
                        while (k < n && k - i < 10 && src[k] != '\'' && src[k] != '\n') k++
                        out.append("' '"); i = if (k < n && src[k] == '\'') k + 1 else k
                    }
                    ch == '}' && base.isNotEmpty() && depth == base.last() -> { base.removeLast(); state = back.removeLast(); i++ }
                    else -> { if (ch == '{') depth++ else if (ch == '}') depth--; out.append(ch); i++ }
                }
                1 -> when {
                    ch == '\\' -> i += 2
                    ch == '"' -> { out.append('"'); state = 0; i++ }
                    ch == '$' && nx == '{' -> { back.addLast(1); base.addLast(depth); state = 0; out.append(' '); i += 2 }
                    ch == '\n' -> { out.append('\n'); state = 0; i++ }
                    else -> i++
                }
                else -> when {
                    src.startsWith("\"\"\"", i) -> { i += 3; while (i < n && src[i] == '"') i++; state = 0 }
                    ch == '$' && nx == '{' -> { back.addLast(2); base.addLast(depth); state = 0; out.append(' '); i += 2 }
                    else -> { if (ch == '\n') out.append('\n'); i++ }
                }
            }
        }
        return out.toString()
    }

    /** after की कौन-सी लाइनें नई हैं (before में नहीं थीं) - लाइन नंबर */
    private fun addedIdx(before: String?, after: String): Set<Int> {
        val lines = after.lines()
        if (before == null) return lines.indices.toSet()
        val old = HashMap<String, Int>()
        before.lines().forEach { old[it.trim()] = (old[it.trim()] ?: 0) + 1 }
        val out = HashSet<Int>()
        lines.forEachIndexed { k, l ->
            val t = l.trim(); val c = old[t] ?: 0
            if (c > 0) old[t] = c - 1 else if (t.isNotEmpty()) out.add(k)
        }
        return out
    }

    /** नई (जोड़ी गई) लाइनें - before में नहीं थीं */
    fun addedLines(before: String?, after: String): String {
        if (before == null) return after
        val old = HashMap<String, Int>()
        before.lines().forEach { old[it.trim()] = (old[it.trim()] ?: 0) + 1 }
        return after.lines().filter { l ->
            val k = l.trim(); val n = old[k] ?: 0
            if (n > 0) { old[k] = n - 1; false } else k.isNotEmpty()
        }.joinToString("\n")
    }

    private fun declares(text: String, name: String): Boolean {
        val n = Regex.escape(name)
        return Regex("\\b(?:fun|val|var|class|object|interface|typealias|enum\\s+class|annotation\\s+class|const\\s+val)\\s+(?:<[^>]*>\\s*)?(?:[\\w.<>?, ]+\\.)?$n\\b").containsMatchIn(text) ||
            Regex("\\b(?:val|var)\\s*\\([^)]*\\b$n\\b").containsMatchIn(text)          // val (a, b) = ...
    }

    /** इस नाम को declare करने वाली class/object/interface की फ़ाइलें */
    private fun typeFiles(repo: Map<String, String>, type: String): List<Pair<String, String>> =
        repo.entries.filter { (p, c) -> (p.endsWith(".kt") || p.endsWith(".java")) &&
            Regex("\\b(?:class|object|interface)\\s+${Regex.escape(type)}\\b").containsMatchIn(c) }.map { it.key to it.value }

    /** type की class का हिस्सा: "class Type ... {" से उसके बंद होने तक (मोटे तौर पर; न मिले तो पूरी फ़ाइल) */
    private fun typeBody(src: String, type: String): String {
        val m = Regex("\\b(?:class|object|interface)\\s+${Regex.escape(type)}\\b").find(src) ?: return src
        var i = src.indexOf('{', m.range.last).takeIf { it >= 0 } ?: return src.substring(m.range.first)
        // header (constructor के val/var भी member हैं)
        val header = src.substring(m.range.first, i)
        var depth = 0
        val start = i
        while (i < src.length) {
            when (src[i]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) return header + src.substring(start, i + 1) } }
            i++
        }
        return header + src.substring(start)
    }

    private fun superTypes(src: String, type: String): List<String> {
        val m = Regex("\\b(?:class|object|interface)\\s+${Regex.escape(type)}\\b([^{]*)").find(src) ?: return emptyList()
        val head = m.groupValues[1]
        // ")" के बाद वाला ":" (constructor के parameters वाले ":" नहीं)
        var depth = 0; var colon = -1
        for ((k, ch) in head.withIndex()) {
            when (ch) { '(' -> depth++; ')' -> depth--; ':' -> if (depth == 0) { colon = k; break } }
        }
        val java = Regex("\\b(?:extends|implements)\\s+([\\w., <>]+)").findAll(head).flatMap { it.groupValues[1].split(',') }.toList()
        val kt = if (colon >= 0) head.substring(colon + 1).split(',') else emptyList()
        return (kt + java).map { it.trim().substringBefore('(').substringBefore('<').substringBefore(' ').trim() }.filter { it.isNotBlank() && it[0].isUpperCase() }
    }

    private fun members(body: String): List<String> =
        Regex("\\b(?:fun|val|var)\\s+(?:<[^>]*>\\s*)?(?:[\\w.<>?]+\\.)?([a-zA-Z_]\\w*)").findAll(body).map { it.groupValues[1] }.distinct().toList()

    private fun declRe(name: String) = Regex("\\b(?:fun|val|var)\\s+(?:<[^>]*>\\s*)?(?:[\\w.<>?]+\\.)?${Regex.escape(name)}\\b")

    /** type में (या रिपो वाली parent classes में, या extension function) name है? null = पता नहीं (बाहर की parent class) */
    private fun hasMember(repo: Map<String, String>, type: String, name: String, seen: MutableSet<String> = mutableSetOf()): Boolean? {
        if (!seen.add(type)) return false
        val n = Regex.escape(name)
        // extension: fun Type.name( / val Type.name
        if (repo.values.any { Regex("\\b(?:fun|val|var)\\s+(?:<[^>]*>\\s*)?${Regex.escape(type)}\\??\\.$n\\b").containsMatchIn(it) }) return true
        val files = typeFiles(repo, type)
        if (files.isEmpty()) return null
        var unknown = false
        for ((p, src) in files) {
            // पूरी फ़ाइल (companion/nested समेत) - brace गिनने की ग़लती से बचने को
            if (Regex("\\b(?:fun|val|var)\\s+(?:<[^>]*>\\s*)?$n\\b").containsMatchIn(src)) return true
            if (p.endsWith(".java") && Regex("\\b$n\\s*\\(").containsMatchIn(src)) return true
            if (Regex("\\b(?:class|object|interface)\\s+$n\\b").containsMatchIn(src)) return true
            if (Regex("\\benum\\s+class\\s+${Regex.escape(type)}\\b").containsMatchIn(src) && Regex("(?m)^\\s*$n\\s*[,(;{]").containsMatchIn(src)) return true
            for (sup in superTypes(src, type)) {
                when (hasMember(repo, sup, name, seen)) { true -> return true; null -> unknown = true; false -> {} }
            }
        }
        return if (unknown) null else false
    }

    /** रिपो में कहीं `x.name(` जहाँ x इसी type का है? (मतलब पहले से चल रहा है) */
    private fun usedOnType(files: Map<String, String>, type: String, name: String): Boolean {
        val re = Regex("\\b([A-Za-z_]\\w*)\\s*(?:\\?|!!)?\\.\\s*${Regex.escape(name)}\\s*\\(")
        return files.values.any { src -> re.findAll(src).any { typeAt(src, it.groupValues[1], it.range.first) == type } }
    }

    /** किन फ़ाइलों में name का fun/val/var है */
    private fun declaredIn(files: Map<String, String>, name: String): List<String> =
        files.filter { declRe(name).containsMatchIn(it.value) }.keys.map { it.substringAfterLast('/') }

    /**
     * recv का type: इस्तेमाल की जगह (pos) से पहले वाली **सबसे नज़दीकी** declaration से.
     * वह बिना type वाली हो (val x = …, lambda का x ->, for (x in …)) = पता नहीं (null).
     */
    private fun typeAt(file: String, recv: String, pos: Int): String? {
        if (recv[0].isUpperCase()) return recv
        val r = Regex.escape(recv)
        data class D(val at: Int, val type: String?)
        val ds = ArrayList<D>()
        fun typed(re: Regex) = re.findAll(file).forEach { ds.add(D(it.range.first, it.groupValues[1])) }
        fun untyped(re: Regex) = re.findAll(file).forEach { ds.add(D(it.range.first, null)) }
        typed(Regex("\\b(?:val|var)\\s+$r\\s*:\\s*([A-Z]\\w*)"))
        typed(Regex("[(,]\\s*(?:@\\w+\\s+)*(?:private\\s+|internal\\s+|protected\\s+|public\\s+|override\\s+|vararg\\s+)*(?:val\\s+|var\\s+)?$r\\s*:\\s*([A-Z]\\w*)"))
        typed(Regex("\\b(?:val|var)\\s+$r\\s*=\\s*([A-Z]\\w*)\\s*\\("))
        untyped(Regex("\\b(?:val|var)\\s+$r\\s*=(?!\\s*[A-Z]\\w*\\s*\\()"))
        untyped(Regex("\\b(?:val|var)\\s+$r\\s+by\\b"))
        untyped(Regex("[{,(]\\s*$r\\s*(?:,|->)"))
        untyped(Regex("\\bfor\\s*\\(\\s*$r\\s+in\\b"))
        untyped(Regex("\\b(?:val|var)\\s*\\([^)]*\\b$r\\b"))
        if (ds.isEmpty()) return null
        val before = ds.filter { it.at < pos }.maxByOrNull { it.at }
        if (before != null) return before.type
        // बाद में declare (class की property) - सिर्फ़ तब, जब हर जगह एक ही type हो
        return ds.map { it.type }.distinct().singleOrNull()
    }

    /** इस लाइन से ऊपर (30 लाइन तक) कोई receiver वाला block है? (apply { put(…) } में put = बाहर की class का) */
    private fun inReceiverBlock(lines: List<String>, idx: Int): Boolean =
        (maxOf(0, idx - 30)..idx).any { k -> Regex("\\b(?:apply|with|run|buildString|buildList|buildMap|buildSet|edit|use|also|let|synchronized|sequence|thread)\\s*(?:\\([^)]*\\))?\\s*\\{").containsMatchIn(lines.getOrElse(k) { "" }) } ||
            (maxOf(0, idx - 30)..idx).any { k -> Regex("\\.\\s*(?:apply|run|with|edit)\\b").containsMatchIn(lines.getOrElse(k) { "" }) }

    /**
     * @param path   बदली फ़ाइल
     * @param before बदलाव से पहले (null = नई फ़ाइल)
     * @param after  बदलाव के बाद पूरी फ़ाइल
     * @param repo   पूरा रिपो (बदलाव समेत) - path -> content
     * @param whole  repo पूरा है (GitHub मोड). false = user की भेजी कुछ फ़ाइलें (नॉर्मल मोड) - सिर्फ़ पक्की member जाँच
     */
    fun check(path: String, before: String?, after: String, repo: Map<String, String>, whole: Boolean): List<Miss> {
        if (!path.endsWith(".kt") && !path.endsWith(".java")) return emptyList()
        val idx = addedIdx(before, after)
        if (idx.isEmpty()) return emptyList()
        val file = strip(after)
        val fileLines = file.lines()
        val lineStart = IntArray(fileLines.size + 1).also { a -> var p = 0; fileLines.forEachIndexed { k, l -> a[k] = p; p += l.length + 1 }; a[fileLines.size] = p }
        fun lineOf(pos: Int): Int { var lo = 0; var hi = fileLines.size - 1; while (lo < hi) { val m = (lo + hi + 1) / 2; if (lineStart[m] <= pos) lo = m else hi = m - 1 }; return lo }
        fun isNew(pos: Int) = lineOf(pos) in idx
        val others = repo.filterKeys { it != path }.mapValues { strip(it.value) }
        val all = others + (path to file)
        val oldStripped = strip(before ?: "")
        val oldUse = others.values.joinToString("\n") + "\n" + oldStripped
        val out = linkedMapOf<String, Miss>()

        // 1) recv.name(  - recv के type में name है?
        Regex("\\b([A-Za-z_]\\w*)\\s*(?:\\?|!!)?\\.\\s*([a-zA-Z_]\\w*)\\s*\\(").findAll(file).forEach { m ->
            if (!isNew(m.range.first)) return@forEach
            val recv = m.groupValues[1]; val name = m.groupValues[2]
            if (recv in KEYWORDS || name in KEYWORDS) return@forEach
            // पहले से (package/qualified नाम: a.b.C.d) - पीछे भी . हो तो छोड़ो
            if (m.range.first > 0 && file[m.range.first - 1] == '.') return@forEach
            val type = typeAt(file, recv, m.range.first) ?: return@forEach
            if (type in TYPE_OK) return@forEach
            val key = "$type.$name"
            if (key in out) return@forEach
            val tfs = typeFiles(all, type)
            if (tfs.isEmpty()) return@forEach                       // बाहर की class (JSONObject…) - नहीं जानते
            val has = hasMember(all, type, name)
            val tf = tfs.first()
            if (has == false) {
                out[key] = Miss("$recv.$name()", "$type (${tf.first.substringAfterLast('/')}) में $name नाम का function/property नहीं है",
                    sure = true, known = members(typeBody(tf.second, type)))
            } else if (has == null && !usedOnType(others + (path to oldStripped), type, name)) {
                // रिपो की class, पर parent class बाहर की (जैसे AccessibilityService). रिपो में इस type पर कभी इस्तेमाल नहीं हुआ.
                val elsewhere = declaredIn(all, name).filter { it != tf.first.substringAfterLast('/') }
                out[key] = if (elsewhere.isNotEmpty())
                    // नाम रिपो की किसी दूसरी फ़ाइल/class में है (जैसे Speaker.speak) - गलत object पर बुलाया = पक्की गलती
                    Miss("$recv.$name()", "$type (${tf.first.substringAfterLast('/')}) में $name नहीं है — यह ${elsewhere.take(3).joinToString()} में है (गलत object पर बुलाया)",
                        sure = true, known = members(typeBody(tf.second, type)))
                else Miss("$recv.$name()", "$type (${tf.first.substringAfterLast('/')}) में नहीं मिला; सिर्फ़ उसकी बाहर वाली parent class (${superTypes(tf.second, type).joinToString()}) में हो सकता है",
                    sure = false, known = members(typeBody(tf.second, type)))
            }
        }
        if (!whole) return out.values.toList()

        // 2) bare name(  - इसी फ़ाइल में / रिपो में declare या पहले से इस्तेमाल?
        val outerSuper = Regex("\\b(?:class|object)\\s+(\\w+)").findAll(file).map { it.groupValues[1] }
            .flatMap { superTypes(file, it).asSequence() }.any { sup -> sup !in TYPE_OK && typeFiles(all, sup).isEmpty() }
        Regex("(?<![.\\w])([a-z_]\\w*)\\s*\\(").findAll(file).forEach { m ->
            if (!isNew(m.range.first)) return@forEach
            val name = m.groupValues[1]
            if (name in KEYWORDS || name in BARE_OK || name in out) return@forEach
            // fun name( - यहीं declare हो रहा है
            if (Regex("\\bfun\\s+(?:<[^>]*>\\s*)?(?:[\\w.<>?]+\\.)?$").containsMatchIn(file.substring(maxOf(0, m.range.first - 60), m.range.first))) return@forEach
            if (all.values.any { declRe(name).containsMatchIn(it) || Regex("\\b(?:class|object|interface)\\s+${Regex.escape(name)}\\b").containsMatchIn(it) }) return@forEach
            if (Regex("(?<![.\\w])${Regex.escape(name)}\\s*\\(").containsMatchIn(oldUse)) return@forEach      // पहले से इस्तेमाल = मौजूद (import)
            if (Regex("\\b${Regex.escape(name)}\\s*:").containsMatchIn(file)) return@forEach                // parameter (function type)
            if (Regex("(?m)^\\s*import\\s+[\\w.]*\\.${Regex.escape(name)}\\b").containsMatchIn(after)) return@forEach
            val soft = outerSuper || inReceiverBlock(fileLines, lineOf(m.range.first))
            out[name] = if (soft) Miss("$name()", "पूरे रिपो में \"fun $name\" कहीं declare नहीं है; बाहर की class (parent class या apply/with वाले object) का हो सकता है", sure = false)
                else Miss("$name()", "पूरे रिपो में \"fun $name\" कहीं declare नहीं है", sure = true)
        }

        // 3) Name( / Name.  - class/object: रिपो में, import में, या पहले से इस्तेमाल?
        val starImport = Regex("(?m)^\\s*import\\s+[\\w.]+\\.\\*").containsMatchIn(after)
        if (!starImport) Regex("(?<![.\\w])([A-Z]\\w*)\\s*[.(]").findAll(file).forEach { m ->
            if (!isNew(m.range.first)) return@forEach
            val name = m.groupValues[1]
            if (name in TYPE_OK || name in BARE_OK || name in out || name.length < 2 || name.all { it.isUpperCase() || it == '_' || it.isDigit() }) return@forEach
            if (all.values.any { declares(it, name) }) return@forEach
            if (Regex("(?m)^\\s*import\\s+[\\w.]*\\b${Regex.escape(name)}\\b").containsMatchIn(after)) return@forEach
            if (Regex("(?<![\\w])${Regex.escape(name)}\\b").containsMatchIn(oldUse)) return@forEach
            out[name] = Miss(name, "रिपो में \"$name\" नाम की class/object नहीं है और import भी नहीं", sure = true)
        }
        return out.values.toList()
    }

    /** प्लानर की "uses" जाँच: "path: name" - फ़ाइल में name सच में है? @return गलत entries */
    fun badUses(uses: List<String>, read: (String) -> String?): List<String> = uses.mapNotNull { u ->
        val path = u.substringBefore(':').trim(); val name = u.substringAfter(':', "").trim().substringAfterLast('.').substringBefore('(').trim()
        if (path.isBlank() || name.isBlank()) return@mapNotNull null
        val c = read(path) ?: return@mapNotNull "$u — फ़ाइल $path नहीं मिली"
        if (!Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(c)) "$u — $path में \"$name\" नहीं है" else null
    }

    /** "path: name" - उस फ़ाइल में name की असली declaration लाइनें (कोडर को असली signature) */
    fun signatures(uses: List<String>, read: (String) -> String?): String = uses.mapNotNull { u ->
        val path = u.substringBefore(':').trim(); val name = u.substringAfter(':', "").trim().substringAfterLast('.').substringBefore('(').trim()
        val c = read(path) ?: return@mapNotNull null
        val n = Regex.escape(name)
        val lines = c.lines().filter { Regex("\\b(?:fun|val|var|class|object|interface|const val)\\s+(?:<[^>]*>\\s*)?(?:[\\w.<>?]+\\.)?$n\\b").containsMatchIn(it) }
            .map { it.trim().take(220) }.take(4)
        if (lines.isEmpty()) null else "$path:\n" + lines.joinToString("\n") { "  $it" }
    }.joinToString("\n")
}
