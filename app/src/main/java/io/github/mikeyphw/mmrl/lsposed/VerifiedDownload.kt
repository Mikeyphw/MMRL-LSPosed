package io.github.mikeyphw.mmrl.lsposed

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.nio.file.Files
import org.json.JSONObject

/** Save only SHA-256-pinned first-party releases. Never install APKs automatically. */
internal object VerifiedDownload {
    private const val MAX_BYTES = 104_857_600L
    private val digestRegex = Regex("sha256:[0-9a-f]{64}")
    private val nameRegex = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}\\.(apk|zip)", RegexOption.IGNORE_CASE)
    private val redirectHosts = setOf("github.com", "release-assets.githubusercontent.com",
        "objects.githubusercontent.com")

    fun allowedSourceUrl(url: String): Boolean = runCatching {
        val parsed = URL(url)
        parsed.protocol == "https" && parsed.host == "github.com" && parsed.port == -1 &&
            parsed.path.startsWith("/Mikeyphw/MMRL-LSPosed/releases/download/") &&
            !parsed.path.contains("..") && parsed.userInfo == null
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
        if (info.optString("schema") != RECEIPT_SCHEMA ||
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
        root.isDirectory && !file.isDirectory && file.isFile &&
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
             onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        val digest = asset.digest ?: throw IOException("No published SHA-256; use GitHub instead")
        if (!digestRegex.matches(digest) || asset.size !in 1..MAX_BYTES ||
            !asset.verifiedDownloadable || !allowedSourceUrl(asset.url) ||
            !ReleaseIdentityPolicy.asset(asset.releaseTag, asset.name, asset.url))
            throw IOException("Unverified or unsupported release asset")
        val root = File(context.filesDir, "verified-downloads")
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create verified downloads directory")
        val expected = digest.removePrefix("sha256:")
        val destination = File(root, "${expected.take(16)}-${asset.name}")
        if (destination.exists()) {
            if (safeSavedFile(root, destination) && destination.length() == asset.size &&
                sha256(destination) == expected) {
                writeReceipt(destination, expected, asset)
                return destination
            }
            throw IOException("Existing download differs; refusing replacement")
        }
        val temp = File.createTempFile(".download-", ".pending", root)
        try {
            var url = URL(asset.url)
            var redirects = 0
            while (true) {
                if (url.protocol != "https" || url.host !in redirectHosts || url.port != -1 ||
                    url.userInfo != null) throw IOException("Download redirected outside trusted HTTPS hosts")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "MMRL-LSPosed/0.1")
                }
                try {
                    val code = connection.responseCode
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        if (++redirects > 4) throw IOException("Too many release redirects")
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("Missing release redirect location")
                        url = URL(url, location)
                        continue
                    }
                    if (code != 200) throw IOException("Release download HTTP $code")
                    val declared = connection.contentLengthLong
                    if (declared > MAX_BYTES || (declared >= 0 && declared != asset.size))
                        throw IOException("Unexpected release file size")
                    val sha = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    FileOutputStream(temp).use { file ->
                        connection.inputStream.use { input ->
                            val buf = ByteArray(16_384)
                            while (true) {
                                if (cancelled()) throw InterruptedIOException("Download cancelled; incomplete file discarded")
                                val len = input.read(buf)
                                if (len < 0) break
                                count += len
                                if (count > MAX_BYTES || count > asset.size)
                                    throw IOException("Release download exceeded verified size")
                                sha.update(buf, 0, len)
                                file.write(buf, 0, len)
                                onProgress(count, asset.size)
                            }
                        }
                        file.fd.sync()
                    }
                    if (count != asset.size || sha.digest().joinToString("") { "%02x".format(it) } != expected)
                        throw IOException("Release SHA-256/size mismatch; download discarded")
                    if (!hasZipHeader(temp)) throw IOException("Release asset is not an APK/ZIP archive")
                    if (!temp.renameTo(destination)) throw IOException("Cannot finalize verified release")
                    writeReceipt(destination, expected, asset)
                    return destination
                } finally { connection.disconnect() }
            }
        } finally { temp.delete() }
    }

    private fun writeReceipt(file: File, hash: String, asset: GitHubEcosystem.Asset) {
        require(ReleaseIdentityPolicy.asset(asset.releaseTag, asset.name, asset.url))
        val receipt = File(file.parentFile, "${file.name}.receipt.json")
        val temp = File(file.parentFile, ".${file.name}.receipt.pending")
        try {
            FileOutputStream(temp).use { out ->
                out.write(JSONObject()
                    .put("schema", RECEIPT_SCHEMA)
                    .put("repository", ReleaseIdentityPolicy.REPOSITORY)
                    .put("release_tag", asset.releaseTag)
                    .put("asset_name", asset.name)
                    .put("asset_url", asset.url)
                    .put("size", asset.size)
                    .put("file", file.name).put("sha256", hash)
                    .toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!temp.renameTo(receipt)) throw IOException("Cannot save verified download receipt")
        } finally { temp.delete() }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16384)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
