package io.github.mikeyphw.mmrl.lsposed

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** User-initiated CI archive custody. SHA matches GitHub's artifact metadata,
 * not a trusted publisher, APK signer, Devtool attestation or installed package.
 * The only permitted extraction is bounded, non-executable evidence into private storage.
 */
internal object CiArtifactDownload {
    private const val REPOSITORY = "Mikeyphw/MMRL-LSPosed"
    private const val MAX_ARCHIVE = 150L * 1024 * 1024
    private const val MAX_TOTAL_UNPACKED = 300L * 1024 * 1024
    private const val MAX_ENTRIES = 512
    private const val MAX_EVIDENCE = 1_048_576L
    private const val MAX_EVIDENCE_TOTAL = 8L * 1024 * 1024
    private const val SCHEMA = "mmrl-lsposed.ci-archive-receipt.v1"
    private val shaPattern = Regex("sha256:[a-f0-9]{64}")
    private val pathSegment = Regex("[A-Za-z0-9_.@+-]{1,120}")
    private val extractable = Regex("[A-Za-z0-9_.-]{1,110}\\.(?:json|txt|log|xml|trx)", RegexOption.IGNORE_CASE)

    data class Saved(val file: File, val runId: Long, val attempt: Int,
                     val artifactId: Long, val sourceSha: String, val sha256: String,
                     val entryCount: Int)

    internal fun eligible(run: GitHubEcosystem.Run, artifact: GitHubEcosystem.Artifact): Boolean {
        val inspection = CiArtifactFederation.inspect(run, artifact)
        return run.status == "success" &&
            (run.event == "pull_request" || (run.event == "push" && run.headBranch == "main")) &&
            inspection.currentAttempt && inspection.eventCompatible &&
            inspection.hasPublishedDigest && !artifact.expired &&
            artifact.bytes in 1..MAX_ARCHIVE && artifact.id > 0 &&
            shaPattern.matches(artifact.digest ?: "")
    }

    /** API redirect only: never send the GitHub credential to these hosts. */
    internal fun allowedStorageRedirect(value: String): Boolean = runCatching {
        val url = URL(value)
        val host = url.host.lowercase(java.util.Locale.ROOT)
        url.protocol == "https" && url.port == -1 && url.userInfo == null &&
            url.ref == null && (Regex("[a-z0-9-]+\\.blob\\.core\\.windows\\.net").matches(host) ||
                Regex("[a-z0-9-]+\\.actions\\.githubusercontent\\.com").matches(host))
    }.getOrDefault(false)

    /** Refuse paths dangerous for possible future extraction even if only listing. */
    internal fun safeEntry(name: String): Boolean {
        if (name.isBlank() || name.length > 480 || '\\' in name || '\u0000' in name ||
            name.startsWith('/') || ':' in name) return false
        val normalized = name.removeSuffix("/")
        return normalized.isNotEmpty() && normalized.split('/').all { segment ->
            segment != "." && segment != ".." && !segment.endsWith('.') &&
                pathSegment.matches(segment)
        }
    }

    /** Inspect central directory without extracting or executing anything. */
    internal fun inspectZip(file: File): List<String> {
        val names = mutableListOf<String>()
        val seen = HashSet<String>()
        var expanded = 0L
        ZipFile(file).use { zip ->
            val all = zip.entries()
            while (all.hasMoreElements()) {
                val entry = all.nextElement()
                if (names.size >= MAX_ENTRIES || !safeEntry(entry.name) || !seen.add(entry.name))
                    throw IOException("ZIP has unsafe, repeated or excessive entries")
                if (entry.size < 0 || entry.compressedSize < 0 || entry.size > MAX_TOTAL_UNPACKED)
                    throw IOException("ZIP entry has unknown/excessive size")
                if (entry.size > MAX_TOTAL_UNPACKED - expanded)
                    throw IOException("ZIP total expanded size exceeds limit")
                expanded += entry.size
                if (!entry.isDirectory && entry.size > 1_048_576L && entry.compressedSize < 1024L)
                    throw IOException("ZIP entry has implausible compression ratio")
                names.add(entry.name)
            }
        }
        if (names.isEmpty()) throw IOException("Empty ZIP archive")
        return names
    }

