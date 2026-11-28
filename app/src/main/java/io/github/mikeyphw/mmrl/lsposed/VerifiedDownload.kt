package io.github.mikeyphw.mmrl.lsposed

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import io.github.mikeyphw.mmrl.shared.Sha256Digest
import java.nio.file.Files
import org.json.JSONObject

/** Save only SHA-256-pinned first-party releases. Never install APKs automatically. */
internal object VerifiedDownload {
    private const val MAX_BYTES = 104_857_600L
    private val nameRegex = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}\\.(apk|zip)", RegexOption.IGNORE_CASE)
    private val redirectHosts = setOf("github.com", "release-assets.githubusercontent.com",
        "objects.githubusercontent.com")

    fun allowedSourceUrl(url: String): Boolean = runCatching {
        val parsed = URL(url)
        parsed.protocol == "https" && parsed.host == "github.com" && parsed.port == -1 &&
            parsed.path.startsWith("/Mikeyphw/MMRL-LSPosed/releases/download/") &&
            !parsed.path.contains("..") && parsed.userInfo == null && parsed.query == null && parsed.ref == null
    }.getOrDefault(false)

    fun allowedName(name: String): Boolean = nameRegex.matches(name) && !name.contains("..")

    data class Saved(val file: File, val sha256: String, val releaseTag: String?)
    private const val RECEIPT_SCHEMA = "mmrl-lsposed.release-asset-receipt.v2"
    private val storedName = Regex("[a-f0-9]{16}-[A-Za-z0-9][A-Za-z0-9_.-]{0,119}\\.(apk|zip)",
        RegexOption.IGNORE_CASE)

    /** Return the bound release tag, empty string for a valid legacy hash-only
     * receipt, or null for invalid/mixed metadata. No signature is asserted. */
    internal fun receiptTag(info: JSONObject, filename: String, hash: String, size: Long): String? {
        if (!Regex("[a-f0-9]{64}").matches(hash) ||
            !storedName.matches(filename) || !filename.startsWith(hash.take(16) + "-") ||
            info.optString("file") != filename || info.optString("sha256") != hash ||
            size !in 1..MAX_BYTES) return null
        if (!info.has("schema")) {
            // Preserve existing valid S11 downloads, but never upgrade their trust label.
            return if (info.length() == 2) "" else null
        }
        val tag = info.optString("release_tag")
        val name = info.optString("asset_name")
        if (info.keys().asSequence().toSet() != setOf("schema", "repository", "release_tag",
                "asset_name", "asset_url", "size", "file", "sha256") ||
            info.optString("schema") != RECEIPT_SCHEMA ||
            info.optString("repository") != ReleaseIdentityPolicy.REPOSITORY ||
            info.optLong("size", -1L) != size ||
            filename != "${hash.take(16)}-$name" ||
            !ReleaseIdentityPolicy.asset(tag, name, info.optString("asset_url"))) return null
        return tag
    }

    private fun hasZipHeader(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val header = ByteArray(4)
            stream.read(header) == 4 && header.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        }
    }.getOrDefault(false)

    private fun safeSavedFile(root: File, file: File): Boolean = runCatching {
        root.isDirectory && !Files.isSymbolicLink(root.toPath()) && !file.isDirectory && file.isFile &&
            !Files.isSymbolicLink(file.toPath()) &&
            file.canonicalFile.parentFile == root.canonicalFile &&
            file.absoluteFile.parentFile == root.absoluteFile &&
            file.length() in 1..MAX_BYTES && hasZipHeader(file)
    }.getOrDefault(false)

    private fun readReceipt(root: File, file: File, hash: String): String? = runCatching {
        val receipt = File(root, "${file.name}.receipt.json")
        if (!receipt.isFile || Files.isSymbolicLink(receipt.toPath()) ||
            receipt.canonicalFile.parentFile != root.canonicalFile ||
            receipt.length() !in 1..4096L) return@runCatching null
        receiptTag(JSONObject(receipt.readText()), file.name, hash, file.length())
    }.getOrNull()

    /** Rehash at the time of opening; selecting a saved row does not grant trust. */
    fun recheckSaved(context: Context, selected: Saved): Boolean {
        val root = File(context.filesDir, "verified-downloads")
        val file = selected.file
        if (!safeSavedFile(root, file) || !storedName.matches(file.name)) return false
        val tag = readReceipt(root, file, selected.sha256) ?: return false
        return (if (tag.isEmpty()) null else tag) == selected.releaseTag &&
            sha256(file) == selected.sha256
    }

    /** Explicit scan and full rehash before surfacing previously saved files. */
    fun listSaved(context: Context): List<Saved> {
        val root = File(context.filesDir, "verified-downloads")
        return root.listFiles().orEmpty().filter { it.name.endsWith(".receipt.json") }
            .take(100).mapNotNull { receipt -> runCatching {
                if (!receipt.isFile || Files.isSymbolicLink(receipt.toPath()) ||
                    receipt.length() !in 1..4096L ||
                    receipt.canonicalFile.parentFile != root.canonicalFile) return@runCatching null
                val info = JSONObject(receipt.readText())
                val filename = info.optString("file")
                val hash = info.optString("sha256")
                if (!storedName.matches(filename) ||
                    !receipt.name.equals("$filename.receipt.json")) return@runCatching null
                val file = File(root, filename)
                if (!safeSavedFile(root, file)) return@runCatching null
                val tag = receiptTag(info, filename, hash, file.length()) ?: return@runCatching null
                if (sha256(file) != hash) return@runCatching null
                Saved(file, hash, tag.takeIf { it.isNotEmpty() })
            }.getOrNull() }
    }

    fun save(context: Context, asset: GitHubEcosystem.Asset,
             cancelled: () -> Boolean = { false },
             onProgress: (Long, Long) -> Unit = { _, _ -> },
             resumeId: String? = null, onStarted: (String) -> Unit = {}): File {
        val digest = asset.digest?.let(Sha256Digest::parse)
            ?: throw TransferRefused("No published SHA-256; use GitHub instead")
        if (!asset.verifiedDownloadable || !allowedSourceUrl(asset.url) ||
            !ReleaseIdentityPolicy.asset(asset.releaseTag, asset.name, asset.url))
            throw TransferRefused("Unverified or unsupported release asset")
        val transferId = try { ActivityTransferJournal.begin(context, "RELEASE") }
            catch (failure: Exception) { throw publicTransferFailure(failure) }
        try {
            onStarted(transferId)
            TransferPublication.checkCancelled(cancelled)
            if (!GitHubEcosystem.freshReleaseAsset(asset, context))
                throw TransferRefused("Release asset metadata changed; reload GitHub")
            val root = File(context.filesDir, "verified-downloads")
            TransferPublication.prepare(root)
            val expected = digest.hex
            val destination = File(root, "${expected.take(16)}-${asset.name}")
            val receipt = JSONObject()
                .put("schema", RECEIPT_SCHEMA)
                .put("repository", ReleaseIdentityPolicy.REPOSITORY)
                .put("release_tag", asset.releaseTag)
                .put("asset_name", asset.name)
                .put("asset_url", asset.url)
                .put("size", asset.size)
                .put("file", destination.name).put("sha256", expected)
                .toString().toByteArray(Charsets.UTF_8)
            val publish: (File?) -> Unit = { stage ->
                TransferPublication.publish(root, stage, destination, asset.size, digest,
                    ::hasZipHeader, receipt, { bytes ->
                        runCatching { receiptTag(JSONObject(bytes.toString(Charsets.UTF_8)),
                            destination.name, expected, asset.size) == asset.releaseTag }.getOrDefault(false)
                    }, cancelled)
            }
            if (TransferPublication.exists(destination)) {
                publish(null)
            } else {
                val stage = ResumableTransfer.create(root, resumeId,
                    "release:${asset.releaseTag}:${asset.name}:${asset.size}:${digest.hex}", asset.name, asset.size, digest)
                try {
                    var restarts = 0
                    while (true) {
                        try {
                            var url = URL(asset.url)
                            var redirects = 0
                            while (true) {
                                TransferPublication.checkCancelled(cancelled)
                                if (url.protocol != "https" || url.host !in redirectHosts || url.port != -1 ||
                                    url.userInfo != null || url.ref != null)
                                    throw TransferRefused("Download redirected outside trusted HTTPS hosts")
                                val connection = (url.openConnection() as HttpURLConnection).apply {
                                    connectTimeout = 10_000
                                    readTimeout = 15_000
                                    instanceFollowRedirects = false
                                    setRequestProperty("User-Agent", "MMRL-LSPosed/0.1")
                                    setRequestProperty("Accept-Encoding", "identity")
                                }
                                try {
                                    stage.prepareConnection(connection)
                                    val code = connection.responseCode
                                    TransferPublication.checkCancelled(cancelled)
                                    if (code in listOf(301, 302, 303, 307, 308)) {
                                        if (++redirects > 4) throw TransferRefused("Too many release redirects")
                                        val location = connection.getHeaderField("Location")
                                            ?: throw TransferRefused("Missing release redirect location")
                                        url = URL(url, location)
                                        continue
                                    }
                                    if (code !in setOf(200, 206, 416)) throw transferHttpFailure(code, connection.getHeaderField("Retry-After"))
                                    stage.copyResponse(connection.url.host.lowercase(java.util.Locale.ROOT), code, connection.getHeaderField("Content-Range"), connection.contentLengthLong,
                                        connection.contentEncoding, connection.getHeaderField("ETag"), { connection.inputStream }, cancelled) { count, total ->
                                        ActivityTransferJournal.progress(context, transferId, count, total)
                                        onProgress(count, total)
                                    }
                                    break
                                } finally { connection.disconnect() }
                            }
                            break
                        } catch (_: RestartTransfer) {
                            if (++restarts > 1 || !GitHubEcosystem.freshReleaseAsset(asset, context))
                                throw TransferRefused("Release range or identity changed; reload GitHub")
                        }
                    }
                    TransferPublication.checkCancelled(cancelled)
                    if (!GitHubEcosystem.freshReleaseAsset(asset, context))
                        throw TransferRefused("Release asset metadata changed during transfer")
                    publish(stage.file)
                    stage.discard()
                } catch (failure: Exception) {
                    try { stage.retainAfter(failure, resumeId != null) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                    throw failure
                }
            }
            ActivityTransferJournal.finish(context, transferId, "SUCCEEDED")
            return destination
        } catch (failure: Exception) {
            val verdict = if (failure is TransferCancelled) "CANCELLED" else "FAILED"
            try { ActivityTransferJournal.finish(context, transferId, verdict) }
            catch (historyFailure: Exception) { failure.addSuppressed(historyFailure) }
            throw publicTransferFailure(failure)
        }
    }

    private fun sha256(file: File): String = file.inputStream().use {
        Sha256Digest.calculateBounded(it, MAX_BYTES).digest.hex
    }
}
