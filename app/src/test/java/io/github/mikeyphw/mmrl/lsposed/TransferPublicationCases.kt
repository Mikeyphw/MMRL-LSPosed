package io.github.mikeyphw.mmrl.lsposed

import io.github.mikeyphw.mmrl.shared.Sha256Digest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Executed by hosted JUnit and by the standalone Kotlin compiler without Android stubs. */
internal object TransferPublicationCases {
    private val payload = byteArrayOf(80, 75, 3, 4, 5, 6)
    private val receipt = "exact receipt".encodeToByteArray()
    private val digest = Sha256Digest.calculate(payload)
    private fun expectFailure(block: () -> Unit): Exception {
        try { block() } catch (failure: Exception) { return failure }
        error("Expected refusal")
    }
    private fun temp(block: (File) -> Unit) {
        val root = Files.createTempDirectory("publication-test-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun stage(root: File, bytes: ByteArray = payload) =
        File.createTempFile(".part-", ".pending", root).apply { writeBytes(bytes) }
    private fun publish(root: File, input: File?, dest: File, bytes: ByteArray = payload,
                        format: (File) -> Boolean = { true }, cancelled: () -> Boolean = { false }) =
        TransferPublication.publish(root, input, dest, bytes.size.toLong(), Sha256Digest.calculate(bytes),
            format, receipt, { it.contentEquals(receipt) }, cancelled)
    fun boundedCopy() {
        val out = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        TransferPublication.copyExact(ByteArrayInputStream(payload), out, payload.size.toLong(), -1,
            null, { false }) { count, total -> check(total == payload.size.toLong()); progress += count }
        check(out.toByteArray().contentEquals(payload) && progress.last() == payload.size.toLong())
        for ((data, expected, declared) in listOf(Triple(payload, 7L, -1L), Triple(payload, 5L, -1L),
            Triple(payload, 6L, 7L), Triple(byteArrayOf(), 6L, -1L))) {
            check(expectFailure { TransferPublication.copyExact(ByteArrayInputStream(data), ByteArrayOutputStream(),
                expected, declared, null, { false }) { _, _ -> } } is TransferRefused)
        }
        check(expectFailure { TransferPublication.copyExact(ByteArrayInputStream(payload), ByteArrayOutputStream(),
            6, 6, "gzip", { false }) { _, _ -> } } is TransferRefused)
        val zero = object : InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int) = 0
        }
        check(expectFailure { TransferPublication.copyExact(zero, ByteArrayOutputStream(),
            6, -1, null, { false }) { _, _ -> } } is TransferRefused)
    }
    fun explicitCancelAndTimeout() {
        val out = ByteArrayOutputStream()
        var cancelled = false
        val stream = object : InputStream() {
            override fun read() = error("Unexpected single read")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b[off] = 1; cancelled = true; return 1
            }
        }
        check(expectFailure { TransferPublication.copyExact(stream, out, 1, 1, null, { cancelled }) { _, _ -> } }
            is TransferCancelled)
        check(out.size() == 0)
        val timeout = publicTransferFailure(SocketTimeoutException("signed?secret=fixture"))
        check(timeout is TransferRefused && "secret" !in timeout.message.orEmpty())
        check(publicTransferFailure(TransferCancelled()) is TransferCancelled)
        check("private" !in publicTransferFailure(IOException("private/path")).message.orEmpty())
    }
    fun publicationAndReuse() = temp { root ->
        val dest = File(root, "module.apk")
        val input = stage(root)
        publish(root, input, dest)
        check(dest.readBytes().contentEquals(payload))
        check(File(root, "module.apk.receipt.json").readBytes().contentEquals(receipt))
        input.delete()
        publish(root, null, dest)
        check(TransferPublication.verify(root, dest, payload.size.toLong(), digest) { true })
        check(root.listFiles()!!.none { it.name.endsWith(".pending") })
    }
    fun mismatchesNeverReplace() = temp { root ->
        val dest = File(root, "module.apk")
        val input = stage(root)
        val alien = byteArrayOf(9, 8, 7, 6, 5, 4)
        dest.writeBytes(alien)
        expectFailure { publish(root, input, dest) }
        check(dest.readBytes().contentEquals(alien))
        check(!File(root, "module.apk.receipt.json").exists())
        dest.delete()
        val bad = stage(root, alien)
        expectFailure { publish(root, bad, dest) }
        check(!dest.exists())
        expectFailure { publish(root, input, dest, format = { false }) }
        check(!dest.exists())
        expectFailure { publish(root, input, dest, cancelled = { true }) }
        check(!dest.exists())
    }
    fun receiptCollisionAndOrphanRecovery() = temp { root ->
        val dest = File(root, "module.apk")
        val input = stage(root)
        val receiptFile = File(root, "module.apk.receipt.json")
        // Simulate a receipt collision after the binary was atomically linked.
        expectFailure { publish(root, input, dest, format = { file ->
            if (file == dest && !receiptFile.exists()) receiptFile.writeText("unrelated")
            true
        }) }
        check(dest.readBytes().contentEquals(payload))
        check(receiptFile.readText() == "unrelated")
        expectFailure { publish(root, input, dest) }
        check(receiptFile.readText() == "unrelated")
        receiptFile.delete() // Test fixture only: clear the injected conflict.
        publish(root, null, dest)
        check(receiptFile.readBytes().contentEquals(receipt))
        check(dest.readBytes().contentEquals(payload))
    }
    fun symlinkAndForeignStage() = temp { root ->
        val actual = File(root, "other").apply { writeBytes(payload) }
        val dest = File(root, "module.apk")
        Files.createSymbolicLink(dest.toPath(), actual.toPath())
        expectFailure { publish(root, stage(root), dest) }
        check(Files.isSymbolicLink(dest.toPath()) && actual.readBytes().contentEquals(payload))
        Files.delete(dest.toPath())
        val staged = File(root, ".part-link")
        Files.createSymbolicLink(staged.toPath(), actual.toPath())
        expectFailure { publish(root, staged, dest) }
        check(!dest.exists())
        val foreign = File.createTempFile("foreign", ".pending")
        try { foreign.writeBytes(payload); expectFailure { publish(root, foreign, dest) } }
        finally { foreign.delete() }
        check(!dest.exists())
    }
    fun concurrentDifferentWriters() = temp { root ->
        val dest = File(root, "module.apk")
        val other = byteArrayOf(9, 8, 7, 6, 5, 4)
        val first = stage(root)
        val second = stage(root, other)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<Boolean> { start.await(); runCatching { publish(root, first, dest) }.isSuccess }
            val b = pool.submit<Boolean> { start.await(); runCatching { publish(root, second, dest, other) }.isSuccess }
            start.countDown()
            val winnerA = a.get(5, TimeUnit.SECONDS)
            val winnerB = b.get(5, TimeUnit.SECONDS)
            check(winnerA != winnerB)
            check(dest.readBytes().contentEquals(if (winnerA) payload else other))
        } finally { pool.shutdownNow() }
    }
}

fun main() {
    listOf(TransferPublicationCases::boundedCopy, TransferPublicationCases::explicitCancelAndTimeout,
        TransferPublicationCases::publicationAndReuse, TransferPublicationCases::mismatchesNeverReplace,
        TransferPublicationCases::receiptCollisionAndOrphanRecovery, TransferPublicationCases::symlinkAndForeignStage,
        TransferPublicationCases::concurrentDifferentWriters).forEach { test ->
        test(); println("TRANSFER_PUBLICATION_PASS ${test.name}")
    }
}
