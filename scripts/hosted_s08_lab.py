#!/usr/bin/env python3
"""Hosted-only, source-bound signing of S08 instrumentation and disposable lab APKs.

Never signs releases or runs instrumentation. Stable debug identity matches the
already-verified signed app, and secrets are never printed or checked in.
"""
from __future__ import annotations
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from zipfile import ZipFile

REPO = 'Mikeyphw/MMRL-LSPosed'
MAIN = 'io.github.mikeyphw.mmrl.lsposed.debug'
LAB_MODULE = 'com.mikeyphw.lsposed.hooklab.module'
LAB_TARGET = 'com.mikeyphw.lsposed.hooklab.target'
SHA64 = re.compile(r'[0-9a-f]{64}\Z')
CERT = re.compile(r'(?im)^Signer #1 certificate SHA-256 digest:\s*([0-9a-f]{64})\s*$')
PKG = re.compile(r"(?m)^package: name='([^']+)' ")


def sha(path: Path) -> str:
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def cli(*args: str) -> str:
    p = subprocess.run(args, text=True, capture_output=True, timeout=160)
    if p.returncode:
        raise RuntimeError('A pinned Android build-tools verification command failed')
    return p.stdout


def assert_regular(path: Path, limit=150_000_000) -> None:
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size < limit:
        raise RuntimeError('Missing, symlinked or oversized APK/receipt')


