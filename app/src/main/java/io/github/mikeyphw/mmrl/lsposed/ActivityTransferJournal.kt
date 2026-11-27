package io.github.mikeyphw.mmrl.lsposed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** App-owned durable *download* operation journal. Not a Vector mutation authority.
 * It stores bounded opaque IDs, normalized status and numeric provenance only;
 * no URLs, downloaded paths, credentials, HTTP headers or exception strings.
 */
internal object ActivityTransferJournal {
    private const val FILE = "activity_transfer_ledger_v1"
    private const val KEY = "entries"
    private const val LIMIT = 180
    private const val MAX_BYTES = 64 * 1024
    private val session = UUID.randomUUID().toString()
    private val allowedSource = Regex("(?:RELEASE|CI:[1-9][0-9]*:[1-9][0-9]*:[1-9][0-9]*)")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun read(context: Context): JSONArray {
        val saved = prefs(context).getString(KEY, "[]") ?: throw IOException("Missing history ledger")
        if (saved.toByteArray(Charsets.UTF_8).size > MAX_BYTES)
            throw IOException("Oversize history ledger")
        val array = try { JSONArray(saved) } catch (_: Exception) {
            throw IOException("Corrupted history ledger")
        }
        if (array.length() > LIMIT) throw IOException("Unexpected history ledger size")
        return array
    }

    private fun persist(context: Context, array: JSONArray) {
        val encoded = array.toString()
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_BYTES ||
            !prefs(context).edit().putString(KEY, encoded).commit())
            throw IOException("Unable to persist history ledger")
    }

    /** Never evict a still-running operation to admit another one.
     * Old terminal entries may still roll off this bounded journal; durable historical
     * capture must be addressed separately before M1-C acceptance.
     */
    private fun trimmed(array: JSONArray): JSONArray {
        val retained = ArrayList<JSONObject>(array.length())
        for (i in 0 until array.length()) retained.add(array.getJSONObject(i))
        while (retained.size >= LIMIT) {
            val firstTerminal = retained.indexOfFirst { it.optString("status") != "RUNNING" }
            if (firstTerminal < 0) throw IOException("Unfinished transfer journal capacity reached")
            retained.removeAt(firstTerminal)
        }
        return JSONArray().apply { retained.forEach { put(it) } }
    }

    /** Reconcile prior-process entries before admitting a new transfer or projecting history.
     * No previous operation is resumed, and no current-process transfer is interrupted.
     */
    private fun recoverAbandoned(context: Context, array: JSONArray) {
        var changed = false
        for (i in 0 until array.length()) {
            val row = array.getJSONObject(i)
            if (row.optString("status") == "RUNNING" && row.optString("session") != session) {
                row.put("status", "INTERRUPTED")
                row.put("updated", System.currentTimeMillis().coerceAtLeast(row.optLong("updated")))
                changed = true
            }
        }
        if (changed) persist(context, array)
    }

    /** Must be committed before starting the network operation. */
    @Synchronized fun begin(context: Context, source: String): String {
        if (!allowedSource.matches(source)) throw IOException("Invalid public transfer source")
        val now = System.currentTimeMillis().coerceAtLeast(1L)
        val id = "transfer:${UUID.randomUUID()}"
        val prior = read(context)
        recoverAbandoned(context, prior)
        // Archive terminal receipts before the bounded operational ledger can trim them.
        // Archive failure denies a new transfer rather than silently losing history.
        ActivityReceiptCapture.captureTransfers(context, prior)
        val array = trimmed(prior)
        array.put(JSONObject().put("id", id).put("source", source)
            .put("status", "RUNNING").put("started", now).put("updated", now)
            .put("session", session).put("percent", 0))
        persist(context, array)
        return id
    }

    @Synchronized fun progress(context: Context, id: String, bytes: Long, total: Long) {
        if (total <= 0 || bytes < 0 || bytes > total) return
        // Multiplying Long byte counts by 100 can overflow for valid huge transfers.
        val percent = ((bytes.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 99)
        val array = read(context)
        for (i in 0 until array.length()) {
            val row = array.getJSONObject(i)
            if (row.optString("id") != id) continue
            if (row.optString("status") != "RUNNING" || row.optString("session") != session)
                throw IOException("Transfer is not active in this process")
            val previous = row.optInt("percent")
            if (percent <= previous || percent / 5 <= previous / 5) return
            row.put("percent", percent).put("updated", System.currentTimeMillis().coerceAtLeast(row.optLong("updated")))
            persist(context, array)
            return
        }
        throw IOException("Transfer history identity missing")
    }

    /** Terminal updates are durable; caller success is reported only after commit. */
    @Synchronized fun finish(context: Context, id: String, verdict: String) {
        if (verdict !in setOf("SUCCEEDED", "FAILED", "CANCELLED"))
            throw IOException("Invalid transfer verdict")
        val array = read(context)
        for (i in 0 until array.length()) {
            val row = array.getJSONObject(i)
            if (row.optString("id") != id) continue
            if (row.optString("status") != "RUNNING" || row.optString("session") != session)
                throw IOException("Transfer is not active in this process")
            row.put("status", verdict).put("updated", System.currentTimeMillis().coerceAtLeast(row.optLong("updated")))
            if (verdict == "SUCCEEDED") row.put("percent", 100)
            else if (verdict == "FAILED") row.put("failure", "TRANSFER_FAILED")
            persist(context, array)
            // Terminal result is durable in preferences before the redundant historical archive.
            // If archival fails, the terminal entry remains for a safe later replay.
            ActivityReceiptCapture.captureTransfers(context, array)
            return
        }
        throw IOException("Transfer history identity missing")
    }

    /** Opening Activity never resumes work; old-process active transfers become interrupted. */
    @Synchronized fun snapshots(context: Context): List<JSONObject> {
        val array = read(context)
        recoverAbandoned(context, array)
        val output = ArrayList<JSONObject>(array.length())
        for (i in 0 until array.length()) {
            val row = array.getJSONObject(i)
            val id = row.optString("id")
            val status = row.optString("status")
            val source = row.optString("source")
            val start = row.optLong("started")
            val updated = row.optLong("updated")
            val progress = row.optInt("percent", -1)
            if (!id.startsWith("transfer:") || !allowedSource.matches(source) ||
                status !in setOf("RUNNING","SUCCEEDED","FAILED","CANCELLED","INTERRUPTED") ||
                start <= 0 || updated < start || progress !in 0..100)
                throw IOException("Invalid persisted transfer record")
            output.add(JSONObject(row.toString()))
        }
        ActivityReceiptCapture.captureTransfers(context, array)
        return output
    }
}
