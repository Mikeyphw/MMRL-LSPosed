"""Offline, no-Gradle regression checks for S01 GitHub debug signing."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
SIGNER = ROOT / 'scripts/hosted_debug_signing.py'
spec = importlib.util.spec_from_file_location('lsposed_hosted_signer', SIGNER)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class S01Signing(unittest.TestCase):
    def test_policy_schema_and_certificate_pinning(self):
        policy = {
            'schema': 'mmrl-lsposed.debug-signing.v1',
            'repository': module.REPO,
            'application_id': module.PACKAGE,
            'ref': module.BRANCH_REF,
            'alias': module.ALIAS,
            'purpose': 'debug-only',
            'certificate_sha256': 'a' * 64,
        }
        self.assertEqual(module.validate_policy(policy), 'a' * 64)
        for bad in ['pending', '0' * 63, '', 'F' * 64]:
            with self.subTest(bad=bad), self.assertRaises(module.SignRefused):
                module.validate_policy({**policy, 'certificate_sha256': bad})
        with self.assertRaises(module.SignRefused):
            module.validate_policy({**policy, 'purpose': 'release'})
        with self.assertRaises(module.SignRefused):
            module.validate_policy({**policy, 'extra': 'unexpected'})

    def test_devtool_signing_manager_wired_in(self):
        source = SIGNER.read_text()
        self.assertIn('from devtool.android.signing import AndroidSigningManager, SigningProfile', source)
        self.assertIn('manager.inspect_profile(', source)
        self.assertIn('manager.verify_artifact(', source)
        self.assertIn('expected_sha256=cert_sha', source)
        self.assertIn('env:{SECRET_PASS}', source)
        self.assertNotIn('signing_required = true', source)

    def test_android_package_identity_and_debug_required(self):
        sample = ("package: name='io.github.mikeyphw.mmrl.lsposed.debug' "
                  "versionCode='1' versionName='0.1.0-s01-debug'\n"
                  "application-debuggable\n")
        self.assertEqual(module.parse_badging(sample), (module.PACKAGE, 1, '0.1.0-s01-debug'))
        with self.assertRaises(module.SignRefused):
            module.parse_badging(sample.replace('application-debuggable', 'application-not-debuggable'))

    def test_exactly_one_apk_signer(self):
        single = 'Signer #1 certificate SHA-256 digest: ' + 'b' * 64
        self.assertEqual(module.parse_exactly_one_signer(single), 'b' * 64)
        with self.assertRaises(module.SignRefused):
            module.parse_exactly_one_signer(single + '\nSigner #2 certificate SHA-256 digest: ' + 'c' * 64)
        with self.assertRaises(module.SignRefused):
            module.parse_exactly_one_signer('')

    def test_trusted_push_only_and_pr_never_sees_secrets(self):
        workflow = (ROOT / '.github/workflows/s01-hosted-devtool.yml').read_text()
        self.assertIn("if: github.event_name == 'push' && github.ref == 'refs/heads/main'", workflow)
        self.assertIn('MMRL_DEBUG_KEYSTORE_B64: ${{ secrets.MMRL_DEBUG_KEYSTORE_B64 }}', workflow)
        self.assertIn('MMRL_DEBUG_STORE_PASSWORD: ${{ secrets.MMRL_DEBUG_STORE_PASSWORD }}', workflow)
        self.assertIn("if: github.event_name == 'pull_request'", workflow)
        self.assertIn('mmrl-lsposed-pr-debug-ephemeral-', workflow)
        self.assertIn('mmrl-lsposed-signed-debug-', workflow)
        self.assertIn('needs: hosted-debug', workflow)
        jobs = workflow.split('  attest-signed:\n')
        self.assertEqual(len(jobs), 2)
        self.assertNotIn('id-token: write', jobs[0])
        self.assertIn('id-token: write', jobs[1])
        self.assertIn('attestations: write', jobs[1])
        self.assertIn('actions/attest@1e69f48acb82d1966a394da916b4c1698aa569d6', jobs[1])

    def test_no_unsafe_release_claims(self):
        source = SIGNER.read_text()
        self.assertIn('"release_qualified": False', source)
        self.assertIn('"device_qualified": False', source)
        self.assertIn('"hooks_verified": False', source)
        self.assertIn('"status": "SIGNED_DEBUG_VERIFIED_NOT_RELEASE"', source)

    def test_secrets_never_written_in_source(self):
        script = (ROOT / 'scripts/setup_debug_signing.zsh').read_text()
        self.assertIn('gh secret set MMRL_DEBUG_KEYSTORE_B64', script)
        self.assertIn('gh secret set MMRL_DEBUG_STORE_PASSWORD', script)
        self.assertIn('$HOME/.config/mmrl-lsposed/signing', script)
        self.assertIn('-storepass:env MMRL_DEBUG_LOCAL_STORE_PASS', script)
        self.assertNotIn('keytool -genkeypair -storepass=', script)

    def test_policy_is_pinned_in_checked_out_source_when_present(self):
        policy = ROOT / 'config/debug-signing.json'
        if policy.exists():
            self.assertRegex(module.validate_policy(json.loads(policy.read_text())), r'^[0-9a-f]{64}$')
        # Workflow's trusted main signing step fails closed if this file is missing.
        self.assertIn('config/debug-signing.json', SIGNER.read_text())

    def test_hosting_preserves_devtool_source_pin(self):
        source = (ROOT / 'scripts/hosted_devtool.py').read_text()
        self.assertIn('b164463db51e2cffd12f753043b9e55a08cc43ae', source)
        self.assertIn('AndroidSigningManager', SIGNER.read_text())


if __name__ == '__main__':
    unittest.main()