def execute() -> None:
    env = os.environ
    required = {'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': REPO,
                'GITHUB_REF': 'refs/heads/main', 'GITHUB_EVENT_NAME': 'push',
                'RUNNER_OS': 'Linux'}
    if any(env.get(k) != v for k,v in required.items()):
        raise RuntimeError('S08 lab signing is authorized for GitHub main pushes only')
    source = env.get('GITHUB_SHA','')
    if not re.fullmatch(r'[0-9a-f]{40}', source):
        raise RuntimeError('Untrusted source SHA')
    root = Path(env['GITHUB_WORKSPACE']).resolve(strict=True)
    if cli('git','-C',str(root),'rev-parse','HEAD').strip() != source:
        raise RuntimeError('Source does not match GitHub run')
    run, attempt = int(env['GITHUB_RUN_ID']), int(env['GITHUB_RUN_ATTEMPT'])
    if run <= 0 or attempt <= 0: raise RuntimeError('Invalid run identity')
    temp = Path(env['RUNNER_TEMP']).resolve(strict=True)
    existing = temp/'s01-signed'/'signed-debug.json'
    assert_regular(existing, 32768)
    signed_main = temp/'s01-signed'/'MMRL-LSPosed-debug-signed.apk'
    assert_regular(signed_main)
    main_receipt = json.loads(existing.read_text())
    if main_receipt.get('schema') != 'mmrl-lsposed.hosted-signed-debug.v1' or \
       main_receipt.get('status') != 'SIGNED_DEBUG_VERIFIED_NOT_RELEASE' or \
       main_receipt.get('run_id') != run or main_receipt.get('attempt') != attempt or \
       main_receipt.get('source_sha') != source or \
       main_receipt.get('signed_apk_sha256') != sha(signed_main):
        raise RuntimeError('Signed application proof is unavailable')
    policy = json.loads((root/'config/debug-signing.json').read_text())
    fingerprint = policy.get('certificate_sha256','')
    if not SHA64.fullmatch(fingerprint) or main_receipt.get('certificate_sha256') != fingerprint:
        raise RuntimeError('Pinned certificate does not match signed app')
    key_b64 = env.get('MMRL_DEBUG_KEYSTORE_B64','')
    password = env.get('MMRL_DEBUG_STORE_PASSWORD','')
    if not (key_b64 and password) or len(key_b64) > 12_000_000:
        raise RuntimeError('GitHub stable debug secrets unavailable')
    try:
        key = base64.b64decode(key_b64, validate=True)
    except Exception as error:
        raise RuntimeError('Keystore is not valid base64') from error
    if not 100 < len(key) < 8_000_000: raise RuntimeError('Invalid keystore size')
    sdk = Path(env['ANDROID_SDK_ROOT'])/'build-tools'/'36.0.0'
    signer = sdk/'apksigner'
    aapt = sdk/'aapt'
    for p in (signer,aapt):
        if not p.is_file(): raise RuntimeError('Pinned Android build tool missing')
    targets = [
        ('MMRL-LSPosed-debug-androidTest.apk', root/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', MAIN+'.test'),
        ('S08-HookLab-Module-debug.apk', root/'hook-lab-module/build/outputs/apk/debug/hook-lab-module-debug.apk', LAB_MODULE),
        ('S08-HookLab-Target-debug.apk', root/'hook-lab-target/build/outputs/apk/debug/hook-lab-target-debug.apk', LAB_TARGET),
    ]
    out = temp/'s08-device-lab'
    if out.is_symlink() or out.exists(): raise RuntimeError('Cannot overwrite S08 signed evidence')
    out.mkdir(mode=0o700)
    with tempfile.TemporaryDirectory(prefix='s08-lab-key-', dir=temp) as td:
        key_file = Path(td)/'key.p12'
        key_file.write_bytes(key)
        key_file.chmod(0o600)
        details = {}
        for name, input_apk, package in targets:
            assert_regular(input_apk)
            result = out/name
            cli(str(signer),'sign','--ks',str(key_file),'--ks-type','PKCS12',
                '--ks-key-alias','mmrl_lsposed_debug',
                '--ks-pass','env:MMRL_DEBUG_STORE_PASSWORD',
                '--key-pass','env:MMRL_DEBUG_STORE_PASSWORD',
                '--out',str(result),str(input_apk))
            assert_regular(result)
            verification = cli(str(signer),'verify','--verbose','--print-certs',str(result))
            matches = CERT.findall(verification)
            if matches != [fingerprint]:
                raise RuntimeError('S08 lab APK signing identity mismatch')
            m = PKG.search(cli(str(aapt),'dump','badging',str(result)))
            if m is None or m.group(1) != package:
                raise RuntimeError('Unexpected S08 artifact application package')
            details[package] = {'file':name,'sha256':sha(result),'size':result.stat().st_size}
    with ZipFile(out/'S08-HookLab-Module-debug.apk') as z:
        expected = {
            'META-INF/xposed/java_init.list':'com.mikeyphw.lsposed.hooklab.module.LabHookModule',
            'META-INF/xposed/scope.list':LAB_TARGET,
        }
        for path,value in expected.items():
            if z.read(path).decode().strip() != value:
                raise RuntimeError('Modern Xposed module has an unexpected entry/scope')
        properties = z.read('META-INF/xposed/module.prop').decode()
        if 'staticScope=true' not in properties or 'targetApiVersion=102' not in properties:
            raise RuntimeError('Lab must be Vector API 102 static scoped')
    receipt = {
        'schema':'mmrl-lsposed.s08.instrumentation-pair.v1',
        'status':'SIGNED_TEST_APKS_NOT_DEVICE_QUALIFIED',
        'repository':REPO, 'source_sha':source, 'run_id':run, 'attempt':attempt,
        'cert_sha256':fingerprint, 'main_app_package':MAIN,
        'main_apk_sha256':sha(signed_main), 'artifacts':details,
        'authorized_scope':[LAB_TARGET+'/0'], 'hook_verified':False,
        'restored':False, 'device_qualified':False, 'release_qualified':False,
    }
    (out/'pair-receipt.json').write_text(json.dumps(receipt,sort_keys=True,indent=2)+'\n')
    print('S08_INSTRUMENTATION_SIGNED_PAIR_VERIFIED '+json.dumps({
        'run':run,'source':source,'cert_sha256':fingerprint,
        'packages':list(details), 'device_qualified':False},sort_keys=True))

if __name__=='__main__':
    try: execute()
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.SubprocessError, FileNotFoundError) as e:
        # Only report the class; never echo keystore, raw CLI output or password.
        print('S08_LAB_SIGNING_REFUSED: '+type(e).__name__,file=sys.stderr)
        raise SystemExit(2)
