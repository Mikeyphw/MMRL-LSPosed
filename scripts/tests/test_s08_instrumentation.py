"""Source + host logic contracts for S08 instrumentation (no Android execution)."""
from pathlib import Path
import importlib.util
import re
import unittest
from unittest.mock import patch
import tempfile
import json

ROOT = Path(__file__).resolve().parents[2]
LAB = ROOT/'scripts/s08_device_lab.py'
spec=importlib.util.spec_from_file_location('s08_device_lab', LAB)
mod=importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

class S08InstrumentationContracts(unittest.TestCase):
    def test_three_isolated_packages_and_static_scope(self):
        settings=(ROOT/'settings.gradle.kts').read_text()
        self.assertIn('include(":hook-lab-module", ":hook-lab-target")', settings)
        entry=(ROOT/'hook-lab-module/src/main/resources/META-INF/xposed/java_init.list').read_text().strip()
        scope=(ROOT/'hook-lab-module/src/main/resources/META-INF/xposed/scope.list').read_text().splitlines()
        prop=(ROOT/'hook-lab-module/src/main/resources/META-INF/xposed/module.prop').read_text()
        self.assertEqual(entry,'com.mikeyphw.lsposed.hooklab.module.LabHookModule')
        self.assertEqual(scope,[mod.TARGET])
        self.assertIn('targetApiVersion=102',prop)
        self.assertIn('staticScope=true',prop)
        self.assertNotIn('com.mikeyphw.mmrl.hooklab.',str(ROOT/'hook-lab-module'))

    def test_no_arbitrary_module_hook(self):
        hook=(ROOT/'hook-lab-module/src/main/java/com/mikeyphw/lsposed/hooklab/module/LabHookModule.java').read_text()
        self.assertIn('TARGET.equals(param.getPackageName())',hook)
        self.assertIn('param.isFirstPackage()',hook)
        self.assertIn('getDeclaredMethod("readValue")',hook)
        self.assertNotIn('system_server',hook)

    def test_hosted_only_and_no_unattended_device_writes(self):
        flow=(ROOT/'.github/workflows/s01-hosted-devtool.yml').read_text()
        self.assertIn(':app:assembleDebugAndroidTest',flow)
        self.assertIn(':hook-lab-module:assembleDebug',flow)
        self.assertIn(':hook-lab-target:assembleDebug',flow)
        self.assertIn('scripts/hosted_s08_lab.py',flow)
        self.assertIn('Attest exact S08 lab and test APK bytes',flow)
        self.assertIn('Mikeyphw/MMRL-LSPosed', (ROOT/'scripts/hosted_s08_lab.py').read_text())
        self.assertNotIn('adb -s ',flow)
        self.assertNotIn('am instrument',flow)
        self.assertIn("github.event_name == 'push' && github.ref == 'refs/heads/main'",flow)

    def test_device_lab_requires_one_time_token(self):
        source=(ROOT/'app/src/androidTest/java/io/github/mikeyphw/mmrl/lsposed/S08LabWriteDeviceTest.kt').read_text()
        self.assertIn('token.delete()',source)
        self.assertIn('VectorWriteJournal.pending(ctx) == null',source)
        self.assertIn('candidate.staticScope',source)
        self.assertIn('VectorMutation.change(ctx',source)
        self.assertIn('PASS_LAB_WRITE_AND_RESTORE',source)
        self.assertIn('RECOVERY_REQUIRED',source)
        self.assertNotIn('scope set',source)

    def test_read_only_device_test_has_no_mutation(self):
        source=(ROOT/'app/src/androidTest/java/io/github/mikeyphw/mmrl/lsposed/S08ReadOnlyDeviceTest.kt').read_text()
        self.assertIn('VectorReadOnly.inspect',source)
        self.assertIn('VectorReadOnly.scopes',source)
        self.assertNotIn('VectorMutation',source)
        self.assertNotIn('RootService',source)

    def test_fixed_cli_allowlist_refuses_other_package(self):
        with self.assertRaisesRegex(RuntimeError,'unapproved'):
            mod.vector('device','/data/adb/modules/zygisk_vector/cli','modules','disable','other.package')
        with self.assertRaisesRegex(RuntimeError,'unapproved'):
            mod.vector('device','/data/adb/modules/zygisk_vector/cli','scope','set',mod.MODULE,mod.SCOPE)

    def test_vector_baseline_rejects_foreign_scope(self):
        def fake(serial,path,*args):
            return {('status',):{'API Version':102,'Framework Version':'2.2'},
                ('modules','ls'):[{'PACKAGE':mod.MODULE,'STATUS':'disabled'}],
                ('scope','ls',mod.MODULE):[{'APP_PACKAGE':'other.app','USER_ID':0}]}[args]
        with patch.object(mod,'vector',side_effect=fake):
            with self.assertRaisesRegex(RuntimeError,'unrelated targets'):
                mod.vector_baseline('serial','cli')

    def test_recovery_never_changes_unrelated_module_state(self):
        other={'unrelated.module':'enabled'}
        doc={'schema':'mmrl-lsposed.s08.host-journal.v1','serial':'serial',
            'module':mod.MODULE,'target':mod.TARGET,'original_enabled':False,
            'original_scope':[],'unrelated_modules':other}
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'journal.json';p.write_text(json.dumps(doc))
            with patch.object(mod,'vector_baseline',return_value=({mod.MODULE:'enabled','unrelated.module':'disabled'},[mod.SCOPE])):
                with self.assertRaisesRegex(RuntimeError,'Unrelated module states changed'):
                    mod.recover('serial','cli',p,doc)

    def test_recovery_order_scope_then_disable(self):
        doc={'schema':'mmrl-lsposed.s08.host-journal.v1','serial':'serial',
            'module':mod.MODULE,'target':mod.TARGET,'original_enabled':False,
            'original_scope':[],'unrelated_modules':{'unrelated.module':'enabled'}}
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'journal.json';path.write_text(json.dumps(doc))
            states=[({mod.MODULE:'enabled','unrelated.module':'enabled'},[mod.SCOPE]),
                    ({mod.MODULE:'enabled','unrelated.module':'enabled'},[]),
                    ({mod.MODULE:'disabled','unrelated.module':'enabled'},[])]
            def fake_vec(serial,cli,*parts):
                if parts==('scope','rm',mod.MODULE,mod.SCOPE):return {}
                if parts==('modules','disable',mod.MODULE):return {}
                self.fail('Unexpected recovery command')
            with patch.object(mod,'vector_baseline',side_effect=states), patch.object(mod,'vector',side_effect=fake_vec) as api:
                result=mod.recover('serial','cli',path,doc)
                self.assertEqual(result['phase'],'RESTORED')
                self.assertEqual(api.call_args_list[0].args[2:],('scope','rm',mod.MODULE,mod.SCOPE))
                self.assertEqual(api.call_args_list[1].args[2:],('modules','disable',mod.MODULE))

    def test_androidtest_install_uses_test_only_flag(self):
        with tempfile.TemporaryDirectory() as tmp:
            target=Path(tmp)/'test.apk';target.write_bytes(b'testbytes')
            with patch.object(mod,'installed',side_effect=[None,target]),patch.object(mod,'adb') as adb:
                mod.ensure_installed('serial',{mod.TEST:target},Path(tmp))
                self.assertEqual(adb.call_args.args[1:3],('install','-t'))

    def test_host_requires_signed_app_already_installed(self):
        with tempfile.TemporaryDirectory() as tmp:
            target=Path(tmp)/'app.apk';target.write_bytes(b'app')
            with patch.object(mod,'installed',return_value=None),patch.object(mod,'adb') as adb:
                with self.assertRaisesRegex(RuntimeError,'Install exact signed main app'):
                    mod.ensure_installed('serial',{mod.APP:target},Path(tmp))
                adb.assert_not_called()


    def test_android_instrumentation_success_without_optional_exit_marker(self):
        from types import SimpleNamespace
        output = SimpleNamespace(returncode=0, stdout='INSTRUMENTATION_STATUS_CODE: -1\n\nTime: 48.201\n\nOK (1 test)\n',stderr='')
        self.assertTrue(mod.instrumentation_passed(output))
        with patch.object(mod,'adb',return_value=output):
            self.assertIn('OK (1 test)',mod.instrumentation('localhost:5555','S08ReadOnlyDeviceTest'))

    def test_instrumentation_rejects_false_successes(self):
        from types import SimpleNamespace
        cases = [
            (0,'Time: 1\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n','',True),
            (0,'Time: 1\nOK (1 test)\nINSTRUMENTATION_CODE: 0\n','',False),
            (0,'Time: 1\nOK (1 test)\nINSTRUMENTATION_RESULT: shortMsg=Process crashed.\n','',False),
            (0,'Time: 1\nFAILURES!!!\n','',False),
            (0,'Time: 1\nOK (2 tests)\n','',False),
            (0,'INSTRUMENTATION_CODE: -1\n','',False),
            (1,'Time: 1\nOK (1 test)\n','',False),
            (0,'Time: 1\nOK (1 test)\n','INSTRUMENTATION_FAILED',False),
        ]
        for rc, out, err, expected in cases:
            with self.subTest(rc=rc,output=out,stderr=err):
                self.assertEqual(mod.instrumentation_passed(SimpleNamespace(returncode=rc,stdout=out,stderr=err)),expected)

    def test_attested_app_source_only_allows_host_runner_changes(self):
        from types import SimpleNamespace
        from unittest.mock import call
        source='a'*40
        current='b'*40
        def accept(parts):
            cmd=' '.join(parts)
            if 'rev-parse HEAD' in cmd:return SimpleNamespace(returncode=0,stdout=current)
            if 'merge-base --is-ancestor' in cmd:return SimpleNamespace(returncode=0,stdout='')
            if 'diff --name-only --no-renames' in cmd:
                return SimpleNamespace(returncode=0,stdout='scripts/s08_device_lab.py\nscripts/tests/test_s08_instrumentation.py\n')
            if 'diff --name-only' in cmd:return SimpleNamespace(returncode=0,stdout='')
            raise AssertionError(cmd)
        with patch.object(mod,'proc',side_effect=lambda *args,**kw:accept(args)):
            mod.verify_checkout_source(Path('/tmp/MMRL-LSPosed'),source)

        def reject(parts):
            cmd=' '.join(parts)
            if 'rev-parse HEAD' in cmd:return SimpleNamespace(returncode=0,stdout=current)
            if 'merge-base --is-ancestor' in cmd:return SimpleNamespace(returncode=0,stdout='')
            if 'diff --name-only --no-renames' in cmd:
                return SimpleNamespace(returncode=0,stdout='scripts/s08_device_lab.py\napp/src/main/AndroidManifest.xml\n')
            raise AssertionError(cmd)
        with patch.object(mod,'proc',side_effect=lambda *args,**kw:reject(args)):
            with self.assertRaisesRegex(RuntimeError,'unrelated code'):
                mod.verify_checkout_source(Path('/tmp/MMRL-LSPosed'),source)

if __name__=='__main__':unittest.main()
