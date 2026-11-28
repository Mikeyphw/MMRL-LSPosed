package io.github.mikeyphw.mmrl.lsposed

import io.github.mikeyphw.mmrl.shared.Sha256Digest
import io.github.mikeyphw.mmrl.shared.TransferFailure
import io.github.mikeyphw.mmrl.shared.TransferRetryPolicy
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Persist only bounded public identity, never a token, signed redirect, or private path. */
internal data class QueuedSource(
    val kind: String, val name: String, val size: Long, val digest: String,
    val tag: String = "", val runId: Long = 0, val runAttempt: Int = 0,
    val headSha: String = "", val event: String = "", val headBranch: String = "", val artifactId: Long = 0,
) {
    init {
        require(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}").matches(name) && !name.contains(".."))
        require(Regex("sha256:[a-f0-9]{64}").matches(digest) && Sha256Digest.parse(digest) != null && size in 1..(150L * 1024 * 1024))
        require(if (kind == "RELEASE") {
            Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}").matches(tag) && !tag.contains("..") &&
                (name.endsWith(".apk", true) || name.endsWith(".zip", true)) && size <= 104_857_600L &&
                runId == 0L && runAttempt == 0 && artifactId == 0L && headSha.isEmpty() && event.isEmpty() && headBranch.isEmpty()
        } else {
            kind == "CI" && tag.isEmpty() && runId > 0 && runAttempt > 0 && artifactId > 0 &&
                Regex("[a-f0-9]{40}").matches(headSha) && (event == "pull_request" || event == "push" && headBranch == "main") &&
                headBranch in setOf("", "main")
        })
    }
}

internal enum class QueueStatus { QUEUED, RUNNING, RETRY_WAIT, CANCELLING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED }
internal data class QueueEntry(
    val id: String, val source: QueuedSource, val state: QueueStatus = QueueStatus.QUEUED,
    val attempt: Int = 0, val bytes: Long = 0, val retryAt: Long = 0,
    val activityId: String = "", val failure: TransferFailure? = null,
) {
    init {
        require(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").matches(id))
        require(attempt in 0..3 && bytes in 0..source.size && retryAt >= 0)
        require(activityId.isEmpty() || Regex("transfer:[a-f0-9-]{36}").matches(activityId))
        require(state != QueueStatus.SUCCEEDED || bytes == source.size)
        require(state != QueueStatus.RETRY_WAIT || failure?.retryable == true && retryAt > 0)
    }
    val active: Boolean get() = state in setOf(QueueStatus.QUEUED, QueueStatus.RUNNING, QueueStatus.RETRY_WAIT, QueueStatus.CANCELLING)
}

/** Single app-private, atomically replaced snapshot. A process lock prevents multiple schedulers. */
internal class QueueStore(private val root: File) : AutoCloseable {
    private val lockChannel: FileChannel
    private val lock: java.nio.channels.FileLock
    private val file = File(root, "queue-v1.dat")
    init {
        TransferPublication.prepare(root)
        val lockFile = File(root, "queue.lock")
        if (TransferPublication.exists(lockFile) && !TransferPublication.safeRegular(root, lockFile))
            throw IOException("Unsafe queue lock")
        lockChannel = FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        try { lock = lockChannel.tryLock() ?: throw IOException("Download queue already owned") }
        catch (e: Exception) { lockChannel.close(); throw e }
    }
    fun read(): List<QueueEntry> {
        if (!TransferPublication.exists(file)) return emptyList()
        if (!TransferPublication.safeRegular(root, file) || file.length() !in 1..MAX_BYTES) throw IOException("Invalid queue snapshot")
        return DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != MAGIC) throw IOException("Unknown queue schema")
            val count = input.readInt()
            if (count !in 0..LIMIT) throw IOException("Queue capacity exceeded")
            val rows = List(count) {
                val id = input.readUTF()
                val source = QueuedSource(input.readUTF(), input.readUTF(), input.readLong(), input.readUTF(),
                    input.readUTF(), input.readLong(), input.readInt(), input.readUTF(), input.readUTF(), input.readUTF(), input.readLong())
                QueueEntry(id, source, QueueStatus.valueOf(input.readUTF()), input.readInt(), input.readLong(), input.readLong(),
                    input.readUTF(), input.readUTF().takeIf { it.isNotEmpty() }?.let(TransferFailure::valueOf))
            }
            if (input.read() != -1 || rows.map { it.id }.distinct().size != rows.size ||
                rows.map { it.source }.distinct().size != rows.size) throw IOException("Invalid queue identities")
            rows
        }
    }
    fun write(rows: List<QueueEntry>) {
        require(rows.size <= LIMIT && rows.map { it.id }.distinct().size == rows.size)
        if (TransferPublication.exists(file) && !TransferPublication.safeRegular(root, file)) throw IOException("Unsafe queue snapshot")
        val temp = File.createTempFile(".queue-", ".pending", root)
        try {
            FileOutputStream(temp).use { raw ->
                val out = DataOutputStream(raw.buffered())
                out.writeInt(MAGIC); out.writeInt(rows.size)
                for (row in rows) {
                    val s = row.source
                    out.writeUTF(row.id); out.writeUTF(s.kind); out.writeUTF(s.name); out.writeLong(s.size); out.writeUTF(s.digest)
                    out.writeUTF(s.tag); out.writeLong(s.runId); out.writeInt(s.runAttempt); out.writeUTF(s.headSha)
                    out.writeUTF(s.event); out.writeUTF(s.headBranch); out.writeLong(s.artifactId)
                    out.writeUTF(row.state.name); out.writeInt(row.attempt); out.writeLong(row.bytes); out.writeLong(row.retryAt)
                    out.writeUTF(row.activityId); out.writeUTF(row.failure?.name ?: "")
                }
                out.flush(); raw.fd.sync()
            }
            if (temp.length() > MAX_BYTES) throw IOException("Queue snapshot too large")
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } finally { temp.delete() }
    }
    override fun close() { lock.release(); lockChannel.close() }
    companion object { const val LIMIT = 64; private const val MAX_BYTES = 128 * 1024L; private const val MAGIC = 0x4d325132 }
}

