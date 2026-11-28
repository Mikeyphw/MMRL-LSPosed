package io.github.mikeyphw.mmrl.lsposed

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Passive read-only observer: never edits the Vector journal, changes scopes or authorizes writes. */
internal object ActivityReceiptCapture {
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var preferences: SharedPreferences? = null
    private val lock = Any()

    private fun vectorKind(raw: String): String? = when (raw) {
        "ENABLE", "DISABLE" -> raw
        "ADD_SCOPE", "REMOVE_SCOPE" -> "SCOPE_CHANGE"
        else -> null
    }

    @Synchronized fun captureTransfers(context: Context, ledger: JSONArray) {
        for (i in 0 until ledger.length()) {
            val row = ledger.getJSONObject(i)
            val status = row.optString("status")
            if (status == "RUNNING") continue
            val receipt = ArchivedActivityReceipt(
                id = row.optString("id"), source = row.optString("source"),
                kind = "DOWNLOAD", status = status,
                started = row.optLong("started"), updated = row.optLong("updated"),
                percent = row.optInt("percent", -1),
                failure = if (status == "FAILED") "TRANSFER_FAILED" else "NONE",
            )
            ActivityReceiptArchive.append(context.filesDir, receipt)
        }
    }

    /** Only a completed/explicitly-reconciled Vector receipt is archived. No module/target/before/after captured. */
    @Synchronized fun captureVector(context: Context, prefs: SharedPreferences) {
        val raw = prefs.getString("receipts", "[]") ?: throw IOException("Missing Vector receipts")
        if (raw.toByteArray(Charsets.UTF_8).size > 64 * 1024) throw IOException("Oversize Vector receipts")
        val ledger = try { JSONArray(raw) } catch (_: Exception) { throw IOException("Corrupt Vector receipts") }
        if (ledger.length() > 64) throw IOException("Unbounded Vector receipts")
        for (i in 0 until ledger.length()) {
            val row = ledger.getJSONObject(i)
            val kind = vectorKind(row.optString("action")) ?: continue
            val status = when (row.optString("verdict")) {
                "VERIFIED" -> "SUCCEEDED"
                "RECONCILED_BY_USER" -> "INTERRUPTED"
                else -> continue
            }
            val receipt = ArchivedActivityReceipt(
                id = "vector:${row.optString("id")}", source = "VECTOR",
                kind = kind, status = status,
                started = row.optLong("started_at"), updated = row.optLong("closed_at"),
                percent = if (status == "SUCCEEDED") 100 else 0,
            )
            ActivityReceiptArchive.append(context.filesDir, receipt)
        }
    }

    /** Caller supplies its long-lived application context before UI-authorized
     * Vector writes. Preserve a strong listener reference and the supplied
     * storage namespace; never substitute a different package context.
     */
    @Synchronized fun start(context: Context) {
        if (listener != null) return
        val app = context
        val prefs = app.getSharedPreferences("vector_mutation_journal_v1", Context.MODE_PRIVATE)
        // First ingest existing receipts; if corrupt, refuse to register a falsely healthy recorder.
        captureVector(app, prefs)
        val observer = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == "receipts") {
                try { captureVector(app, changed) }
                catch (_: Exception) {
                    android.util.Log.e("ActivityArchive", "Read-only Vector history capture failed")
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(observer)
        preferences = prefs
        listener = observer
    }

    /** Synchronous second capture path immediately after an authorized UI write finishes. */
    fun afterUiWrite(context: Context): Boolean =
        runCatching {
            val app = context
            captureVector(app, app.getSharedPreferences("vector_mutation_journal_v1", Context.MODE_PRIVATE))
        }.fold(onSuccess = { true }, onFailure = {
            android.util.Log.e("ActivityArchive", "Post-write history capture unavailable")
            false
        })

    fun archived(context: Context): List<ArchivedActivityReceipt> =
        ActivityReceiptArchive.read(context.filesDir)
}
