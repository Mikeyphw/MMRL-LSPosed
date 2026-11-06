package io.github.mikeyphw.mmrl.lsposed

import io.github.mikeyphw.mmrl.shared.GitHubRepository
import io.github.mikeyphw.mmrl.shared.ReleaseAssetIdentity

/**
 * Public GitHub release *metadata* identity, not APK signature or build attestation.
 * The app owns the repository name; shared source owns strict tag/name/URL admission.
 * No fallback to a permissive legacy URL check is allowed.
 */
internal object ReleaseIdentityPolicy {
    const val REPOSITORY = "Mikeyphw/MMRL-LSPosed"
    private val repository = requireNotNull(GitHubRepository.parse(REPOSITORY))

    fun validTag(tag: String): Boolean = ReleaseAssetIdentity.of(repository, tag, "probe.apk") != null
    fun validName(name: String): Boolean = ReleaseAssetIdentity.of(repository, "v1", name) != null
    fun releasePage(tag: String, url: String): Boolean =
        ReleaseAssetIdentity.of(repository, tag, "probe.apk")?.matchesReleasePage(url) == true
    fun asset(tag: String, name: String, url: String): Boolean =
        ReleaseAssetIdentity.of(repository, tag, name)?.matchesAssetUrl(url) == true
}
