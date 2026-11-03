package io.github.mikeyphw.mmrl.lsposed

import org.json.JSONObject
import java.io.IOException

/** Pure fail-closed verification and acknowledgement rules; exercised by hosted JVM tests. */
internal object VectorWritePolicy {
    fun verifyModuleAcknowledgement(reply: JSONObject, operation: String, module: String) {
        if (operation !in setOf("modules enable", "modules disable"))
            throw IOException("Unsupported module acknowledgement")
        if (!reply.optBoolean("success", false)) throw IOException("Vector did not acknowledge operation")
        val data = reply.optJSONObject("data") ?: throw IOException("Missing module acknowledgement")
        val failed = data.optJSONArray("Failed") ?: throw IOException("Missing failure list")
        val correct = data.optJSONArray(if (operation == "modules enable") "Enabled" else "Disabled")
            ?: throw IOException("Missing module success list")
        if (failed.length() != 0 || correct.length() != 1 || correct.optString(0) != module)
            throw IOException("Incomplete or conflicting Vector module acknowledgement")
    }

    fun verifyScopeAcknowledgement(reply: JSONObject) {
        if (!reply.optBoolean("success", false)) throw IOException("Vector scope mutation rejected")
        if ((reply.optJSONObject("data")?.optJSONArray("Failed")?.length() ?: 0) != 0)
            throw IOException("Vector scope mutation reported failed targets")
    }

    fun verifyReadBack(
        before: Map<String, VectorProtocol.Module>,
        after: Map<String, VectorProtocol.Module>,
        module: String,
        wantedEnabled: Boolean,
        oldScopes: List<VectorProtocol.Scope>,
        actualScopes: List<VectorProtocol.Scope>,
        wantedScopes: List<VectorProtocol.Scope>,
    ) {
        if (!before.containsKey(module)) throw IOException("Module absent before mutation")
        val expected = before.toMutableMap().apply {
            this[module] = VectorProtocol.Module(module, wantedEnabled)
        }
        if (expected != after || wantedScopes != actualScopes ||
            oldScopes.distinct().size != oldScopes.size ||
            actualScopes.distinct().size != actualScopes.size)
            throw IOException("Unexpected Vector side effects; reconcile manually")
    }
}
