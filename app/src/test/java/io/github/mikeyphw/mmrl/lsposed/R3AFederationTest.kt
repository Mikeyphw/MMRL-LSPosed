package io.github.mikeyphw.mmrl.lsposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exact CI artifact naming and run-attempt admission, no download or trust escalation. */
class R3AFederationTest {
    private val head = "a".repeat(40)
    private val url = "https://github.com/Mikeyphw/MMRL-LSPosed/actions/runs/123"
    private val digest = "sha256:" + "b".repeat(64)
    private val pr = GitHubEcosystem.Run(123, "success", head, 2, url, "pull_request")
    private val push = pr.copy(event = "push", headBranch = "main")
    private fun art(name: String, hash: String? = digest) = GitHubEcosystem.Artifact(
        21, name, 4096, false, hash, "$url/artifacts/21")

    @Test fun currentAttemptUsesExactRunAndAttemptNotAStaleArchive() {
        val current = art("mmrl-lsposed-s01-evidence-123-attempt2")
        val stale = art("mmrl-lsposed-s01-evidence-123-attempt1")
        val foreign = art("mmrl-lsposed-s01-evidence-124-attempt2")
        assertTrue(CiArtifactFederation.inspect(pr, current).currentAttempt)
        assertTrue(CiArtifactFederation.inspect(pr, current).eventCompatible)
        assertFalse(CiArtifactFederation.inspect(pr, stale).currentAttempt)
        assertFalse(CiArtifactFederation.inspect(pr, foreign).belongsToRun)
        assertEquals(listOf(current), CiArtifactFederation.currentAttempt(pr, listOf(stale, foreign, current)))
    }

    @Test fun prArtifactsCannotBecomeTrustedMainOrSignerProof() {
        val ephemeral = art("mmrl-lsposed-pr-debug-ephemeral-123-attempt2")
        val stable = art("mmrl-lsposed-signed-debug-123-attempt2")
        assertTrue(CiArtifactFederation.inspect(pr, ephemeral).eventCompatible)
        assertFalse(CiArtifactFederation.inspect(pr, stable).eventCompatible)
        assertFalse(CiArtifactFederation.inspect(push, ephemeral).eventCompatible)
        assertTrue(CiArtifactFederation.inspect(push, stable).eventCompatible)
        assertFalse(CiArtifactFederation.inspect(push.copy(headBranch = "other"), stable).eventCompatible)
        assertTrue(CiArtifactFederation.inspect(push, stable).metadataOnly)
    }

    @Test fun falseNamesAndMissingDigestAreNotPromoted() {
        val invalid = listOf(
            "mmrl-lsposed-s01-evidence-123-attempt2-extra",
            "mmrl-lsposed-s01-evidence-0123-attempt2",
            "mmrl-lsposed-s01-evidence-123-attempt0",
            "mmrl-lsposed-s01-evidence-123-attempt99999999999999999999999",
            "mmrl-lsposed-devtool-source-123-attempt2",
        )
        for (name in invalid) assertFalse(CiArtifactFederation.inspect(pr, art(name)).currentAttempt)
        val unpinned = CiArtifactFederation.inspect(pr,
            art("mmrl-lsposed-jvm-tests-123-attempt2", null))
        assertTrue(unpinned.currentAttempt)
        assertFalse(unpinned.hasPublishedDigest)
        assertFalse(CiArtifactFederation.inspect(pr, art("mmrl-lsposed-s01-evidence-123-attempt2", "sha256:abc")).hasPublishedDigest)
    }

    @Test fun unrecognizedRunEventDoesNotClaimAdmission() {
        val evidence = art("mmrl-lsposed-s01-evidence-123-attempt2")
        assertFalse(CiArtifactFederation.inspect(pr.copy(event = ""), evidence).eventCompatible)
        assertTrue(CiArtifactFederation.inspect(push, evidence).eventCompatible)
    }
}
