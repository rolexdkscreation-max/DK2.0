               prUrl: String, backup: String, check: CheckState? = null, checkUrl: String = "", prNote: String = ""): String = buildString {
        val gh = mode == Mode.GITHUB
        val chk = check ?: when (testOk) { true -> CheckState.NONE; false -> CheckState.FAIL; null -> CheckState.RUNNING }
        val done = testOk == true && (!gh || (chk == CheckState.PASS && prUrl.isNotBlank()))
        append("📋 DK कोडिंग एजेंट — काम की रिपोर्ट (").append(if (gh) "GitHub मोड" else "नॉर्मल मोड").append(")\n")
        append("━━━━━━━━━━━━━━━━━━━━\n")
        append("स्थिति: ").append(when {
            done -> "✅ सत्यापन के अनुसार काम पूरा"
            testOk == false || chk == CheckState.FAIL -> "❌ काम अधूरा — जाँच में समस्या मिली"
            else -> "⏳ काम की पुष्टि अभी बाकी है"
        }).append("\n\n")

        append("1) आपने क्या काम दिया था?\n")
        append(task.trim().take(500).ifBlank { "अनुरोध का विवरण उपलब्ध नहीं" }).append("\n\n")

        append("2) एजेंट ने क्या बदला?\n")
        if (changes.isEmpty()) append("• इस रिपोर्ट में कोई लागू किया गया फ़ाइल बदलाव दर्ज नहीं है\n")
        else changes.forEach { (f, w) -> append("• फ़ाइल: ").append(f).append("\n  बदलाव: ").append(w).append("\n") }
        append("\n")

        append("3) बग / समस्या और उसका समाधान\n")
        if (bugs.isEmpty()) append("• कोई root cause दर्ज नहीं है; इसका मतलब यह नहीं कि हर संभावित बग की गारंटी से जाँच हो गई।\n")
        else bugs.forEachIndexed { i, b ->
            append(i + 1).append(". समस्या: ").append(b.what).append("\n")
            append("   जगह: ").append(b.where.ifBlank { "पुष्टि नहीं हुई" }).append("\n")
            append("   जड़: ").append(b.rootCause).append("\n")
            append("   सुधार: ").append(b.fix).append("\n")
        }
        append("\n")

        append("4) जाँच और टेस्ट का असली नतीजा\n")
        append("• एजेंट की जाँच: ").append(when (testOk) {
            true -> "✅ पास"
            false -> "❌ फ़ेल"
            null -> "⚠ नतीजा उपलब्ध नहीं"
        }).append("\n")
        if (testNote.isNotBlank()) append("• जाँच का विवरण: ").append(testNote.trim()).append("\n")
        if (!gh) {
            append("• महत्वपूर्ण सीमा: नॉर्मल मोड में GitHub Actions/Android Gradle build अपने-आप नहीं चला। इसलिए ऊपर का पास/फ़ेल code review या उपलब्ध जाँच का नतीजा है—इसे वास्तविक APK build या फ़ोन पर सफल चलने का प्रमाण न मानें।\n")
        } else {
            append("• GitHub Actions: ").append(chk.hi).append("\n")
            append("• Build/check लिंक: ").append(checkUrl.ifBlank { "लिंक उपलब्ध नहीं" }).append("\n")
            append("• Pull Request: ").append(prUrl.ifBlank { "अभी नहीं बना" + if (prNote.isNotBlank()) " — $prNote" else " — build/check पास होने पर ही बनाया जाएगा" }).append("\n")
        }
        append("\n")

        if (backup.isNotBlank()) {
            append("5) सुरक्षित बैकअप\n• Branch: ").append(backup).append("\n\n")
        } else {
            append("5) सुरक्षित बैकअप\n• इस रिपोर्ट में अलग backup branch दर्ज नहीं है।\n\n")
        }

        append(if (done) "6) अगला कदम\n" else "6) अभी क्या बाकी है?\n")
        when {
            done && gh -> append("• बदलाव अलग branch/PR पर है। कृपया PR की समीक्षा करें; merge केवल आपकी स्पष्ट मंज़ूरी के बाद होगा।\n")
            done -> append("• असली Android build और फ़ोन पर जाँच करनी हो तो प्रोजेक्ट के build/test चलाएँ; इस मोड की रिपोर्ट उसे प्रमाणित नहीं करती।\n")
            testOk == false || chk == CheckState.FAIL -> append("• ऊपर दिए गए error/log को ठीक करके जाँच फिर चलानी होगी। main को सीधे नहीं बदला गया।\n")
            else -> append("• जाँच का वास्तविक नतीजा प्राप्त करें। जब तक आवश्यक जाँच पास न हो, काम को पूरा न मानें।\n")
        }
        if (gh && !done) append("• main को हाथ नहीं लगाया गया; merge नहीं किया गया।\n")
    }
}

/** फ़ाइल की syntax जाँच (बिना compiler): brackets / XML / JSON. null = ठीक */
object Syntax {
    private val cLike = setOf("kt", "kts", "java", "js", "mjs", "ts", "jsx", "tsx", "c", "h", "cpp", "hpp", "cs", "go", "swift", "dart", "gradle", "scala", "php", "css", "scss")

    fun check(path: String, src: String): String? {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (src.contains("<<<FIND") || src.contains("FIND>>>") || src.contains("<<<REPLACE") || src.contains("REPLACE>>>") || src.contains("<<<FILE"))
            return "फ़ाइल में <<<FIND/REPLACE/FILE के निशान रह गए"
        return when (ext) {
            in cLike -> brackets(src, hashComments = ext == "php", templates = ext in setOf("kt", "kts"), backtick = ext in setOf("js", "mjs", "ts", "jsx", "tsx"))
            "py" -> brackets(src, hashComments = true, templates = false, backtick = false, cComments = false)
            "json" -> try { val t = src.trim(); if (t.startsWith("[")) JSONArray(t) else JSONObject(t); null } catch (e: Exception) { "JSON गलत: ${e.message?.take(150)}" }
            "xml" -> try {
                javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(src.byteInputStream()); null
            } catch (e: Exception) { "XML गलत: ${e.message?.take(150)}" }
            else -> null
        }
    }