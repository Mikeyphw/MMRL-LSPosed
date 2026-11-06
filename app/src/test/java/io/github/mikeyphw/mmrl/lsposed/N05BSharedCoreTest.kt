package io.github.mikeyphw.mmrl.lsposed

import io.github.mikeyphw.mmrl.shared.GitHubRepository
import io.github.mikeyphw.mmrl.shared.GitHubRunIdentity
import io.github.mikeyphw.mmrl.shared.ReleaseAssetIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM: validates the real source-pinned shared-core dependency, no network/root. */
class N05BSharedCoreTest {
    private val repo = requireNotNull(GitHubRepository.parse("Mikeyphw/MMRL-LSPosed"))

    @Test fun releaseIdentityIsDelegatedToPinnedSharedCore() {
        val release = requireNotNull(ReleaseAssetIdentity.of(repo, "v1.0", "app-release.apk"))
        assertEquals("Mikeyphw/MMRL-LSPosed", repo.fullName)
        assertTrue(ReleaseIdentityPolicy.asset("v1.0", "app-release.apk", release.assetUrl))
        assertTrue(ReleaseIdentityPolicy.releasePage("v1.0", release.releasePage))
        assertFalse(ReleaseIdentityPolicy.asset("v1.0", "app-release.apk", release.assetUrl + "?download=1"))
        assertFalse(ReleaseIdentityPolicy.asset("v2.0", "app-release.apk", release.assetUrl))
        assertNull(ReleaseAssetIdentity.of(repo, "../v1.0", "app-release.apk"))
        assertNull(ReleaseAssetIdentity.of(repo, "v1.0", "../app-release.apk"))
        assertNull(GitHubRepository.parse("other/../repo"))
    }

    @Test fun sharedCiIdentityRemainsMetadataOnlyAndAttemptBound() {
        val head = "a".repeat(40)
        val identity = GitHubRunIdentity.of(repo, 123L, 2, head, "push", "main")
        assertNotNull(identity)
        val ci = requireNotNull(identity)
        assertTrue(ci.matchesCurrentRun(123L, 2, head, "push", "main", "completed", "success"))
        assertFalse(ci.matchesCurrentRun(123L, 1, head, "push", "main", "completed", "success"))
        assertFalse(ci.matchesCurrentRun(123L, 2, head, "push", "main", "completed", "failure"))
        assertTrue(ci.matchesArtifactOwner(123L, head))
        assertFalse(ci.matchesArtifactOwner(124L, head))
        assertNull(GitHubRunIdentity.of(repo, 123L, 0, head, "push", "main"))
    }
}
