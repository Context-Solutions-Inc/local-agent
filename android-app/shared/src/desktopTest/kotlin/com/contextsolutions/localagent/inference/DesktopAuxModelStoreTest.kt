package com.contextsolutions.localagent.inference

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DesktopAuxModelStoreTest {

    private val tmp: File = File.createTempFile("aux-store", "").let {
        it.delete(); it.mkdirs(); it
    }

    @AfterTest fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun spec(url: String, size: Long = 8L) =
        DesktopModelSpec(filename = "m.onnx", downloadUrl = url, sha256 = "abc", sizeBytes = size)

    @Test
    fun downloads_when_absent_and_endpoint_configured() = runTest {
        var calls = 0
        val store = DesktopAuxModelStore(
            baseDir = tmp,
            download = { inv, _ ->
                calls++
                inv.localFile().writeBytes(ByteArray(8))
                ModelDownloadResult.Success(inv.localFile(), 8L)
            },
        )
        val result = store.ensure("LOCALAGENT_TEST_ONNX_UNSET", spec("https://host/m.onnx"))
        assertEquals(1, calls, "configured + absent should trigger one download")
        assertTrue(result != null && result.isFile)
    }

    @Test
    fun skips_download_when_endpoint_unconfigured() = runTest {
        var calls = 0
        val store = DesktopAuxModelStore(
            baseDir = tmp,
            download = { _, _ -> calls++; ModelDownloadResult.Failure("should not run", retryable = false) },
        )
        // Blank URL ⇒ DesktopModelSpec.isConfigured == false ⇒ no fetch attempted.
        val result = store.ensure("LOCALAGENT_TEST_ONNX_UNSET", spec(url = ""))
        assertEquals(0, calls, "unconfigured endpoint must not hit the network")
        assertNull(result)
    }

    @Test
    fun no_op_when_file_already_present() = runTest {
        var calls = 0
        val s = spec("https://host/m.onnx", size = 8L)
        // Pre-stage a correctly-sized file in <baseDir>/models/.
        val inv = DesktopModelInventory(s, tmp)
        inv.localFile().writeBytes(ByteArray(8))
        val store = DesktopAuxModelStore(
            baseDir = tmp,
            download = { _, _ -> calls++; ModelDownloadResult.Failure("should not run", retryable = false) },
        )
        val result = store.ensure("LOCALAGENT_TEST_ONNX_UNSET", s)
        assertEquals(0, calls, "already-present file must not re-download")
        assertEquals(inv.localFile(), result)
    }

    @Test
    fun download_failure_returns_null() = runTest {
        val store = DesktopAuxModelStore(
            baseDir = tmp,
            download = { _, _ -> ModelDownloadResult.Failure("boom", retryable = true) },
        )
        val result = store.ensure("LOCALAGENT_TEST_ONNX_UNSET", spec("https://host/m.onnx"))
        assertNull(result)
        assertFalse(DesktopModelInventory(spec("https://host/m.onnx"), tmp).isPresent())
    }
}
