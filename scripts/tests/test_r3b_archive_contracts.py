"""R3B static fail-closed network, ZIP and source constraints; no Gradle in Termux."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
SRC=ROOT/'app/src/main/java/io/github/mikeyphw/mmrl/lsposed'

class R3BContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archive=(SRC/'CiArtifactDownload.kt').read_text()
        cls.api=(SRC/'GitHubEcosystem.kt').read_text()
        cls.ui=(SRC/'EcosystemPages.kt').read_text()

    def test_token_restricted_to_first_api_connection(self):
        src=self.archive
        self.assertEqual(src.count('setRequestProperty("Authorization", "Bearer $token")'),1)
        self.assertIn('https://api.github.com/repos/$REPOSITORY/actions/artifacts/$id/zip',src)
        self.assertIn('if (!allowedStorageRedirect(redirect))',src)
        self.assertIn('instanceFollowRedirects = false',src)
        self.assertIn('Add a GitHub token with Actions: read access',src)
        self.assertIn('setRequestProperty("User-Agent", "MMRL-LSPosed/0.1")',src)

    def test_request_time_identity_and_digest_checks(self):
        s=self.archive
        for needle in ('GitHubEcosystem.freshRun(run, context)',
                       'GitHubEcosystem.runArtifacts(run, context).singleOrNull { it.id == artifact.id }',
                       'inspection.currentAttempt && inspection.eventCompatible',
                       '"sha256:$hash" != artifact.digest',
                       'length != artifact.bytes',
                       'artifact.expired', 'published_digest', 'independently_attested'):
            self.assertIn(needle,s)

    def test_no_background_or_implicit_binary_install(self):
        s=self.archive+self.ui
        for forbidden in ('PackageInstaller', 'Runtime.getRuntime()', 'su -c', 'VectorMutation.change(',
                          'adb install', 'setRequestProperty("Authorization", "Bearer $token")\n            // forwarded'):
            self.assertNotIn(forbidden,s)
        self.assertIn('modifier = Modifier.testTag("ci_archive_download")',s)
        self.assertIn('modifier = Modifier.testTag("ci_archive_extract_evidence")',s)

    def test_archive_custody_is_private_and_bounded(self):
        s=self.archive
        for needle in ('filesDir, "ci-archives"', 'Files.isSymbolicLink', 'MAX_ARCHIVE',
                       'MAX_ENTRIES', 'MAX_TOTAL_UNPACKED', 'ZipFile(file)',
                       'MAX_EVIDENCE', 'MAX_EVIDENCE_TOTAL',
                       'entry.size', 'crc.value != entry.crc', 'sourceSha'):
            self.assertIn(needle,s)
        self.assertNotIn('FileProvider.getUriForFile',s)
        self.assertNotIn('Intent.ACTION_INSTALL_PACKAGE',s)

    def test_attempt_confusion_refused_by_api(self):
        self.assertIn('obj.optInt("run_attempt", 0) == run.attempt',self.api)
        self.assertIn('obj.optString("head_sha") == run.headSha',self.api)
        self.assertIn('obj.optString("status") == "completed"',self.api)
        self.assertIn('obj.optString("conclusion") == "success"',self.api)

    def test_android_jvm_tests_cover_attack_vectors(self):
        s=(ROOT/'app/src/test/java/io/github/mikeyphw/mmrl/lsposed/R3BArtifactArchiveTest.kt').read_text()
        for key in ('onlyCompletedCurrentAttemptDigestBoundArtifactsAreDownloadable',
                    'credentialCannotBeSentToUntrustedRedirectHost',
                    'zipPathsFailClosedBeforeExtraction','zipInspectionRejectsTraversalAndPreservesSafeNames',
                    'receiptRequiresExactSourceRunArtifactDigestAndExplicitUnqualifiedFlags',
                    'freshRunRejectsSilentRerunAndAlteredSha'):
            self.assertIn(key,s)

if __name__=='__main__': unittest.main()
