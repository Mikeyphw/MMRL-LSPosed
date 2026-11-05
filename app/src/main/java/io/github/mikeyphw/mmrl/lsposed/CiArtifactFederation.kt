package io.github.mikeyphw.mmrl.lsposed

/** Read-only CI metadata classification; never a signer, APK or attestation verifier.
 * The GitHub API associates artifacts with a run/source but not with an attempt.
 * Only our exact run-id/attempt-named artifacts can be called current-attempt.
 */
internal object CiArtifactFederation {
    enum class Kind(val label: String, val prOnly: Boolean = false, val mainOnly: Boolean = false) {
        DEVTOOL_EVIDENCE("Devtool graph and source evidence"),
        JVM_TESTS("Hosted JVM results"),
        LINT_REPORT("Android lint reports"),
        API36_UI("API 36 emulator evidence"),
        PR_DEBUG("PR ephemeral debug APK", prOnly = true),
        PR_EMULATOR("PR emulator APK pair", prOnly = true),
        SIGNED_DEBUG("Trusted-main debug APK receipt", mainOnly = true),
        S08_LAB("Trusted-main S08 device test assets", mainOnly = true),
        OTHER("Unclassified GitHub artifact"),
    }

    data class Inspection(
        val kind: Kind,
        val belongsToRun: Boolean,
        val currentAttempt: Boolean,
        val eventCompatible: Boolean,
        val hasPublishedDigest: Boolean,
    ) {
        // A published artifact digest is NOT an independently checked archive.
        val metadataOnly: Boolean get() = true
    }

    private val shape = Regex(
        "mmrl-lsposed-(s01-evidence|jvm-tests|s01-lint|emulator-api36|" +
            "pr-debug-ephemeral|pr-emulator|signed-debug|s08-lab)-([1-9][0-9]*)-attempt([1-9][0-9]*)"
    )
    private val digest = Regex("sha256:[0-9a-f]{64}")

    fun inspect(run: GitHubEcosystem.Run, artifact: GitHubEcosystem.Artifact): Inspection {
        val match = shape.matchEntire(artifact.name)
        val id = match?.groupValues?.get(2)?.toLongOrNull()
        val attempt = match?.groupValues?.get(3)?.toIntOrNull()
        val kind = when (match?.groupValues?.get(1)) {
            "s01-evidence" -> Kind.DEVTOOL_EVIDENCE
            "jvm-tests" -> Kind.JVM_TESTS
            "s01-lint" -> Kind.LINT_REPORT
            "emulator-api36" -> Kind.API36_UI
            "pr-debug-ephemeral" -> Kind.PR_DEBUG
            "pr-emulator" -> Kind.PR_EMULATOR
            "signed-debug" -> Kind.SIGNED_DEBUG
            "s08-lab" -> Kind.S08_LAB
            else -> Kind.OTHER
        }
        val belongs = kind != Kind.OTHER && id == run.id && attempt != null
        val current = belongs && attempt == run.attempt
        val compatible = current && when {
            kind.prOnly -> run.event == "pull_request"
            kind.mainOnly -> run.event == "push" && run.headBranch == "main"
            else -> run.event == "push" || run.event == "pull_request"
        }
        return Inspection(kind, belongs, current, compatible,
            artifact.digest?.let { digest.matches(it) } == true)
    }

    /** Default view: exact naming and current attempt, but never hide all evidence
     * behind a false assertion of trust. The "All" filter remains available. */
    fun currentAttempt(run: GitHubEcosystem.Run, artifacts: List<GitHubEcosystem.Artifact>) =
        artifacts.filter { inspect(run, it).currentAttempt }
}
