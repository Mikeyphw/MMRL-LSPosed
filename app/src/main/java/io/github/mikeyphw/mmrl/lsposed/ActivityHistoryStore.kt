package io.github.mikeyphw.mmrl.lsposed

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.mikeyphw.mmrl.shared.OperationFailure
import androidx.room.withTransaction
import io.github.mikeyphw.mmrl.shared.OperationId
import io.github.mikeyphw.mmrl.shared.OperationKind
import io.github.mikeyphw.mmrl.shared.OperationOrigin
import io.github.mikeyphw.mmrl.shared.OperationPhase
import io.github.mikeyphw.mmrl.shared.OperationRecord
import io.github.mikeyphw.mmrl.shared.OperationStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.security.MessageDigest

/** Independent private Room database; never opens or modifies Vector/LSPosed databases. */
@Entity(tableName = "activity_operations")
internal data class ActivityEntry(
    @PrimaryKey val id: String,
    val kind: String,
    val origin: String,
    val status: String,
    val phase: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val progressPercent: Int?,
    val source: String,
    val failureCode: String?,
    val schemaVersion: Int,
)

/** Persisted historical events outlive the bounded current-receipt projection. */
@Entity(tableName = "activity_history_events")
internal data class HistoricalActivityEntry(
    @PrimaryKey val id: String,
    val kind: String,
    val origin: String,
    val status: String,
    val phase: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val progressPercent: Int?,
    val source: String,
    val failureCode: String?,
    val schemaVersion: Int,
) {
    fun entry() = ActivityEntry(id, kind, origin, status, phase,
        createdAtMillis, updatedAtMillis, progressPercent, source, failureCode, schemaVersion)
}

internal fun ActivityEntry.historical() = HistoricalActivityEntry(id, kind, origin, status,
    phase, createdAtMillis, updatedAtMillis, progressPercent, source, failureCode, schemaVersion)

@Dao
internal interface ActivityEntryDao {
    @Query("SELECT * FROM activity_operations ORDER BY updatedAtMillis DESC LIMIT 200")
    suspend fun recent(): List<ActivityEntry>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ActivityEntry)
    @Query("DELETE FROM activity_operations")
    suspend fun clearProjection()
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveHistory(entry: HistoricalActivityEntry)
    /** Page across current proof and durable historical receipts without discarding old history.
     * Current proof takes precedence for an identical operation ID. This query NEVER
     * asserts that a historical transfer is still a presently verified download.
     */
    @Query("""
        SELECT id, kind, origin, status, phase, createdAtMillis, updatedAtMillis,
               progressPercent, source, failureCode, schemaVersion
        FROM (
            SELECT id, kind, origin, status, phase, createdAtMillis, updatedAtMillis,
                   progressPercent, source, failureCode, schemaVersion
            FROM activity_operations
            UNION ALL
            SELECT h.id, h.kind, h.origin, h.status, h.phase,
                   h.createdAtMillis, h.updatedAtMillis, h.progressPercent,
                   h.source, h.failureCode, h.schemaVersion
            FROM activity_history_events AS h
            WHERE NOT EXISTS (
                SELECT 1 FROM activity_operations AS a WHERE a.id = h.id
            )
        )
        ORDER BY updatedAtMillis DESC, id DESC LIMIT :limit OFFSET :offset
    """)
    suspend fun page(limit: Int, offset: Int): List<ActivityEntry>
}

/** Exact migration; no destructive fallback for recorded Activity data. */
internal val ActivityHistoryMigration1To2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE activity_operations ADD COLUMN failureCode TEXT")
        db.execSQL("""CREATE TABLE IF NOT EXISTS activity_history_events (
            id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, origin TEXT NOT NULL,
            status TEXT NOT NULL, phase TEXT NOT NULL,
            createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL,
            progressPercent INTEGER, source TEXT NOT NULL, failureCode TEXT,
            schemaVersion INTEGER NOT NULL)""")
    }
}

