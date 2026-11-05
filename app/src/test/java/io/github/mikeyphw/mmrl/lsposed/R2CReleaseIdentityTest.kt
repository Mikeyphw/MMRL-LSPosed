package io.github.mikeyphw.mmrl.lsposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/** Pure JVM tests; no root, APK install, GitHub requests or filesystem writes. */
class R2CReleaseIdentityTest {
    private val hash = "a".repeat(64)
    private val tag = "v1.2.3"
    private val filename = "app-release.apk"
    private val url = "https://github.com/Mikeyphw/MMRL-LSPosed/releases/download/$tag/$filename"
    private val stored = "${hash.take(16)}-$filename"

    private fun receipt(): JSONObject = JSONObject()
        .put("schema", "mmrl-lsposed.release-asset-receipt.v2")
        .put("repository", ReleaseIdentityPolicy.REPOSITORY)
        .put("release_tag", tag)
        .put("asset_name", filename)
        .put("asset_url", url)
        .put("size", 4096)
        .put("file", stored)
        .put("sha256", hash)

    @Test fun exactReleasePageAndArtifactBinding() {
        assertTrue(ReleaseIdentityPolicy.releasePage(tag,
            "https://github.com/Mikeyphw/MMRL-LSPosed/releases/tag/$tag"))
        assertTrue(ReleaseIdentityPolicy.asset(tag, filename, url))
        assertFalse(ReleaseIdentityPolicy.asset("v1.2.4", filename, url))
        assertFalse(ReleaseIdentityPolicy.asset(tag, "other.apk", url))
        assertFalse(ReleaseIdentityPolicy.asset(tag, filename, "$url?download=1"))
        assertFalse(ReleaseIdentityPolicy.asset(tag, filename, "$url#test"))
        assertFalse(ReleaseIdentityPolicy.asset(tag, filename, url.replace("github.com", "github.com.evil.test")))
        assertFalse(ReleaseIdentityPolicy.asset(tag, filename, url.replace("MMRL-LSPosed", "MMRL")))
        assertFalse(ReleaseIdentityPolicy.asset(tag, filename, url.replace("/v1.2.3/", "/%76%31.2.3/")))
        assertFalse(ReleaseIdentityPolicy.asset(tag, filename, url.replace("/v1.2.3/", "/v1.2.3//")))
        assertFalse(ReleaseIdentityPolicy.validTag("../v1"))
        assertFalse(ReleaseIdentityPolicy.validTag("v1+bad"))
        assertFalse(ReleaseIdentityPolicy.validName("../app.apk"))
        assertFalse(ReleaseIdentityPolicy.validName("app.apk/other"))
    }

    @Test fun downloadableRequiresExactlyBoundReleaseMetadataAndHash() {
        val strong = "sha256:$hash"
        assertTrue(GitHubEcosystem.Asset(filename, 4096, strong, url, tag).verifiedDownloadable)
        assertFalse(GitHubEcosystem.Asset(filename, 4096, strong, url, "v1.2.4").verifiedDownloadable)
        assertFalse(GitHubEcosystem.Asset(filename, 4096, strong, "$url?from=x", tag).verifiedDownloadable)
        assertFalse(GitHubEcosystem.Asset(filename, 4096, "sha256:zz", url, tag).verifiedDownloadable)
        assertFalse(GitHubEcosystem.Asset(filename, 4096, null, url, tag).verifiedDownloadable)
        assertFalse(GitHubEcosystem.Asset(filename, 0, strong, url, tag).verifiedDownloadable)
        assertFalse(GitHubEcosystem.Asset(filename, 4096, strong, url).verifiedDownloadable)
    }

    @Test fun sourceBoundReceiptRejectsTamperingAndPartialClaims() {
        assertEquals(tag, VerifiedDownload.receiptTag(receipt(), stored, hash, 4096))
        assertNull(VerifiedDownload.receiptTag(receipt().put("asset_url", "$url?modified=1"), stored, hash, 4096))
        assertNull(VerifiedDownload.receiptTag(receipt().put("release_tag", "v9"), stored, hash, 4096))
        assertNull(VerifiedDownload.receiptTag(receipt().put("size", 8192), stored, hash, 4096))
        assertNull(VerifiedDownload.receiptTag(receipt().put("repository", "somebody/else"), stored, hash, 4096))
        assertNull(VerifiedDownload.receiptTag(receipt().put("sha256", "b".repeat(64)), stored, hash, 4096))
        assertNull(VerifiedDownload.receiptTag(receipt().put("schema", "unknown"), stored, hash, 4096))
        assertNull(VerifiedDownload.receiptTag(receipt(), stored.replace("app", "fake"), hash, 4096))
    }

    @Test fun genuineLegacyReceiptIsPreservedWithoutSourceUpgrade() {
        val old = JSONObject().put("file", stored).put("sha256", hash)
        assertEquals("", VerifiedDownload.receiptTag(old, stored, hash, 4096))
        assertNull(VerifiedDownload.receiptTag(old.put("release_tag", tag), stored, hash, 4096))
        assertNull(VerifiedDownload.receiptTag(old.put("schema", "mmrl-lsposed.release-asset-receipt.v2"), stored, hash, 4096))
    }
}