    private fun checkedFile(root: File, file: File): Boolean = runCatching {
        root.isDirectory && file.isFile && !file.isDirectory &&
            !Files.isSymbolicLink(root.toPath()) && !Files.isSymbolicLink(file.toPath()) &&
            file.canonicalFile.parentFile == root.canonicalFile &&
            file.absoluteFile.parentFile == root.absoluteFile &&
            file.length() in 1..MAX_ARCHIVE
    }.getOrDefault(false)

    private fun filename(run: GitHubEcosystem.Run, artifact: GitHubEcosystem.Artifact) =
        "run${run.id}-attempt${run.attempt}-artifact${artifact.id}.zip"

    private fun root(context: Context) = File(context.filesDir, "ci-archives")

    /** Hash the raw GitHub artifact ZIP, not an individual ZIP entry. */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(16_384)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Receipt describes authenticated metadata, not cryptographic build provenance. */
    internal fun validReceipt(doc: JSONObject, file: File): Boolean {
        val id = doc.optLong("run_id", 0)
        val attempt = doc.optInt("attempt", 0)
        val artifact = doc.optLong("artifact_id", 0)
        val rawHash = doc.optString("archive_sha256")
        return doc.optString("schema") == SCHEMA && doc.optString("repository") == REPOSITORY &&
            id > 0 && attempt > 0 && artifact > 0 &&
            doc.optString("file") == "run${id}-attempt${attempt}-artifact${artifact}.zip" &&
            file.name == doc.optString("file") && doc.optLong("bytes", -1) == file.length() &&
            Regex("[a-f0-9]{40}").matches(doc.optString("head_sha")) &&
            ((doc.optString("event") == "pull_request" && doc.optString("head_branch") == "") ||
                (doc.optString("event") == "push" && doc.optString("head_branch") == "main")) &&
            doc.optBoolean("publisher_qualified", true) == false &&
            doc.optBoolean("apk_signer_qualified", true) == false &&
            doc.optBoolean("independently_attested", true) == false &&
            shaPattern.matches(doc.optString("published_digest")) &&
            Regex("[a-f0-9]{64}").matches(rawHash) &&
            doc.optString("published_digest") == "sha256:$rawHash" &&
            doc.optInt("entry_count", 0) in 1..MAX_ENTRIES
    }

    private fun receipt(file: File) = File(file.parentFile, "${file.name}.receipt.json")

    private fun loadReceipt(file: File): JSONObject? = runCatching {
        val path = receipt(file)
        if (!path.isFile || Files.isSymbolicLink(path.toPath()) ||
            path.canonicalFile.parentFile != file.parentFile.canonicalFile ||
            path.length() !in 1..4096L) null
        else JSONObject(path.readText(Charsets.UTF_8))
    }.getOrNull()

    /** Re-hash ZIP + inspect entries when opening an archived result. */
    fun recheckSaved(context: Context, selected: Saved): Boolean = runCatching {
        val file = selected.file
        if (!checkedFile(root(context), file)) return@runCatching false
        val info = loadReceipt(file) ?: return@runCatching false
        if (!validReceipt(info, file) || sha256(file) != info.optString("archive_sha256") ||
            info.optLong("run_id") != selected.runId || info.optInt("attempt") != selected.attempt ||
            info.optLong("artifact_id") != selected.artifactId ||
            info.optString("head_sha") != selected.sourceSha ||
            info.optString("archive_sha256") != selected.sha256) return@runCatching false
        inspectZip(file).size == info.optInt("entry_count") && selected.entryCount == info.optInt("entry_count")
    }.getOrDefault(false)

