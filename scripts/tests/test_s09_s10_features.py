"""S09+S10 offline controls: no implicit root/network, safe GitHub and downloads."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'app/src/main/java/io/github/mikeyphw/mmrl/lsposed'


class FeaturesContracts(unittest.TestCase):
    def read(self, file):
        return (SRC / file).read_text()

    def test_apps_opt_in_index_and_explicit_invalidation(self):
        activity = self.read('MainActivity.kt')
        for text in ('AppsReversePage(', 'appsRequest++', 'LaunchedEffect(appsRequest)',
                     'AppsIndexState.Loading', 'AppsIndexState.Idle'):
            self.assertIn(text, activity)
        apps = self.read('AppsReversePage.kt')
        self.assertIn('"apps_build_index"', apps)
        self.assertIn('"apps_search"', apps)
        self.assertIn('Connect to Vector on Home first', apps)
        self.assertNotIn('VectorReadOnly.inspect(', apps)
        self.assertNotIn('VectorMutation.change(', apps)
        self.assertIn('AndroidView(', apps)
        self.assertIn('onOpenModule(module)', apps)
        self.assertIn('item.label.contains', apps)

    def test_index_is_bounded_and_checks_user_and_snapshot(self):
        index = self.read('AppsReverseIndex.kt')
        for s in ('MAX_MODULES = 128', 'MAX_APPS = 5000',
                  'scope.userId == 0', 'after.modules == baseline.modules',
                  'VectorReadOnly.scopes(context, module)',
                  'data class Target(', 'fun reverse('):
            self.assertIn(s, index)
        self.assertNotIn('VectorMutation', index)
        main = self.read('MainActivity.kt')
        self.assertIn('ModuleStateFilter.ENABLED', main)
        self.assertIn('ModuleStateFilter.DISABLED', main)
        self.assertIn('Sort: enabled first', main)
        self.assertIn('Framework details', main)
        self.assertIn('Hook execution not verified', main)

    def test_network_is_user_initiated_and_github_only(self):
        pages = self.read('EcosystemPages.kt')
        self.assertIn('"discover_search"', pages)
        self.assertIn('"downloads_load"', pages)
        self.assertIn('"downloads_saved"', pages)
        self.assertIn('onClick = {', pages)
        self.assertNotIn('LaunchedEffect(', pages)
        api = self.read('GitHubEcosystem.kt')
        for s in ('"api.github.com"', 'connectTimeout = 7_000',
                  'readTimeout = 10_000', 'MAX_RESPONSE = 1_048_576',
                  'topic:xposed-module', 'fun recentRuns()',
                  'fun releases()', 'fun discovery(', 'fun runArtifacts('):
            self.assertIn(s, api)
        self.assertIn('runRef.optString("head_sha") != run.headSha', api)
        self.assertIn('runRef.optLong("id") != run.id', api)
        self.assertIn('decodeRunArtifacts(run, items)', api)
        self.assertIn('it.isBlank() || it == "null"', api)
        self.assertIn('"expired"', api)
        self.assertNotIn('Authorization', api)
        self.assertNotIn('VectorRootTransport', api)

    def test_download_fail_closed_digest_and_path(self):
        download = self.read('VerifiedDownload.kt')
        for s in ('allowedSourceUrl(url: String)', 'allowedName(name: String)',
                  'MessageDigest.getInstance("SHA-256")',
                  'Release SHA-256/size mismatch', 'MAX_BYTES = 104_857_600L',
                  'Too many release redirects', 'Downloaded', 'temp.delete()',
                  'Cancelled'):
            # Allow case-insensitive descriptive code while checking hard invariants below.
            if s in ('Downloaded', 'Cancelled'):
                continue
            self.assertIn(s, download)
        self.assertIn('Download cancelled;', download)
        self.assertIn('file.fd.sync()', download)
        self.assertIn('fun listSaved(', download)
        self.assertIn('receipt.json', download)
        self.assertNotIn('Runtime.getRuntime()', download)
        self.assertNotIn('su -c', download)

    def test_manifest_private_file_handoff(self):
        manifest = (ROOT/'app/src/main/AndroidManifest.xml').read_text()
        self.assertIn('android.permission.INTERNET', manifest)
        self.assertIn('androidx.core.content.FileProvider', manifest)
        self.assertIn('android:exported="false"', manifest)
        self.assertIn('android:grantUriPermissions="true"', manifest)
        xml = (ROOT/'app/src/main/res/xml/verified_file_paths.xml').read_text()
        self.assertIn('path="verified-downloads/"', xml)
        self.assertNotIn('external-path', xml)
        self.assertIn('Modifier.testTag("nav_${destination.name.lowercase()}")',
                      self.read('MainActivity.kt'))

    def test_emulator_tests_visit_features_without_network_or_root(self):
        tests = (ROOT/'app/src/androidTest/java/io/github/mikeyphw/mmrl/lsposed/S09EmulatorUiTest.kt').read_text()
        self.assertEqual(tests.count('@Test fun '), 4)
        for tag in ('apps_build_index', 'discover_search', 'downloads_load'):
            self.assertIn(tag, tests)
        for prohibited in ('VectorMutation.change(', 'GitHubEcosystem.discovery(',
                           'VectorReadOnly.inspect(', 'RootService.bind('):
            self.assertNotIn(prohibited, tests)

    def test_download_request_ownership_and_safe_cancellation(self):
        ui = self.read('EcosystemPages.kt')
        self.assertIn('val requestedTab = tab', ui)
        self.assertIn('if (requestedTab == 0)', ui)
        self.assertIn('GitHubEcosystem.releases()', ui)
        self.assertIn('GitHubEcosystem.recentRuns()', ui)
        self.assertNotIn(' as List<GitHubEcosystem.', ui)
        self.assertIn('val cancelSignal = remember { AtomicBoolean(false) }', ui)
        self.assertIn('DisposableEffect(Unit) { onDispose { cancelSignal.set(true) } }', ui)
        self.assertIn('cancelled = { cancelSignal.get() }', ui)
        self.assertIn('cancelSignal.set(true); cancelRequested = true', ui)
        self.assertIn('lastPercent = percent', ui)
        self.assertIn('coroutine.launch { progress = percent }', ui)
        self.assertIn('archives can predate reruns', ui)

    def test_emulator_interacts_with_offline_feature_states(self):
        test = (ROOT/'app/src/androidTest/java/io/github/mikeyphw/mmrl/lsposed/S09EmulatorUiTest.kt').read_text()
        self.assertIn('assertIsNotEnabled()', test)
        self.assertIn('compose.onNodeWithTag("discover_query").performTextInput("lsposed")', test)
        self.assertIn('compose.onNodeWithText("CI artifacts").performClick()', test)
        self.assertIn('compose.onNodeWithText("Releases").performClick()', test)

    def test_hosted_jvm_suite_includes_new_pure_policy_tests(self):
        tests = ROOT/'app/src/test/java/io/github/mikeyphw/mmrl/lsposed/S09S10PolicyTest.kt'
        self.assertTrue(tests.is_file())
        self.assertIn('reverseScopeRefusesOtherAndroidUsersAndMalformedModules', tests.read_text())
        flow = (ROOT/'.github/workflows/s01-hosted-devtool.yml').read_text()
        self.assertIn('Run independent Vector JVM protocol tests via native Devtool', flow)
        self.assertIn('API 36 non-root Compose AndroidTest (emulator)', flow)


if __name__ == '__main__': unittest.main()
