#!/usr/bin/env python3
"""S08 real Android instrumentation acceptance, based on MMRL Phase06 R8.

Requires adb, gh, working Vector 2.2, primary Android user and explicit opt-in.
Never clears data, uninstalls apps, touches another module or reboots the phone.
No local Gradle build. Unknown mutation state leaves a durable recovery journal.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys

REPO = 'Mikeyphw/MMRL-LSPosed'
APP = 'io.github.mikeyphw.mmrl.lsposed.debug'
TEST = APP+'.test'
MODULE = 'com.mikeyphw.lsposed.hooklab.module'
TARGET = 'com.mikeyphw.lsposed.hooklab.target'
SCOPE = TARGET+'/0'
RUNNER = 'androidx.test.runner.AndroidJUnitRunner'
CONSENT = 'AUTHORIZE_S08_DISPOSABLE_LAB_ONLY'
PATHS = ('/data/adb/modules/zygisk_vector/cli',
         '/sbin/.magisk/modules/zygisk_vector/cli',
         '/debug_ramdisk/.magisk/modules/zygisk_vector/cli')
SHA40 = re.compile('[0-9a-f]{40}\\Z')
SHA64 = re.compile('[0-9a-f]{64}\\Z')


def proc(*args, timeout=160, check=True):
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise RuntimeError('Unavailable or timed-out device/host tool') from exc
    if check and r.returncode:
        raise RuntimeError('Command failed: '+str(args[:3])+' ('+str(r.returncode)+') / '+(r.stdout+r.stderr)[-250:])
    return r


def adb(serial, *args, timeout=160, check=True):
    return proc('adb','-s',serial,*args,timeout=timeout,check=check)


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream,'sha256').hexdigest()


def atomic_json(path:Path,data:dict,*,exclusive=False):
    path.parent.mkdir(parents=True,exist_ok=True)
    b=(json.dumps(data,sort_keys=True,indent=2)+'\n').encode()
    if exclusive:
        fd = os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'wb') as f:
            f.write(b);f.flush();os.fsync(f.fileno())
    else:
        temp=path.with_name(path.name+'.tmp')
        fd=os.open(temp,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        try:
            with os.fdopen(fd,'wb') as f:
                f.write(b);f.flush();os.fsync(f.fileno())
            temp.replace(path)
        finally:
            temp.unlink(missing_ok=True)
    directory=os.open(path.parent,os.O_RDONLY)
    try:os.fsync(directory)
    finally:os.close(directory)


def validate_source(run_id):
    r=json.loads(proc('gh','api',f'repos/{REPO}/actions/runs/{run_id}').stdout)
    if (r.get('status') != 'completed' or r.get('conclusion') != 'success' or
        r.get('head_branch') != 'main' or r.get('event') != 'push' or
        not SHA40.fullmatch(str(r.get('head_sha','')))):
        raise RuntimeError('Refusing non-successful or untrusted GitHub Actions run')
    attempt=int(r.get('run_attempt',0))
    if attempt < 1: raise RuntimeError('Missing run attempt')
    checkout=Path.home()/'Code'/'MMRL-LSPosed'
    verify_checkout_source(checkout, r['head_sha'])
    return r['head_sha'],attempt


# A later host-runner-only fix does not alter the Android APKs, test
# instrumentation, libxposed module, or the Vector write implementation.
# Allow an already-attested same-run APK pair ONLY when the exact CI source is
# an ancestor and every committed difference is among these host-only paths.
HOST_ONLY_COMPAT_PATHS = frozenset({
    'scripts/s08_device_lab.py',
    'scripts/tests/test_s08_instrumentation.py',
})


def verify_checkout_source(checkout: Path, source: str):
    current = proc('git','-C',str(checkout),'rev-parse','HEAD').stdout.strip()
    if current == source:
        return
    if not SHA40.fullmatch(current):
        raise RuntimeError('Invalid local checkout HEAD')
    ancestor = proc('git','-C',str(checkout),'merge-base','--is-ancestor',source,current,check=False)
    if ancestor.returncode:
        raise RuntimeError('GitHub APK source is not an ancestor of local checkout')
    changed = set(proc('git','-C',str(checkout),'diff','--name-only','--no-renames',
                       source,current).stdout.splitlines())
    if not changed or changed - HOST_ONLY_COMPAT_PATHS:
        raise RuntimeError('Checkout changed app/test binaries or unrelated code since GitHub build')
    # This guards changes committed after the provenance run; uncommitted
    # changes to binary/test inputs must not be used with the old artifact.
    binary_dirty = proc('git','-C',str(checkout),'diff','--name-only',source,'--',
                        'app','hook-lab-module','hook-lab-target',
                        'settings.gradle.kts','gradle','.github/workflows',check=True)
    if binary_dirty.stdout.strip():
        raise RuntimeError('App or test inputs differ from attested GitHub source')


def download_pairs(run_id,attempt,source,output):
    a=output/'signed-app';b=output/'signed-lab'
    a.mkdir();b.mkdir()
    for dest,name in ((a,f'mmrl-lsposed-signed-debug-{run_id}-attempt{attempt}'),
                      (b,f'mmrl-lsposed-s08-lab-{run_id}-attempt{attempt}')):
        proc('gh','run','download',str(run_id),'-R',REPO,'-n',name,'-D',str(dest),timeout=180)
    main=a/'MMRL-LSPosed-debug-signed.apk'
    proof=json.loads((a/'signed-debug.json').read_text())
    lab=json.loads((b/'pair-receipt.json').read_text())
    cert=proof.get('certificate_sha256','')
    if not SHA64.fullmatch(cert) or any(proof.get(k)!=v for k,v in {
        'schema':'mmrl-lsposed.hosted-signed-debug.v1','source_sha':source,
        'run_id':run_id,'attempt':attempt,'signed_apk_sha256':sha(main),
        'status':'SIGNED_DEBUG_VERIFIED_NOT_RELEASE','application_id':APP}.items()):
        raise RuntimeError('Signed main app receipt disagrees with GitHub run')
    if any(lab.get(k)!=v for k,v in {
        'schema':'mmrl-lsposed.s08.instrumentation-pair.v1',
        'status':'SIGNED_TEST_APKS_NOT_DEVICE_QUALIFIED','source_sha':source,
        'run_id':run_id,'attempt':attempt,'cert_sha256':cert,
        'main_apk_sha256':sha(main), 'main_app_package':APP,
        'authorized_scope':[SCOPE],'device_qualified':False}.items()):
        raise RuntimeError('Signed lab receipt disagrees with source or main APK')
    expected={TEST:'MMRL-LSPosed-debug-androidTest.apk',
              MODULE:'S08-HookLab-Module-debug.apk',
              TARGET:'S08-HookLab-Target-debug.apk'}
    if set(lab['artifacts'])!=set(expected):
        raise RuntimeError('Unexpected lab artifact package list')
    files={APP:main}
    for pkg,name in expected.items():
        entry=lab['artifacts'][pkg];apk=b/name
        if apk.is_symlink() or not apk.is_file() or apk.stat().st_size>150_000_000 or \
           entry.get('file')!=name or entry.get('sha256')!=sha(apk) or entry.get('size')!=apk.stat().st_size:
            raise RuntimeError('Untrusted lab APK bytes: '+pkg)
        files[pkg]=apk
    return files


def emulator_guard(serial):
    adb(serial,'get-state')
    props={key:adb(serial,'shell','getprop',key).stdout.strip().lower() for key in
        ('ro.product.model','ro.hardware','ro.kernel.qemu','ro.build.characteristics')}
    if (props['ro.kernel.qemu']=='1' or 'emulator' in props['ro.build.characteristics'] or
        'sdk_gphone' in props['ro.product.model'] or props['ro.hardware'] in ('ranchu','goldfish')):
        raise RuntimeError('Emulator refused; S08 root write acceptance requires physical Android')
    user=adb(serial,'shell','am','get-current-user').stdout.strip()
    if user!='0':raise RuntimeError('User 0 required for scope writes')
    return props


def installed(serial,pkg,folder):
    found=adb(serial,'shell','pm','list','packages','--user','0',pkg).stdout.splitlines()
    if 'package:'+pkg not in found:return None
    paths=adb(serial,'shell','pm','path','--user','0',pkg).stdout.splitlines()
    if len(paths)!=1 or not paths[0].startswith('package:/data/app/') or not paths[0].endswith('.apk'):
        raise RuntimeError('Cannot verify installed APK bytes: '+pkg)
    out=folder/('installed-'+pkg+'.apk')
    adb(serial,'pull',paths[0].removeprefix('package:'),str(out),timeout=95)
    if not out.is_file(): raise RuntimeError('Cannot retrieve installed APK: '+pkg)
    return out


def ensure_installed(serial,files,folder):
    missing=[]
    # Inspect every preinstalled package before installing any missing test package.
    for pkg,apk in files.items():
        existing=installed(serial,pkg,folder)
        if existing is None:
            if pkg==APP:
                raise RuntimeError('Install exact signed main app APK manually before S08 tests')
            missing.append(pkg)
        elif sha(existing)!=sha(apk):
            raise RuntimeError('Installed package differs from this run: '+pkg+
                '. Refusing replacement; uninstall/replace only after reviewing this package explicitly')
    for pkg in missing:
        # AndroidTest APK is testOnly; Android requires -t even for a verified signer.
        install_args = ('install','-t',str(files[pkg])) if pkg == TEST else ('install',str(files[pkg]))
        adb(serial,*install_args,timeout=160)
        got=installed(serial,pkg,folder)
        if got is None or sha(got)!=sha(files[pkg]):
            raise RuntimeError('Fresh test-only APK installation was not verified: '+pkg)


def choose_cli(serial):
    for path in PATHS:
        if adb(serial,'shell','su','-c','test -x '+path,check=False).returncode==0:
            return path
    raise RuntimeError('No allowlisted Vector CLI available with root permissions')


def vector(serial,path,*args):
    if tuple(args) not in (('status',),('modules','ls'),('scope','ls',MODULE),
                         ('modules','enable',MODULE),('modules','disable',MODULE),
                         ('scope','add',MODULE,SCOPE),('scope','rm',MODULE,SCOPE)):
        raise RuntimeError('Refusing unapproved Vector CLI command')
    raw=adb(serial,'shell','su','-c',path+' --json '+' '.join(args),timeout=45).stdout
    try:r=json.loads(raw)
    except json.JSONDecodeError as e:raise RuntimeError('Invalid Vector CLI JSON') from e
    if not isinstance(r,dict) or r.get('success') is not True:
        raise RuntimeError('Vector reported an unsuccessful command')
    return r['data']


def vector_baseline(serial,path):
    s=vector(serial,path,'status')
    if str(s.get('API Version'))!='102' or not str(s.get('Framework Version','')).lstrip('v').startswith('2.2'):
        raise RuntimeError('Only Vector 2.2/API102 is qualified')
    module_rows=vector(serial,path,'modules','ls')
    status={x['PACKAGE']:x['STATUS'] for x in module_rows}
    if len(status)!=len(module_rows) or status.get(MODULE) not in ('enabled','disabled'):
        raise RuntimeError('Disposable module not present in Vector inventory')
    entries=vector(serial,path,'scope','ls',MODULE)
    scope=sorted(f"{x['APP_PACKAGE']}/{int(x['USER_ID'])}" for x in entries)
    if scope not in ([],[SCOPE]):
        raise RuntimeError('Disposable module has unrelated targets; no writes allowed')
    return status,scope


def recover(serial,path,journal_path,journal):
    if any(journal.get(k)!=v for k,v in {'schema':'mmrl-lsposed.s08.host-journal.v1',
        'serial':serial,'module':MODULE,'target':TARGET,'original_enabled':False}.items()) or \
        journal.get('original_scope') not in ([],[SCOPE]):
        raise RuntimeError('Recovery journal does not authorize this exact disposable lab')
    before,scope=vector_baseline(serial,path)
    others={k:v for k,v in before.items() if k!=MODULE}
    if others!=journal.get('unrelated_modules'):
        raise RuntimeError('Unrelated module states changed; recovery refused')
    journal['phase']='HOST_LAB_RECOVERY_IN_PROGRESS'
    atomic_json(journal_path,journal)
    original=journal['original_scope']
    if scope!=original:
        vector(serial,path,'scope','rm' if original==[] else 'add',MODULE,SCOPE)
    current,_=vector_baseline(serial,path)
    if current[MODULE]=='enabled':
        vector(serial,path,'modules','disable',MODULE)
    post,final=vector_baseline(serial,path)
    if post[MODULE]!='disabled' or final!=original or {k:v for k,v in post.items() if k!=MODULE}!=others:
        journal['phase']='RECOVERY_REQUIRED';atomic_json(journal_path,journal)
        raise RuntimeError('Disposable module restoration not independently verified')
    journal['phase']='RESTORED';journal['restored']=True
    atomic_json(journal_path,journal)
    return journal


def instrumentation(serial,classname,params=None):
    args=['shell','am','instrument','-w','-e','class',
          'io.github.mikeyphw.mmrl.lsposed.'+classname]
    for key,value in (params or {}).items():
        args.extend(('-e',key,value))
    args.append(TEST+'/'+RUNNER)
    result=adb(serial,*args,timeout=240,check=False)
    if not instrumentation_passed(result):
        raise RuntimeError('Device AndroidTest did not report success (adb rc=' +
                           str(result.returncode) + '): ' + (result.stdout + result.stderr)[-650:])
    return result.stdout


def instrumentation_passed(result):
    """Require JUnit's one-test success, not an optional am-instrument marker.

    On some Android versions, `am instrument -w` ends with `OK (1 test)` but
    does not print `INSTRUMENTATION_CODE: -1`. A nonzero or conflicting explicit
    code, a crash marker, failed assertions, or missing exact JUnit success
    always refuses. The caller additionally checks the app's persisted JSON
    evidence before accepting the operation (and read-back for writes).
    """
    stdout = result.stdout or ''
    stderr = result.stderr or ''
    if result.returncode != 0 or any(x in stdout + stderr for x in (
        'FAILURES!!!', 'INSTRUMENTATION_FAILED', 'INSTRUMENTATION_ABORTED',
        'INSTRUMENTATION_RESULT: shortMsg=', 'INSTRUMENTATION_RESULT: longMsg=',
        'Process crashed.', 'Test run failed',
    )):
        return False
    codes = re.findall(r'(?m)^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$', stdout)
    if codes and (len(codes) != 1 or codes[0] != '-1'):
        return False
    return re.search(r'(?m)^OK \(1 test\)\s*$', stdout) is not None


def app_evidence(serial,filename):
    r=adb(serial,'shell','run-as',APP,'cat','files/'+filename)
    try:return json.loads(r.stdout)
    except json.JSONDecodeError as e:raise RuntimeError('Missing device evidence file') from e


def run(args):
    if args.recover_journal:
        journal_path=Path(args.recover_journal).expanduser().resolve(strict=True)
        doc=json.loads(journal_path.read_text())
        if not args.authorize_lab_changes or args.serial!=doc.get('serial'):
            raise RuntimeError('Explicit lab-only authorization and original serial required to recover')
        emulator_guard(args.serial)
        recover(args.serial,choose_cli(args.serial),journal_path,doc)
        print('S08_DISPOSABLE_LAB_RESTORED '+str(journal_path))
        return
    if not args.run_id or not args.serial:raise RuntimeError('GitHub run ID and ADB serial required')
    source,attempt=validate_source(args.run_id)
    serial=args.serial
    if not re.fullmatch(r'[A-Za-z0-9_.:-]{1,120}',serial):
        raise RuntimeError('Invalid ADB serial')
    parent=Path.home()/'.local/state/mmrl-lsposed/s08-instrumentation'/f'run-{args.run_id}-a{attempt}'/serial.replace(':','_')
    parent.mkdir(parents=True, exist_ok=True)
    # Independent immutable run evidence: read-only acceptance may precede a
    # later explicitly authorized write run. Never reuse or overwrite evidence.
    for number in range(1, 1000):
        directory=parent/f'session-{number:04d}'
        try:
            directory.mkdir(exist_ok=False)
            break
        except FileExistsError:
            continue
    else:
        raise RuntimeError('No fresh S08 acceptance session available')
    props=emulator_guard(serial)
    artifacts=download_pairs(args.run_id,attempt,source,directory)
    ensure_installed(serial,artifacts,directory)
    instrument_log=instrumentation(serial,'S08ReadOnlyDeviceTest')
    (directory/'read-only-instrumentation.log').write_text(instrument_log)
    read_report=app_evidence(serial,'s08_read_only_device.json')
    atomic_json(directory/'read-only.json',read_report,exclusive=True)
    if read_report.get('status')!='PASS_READ_ONLY_CONFIG':
        raise RuntimeError('Read-only instrumentation did not pass')
    print('S08_DEVICE_READ_ONLY_PASS '+str(directory/'read-only.json'))
    if not args.authorize_lab_changes:
        print('WRITE_SKIPPED: pass --authorize-lab-changes only for this disposable test pair')
        return
    vector_path=choose_cli(serial)
    states,scopes=vector_baseline(serial,vector_path)
    if states[MODULE]!='disabled':raise RuntimeError('Disposable module must initially be disabled')
    journal_path=directory/'host-recovery-journal.json'
    journal={'schema':'mmrl-lsposed.s08.host-journal.v1',
             'phase':'BASELINE_JOURNALED','run_id':args.run_id,'attempt':attempt,
             'source_sha':source,'serial':serial,'module':MODULE,'target':TARGET,
             'original_scope':scopes,'original_enabled':False,
             'unrelated_modules':{k:v for k,v in states.items() if k!=MODULE},
             'device':props,'restored':False}
    atomic_json(journal_path,journal,exclusive=True)
    nonce=secrets.token_hex(24)
    ticket=f'{nonce}:{source}:{MODULE}:{TARGET}'
    # Static shell syntax. All interpolated parts are lowercase hex and fixed package IDs.
    cmd=f"umask 077; printf %s '{ticket}' > files/s08_lab_write_token"
    adb(serial,'shell','run-as',APP,'sh','-c',cmd)
    journal['phase']='INSTRUMENTATION_STARTED';atomic_json(journal_path,journal)
    try:
        log=instrumentation(serial,'S08LabWriteDeviceTest',{
            's08_lab_authorization':CONSENT,'s08_lab_nonce':nonce,'s08_source_sha':source})
        (directory/'lab-instrumentation.log').write_text(log)
        record=app_evidence(serial,'s08_lab_write_device.json')
        atomic_json(directory/'lab-device.json',record,exclusive=True)
        live,scope=vector_baseline(serial,vector_path)
        if (record.get('status')!='PASS_LAB_WRITE_AND_RESTORE' or
            live.get(MODULE)!='disabled' or scope!=scopes or
            {k:v for k,v in live.items() if k!=MODULE}!=journal['unrelated_modules']):
            raise RuntimeError('S08 lab restoration or read-back was not independently verified')
        journal['phase']='RESTORED';journal['restored']=True
        journal['device_report']=record['status'];atomic_json(journal_path,journal)
        print('S08_LAB_WRITE_AND_RESTORE_PASS '+str(journal_path))
    except Exception:
        journal['phase']='RECOVERY_REQUIRED'
        atomic_json(journal_path,journal)
        print('S08_RECOVERY_REQUIRED '+str(journal_path),file=sys.stderr)
        raise


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('run_id',type=int,nargs='?')
    p.add_argument('serial',nargs='?')
    p.add_argument('--authorize-lab-changes',action='store_true')
    p.add_argument('--recover-journal',type=str)
    ns=p.parse_args()
    try:run(ns)
    except (RuntimeError,KeyError,ValueError,OSError,subprocess.SubprocessError) as exc:
        print('S08_DEVICE_REFUSED: '+str(exc),file=sys.stderr)
        return 2
    return 0

if __name__=='__main__':raise SystemExit(main())
