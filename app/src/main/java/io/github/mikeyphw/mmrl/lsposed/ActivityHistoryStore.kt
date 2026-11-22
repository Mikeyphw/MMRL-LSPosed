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
    val schemaVersion: Int,
)

@Dao
internal interface ActivityEntryDao {
    @Query("SELECT * FROM activity_operations ORDER BY updatedAtMillis DESC LIMIT 200")
    suspend fun recent(): List<ActivityEntry>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ActivityEntry)
}

@Database(entities = [ActivityEntry::class], version = 1, exportSchema = true)
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
                .build().also { database = it }
        }

    private fun snapshot(id: String, kind: OperationKind, status: OperationStatus,
                         timestamp: Long, source: String): ActivityEntry? {
        val parsed = OperationId.parse(id) ?: return null
        if (timestamp <= 0) return null
        val terminal = status.terminal
        val phase = if (terminal) OperationPhase.FINISHED else OperationPhase.WAITING_FOR_USER
        val progress = if (status == OperationStatus.SUCCEEDED) 100 else null
        val model = OperationRecord.restore(parsed, kind, OperationOrigin.USER,
            status, phase, timestamp, timestamp, progress) ?: return null
        return ActivityEntry(id, model.kind.name, model.origin.name, model.status.name,
            model.phase.name, model.createdAtMillis, model.updatedAtMillis,
            model.progressPercent, source, model.schemaVersion)
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
                item.optLong("closed_at"), "Vector journal")?.let(output::add)
        }
        VectorWriteJournal.pending(context)?.let { pending ->
            val action = kind(pending.action)
            if (action != null) snapshot("vector:${pending.id}", action,
                OperationStatus.RECONCILIATION_REQUIRED, pending.startedAtMillis,
                "Pending Vector write · outcome unknown")?.let(output::add)
        }
        return output
    }

    private fun digestId(source: String, sha: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$source:$sha".toByteArray(Charsets.UTF_8))
        return "download:" + bytes.take(16).joinToString("") { "%02x".format(it) }
    }

    /** Only saved files that pass existing full SHA-256 verification are projected. */
    suspend fun refresh(context: Context): List<ActivityEntry> = mutex.withLock {
        val dao = db(context).entries()
        val events = vectorReceipts(context).toMutableList()
        for (saved in VerifiedDownload.listSaved(context)) {
            snapshot(digestId("release", saved.sha256), OperationKind.DOWNLOAD,
                OperationStatus.SUCCEEDED, saved.file.lastModified(),
                "Verified first-party release receipt")?.let(events::add)
        }
        for (saved in CiArtifactDownload.listSaved(context)) {
            snapshot(digestId("ci", saved.sha256), OperationKind.DOWNLOAD,
                OperationStatus.SUCCEEDED, saved.file.lastModified(),
                "Verified CI artifact receipt · not a release")?.let(events::add)
        }
        for (event in events) dao.upsert(event)
        dao.recent()
    }
}
