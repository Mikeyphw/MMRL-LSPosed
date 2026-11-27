package io.github.mikeyphw.mmrl.lsposed

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Runs on Android emulator/device. Never opens or clears the user's Activity database. */
@RunWith(AndroidJUnit4::class)
class M1CActivityPersistenceInstrumentedTest {
    @Test fun existingVersionOneHistoryMigratesToVersionTwoWithoutLosingRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "m1c-test-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("""CREATE TABLE IF NOT EXISTS activity_operations (
                    id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL,
                    origin TEXT NOT NULL, status TEXT NOT NULL, phase TEXT NOT NULL,
                    createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL,
                    progressPercent INTEGER, source TEXT NOT NULL,
                    schemaVersion INTEGER NOT NULL)""")
                db.execSQL("""INSERT INTO activity_operations VALUES (
                    'download:legacy','DOWNLOAD','USER','SUCCEEDED','FINISHED',
                    1,2,100,'old verified receipt',1)""")
                db.version = 1
            }
            Room.databaseBuilder(context, ActivityHistoryDatabase::class.java, name)
                .addMigrations(ActivityHistoryMigration1To2).build().use { db ->
                    val old = runBlocking { db.entries().recent() }
                    assertEquals(1, old.size)
                    assertEquals("download:legacy", old.single().id)
                    assertEquals("SUCCEEDED", old.single().status)
                    runBlocking {
                        db.entries().saveHistory(old.single().copy(
                            source = "Historical receipt; current trust not implied").historical())
                    }
                }
            Room.databaseBuilder(context, ActivityHistoryDatabase::class.java, name)
                .addMigrations(ActivityHistoryMigration1To2).build().use { reopened ->
                    val persisted = runBlocking { reopened.entries().page(limit = 50, offset = 0) }
                    assertEquals(1, persisted.size)
                    assertEquals("download:legacy", persisted.single().id)
                    assertNotNull(persisted.single().source)
                }
        } finally { context.deleteDatabase(name) }
    }
}
