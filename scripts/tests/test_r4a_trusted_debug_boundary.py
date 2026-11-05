"""R4A: offline signer-to-Devtool continuity and debug/release separation."""
from __future__ import annotations
import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import ci_federation_portable as portable
import ci_release_boundary as boundary

SOURCE = 'a' * 40
RUN = 42420000001
ATTEMPT = 2
PIN = boundary.DEVTOOL_SHA
CERT = 'd887b78cc920b49112e2373f2389f51d7bc2931f894ff89509a0aae14e617b27'
GRAPH = 'b' * 64
ORIGINAL = 'c' * 64

def jsonwrite(path, value):
    path.write_text(json.dumps(value, sort_keys=True) + '\n')


class R4ABoundaryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='r4a-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.signed = self.root / 'signed'
        self.evidence = self.root / 'evidence'
        self.signed.mkdir()
        self.evidence.mkdir()
        self.policy = self.root / 'policy.json'
        self.policy_doc = {
            'schema': 'mmrl-lsposed.debug-signing.v1',
            'repository': boundary.REPO, 'application_id': boundary.PACKAGE,
            'ref': 'refs/heads/main', 'alias': 'mmrl_lsposed_debug',
            'purpose': 'debug-only', 'certificate_sha256': CERT,
        }
        jsonwrite(self.policy, self.policy_doc)
        self.apk = self.signed / boundary.APK
        self.apk.write_bytes(b'PK\x03\x04' + b'validated-debug-only-test-fixture' * 90)
        self.original = {'schema': 'mmrl.lsposed.s01.devtool-plan.v1',
            'source_sha': SOURCE, 'devtool_sha': PIN, 'graph_sha256': GRAPH,
            'node_ids': {'apk': 'node-01', 'lint':'node-02', 'test':'node-03'},
            'package': boundary.PACKAGE, 'release_qualified': False,
            'device_qualified': False,
            'output': 'app/build/outputs/apk/debug/app-debug.apk',
            'apk_sha256': ORIGINAL, 'apk_size': 100234,
            'debug_signer_sha256': 'd' * 64, 'package_verified': True,
            'signature_verified': True, 'hooks_verified': False,
            'run_id': RUN, 'attempt': ATTEMPT, 'status':'VERIFIED_DEBUG_ONLY'}
        plan = {k:v for k,v in self.original.items()
            if k not in ('apk_sha256','apk_size','debug_signer_sha256','package_verified',
                         'signature_verified','hooks_verified','run_id','attempt','status')}
        index = {
            'schema':portable.INDEX_SCHEMA,'repository':boundary.REPO,'source_sha':SOURCE,
            'run_id':RUN,'attempt':ATTEMPT,'event':'push','devtool_sha':PIN,
            'devtool_target':'lsposed_app','devtool_workflow':'ci','graph_sha256':GRAPH,
            'node_ids':self.original['node_ids'],'debug_apk_sha256':ORIGINAL,
            'debug_apk_size':100234,'debug_apk_signer_sha256':'d'*64,
            'evidence_artifact_name':f'mmrl-lsposed-s01-evidence-{RUN}-attempt{ATTEMPT}',
            'verification_status':'VERIFIED_DEBUG_ONLY','device_qualified':False,
            'release_qualified':False,'hooks_verified':False,'independently_attested':False,
        }
        self.docs = {'plan.json':plan,'verification.json':self.original,'federation-index.json':index}
        encoded = {k:(json.dumps(v,sort_keys=True) + "\n").encode() for k,v in self.docs.items()}
        self.docs[portable.CONTRACT]=portable.compose(encoded)
        for key,doc in self.docs.items():jsonwrite(self.evidence/key,doc)
        self.node = {'schema_version':2,'kind':'devtool.actions.node-handoff',
            'source_commit':SOURCE,'graph_sha256':GRAPH,'run_id':str(RUN),
            'attempt':ATTEMPT,'producer_job':'node-01',
            'release_qualified':False,'files':[{
            'path':'app/build/outputs/apk/debug/app-debug.apk',
            'sha256':ORIGINAL,'size':100234}]}
        jsonwrite(self.signed/'devtool-node-receipt.json',self.node)
        self.receipt = {
            'schema':'mmrl-lsposed.hosted-signed-debug.v1',
            'status':'SIGNED_DEBUG_VERIFIED_NOT_RELEASE',
            'repository':boundary.REPO,'source_sha':SOURCE,'run_id':RUN,
            'attempt':ATTEMPT,'devtool_sha':PIN,'devtool_graph_sha256':GRAPH,
            'devtool_node_receipt_sha256':boundary._sha(self.signed/'devtool-node-receipt.json'),
            'devtool_original_apk_sha256':ORIGINAL,
            'signed_apk':boundary.APK, 'signed_apk_sha256':boundary._sha(self.apk),
            'signed_apk_size':self.apk.stat().st_size,'certificate_sha256':CERT,
            'application_id':boundary.PACKAGE,'version_code':1,
            'version_name':'0.1.0-s01-debug', 'debuggable':True,
            'release_qualified':False, 'device_qualified':False,
            'hooks_verified':False}
        jsonwrite(self.signed/'signed-debug.json',self.receipt)

    def qualified(self):
        return boundary.verify(signed=self.signed, evidence=self.evidence,
            policy=self.policy, source=SOURCE, run=RUN, attempt=ATTEMPT,
            devtool_sha=PIN)

    def test_exact_source_graph_native_signer_and_apk_pass(self):
        result = self.qualified()
        self.assertEqual(result['status'],'R4A_TRUSTED_DEBUG_BOUNDARY_PASS_NOT_RELEASE')
        self.assertIs(result['release_qualified'],False)

    def test_apk_mutation_and_symlink_refused(self):
        self.apk.write_bytes(self.apk.read_bytes()+b'evil')
        with self.assertRaises(ValueError):self.qualified()
        self.apk.unlink()
        self.apk.symlink_to(self.signed/'signed-debug.json')
        with self.assertRaises(ValueError):self.qualified()

    def test_forged_release_or_device_or_hook_claim_refused(self):
        for key in ('release_qualified','device_qualified','hooks_verified'):
            receipt = self.receipt | {key:True}
            jsonwrite(self.signed/'signed-debug.json',receipt)
            with self.assertRaises(ValueError):self.qualified()
        jsonwrite(self.signed/'signed-debug.json',self.receipt)

    def test_wrong_committed_cert_and_debug_identity_refused(self):
        for attr,val in [('certificate_sha256','e'*64),('purpose','release')]:
            jsonwrite(self.policy,self.policy_doc | {attr:val})
            with self.assertRaises(ValueError):self.qualified()
        jsonwrite(self.policy,self.policy_doc)
        jsonwrite(self.signed/'signed-debug.json',self.receipt|{'debuggable':False})
        with self.assertRaises(ValueError):self.qualified()

    def test_node_replacement_or_devtool_graph_drift_refused(self):
        jsonwrite(self.signed/'devtool-node-receipt.json',self.node|{'source_commit':'f'*40})
        with self.assertRaises(ValueError):self.qualified()
        jsonwrite(self.signed/'devtool-node-receipt.json',self.node)
        jsonwrite(self.signed/'signed-debug.json',self.receipt|{'devtool_graph_sha256':'e'*64})
        with self.assertRaises(ValueError):self.qualified()

    def test_wrong_run_attempt_and_pr_evidence_refused(self):
        for changed in ({'run':RUN+1},{'attempt':ATTEMPT+1}, {'source':'1'*40},
                        {'devtool_sha':'3'*40}):
            opts=dict(signed=self.signed,evidence=self.evidence,policy=self.policy,
                      source=SOURCE,run=RUN,attempt=ATTEMPT,devtool_sha=PIN)
            opts.update(changed)
            with self.assertRaises(ValueError):boundary.verify(**opts)
        self.docs['federation-index.json']['event']='pull_request'
        jsonwrite(self.evidence/'federation-index.json',self.docs['federation-index.json'])
        with self.assertRaises(ValueError):self.qualified()

    def test_duplicate_json_keys_refused(self):
        x=(self.signed/'signed-debug.json')
        x.write_text('{"release_qualified":false,"release_qualified":true}')
        with self.assertRaises(ValueError):self.qualified()

    def test_missing_portable_receipt_fails_closed(self):
        (self.evidence/'portable-federation-contract.json').unlink()
        with self.assertRaises(ValueError):self.qualified()

    def test_git_workflow_is_additive_and_privileges_separate(self):
        text=(ROOT/'.github/workflows/s01-hosted-devtool.yml').read_text()
        attest=text.split('  attest-signed:\n',1)[1]
        self.assertIn('R4A_TRUSTED_DEBUG_BOUNDARY_BEGIN',attest)
        self.assertIn('--evidence "$RUNNER_TEMP/r4a-evidence"',attest)
        self.assertIn('persist-credentials: false',attest)
        self.assertLess(attest.index('Verify trusted debug signer continuity'),
                        attest.index('Attest exact signed APK'))
        self.assertNotIn('MMRL_DEBUG_STORE_PASSWORD:',attest)
        self.assertNotIn('MMRL_DEBUG_KEYSTORE_B64:',attest)
        self.assertEqual(text.count('id-token: write'),1)
        self.assertIn('actions/attest@1e69f48acb82d1966a394da916b4c1698aa569d6',attest)


if __name__ == '__main__':unittest.main()
