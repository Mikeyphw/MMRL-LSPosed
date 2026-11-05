"""R3C source/run/graph-bounded portable Devtool evidence contract."""
from __future__ import annotations
import json, os, sys, tempfile, unittest, zipfile
from pathlib import Path
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
import ci_federation as r3a
import ci_federation_portable as portable
import hosted_devtool as host

SOURCE='a'*40
ENV=dict(GITHUB_SHA=SOURCE,GITHUB_RUN_ID='123',GITHUB_RUN_ATTEMPT='2',
         GITHUB_ACTIONS='true',GITHUB_REPOSITORY=host.REPO,
         GITHUB_EVENT_NAME='pull_request',GITHUB_REF='refs/pull/4/merge')
EXPECTED=dict(repository=host.REPO,source_sha=SOURCE,run_id=123,attempt=2,
              event='pull_request',devtool_sha=host.DEVTOOL_SHA)

def input_files():
    plan=dict(schema='mmrl.lsposed.s01.devtool-plan.v1',source_sha=SOURCE,
              devtool_sha=host.DEVTOOL_SHA,graph_sha256='b'*64,node_ids=r3a.NODES,
              package=host.PACKAGE,release_qualified=False,device_qualified=False)
    receipt=dict(plan,status='VERIFIED_DEBUG_ONLY',run_id=123,attempt=2,
                 package_verified=True,signature_verified=True,hooks_verified=False,
                 apk_sha256='c'*64,apk_size=4096,debug_signer_sha256='d'*64)
    index=r3a.build_index(plan,receipt,ENV)
    return {name:json.dumps(obj,sort_keys=True).encode() for name,obj in
            zip(portable.FILES,(plan,receipt,index))}

def bundle():
    values=input_files()
    values[portable.CONTRACT]=json.dumps(portable.compose(values),sort_keys=True).encode()
    return values

class R3CPortableTests(unittest.TestCase):
    def test_valid_cross_consumer_consistency_and_digest(self):
        doc=portable.check(bundle(),EXPECTED)
        self.assertEqual(doc['producer']['run_id'],123)
        self.assertEqual(doc['devtool']['node_ids'],r3a.NODES)
        self.assertEqual(doc['contract_sha256'],portable.sha(portable.canonical(
            {k:v for k,v in doc.items() if k!='contract_sha256'})))
        self.assertFalse(doc['qualifications']['release_qualified'])
        self.assertFalse(doc['qualifications']['independently_attested'])

    def test_unchangeable_external_git_identity(self):
        for field,value in [('source_sha','f'*40),('repository','other/repo'),
                            ('run_id',124),('attempt',3),('event','push'),
                            ('devtool_sha','0'*40)]:
            with self.subTest(field=field),self.assertRaisesRegex(ValueError,'Independent GitHub identity'):
                portable.check(bundle(),dict(EXPECTED,**{field:value}))

    def test_tampering_forged_qualifications_and_digests_refused(self):
        for path,key,value in [('plan.json','source_sha','f'*40),
                ('verification.json','graph_sha256','f'*64),
                ('verification.json','signature_verified',False),
                ('verification.json','device_qualified',True),
                ('federation-index.json','release_qualified',True),
                ('federation-index.json','debug_signer_sha256','0'*64),
                (portable.CONTRACT,'contract_sha256','0'*64),
                (portable.CONTRACT,'trust_model','signed attestation')]:
            with self.subTest(path=path,key=key),self.assertRaises(ValueError):
                data=bundle();doc=json.loads(data[path]);doc[key]=value
                data[path]=json.dumps(doc).encode();portable.check(data,EXPECTED)

    def test_zip_archive_exact_members_and_bounds(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'evidence.zip';items=bundle()
            def pack(rows):
                with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as z:
                    for name,value in rows:z.writestr(name,value)
            pack(items.items())
            self.assertEqual(portable.archive_files(path),items)
            self.assertEqual(portable.check(portable.archive_files(path),EXPECTED)['producer']['attempt'],2)
            for name in ('../escape.json','nested/payload.json','plan.json'):
                with self.subTest(name=name),self.assertRaises(ValueError):
                    pack([*items.items(),(name,b'{}')]);portable.archive_files(path)
            pack([(name,b'0'*(portable.MAX_FILE+1) if name=='plan.json' else v)
                  for name,v in items.items()])
            with self.assertRaises(ValueError):portable.archive_files(path)
            pack(items.items())
            with zipfile.ZipFile(path,'a') as z:
                symlink=zipfile.ZipInfo('symlink');symlink.create_system=3
                symlink.external_attr=0o120777 << 16
                z.writestr(symlink,b'../plan.json')
            with self.assertRaises(ValueError):portable.archive_files(path)

    def test_repeated_json_key_downgrade_is_rejected(self):
        data=bundle()
        data['plan.json']=data['plan.json'].replace(b'"source_sha":',
            b'"source_sha":"forged", "source_sha":',1)
        with self.assertRaisesRegex(ValueError,'Repeated JSON key'):
            portable.check(data,EXPECTED)

    def test_generate_once_and_refuse_wrong_hosted_run(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            for name,value in input_files().items():(root/name).write_bytes(value)
            with patch.dict(os.environ,ENV):
                result=portable.generate(root)
                self.assertEqual(portable.check(portable.directory_files(root,require_contract=True),EXPECTED),result)
                with self.assertRaisesRegex(ValueError,'overwrite'):portable.generate(root)
                (root/portable.CONTRACT).unlink()
                with patch.dict(os.environ,dict(ENV,GITHUB_RUN_ID='999')):
                    with self.assertRaises(ValueError):portable.generate(root)
            self.assertFalse((root/portable.CONTRACT).exists())

    def test_workflow_integration_preserves_signing_and_provenance_boundary(self):
        flow=(ROOT/'.github/workflows/s01-hosted-devtool.yml').read_text()
        self.assertEqual(flow.count('R3C_PORTABLE_CONTRACT_BEGIN'),1)
        self.assertEqual(flow.count('ci_federation_portable.py generate'),1)
        self.assertEqual(flow.count('ci_federation_portable.py verify'),1)
        self.assertLess(flow.index('R3A_FEDERATION_INDEX_END'),flow.index('R3C_PORTABLE_CONTRACT_BEGIN'))
        self.assertLess(flow.index('R3C_PORTABLE_CONTRACT_END'),flow.index('Publish source/graph verification evidence'))
        self.assertIn('python3 scripts/ci_federation.py',flow)
        self.assertIn("if: github.event_name == 'push' && github.ref == 'refs/heads/main'",flow)

if __name__=='__main__':unittest.main()
