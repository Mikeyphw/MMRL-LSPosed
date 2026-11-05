package io.github.mikeyphw.mmrl.lsposed

/**
 * Public GitHub release *metadata* identity, not APK signature or build attestation.
 * This deliberately supports only plain ASCII release tags and filenames; other
 * releases remain browsable in GitHub rather than weakening the download gate.
 */
internal object ReleaseIdentityPolicy {
    const val REPOSITORY = "Mikeyphw/MMRL-LSPosed"
    private val TAG = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}\\.(apk|zip)", RegexOption.IGNORE_CASE)

    fun validTag(tag: String) = TAG.matches(tag) && !tag.contains("..")
    fun validName(name: String) = NAME.matches(name) && !name.contains("..")
    fun releasePage(tag: String, url: String): Boolean =
        validTag(tag) && url == "https://github.com/$REPOSITORY/releases/tag/$tag"

    fun asset(tag: String, name: String, url: String): Boolean =
        validTag(tag) && validName(name) &&
            url == "https://github.com/$REPOSITORY/releases/download/$tag/$name"
}
