package io.github.mikeyphw.mmrl.lsposed

import org.json.JSONObject
import java.io.IOException

/**
 * Strict protocol parsing for Vector v2.2 build 3116 (API 102).
 * Data is saved daemon configuration, never evidence that a process is hooked.
 * Pure parsing permits hosted JVM unit tests without root/Android devices.
 */
internal object VectorProtocol {
    data class Status(val version: String, val api: Int)
    data class Module(val packageName: String, val enabled: Boolean)
    data class Scope(val packageName: String, val userId: Int) {
        val key: String get() = "$packageName/$userId"
    }

    private val PACKAGE = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private val VERSION = Regex("^v?2\\.2(?:$|[^0-9.].*)", RegexOption.IGNORE_CASE)
    fun validPackage(value: String): Boolean = value.length <= 180 && PACKAGE.matches(value)
    private fun validScope(value: String): Boolean = validPackage(value) || value == "android" || value == "system"

    private fun data(response: JSONObject): Any {
        if (!response.optBoolean("success", false)) throw IOException("Vector reported an unsuccessful request")
        return response.opt("data") ?: throw IOException("Vector reply contains no data")
    }

    fun status(response: JSONObject): Status {
        val status = data(response) as? JSONObject ?: throw IOException("Vector status is malformed")
        val version = status.optString("Framework Version", "").trim()
        val apiRaw = status.opt("API Version")
        val api = when (apiRaw) {
            is Number -> apiRaw.toInt()
            is String -> apiRaw.toIntOrNull() ?: -1
            else -> -1
        }
        if (api != 102 || !VERSION.matches(version))
            throw IOException("Only Vector v2.2 with API 102 is qualified")
        return Status(version.take(90), api)
    }

    fun modules(response: JSONObject): Map<String, Module> {
        val list = data(response) as? org.json.JSONArray ?: throw IOException("Vector modules are malformed")
        if (list.length() > 512) throw IOException("Too many Vector modules")
        val rows = linkedMapOf<String, Module>()
        for (i in 0 until list.length()) {
            val row = list.optJSONObject(i) ?: throw IOException("Malformed Vector module row")
            val pkg = row.optString("PACKAGE", "")
            val state = row.optString("STATUS", "")
            if (!validPackage(pkg) || state !in setOf("enabled", "disabled") || rows.containsKey(pkg))
                throw IOException("Invalid or duplicate Vector module")
            rows[pkg] = Module(pkg, state == "enabled")
        }
        return rows
    }

    fun scopes(response: JSONObject): List<Scope> {
        val list = data(response) as? org.json.JSONArray ?: throw IOException("Vector scopes are malformed")
        if (list.length() > 1000) throw IOException("Too many Vector scopes")
        val rows = ArrayList<Scope>(list.length())
        for (i in 0 until list.length()) {
            val row = list.optJSONObject(i) ?: throw IOException("Malformed Vector scope row")
            val pkg = row.optString("APP_PACKAGE", "")
            val rawUser = row.opt("USER_ID") as? Number
            val longUser = rawUser?.toLong() ?: -1L
            if (!validScope(pkg) || longUser < 0 || longUser > Int.MAX_VALUE ||
                rawUser == null || rawUser.toDouble() != longUser.toDouble())
                throw IOException("Invalid Vector user/package scope")
            rows += Scope(pkg, longUser.toInt())
        }
        if (rows.size != rows.distinct().size) throw IOException("Duplicate Vector scopes")
        return rows.sortedWith(compareBy({ it.packageName }, { it.userId }))
    }
}
