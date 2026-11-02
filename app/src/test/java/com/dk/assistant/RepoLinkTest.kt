package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

/** Repo खाने में कुछ भी डालो → "owner/naam" (आख़िर का "/" या ".git" न बचे) */
class RepoLinkTest {
    @Test fun allFormsBecomeOwnerSlashName() {
        val want = "rolex/DK2.0"
        listOf(
            "rolex/DK2.0", " rolex/DK2.0 ", "rolex/DK2.0/", "rolex/DK2.0.git",
            "https://github.com/rolex/DK2.0", "https://github.com/rolex/DK2.0/",
            "https://github.com/rolex/DK2.0.git", "https://github.com/rolex/DK2.0.git/",
            "http://github.com/rolex/DK2.0", "https://www.github.com/rolex/DK2.0",
            "github.com/rolex/DK2.0", "HTTPS://GitHub.com/rolex/DK2.0.GIT", "git@github.com:rolex/DK2.0.git",
        ).forEach { assertEquals("\"$it\"", want, Prefs.cleanRepo(it)) }
    }
    @Test fun emptyStaysEmpty() {
        assertEquals("", Prefs.cleanRepo(""))
        assertEquals("", Prefs.cleanRepo("   "))
    }
}
