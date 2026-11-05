"""S11 regressions: direct scope taps, Vector catalog, opt-out auto-connect and secrets."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT/'app/src/main/java/io/github/mikeyphw/mmrl/lsposed'

class S11Contracts(unittest.TestCase):
    def source(self, file): return (SRC/file).read_text()

    def test_scope_immediate_single_change_still_journaled_and_read_back(self):
        page = self.source('ScopeDraftPage.kt')
        mutation = self.source('VectorMutation.kt')
        for marker in ('onCheckedChange = { toggle() }','onWriteBusy(true)',
                       'VectorMutation.Request(', 'VectorMutation.change(',
                       'onCommitted(it)', 'VectorMutation.Action.ADD_SCOPE',
                       'VectorMutation.Action.REMOVE_SCOPE', 'scope_enable_module'):
            self.assertIn(marker,page)
        self.assertNotIn('showReview',page)
        self.assertNotIn('confirmTarget',page)
        self.assertNotIn('draftSet',page)
        self.assertNotIn('VectorRootTransport.mutate(',page)
        self.assertIn('VectorWriteJournal.begin(',mutation)
        self.assertIn('VectorWritePolicy.verifyReadBack(',mutation)
        self.assertIn('if (request.action == Action.ADD_SCOPE && !module.enabled)',mutation)

    def test_automatic_connection_is_only_readonly_and_one_shot(self):
        main = self.source('MainActivity.kt')
        session = self.source('VectorUiSession.kt')
        settings = self.source('LocalSettings.kt')
        self.assertIn('session.autoConnectAttempted',main)
        self.assertIn('!LocalSettings.isEmulator()',main)
        self.assertIn('if (autoConnect && !LocalSettings.isEmulator()',main)
        self.assertIn('var autoConnectAttempted = false',session)
        self.assertIn('"auto_connect_vector"',settings)
        self.assertIn('getBoolean(AUTO_CONNECT, true)',settings)
        self.assertIn('setAutoConnect(',settings)
        self.assertIn('"auto_vector_connect"', main)
        self.assertNotIn('VectorMutation.change(',main[main.index('LaunchedEffect(session)'):main.index('LaunchedEffect(refreshKey)')])

    def test_official_vector_catalog_is_bounded_and_cached_without_token(self):
        src=self.source('VectorModuleCatalog.kt')
        for marker in ('https://backup.modules.lsposed.org/modules.json',
            'MAX_BYTES = 6 * 1024 * 1024','rows.length() > 2500',
            'val saved = cacheText(cache)','Result(parse(saved)',
            'row.optBoolean("hide")',
            'releases.length() == 0', 'instanceFollowRedirects = false',
            '"Search LSPosed modules"'):
            if marker == '"Search LSPosed modules"':
                self.assertIn(marker,self.source('EcosystemPages.kt'))
            else:self.assertIn(marker,src)
        self.assertNotIn('api.github.com',src)
        self.assertNotIn('Authorization',src)
        self.assertNotIn('VectorRootTransport',src)

    def test_github_token_encryption_scoped_header_and_unexposed_in_ui(self):
        settings=self.source('LocalSettings.kt')
        api=self.source('GitHubEcosystem.kt')
        main=self.source('MainActivity.kt')
        for marker in ('AndroidKeyStore','AES/GCM/NoPadding',
                'GCMParameterSpec(128,','KeyProperties.PURPOSE_ENCRYPT',
                'KeyProperties.PURPOSE_DECRYPT','Base64.NO_WRAP',
                'fun clearGitHubToken(', 'gitHubToken(context: Context)'):
            self.assertIn(marker,settings)
        self.assertIn('PasswordVisualTransformation()',main)
        self.assertIn('"github_token_input"',main)
        self.assertIn('"github_token_check"',main)
        self.assertIn('url.host != "api.github.com"',api)
        self.assertIn('instanceFollowRedirects = false',api)
        self.assertIn('"Authorization", "Bearer $token"',api)
        self.assertNotIn('setRequestProperty("Authorization"',self.source('VectorModuleCatalog.kt'))
        self.assertIn('android:allowBackup="false"',(ROOT/'app/src/main/AndroidManifest.xml').read_text())

if __name__=='__main__':unittest.main()
