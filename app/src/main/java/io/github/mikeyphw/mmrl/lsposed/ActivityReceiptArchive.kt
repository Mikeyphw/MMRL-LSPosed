package io.github.mikeyphw.mmrl.lsposed

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Durable, append-only read-only Activity evidence. No Vector authority or raw user data.
 * A full/damaged archive fails closed rather than silently dropping historical receipts.
 */
internal data class ArchivedActivityReceipt(
    val id: String,
    val source: String,
    val kind: String,
    val status: String,
    val started: Long,
    val updated: Long,
    val percent: Int,
    val failure: String = "NONE",
) {
    fun validate() {
        require(ID.matches(id)) { "Invalid receipt ID" }
        require(SOURCE.matches(source)) { "Invalid receipt source" }
        require(kind in setOf("DOWNLOAD", "ENABLE", "DISABLE", "SCOPE_CHANGE")) { "Invalid kind" }
        require(status in setOf("SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED")) { "Nonterminal receipt" }
        require(started > 0 && updated >= started && percent in 0..100) { "Invalid receipt counters" }
        require(failure in setOf("NONE", "TRANSFER_FAILED")) { "Unapproved failure code" }
        require((status == "FAILED") == (failure == "TRANSFER_FAILED")) { "Failure mismatch" }
        require(status != "SUCCEEDED" || percent == 100) { "Success without complete progress" }
    }

    companion object {
        private val ID = Regex("(?:transfer|vector):[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val SOURCE = Regex("(?:VECTOR|RELEASE|CI:[1-9][0-9]*:[1-9][0-9]*:[1-9][0-9]*)")
    }
}

internal object ActivityReceiptArchive {
    private const val FILE = "activity_receipts_archive_v1.log"
    private const val MAX_SIZE = 16L * 1024 * 1024
    private const val MAX_LINE = 512
    private val utf8 = StandardCharsets.UTF_8

    private fun file(directory: File): File = File(directory, FILE)

    private fun checksum(payload: String): String = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(utf8)).joinToString("") { "%02x".format(it) }

    private fun encode(receipt: ArchivedActivityReceipt): String {
        receipt.validate()
        return listOf("v1", receipt.id, receipt.source, receipt.kind, receipt.status,
            receipt.started, receipt.updated, receipt.percent, receipt.failure).joinToString("\t")
    }

    private fun decode(raw: String): ArchivedActivityReceipt {
        val fields = raw.split('\t')
        if (fields.size != 10 || fields[0] != "v1" ||
            fields.last() != checksum(fields.dropLast(1).joinToString("\t")))
            throw IOException("Invalid archived receipt checksum or format")
        val receipt = try {
            ArchivedActivityReceipt(fields[1], fields[2], fields[3], fields[4],
                fields[5].toLong(), fields[6].toLong(), fields[7].toInt(), fields[8])
        } catch (_: NumberFormatException) {
            throw IOException("Invalid archived receipt counters")
        }
        try { receipt.validate() } catch (_: IllegalArgumentException) {
            throw IOException("Invalid archived receipt fields")
        }
        return receipt
    }

    private fun load(path: File): LinkedHashMap<String, ArchivedActivityReceipt> {
        if (Files.isSymbolicLink(path.toPath())) throw IOException("Archive link refused")
        if (!path.exists()) return linkedMapOf()
        if (!path.isFile || path.length() > MAX_SIZE) throw IOException("Archive size or type invalid")
        val raw = path.readBytes()
        if (raw.isEmpty()) return linkedMapOf()
        if (raw.last() != '\n'.code.toByte()) throw IOException("Interrupted archived receipt write")
        val content = try {
            utf8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw)).toString()
        } catch (_: Exception) { throw IOException("Archive text encoding invalid") }
        val entries = linkedMapOf<String, ArchivedActivityReceipt>()
        for (line in content.dropLast(1).split('\n')) {
            if (line.length > MAX_LINE) throw IOException("Archive record too long")
            val receipt = decode(line)
            val previous = entries.putIfAbsent(receipt.id, receipt)
            if (previous != null && previous != receipt)
                throw IOException("Conflicting archived operation identity")
        }
        return entries
    }

    /** Safe to call multiple times for the same terminal receipt; never silently evicts. */
    @Synchronized fun append(directory: File, receipt: ArchivedActivityReceipt) {
        receipt.validate()
        val target = file(directory)
        val existing = load(target)
        existing[receipt.id]?.let { old ->
            if (old != receipt) throw IOException("Conflicting archived operation identity")
            return
        }
        val line = encode(receipt) + "\t" + checksum(encode(receipt)) + "\n"
        val bytes = line.toByteArray(utf8)
        if (bytes.size > MAX_LINE || target.length() + bytes.size > MAX_SIZE)
            throw IOException("Activity history archive at capacity")
        if (!directory.isDirectory) throw IOException("No app-private history directory")
        FileOutputStream(target, true).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    @Synchronized fun read(directory: File): List<ArchivedActivityReceipt> =
        load(file(directory)).values.toList()
}
