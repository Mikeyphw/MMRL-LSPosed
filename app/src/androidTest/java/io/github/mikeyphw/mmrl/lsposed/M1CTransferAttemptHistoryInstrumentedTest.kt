package io.github.mikeyphw.mmrl.lsposed

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the instrumentation package's private preferences/files, never the user's app. */
@RunWith(AndroidJUnit4::class)
class M1CTransferAttemptHistoryInstrumentedTest {
    @Test fun sameSourceAttemptsRemainDistinctWithFailuresAndProgress() {
        val testContext: Context = InstrumentationRegistry.getInstrumentation().context
        val prefs = testContext.getSharedPreferences("activity_transfer_ledger_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val archive = java.io.File(testContext.filesDir, "activity_receipts_archive_v1.log")
        archive.delete()
        try {
            val first = ActivityTransferJournal.begin(testContext, "CI:101:1:4")
            ActivityTransferJournal.progress(testContext, first, 25, 100)
            ActivityTransferJournal.finish(testContext, first, "FAILED")
            val second = ActivityTransferJournal.begin(testContext, "CI:101:2:4")
            ActivityTransferJournal.progress(testContext, second, 99, 100)
            ActivityTransferJournal.finish(testContext, second, "SUCCEEDED")
            assertNotEquals(first, second)
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
            prefs.edit().clear().commit()
            archive.delete()
        }
    }
}
