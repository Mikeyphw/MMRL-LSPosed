"""Offline fixtures for the hosted preflight; no Gradle or network execution."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import ci_ksp_resolution as ksp


class KspResolutionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.rows = []
        self.pins = {}

    def add(self, artifact, ext, data):
        name = f'{artifact}-{ksp.VERSION}{ext}'
        p = self.root / 'caches/modules-2/files-2.1' / ksp.GROUP / artifact / ksp.VERSION / hashlib.sha1(data).hexdigest() / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
        self.rows.append({'artifact':artifact,'name':name,'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data)})
        return p

    def fixture(self):
        def pom(artifact, dependency=''):
            return f'<project><groupId>{ksp.GROUP}</groupId><artifactId>{artifact}</artifactId><version>{ksp.VERSION}</version>{dependency}</project>'.encode()
        dep = f'<dependencies><dependency><groupId>{ksp.GROUP}</groupId><artifactId>{ksp.IMPL}</artifactId><version>{ksp.VERSION}</version></dependency></dependencies>'
        for artifact, data in [(ksp.MARKER,pom(ksp.MARKER,dep)),(ksp.IMPL,pom(ksp.IMPL))]:
            self.add(artifact,'.pom',data); self.pins[artifact] = hashlib.sha256(data).hexdigest()
        import io
        raw = io.BytesIO()
        with zipfile.ZipFile(raw,'w') as z:
            z.writestr('META-INF/gradle-plugins/com.google.devtools.ksp.properties','implementation-class=fixture.Plugin')
        return self.add(ksp.IMPL,'.jar',raw.getvalue())

    def test_complete_and_tampered_cache(self):
        jar = self.fixture()
        with patch.object(ksp,'POM_PINS',self.pins):
            complete, records = ksp.checked_cache(self.root)
            self.assertTrue(complete); self.assertEqual(len(records),3)
            jar.write_bytes(b'tampered')
            with self.assertRaises(ValueError): ksp.checked_cache(self.root)

    def test_symlink_cache_object_refused(self):
        jar = self.fixture(); data = jar.read_bytes(); jar.unlink()
        other = self.root/'outside.jar'; other.write_bytes(data); jar.symlink_to(other)
        with patch.object(ksp,'POM_PINS',self.pins), self.assertRaises(ValueError): ksp.checked_cache(self.root)

    def test_verified_cache_receipt_and_canonical_bytes(self):
        self.fixture(); known = {}; online = []
        with patch.object(ksp,'POM_PINS',self.pins):
            ksp.verify_records(self.rows,known,online=lambda row: online.append(row['name']) or row['sha256'])
            self.assertEqual(len(online),1)
            ksp.verify_records(self.rows,known,online=lambda row: self.fail('network on known object'))
            receipt = self.root/'receipt.json'; identity = {'version':ksp.VERSION}
            receipt.write_text(json.dumps({'schema':'mmrl-lsposed.ksp-cache-receipt.v1','identity':identity,'origin':'canonical-maven-https','objects':self.rows}))
            self.assertEqual(ksp.read_trusted_receipt(receipt,identity),known)
            with self.assertRaises(ValueError): ksp.read_trusted_receipt(receipt,{})
            bad = [dict(self.rows[-1],sha256='0'*64)]
            with self.assertRaises(ValueError): ksp.verify_records(bad,known)
            with self.assertRaises(ValueError): ksp.verify_records(self.rows,{},online=lambda row:'0'*64)

    def test_bounded_transient_retry(self):
        outcomes = iter([(1,ksp.PLUGIN_MISSING),(1,'Could not resolve HTTP status code 503'),(0,'')]); calls=[]; waits=[]
        def execute(root,offline): calls.append(offline); return next(outcomes)
        checks=iter([(False,[]),(True,self.rows)])
        attempts,_ = ksp.prepare(self.root,self.root,execute=execute,inspect=lambda p:next(checks),network=lambda:[{'target':'central_marker','status':'HTTP_429'}],sleep=waits.append)
        self.assertEqual(calls,[False]*3); self.assertEqual(waits,[15,45]); self.assertEqual(len(attempts),3)

    def test_offline_success_and_nonretryable_failure(self):
        calls=[]
        attempts,_=ksp.prepare(self.root,self.root,execute=lambda root,offline:(calls.append(offline) or (0,'')),inspect=lambda p:(True,[]))
        self.assertEqual(calls,[True]); self.assertEqual(attempts,[{'mode':'offline','exit':0}])
        for error in ('Could not resolve dependency verification failed HTTP 429','Compilation error','Could not resolve HTTP 404'):
            with self.assertRaises(ksp.ResolutionFailure) as caught:
                ksp.prepare(self.root,self.root,execute=lambda r,o:(1,error),inspect=lambda p:(False,[]),sleep=lambda n:self.fail('nontransient retry'))
            self.assertEqual(len(caught.exception.attempts),1)

    def test_exhausted_retries_fail(self):
        waits=[]
        with self.assertRaises(ksp.ResolutionFailure) as caught:
            ksp.prepare(self.root,self.root,execute=lambda r,o:(1,'Could not resolve HTTP 429'),inspect=lambda p:(False,[]),sleep=waits.append)
        self.assertEqual(waits,[15,45]); self.assertEqual(len(caught.exception.attempts),3)

    def test_workflows_preserve_required_gates(self):
        repo=Path(__file__).resolve().parents[2]
        for name in ('s01-hosted-devtool.yml','r4d-supply-chain-qualification.yml'):
            text=(repo/'.github/workflows'/name).read_text()
            self.assertIn('scripts/ci_ksp_resolution.py',text)
            self.assertIn('actions/cache/restore@5a3ec84eff668545956fd18022155c47e93e2684',text)
            self.assertIn('validate-wrappers: true',text)
            self.assertNotIn('--offline',text)
            if name.startswith('s01'):
                self.assertIn("if: success() && github.event_name == 'push' && github.ref == 'refs/heads/main'",text)
                for node in ('--node apk','--node lint','--node test'): self.assertIn(node,text)
            else:
                self.assertNotIn('actions/cache/save@',text)
                self.assertIn('releaseRuntimeClasspath',text)
