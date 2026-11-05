"""R4B offline release-policy/receipt/source contracts, never actual signing."""
from __future__ import annotations

import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('r4b_release_signing', ROOT / 'scripts/ci_production_signing.py')
assert SPEC and SPEC.loader
r4b = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(r4b)


class R4BSigningPolicyTests(unittest.TestCase):
    def setUp(self):
        self.release = json.loads((ROOT / 'config/release-signing.json').read_text())
        self.debug = json.loads((ROOT / 'config/debug-signing.json').read_text())
        self.cert = '1' * 64
        self.source = 'a' * 40
        self.env = {
            'GITHUB_ACTIONS': 'true', 'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64',
            'GITHUB_REPOSITORY': 'Mikeyphw/MMRL-LSPosed',
            'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REF': 'refs/heads/main',
            'GITHUB_SHA': self.source, 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '2',
        }

    def pending(self):
        return dict(self.release, enrollment='pending', certificate_sha256=None)

    def enrolled(self):
        r = dict(self.release)
        r['enrollment'], r['certificate_sha256'] = 'enrolled', self.cert
        return r

    def test_pending_policy_allows_audit_but_never_signing(self):
        pending = self.pending()
        self.assertIsNone(r4b.exact_release_policy(pending, self.debug, require_enrolled=False))
        with self.assertRaises(r4b.Refused):
            r4b.exact_release_policy(pending, self.debug, require_enrolled=True)

    def test_enrollment_rejects_reused_debug_certificate(self):
        r = self.enrolled()
        r['certificate_sha256'] = self.debug['certificate_sha256']
        with self.assertRaises(r4b.Refused):
            r4b.exact_release_policy(r, self.debug, require_enrolled=True)

    def test_explicit_distinct_certificate_enrolls_only_after_policy_edit(self):
        self.assertEqual(self.cert, r4b.exact_release_policy(
            self.enrolled(), self.debug, require_enrolled=True))

    def test_invalid_identity_and_publication_upgrade_are_refused(self):
        for key, value in [('repository', 'fork/other'), ('application_id', self.debug['application_id']),
                           ('ref', 'refs/heads/feature'), ('environment', 'skip'),
                           ('purpose', 'published-release'), ('publish_allowed', True),
                           ('release_qualified', True), ('device_qualified', True),
                           ('key_alias', 'mmrl_lsposed_debug'), ('keystore_type', 'JKS')]:
            with self.subTest(key=key):
                r = self.enrolled(); r[key] = value
                with self.assertRaises(r4b.Refused):
                    r4b.exact_release_policy(r, self.debug, require_enrolled=True)

    def test_partial_enrollment_and_unexpected_keys_refused(self):
        pending = self.pending()
        cases = [dict(pending, certificate_sha256=self.cert),
                 dict(pending, enrollment='enrolled'),
                 dict(pending, dangerous_new_key='unexpected'),
                 dict(pending, enrollment=True),
                 dict(pending, enrollment='enrolled', certificate_sha256='AA' * 32)]
        for obj in cases:
            with self.subTest(case=obj):
                with self.assertRaises(r4b.Refused):
                    r4b.exact_release_policy(obj, self.debug, require_enrolled=False)

    def test_debug_policy_is_independently_required(self):
        d = dict(self.debug, purpose='release')
        with self.assertRaises(r4b.Refused):
            r4b.exact_release_policy(self.enrolled(), d, require_enrolled=True)

    def test_only_exact_dispatch_main_is_admitted(self):
        self.assertEqual((123, 2), r4b.host_identity(self.env, self.source))
        for key, value in [('GITHUB_EVENT_NAME', 'pull_request'),
                           ('GITHUB_EVENT_NAME', 'push'),
                           ('GITHUB_REF', 'refs/heads/main-fake'),
                           ('GITHUB_REF', 'refs/tags/v1.0'),
                           ('GITHUB_REPOSITORY', 'someone/MMRL-LSPosed'),
                           ('GITHUB_SHA', '0' * 40), ('RUNNER_OS', 'Windows'),
                           ('GITHUB_RUN_ATTEMPT', '0'), ('GITHUB_RUN_ID', '1x')]:
            with self.subTest(key=key, value=value):
                bad = dict(self.env, **{key: value})
                with self.assertRaises(r4b.Refused):
                    r4b.host_identity(bad, self.source)

    def test_non_debuggable_exact_package_only(self):
        valid = "package: name='io.github.mikeyphw.mmrl.lsposed' versionCode='10' versionName='1.0.0'"
        self.assertEqual((10, '1.0.0'), r4b.parse_apk_metadata(valid))
        for bad in [valid + '\napplication-debuggable',
                    valid.replace('lsposed\'', 'lsposed.debug\''),
                    valid.replace('versionCode=\'10\'', 'versionCode=\'0\''),
                    valid.replace('1.0.0', '1.0.0-debug'),
                    valid.replace('1.0.0', '../../weird')]:
            with self.subTest(apk=bad):
                with self.assertRaises(r4b.Refused):
                    r4b.parse_apk_metadata(bad)

    def test_exact_one_signer_matches_enrolled_release_fingerprint(self):
        r4b.parse_signer('Signer #1 certificate SHA-256 digest: '+self.cert, self.cert)
        for bad in ['Signer #1 certificate SHA-256 digest: '+self.debug['certificate_sha256'],
                    'Signer #2 certificate SHA-256 digest: '+self.cert,
                    'Signer #1 certificate SHA-256 digest: '+self.cert+'\nSigner #2 certificate SHA-256 digest: '+self.cert,
                    '']:
            with self.assertRaises(r4b.Refused):
                r4b.parse_signer(bad, self.cert)

    def test_signed_candidate_receipt_refuses_forgery_and_modified_bytes(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / r4b.OUTPUT_NAME
            f.write_bytes(b'PK\003\004candidate-test-content')
            receipt = r4b.generate_receipt(source=self.source, run=123, attempt=2,
                                          expected=self.cert, apk=f,
                                          version_code=10, version_name='1.0.0')
            r4b.validate_receipt(receipt, source=self.source, run=123, attempt=2,
                                 cert=self.cert, apk=f, version_code=10, version_name='1.0.0')
            self.assertFalse(receipt['release_qualified'])
            self.assertFalse(receipt['publish_allowed'])
            self.assertFalse(receipt['device_qualified'])
            for key, value in [('source_sha', '0'*40), ('certificate_sha256', self.debug['certificate_sha256']),
                               ('release_qualified', True), ('independently_attested', True),
                               ('status', 'SIGNED_RELEASE_VERIFIED'), ('attempt', 3),
                               ('application_id', 'io.github.mikeyphw.mmrl.lsposed.debug')]:
                with self.subTest(key=key):
                    altered = dict(receipt, **{key: value})
                    with self.assertRaises(r4b.Refused):
                        r4b.validate_receipt(altered, source=self.source, run=123, attempt=2,
                                             cert=self.cert, apk=f, version_code=10, version_name='1.0.0')
            f.write_bytes(b'PK\003\004mutated-candidate')
            with self.assertRaises(r4b.Refused):
                r4b.validate_receipt(receipt, source=self.source, run=123, attempt=2,
                                     cert=self.cert, apk=f, version_code=10, version_name='1.0.0')

    def test_secret_values_not_permitted_in_git_or_tool_child_processes(self):
        src = (ROOT/'scripts/ci_production_signing.py').read_text()
        self.assertIn('SENSITIVE_ENV', src)
        self.assertIn('env=scrubbed', src)
        self.assertIn("'--ks-pass', 'file:'", src)
        self.assertIn("'--key-pass', 'file:'", src)
        self.assertNotIn("'--ks-pass', 'env:'", src)
        self.assertIn('tempfile.TemporaryDirectory', src)

    def test_workflow_is_manual_protected_and_cannot_publish_release(self):
        wf = (ROOT/'.github/workflows/r4b-isolated-release-candidate.yml').read_text()
        self.assertIn('workflow_dispatch:', wf)
        self.assertIn('sign_candidate:', wf)
        self.assertIn('default: false', wf)
        self.assertNotIn('pull_request:', wf)
        self.assertNotIn('releases: write', wf)
        self.assertNotIn('actions/attest@', wf)
        self.assertNotIn('gh release', wf)
        self.assertIn('name: mmrl-lsposed-release-signing', wf)
        self.assertIn("github.ref == 'refs/heads/main'", wf)
        self.assertIn('--require-enrolled', wf)
        self.assertIn('MMRL_RELEASE_KEYSTORE_B64: ${{ secrets.MMRL_RELEASE_KEYSTORE_B64 }}', wf)
        self.assertIn('retention-days: 7', wf)
        self.assertIn('app-release-unsigned.apk', wf)
        self.assertIn('R4B', wf)
        self.assertFalse((ROOT/'.github/workflows/s01-hosted-devtool.yml').read_text().find('MMRL_RELEASE_KEYSTORE_B64') >= 0)

    def test_json_loader_rejects_duplicate_and_malformed_keys(self):
        with self.assertRaises(r4b.Refused):
            r4b.json_unique(b'{"schema":1,"schema":2}')
        with self.assertRaises(r4b.Refused):
            r4b.json_unique(b'{"schema":')
        with self.assertRaises(r4b.Refused):
            r4b.json_unique(b'[]')


if __name__ == '__main__':
    unittest.main()
