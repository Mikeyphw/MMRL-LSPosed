package io.github.mikeyphw.mmrl.lsposed

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Process
import java.io.File
import java.util.UUID
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a target-UID-owned, per-test namespace; never clears production preferences/files. */
@RunWith(AndroidJUnit4::class)
class M1CTransferAttemptHistoryInstrumentedTest {
    @Test fun sameSourceAttemptsRemainDistinctWithFailuresAndProgress() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(target.applicationInfo.uid, Process.myUid())
        val nonce = UUID.randomUUID().toString()
        val name = "m1c-transfer-test-$nonce"
        val directory = File(target.cacheDir, name)
        assertTrue(directory.mkdir())
        // Instrumentation runs under the target UID. The test APK's own
        // package directory is not necessarily writable by that UID.
        // Keep the journal's production names mapped to unique test storage.
        val testContext = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(key: String, mode: Int): SharedPreferences {
                check(key == "activity_transfer_ledger_v1" && mode == Context.MODE_PRIVATE)
                return target.getSharedPreferences(name, mode)
            }
        }
        val prefs = testContext.getSharedPreferences("activity_transfer_ledger_v1", Context.MODE_PRIVATE)
        val archive = File(testContext.filesDir, "activity_receipts_archive_v1.log")
        try {
            assertTrue("Isolated preferences must be writable", prefs.edit().putString("probe", nonce).commit())
            assertEquals(nonce, prefs.getString("probe", null))
            assertTrue(prefs.edit().clear().commit())
            val probe = File(directory, "write-probe")
            probe.writeText(nonce)
            assertEquals(nonce, probe.readText())
            assertTrue(probe.delete())
            val first = ActivityTransferJournal.begin(testContext, "CI:101:1:4")
            ActivityTransferJournal.progress(testContext, first, 25, 100)
            ActivityTransferJournal.finish(testContext, first, "FAILED")
            val second = ActivityTransferJournal.begin(testContext, "CI:101:2:4")
            ActivityTransferJournal.progress(testContext, second, 99, 100)
            ActivityTransferJournal.finish(testContext, second, "SUCCEEDED")
            assertNotEquals(first, second)
            // Verify persistence in the supplied private namespace, not a
            // substituted target application context.
            assertTrue(archive.isFile)
            assertTrue(prefs.getString("entries", "[]")!!.contains(first))
            assertTrue(prefs.getString("entries", "[]")!!.contains(second))
            val history = ActivityReceiptCapture.archived(testContext)
            assertEquals(2, history.count { it.id == first || it.id == second })
            val failed = history.single { it.id == first }
            val successful = history.single { it.id == second }
            assertEquals("FAILED", failed.status)
            assertEquals("TRANSFER_FAILED", failed.failure)
            assertEquals("SUCCEEDED", successful.status)
            assertEquals(100, successful.percent)
            assertTrue(ActivityTransferJournal.snapshots(testContext).any { it.getString("id") == first })
        } finally {
            try {
                assertTrue(target.deleteSharedPreferences(name))
            } finally {
                assertTrue(directory.deleteRecursively())
            }
        }
    }
}
