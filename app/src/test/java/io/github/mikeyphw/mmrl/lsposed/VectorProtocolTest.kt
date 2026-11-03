package io.github.mikeyphw.mmrl.lsposed

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class VectorProtocolTest {
    private fun parse(value: String) = JSONObject(value)

    @Test fun statusAcceptsOnlyExactQualifiedVersion() {
        val reply = parse("""{"success":true,"data":{"Framework Version":"v2.2","API Version":102}}""")
        assertEquals(102, VectorProtocol.status(reply).api)
        assertEquals(102, VectorProtocol.status(parse("""{"success":true,"data":{"Framework Version":"2.2","API Version":"102"}}""")).api)
        for (version in listOf("2.20", "12.2", "v2.3", "v2.2.1")) {
            assertThrows(IOException::class.java) {
                VectorProtocol.status(parse("""{"success":true,"data":{"Framework Version":"$version","API Version":102}}"""))
            }
        }
        assertThrows(IOException::class.java) {
            VectorProtocol.status(parse("""{"success":true,"data":{"Framework Version":"v2.2","API Version":101}}"""))
        }
        assertThrows(IOException::class.java) {
            VectorProtocol.status(parse("""{"success":false,"data":{"Framework Version":"v2.2","API Version":102}}"""))
        }
    }

    @Test fun modulesAreSavedFlagsNotHookClaims() {
        val modules = VectorProtocol.modules(parse("""{"success":true,"data":[{"PACKAGE":"com.example.alpha","STATUS":"enabled"},{"PACKAGE":"com.example.beta","STATUS":"disabled"}]}"""))
        assertEquals(2, modules.size)
        assertTrue(modules["com.example.alpha"]!!.enabled)
        assertFalse(modules["com.example.beta"]!!.enabled)
        assertThrows(IOException::class.java) {
            VectorProtocol.modules(parse("""{"success":true,"data":[{"PACKAGE":"com.example.alpha","STATUS":"enabled"},{"PACKAGE":"com.example.alpha","STATUS":"disabled"}]}"""))
        }
        assertThrows(IOException::class.java) {
            VectorProtocol.modules(parse("""{"success":true,"data":[{"PACKAGE":"com.example.alpha","STATUS":"hooked"}]}"""))
        }
    }

    @Test fun scopesAcceptSpecialReadOnlyTargetsAndValidateUsers() {
        val rows = VectorProtocol.scopes(parse("""{"success":true,"data":[{"APP_PACKAGE":"system","USER_ID":0},{"APP_PACKAGE":"com.example.target","USER_ID":10},{"APP_PACKAGE":"android","USER_ID":0}]}"""))
        assertEquals(3, rows.size)
        assertTrue(rows.any { it.key == "system/0" })
        assertTrue(rows.any { it.key == "com.example.target/10" })
        for (user in listOf("-1", "1.5", "2147483648")) {
            assertThrows(IOException::class.java) {
                VectorProtocol.scopes(parse("""{"success":true,"data":[{"APP_PACKAGE":"com.example.app","USER_ID":$user}]}"""))
            }
        }
        assertThrows(IOException::class.java) {
            VectorProtocol.scopes(parse("""{"success":true,"data":[{"APP_PACKAGE":"android","USER_ID":0},{"APP_PACKAGE":"android","USER_ID":0}]}"""))
        }
    }
}
