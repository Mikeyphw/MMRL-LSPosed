package io.github.mikeyphw.mmrl.lsposed

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Isolated test-owned cache only; no Vector, signing, production Room or user data writes. */
@RunWith(AndroidJUnit4::class)
class M1CReceiptArchiveInstrumentedTest {
    @Test fun durableTerminalReceiptAndTamperRefusal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(context.cacheDir, "m1c-archive-${UUID.randomUUID()}")
        assertTrue(sandbox.mkdirs())
        try {
            val id = "transfer:${UUID.randomUUID()}"
            val receipt = ArchivedActivityReceipt(id, "RELEASE", "DOWNLOAD",
                "SUCCEEDED", 100, 200, 100)
            ActivityReceiptArchive.append(sandbox, receipt)
            ActivityReceiptArchive.append(sandbox, receipt)
            assertEquals(listOf(receipt), ActivityReceiptArchive.read(sandbox))
            val file = File(sandbox, "activity_receipts_archive_v1.log")
            val damaged = file.readText().replace("RELEASE", "CI:1:1:1")
            file.writeText(damaged)
            var refused = false
            try { ActivityReceiptArchive.read(sandbox) }
            catch (_: java.io.IOException) { refused = true }
            assertTrue("Modified evidence must fail verification", refused)
        } finally { sandbox.deleteRecursively() }
    }
}
