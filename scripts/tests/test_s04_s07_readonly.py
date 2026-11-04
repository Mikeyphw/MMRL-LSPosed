"""Offline safety contracts; JVM parser tests run through native Devtool CI."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'app/src/main/java/io/github/mikeyphw/mmrl/lsposed'


class ReadOnlyVectorContracts(unittest.TestCase):
    def test_exact_three_privileged_operations(self):
        service = (SRC / 'VectorCliRootService.kt').read_text()
        self.assertEqual(len(re.findall(r'"(status|modules ls|scope ls)"\s*->', service)), 3)
        for write in ('"modules enable" ->', '"modules disable" ->',
                      '"scope add" ->', '"scope rm" ->'):
            self.assertEqual(service.count(write), 1)
        for disallowed in ('"scope set" ->', '"config set" ->', '"db reset" ->'):
            self.assertNotIn(disallowed, service)
        self.assertIn('Process.myUid() != 0', service)
        self.assertIn('"/data/adb/modules/zygisk_vector/cli"', service)
        self.assertIn('"/sbin/.magisk/modules/zygisk_vector/cli"', service)
        self.assertIn('if (valid(module))', service)
        self.assertIn('"--json"', service)

    def test_libsu_service_is_not_regular_android_service(self):
        manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
        app = manifest.find('application')
        self.assertEqual(app.findall('service'), [])
        self.assertEqual(app.findall('receiver'), [])
        aidl = (ROOT / 'app/src/main/aidl/io/github/mikeyphw/mmrl/lsposed/IVectorReadOnly.aidl').read_text()
        self.assertIn('String query(String operation, String modulePackage);', aidl)
        self.assertIn('String mutate(String operation, String modulePackage, String targetPackage);', aidl)
        self.assertNotIn('execute(String', aidl)

    def test_no_shell_or_root_binding_in_ui(self):
        for name in ('MainActivity.kt', 'ScopeDraftPage.kt', 'VectorReadOnly.kt',
                     'VectorProtocol.kt', 'ModuleDiscovery.kt'):
            source = (SRC / name).read_text()
            with self.subTest(name=name):
                for denied in ('ProcessBuilder(', 'RootService.bind', 'Shell.cmd(',
                               'Runtime.getRuntime()', 'scope set'):

                    self.assertNotIn(denied, source)
        transport = (SRC / 'VectorRootTransport.kt').read_text()
        self.assertIn('Looper.getMainLooper()', transport)
        self.assertIn('CountDownLatch', transport)
        self.assertIn('17, TimeUnit.SECONDS', transport)
        self.assertIn('toByteArray(Charsets.UTF_8).size', transport)
        self.assertIn('if (!permitted)', transport)

    def test_protocol_validates_every_response(self):
        model = (SRC / 'VectorProtocol.kt').read_text()
        for evidence in ('api != 102', 'VERSION.matches(version)', '"PACKAGE"', '"STATUS"',
                         '"APP_PACKAGE"', '"USER_ID"', 'rows.distinct()',
                         'response.optBoolean("success", false)'):
            self.assertIn(evidence, model)
        self.assertNotRegex(model, r'fun\s+(?:change|enable|disable|setScope|addScope|removeScope)\(')
        junit = ROOT / 'app/src/test/java/io/github/mikeyphw/mmrl/lsposed/VectorProtocolTest.kt'
        self.assertTrue(junit.is_file())
        self.assertIn('@Test', junit.read_text())
        self.assertIn('assertThrows', junit.read_text())

    def test_declared_apk_scope_and_split_discovery(self):
        scanner = (SRC / 'ModuleDiscovery.kt').read_text()
        for entry in ('splitSourceDirs', 'module.prop', 'scope.list', 'staticScope',
                      'xposedscope', 'xposedminversion', 'native_init.list',
                      'MAX_METADATA_BYTES', 'getInstalledApplications'):
            self.assertIn(entry, scanner)

    def test_scope_draft_review_is_explicit_and_single_apply(self):
        editor = (SRC / 'ScopeDraftPage.kt').read_text()
        for item in ('TargetTab.SUGGESTED', 'TargetTab.SELECTED', 'AlertDialog(',
                     'Review draft', 'Discard changes', 'rememberSaveable',
                     'Checkbox(', 'managerPackages', 'staticScope', 'Other Android user'):
            self.assertIn(item, editor)
        for denied in ('submitChanges', 'writeScope', 'RootService', 'VectorRootTransport'):
            self.assertNotIn(denied, editor)
        activity = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('if (vectorRefresh == 0) return@LaunchedEffect', activity)
        self.assertIn('onConnect = { vectorRefresh++ }', activity)
        self.assertIn('if (scopeOpen && selectedModule?.packageName == pkg)', activity)
        self.assertIn('VectorMutation.change(', editor)
        self.assertIn('Confirm single change', editor)

    def test_ci_keeps_signing_and_adds_real_jvm_tests(self):
        flow = (ROOT / '.github/workflows/s01-hosted-devtool.yml').read_text()
        self.assertIn('scripts/hosted_devtool.py run --node apk', flow)
        self.assertIn('scripts/hosted_devtool.py run --node lint', flow)
        self.assertIn('scripts/hosted_devtool.py run --node test', flow)
        self.assertIn('scripts/hosted_debug_signing.py', flow)
        self.assertIn('actions/attest@', flow)
        self.assertLess(flow.index('--node test'), flow.index('scripts/hosted_debug_signing.py'))
        # Devtool owns APK, lint and JVM tasks. Its reviewed provider does not
        # support :app:assembleDebugAndroidTest, so allow exactly one hosted
        # standalone instrumentation/lab build group and reject any other direct Gradle.
        direct = re.findall(r'(?m)^\s+(?:gradle|\./gradlew)\s.*$', flow)
        self.assertEqual([x.strip() for x in direct], [
            './gradlew --no-daemon :app:assembleDebugAndroidTest :hook-lab-module:assembleDebug :hook-lab-target:assembleDebug'
        ])
        devtool = (ROOT / '.devtool.toml').read_text()
        self.assertIn('[targets.lsposed_app.jobs.unit_debug]', devtool)
        self.assertIn('gradle_tasks = [":app:testDebugUnitTest"]', devtool)
        graph = (ROOT / 'scripts/hosted_devtool.py').read_text()
        self.assertIn('"node-03"', graph)
        self.assertIn('TEST_TASK', graph)

    def test_english_only_and_no_vector_binaries_bundled(self):
        self.assertFalse(list((ROOT / 'app/src/main/res').glob('values-*/strings.xml')))
        self.assertFalse(list(ROOT.rglob('manager.apk')))
        self.assertFalse(list(ROOT.rglob('daemon.apk')))
        root = (SRC / 'VectorCliRootService.kt').read_text()
        self.assertNotIn('com.dergoogler.mmrl.', root)
        self.assertIn('query(operation:', root)


if __name__ == '__main__':
    unittest.main()
