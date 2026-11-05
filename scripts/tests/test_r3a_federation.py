"""R3A run/attempt-bound CI artifact navigation and self-described Devtool index."""
from pathlib import Path
from unittest.mock import patch
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'app/src/main/java/io/github/mikeyphw/mmrl/lsposed'
SCRIPTS = ROOT / 'scripts'
sys.path.insert(0, str(SCRIPTS))
import ci_federation as fed

SOURCE = 'a' * 40
GRAPH = 'b' * 64
APK = 'c' * 64
SIGNER = 'd' * 64


def fixture():
    import hosted_devtool as devtool
    plan = {'schema': 'mmrl.lsposed.s01.devtool-plan.v1',
            'source_sha': SOURCE, 'devtool_sha': devtool.DEVTOOL_SHA,
            'graph_sha256': GRAPH, 'node_ids': fed.NODES,
            'package': devtool.PACKAGE, 'release_qualified': False, 'device_qualified': False}
    checked = dict(plan, status='VERIFIED_DEBUG_ONLY',run_id=123,attempt=2,
                   package_verified=True,signature_verified=True,
                   hooks_verified=False,apk_sha256=APK,apk_size=4096,
                   debug_signer_sha256=SIGNER)
    env = dict(GITHUB_SHA=SOURCE,GITHUB_RUN_ID='123',GITHUB_RUN_ATTEMPT='2',
               GITHUB_ACTIONS='true',GITHUB_REPOSITORY=devtool.REPO,
               GITHUB_EVENT_NAME='pull_request',GITHUB_REF='refs/pull/4/merge')
    return plan, checked, env

class R3AFederationContracts(unittest.TestCase):
    def test_federation_index_requires_exact_source_graph_and_node_receipts(self):
        plan, checked, env = fixture()
        result = fed.build_index(plan,checked,env)
        self.assertEqual(result['source_sha'],SOURCE)
        self.assertEqual(result['run_id'],123)
        self.assertEqual(result['attempt'],2)
        self.assertEqual(result['node_ids'],fed.NODES)
        self.assertEqual(result['evidence_artifact_name'], 'mmrl-lsposed-s01-evidence-123-attempt2')
        self.assertFalse(result['release_qualified'])
        self.assertFalse(result['device_qualified'])
        self.assertFalse(result['independently_attested'])
        self.assertFalse(result['hooks_verified'])
        for which,key,value in [
            ('plan','source_sha','0'*40),('plan','graph_sha256','0'*64),
            ('plan','devtool_sha','0'*40),('verification','graph_sha256','f'*64),
            ('verification','signature_verified',False),('verification','release_qualified',True),
            ('verification','run_id',124),('verification','apk_sha256','bad')]:
            with self.subTest(which=which,key=key):
                p,c,e=fixture(); (p if which=='plan' else c)[key]=value
                with self.assertRaises(ValueError):fed.build_index(p,c,e)

    def test_event_ref_and_attempt_fail_closed(self):
        for change in [dict(GITHUB_EVENT_NAME='push'),dict(GITHUB_RUN_ATTEMPT='0'),
                       dict(GITHUB_RUN_ID='abc'),dict(GITHUB_SHA='x'*40),
                       dict(GITHUB_REPOSITORY='Other/Repo'),dict(GITHUB_REF='refs/pull/4/head')]:
            p,c,e=fixture();e.update(change)
            with self.subTest(change=change),self.assertRaises(ValueError):fed.build_index(p,c,e)
        p,c,e=fixture();e.update(GITHUB_EVENT_NAME='push',GITHUB_REF='refs/heads/main')
        self.assertEqual(fed.build_index(p,c,e)['event'],'push')

    def test_generated_index_refuses_replacing_existing_file(self):
        plan,checked,env=fixture()
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);a=root/'plan.json';b=root/'verification.json';out=root/'federation-index.json'
            a.write_text(json.dumps(plan));b.write_text(json.dumps(checked))
            out.write_text('do not overwrite')
            with patch.dict(os.environ,env,clear=False):
                run=subprocess.run([sys.executable,str(SCRIPTS/'ci_federation.py'),
                     '--plan',str(a),'--verification',str(b),'--output',str(out)],capture_output=True,text=True)
                self.assertEqual(run.returncode,2)
                self.assertEqual(out.read_text(),'do not overwrite')
            out.unlink()
            with patch.dict(os.environ,env,clear=False):
                run=subprocess.run([sys.executable,str(SCRIPTS/'ci_federation.py'),
                     '--plan',str(a),'--verification',str(b),'--output',str(out)],capture_output=True,text=True)
                self.assertEqual(run.returncode,0,run.stdout+'\n'+run.stderr)
            self.assertEqual(json.loads(out.read_text())['schema'],'mmrl-lsposed.devtool-federation-index.v1')

    def test_ui_and_workflow_show_source_bound_evidence_without_install(self):
        ui=(SRC/'EcosystemPages.kt').read_text()
        policy=(SRC/'CiArtifactFederation.kt').read_text()
        api=(SRC/'GitHubEcosystem.kt').read_text()
        flow=(ROOT/'.github/workflows/s01-hosted-devtool.yml').read_text()
        self.assertIn('ci_current_attempt',ui)
        self.assertIn('ci_all_attempts',ui)
        self.assertIn('CiArtifactFederation.currentAttempt(active, artifacts.orEmpty())',ui)
        self.assertIn('GitHub metadata is not a Devtool receipt',ui)
        self.assertIn('fun currentAttempt(',policy)
        self.assertIn('artifact.digest',policy)
        self.assertIn('obj.optString("event")',api)
        self.assertIn('scripts/ci_federation.py',flow)
        self.assertIn('federation-index.json',flow)
        self.assertIn('path: ${{ runner.temp }}/s01-evidence/',flow)
        self.assertNotIn('Runtime.getRuntime()',policy)
        self.assertNotIn('VectorMutation',policy)

if __name__ == '__main__': unittest.main()
