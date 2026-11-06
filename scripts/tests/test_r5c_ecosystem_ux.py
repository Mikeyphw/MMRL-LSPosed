"""R5C source guards; real Android compilation and UI tests run on hosted Actions."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / 'app/src/main/java/io/github/mikeyphw/mmrl/lsposed'
PAGE = SRC / 'EcosystemPages.kt'


class R5CEcosystemUxTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.src = PAGE.read_text(encoding='utf-8')
        cls.model = (SRC / 'EcosystemPresentation.kt').read_text(encoding='utf-8')

    def test_discover_uses_existing_official_catalog(self):
        self.assertIn('VectorModuleCatalog.cached(context.applicationContext)', self.src)
        self.assertIn('VectorModuleCatalog.load(context.applicationContext)', self.src)
        self.assertIn('VectorModuleCatalog.filter(catalog?.modules.orEmpty(), term)', self.src)
        self.assertIn('discover_show_more', self.src)
        self.assertIn('discover_clear_query', self.src)
        self.assertNotIn('results.take(250)', self.src)

    def test_saved_download_authority_is_not_changed(self):
        self.assertIn('VerifiedDownload.save(context.applicationContext, asset,', self.src)
        self.assertIn('VerifiedDownload.recheckSaved(context.applicationContext, selected)', self.src)
        self.assertIn('CiArtifactDownload.eligible(active, artifact)', self.src)
        self.assertIn('CiArtifactDownload.save(context.applicationContext, active, artifact,', self.src)
        self.assertIn('CiArtifactDownload.extractEvidence(context.applicationContext, entry)', self.src)
        self.assertIn('cancelSignal.set(true)', self.src)
        self.assertIn('No APK installation', self.src.replace('no APK installation', 'No APK installation'))

    def test_local_search_is_not_network_search(self):
        self.assertIn('EcosystemPresentation.matches(releaseQuery', self.src)
        self.assertIn('EcosystemPresentation.matches(runQuery', self.src)
        self.assertIn('EcosystemPresentation.matches(artifactQuery', self.src)
        self.assertIn('downloads_clear_query', self.src)
        self.assertIn('ci_artifact_clear', self.src)
        self.assertIn('fun matches(query: String, vararg fields: String)', self.model)
        self.assertNotIn('http', self.model)
        self.assertNotIn('VectorMutation', self.model)

    def test_tab_changes_preserve_list_types(self):
        self.assertIn('val requestedTab = tab', self.src)
        self.assertIn('if (tab == requestedTab)', self.src)
        self.assertNotIn('as List<', self.src)


if __name__ == '__main__':
    unittest.main()
