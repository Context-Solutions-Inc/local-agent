package com.contextsolutions.localagent.job

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * M2 (security) — the Node.js archive is verified against a pinned SHA-256
 * BEFORE it is extracted/executed, and only an https/loopback mirror is accepted.
 */
class DesktopNodeProvisionerTest {

    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun rejectsArchiveThatFailsTheChecksumAndExtractsNothing() = runBlocking {
        // A loopback mirror that serves bogus bytes for ANY requested path, so the
        // host asset's download never matches its pinned hash.
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = "this is not a real node archive".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        server = srv
        val baseDir = File(System.getProperty("java.io.tmpdir"), "nodeprov-${System.nanoTime()}").apply { mkdirs() }
        try {
            val provisioner = DesktopNodeProvisioner(
                baseDir = baseDir,
                distBase = "http://127.0.0.1:${srv.address.port}",
                ioDispatcher = Dispatchers.Unconfined,
                systemNodeProbe = { false }, // force the download path even on a machine with system Node
            )

            val result = provisioner.ensure(onProgress = {})

            assertIs<NodeResult.Failed>(result)
            // Nothing was extracted/installed: no node binary present.
            assertTrue(DesktopJobRuntimeEnv.nodeBinDir(baseDir) == null, "no node binary may be installed on a checksum failure")
            // The rejected archive was deleted (the download dir is cleaned up).
            assertFalse(File(baseDir, "runtimes/.node-download").exists(), "the download scratch dir must be cleaned up")
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun rejectsInsecureMirrorOverride() = runBlocking {
        val baseDir = File(System.getProperty("java.io.tmpdir"), "nodeprov-${System.nanoTime()}").apply { mkdirs() }
        try {
            val result = DesktopNodeProvisioner(
                baseDir = baseDir,
                distBase = "http://evil.example.com/dist",
                ioDispatcher = Dispatchers.Unconfined,
                systemNodeProbe = { false },
            ).ensure(onProgress = {})

            assertIs<NodeResult.Failed>(result)
            assertTrue(result.reason.contains("insecure mirror"), "got: ${result.reason}")
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun distBasePolicyAcceptsHttpsAndLoopbackOnly() {
        assertTrue(DesktopNodeProvisioner.isSafeDistBase("https://nodejs.org/dist"))
        assertTrue(DesktopNodeProvisioner.isSafeDistBase("http://127.0.0.1:8080"))
        assertTrue(DesktopNodeProvisioner.isSafeDistBase("http://localhost:9"))
        assertFalse(DesktopNodeProvisioner.isSafeDistBase("http://nodejs.org/dist"))
        assertFalse(DesktopNodeProvisioner.isSafeDistBase("http://10.0.0.5/dist"))
    }

    @Test
    fun pinnedChecksumsCoverEverySupportedAsset() {
        val expected = setOf(
            "node-v20.18.1-linux-x64.tar.gz",
            "node-v20.18.1-linux-arm64.tar.gz",
            "node-v20.18.1-darwin-x64.tar.gz",
            "node-v20.18.1-darwin-arm64.tar.gz",
            "node-v20.18.1-win-x64.zip",
        )
        assertTrue(DesktopNodeProvisioner.NODE_SHA256.keys.containsAll(expected))
        DesktopNodeProvisioner.NODE_SHA256.values.forEach {
            assertTrue(it.matches(Regex("[0-9a-f]{64}")), "not a sha256: $it")
        }
    }
}
