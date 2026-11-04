package io.github.mikeyphw.mmrl.lsposed

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Physical device only: tests real Vector IPC without modifying configuration. */
@RunWith(AndroidJUnit4::class)
class S08ReadOnlyDeviceTest {
    @Test fun inspectSavedConfigurationAndNoWrites() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val report = JSONObject().put("schema", "mmrl-lsposed.s08.read-only-device.v1")
            .put("module_writes", 0).put("scope_writes", 0)
            .put("hooks_verified", false).put("device_qualified", false)
            .put("status", "NOT_QUALIFIED")
        try {
            assertEquals("io.github.mikeyphw.mmrl.lsposed.debug", ctx.packageName)
            assertEquals("Primary Android user only", 0, Process.myUid() / 100000)
            val before = VectorReadOnly.inspect(ctx)
            assertEquals(102, before.apiVersion)
            var scopeCount = 0
            before.modules.keys.forEach { name ->
                VectorReadOnly.scopes(ctx, name)
                scopeCount++
            }
            val after = VectorReadOnly.inspect(ctx)
            assertEquals("Module state drift during read-only test", before.modules, after.modules)
            report.put("api", before.apiVersion).put("framework", before.frameworkVersion)
                .put("module_count", before.modules.size).put("scope_lists_read", scopeCount)
                .put("status", "PASS_READ_ONLY_CONFIG").put("device_qualified", true)
        } catch (e: Exception) {
            report.put("error_type", e.javaClass.simpleName)
                .put("error_detail", (e.message ?: "unavailable").take(180))
        } finally {
            val tmp = File(ctx.filesDir, "s08_read_only_device.json.tmp")
            val dest = File(ctx.filesDir, "s08_read_only_device.json")
            tmp.writeText(report.toString(2) + "\n")
            check(tmp.renameTo(dest))
        }
        assertTrue("Read-only device acceptance failed: " + report.optString("error_detail"),
            report.optString("status") == "PASS_READ_ONLY_CONFIG")
    }
}
