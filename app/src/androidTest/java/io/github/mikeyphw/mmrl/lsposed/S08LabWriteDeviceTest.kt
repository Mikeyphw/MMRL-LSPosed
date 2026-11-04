package io.github.mikeyphw.mmrl.lsposed

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Opt-in instrumentation of the real S08 mutation API, never ordinary modules.
 * A separate host journal MUST already be fsynced before a one-use token is
 * placed in app-private storage. Any ambiguous write remains locked by S08.
 */
@RunWith(AndroidJUnit4::class)
class S08LabWriteDeviceTest {
    private companion object {
        const val MODULE = "com.mikeyphw.lsposed.hooklab.module"
        const val TARGET = "com.mikeyphw.lsposed.hooklab.target"
        const val CONSENT = "AUTHORIZE_S08_DISPOSABLE_LAB_ONLY"
    }

    @Test fun verifiedWritesAndExactRestoration() {
        val args = InstrumentationRegistry.getArguments()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val report = JSONObject().put("schema", "mmrl-lsposed.s08.lab-device.v1")
            .put("module_package", MODULE).put("scope_target", "$TARGET/0")
            .put("module_writes_attempted", 0).put("scope_writes_attempted", 0)
            .put("hooks_verified", false).put("status", "NOT_QUALIFIED")
            .put("stage", "preflight")
        val output = File(ctx.filesDir, "s08_lab_write_device.json")
        fun persist() {
            val temp = File(ctx.filesDir, "s08_lab_write_device.json.tmp")
            temp.writeText(report.toString(2) + "\n")
            check(temp.renameTo(output))
        }
        try {
            require(ctx.packageName == "io.github.mikeyphw.mmrl.lsposed.debug")
            require(Process.myUid() / 100000 == 0) { "Only user 0 may be used" }
            require(args.getString("s08_lab_authorization") == CONSENT) { "Explicit host consent required" }
            val nonce = args.getString("s08_lab_nonce").orEmpty()
            val source = args.getString("s08_source_sha").orEmpty()
            require(Regex("[a-f0-9]{48}").matches(nonce)) { "Missing host-issued one-use nonce" }
            require(Regex("[a-f0-9]{40}").matches(source)) { "Missing pinned source SHA" }
            val token = File(ctx.filesDir, "s08_lab_write_token")
            require(token.isFile && token.readText() == "$nonce:$source:$MODULE:$TARGET") {
                "Source-bound token not present"
            }
            require(token.delete()) { "Cannot consume one-use token" }
            check(VectorWriteJournal.pending(ctx) == null) { "Pending write lock must be reconciled first" }
            listOf(MODULE, TARGET).forEach { ctx.packageManager.getApplicationInfo(it, 0) }
            val before = VectorReadOnly.inspect(ctx)
            val candidate = ModuleDiscovery.scan(ctx).singleOrNull { it.packageName == MODULE }
                ?: error("Recognized lab module APK required")
            check(candidate.staticScope && candidate.declaredScopes == listOf(TARGET)) {
                "Lab must declare only its disposable static target"
            }
            check(before.apiVersion == 102 && before.modules[MODULE]?.enabled == false) {
                "Lab must start disabled under Vector 2.2/API 102"
            }
            val baseline = VectorReadOnly.scopes(ctx, MODULE)
            val target = VectorProtocol.Scope(TARGET, 0)
            check(baseline.isEmpty() || baseline == listOf(target)) { "Unrecognized lab scope" }
            val otherStates = before.modules.filterKeys { it != MODULE }
            report.put("original_enabled", false)
                .put("original_scope", if (baseline.isEmpty()) "EMPTY" else "LAB_TARGET")
                .put("stage", "BASELINE_CONFIRMED")
            persist()

            fun action(kind: VectorMutation.Action, enabled: Boolean,
                       scope: List<VectorProtocol.Scope>, targetPackage: String = ""):
                    VectorMutation.Outcome {
                val scopeEdit = kind == VectorMutation.Action.ADD_SCOPE || kind == VectorMutation.Action.REMOVE_SCOPE
                report.put("stage", "INTEND_${kind.name}")
                    .put(if (scopeEdit) "scope_writes_attempted" else "module_writes_attempted",
                        report.getInt(if (scopeEdit) "scope_writes_attempted" else "module_writes_attempted") + 1)
                persist() // fsynced host journal is authoritative; this is independent device evidence.
                val result = VectorMutation.change(ctx, VectorMutation.Request(
                    MODULE, kind, enabled, scope, targetPackage))
                report.put("stage", "VERIFIED_${kind.name}")
                    .put("latest_receipt_id", result.receiptId)
                persist()
                return result
            }

            action(VectorMutation.Action.ENABLE, false, baseline)
            val flip = if (baseline.isEmpty()) VectorMutation.Action.ADD_SCOPE
                       else VectorMutation.Action.REMOVE_SCOPE
            val first = action(flip, true, baseline, TARGET).scopes
            check(first == if (baseline.isEmpty()) listOf(target) else emptyList())
            val undo = if (baseline.isEmpty()) VectorMutation.Action.REMOVE_SCOPE
                       else VectorMutation.Action.ADD_SCOPE
            action(undo, true, first, TARGET)
            action(VectorMutation.Action.DISABLE, true, baseline)

            val after = VectorReadOnly.inspect(ctx)
            check(after.modules[MODULE]?.enabled == false &&
                VectorReadOnly.scopes(ctx, MODULE) == baseline) { "Lab state not restored" }
            check(after.modules.filterKeys { it != MODULE } == otherStates) {
                "Unrelated module states changed"
            }
            check(VectorWriteJournal.pending(ctx) == null) { "Unresolved mutation journal" }
            report.put("stage", "RESTORED")
                .put("restored", true)
                .put("unrelated_modules_unchanged", true)
                .put("status", "PASS_LAB_WRITE_AND_RESTORE")
            persist()
        } catch (e: Exception) {
            report.put("stage", "RECOVERY_REQUIRED")
                .put("status", "NOT_QUALIFIED")
                .put("error_type", e.javaClass.simpleName)
                .put("error_detail", (e.message ?: "Unknown error").take(160))
            persist()
        }
        assertTrue("Lab write/restoration incomplete: " + report.optString("stage") + " / " +
            report.optString("error_detail"), report.optString("status") == "PASS_LAB_WRITE_AND_RESTORE")
    }
}
