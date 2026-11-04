"""S09 non-root hosted emulator UI gate: fail-closed source and host contracts."""
from __future__ import annotations
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
SPEC=importlib.util.spec_from_file_location('ci_emulator',ROOT/'scripts/ci_emulator.py')
ci=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ci)

class EmulatorGateContracts(unittest.TestCase):
    def test_ci_has_pinned_action_and_blocking_job(self):
        flow=(ROOT/'.github/workflows/s01-hosted-devtool.yml').read_text()
        self.assertIn('emulator-instrumentation:',flow)
        self.assertIn('needs: hosted-debug',flow)
        self.assertIn('needs: [hosted-debug, emulator-instrumentation]',flow)
        self.assertIn('ReactiveCircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d',flow)
        self.assertIn('api-level: 36',flow)
        self.assertIn('run --out',flow)
        self.assertIn('Build Android instrumentation + isolated Vector hook lab (hosted only)',flow)
        self.assertIn('mmrl-lsposed-pr-emulator-',flow)
        self.assertIn('mmrl-lsposed-emulator-api36-',flow)

    def test_emulator_class_cannot_trigger_s08_privileged_test(self):
        src=(ROOT/'app/src/androidTest/java/io/github/mikeyphw/mmrl/lsposed/S09EmulatorUiTest.kt').read_text()
        self.assertEqual(src.count('@Test fun '),4)
        self.assertIn('VectorUiSession::class.java',src)
        self.assertIn('Root connection must be explicit',src)
        self.assertIn('createAndroidComposeRule<MainActivity>()',src)
        self.assertIn('performTextInput("ci-test-absent-module")',src)
        for s in ('VectorMutation.change(', 'VectorReadOnly.inspect(',
                  'RootService.bind(', 'adb shell', 'scope add', 'modules enable',
                  'S08LabWriteDeviceTest(', 'S08ReadOnlyDeviceTest('):
            self.assertNotIn(s,src)
        self.assertEqual(ci.CLASS,'io.github.mikeyphw.mmrl.lsposed.S09EmulatorUiTest')

    def test_ui_test_has_stable_navigation_tags(self):
        src=(ROOT/'app/src/main/java/io/github/mikeyphw/mmrl/lsposed/MainActivity.kt').read_text()
        self.assertIn('Modifier.testTag("nav_${destination.name.lowercase()}")',src)
        self.assertIn('Modifier.testTag("vector_connect")',src)
        self.assertIn('Modifier.weight(1f).testTag("module_search")',src)
        self.assertIn('Modifier.testTag("open_settings")',src)

    def test_runner_rejects_false_junit_success(self):
        true='Time: 12.0\n\nOK (4 tests)\n'
        self.assertTrue(ci.junit_passed(0,true,''))
        self.assertTrue(ci.junit_passed(0,true+'INSTRUMENTATION_CODE: -1\n',''))
        for rc,stdout,stderr in ((1,true,''),(0,'OK (1 test)\n',''),
            (0,'OK (3 tests)\n',''),(0,'INSTRUMENTATION_CODE: -1\n',''),
            (0,true+'INSTRUMENTATION_CODE: 0\n',''),
            (0,true+'FAILURES!!!\n',''),
            (0,true,'INSTRUMENTATION_FAILED')):
            with self.subTest(rc=rc,stdout=stdout,stderr=stderr):
                self.assertFalse(ci.junit_passed(rc,stdout,stderr))

    def test_source_identity_required_and_no_secret_pr(self):
        good={'GITHUB_REPOSITORY':ci.REPO,'GITHUB_ACTIONS':'true','GITHUB_SHA':'a'*40,
              'GITHUB_RUN_ID':'1234','GITHUB_RUN_ATTEMPT':'1','GITHUB_EVENT_NAME':'pull_request'}
        with patch.dict('os.environ',good,clear=True):
            self.assertEqual(ci.expected_host(),('a'*40,1234,1,'pull_request'))
        for key in ('GITHUB_REPOSITORY','GITHUB_RUN_ID','GITHUB_SHA'):
            with patch.dict('os.environ',{k:v for k,v in good.items() if k!=key},clear=True):
                with self.assertRaises(ValueError):ci.expected_host()

    def test_pr_stage_only_pr_and_exact_files(self):
        with tempfile.TemporaryDirectory() as td:
            path=Path(td)/'not-a-real-apk.apk';path.write_bytes(b'0'*2048)
            self.assertEqual(ci.checked_file(path),path)
            path.rename(Path(td)/'different.txt')
            with self.assertRaises(ValueError):ci.checked_file(Path(td)/'different.txt')

    def test_verifier_does_not_invoke_root_or_vector(self):
        src=(ROOT/'scripts/ci_emulator.py').read_text()
        self.assertIn("'shell','am','instrument','-w','-e','class',CLASS",src)
        self.assertIn("'root_required':False",src)
        self.assertIn("'s08_physical_tests_executed':False",src)
        self.assertIn("'vector_writes_attempted':0",src)
        for prohibited in ('--authorize-lab-changes','scope add','modules enable',
                           'scripts/s08_device_lab.py','S08LabWriteDeviceTest'):
            self.assertNotIn(prohibited,src)

    def test_pr_packaging_checks_manifest_signer_and_target(self):
        import hashlib
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            app=root/'app-debug.apk';app.write_bytes(b'A'*4096)
            test=root/'app-debug-androidTest.apk';test.write_bytes(b'B'*4096)
            pair=root/'stage'
            def checked(path):return app if str(path).endswith('/debug/app-debug.apk') else test
            with patch.object(ci,'expected_host',return_value=('a'*40,10,1,'pull_request')), \
                 patch.object(ci,'checked_file',side_effect=checked), \
                 patch.object(ci,'package_badging',side_effect=[
                    (ci.PACKAGE,None,None),(ci.TEST_PACKAGE,ci.PACKAGE,ci.RUNNER)]), \
                 patch.object(ci,'signing_certificate',side_effect=['b'*64,'b'*64]):
                ci.stage_pr(pair)
            doc=json.loads((pair/'pr-receipt.json').read_text())
            self.assertEqual(doc['certificate_sha256'],'b'*64)
            self.assertEqual(doc['app_package'],ci.PACKAGE)
            self.assertEqual(doc['instrumentation_target'],ci.PACKAGE)
            self.assertEqual(doc['test']['size'],4096)

    def test_pr_packaging_refuses_wrong_target_or_mismatched_signer(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);app=root/'app-debug.apk';app.write_bytes(b'A'*4096)
            test=root/'app-debug-androidTest.apk';test.write_bytes(b'B'*4096)
            def checked(path):return app if str(path).endswith('/debug/app-debug.apk') else test
            with patch.object(ci,'expected_host',return_value=('a'*40,10,1,'pull_request')), \
                 patch.object(ci,'checked_file',side_effect=checked), \
                 patch.object(ci,'package_badging',side_effect=[
                    (ci.PACKAGE,None,None),(ci.TEST_PACKAGE,'foreign.target',ci.RUNNER)]):
                with self.assertRaisesRegex(ValueError,'package/runner'):
                    ci.stage_pr(root/'stage1')
            with patch.object(ci,'expected_host',return_value=('a'*40,10,1,'pull_request')), \
                 patch.object(ci,'checked_file',side_effect=checked), \
                 patch.object(ci,'package_badging',side_effect=[
                    (ci.PACKAGE,None,None),(ci.TEST_PACKAGE,ci.PACKAGE,ci.RUNNER)]), \
                 patch.object(ci,'signing_certificate',side_effect=['a'*64,'b'*64]):
                with self.assertRaisesRegex(ValueError,'signer differs'):
                    ci.stage_pr(root/'stage2')

    def test_manifest_package_and_signer_extraction(self):
        sample=("package: name='io.github.mikeyphw.mmrl.lsposed.debug.test' versionCode='1'\n"
                "instrumentation: name='androidx.test.runner.AndroidJUnitRunner' "
                "targetPackage='io.github.mikeyphw.mmrl.lsposed.debug'\n")
        with patch.object(ci,'host_android_tool',return_value=Path('/mock/aapt')), \
             patch.object(ci,'run_checked',return_value=SimpleNamespace(stdout=sample)):
            self.assertEqual(ci.package_badging(Path('test.apk')),
                (ci.TEST_PACKAGE,ci.PACKAGE,ci.RUNNER))
        with patch.object(ci,'host_android_tool',return_value=Path('/mock/apksigner')), \
             patch.object(ci,'run_checked',return_value=SimpleNamespace(stdout=(
                 'Signer #1 certificate SHA-256 digest: '+'c'*64+'\n'))):
            self.assertEqual(ci.signing_certificate(Path('test.apk')),'c'*64)

if __name__=='__main__':unittest.main()
