"""R4C deterministic source/identity and non-destructive device safety tests."""
from __future__ import annotations

import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import ci_device_acceptance as r4c
import ci_production_signing as r4b


class R4CDeviceContracts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.candidate_dir = self.root / 'candidate'
        self.candidate_dir.mkdir()
        self.apk = self.candidate_dir / r4b.OUTPUT_NAME
        self.apk.write_bytes(b'candidate signed APK fixture' * 5)
        self.baseline = self.root / 'baseline.apk'
        self.baseline.write_bytes(b'known installed baseline fixture' * 6)
        self.cert = '1' * 64
        self.source = 'a' * 40
        self.serial = 'localhost:5555'
        self.plan_path = self.root / 'plan.json'
        self.journal_path = self.root / 'journal.json'
        self.version = 2
        self.old_version = 1
        self.installed = self.baseline.read_bytes()
        self.receipt = r4b.generate_receipt(source=self.source, run=123, attempt=1,
                                            expected=self.cert, apk=self.apk,
                                            version_code=self.version, version_name='0.2')
        self.save_receipt(self.receipt)
        self.source_patch = patch.object(r4c, 'source_policy', return_value=self.cert)
        self.meta_patch = patch.object(r4c, 'apk_metadata', side_effect=self.mock_metadata)
        self.read_patch = patch.object(r4c, 'device_readonly', side_effect=self.mock_device_read)
        self.source_patch.start(); self.meta_patch.start(); self.read_patch.start()
        self.addCleanup(self.source_patch.stop)
        self.addCleanup(self.meta_patch.stop)
        self.addCleanup(self.read_patch.stop)
        self.kw = dict(root=self.root, candidate_dir=self.candidate_dir,
                       source=self.source, run=123, attempt=1,
                       aapt='aapt', apksigner='apksigner')
        self.device_kw = dict(self.kw, baseline=self.baseline, serial=self.serial)

    def save_receipt(self, receipt):
        (self.candidate_dir / r4b.RECEIPT_NAME).write_text(json.dumps(receipt))

    def mock_metadata(self, apk, *, aapt, apksigner, cert):
        if Path(apk).read_bytes() == self.baseline.read_bytes():
            return self.old_version, '0.1'
        if Path(apk).read_bytes() == self.apk.read_bytes():
            return self.version, '0.2'
        raise r4c.Refused('Invalid installed APK fixture')

    def mock_device_read(self, serial, *, dest):
        self.assertEqual(serial, self.serial)
        Path(dest).write_bytes(self.installed)
        return {'model': 'M2101K7BG', 'api': 36,
                'installed_sha256': r4b.sha256(Path(dest))}

    def test_pending_policy_refuses_device_candidate_even_after_future_enrollment(self):
        # Exercise an explicit pending fixture: the live policy may later be enrolled.
        release = r4b.read_json(ROOT / 'config/release-signing.json')
        debug = r4b.read_json(ROOT / 'config/debug-signing.json')
        pending = dict(release, enrollment='pending', certificate_sha256=None)
        with self.assertRaises(r4b.Refused):
            r4b.exact_release_policy(pending, debug, require_enrolled=True)

    def test_candidate_receipt_and_exact_run(self):
        self.assertEqual(r4c.verify_candidate(**self.kw), self.receipt)
        for changed in [dict(self.kw, source='b'*40),dict(self.kw, run=124),
                        dict(self.kw, attempt=2)]:
            with self.assertRaises((r4c.Refused, r4b.Refused)):
                r4c.verify_candidate(**changed)

    def test_forged_release_and_device_flags_refused(self):
        for key in ('release_qualified','device_qualified','publish_allowed','independently_attested'):
            r = dict(self.receipt, **{key:True})
            self.save_receipt(r)
            with self.assertRaises((r4c.Refused, r4b.Refused)):
                r4c.verify_candidate(**self.kw)
        self.save_receipt(self.receipt)

    def test_modified_bytes_refused(self):
        self.apk.write_bytes(self.apk.read_bytes()+b'tamper')
        with self.assertRaises((r4c.Refused, r4b.Refused)):
            r4c.verify_candidate(**self.kw)

    def test_extra_files_and_symlink_refused(self):
        (self.candidate_dir/'evil.bin').write_bytes(b'x')
        with self.assertRaises((r4c.Refused, r4b.Refused)):
            r4c.verify_candidate(**self.kw)
        (self.candidate_dir/'evil.bin').unlink()
        self.apk.unlink()
        self.apk.symlink_to(self.baseline)
        with self.assertRaises((r4c.Refused, r4b.Refused)):
            r4c.verify_candidate(**self.kw)

    def test_readonly_plan_exact_baseline_and_qualification_false(self):
        plan = r4c.make_plan(**self.device_kw)
        self.assertEqual(plan['baseline_version_code'],1)
        self.assertEqual(plan['candidate_version_code'],2)
        self.assertFalse(plan['rollback_guaranteed'])
        self.assertFalse(plan['device_qualified'])
        self.assertFalse(plan['release_qualified'])
        r4c.checked_plan(plan)

    def test_installed_mismatch_is_refused(self):
        self.installed=b'unrecognized installed APK'
        with self.assertRaises((r4c.Refused, r4b.Refused)):
            r4c.make_plan(**self.device_kw)

    def test_no_downgrade_or_same_version_trial(self):
        self.version=self.old_version
        self.receipt=r4b.generate_receipt(source=self.source,run=123,attempt=1,
                                          expected=self.cert,apk=self.apk,
                                          version_code=1,version_name='0.2')
        self.save_receipt(self.receipt)
        with self.assertRaises((r4c.Refused, r4b.Refused)):
            r4c.make_plan(**self.device_kw)

    def test_trial_requires_all_explicit_acknowledgements(self):
        p = r4c.make_plan(**self.device_kw)
        r4c.write_exclusive(self.plan_path,p)
        kwargs=dict(self.device_kw,plan_path=self.plan_path,journal_path=self.journal_path)
        for case in [{'ack_mutation':False,'ack_backup':True,'ack_rollback':True},
                     {'ack_mutation':True,'ack_backup':False,'ack_rollback':True},
                     {'ack_mutation':True,'ack_backup':True,'ack_rollback':False}]:
            with self.assertRaises((r4c.Refused, r4b.Refused)):
                r4c.run_trial(**kwargs, **case)
            self.assertFalse(self.journal_path.exists())

    def test_trial_refuses_stale_plan_without_mutation(self):
        plan=r4c.make_plan(**self.device_kw)
        plan['model']='other'
        r4c.write_exclusive(self.plan_path,plan)
        with patch.object(r4c, 'adb') as calls:
            with self.assertRaises((r4c.Refused, r4b.Refused)):
                r4c.run_trial(**self.device_kw,plan_path=self.plan_path,
                              journal_path=self.journal_path,
                              ack_mutation=True,ack_backup=True,ack_rollback=True)
            calls.assert_not_called()
        self.assertFalse(self.journal_path.exists())

    def test_trial_requires_exact_install_readback(self):
        r4c.write_exclusive(self.plan_path,r4c.make_plan(**self.device_kw))
        calls=[]
        def fake_adb(serial,args,timeout=60):
            calls.append(args)
            if args[0]=='install': self.installed=self.apk.read_bytes()
            return 'Success'
        with patch.object(r4c,'adb',side_effect=fake_adb):
            result=r4c.run_trial(**self.device_kw,plan_path=self.plan_path,
                                 journal_path=self.journal_path,
                                 ack_mutation=True,ack_backup=True,ack_rollback=True)
        self.assertEqual(result['status'],'TRIAL_INSTALLED_HASH_VERIFIED_NOT_QUALIFIED')
        self.assertEqual(calls,[['install','-r',str(self.apk)]])
        self.assertFalse(result['device_qualified'])

    def test_install_exit_zero_without_success_is_refused(self):
        r4c.write_exclusive(self.plan_path,r4c.make_plan(**self.device_kw))
        with patch.object(r4c,'adb',return_value='Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]'):
            with self.assertRaises((r4c.Refused,r4b.Refused)):
                r4c.run_trial(**self.device_kw,plan_path=self.plan_path,
                              journal_path=self.journal_path,
                              ack_mutation=True,ack_backup=True,ack_rollback=True)
        self.assertEqual(r4b.read_json(self.journal_path)['status'],
                         'INSTALL_FAILED_STATE_REQUIRES_INSPECTION')

    def test_install_failure_journal_never_claims_rollback(self):
        r4c.write_exclusive(self.plan_path,r4c.make_plan(**self.device_kw))
        with patch.object(r4c,'adb',side_effect=r4c.Refused('install refused')):
            with self.assertRaises((r4c.Refused, r4b.Refused)):
                r4c.run_trial(**self.device_kw,plan_path=self.plan_path,
                              journal_path=self.journal_path,
                              ack_mutation=True,ack_backup=True,ack_rollback=True)
        state=r4b.read_json(self.journal_path)
        self.assertEqual(state['status'],'INSTALL_FAILED_STATE_REQUIRES_INSPECTION')
        self.assertFalse(state['rollback_guaranteed'])

    def test_restore_rejects_unverified_current_device_and_never_uninstalls(self):
        r4c.write_exclusive(self.plan_path,r4c.make_plan(**self.device_kw))
        self.installed=self.apk.read_bytes()
        journal={**r4b.read_json(self.plan_path),'status':'TRIAL_INSTALLED_HASH_VERIFIED_NOT_QUALIFIED'}
        r4c.write_exclusive(self.journal_path,journal)
        self.installed=b'unknown device version'
        with patch.object(r4c, 'adb') as mock_adb:
            with self.assertRaises((r4c.Refused, r4b.Refused)):
                r4c.attempt_restore(**self.device_kw,journal_path=self.journal_path,
                                    ack_downgrade=True,ack_backup=True)
            mock_adb.assert_not_called()

    def test_restore_android_rejection_is_reported_not_hidden(self):
        r4c.write_exclusive(self.plan_path,r4c.make_plan(**self.device_kw))
        journal={**r4b.read_json(self.plan_path),'status':'TRIAL_INSTALLED_HASH_VERIFIED_NOT_QUALIFIED'}
        r4c.write_exclusive(self.journal_path,journal)
        self.installed=self.apk.read_bytes()
        with patch.object(r4c,'adb',side_effect=r4c.Refused('downgrade disallowed')):
            with self.assertRaises((r4c.Refused, r4b.Refused)):
                r4c.attempt_restore(**self.device_kw,journal_path=self.journal_path,
                                    ack_downgrade=True,ack_backup=True)
        self.assertEqual(r4b.read_json(self.journal_path)['status'],
                         'RESTORE_FAILED_MANUAL_RECOVERY_REQUIRED')

    def test_restore_exact_baseline_with_positive_ack(self):
        r4c.write_exclusive(self.plan_path,r4c.make_plan(**self.device_kw))
        journal={**r4b.read_json(self.plan_path),'status':'TRIAL_INSTALLED_HASH_VERIFIED_NOT_QUALIFIED'}
        r4c.write_exclusive(self.journal_path,journal)
        self.installed=self.apk.read_bytes()
        calls=[]
        def do_restore(serial,args,timeout=60):
            calls.append(args)
            if args[0]=='install':self.installed=self.baseline.read_bytes()
            return 'Success'
        with patch.object(r4c,'adb',side_effect=do_restore):
            result=r4c.attempt_restore(**self.device_kw,journal_path=self.journal_path,
                                       ack_downgrade=True,ack_backup=True)
        self.assertEqual(calls,[['install','-r','-d',str(self.baseline)]])
        self.assertEqual(result['status'],'BASELINE_APK_RESTORED_NOT_DATA_VERIFIED')
        self.assertFalse(result['device_qualified'])

    def test_journal_collision_refused(self):
        r4c.write_exclusive(self.plan_path,{'status':'test'})
        with self.assertRaises((r4c.Refused, r4b.Refused)):
            r4c.write_exclusive(self.plan_path,{'status':'changed'})
        self.assertEqual(r4b.read_json(self.plan_path)['status'],'test')

    def test_safe_adb_serial_and_allowed_commands(self):
        with self.assertRaises((r4c.Refused, r4b.Refused)):
            r4c.adb('localhost:5555; rm -rf /',['get-state'])
        with self.assertRaises((r4c.Refused, r4b.Refused)):
            r4c.adb(self.serial,['uninstall',r4c.APP])

    def test_git_source_refuses_invalid_hash(self):
        self.source_patch.stop()
        with self.assertRaises((r4c.Refused, r4b.Refused)):
            r4c.source_policy(self.root,'not-a-real-SHA')
        self.source_patch.start()


if __name__ == '__main__':
    unittest.main()
