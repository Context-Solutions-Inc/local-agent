package com.contextsolutions.localagent.job

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitignoreMatcherTest {

    @Test
    fun ignoresEntriesFromAParentGitignore() {
        val root = createTempDirectory("gitignore-test").toFile()
        try {
            // Parent .gitignore (one level above the browsed folder) hides these.
            File(root, ".gitignore").writeText(
                """
                # comment
                node_modules/
                seen.json
                *.log
                """.trimIndent(),
            )
            File(root, ".git").mkdirs()  // marks the repo root; walk-up stops here
            val child = File(root, "ca").apply { mkdirs() }

            // The browsed folder's own entries:
            File(child, "node_modules").mkdirs()
            File(child, "seen.json").writeText("{}")
            File(child, "foo.log").writeText("")
            File(child, "watch.sh").writeText("")
            File(child, "README.md").writeText("")

            val matcher = GitignoreMatcher.forDirectory(child)

            assertTrue(matcher.isIgnored(File(child, "node_modules")), "node_modules should be ignored")
            assertTrue(matcher.isIgnored(File(child, "seen.json")), "seen.json should be ignored")
            assertTrue(matcher.isIgnored(File(child, "foo.log")), "foo.log should be ignored")

            assertFalse(matcher.isIgnored(File(child, "watch.sh")), "watch.sh should be shown")
            assertFalse(matcher.isIgnored(File(child, "README.md")), "README.md should be shown")
        } finally {
            root.deleteRecursively()
        }
    }
}
