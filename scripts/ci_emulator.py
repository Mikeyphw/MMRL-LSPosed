#!/usr/bin/env python3
"""Pinned-artifact, non-root API36 Android emulator instrumentation for GitHub Actions.

S08 privileged instrumentation remains EXCLUDED. This runner permits exactly one
fixed JUnit class with no Vector writes. PRs never receive stable signing secrets.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

PACKAGE = "io.github.mikeyphw.mmrl.lsposed.debug"
TEST_PACKAGE = PACKAGE + ".test"
CLASS = "io.github.mikeyphw.mmrl.lsposed.S09EmulatorUiTest"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
EXPECTED_TEST_COUNT = 4
REPO = "Mikeyphw/MMRL-LSPosed"
MAX_APK = 200_000_000


def sha(path: Path) -> str:
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def checked_file(path: Path, *, suffix='.apk') -> Path:
    if (path.is_symlink() or not path.is_file() or path.suffix != suffix or
            not 1_000 < path.stat().st_size <= MAX_APK):
        raise ValueError('Invalid or unsafe APK: ' + str(path))
    return path


def expected_host() -> tuple[str, int, int, str]:
    source = os.environ.get('GITHUB_SHA', '')
    run = os.environ.get('GITHUB_RUN_ID', '')
    attempt = os.environ.get('GITHUB_RUN_ATTEMPT', '')
    event = os.environ.get('GITHUB_EVENT_NAME', '')
    if (os.environ.get('GITHUB_REPOSITORY') != REPO or
            os.environ.get('GITHUB_ACTIONS') != 'true' or
            not re.fullmatch('[a-f0-9]{40}', source) or
            not run.isdigit() or int(run) < 1 or
            not attempt.isdigit() or int(attempt) < 1 or
            event not in ('push', 'pull_request')):
        raise ValueError('GitHub source/run identity not verified')
    return source, int(run), int(attempt), event


def run_checked(*argv: str, timeout=120) -> subprocess.CompletedProcess:
    result = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
    if result.returncode != 0:
        raise ValueError('Command returned failure: ' + argv[0] + ' / ' +
                         (result.stdout + result.stderr)[-400:])
    return result




def host_android_tool(name: str) -> Path:
    """Build-tools are installed by the existing hosted Android SDK setup."""
    sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
    if not sdk:
        raise ValueError('Android SDK root not set')
    tool = Path(sdk)/'build-tools'/'36.0.0'/name
    if not tool.is_file() or tool.is_symlink():
        raise ValueError('Pinned Android build tool missing: '+name)
    return tool



def parse_xmltree_instrumentation(output: str) -> tuple[str | None, str | None]:
    """Read exact instrumentation runner/target from compiled AndroidManifest tree.

    Restrict to instrumentation nodes, reject duplicate/ambiguous values,
    and require exact Android platform attribute IDs.
    """
    found: list[dict[str, str]] = []
    current: dict[str, str] | None = None
    root_depth = -1
    for line in output.splitlines():
        element = re.match(r"^(\s*)E:\s+([\w.-]+)(?:\s|$)", line)
        if element:
            depth = len(element.group(1))
            if current is not None and depth <= root_depth:
                found.append(current)
                current = None
            if element.group(2) == 'instrumentation':
                if current is not None:
                    raise ValueError('Nested instrumentation element')
                current = {}
                root_depth = depth
            continue
        if current is None:
            continue
        attribute = re.match(
            r'^\s*A:\s+(?:android:|http://schemas.android.com/apk/res/android:)'
            r'(name|targetPackage)\(0x([0-9A-Fa-f]{8})\)="([^"\r\n]*)"', line)
        if not attribute:
            continue
        key, resource_id, value = attribute.groups()
        if resource_id.lower() != {'name':'01010003', 'targetPackage':'01010021'}[key]:
            raise ValueError('Unexpected instrumentation manifest attribute ID')
        if key in current:
            raise ValueError('Duplicate instrumentation manifest attribute')
        current[key] = value
    if current is not None:
        found.append(current)
    if not found:
        return None, None
    if len(found) != 1 or set(found[0]) != {'name', 'targetPackage'}:
        raise ValueError('Incomplete or ambiguous instrumentation manifest XML')
    runner, target = found[0]['name'], found[0]['targetPackage']
    if not runner or not target:
        raise ValueError('Empty instrumentation runner/target manifest XML')
    # This is a source-locked PR gate, not a generic Android manifest reader.
    # Reject truncation or forged metadata here, before artifact staging.
    if runner != RUNNER or target != PACKAGE:
        raise ValueError('Unexpected instrumentation runner/target manifest XML')
    return target, runner


def package_badging(apk: Path) -> tuple[str, str | None, str | None]:
    """Return manifest package, instrumentation target and runner from aapt."""
    out = run_checked(str(host_android_tool('aapt')), 'dump', 'badging', str(apk)).stdout
    match = re.search(r"(?m)^package: name='([^']+)'", out)
    if not match:
        raise ValueError('APK package manifest not readable')
    # Android build tools can insert label or other attributes between the
    # runner and targetPackage, or reorder those attributes entirely.
    # Extract exactly one instrumentation entry and parse named attributes,
    # never infer a target/runner from untrusted position-dependent text.
    entries = re.findall(r"(?m)^instrumentation:\s+([^\r\n]+)$", out)
    if not entries:
        # AAPT badging sometimes omits the instrumentation record completely.
        # Verify compiled AndroidManifest.xml instead of inferring metadata.
        xmltree = run_checked(str(host_android_tool('aapt')), 'dump',
                              'xmltree', str(apk), 'AndroidManifest.xml').stdout
        target, runner = parse_xmltree_instrumentation(xmltree)
        return match.group(1), target, runner
    if len(entries) != 1:
        raise ValueError('Expected exactly one instrumentation manifest entry')
    attributes = re.findall(
        r"(?<!\S)([A-Za-z][A-Za-z0-9]*)='([^'\r\n]*)'", entries[0])
    if len(attributes) != len({key for key, _ in attributes}):
        raise ValueError('Duplicate instrumentation manifest attribute')
    attrs = dict(attributes)
    runner, target = attrs.get('name'), attrs.get('targetPackage')
    if not runner or not target:
        raise ValueError('Incomplete instrumentation runner/target manifest')
    return match.group(1), target, runner


def signing_certificate(apk: Path) -> str:
    out = run_checked(str(host_android_tool('apksigner')), 'verify',
                      '--verbose', '--print-certs', str(apk)).stdout
    matches = re.findall(r'(?im)^Signer #1 certificate SHA-256 digest:\s*([0-9a-f]{64})\s*$', out)
    if len(matches) != 1 or re.search(r'(?m)^Signer #2', out):
        raise ValueError('APK lacks an exclusive verified signing certificate')
    return matches[0]

def stage_pr(directory: Path):
    source, run, attempt, event = expected_host()
    if event != 'pull_request':
        raise ValueError('Ephemeral PR pair staging only allowed for pull_request')
    app = checked_file(Path('app/build/outputs/apk/debug/app-debug.apk'))
    test = checked_file(Path('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
    app_pkg, app_target, app_runner = package_badging(app)
    test_pkg, test_target, test_runner = package_badging(test)
    if (app_pkg, app_target, app_runner) != (PACKAGE, None, None) or \
       (test_pkg, test_target, test_runner) != (TEST_PACKAGE, PACKAGE, RUNNER):
        raise ValueError('PR APK manifest package/runner mismatch: '
                         f'app={(app_pkg, app_target, app_runner)!r}; '
                         f'test={(test_pkg, test_target, test_runner)!r}')
    certificate = signing_certificate(app)
    if signing_certificate(test) != certificate:
        raise ValueError('PR APK instrumentation signer differs from main APK')
    directory.mkdir(parents=True, exist_ok=False)
    manifest = {'schema': 'mmrl-lsposed.emulator.pr-pair.v1', 'repository': REPO,
                'source_sha': source, 'run_id': run, 'attempt': attempt,
                'debug_only': True, 'stable_signed': False, 'device_qualified': False,
                'app_package':PACKAGE,'test_package':TEST_PACKAGE,
                'test_runner':RUNNER, 'instrumentation_target':PACKAGE,
                'certificate_sha256':certificate,
                'app': {'file': 'app-debug.apk', 'sha256': sha(app), 'size':app.stat().st_size},
                'test': {'file': 'app-debug-androidTest.apk', 'sha256': sha(test), 'size':test.stat().st_size}}
    shutil.copy2(app, directory/manifest['app']['file'])
    shutil.copy2(test, directory/manifest['test']['file'])
    (directory/'pr-receipt.json').write_text(json.dumps(manifest, sort_keys=True, indent=2)+'\n')
    print('EMULATOR_PR_EPHEMERAL_PAIR_STAGED', source)


def validated_pair() -> tuple[Path, Path, dict]:
    source, run, attempt, event = expected_host()
    root = Path(os.environ.get('RUNNER_TEMP', '/missing-runner-temp'))
    if event == 'push':
        main = root/'emulator-main'
        lab = root/'emulator-lab'
        app = checked_file(main/'MMRL-LSPosed-debug-signed.apk')
        test = checked_file(lab/'MMRL-LSPosed-debug-androidTest.apk')
        signed = json.loads((main/'signed-debug.json').read_text())
        pair = json.loads((lab/'pair-receipt.json').read_text())
        for doc in (signed, pair):
            for key, value in {'source_sha': source, 'run_id':run, 'attempt':attempt}.items():
                if doc.get(key) != value:
                    raise ValueError('Signed artifact source/run mismatch')
        test_rec = pair.get('artifacts', {}).get(TEST_PACKAGE, {})
        cert = signed.get('certificate_sha256')
        if (signed.get('schema') != 'mmrl-lsposed.hosted-signed-debug.v1' or
            signed.get('status') != 'SIGNED_DEBUG_VERIFIED_NOT_RELEASE' or
            signed.get('repository') != REPO or
            signed.get('application_id') != PACKAGE or
            signed.get('debuggable') is not True or
            signed.get('release_qualified') is not False or
            signed.get('signed_apk_sha256') != sha(app) or
            signed.get('signed_apk_size') != app.stat().st_size or
            not isinstance(cert, str) or not re.fullmatch('[0-9a-f]{64}',cert) or
            pair.get('schema') != 'mmrl-lsposed.s08.instrumentation-pair.v1' or
            pair.get('status') != 'SIGNED_TEST_APKS_NOT_DEVICE_QUALIFIED' or
            pair.get('repository') != REPO or
            pair.get('device_qualified') is not False or
            pair.get('release_qualified') is not False or
            pair.get('main_apk_sha256') != sha(app) or
            pair.get('main_app_package') != PACKAGE or
            pair.get('cert_sha256') != cert or
            set(pair.get('artifacts',{})) != {
                TEST_PACKAGE,'com.mikeyphw.lsposed.hooklab.module',
                'com.mikeyphw.lsposed.hooklab.target'} or
            test_rec.get('sha256') != sha(test) or
            test_rec.get('size') != test.stat().st_size or
            test_rec.get('file') != test.name):
            raise ValueError('Signed APK pair/receipt mismatch')
    else:
        directory = root/'emulator-pr-pair'
        doc = json.loads((directory/'pr-receipt.json').read_text())
        if any(doc.get(k) != v for k,v in {
            'schema':'mmrl-lsposed.emulator.pr-pair.v1',
            'repository':REPO,'source_sha':source,'run_id':run,'attempt':attempt,
            'debug_only':True,'stable_signed':False,'device_qualified':False,
            'app_package':PACKAGE,'test_package':TEST_PACKAGE,
            'test_runner':RUNNER,'instrumentation_target':PACKAGE,
        }.items()):
            raise ValueError('Ephemeral PR receipt identity mismatch')
        app = checked_file(directory/'app-debug.apk')
        test = checked_file(directory/'app-debug-androidTest.apk')
        if (not re.fullmatch('[0-9a-f]{64}',str(doc.get('certificate_sha256',''))) or
            doc.get('app') != {'file':app.name,'sha256':sha(app),'size':app.stat().st_size} or
            doc.get('test') != {'file':test.name,'sha256':sha(test),'size':test.stat().st_size}):
            raise ValueError('PR APK digest/signature receipt mismatch')
    return app, test, {'source_sha':source,'run_id':run,'attempt':attempt,'event':event,
                        'app_sha256':sha(app),'test_sha256':sha(test)}


def junit_passed(returncode: int, stdout: str, stderr: str) -> bool:
    """Android 16 may omit INSTRUMENTATION_CODE; require exact 4-test JUnit success."""
    if returncode or any(marker in (stdout+stderr) for marker in (
        'FAILURES!!!', 'INSTRUMENTATION_FAILED', 'INSTRUMENTATION_ABORTED',
        'INSTRUMENTATION_RESULT: shortMsg=', 'INSTRUMENTATION_RESULT: longMsg=',
        'Process crashed.', 'Test run failed')):
        return False
    codes = re.findall(r'(?m)^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$', stdout)
    if codes and codes != ['-1']:
        return False
    return re.search(rf'(?m)^OK \({EXPECTED_TEST_COUNT} tests\)\s*$', stdout) is not None


def run_emulator(output:Path):
    app, test, identity=validated_pair()
    output.mkdir(parents=True, exist_ok=True)
    # Avoid existing devices / external hosts. The hosted Android emulator must be unique.
    devices = run_checked('adb','devices',timeout=25).stdout.splitlines()[1:]
    online = [s.split()[0] for s in devices if s.split()[1:] == ['device']]
    if len(online) != 1 or not re.fullmatch(r'emulator-\d+', online[0]):
        raise ValueError('Exactly one Android emulator required; physical devices refused')
    serial=online[0]
    emulator_api = run_checked('adb','-s',serial,'shell','getprop','ro.build.version.sdk').stdout.strip()
    if emulator_api != '36':
        raise ValueError('Android 16 (API36) emulator required')
    run_checked('adb','-s',serial,'install','-r',str(app),timeout=120)
    run_checked('adb','-s',serial,'install','-r','-t',str(test),timeout=120)
    command=('adb','-s',serial,'shell','am','instrument','-w','-e','class',CLASS,
             TEST_PACKAGE+'/'+RUNNER)
    executed=subprocess.run(command,capture_output=True,text=True,timeout=280)
    log=executed.stdout+('\n=== stderr ===\n'+executed.stderr if executed.stderr else '')
    (output/'androidtest.log').write_text(log)
    report={'schema':'mmrl-lsposed.emulator.ui.v1',**identity,'api':36,
            'test_class':CLASS,'expected_test_count':EXPECTED_TEST_COUNT,
            'root_required':False,'vector_writes_attempted':0,
            's08_physical_tests_executed':False,
            'status':'PASS_NON_ROOT_UI' if junit_passed(executed.returncode,executed.stdout,executed.stderr) else 'FAIL_EMULATOR_UI',
            'timestamp':int(time.time())}
    (output/'emulator-report.json').write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')
    if report['status']!='PASS_NON_ROOT_UI':
        raise ValueError('Emulator JUnit run did not pass: '+log[-650:])
    print('MMRL_LSPOSED_EMULATOR_UI_PASS', source_label(identity), 'API 36', EXPECTED_TEST_COUNT, 'tests')


def source_label(info:dict):return info['source_sha'][:12]


def verify(output:Path):
    app, test, identity=validated_pair()
    output.mkdir(parents=True,exist_ok=True)
    report={'schema':'mmrl-lsposed.emulator.artifact-preflight.v1',**identity,
            'app':app.name,'test':test.name,'status':'VERIFIED_NOT_EXECUTED'}
    (output/'preflight.json').write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')
    print('MMRL_LSPOSED_EMULATOR_PAIR_VERIFIED',source_label(identity))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=('stage-pr','verify','run'))
    parser.add_argument('--out',required=True,type=Path)
    args=parser.parse_args()
    try:
        if args.action=='stage-pr':stage_pr(args.out)
        elif args.action=='verify':verify(args.out)
        else:run_emulator(args.out)
    except (OSError,ValueError,KeyError,subprocess.SubprocessError) as exc:
        print('MMRL_LSPOSED_EMULATOR_REFUSED:',str(exc),file=sys.stderr)
        return 2
    return 0


if __name__=='__main__':raise SystemExit(main())
