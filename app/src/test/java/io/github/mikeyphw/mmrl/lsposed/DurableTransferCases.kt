package io.github.mikeyphw.mmrl.lsposed

import io.github.mikeyphw.mmrl.shared.Sha256Digest
import io.github.mikeyphw.mmrl.shared.TransferFailure
import io.github.mikeyphw.mmrl.shared.TransferRetryPolicy
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal object DurableTransferCases {
    private val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val digest = Sha256Digest.calculate(data)
    private fun source(name: String) = QueuedSource("RELEASE", "$name.apk", data.size.toLong(), "sha256:${digest.hex}", tag = "v1")
    private fun root(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("queue-test-").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition()) { check(System.nanoTime() < deadline) { "queue deadline" }; Thread.sleep(10) }
    }
    private fun expectFailure(block: () -> Unit) = check(runCatching(block).isFailure)
    private fun entry(queue: DurableTransferQueue, id: String) = queue.snapshot().single { it.id == id }

    fun concurrencyAndDeduplication() = root { folder ->
        val running = AtomicInteger(); val highest = AtomicInteger(); val starts = AtomicInteger()
        val release = CountDownLatch(1)
        DurableTransferQueue(QueueStore(folder), perform = { row, cancelled, began, progress ->
            starts.incrementAndGet(); val active = running.incrementAndGet(); highest.accumulateAndGet(active, ::maxOf)
            try { began("transfer:${UUID.randomUUID()}"); check(release.await(5, TimeUnit.SECONDS)); TransferPublication.checkCancelled(cancelled); progress(row.source.size) }
            finally { running.decrementAndGet() }
        }, discard = {}).use { queue ->
            val a = queue.enqueue(source("a")); check(queue.enqueue(source("a")) == a)
            val b = queue.enqueue(source("b")); val c = queue.enqueue(source("c")); val d = queue.enqueue(source("d"))
            try {
                await { starts.get() == 2 }; check(entry(queue,c).state == QueueStatus.QUEUED)
                queue.cancel(d); check(entry(queue,d).state == QueueStatus.CANCELLED)
                check(queue.enqueue(source("d")) == d)
            } finally { release.countDown() }
            await { listOf(a,b,c).all { entry(queue,it).state == QueueStatus.SUCCEEDED } }
            check(highest.get() == 2 && starts.get() == 3 && entry(queue,a).activityId.startsWith("transfer:"))
            check(entry(queue,c).bytes == data.size.toLong()); queue.remove(d); check(queue.snapshot().none { it.id == d })
        }
    }
    fun retryAndCancellation() = root { folder ->
        val attempts = AtomicInteger(); val started = CountDownLatch(1); val cancelled = AtomicInteger()
        DurableTransferQueue(QueueStore(folder), perform = { row, signal, began, _ ->
            began("transfer:${UUID.randomUUID()}")
            when (row.source.name) {
                "retry.apk" -> { if (attempts.incrementAndGet() < 3) throw TransferRetryable(TransferFailure.RATE_LIMIT, 2) }
                "cancel.apk" -> { started.countDown(); while (!signal()) Thread.sleep(5); cancelled.incrementAndGet(); throw TransferCancelled() }
                "permanent.apk" -> throw TransferRefused("bad digest")
                "bounded.apk" -> throw TransferRetryable(TransferFailure.TIMEOUT)
            }
        }, discard = {}, policy = TransferRetryPolicy(3, 1, 10)).use { queue ->
            val retry = queue.enqueue(source("retry")); val c = queue.enqueue(source("cancel"))
            check(started.await(3, TimeUnit.SECONDS)); queue.cancel(c)
            val permanent = queue.enqueue(source("permanent")); val bounded = queue.enqueue(source("bounded"))
            await { entry(queue,retry).state == QueueStatus.SUCCEEDED && entry(queue,c).state == QueueStatus.CANCELLED &&
                entry(queue,permanent).state == QueueStatus.FAILED && entry(queue,bounded).state == QueueStatus.FAILED }
            check(entry(queue,retry).attempt == 3 && attempts.get() == 3 && cancelled.get() == 1)
            check(entry(queue,permanent).attempt == 1 && entry(queue,bounded).attempt == 3)
            queue.retry(permanent); await { entry(queue,permanent).state == QueueStatus.FAILED }; check(entry(queue,permanent).attempt == 1)
        }
    }
    fun recoveryAndLock() = root { folder ->
        val id = UUID.randomUUID().toString(); val activity = "transfer:${UUID.randomUUID()}"
        QueueStore(folder).use { store ->
            store.write(listOf(QueueEntry(id,source("recover"),QueueStatus.RUNNING,1,3,activityId=activity)))
            expectFailure { QueueStore(folder).close() }
        }
        val starts = AtomicInteger()
        DurableTransferQueue(QueueStore(folder), perform = { _,_,began,_ -> starts.incrementAndGet(); began("transfer:${UUID.randomUUID()}") }, discard = {}).use { queue ->
            check(entry(queue,id).state == QueueStatus.INTERRUPTED && entry(queue,id).activityId == activity)
            Thread.sleep(30); check(starts.get() == 0)
            queue.retry(id); await { entry(queue,id).state == QueueStatus.SUCCEEDED }; check(starts.get() == 1)
            check(entry(queue,id).activityId != activity)
        }
        QueueStore(folder).use { store -> check(store.read().single().state == QueueStatus.SUCCEEDED) }
    }
    fun persistenceFailureAndCorruption() = root { folder ->
        QueueStore(folder).use { store -> store.write(emptyList()) }
        File(folder,"queue-v1.dat").writeText("broken")
        QueueStore(folder).use { store -> expectFailure { store.read() } }
        File(folder,"queue-v1.dat").delete()
        val starts = AtomicInteger()
        DurableTransferQueue(QueueStore(folder), perform = { _,_,_,_ -> starts.incrementAndGet() }, discard = {}).use { queue ->
            val unrelated = File(folder,"foreign").apply { writeText("untouched") }
            File(folder,"queue-v1.dat").delete(); Files.createSymbolicLink(File(folder,"queue-v1.dat").toPath(),unrelated.toPath())
            expectFailure { queue.enqueue(source("bad")) }; check(starts.get() == 0 && unrelated.readText() == "untouched")
            expectFailure { queue.snapshot() }
        }
    }
    private fun stage(root: File, id: String = UUID.randomUUID().toString(), identity: String = "source") =
        ResumableTransfer.create(root,id,identity,"a.apk",data.size.toLong(),digest)
    private fun partial(stage: ResumableTransfer, etag: String = "\"strong\"") {
        var first = true
        val input = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!first) throw SocketTimeoutException("fixture secret")
                first = false; data.copyInto(b, off, 0, 3); return 3
            }
        }
        val error = runCatching { stage.copyResponse("release-assets.githubusercontent.com",200,null,8,null,etag,{input},{false}) { _,_ -> } }.exceptionOrNull()
        check(error is SocketTimeoutException); stage.retainAfter(error,true)
    }
    fun exactResumeAndDigest() = root { folder ->
        val id = UUID.randomUUID().toString(); val first = stage(folder,id); partial(first)
        check(first.file.length() == 3L)
        val next = stage(folder,id)
        var seen = 0L
        next.copyResponse("release-assets.githubusercontent.com",206,"bytes 3-7/8",5,"identity","\"strong\"",{ByteArrayInputStream(data.copyOfRange(3,8))},{false}) { n,_ -> seen = n }
        check(seen == 8L && next.file.readBytes().contentEquals(data))
        check(TransferPublication.verify(folder,next.file,8,digest){true})
        next.file.writeBytes(byteArrayOf(0)+data.copyOfRange(1,8))
        check(!TransferPublication.verify(folder,next.file,8,digest){true}); next.discard(); check(!next.file.exists())
    }
    fun wrongRangesAndRestart() = root { folder ->
        for ((range,etag,length) in listOf(Triple("bytes 2-7/8","\"strong\"",6L),Triple("bytes 3-7/9","\"strong\"",5L),Triple("bytes 3-7/8","\"changed\"",5L),Triple("bytes 3-7/8","W/\"strong\"",5L))) {
            val staged = stage(folder); partial(staged)
            val error = runCatching { staged.copyResponse("release-assets.githubusercontent.com",206,range,length,null,etag,{error("bad range consumed")},{false}) { _,_ -> } }.exceptionOrNull()
            check(error is RestartTransfer && !staged.file.exists())
        }
        val foreign = stage(folder); partial(foreign)
        check(runCatching { foreign.copyResponse("objects.githubusercontent.com",206,"bytes 3-7/8",5,null,"\"strong\"",{error("foreign host body consumed")},{false}) { _,_ -> } }.exceptionOrNull() is RestartTransfer)
        check(!foreign.file.exists())
        val s = stage(folder); partial(s); s.copyResponse("release-assets.githubusercontent.com",200,null,8,null,"\"new\"",{ByteArrayInputStream(data)},{false}) { _,_ -> }
        check(s.file.readBytes().contentEquals(data)); s.discard()
        val e = stage(folder); partial(e); check(runCatching { e.copyResponse("release-assets.githubusercontent.com",416,null,-1,null,null,{error("no body")},{false}) { _,_ -> } }.exceptionOrNull() is RestartTransfer)
    }
    fun weakIdentityAndExplicitCancellation() = root { folder ->
        val weak = stage(folder); partial(weak,"W/\"weak\""); check(!weak.file.exists())
        val staged = stage(folder); partial(staged)
        val err = runCatching { staged.copyResponse("release-assets.githubusercontent.com",206,"bytes 3-7/8",5,null,"\"strong\"",{ByteArrayInputStream(data.copyOfRange(3,8))},{true}) { _,_ -> } }.exceptionOrNull()
        check(err is TransferCancelled); staged.retainAfter(err,true); check(!staged.file.exists())
        val id = UUID.randomUUID().toString(); partial(stage(folder,id,"original"))
        val other = stage(folder,id,"changed")
        expectFailure { other.copyResponse("release-assets.githubusercontent.com",206,"bytes 3-7/8",5,null,"\"strong\"",{error("foreign data consumed")},{false}) { _,_ -> } }
        check(transferHttpFailure(429,"999") is TransferRetryable)
        check((transferHttpFailure(429,"invalid") as TransferRetryable).retryAfterMillis == Long.MAX_VALUE)
        check(transferHttpFailure(403,null) is TransferRefused)
    }
}

fun main() {
    listOf(DurableTransferCases::concurrencyAndDeduplication, DurableTransferCases::retryAndCancellation,
        DurableTransferCases::recoveryAndLock, DurableTransferCases::persistenceFailureAndCorruption,
        DurableTransferCases::exactResumeAndDigest, DurableTransferCases::wrongRangesAndRestart,
        DurableTransferCases::weakIdentityAndExplicitCancellation).forEach { test -> test(); println("DURABLE_TRANSFER_PASS ${test.name}") }
}
