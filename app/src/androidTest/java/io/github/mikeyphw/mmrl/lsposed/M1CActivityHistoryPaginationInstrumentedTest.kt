package io.github.mikeyphw.mmrl.lsposed

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real Room instrumentation gate: test-owned DB, no Vector/root/user database access. */
@RunWith(AndroidJUnit4::class)
class M1CActivityHistoryPaginationInstrumentedTest {
    @Test fun historyBeyondTwoHundredRemainsQueryableAndCurrentOutranksHistorical() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "m1c-pagination-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, ActivityHistoryDatabase::class.java, name)
            .addMigrations(ActivityHistoryMigration1To2).build()
        try {
            val dao = db.entries()
            repeat(253) { index ->
                dao.saveHistory(HistoricalActivityEntry(
                    id = "test:$index", kind = "DOWNLOAD", origin = "USER",
                    status = "SUCCEEDED", phase = "FINISHED",
                    createdAtMillis = 100, updatedAtMillis = index.toLong() + 200,
                    progressPercent = 100, source = "Historical receipt", failureCode = null,
                    schemaVersion = 1,
                ))
            }
            val oldest = dao.page(limit = 50, offset = 250)
            assertEquals(3, oldest.size)
            assertTrue(oldest.any { it.id == "test:0" })
            dao.upsert(ActivityEntry("test:252", "DOWNLOAD", "USER", "SUCCEEDED",
                "FINISHED", 100, 9999, 100, "Currently verified receipt", null, 1))
            val first = dao.page(50, 0)
            assertEquals("Currently verified receipt", first.first().source)
            assertEquals(253, (0 until 6).sumOf { dao.page(50, it * 50).size })
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