/** Application-owned read-only transfer scheduler. Two workers; no automatic restart after process death. */
internal class DurableTransferQueue(
    private val store: QueueStore,
    private val perform: (QueueEntry, () -> Boolean, (String) -> Unit, (Long) -> Unit) -> Unit,
    private val discard: (QueueEntry) -> Unit,
    private val policy: TransferRetryPolicy = TransferRetryPolicy(maxAttempts = 3, baseMillis = 1000, capMillis = 60_000),
    private val now: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val pool = ScheduledThreadPoolExecutor(2) { work -> Thread(work, "mmrl-transfer").apply { isDaemon = true } }
    private val running = mutableMapOf<String, AtomicBoolean>()
    private var wake: java.util.concurrent.ScheduledFuture<*>? = null
    private var closed = false
    private var fault = false
    private var rows = store.read().map { if (it.active) it.copy(state = QueueStatus.INTERRUPTED, retryAt = 0) else it }
    init { store.write(rows) }

    @Synchronized fun snapshot(): List<QueueEntry> {
        if (fault) throw IOException("Download queue persistence failed; restart to reconcile")
        return rows.toList()
    }
    private fun commit(next: List<QueueEntry>) {
        if (closed || fault) throw IOException("Download queue unavailable")
        try { store.write(next); rows = next }
        catch (error: Exception) {
            fault = true; running.values.forEach { it.set(true) }; wake?.cancel(false)
            throw IOException("Download queue persistence failed", error)
        }
    }
    private fun update(id: String, transform: (QueueEntry) -> QueueEntry) {
        if (rows.none { it.id == id }) throw IOException("Unknown queue identity")
        commit(rows.map { if (it.id == id) transform(it) else it })
    }
    @Synchronized fun enqueue(source: QueuedSource): String {
        if (closed || fault) throw IOException("Download queue unavailable")
        rows.lastOrNull { it.source == source }?.let { return it.id }
        // Never evict unfinished rows or partial storage. Explicit removal is required.
        if (rows.size >= QueueStore.LIMIT) throw IOException("Download queue full; remove completed entries")
        val row = QueueEntry(UUID.randomUUID().toString(), source)
        commit(rows + row); pump(); return row.id
    }
    @Synchronized fun cancel(id: String) {
        val row = rows.singleOrNull { it.id == id } ?: throw IOException("Unknown queue entry")
        when (row.state) {
            QueueStatus.RUNNING -> {
                update(id) { it.copy(state = QueueStatus.CANCELLING) }
                running[id]?.set(true)
            }
            QueueStatus.QUEUED, QueueStatus.RETRY_WAIT, QueueStatus.INTERRUPTED -> {
                discard(row)
                update(id) { it.copy(state = QueueStatus.CANCELLED, retryAt = 0, bytes = 0) }; pump()
            }
            else -> Unit
        }
    }
    @Synchronized fun retry(id: String) {
        val row = rows.singleOrNull { it.id == id } ?: throw IOException("Unknown queue entry")
        require(row.state in setOf(QueueStatus.FAILED, QueueStatus.CANCELLED, QueueStatus.INTERRUPTED))
        require(rows.none { it.id != id && it.source == row.source && (it.active || it.state == QueueStatus.SUCCEEDED) })
        // An explicit retry is a new bounded attempt budget, not a replay of a privileged write.
        update(id) { it.copy(state = QueueStatus.QUEUED, attempt = 0, retryAt = 0, failure = null, bytes = 0, activityId = "") }; pump()
    }
    @Synchronized fun remove(id: String) {
        val row = rows.singleOrNull { it.id == id } ?: return
        require(!row.active)
        discard(row) // only this queue's private partial files; never published assets
        commit(rows.filterNot { it.id == id })
    }
    private fun pump() {
        if (closed || fault) return
        wake?.cancel(false); wake = null
        while (running.size < 2) {
            val row = rows.firstOrNull { it.state == QueueStatus.QUEUED || it.state == QueueStatus.RETRY_WAIT && it.retryAt <= now() } ?: break
            if (row.attempt >= policy.maxAttempts) { update(row.id) { it.copy(state = QueueStatus.FAILED, retryAt = 0) }; continue }
            val next = row.copy(state = QueueStatus.RUNNING, attempt = row.attempt + 1, retryAt = 0, bytes = 0, activityId = "", failure = null)
            update(row.id) { next }
            val signal = AtomicBoolean(false)
            running[row.id] = signal
            pool.execute { execute(next, signal) }
        }
        if (running.size < 2) rows.filter { it.state == QueueStatus.RETRY_WAIT }.minOfOrNull { it.retryAt }?.let { at ->
            wake = pool.schedule({ synchronized(this) { pump() } }, (at - now()).coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        }
    }
    private fun execute(row: QueueEntry, signal: AtomicBoolean) {
        var error: Exception? = null
        try {
            perform(row, { signal.get() }, { id -> synchronized(this) { update(row.id) { it.copy(activityId = id) } } }, { bytes ->
                synchronized(this) {
                    val prior = rows.single { it.id == row.id }
                    require(bytes in 0..row.source.size)
                    if (bytes == row.source.size || bytes * 20 / row.source.size > prior.bytes * 20 / row.source.size)
                        update(row.id) { it.copy(bytes = bytes) }
                }
            })
        } catch (e: Exception) { error = e }
        synchronized(this) {
            try {
                if (closed || fault) return
                val current = rows.single { it.id == row.id }
                if (error == null) {
                    // Cancellation after publication must not turn a verified result into a false cancellation.
                    discard(current); update(row.id) { it.copy(state = QueueStatus.SUCCEEDED, bytes = row.source.size, retryAt = 0) }
                } else if (error is TransferCancelled) {
                    discard(current); update(row.id) { it.copy(state = QueueStatus.CANCELLED, bytes = 0, retryAt = 0) }
                } else {
                    val failure = (error as? TransferRetryable)?.failure ?: TransferFailure.STORAGE_FAILURE
                    val delay = if (signal.get()) null else policy.delayMillis(current.attempt, failure, (error as? TransferRetryable)?.retryAfterMillis)
                    if (delay == null && (!failure.retryable || signal.get())) discard(current)
                    update(row.id) { it.copy(state = if (delay == null) QueueStatus.FAILED else QueueStatus.RETRY_WAIT,
                        failure = failure, retryAt = if (delay == null) 0 else now() + delay) }
                }
            } catch (_: Exception) {
                fault = true; running.values.forEach { it.set(true) }
            } finally { running.remove(row.id); pump() }
        }
    }
    override fun close() {
        synchronized(this) { closed = true; wake?.cancel(false); running.values.forEach { it.set(true) }; pool.shutdown() }
        if (!pool.awaitTermination(30, TimeUnit.SECONDS)) throw IOException("Queue workers still active")
        store.close()
    }
}
