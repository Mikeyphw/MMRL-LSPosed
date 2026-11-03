package io.github.mikeyphw.mmrl.lsposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.topjohnwu.superuser.ipc.RootService
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Non-daemon root Binder transport. Never binds automatically during UI startup.
 * Original design adapted from MMRL Phase06 (GPL-3.0).
 */
internal object VectorRootTransport {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var connected: IVectorReadOnly? = null
    private var binding: ServiceConnection? = null

    fun query(context: Context, operation: String, modulePackage: String = ""): JSONObject {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw IOException("Vector IPC must run on a background dispatcher")
        val permitted = (operation == "status" || operation == "modules ls") && modulePackage.isEmpty() ||
            (operation == "scope ls" && modulePackage.length <= 180 &&
                PKG.matches(modulePackage))
        if (!permitted) throw IOException("Query not allowlisted")
        val remote = connect(context.applicationContext)
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "lsposed-vector-ipc").apply { isDaemon = true }
        }
        val raw = try {
            executor.submit(Callable { remote.query(operation, modulePackage) })
                .get(17, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            reset()
            throw IOException("Vector Binder call timed out", e)
        } catch (e: Exception) {
            reset()
            throw IOException("Vector Binder call unavailable", e)
        } finally { executor.shutdownNow() }
        if (raw.toByteArray(Charsets.UTF_8).size > 131072)
            throw IOException("Oversized Vector Binder response")
        val reply = try { JSONObject(raw) } catch (e: Exception) {
            throw IOException("Vector response is not JSON", e)
        }
        if (reply.has("mmrl_transport_error"))
            throw IOException(reply.optString("mmrl_transport_error").take(120))
        if (!reply.optBoolean("success", false))
            throw IOException("Vector reported unsuccessful read-only request")
        return reply
    }

    /** A single explicitly approved mutation. Callers must hold the write gate. */
    fun mutate(context: Context, operation: String, modulePackage: String, targetPackage: String = ""): JSONObject {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw IOException("Vector writes must run on an IO worker")
        if (!VectorProtocol.validPackage(modulePackage)) throw IOException("Invalid module package")
        val permitted = when (operation) {
            "modules enable", "modules disable" -> targetPackage.isEmpty()
            "scope add", "scope rm" -> targetPackage.endsWith("/0") &&
                VectorProtocol.validPackage(targetPackage.removeSuffix("/0"))
            else -> false
        }
        if (!permitted) throw IOException("Mutation operation is not allowlisted")
        val remote = connect(context.applicationContext)
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "lsposed-vector-write-ipc").apply { isDaemon = true }
        }
        val raw = try {
            executor.submit(Callable { remote.mutate(operation, modulePackage, targetPackage) })
                .get(17, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            reset()
            throw IOException("Vector write result unknown after Binder timeout", e)
        } catch (e: Exception) {
            reset()
            throw IOException("Vector write result unknown after Binder failure", e)
        } finally { executor.shutdownNow() }
        if (raw.toByteArray(Charsets.UTF_8).size > 131072)
            throw IOException("Vector write response exceeds limit; outcome unknown")
        val reply = try { JSONObject(raw) } catch (e: Exception) {
            throw IOException("Vector write returned malformed JSON; outcome unknown", e)
        }
        if (reply.has("mmrl_transport_error") || !reply.optBoolean("success", false))
            throw IOException("Vector did not confirm the write; refresh and reconcile")
        return reply
    }

    private fun connect(context: Context): IVectorReadOnly = synchronized(lock) {
        connected?.takeIf { it.asBinder().isBinderAlive }?.let { return@synchronized it }
        binding?.let { old -> main.post { runCatching { RootService.unbind(old) } } }
        connected = null
        binding = null
        val ready = CountDownLatch(1)
        val service = AtomicReference<IVectorReadOnly?>(null)
        val problem = AtomicReference("Root connection failed")
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service.set(IVectorReadOnly.Stub.asInterface(binder))
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {
                connected = null
                problem.set("Root connection disconnected")
                ready.countDown()
            }
            override fun onBindingDied(name: ComponentName?) {
                connected = null
                problem.set("Root binding died")
                ready.countDown()
            }
            override fun onNullBinding(name: ComponentName) {
                problem.set("Root service returned no binder")
                ready.countDown()
            }
        }
        if (!main.post {
                try { RootService.bind(Intent(context, VectorCliRootService::class.java), conn) }
                catch (_: Exception) { problem.set("Root binding refused"); ready.countDown() }
            }) throw IOException("Main looper refused RootService binding")
        val signalled = try { ready.await(20, TimeUnit.SECONDS) }
        catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            main.post { runCatching { RootService.unbind(conn) } }
            throw IOException("Root binding interrupted", e)
        }
        if (!signalled) {
            main.post { runCatching { RootService.unbind(conn) } }
            throw IOException("Root binding timed out")
        }
        val live = service.get()
        if (live == null || !live.asBinder().isBinderAlive) {
            main.post { runCatching { RootService.unbind(conn) } }
            throw IOException(problem.get())
        }
        binding = conn
        connected = live
        live
    }

    private fun reset() = synchronized(lock) {
        connected = null
        binding?.let { old -> main.post { runCatching { RootService.unbind(old) } } }
        binding = null
    }

    private val PKG = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
}
