package io.github.mikeyphw.mmrl.lsposed

import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class VectorWritePolicyTest {
    private val module = "dev.example.module"
    private val target = VectorProtocol.Scope("dev.example.target", 0)
    private val before = mapOf(module to VectorProtocol.Module(module, true),
        "dev.example.other" to VectorProtocol.Module("dev.example.other", false))

    @Test fun exactlyOneModuleAcknowledgement() {
        val ok = JSONObject("""{"success":true,"data":{"Enabled":["$module"],"Failed":[]}}""")
        VectorWritePolicy.verifyModuleAcknowledgement(ok, "modules enable", module)
        val rejects = listOf(
            """{"success":true,"data":{"Enabled":["$module"]}}""",
            """{"success":true,"data":{"Enabled":["$module","$module"],"Failed":[]}}""",
            """{"success":true,"data":{"Enabled":["dev.other.module"],"Failed":[]}}""",
            """{"success":false,"data":{"Enabled":["$module"],"Failed":[]}}""",
            """{"success":true,"data":{"Enabled":["$module"],"Failed":["oops"]}}""",
        )
        rejects.forEach { raw ->
            assertThrows(IOException::class.java) {
                VectorWritePolicy.verifyModuleAcknowledgement(JSONObject(raw), "modules enable", module)
            }
        }
    }

    @Test fun scopeNeedsSuccessfulReply() {
        VectorWritePolicy.verifyScopeAcknowledgement(JSONObject("""{"success":true,"data":{}}"""))
        assertThrows(IOException::class.java) {
            VectorWritePolicy.verifyScopeAcknowledgement(JSONObject("""{"success":false}"""))
        }
        assertThrows(IOException::class.java) {
            VectorWritePolicy.verifyScopeAcknowledgement(JSONObject("""{"success":true,"data":{"Failed":["bad"]}}"""))
        }
    }

    @Test fun rejectUnintendedModuleAndScopeChanges() {
        val expected = before + (module to VectorProtocol.Module(module, false))
        VectorWritePolicy.verifyReadBack(before, expected, module, false,
            listOf(target), listOf(target), listOf(target))
        val thirdParty = expected + ("dev.example.other" to VectorProtocol.Module("dev.example.other", true))
        assertThrows(IOException::class.java) {
            VectorWritePolicy.verifyReadBack(before, thirdParty, module, false,
                listOf(target), listOf(target), listOf(target))
        }
        assertThrows(IOException::class.java) {
            VectorWritePolicy.verifyReadBack(before, expected, module, false,
                listOf(target), emptyList(), listOf(target))
        }
    }

    @Test fun verifiedScopeDiffPreservesEnabledStatus() {
        val entries = listOf(target)
        VectorWritePolicy.verifyReadBack(before, before, module, true,
            emptyList(), entries, entries)
        assertThrows(IOException::class.java) {
            VectorWritePolicy.verifyReadBack(before, before, module, false,
                emptyList(), entries, entries)
        }
    }
}
