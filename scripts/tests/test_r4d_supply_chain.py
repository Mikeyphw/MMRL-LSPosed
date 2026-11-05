"""R4D fake Gradle inputs; no Android build, signing, or device mutation."""
from __future__ import annotations

import copy
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('r4d_supply_chain', ROOT / 'scripts/ci_r4d_supply_chain.py')
assert SPEC and SPEC.loader
import sys
sys.path.insert(0, str(ROOT / 'scripts'))
r4d = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(r4d)

REPORT = b'''\
------------------------------------------------------------
Project ':app'
------------------------------------------------------------
releaseRuntimeClasspath - Runtime classpath of 'main'.
+--- androidx.core:core:1.16.0
|    +--- androidx.annotation:annotation:1.9.1
|    \\--- org.jetbrains.kotlin:kotlin-stdlib:2.2.0
+--- androidx.activity:activity-compose:1.10.1
|    \\--- androidx.lifecycle:lifecycle-runtime:2.8.7 -> 2.9.0
\\--- com.github.topjohnwu.libsu:core:6.0.0
     \\--- androidx.core:core:1.16.0 (*)
BUILD SUCCESSFUL in 11s
'''


class R4DSupplyChainContract(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'repo'
        self.root.mkdir()
        for item in r4d.SOURCE_INPUTS:
            target = self.root / item
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / item, target)
        self.identity = r4d.parse_identity('a' * 40, '42115870049', '1',
                                           'push', 'refs/heads/main')
        self.dir = Path(self.temp.name) / 'evidence'

    def bundle(self):
        return r4d.generate(self.root, REPORT, self.identity, enforce_checkout=False)

    def test_inventory_is_technically_resolved_but_not_license_qualified(self):
        data = self.bundle()
        bom = r4d.strict_json(data['sbom.cdx.json'])
        inv = r4d.strict_json(data['dependency-inventory.json'])
        lic = r4d.strict_json(data['license-audit.json'])
        self.assertEqual(bom['specVersion'], '1.6')
        self.assertEqual(bom['compositions'][0]['aggregate'], 'incomplete')
        self.assertTrue(inv['resolved_component_count'] >= 5)
        self.assertIn('androidx.lifecycle:lifecycle-runtime:2.9.0', inv['release_components'])
        self.assertFalse(inv['transitive_edges_independently_verified'])
        self.assertFalse(lic['third_party_license_verified'])
        self.assertTrue(all('licenses' not in c for c in bom['components']))
        self.assertIn('app/build.gradle.kts', {v['declared_at'] for v in inv['declarations']})

    def test_policy_report_remains_blocked_with_pending_certificate(self):
        q = r4d.strict_json(self.bundle()['release-qualification.json'])
        self.assertEqual(q['status'], 'BLOCKED')
        self.assertFalse(q['publish_allowed'])
        self.assertFalse(q['device_qualified'])
        self.assertFalse(q['release_qualified'])
        self.assertIn('PRODUCTION_RELEASE_CERTIFICATE_NOT_ENROLLED', q['blockers'])
        self.assertIn('S11_PHYSICAL_UX_REQUALIFICATION_PENDING', q['blockers'])

    def test_even_enrolled_certificate_cannot_auto_qualify(self):
        p = self.root / 'config/release-signing.json'
        data = json.loads(p.read_text())
        data['enrollment'] = 'enrolled'
        data['certificate_sha256'] = 'f' * 64
        p.write_text(json.dumps(data))
        q = r4d.strict_json(self.bundle()['release-qualification.json'])
        self.assertEqual(q['status'], 'BLOCKED')
        self.assertNotIn('PRODUCTION_RELEASE_CERTIFICATE_NOT_ENROLLED', q['blockers'])
        self.assertIn('R4C_PHYSICAL_UPGRADE_DATA_AND_RESTORE_PENDING', q['blockers'])

    def test_generated_evidence_rechecks_bytes_and_source(self):
        r4d.write_exclusive(self.dir, self.bundle())
        result = r4d.verify(self.dir, self.root, self.identity, enforce_checkout=False)
        self.assertEqual(result['status'], 'BLOCKED')
        (self.dir / 'gradle-release-dependencies.txt').write_bytes(REPORT + b'tampered\n')
        with self.assertRaises(r4d.Refused):
            r4d.verify(self.dir, self.root, self.identity, enforce_checkout=False)

    def test_forged_manifest_hash_and_promoted_status_fail(self):
        r4d.write_exclusive(self.dir, self.bundle())
        m = self.dir / 'manifest.json'
        data = json.loads(m.read_text())
        data['outputs_sha256']['sbom.cdx.json'] = 'f' * 64
        m.write_text(json.dumps(data))
        with self.assertRaises(r4d.Refused):
            r4d.verify(self.dir, self.root, self.identity, enforce_checkout=False)
        shutil.rmtree(self.dir)
        r4d.write_exclusive(self.dir, self.bundle())
        qpath = self.dir / 'release-qualification.json'
        q = json.loads(qpath.read_text()); q['publish_allowed'] = True
        qpath.write_text(json.dumps(q))
        with self.assertRaises(r4d.Refused):
            r4d.verify(self.dir, self.root, self.identity, enforce_checkout=False)

    def test_source_policy_modification_invalidates_old_evidence(self):
        r4d.write_exclusive(self.dir, self.bundle())
        policy = self.root / 'config/release-signing.json'
        data = json.loads(policy.read_text())
        data['publish_allowed'] = True
        policy.write_text(json.dumps(data))
        with self.assertRaises(r4d.Refused):
            r4d.verify(self.dir, self.root, self.identity, enforce_checkout=False)

    def test_duplicate_json_and_unknown_key_fail_closed(self):
        p = self.root / 'config/release-signing.json'
        p.write_text('{"schema":"a", "schema":"b"}')
        with self.assertRaises(r4d.Refused):
            self.bundle()

    def test_newer_git_head_does_not_match_evidence_identity(self):
        bad = copy.deepcopy(self.identity)
        bad['source_sha'] = '0' * 40
        r4d.write_exclusive(self.dir, self.bundle())
        with self.assertRaises(r4d.Refused):
            r4d.verify(self.dir, self.root, bad, enforce_checkout=False)

    def test_other_repo_pr_ref_and_attempts_refused(self):
        x = r4d.parse_identity
        for parameters in [
            ('a' * 40, '4', '0', 'push', 'refs/heads/main'),
            ('a' * 40, '4', '1', 'pull_request', 'refs/heads/main'),
            ('a' * 40, '4', '1', 'push', 'refs/heads/feat/other'),
            ('a' * 40, '4', '1', 'workflow_dispatch', 'refs/tags/v1'),
            ('b' * 39, '4', '1', 'push', 'refs/heads/main'),
            ('a' * 40, '-4', '1', 'push', 'refs/heads/main'),
        ]:
            with self.subTest(parameters=parameters):
                with self.assertRaises(r4d.Refused):
                    x(*parameters)

    def test_stale_attempt_fails_even_same_run_source(self):
        r4d.write_exclusive(self.dir, self.bundle())
        bad = dict(self.identity, attempt=2)
        with self.assertRaises(r4d.Refused):
            r4d.verify(self.dir, self.root, bad, enforce_checkout=False)

    def test_gradle_resolution_failures_and_too_few_dependencies_refused(self):
        for report in (b'', REPORT + b'UNRESOLVED dependency',
                       b'releaseRuntimeClasspath - empty\n+--- a:b:1\n',
                       b'compileClasspath - wrong\n+--- a:b:1\n',
                       b'releaseRuntimeClasspath - missing\n+--- org.gradle:broken:FAILED\n'):
            with self.subTest(report=report):
                with self.assertRaises(r4d.Refused):
                    r4d.resolved_dependencies(report)

    def test_exclusive_output_refuses_overwrites_or_extra_files(self):
        items = self.bundle()
        r4d.write_exclusive(self.dir, items)
        with self.assertRaises(r4d.Refused):
            r4d.write_exclusive(self.dir, items)
        (self.dir / 'extra.json').write_text('{}')
        with self.assertRaises(r4d.Refused):
            r4d.verify(self.dir, self.root, self.identity, enforce_checkout=False)

    def test_workflow_readonly_pin_and_distribution_boundaries(self):
        wf = (ROOT / '.github/workflows/r4d-supply-chain-qualification.yml').read_text()
        self.assertIn('releaseRuntimeClasspath', wf)
        self.assertIn('actions/attest@1e69f48acb82d1966a394da916b4c1698aa569d6', wf)
        self.assertIn('if: github.event_name == \'push\' && github.ref == \'refs/heads/main\'', wf)
        for forbidden in ('gh release create', 'adb install', 'sign_candidate: true',
                          'MMRL_RELEASE_KEYSTORE_B64:', 'releases: write',
                          'gradle publish', 'curl -X POST'):
            self.assertNotIn(forbidden, wf)
        self.assertIn('retention-days: 14', wf)

if __name__ == '__main__':
    unittest.main()