@Database(entities = [ActivityEntry::class, HistoricalActivityEntry::class], version = 2, exportSchema = true)
internal abstract class ActivityHistoryDatabase : RoomDatabase() {
    abstract fun entries(): ActivityEntryDao
}

/** Read-only projection from the existing authoritative Vector and verified-download receipts. */
internal object ActivityHistoryStore {
    private val mutex = Mutex()
    @Volatile private var database: ActivityHistoryDatabase? = null

    private fun db(context: Context): ActivityHistoryDatabase =
        database ?: synchronized(this) {
            database ?: Room.databaseBuilder(context.applicationContext,
                ActivityHistoryDatabase::class.java, "lsposed_activity_v1.db")
                .addMigrations(ActivityHistoryMigration1To2).build().also { database = it }
        }

    private fun snapshot(id: String, kind: OperationKind, status: OperationStatus,
                         timestamp: Long, source: String, progress: Int? = null,
                         failureCode: String? = null, created: Long = timestamp): ActivityEntry? {
        val parsed = OperationId.parse(id) ?: return null
        if (timestamp <= 0 || created <= 0 || timestamp < created) return null
        val phase = if (status.terminal) OperationPhase.FINISHED
            else if (status == OperationStatus.RUNNING) OperationPhase.TRANSFERRING
            else OperationPhase.WAITING_FOR_USER
        val boundedProgress = if (status == OperationStatus.SUCCEEDED) 100 else progress
        val failure = failureCode?.let(OperationFailure::parse)
        val model = OperationRecord.restore(parsed, kind, OperationOrigin.USER,
            status, phase, created, timestamp, boundedProgress,
            failure = failure) ?: return null
        return ActivityEntry(id, model.kind.name, model.origin.name, model.status.name,
            model.phase.name, model.createdAtMillis, model.updatedAtMillis,
            model.progressPercent, source, model.failure?.code, model.schemaVersion)
    }

    private fun historicalTransfers(context: Context): List<ActivityEntry> {
        return ActivityTransferJournal.snapshots(context).mapNotNull { row ->
            val status = runCatching { OperationStatus.valueOf(row.getString("status")) }.getOrNull()
                ?: return@mapNotNull null
            val source = row.getString("source")
            val label = if (source == "RELEASE")
                "Historical first-party transfer · current file proof not implied"
            else "Historical CI artifact transfer · not publisher attestation · $source"
            snapshot(row.getString("id"), OperationKind.DOWNLOAD, status,
                row.getLong("updated"), label,
                progress = row.optInt("percent", -1).takeIf { it in 0..100 },
                failureCode = if (status == OperationStatus.FAILED) "TRANSFER_FAILED" else null,
                created = row.getLong("started"))
        }
    }

    private fun kind(action: String): OperationKind? = when (action) {
        "ENABLE" -> OperationKind.ENABLE
        "DISABLE" -> OperationKind.DISABLE
        "ADD_SCOPE", "REMOVE_SCOPE" -> OperationKind.SCOPE_CHANGE
        else -> null
    }

    /** All journal access is read-only. Pending writes remain blocked by VectorWriteJournal. */
    private fun vectorReceipts(context: Context): List<ActivityEntry> {
        val prefs = context.getSharedPreferences("vector_mutation_journal_v1", Context.MODE_PRIVATE)
        val saved = prefs.getString("receipts", "[]") ?: "[]"
        require(saved.length <= 64 * 1024) { "Invalid Vector receipt ledger" }
        val journal = JSONArray(saved)
        require(journal.length() <= 64) { "Invalid Vector receipt count" }
        val output = mutableListOf<ActivityEntry>()
        for (i in 0 until journal.length()) {
            val item = journal.getJSONObject(i)
            val action = kind(item.optString("action")) ?: continue
            val verdict = when (item.optString("verdict")) {
                "VERIFIED" -> OperationStatus.SUCCEEDED
                "RECONCILED_BY_USER" -> OperationStatus.INTERRUPTED
                else -> continue
            }
            snapshot("vector:${item.optString("id")}", action, verdict,
                item.optLong("closed_at"), "Vector journal · configuration receipt",
                created = item.optLong("started_at"))?.let(output::add)
        }
        VectorWriteJournal.pending(context)?.let { pending ->
            val action = kind(pending.action)
            if (action != null) snapshot("vector:${pending.id}", action,
                OperationStatus.RECONCILIATION_REQUIRED, pending.startedAtMillis,
                "Pending Vector write · outcome unknown")?.let(output::add)
        }
        return output
    }

