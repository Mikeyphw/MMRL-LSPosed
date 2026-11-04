"""S11 R2: cache-first rendering, accurate API diagnostics, GSI auto-connect, emulator coverage."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
SRC=ROOT/'app/src/main/java/io/github/mikeyphw/mmrl/lsposed'
class S11R2Audit(unittest.TestCase):
    def test_local_catalog_rendered_before_refresh_and_cache_reused_on_failure(self):
        page=(SRC/'EcosystemPages.kt').read_text()
        cache=(SRC/'VectorModuleCatalog.kt').read_text()
        self.assertIn('VectorModuleCatalog.cached(context.applicationContext)',page)
        self.assertLess(page.index('if (savedCatalog != null) catalog = savedCatalog'),page.index('load()\n    }'))
        self.assertIn('fun cached(context: Context)',cache)
        self.assertIn('val previous = cached(context)',cache)
        self.assertIn('instanceFollowRedirects = false',cache)
        self.assertNotIn('Authorization',cache)
    def test_unknown_host_timeout_and_http_auth_diagnoses_distinctly(self):
        api=(SRC/'GitHubEcosystem.kt').read_text()
        ui=(SRC/'MainActivity.kt').read_text()
        for term in ('UnknownHostException','SocketTimeoutException',
                     'status = connection.responseCode','401 ->','403, 429 ->',
                     'fun connectionDiagnostic(error: Throwable)'):
            self.assertIn(term,api)
        self.assertIn('GitHubEcosystem.connectionDiagnostic(it)',ui)
        self.assertNotIn('Unable to reach GitHub. Check connectivity or Private DNS.',ui)
    def test_gsi_fingerprint_does_not_suppress_autoconnect(self):
        settings=(SRC/'LocalSettings.kt').read_text()
        auto=settings[settings.index('fun isEmulator()'):settings.index('fun hasGitHubToken')]
        self.assertNotIn('Build.FINGERPRINT',auto)
        self.assertIn('Build.HARDWARE',auto)
        self.assertIn('Build.PRODUCT',auto)
    def test_nonroot_emulator_verifies_settings_controls(self):
        app=(ROOT/'app/src/androidTest/java/io/github/mikeyphw/mmrl/lsposed/S09EmulatorUiTest.kt').read_text()
        self.assertEqual(app.count('@Test fun '),4)
        for tag in ('auto_vector_connect','github_token_input','github_token_check'):
            self.assertIn('onNodeWithTag("'+tag+'").assertExists()',app)
        self.assertIn('assertNoImplicitVectorConnection()',app)
        self.assertNotIn('VectorMutation.change(',app)
if __name__=='__main__': unittest.main()
