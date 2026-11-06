"""N05B: offline exact-source Gradle dependency and trust-boundary contracts."""
from __future__ import annotations

from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
EXPECTED_SHA = "9e09e1267099ef6a015f0c4bb1dcd31e3471d0ee"
SUB = "third_party/mmrl-shared"


def git(*args: str) -> str:
    return subprocess.check_output(["git", "-C", str(ROOT), *args], text=True).strip()


class SharedCoreSourceContract(unittest.TestCase):
    def test_shared_code_is_an_exact_gitlink_not_copied_source(self):
        index = git("ls-files", "--stage", "--", SUB)
        self.assertRegex(index, r"^160000 [0-9a-f]{40} 0\s+" + re.escape(SUB) + r"$")
        self.assertEqual(index.split()[1], EXPECTED_SHA)
        checkout = ROOT / SUB
        self.assertTrue((checkout / ".git").exists(), "submodule checkout required, not an empty folder")
        self.assertEqual(subprocess.check_output(["git", "-C", str(checkout), "rev-parse", "HEAD"], text=True).strip(), EXPECTED_SHA)
        settings = (ROOT / ".gitmodules").read_text()
        self.assertIn(f"path = {SUB}", settings)
        self.assertIn("url = https://github.com/Mikeyphw/MMRL-Shared.git", settings)

    def test_gradle_owns_separate_android_library_dependency(self):
        settings = (ROOT / "settings.gradle.kts").read_text()
        self.assertIn('include(":shared-contracts")', settings)
        self.assertIn('project(":shared-contracts").projectDir = file("integration/shared-contracts")', settings)
        app = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn('implementation(project(":shared-contracts"))', app)
        adapter = (ROOT / "integration/shared-contracts/build.gradle.kts").read_text()
        self.assertIn('alias(libs.plugins.android.library)', adapter)
        self.assertIn('third_party/mmrl-shared/src/main/kotlin', adapter)
        self.assertIn('sourceSets.named("main")', adapter)
        self.assertIn('kotlin.directories.add(', adapter)
        self.assertNotIn('java.srcDir(', adapter)
        self.assertNotIn('mavenLocal()', adapter)
        self.assertNotIn('exec {', adapter)

    def test_release_identity_delegates_without_permissive_fallback(self):
        src = (ROOT / "app/src/main/java/io/github/mikeyphw/mmrl/lsposed/ReleaseIdentityPolicy.kt").read_text()
        self.assertIn('GitHubRepository.parse(REPOSITORY)', src)
        self.assertIn('ReleaseAssetIdentity.of(', src)
        self.assertIn('.matchesAssetUrl(url)', src)
        self.assertIn('.matchesReleasePage(url)', src)
        self.assertNotIn('Regex(', src)
        self.assertNotIn('||', src)
        test = (ROOT / "app/src/test/java/io/github/mikeyphw/mmrl/lsposed/N05BSharedCoreTest.kt").read_text()
        for term in ("releaseIdentityIsDelegatedToPinnedSharedCore", "sharedCiIdentityRemainsMetadataOnlyAndAttemptBound"):
            self.assertIn(term, test)

    def test_every_hosted_checkout_fetches_pinned_submodules(self):
        for filename in ("s01-hosted-devtool.yml", "r4d-supply-chain-qualification.yml", "r4b-isolated-release-candidate.yml", "r4c-device-acceptance-readonly.yml"):
            workflow = (ROOT / ".github/workflows" / filename).read_text()
            count = workflow.count('uses: actions/checkout@')
            self.assertGreater(count, 0)
            self.assertEqual(workflow.count('submodules: recursive'), count, filename)
            self.assertEqual(workflow.count('persist-credentials: false'), count, filename)

    def test_root_privilege_boundaries_not_changed(self):
        manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
        self.assertNotIn('io.github.mikeyphw.mmrl.shared.android', manifest)
        doc = (ROOT / 'docs/N05B_SHARED_CORE.md').read_text()
        self.assertIn('No root', doc)
        self.assertIn('PR #15', doc)


if __name__ == '__main__':
    unittest.main()