    /** A listing never trusts an existing receipt without a fresh file hash. */
    fun listSaved(context: Context): List<Saved> {
        val folder = root(context)
        return folder.listFiles().orEmpty().filter { it.name.endsWith(".zip") }.take(80).mapNotNull { file ->
            runCatching {
                if (!checkedFile(folder, file)) return@runCatching null
                val doc = loadReceipt(file) ?: return@runCatching null
                if (!validReceipt(doc, file) || sha256(file) != doc.optString("archive_sha256"))
                    return@runCatching null
                if (inspectZip(file).size != doc.optInt("entry_count")) return@runCatching null
                Saved(file, doc.getLong("run_id"), doc.getInt("attempt"),
                    doc.getLong("artifact_id"), doc.getString("head_sha"),
                    doc.getString("archive_sha256"), doc.getInt("entry_count"))
            }.getOrNull()
        }
    }

    /** A token is used only against api.github.com; never forwarded to the 302 URL. */
    private fun download(context: Context, id: Long, target: File,
                         limit: Long, cancelled: () -> Boolean, progress: (Long, Long) -> Unit): Pair<String, Long> {
        val token = try { LocalSettings.gitHubToken(context) }
            catch (_: Exception) { throw IOException("GitHub token unavailable; update Settings") }
            ?: throw IOException("Add a GitHub token with Actions: read access in Settings")
        val api = URL("https://api.github.com/repos/$REPOSITORY/actions/artifacts/$id/zip")
        val initial = (api.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 8_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "MMRL-LSPosed/0.1")
            setRequestProperty("Authorization", "Bearer $token")
        }
        val redirect = try {
            when (initial.responseCode) {
                302 -> initial.getHeaderField("Location") ?: throw IOException("GitHub omitted download location")
                401, 403 -> throw IOException("GitHub Actions access denied; check token Actions: read permission")
                410 -> throw IOException("CI artifact expired")
                else -> throw IOException("GitHub artifact archive unavailable")
            }
        } finally { initial.disconnect() }
        if (!allowedStorageRedirect(redirect)) throw IOException("Unexpected GitHub artifact storage host")
        val conn = (URL(redirect).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "MMRL-LSPosed/0.1")
            // NEVER forward token or signed URL to logs, receipts or other hosts.
        }
        try {
            if (conn.responseCode != 200) throw IOException("CI archive storage unavailable")
            if (conn.contentLengthLong > limit) throw IOException("CI archive exceeds announced size")
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            FileOutputStream(target).use { output ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(16_384)
                    while (true) {
                        if (cancelled()) throw InterruptedIOException("CI archive download cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        count += n
                        if (count > limit || count > MAX_ARCHIVE) throw IOException("CI archive larger than metadata")
                        digest.update(buf, 0, n)
                        output.write(buf, 0, n)
                        progress(count, limit)
                    }
                }
                output.fd.sync()
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } to count
        } finally { conn.disconnect() }
    }

    fun save(context: Context, run: GitHubEcosystem.Run, artifact: GitHubEcosystem.Artifact,
             cancelled: () -> Boolean = { false },
             onProgress: (Long, Long) -> Unit = { _, _ -> }): Saved {
        if (!eligible(run, artifact)) throw IOException("Artifact not current, source-bound or digest-published")
        // Independent API read at the moment of download prevents stale/run-attempt metadata.
        val current = GitHubEcosystem.freshRun(run, context)
        if (!current || GitHubEcosystem.runArtifacts(run, context).singleOrNull { it.id == artifact.id } != artifact)
            throw IOException("Run attempt or artifact metadata changed; reload GitHub")
        val folder = root(context)
        if (Files.isSymbolicLink(folder.toPath())) throw IOException("CI archive directory must not be a symlink")
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot create CI archive directory")
        val dest = File(folder, filename(run, artifact))
        if (dest.exists() || receipt(dest).exists()) throw IOException("CI archive already exists; inspect saved copy")
        val tmp = File.createTempFile(".ci-", ".pending", folder)
        try {
            val (hash, length) = download(context, artifact.id, tmp, artifact.bytes, cancelled, onProgress)
            if (length != artifact.bytes || "sha256:$hash" != artifact.digest)
                throw IOException("GitHub-published archive SHA-256 or byte size mismatch")
            if (cancelled()) throw InterruptedIOException("CI archive download cancelled")
            val entries = inspectZip(tmp)
            if (!tmp.renameTo(dest)) throw IOException("Cannot finalize archive")
            val doc = JSONObject()
                .put("schema", SCHEMA).put("repository", REPOSITORY)
                .put("run_id", run.id).put("attempt", run.attempt)
                .put("head_sha", run.headSha).put("event", run.event)
                .put("head_branch", run.headBranch).put("artifact_id", artifact.id)
                .put("artifact_name", artifact.name)
                .put("published_digest", artifact.digest).put("archive_sha256", hash)
                .put("bytes", length).put("entry_count", entries.size)
                .put("file", dest.name).put("publisher_qualified", false)
                .put("apk_signer_qualified", false).put("independently_attested", false)
            val tmpReceipt = File.createTempFile(".ci-receipt-", ".pending", folder)
            try {
                FileOutputStream(tmpReceipt).use { out ->
                    out.write(doc.toString().toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                if (!tmpReceipt.renameTo(receipt(dest))) throw IOException("Cannot finalize CI archive receipt")
            } finally { tmpReceipt.delete() }
            return Saved(dest, run.id, run.attempt, artifact.id, run.headSha, hash, entries.size)
        } catch (failure: Exception) {
            // An interrupted receipt write must not strand an untracked archive.
            if (dest.exists() && !receipt(dest).exists()) dest.delete()
            throw failure
        } finally { tmp.delete() }
    }

    /** Extract text receipts only. No APK/DEX/ZIP extraction or installer handoff. */
    fun extractEvidence(context: Context, selected: Saved): Int {
        if (!recheckSaved(context, selected)) throw IOException("CI archive or receipt changed")
        val folder = File(root(context), selected.file.name.removeSuffix(".zip") + "-evidence")
        if (folder.exists()) throw IOException("Evidence directory already exists")
        if (!folder.mkdir()) throw IOException("Cannot create evidence directory")
        var written = 0
        var total = 0L
        try {
            ZipFile(selected.file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val basename = entry.name.substringAfterLast('/')
                    if (entry.isDirectory || !extractable.matches(basename)) continue
                    if (written >= 24 || entry.size !in 1..MAX_EVIDENCE ||
                        entry.size > MAX_EVIDENCE_TOTAL - total)
                        throw IOException("Evidence extraction exceeds bounded policy")
                    // Output basename is anchored by SHA-256 of the internal path.
                    val nameHash = MessageDigest.getInstance("SHA-256")
                        .digest(entry.name.toByteArray(Charsets.UTF_8))
                        .take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
                    val target = File(folder, "$nameHash-$basename")
                    val crc = CRC32()
                    var count = 0L
                    FileOutputStream(target).use { out ->
                        zip.getInputStream(entry).use { input ->
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                count += n
                                if (count > MAX_EVIDENCE) throw IOException("Evidence file expanded beyond limit")
                                crc.update(buf, 0, n)
                                out.write(buf, 0, n)
                            }
                        }
                        out.fd.sync()
                    }
                    if (count != entry.size || crc.value != entry.crc)
                        throw IOException("Evidence ZIP entry integrity mismatch")
                    total += count
                    written++
                }
            }
            if (written == 0) throw IOException("No supported text evidence; binary APKs remain ZIP-only")
            return written
        } catch (e: Exception) {
            folder.listFiles()?.forEach { it.delete() }
            folder.delete()
            throw e
        }
    }
}
