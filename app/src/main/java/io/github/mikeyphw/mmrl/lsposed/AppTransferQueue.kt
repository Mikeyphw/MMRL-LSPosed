package io.github.mikeyphw.mmrl.lsposed

import android.content.Context
import java.io.File

/** Single application-owned scheduler. Network operations are admitted only through explicit user actions.
 * No WorkManager, installer, root, Vector or notification authority is granted to this queue.
 */
internal object AppTransferQueue {
    private var engine: DurableTransferQueue? = null
    private var namespace: File? = null
    @Synchronized fun get(context: Context): DurableTransferQueue {
        val app = context.applicationContext ?: context
        val root = app.filesDir.canonicalFile
        engine?.let { require(namespace == root); return it }
        // Reconcile old Activity attempts before opening the queue. Neither reconciliation initiates IO to GitHub.
        ActivityTransferJournal.snapshots(app)
        val store = QueueStore(File(root, "download-queue"))
        val queue = try {
            DurableTransferQueue(store, perform = { row, cancelled, started, progress ->
                val source = row.source
                if (source.kind == "RELEASE") {
                    VerifiedDownload.save(app, releaseAsset(source), cancelled, { count, _ -> progress(count) }, row.id, started)
                } else {
                    CiArtifactDownload.save(app, ciRun(source), ciArtifact(source), cancelled, { count, _ -> progress(count) }, row.id, started)
                }
                Unit
            }, discard = { row ->
                ResumableTransfer.discard(File(root, if (row.source.kind == "RELEASE") "verified-downloads" else "ci-archives"), row.id)
            })
        } catch (error: Exception) { store.close(); throw error }
        namespace = root; engine = queue
        return queue
    }
    private fun releaseAsset(s: QueuedSource) = GitHubEcosystem.Asset(s.name, s.size, s.digest,
        "https://github.com/${ReleaseIdentityPolicy.REPOSITORY}/releases/download/${s.tag}/${s.name}", s.tag)
    private fun ciRun(s: QueuedSource) = GitHubEcosystem.Run(s.runId, "success", s.headSha, s.runAttempt,
        "https://github.com/${ReleaseIdentityPolicy.REPOSITORY}/actions/runs/${s.runId}", s.event, s.headBranch)
    private fun ciArtifact(s: QueuedSource) = GitHubEcosystem.Artifact(s.artifactId, s.name, s.size, false, s.digest,
        "https://github.com/${ReleaseIdentityPolicy.REPOSITORY}/actions/runs/${s.runId}/artifacts/${s.artifactId}")
    fun release(asset: GitHubEcosystem.Asset): QueuedSource {
        require(asset.verifiedDownloadable)
        val source = QueuedSource("RELEASE", asset.name, asset.size, requireNotNull(asset.digest), tag = asset.releaseTag)
        require(releaseAsset(source) == asset)
        return source
    }
    fun ci(run: GitHubEcosystem.Run, asset: GitHubEcosystem.Artifact): QueuedSource {
        require(CiArtifactDownload.eligible(run, asset))
        val source = QueuedSource("CI", asset.name, asset.bytes, requireNotNull(asset.digest),
            runId = run.id, runAttempt = run.attempt, headSha = run.headSha, event = run.event,
            headBranch = run.headBranch, artifactId = asset.id)
        require(ciRun(source) == run && ciArtifact(source) == asset)
        return source
    }
    fun findRelease(context: Context, row: QueueEntry): VerifiedDownload.Saved? {
        require(row.state == QueueStatus.SUCCEEDED && row.source.kind == "RELEASE")
        val asset = row.source
        return VerifiedDownload.listSaved(context).singleOrNull { it.releaseTag == asset.tag &&
            it.sha256 == asset.digest.removePrefix("sha256:") && it.file.name == "${it.sha256.take(16)}-${asset.name}" }
    }
    fun findCi(context: Context, row: QueueEntry): CiArtifactDownload.Saved? {
        require(row.state == QueueStatus.SUCCEEDED && row.source.kind == "CI")
        val asset = row.source
        return CiArtifactDownload.listSaved(context).singleOrNull { it.runId == asset.runId && it.attempt == asset.runAttempt &&
            it.artifactId == asset.artifactId && it.sourceSha == asset.headSha && it.sha256 == asset.digest.removePrefix("sha256:") }
    }
}
