package io.github.mikeyphw.mmrl.lsposed

import io.github.mikeyphw.mmrl.shared.OperationId
import io.github.mikeyphw.mmrl.shared.RepresentationIdentity
import io.github.mikeyphw.mmrl.shared.ResumeDecision
import io.github.mikeyphw.mmrl.shared.ResumeResponse
import io.github.mikeyphw.mmrl.shared.Sha256Digest
import io.github.mikeyphw.mmrl.shared.TransferFormat
import io.github.mikeyphw.mmrl.shared.TransferRequest
import io.github.mikeyphw.mmrl.shared.TransferResumePolicy
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

internal class RestartTransfer : IOException("Range response changed; restarting from zero")

/** Private, source-bound partial bytes. Strong ETag is stored only here, never in Activity or a receipt.
 * A full-body digest and the normal no-replace publication gate are still mandatory after resumption.
 */
internal class ResumableTransfer(private val root: File, private val request: TransferRequest, private val id: String) {
    val file = File(root, ".resume-$id.pending")
    private val metadata = File(root, ".resume-$id.etag")
    private val expected = requireNotNull(request.expectedSize)
    init { require(validId(id)); TransferPublication.prepare(root); validateOwned(file); validateOwned(metadata) }
    private fun validateOwned(path: File) {
        if (TransferPublication.exists(path) && !TransferPublication.safeRegular(root, path))
            throw TransferRefused("Unsafe partial download storage")
    }
    private data class SavedIdentity(val host: String, val etag: String)
    private fun etag(): SavedIdentity? {
        validateOwned(metadata)
        if (!metadata.isFile) return null
        if (metadata.length() !in 1..2048) throw TransferRefused("Invalid partial transfer metadata")
        return DataInputStream(metadata.inputStream()).use { input ->
            val source = input.readUTF(); val size = input.readLong(); val host = input.readUTF(); val value = input.readUTF()
            if (input.read() != -1 || source != request.sourceIdentity.value || size != expected || !validHost(host) || representation(value) == null)
                throw TransferRefused("Partial transfer source identity mismatch")
            SavedIdentity(host, value)
        }
    }
    private fun representation(value: String?) = RepresentationIdentity.fromStrongEtag(request.sourceIdentity, value, expected)
    private fun offset(): Long {
        validateOwned(file)
        return if (file.isFile) file.length() else 0
    }
    fun prepareConnection(connection: HttpURLConnection) {
        val size = offset()
        val value = etag()
        if (size > 0 && (size >= expected || value == null)) discard()
        else if (size > 0 && value?.host == connection.url.host.lowercase(java.util.Locale.ROOT)) {
            connection.setRequestProperty("Range", "bytes=$size-")
            connection.setRequestProperty("If-Range", value.etag)
        }
    }
    private fun validHost(host: String) = host.length in 1..253 && Regex("[a-z0-9.-]+").matches(host)
    private fun remember(host: String, value: String?) {
        require(validHost(host))
        validateOwned(metadata)
        if (representation(value) == null) {
            if (metadata.exists() && !metadata.delete()) throw TransferRefused("Cannot clear partial identity")
            return
        }
        val temp = File.createTempFile(".etag-", ".pending", root)
        try {
            FileOutputStream(temp).use { raw ->
                val out = DataOutputStream(raw)
                out.writeUTF(request.sourceIdentity.value); out.writeLong(expected); out.writeUTF(host); out.writeUTF(requireNotNull(value))
                out.flush(); raw.fd.sync()
            }
            Files.move(temp.toPath(), metadata.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } finally { temp.delete() }
    }
    fun copyResponse(host: String, status: Int, contentRange: String?, contentLength: Long, encoding: String?,
                     receivedEtag: String?, input: () -> InputStream, cancelled: () -> Boolean,
                     progress: (Long, Long) -> Unit) {
        require(validHost(host))
        TransferPublication.checkCancelled(cancelled)
        val previous = etag()
        val staged = offset()
        if (status == 416 && staged > 0) { discard(); throw RestartTransfer() }
        if (status !in setOf(200, 206)) throw transferHttpFailure(status, null)
        val identityEncoding = encoding.isNullOrBlank() || encoding.equals("identity", ignoreCase = true)
        val start: Long
        if (status == 206) {
            val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(contentRange ?: "")
            val total = range?.groupValues?.get(3)?.toLongOrNull()
            val response = ResumeResponse(status, representation(receivedEtag), range?.groupValues?.get(1)?.toLongOrNull(),
                range?.groupValues?.get(2)?.toLongOrNull(), contentLength.takeIf { it >= 0 }, identityEncoding)
            if (previous?.host != host || total != expected || TransferResumePolicy.decide(request, representation(previous.etag), staged, response) != ResumeDecision.APPEND) {
                discard(); throw RestartTransfer()
            }
            start = staged
        } else {
            // A server may ignore Range (200); truncate and bind the new representation, never append it.
            if (contentRange != null || !identityEncoding || contentLength >= 0 && contentLength != expected)
                throw TransferRefused("Unexpected full transfer length or encoding")
            discard()
            start = 0
        }
        if (root.usableSpace < expected - start) throw TransferRefused("Not enough storage for download")
        // Publish the representation before appending. A crash before body writes leaves a zero-length partial.
        remember(host, receivedEtag)
        validateOwned(file)
        FileOutputStream(file, start > 0).use { output ->
            try {
                input().use { source ->
                    TransferPublication.copyExact(source, output, expected - start, contentLength, encoding, cancelled) { count, _ ->
                        progress(start + count, expected)
                    }
                }
            } finally { output.fd.sync() }
        }
    }
    fun discard() { discard(root, id) }
    fun retainAfter(error: Exception, durable: Boolean) {
        if (!durable || publicTransferFailure(error) !is TransferRetryable || etag() == null || offset() !in 1 until expected)
            discard()
    }
    companion object {
        private fun validId(id: String) = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").matches(id)
        fun discard(root: File, id: String) {
            require(validId(id))
            if (!root.exists()) return
            TransferPublication.prepare(root)
            for (suffix in listOf("pending", "etag")) {
                val item = File(root, ".resume-$id.$suffix")
                if (TransferPublication.exists(item)) {
                    if (!TransferPublication.safeRegular(root, item) || !item.delete()) throw TransferRefused("Cannot safely clear owned partial")
                }
            }
        }
        fun create(root: File, resumeId: String?, publicIdentity: String, filename: String, size: Long, digest: Sha256Digest): ResumableTransfer {
            val id = resumeId ?: UUID.randomUUID().toString()
            val request = TransferRequest(requireNotNull(OperationId.parse("transfer:$id")),
                requireNotNull(OperationId.parse("source:${Sha256Digest.calculate(publicIdentity.encodeToByteArray()).hex}")),
                requireNotNull(OperationId.parse("private-download")), filename,
                if (filename.endsWith(".apk", true)) TransferFormat.XPOSED_APK else TransferFormat.ACTIONS_ARCHIVE,
                size, digest, size)
            return ResumableTransfer(root, request, id)
        }
    }
}