    /** Durable past receipt does not assert present file state or live Vector authority. */
    private fun archivedReceipts(context: Context): List<ActivityEntry> =
        ActivityReceiptCapture.archived(context).mapNotNull { receipt ->
            val status = runCatching { OperationStatus.valueOf(receipt.status) }.getOrNull()
                ?: return@mapNotNull null
            val kind = runCatching { OperationKind.valueOf(receipt.kind) }.getOrNull()
                ?: return@mapNotNull null
            val label = when (receipt.source) {
                "VECTOR" -> "Historical Vector write receipt · independent read-only archive"
                "RELEASE" -> "Historical first-party transfer · current file status not implied"
                else -> "Historical CI transfer · not a publisher or signing attestation"
            }
            snapshot(receipt.id, kind, status, receipt.updated, label,
                progress = receipt.percent,
                failureCode = receipt.failure.takeUnless { it == "NONE" },
                created = receipt.started)
        }

    private fun digestId(source: String, sha: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$source:$sha".toByteArray(Charsets.UTF_8))
        return "download:" + bytes.take(16).joinToString("") { "%02x".format(it) }
    }

    /** Only saved files that pass existing full SHA-256 verification are projected. */
    suspend fun refresh(context: Context, page: Int = 0, pageSize: Int = 50): List<ActivityEntry> = mutex.withLock {
        require(page >= 0 && pageSize in 1..200 && page <= Int.MAX_VALUE / pageSize) {
            "Invalid Activity pagination parameters"
        }
        val events = vectorReceipts(context).toMutableList()
        events.addAll(historicalTransfers(context))
        for (saved in VerifiedDownload.listSaved(context)) {
            snapshot(digestId("release:${saved.releaseTag ?: "legacy"}:${saved.file.name}", saved.sha256), OperationKind.DOWNLOAD,
                OperationStatus.SUCCEEDED, saved.file.lastModified(),
                "Currently verified first-party release receipt")?.let(events::add)
        }
        for (saved in CiArtifactDownload.listSaved(context)) {
            snapshot(digestId("ci:${saved.runId}:${saved.attempt}:${saved.artifactId}:${saved.sourceSha}", saved.sha256), OperationKind.DOWNLOAD,
                OperationStatus.SUCCEEDED, saved.file.lastModified(),
                "Currently verified CI archive · not a release")?.let(events::add)
        }
        // The source journals are the present projection. An archive entry remains
        // strictly historical and never resurrects a deleted/currently-unverified file.
        val archived = archivedReceipts(context)
        val database = db(context)
        database.withTransaction {
            val dao = database.entries()
            dao.clearProjection()
            for (event in events.distinctBy { it.id }) {
                dao.upsert(event)
            }
            val history = LinkedHashMap<String, ActivityEntry>()
            for (event in events) {
                val historicalSource = when {
                    event.id.startsWith("download:") -> "Previously verified receipt · current file status not implied"
                    event.id.startsWith("vector:") -> "Historical Vector receipt · read-only journal snapshot"
                    else -> event.source
                }
                history[event.id] = event.copy(source = historicalSource)
            }
            // Durable terminal receipts outrank interrupted transient observations
            // only as historical facts, never as current trusted projections.
            for (entry in archived) history[entry.id] = entry
            // Historical records are durable; a hard 200-row cleanup destroyed evidence.
            // Bounded disk archival and user-driven paging are separate policies.
            for (event in history.values) dao.saveHistory(event.historical())
        }
        database.entries().page(pageSize, page * pageSize)
    }
}
