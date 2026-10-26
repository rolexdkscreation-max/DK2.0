        assertFalse(n.contains("merge")); assertTrue(n.contains("❌ फ़ेल")); assertTrue(n.contains("वास्तविक APK build")); assertTrue(n.contains("क्या काम दिया था"))
    }

    @Test fun everyAgentHasOwnShortPrompt() {
        val all = Seven.Agent.values().map { Seven.Prompts.of(it) }
        assertEquals(11, all.distinct().size)          // 7 पुराने + प्रॉम्प्ट इंजीनियर, परफॉरमेंस, QA, डिप्लॉयमेंट
        Seven.Agent.values().forEach { assertTrue(it.name, Seven.Prompts.of(it).contains("ONLY job")) }
        assertTrue(Seven.Prompts.CODER.contains("NEVER send REPLACE without FIND"))
    }

    @Test fun dispatchableWorkflow_onlyPrCheckThatCanRunByHand() {
        val check = "on:\n  pull_request:\n  workflow_dispatch:\njobs: {}"
        val build = "on:\n  push:\n    branches: [main]\n  workflow_dispatch:\n"
        val prOnly = "on:\n  pull_request:\n# workflow_dispatch:\n"
        assertEquals("check.yml", Seven.dispatchable(listOf("build.yml" to build, "check.yml" to check)))
        assertNull(Seven.dispatchable(listOf("build.yml" to build, "pr.yml" to prOnly)))
    }

    @Test fun report_neverDoneBeforeCheckPasses_alwaysPrAndCheck() {
        val fail = Seven.report("x", Seven.Mode.GITHUB, emptyList(), emptyList(), false, "e: X.kt:3", "", "b", Seven.CheckState.FAIL, "https://ci/9")
        assertTrue(fail.contains("❌ काम अधूरा")); assertFalse(fail.contains("सत्यापन के अनुसार काम पूरा"))
        assertTrue(fail.contains("Pull Request: अभी नहीं बना")); assertTrue(fail.contains("GitHub Actions: ❌ फ़ेल")); assertTrue(fail.contains("Build/check लिंक: https://ci/9"))
        val running = Seven.report("x", Seven.Mode.GITHUB, emptyList(), emptyList(), null, "", "https://pr/1", "b", Seven.CheckState.RUNNING)
        assertFalse(running.contains("सत्यापन के अनुसार काम पूरा")); assertTrue(running.contains("⏳ काम की पुष्टि अभी बाकी है")); assertFalse(running.contains("merge कर दूँ"))
        val noPr = Seven.report("x", Seven.Mode.GITHUB, emptyList(), emptyList(), true, "", "", "b", Seven.CheckState.PASS)
        assertFalse(noPr.contains("सत्यापन के अनुसार काम पूरा"))
        val noCheck = Seven.report("x", Seven.Mode.GITHUB, emptyList(), emptyList(), true, "", "https://pr/1", "b")
        assertFalse(noCheck.contains("सत्यापन के अनुसार काम पूरा")); assertTrue(noCheck.contains("GitHub Actions: — नहीं चला"))
        val ok = Seven.report("x", Seven.Mode.GITHUB, emptyList(), emptyList(), true, "", "https://pr/1", "b", Seven.CheckState.PASS, "https://ci/1")
        assertTrue(ok.contains("✅ सत्यापन के अनुसार काम पूरा")); assertTrue(ok.contains("Pull Request: https://pr/1")); assertTrue(ok.contains("GitHub Actions: ✅ पास")); assertTrue(ok.contains("Build/check लिंक: https://ci/1"))
    }
}