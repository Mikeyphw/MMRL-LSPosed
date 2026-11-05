"""R2B read-only module version comparison: source-level fail-closed contracts."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'app/src/main/java/io/github/mikeyphw/mmrl/lsposed'

class R2BModuleUpdateSourceContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.logic = (SRC / 'ModuleVersionIndex.kt').read_text()
        cls.ui = (SRC / 'MainActivity.kt').read_text()
        cls.catalog = (SRC / 'VectorModuleCatalog.kt').read_text()
        cls.test = (ROOT / 'app/src/test/java/io/github/mikeyphw/mmrl/lsposed/R2BModuleVersionIndexTest.kt').read_text()

    def test_only_strict_numeric_comparisons_can_count_as_newer(self):
        self.assertIn('VERSION.matchEntire(raw)', self.logic)
        self.assertIn('if (a == null || b == null) return Assessment(Verdict.NEEDS_MANUAL_REVIEW', self.logic)
        self.assertIn('comparison < 0 -> Verdict.NEWER_IN_CATALOG', self.logic)
        self.assertIn('Verdict.NEWER_IN_CATALOG', self.logic)
        self.assertIn('candidate.packageName', self.logic)
        self.assertIn('exactPackageIdentityAndVerifiedComparisonCountOnly', self.test)

    def test_no_privileged_operations_or_downloads_in_comparison(self):
        for prohibited in (
            'VectorMutation.', 'VectorReadOnly.', 'VectorRootTransport.',
            'RootService.', 'HttpURLConnection', 'URL(', 'VerifiedDownload.',
            'startActivity(', 'adb ', 'am instrument', 'KeyStore',
        ):
            self.assertNotIn(prohibited, self.logic)
        self.assertIn('VectorModuleCatalog.cached(context.applicationContext)', self.ui)
        self.assertNotIn('VectorModuleCatalog.load(context.applicationContext)', self.ui)

    def test_modules_have_update_indicator_and_clear_provenance_caveat(self):
        self.assertIn('modules_updates_summary', self.ui)
        self.assertIn('module_catalog_newer_${module.packageName}', self.ui)
        self.assertIn('verify publisher and signing', self.ui)
        self.assertIn('publisher and APK signature not verified', self.ui)
        self.assertIn('ModuleVersionIndex.forInstalled', self.ui)
        self.assertIn('ModuleVersionIndex.comparableCount', self.ui)
        self.assertIn('ModuleVersionIndex.newerCount', self.ui)

    def test_ambiguous_versions_cannot_raise_fake_update_count(self):
        self.assertIn('prereleasesAndNonNumericTagsNeverBecomeConfirmedUpdates', self.test)
        self.assertIn('cacheAbsenceOrUnlistedModuleNeverInventsAnUpdate', self.test)
        self.assertIn('exactPackageIdentityAndVerifiedComparisonCountOnly', self.test)

    def test_no_catalog_network_or_signer_mutations(self):
        self.assertIn('If-None-Match', self.catalog)
        self.assertIn('savedModules != null && validator != null', self.catalog)
        self.assertIn('ModuleVersionIndex.Assessment', self.ui)

if __name__ == '__main__': unittest.main()
