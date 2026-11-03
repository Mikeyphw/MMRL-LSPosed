package io.github.mikeyphw.mmrl.lsposed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * Private durable write-ahead record. No tokens, CLI output, or sensitive logs.
 * Any interrupted write blocks subsequent writes until explicit reconciliation.
 * Synchronous commits are deliberate: failure to persist stops privileged work.
 */
internal object VectorWriteJournal {
    data class Pending(val id: String, val module: String, val action: String, val target: String,
                       val startedAtMillis: Long)
    private const val NAME = "vector_mutation_journal_v1"
    private const val PENDING = "pending"
    private const val RECEIPTS = "receipts"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun pending(context: Context): Pending? {
        val json = prefs(context).getString(PENDING, null) ?: return null
        val obj = try { JSONObject(json) } catch (e: Exception) {
            throw IOException("Mutation journal cannot be read; writes locked", e)
        }
        val id = obj.optString("id")
        val module = obj.optString("module")
        val action = obj.optString("action")
        if (id.isBlank() || !VectorProtocol.validPackage(module) ||
            action !in setOf("ENABLE", "DISABLE", "ADD_SCOPE", "REMOVE_SCOPE"))
            throw IOException("Invalid mutation journal; writes locked")
        return Pending(id, module, action, obj.optString("target"), obj.optLong("started_at"))
    }

    fun begin(context: Context, module: String, action: String, target: String): Pending {
        if (pending(context) != null) throw IOException("Previous write requires reconciliation")
        val entry = Pending(UUID.randomUUID().toString(), module, action, target, System.currentTimeMillis())
        val obj = JSONObject().put("id", entry.id).put("module", entry.module)
            .put("action", entry.action).put("target", entry.target)
            .put("started_at", entry.startedAtMillis).put("state", "PENDING")
        if (!prefs(context).edit().putString(PENDING, obj.toString()).commit())
            throw IOException("Cannot persist write-ahead journal; write refused")
        return entry
    }

    /** Append a bounded receipt and clear the write lock atomically. */
    fun verified(context: Context, entry: Pending, before: String, after: String) =
        complete(context, entry, "VERIFIED", before, after)

    /** Reconciliation is explicitly user requested, never automatic retry or rollback. */
    fun reconciled(context: Context, entry: Pending, observed: String) =
        complete(context, entry, "RECONCILED_BY_USER", "outcome unknown", observed)

    private fun complete(context: Context, entry: Pending, verdict: String, before: String, after: String) {
        val active = pending(context) ?: throw IOException("Mutation journal was unexpectedly cleared")
        if (active.id != entry.id) throw IOException("Mutation journal identity changed")
        val store = prefs(context)
        val previous = store.getString(RECEIPTS, "[]") ?: "[]"
        val history = try { JSONArray(previous) } catch (e: Exception) {
            throw IOException("Mutation receipt ledger is corrupt; writes locked", e)
        }
        val receipt = JSONObject().put("id", entry.id).put("module", entry.module)
            .put("action", entry.action).put("target", entry.target)
            .put("started_at", entry.startedAtMillis).put("closed_at", System.currentTimeMillis())
            .put("verdict", verdict).put("before", before).put("after", after)
        val trimmed = JSONArray()
        for (i in maxOf(0, history.length() - 63) until history.length())
            trimmed.put(history.get(i))
        trimmed.put(receipt)
        if (!store.edit().putString(RECEIPTS, trimmed.toString()).remove(PENDING).commit())
            throw IOException("Cannot persist verified receipt; reconciliation still required")
    }
}
