"""R2C safety and UI contracts: refuse URL/source drift and preserve legacy receipts."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'app/src/main/java/io/github/mikeyphw/mmrl/lsposed'

class ReleaseIdentityContracts(unittest.TestCase):
    def src(self, name):
        return (SRC / name).read_text()

    def test_release_url_is_bound_to_repository_tag_and_filename(self):
        policy = self.src('ReleaseIdentityPolicy.kt')
        for frag in ('fun releasePage(', 'fun asset(', 'validTag(tag)', 'validName(name)',
                     'releases/download/$tag/$name', 'url == "https://github.com/',
                     'REPOSITORY = "Mikeyphw/MMRL-LSPosed"'):
            self.assertIn(frag, policy)
        api = self.src('GitHubEcosystem.kt')
        self.assertIn('ReleaseIdentityPolicy.releasePage(tag, link)', api)
        self.assertIn('ReleaseIdentityPolicy.asset(releaseTag, name, url)', api)
        self.assertIn('Asset(file, size, rawDigest, url, tag)', api)
        self.assertIn('digestFormat.matches(digest)', api)

    def test_receipt_binds_source_sha_size_and_legacy_is_downgraded(self):
        code = self.src('VerifiedDownload.kt')
        for frag in ('receiptTag(', 'RECEIPT_SCHEMA', 'release_tag', 'asset_name',
                     'asset_url', 'repository', 'info.optLong("size", -1L) != size',
                     'filename != "${hash.take(16)}-$name"',
                     'if (info.length() == 2) "" else null',
                     'safeSavedFile(root, file)', 'sha256(file) != hash',
                     '!Files.isSymbolicLink(file.toPath())',
                     'ReleaseIdentityPolicy.asset(asset.releaseTag, asset.name, asset.url)',
                     'hasZipHeader(temp)', 'temp.delete()'):
            self.assertIn(frag, code)
        self.assertNotIn('Runtime.getRuntime()', code)
        self.assertNotIn('su -c', code)

    def test_handoff_rehash_is_async_and_apk_signer_not_claimed(self):
        ui = self.src('EcosystemPages.kt')
        self.assertIn('VerifiedDownload.recheckSaved(context.applicationContext, selected)', ui)
        self.assertIn('withContext(Dispatchers.IO)', ui)
        self.assertIn('Saved file or receipt changed', ui)
        self.assertIn('Legacy hash-only receipt', ui)
        self.assertIn('signing certificate are NOT qualified', ui)
        self.assertIn('saved = VerifiedDownload.Saved(file,', ui)
        self.assertIn('FileProvider.getUriForFile', ui)
        self.assertNotIn('VectorMutation.change(', ui)

    def test_regression_suite_includes_negative_cases(self):
        test = (ROOT/'app/src/test/java/io/github/mikeyphw/mmrl/lsposed/R2CReleaseIdentityTest.kt').read_text()
        for case in ('exactReleasePageAndArtifactBinding', 'sourceBoundReceiptRejectsTamperingAndPartialClaims',
                     'genuineLegacyReceiptIsPreservedWithoutSourceUpgrade',
                     'downloadableRequiresExactlyBoundReleaseMetadataAndHash'):
            self.assertIn(case, test)
        build = (ROOT/'app/build.gradle.kts').read_text()
        self.assertIn('applicationId = "io.github.mikeyphw.mmrl.lsposed"', build)
        flow = (ROOT/'.github/workflows/s01-hosted-devtool.yml').read_text()
        self.assertIn('Run independent Vector JVM protocol tests via native Devtool', flow)

if __name__ == '__main__':
    unittest.main()
