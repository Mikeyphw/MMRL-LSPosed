package io.github.mikeyphw.mmrl.lsposed

import io.github.mikeyphw.mmrl.shared.Sha256Digest
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Only explicit user cancellation has this type; SocketTimeoutException is a failure. */
internal class TransferCancelled : IOException("Download cancelled; no incomplete file was published")
internal class TransferRefused(message: String) : IOException(message)
internal class TransferRetryable(val failure: io.github.mikeyphw.mmrl.shared.TransferFailure,
                                 val retryAfterMillis: Long? = null) : IOException("Transient transfer failure: ${failure.name}") {
    init { require(failure.retryable) }
}
internal fun transferHttpFailure(status: Int, retryAfter: String?): IOException {
    val failure = io.github.mikeyphw.mmrl.shared.TransferFailurePolicy.http(status)
    val delay = retryAfter?.let { raw ->
        raw.toLongOrNull()?.let { if (it in 0..60) it * 1000 else Long.MAX_VALUE }
            ?: runCatching {
                val target = java.time.ZonedDateTime.parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                (target - System.currentTimeMillis()).coerceAtLeast(0L)
            }.getOrDefault(Long.MAX_VALUE)
    }
    return if (failure?.retryable == true) TransferRetryable(failure, delay) else TransferRefused("Download HTTP $status")
}

/** The UI must never receive exception messages from a socket, signed URL or private path. */
internal fun publicTransferFailure(error: Exception): IOException = when (error) {
    is TransferCancelled, is TransferRefused, is TransferRetryable -> error as IOException
    is java.net.SocketTimeoutException -> TransferRetryable(io.github.mikeyphw.mmrl.shared.TransferFailure.TIMEOUT)
    is java.net.UnknownHostException, is java.net.ConnectException -> TransferRetryable(io.github.mikeyphw.mmrl.shared.TransferFailure.OFFLINE)
    is java.net.SocketException -> TransferRetryable(io.github.mikeyphw.mmrl.shared.TransferFailure.HTTP_TRANSIENT)
    else -> TransferRefused("Download or storage operation failed; inspect saved downloads and retry")
}

/** Shared by both first-party routes. All bytes remain in app-private storage.
 * A successful receipt is the visibility boundary, not the existence of the binary.
 * Hard-link creation is atomic and fails if the destination already exists. There is
 * deliberately no rename/copy fallback that might replace a concurrent writer's file.
 */
internal object TransferPublication {
    fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw TransferCancelled()
    }

    fun prepare(root: File) {
        if (Files.isSymbolicLink(root.toPath())) throw TransferRefused("Unsafe download directory")
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory)
            throw TransferRefused("Cannot create download directory")
        if (Files.isSymbolicLink(root.toPath()))
            throw TransferRefused("Unsafe download directory")
    }

    fun exists(file: File): Boolean = Files.exists(file.toPath(), NOFOLLOW_LINKS)

    fun safeRegular(root: File, file: File): Boolean =
        root.isDirectory && !Files.isSymbolicLink(root.toPath()) &&
            file.absoluteFile.parentFile == root.absoluteFile &&
            Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) &&
            file.canonicalFile.parentFile == root.canonicalFile

    fun copyExact(input: InputStream, output: OutputStream, expected: Long,
                  declared: Long, encoding: String?, cancelled: () -> Boolean,
                  progress: (Long, Long) -> Unit) {
        if (expected <= 0 || declared < -1 || (declared >= 0 && declared != expected) ||
            (!encoding.isNullOrBlank() && !encoding.equals("identity", ignoreCase = true)))
            throw TransferRefused("Unexpected download length or encoding")
        val buffer = ByteArray(16_384)
        var total = 0L
        while (true) {
            checkCancelled(cancelled)
            val n = input.read(buffer)
            checkCancelled(cancelled)
            if (n == -1) break
            if (n !in 1..buffer.size || n.toLong() > expected - total)
                throw TransferRefused("Download exceeded its verified length")
            output.write(buffer, 0, n)
            total += n
            progress(total, expected)
        }
        if (total != expected) throw TransferRefused("Download length mismatch")
    }

    fun verify(root: File, file: File, expected: Long, digest: Sha256Digest,
               format: (File) -> Boolean): Boolean {
        if (!safeRegular(root, file) || expected <= 0 || file.length() != expected) return false
        val observed = file.inputStream().use { Sha256Digest.calculateBounded(it, expected) }
        return observed.bytes == expected && observed.digest == digest && format(file)
    }

    private fun syncDirectory(root: File) {
        FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private fun linkIfAbsent(stage: File, destination: File) {
        try {
            Files.createLink(destination.toPath(), stage.toPath())
        } catch (_: FileAlreadyExistsException) {
            // A concurrent publisher won. The caller must independently read back its bytes.
        } catch (_: UnsupportedOperationException) {
            throw TransferRefused("Storage does not support safe non-replacing publication")
        }
    }

    /** No cancellation after the publication commit point. If receipt publication fails,
     * retain the complete unreceipted file, invisible to verified listings. Explicit retry
     * may recover it after fresh metadata, full size/digest/format checks and receipt binding.
     * Never delete a shared destination: the losing attempt may not own it.
     */
    @Synchronized fun publish(root: File, stage: File?, destination: File, expected: Long,
                              digest: Sha256Digest, format: (File) -> Boolean,
                              receiptBytes: ByteArray, receiptMatches: (ByteArray) -> Boolean,
                              cancelled: () -> Boolean) {
        prepare(root)
        if (destination.absoluteFile.parentFile != root.absoluteFile ||
            receiptBytes.size !in 1..4096 || !receiptMatches(receiptBytes))
            throw TransferRefused("Invalid publication identity")
        val receipt = File(root, "${destination.name}.receipt.json")
        fun receiptValid(): Boolean = safeRegular(root, receipt) && receipt.length() in 1..4096L &&
            receipt.inputStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (output.size() <= 4096) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 4097 - output.size()))
                    if (count == -1) break
                    if (count <= 0) return@use false
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                bytes.size in 1..4096 && receiptMatches(bytes)
            }
        if (exists(receipt) && (!exists(destination) || !receiptValid()))
            throw TransferRefused("Existing receipt conflicts; refusing replacement")
        checkCancelled(cancelled)
        val destinationAlreadyPresent = exists(destination)
        if (!destinationAlreadyPresent) {
            if (stage == null || !verify(root, stage, expected, digest, format))
                throw TransferRefused("Download SHA-256, length or format mismatch")
            checkCancelled(cancelled)
            linkIfAbsent(stage, destination)
        }
        if (!verify(root, destination, expected, digest, format))
            throw TransferRefused("Existing download differs; refusing replacement")
        if (destinationAlreadyPresent) checkCancelled(cancelled)
        syncDirectory(root)
        if (!exists(receipt)) {
            // Unique stage; the directory is private, and publication never replaces a file.
            val temporary = File.createTempFile(".receipt-", ".pending", root)
            try {
                FileOutputStream(temporary).use { out -> out.write(receiptBytes); out.fd.sync() }
                linkIfAbsent(temporary, receipt)
                syncDirectory(root)
            } finally { temporary.delete() }
        }
        if (!receiptValid() || !verify(root, destination, expected, digest, format))
            throw TransferRefused("Published file or receipt failed read-back")
        syncDirectory(root)
    }
}
