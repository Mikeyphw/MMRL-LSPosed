package io.github.mikeyphw.mmrl.lsposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

/** Pure offline tests; never contact GitHub or a root service. */
class S09S10PolicyTest {
    @Test fun reverseScopeIndexShowsConfiguredButUninstalledTargets() {
        val byModule = mapOf(
            "com.example.modulea" to listOf(VectorProtocol.Scope("com.example.target", 0)),
            "com.example.moduleb" to listOf(VectorProtocol.Scope("com.example.target", 0),
                VectorProtocol.Scope("android", 0)),
        )
        val rows = AppsReverseIndex.reverse(setOf("com.example.target", "com.example.other"), byModule)
        val target = rows.single { it.packageName == "com.example.target" }
        assertEquals(listOf("com.example.modulea", "com.example.moduleb"), target.modules)
        assertTrue(rows.single { it.packageName == "com.example.other" }.modules.isEmpty())
        val system = rows.single { it.packageName == "android" }
        assertTrue(system.systemTarget)
        assertFalse(system.installed)
    }

    @Test fun reverseScopesAreDistinctPerAndroidUser() {
        val rows = AppsReverseIndex.reverse(setOf("com.example.target"), mapOf(
            "com.example.modulea" to listOf(
                VectorProtocol.Scope("com.example.target", 0),
                VectorProtocol.Scope("com.example.target", 10)),
            "com.example.moduleb" to listOf(VectorProtocol.Scope("com.example.target", 10)),
        ))
        val current = rows.single { it.key == "com.example.target/0" }
        val other = rows.single { it.key == "com.example.target/10" }
        assertTrue(current.installed)
        assertEquals(listOf("com.example.modulea"), current.modules)
        assertFalse(other.installed)
        assertEquals(listOf("com.example.modulea", "com.example.moduleb"), other.modules)
        assertEquals(10, other.userId)
    }

    @Test fun reverseIndexUsesCurrentProfileForPackageVisibility() {
        val rows = AppsReverseIndex.reverse(setOf("com.example.target"), mapOf(
            "com.example.module" to listOf(VectorProtocol.Scope("com.example.target", 0)),
        ), inventoryUserId = 10)
        val current = rows.single { it.key == "com.example.target/10" }
        val another = rows.single { it.key == "com.example.target/0" }
        assertTrue(current.installed)
        assertTrue(current.modules.isEmpty())
        assertFalse(another.installed)
        assertEquals(listOf("com.example.module"), another.modules)
    }

    @Test fun reverseScopeIndexRejectsInvalidKeysWithoutRoot() {
        assertThrows(IllegalArgumentException::class.java) {
            AppsReverseIndex.reverse(emptySet(), mapOf("not-valid" to emptyList()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppsReverseIndex.reverse(emptySet(), mapOf("com.example.module" to
                listOf(VectorProtocol.Scope("com.example.target", -1))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppsReverseIndex.reverse(emptySet(), mapOf("com.example.module" to
                listOf(VectorProtocol.Scope("../../bad", 0))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppsReverseIndex.reverse(emptySet(), emptyMap(), inventoryUserId = -1)
        }
    }

    @Test fun discoverLinksRejectExternalOrMalformedNames() {
        assertEquals("https://github.com/example/xposed-module",
            GitHubEcosystem.repositoryUrl("example/xposed-module"))
        for (bad in listOf("https://evil.com/repo", "owner/repo/extra", "../repo", "a/..", "a/b?x")) {
            assertEquals(null, GitHubEcosystem.repositoryUrl(bad))
        }
    }

    @Test fun downloadOnlyFromPinnedGitHubReleasePaths() {
        val valid = "https://github.com/Mikeyphw/MMRL-LSPosed/releases/download/v1.0/app.apk"
        assertTrue(VerifiedDownload.allowedSourceUrl(valid))
        for (bad in listOf(
            "http://github.com/Mikeyphw/MMRL-LSPosed/releases/download/v1/a.apk",
            "https://github.com.evil.invalid/Mikeyphw/MMRL-LSPosed/releases/download/v1/a.apk",
            "https://github.com/evil/repo/releases/download/v1/a.apk",
            "https://github.com/Mikeyphw/MMRL-LSPosed/actions/runs/123",
            "https://github.com:8443/Mikeyphw/MMRL-LSPosed/releases/download/v1/a.apk",
        )) assertFalse(VerifiedDownload.allowedSourceUrl(bad))
        assertFalse(VerifiedDownload.allowedName("../app.apk"))
        assertFalse(VerifiedDownload.allowedName("other.exe"))
        assertTrue(VerifiedDownload.allowedName("safe-v1.apk"))
    }

    @Test fun artifactLookupRejectsOtherRunsAndSourceCommits() {
        val sha = "a".repeat(40)
        val run = GitHubEcosystem.Run(42L, "success", sha, 2,
            "https://github.com/Mikeyphw/MMRL-LSPosed/actions/runs/42")
        fun entry(id: Long, linkedRun: Long, source: String) = JSONObject()
            .put("id", id).put("name", "signed-apk").put("size_in_bytes", 800L)
            .put("expired", false).put("digest", "sha256:" + "b".repeat(64))
            .put("workflow_run", JSONObject().put("id", linkedRun).put("head_sha", source))
        val items = JSONArray().put(entry(10, 42, sha)).put(entry(11, 99, sha))
            .put(entry(12, 42, "c".repeat(40))).put(JSONObject().put("id", 13))
        val result = GitHubEcosystem.decodeRunArtifacts(run, items)
        assertEquals(1, result.size)
        assertEquals(10L, result.single().id)
        assertEquals("sha256:" + "b".repeat(64), result.single().digest)
        assertFalse(result.single().expired)
        assertEquals("https://github.com/Mikeyphw/MMRL-LSPosed/actions/runs/42/artifacts/10",
            result.single().url)
    }

    @Test fun hashIsRequiredBeforeDownloadBecomesAvailable() {
        val url = "https://github.com/Mikeyphw/MMRL-LSPosed/releases/download/v1/app.apk"
        assertTrue(GitHubEcosystem.Asset("app.apk", 4096, "sha256:" + "a".repeat(64), url)
            .verifiedDownloadable)
        assertFalse(GitHubEcosystem.Asset("app.apk", 4096, null, url).verifiedDownloadable)
        assertFalse(GitHubEcosystem.Asset("app.apk", 4096, "sha256:" + "x".repeat(64), url)
            .verifiedDownloadable)
        assertFalse(GitHubEcosystem.Asset("app.apk", 200_000_000, "sha256:" + "a".repeat(64), url)
            .verifiedDownloadable)
        assertFalse(GitHubEcosystem.Asset("app.sh", 4096, "sha256:" + "a".repeat(64), url)
            .verifiedDownloadable)
    }
}
