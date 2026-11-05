package io.github.mikeyphw.mmrl.lsposed

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Non-network JVM policy tests. No credentials, Android root or APK installation. */
class R3BArtifactArchiveTest {
    private val source = "a".repeat(40)
    private val digest = "sha256:" + "b".repeat(64)
    private val run = GitHubEcosystem.Run(1234, "success", source, 2,
        "https://github.com/Mikeyphw/MMRL-LSPosed/actions/runs/1234", "pull_request")
    private fun artifact(name: String = "mmrl-lsposed-s01-evidence-1234-attempt2",
                         digestValue: String? = digest, expired: Boolean = false) =
        GitHubEcosystem.Artifact(5678, name, 4096, expired, digestValue,
            "https://github.com/Mikeyphw/MMRL-LSPosed/actions/runs/1234/artifacts/5678")

    @Test fun onlyCompletedCurrentAttemptDigestBoundArtifactsAreDownloadable() {
        assertTrue(CiArtifactDownload.eligible(run, artifact()))
        assertFalse(CiArtifactDownload.eligible(run.copy(status = "in_progress"), artifact()))
        assertFalse(CiArtifactDownload.eligible(run, artifact(digestValue = null)))
        assertFalse(CiArtifactDownload.eligible(run, artifact(expired = true)))
        assertFalse(CiArtifactDownload.eligible(run, artifact(name = "mmrl-lsposed-s01-evidence-1234-attempt1")))
        assertFalse(CiArtifactDownload.eligible(run, artifact(name = "mmrl-lsposed-signed-debug-1234-attempt2")))
        assertFalse(CiArtifactDownload.eligible(run.copy(event = "push", headBranch = ""), artifact()))
        assertFalse(CiArtifactDownload.eligible(run, artifact().copy(bytes = 200_000_000)))
    }

    @Test fun credentialCannotBeSentToUntrustedRedirectHost() {
        assertTrue(CiArtifactDownload.allowedStorageRedirect(
            "https://productionresultssa5.blob.core.windows.net/actions-results/file?sig=opaque"))
        assertTrue(CiArtifactDownload.allowedStorageRedirect(
            "https://pipelines.actions.githubusercontent.com/file?sig=opaque"))
        for (url in listOf(
            "http://productionresultssa5.blob.core.windows.net/x",
            "https://blob.core.windows.net.evil.test/x",
            "https://127.0.0.1/x", "https://api.github.com/x",
            "https://user@pipelines.actions.githubusercontent.com/file",
            "https://pipelines.actions.githubusercontent.com:444/file",
            "https://evil.test/redirect"
        )) assertFalse(CiArtifactDownload.allowedStorageRedirect(url))
    }

    @Test fun zipPathsFailClosedBeforeExtraction() {
        for (bad in listOf("../secret.txt", "/etc/passwd", "a/../b.json", "a\\b.json",
            "C:/evil.json", "a//b.json", "a/./b.json", "a/evil?.json", "a/dir./x.txt", "")) {
            assertFalse(CiArtifactDownload.safeEntry(bad))
        }
        assertTrue(CiArtifactDownload.safeEntry("evidence/verification.json"))
        assertTrue(CiArtifactDownload.safeEntry("subdir/"))
    }

    private fun zipOf(vararg names: String): File {
        val file = File.createTempFile("ci-test-", ".zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in names) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("sample".toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test fun zipInspectionRejectsTraversalAndPreservesSafeNames() {
        val good = zipOf("plan.json", "nested/verification.json")
        val bad = zipOf("../escape.json")
        try {
            assertEquals(listOf("plan.json", "nested/verification.json"), CiArtifactDownload.inspectZip(good))
            var refused = false
            try { CiArtifactDownload.inspectZip(bad) } catch (_: Exception) { refused = true }
            assertTrue(refused)
        } finally { good.delete(); bad.delete() }
    }

    @Test fun receiptRequiresExactSourceRunArtifactDigestAndExplicitUnqualifiedFlags() {
        val file = File.createTempFile("run1234-attempt2-artifact5678", ".zip")
        val expected = File(file.parentFile, "run1234-attempt2-artifact5678.zip")
        expected.writeBytes(byteArrayOf(1, 2, 3))
        val doc = JSONObject().put("schema", "mmrl-lsposed.ci-archive-receipt.v1")
            .put("repository", "Mikeyphw/MMRL-LSPosed").put("run_id", 1234)
            .put("attempt", 2).put("artifact_id", 5678)
            .put("head_sha", source).put("event", "pull_request").put("head_branch", "")
            .put("file", expected.name).put("bytes", expected.length()).put("entry_count", 1)
            .put("published_digest", digest).put("archive_sha256", "b".repeat(64))
            .put("publisher_qualified", false).put("apk_signer_qualified", false)
            .put("independently_attested", false)
        try {
            assertTrue(CiArtifactDownload.validReceipt(doc, expected))
            assertFalse(CiArtifactDownload.validReceipt(JSONObject(doc.toString()).put("attempt", 3), expected))
            assertFalse(CiArtifactDownload.validReceipt(JSONObject(doc.toString()).put("repository", "other/repo"), expected))
            assertFalse(CiArtifactDownload.validReceipt(JSONObject(doc.toString()).put("publisher_qualified", true), expected))
            assertFalse(CiArtifactDownload.validReceipt(JSONObject(doc.toString()).put("event", "push"), expected))
            assertFalse(CiArtifactDownload.validReceipt(JSONObject(doc.toString()).put("published_digest", "sha256:" + "c".repeat(64)), expected))
        } finally { file.delete(); expected.delete() }
    }

    @Test fun freshRunRejectsSilentRerunAndAlteredSha() {
        val valid = JSONObject().put("id", 1234).put("html_url", run.htmlUrl)
            .put("head_sha", source).put("run_attempt", 2)
            .put("event", "pull_request").put("status", "completed")
            .put("conclusion", "success")
        assertTrue(GitHubEcosystem.runMatches(run, valid))
        assertFalse(GitHubEcosystem.runMatches(run, JSONObject(valid.toString()).put("run_attempt", 3)))
        assertFalse(GitHubEcosystem.runMatches(run, JSONObject(valid.toString()).put("head_sha", "c".repeat(40))))
        assertFalse(GitHubEcosystem.runMatches(run, JSONObject(valid.toString()).put("status", "in_progress")))
        assertFalse(GitHubEcosystem.runMatches(run.copy(event = "push", headBranch = "main"), valid))
    }
}
