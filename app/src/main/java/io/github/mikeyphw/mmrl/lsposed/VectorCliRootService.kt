package io.github.mikeyphw.mmrl.lsposed

import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.topjohnwu.superuser.ipc.RootService
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A non-daemon libsu service: 3 fixed reads + 4 single-target mutations.
 * No arbitrary shell, batch, scope set, config, database, or log commands.
 * Writes are permitted only through the app's pre-read, journal, confirmation
 * and read-back gate; the root side independently enforces fixed arguments.
 * Attribution: reviewed MMRL Phase06 VectorCliRootService design, GPL-3.0.
 */
class VectorCliRootService : RootService() {
    private val bridge = object : IVectorReadOnly.Stub() {
        override fun query(operation: String?, modulePackage: String?): String {
            if (Process.myUid() != 0) return failure("Root service is not UID 0")
            val module = modulePackage.orEmpty()
            val args = when (operation) {
                "status" -> if (module.isEmpty()) listOf("status") else null
                "modules ls" -> if (module.isEmpty()) listOf("modules", "ls") else null
                "scope ls" -> if (valid(module)) listOf("scope", "ls", module) else null
                else -> null
            } ?: return failure("Query is not allowlisted")
            return try { executeFixed(args) }
            catch (e: Exception) { failure("Vector query unavailable (${e.javaClass.simpleName})") }
        }

        override fun mutate(operation: String?, modulePackage: String?, targetPackage: String?): String {
            if (Process.myUid() != 0) return failure("Root service is not UID 0")
            val module = modulePackage.orEmpty()
            val target = targetPackage.orEmpty()
            if (!valid(module)) return failure("Invalid module package")
            val args = when (operation) {
                "modules enable" -> if (target.isEmpty()) listOf("modules", "enable", module) else null
                "modules disable" -> if (target.isEmpty()) listOf("modules", "disable", module) else null
                "scope add" -> if (safeScopeTarget(target)) listOf("scope", "add", module, target) else null
                "scope rm" -> if (safeScopeTarget(target)) listOf("scope", "rm", module, target) else null
                else -> null
            } ?: return failure("Mutation not allowlisted")
            return try { executeFixed(args) }
            catch (e: Exception) { failure("Vector mutation outcome unknown (${e.javaClass.simpleName})") }
        }
    }

    override fun onBind(intent: Intent): IBinder = bridge

    private fun failure(message: String): String =
        JSONObject().put("mmrl_transport_error", message.take(120)).toString()

    private fun valid(pkg: String) = pkg.length <= 180 && PKG.matches(pkg)

    private fun safeScopeTarget(target: String) =
        target.endsWith("/0") && valid(target.removeSuffix("/0"))

    private fun executeFixed(args: List<String>): String {
        // An exact executable in the installed module, not a user-controlled path.
        if (java.io.File("/data/local/tmp/daemon.apk").exists())
            return failure("Unreviewed Vector daemon override detected")
        // Vector's release launcher searches the installed module as well as Magisk's
        // runtime module tree. Do not execute a discovered arbitrary binary/path:
        // only these three known module locations are accepted.
        val executable = listOf(
            "/data/adb/modules/zygisk_vector/cli",
            "/sbin/.magisk/modules/zygisk_vector/cli",
            "/debug_ramdisk/.magisk/modules/zygisk_vector/cli",
        ).firstOrNull { java.io.File(it).isFile && java.io.File(it).canExecute() }
            ?: return failure("Vector CLI unavailable at approved module locations")
        val process = ProcessBuilder(listOf(executable, "--json") + args)
            .redirectErrorStream(true).start()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "lsposed-vector-cli-reader").apply { isDaemon = true }
        }
        try {
            val output = executor.submit(Callable {
                process.inputStream.use { input ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (bytes.size() + count > MAX_BYTES) throw IOException("CLI response too large")
                        bytes.write(buffer, 0, count)
                    }
                    bytes.toString(Charsets.UTF_8.name())
                }
            })
            if (!process.waitFor(12, TimeUnit.SECONDS)) return failure("Vector CLI timed out")
            if (process.exitValue() != 0) return failure("Vector CLI exited nonzero")
            val raw = output.get(2, TimeUnit.SECONDS).trim()
            if (raw.isEmpty() || raw.length > MAX_BYTES) return failure("Invalid Vector response length")
            JSONObject(raw) // Reject text or malformed replies.
            return raw
        } finally {
            process.destroyForcibly()
            executor.shutdownNow()
        }
    }

    private companion object {
        const val MAX_BYTES = 131072
        val PKG = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    }
}
