package com.contextsolutions.localagent.job

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopJobInitializerTest {

    private fun jobDir(manifest: String): File {
        val dir = createTempDirectory("jobinit").toFile()
        File(dir, JobSettingsLoader.FILE_NAME).writeText(manifest)
        return dir
    }

    private fun entryFor(dir: File) = JobCatalogEntry(
        id = "test", displayName = "Test", description = "", programPath = "",
        workingDir = dir.absolutePath, supportedOnThisOs = true, requiresInit = true,
    )

    private fun initializer(browserDetector: () -> File? = { File("/bin/sh") }) =
        DesktopJobInitializer(now = { 1_000L }, browserDetector = browserDetector)

    @Test
    fun succeedsAndWritesMarker() = runBlocking {
        val dir = jobDir(
            """
            { "version": 3, "init": { "steps": [
              { "id": "s1", "title": "One", "run": { "linux": "true", "macos": "true", "windows": "exit 0" } }
            ] } }
            """.trimIndent(),
        )
        try {
            val updates = mutableListOf<JobInitProgress>()
            val result = initializer().initialize(entryFor(dir)) { updates.add(it) }
            assertEquals(JobInitResult.Succeeded, result)
            assertTrue(File(dir, DesktopJobInitializer.MARKER_NAME).isFile, "marker written")
            assertTrue(updates.any { it.state == JobInitStepState.RUNNING }, "emits RUNNING")
            assertTrue(updates.any { it.index == 0 && it.state == JobInitStepState.DONE }, "step 0 reaches DONE")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun planListsStepsIncludingRequiredRuntime() = runBlocking {
        val dir = jobDir(
            """
            { "version": 1, "init": {
              "requires": ["node"],
              "steps": [ { "title": "Install", "run": { "linux": "true", "macos": "true", "windows": "exit 0" } } ],
              "verify": { "linux": "true", "macos": "true", "windows": "exit 0" }
            } }
            """.trimIndent(),
        )
        try {
            val plan = initializer().plan(entryFor(dir))
            // require-node + Install + Verify
            assertEquals(listOf("Set up Node.js", "Install", "Verify setup"), plan.map { it.title })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun planListsBrowserCheck() = runBlocking {
        val dir = jobDir(
            """
            { "version": 1, "init": {
              "requires": ["node", "chrome"],
              "steps": [ { "title": "Install", "run": { "linux": "true", "macos": "true", "windows": "exit 0" } } ]
            } }
            """.trimIndent(),
        )
        try {
            val plan = initializer().plan(entryFor(dir))
            assertEquals(listOf("Set up Node.js", "Check for Google Chrome", "Install"), plan.map { it.title })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun browserCheckFailsWhenAbsent() = runBlocking {
        val dir = jobDir(
            """{ "version": 1, "init": { "requires": ["chrome"],
              "steps": [ { "run": { "linux": "true", "macos": "true", "windows": "exit 0" } } ] } }""",
        )
        try {
            val result = initializer(browserDetector = { null }).initialize(entryFor(dir)) {}
            assertTrue(result is JobInitResult.Failed, "missing browser ⇒ Failed")
            assertEquals("Check for Google Chrome", result.stepTitle)
            assertTrue(result.reason.contains("Chrome"), "actionable message")
            assertFalse(File(dir, DesktopJobInitializer.MARKER_NAME).isFile, "no marker on failure")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun browserCheckPassesWhenPresent() = runBlocking {
        val dir = jobDir(
            """{ "version": 1, "init": { "requires": ["chrome"],
              "steps": [ { "run": { "linux": "true", "macos": "true", "windows": "exit 0" } } ] } }""",
        )
        try {
            val result = initializer(browserDetector = { File("/bin/sh") }).initialize(entryFor(dir)) {}
            assertEquals(JobInitResult.Succeeded, result)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun interactiveLaunchOfMissingProgramFails() = runBlocking {
        val dir = jobDir(
            """
            { "version": 1, "init": { "steps": [
              { "id": "warm", "title": "Open browser", "interactive": true,
                "launch": {
                  "linux": "localagent-no-such-binary-xyz123 --x",
                  "macos": "localagent-no-such-binary-xyz123 --x",
                  "windows": "localagent-no-such-binary-xyz123 --x"
                } }
            ] } }
            """.trimIndent(),
        )
        try {
            val result = initializer().initialize(entryFor(dir)) {}
            assertTrue(result is JobInitResult.Failed, "missing launch program ⇒ Failed (not DONE)")
            assertEquals("Open browser", result.stepTitle)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun secondRunIsAlreadyInitialized() = runBlocking {
        val dir = jobDir(
            """{ "version": 3, "init": { "steps": [ { "run": { "linux": "true", "macos": "true", "windows": "exit 0" } } ] } }""",
        )
        try {
            initializer().initialize(entryFor(dir)) {}
            val again = initializer().initialize(entryFor(dir)) {}
            assertEquals(JobInitResult.AlreadyInitialized, again)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun failingStepBlocksAndWritesNoMarker() = runBlocking {
        val dir = jobDir(
            """
            { "version": 1, "init": { "steps": [
              { "id": "s1", "title": "Boom", "run": { "linux": "false", "macos": "false", "windows": "exit 1" } }
            ] } }
            """.trimIndent(),
        )
        try {
            val result = initializer().initialize(entryFor(dir)) {}
            assertTrue(result is JobInitResult.Failed, "non-zero step ⇒ Failed")
            assertEquals("Boom", result.stepTitle)
            assertFalse(File(dir, DesktopJobInitializer.MARKER_NAME).isFile, "no marker on failure")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun failingVerifyBlocks() = runBlocking {
        val dir = jobDir(
            """
            { "version": 1, "init": {
              "steps": [ { "run": { "linux": "true", "macos": "true", "windows": "exit 0" } } ],
              "verify": { "linux": "false", "macos": "false", "windows": "exit 1" }
            } }
            """.trimIndent(),
        )
        try {
            val result = initializer().initialize(entryFor(dir)) {}
            assertTrue(result is JobInitResult.Failed)
            assertEquals("Verify setup", result.stepTitle)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun noInitBlockIsAlreadyInitialized() = runBlocking {
        val dir = jobDir("""{ "version": 1, "name": "Test" }""")
        try {
            assertEquals(JobInitResult.AlreadyInitialized, initializer().initialize(entryFor(dir)) {})
        } finally {
            dir.deleteRecursively()
        }
    }
}
