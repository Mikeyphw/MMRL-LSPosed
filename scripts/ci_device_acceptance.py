#!/usr/bin/env python3
"""R4C guarded, operator-controlled release-candidate device trial.

Default is read-only. Does not qualify a release, hooks, app data compatibility,
or a device; never calls uninstall/clear/reboot/root/Vector or accesses keys.
See docs/R4C_DEVICE_ACCEPTANCE.md for Android downgrade limitations.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from typing import Any

import ci_production_signing as r4b

SOURCE = re.compile(r'[a-f0-9]{40}\Z')
SERIAL = re.compile(r'[A-Za-z0-9._:-]{1,80}\Z')
PM_PATH = re.compile(r'package:(/[-A-Za-z0-9_./@=+~]+/base\.apk)\Z')
SCHEMA = 'mmrl-lsposed.r4c.device-trial.v1'
APP = r4b.APP_ID
MAX_JSON = 32768


class Refused(r4b.Refused):
    pass


def require(condition: bool, reason: str) -> None:
    if not condition:
        raise Refused(reason)


def git(root: Path, *args: str) -> str:
    try:
        p = subprocess.run(['git', '-C', str(root), *args], capture_output=True,
                           text=True, timeout=15, check=False)
    except (OSError, subprocess.TimeoutExpired) as e:
        raise Refused('Git source verification unavailable') from e
    if p.returncode != 0 or len(p.stdout) > 32768:
        raise Refused('Git source verification failed')
    return p.stdout.strip()


def source_policy(root: Path, source: str) -> str:
    require(SOURCE.fullmatch(source) is not None, 'Invalid candidate source SHA')
    require((root / '.git').is_dir(), 'Git checkout required for exact candidate policy')
    origin = git(root, 'remote', 'get-url', 'origin')
    require(re.fullmatch(r'(?:git@github\.com:|https://github\.com/|ssh://git@github\.com/)Mikeyphw/MMRL-LSPosed(?:\.git)?/?', origin) is not None,
            'Unexpected candidate source repository origin')
    # The candidate source must be merged to origin/main; a PR/temporary head cannot
    # authorize a production candidate. A reachable commit may still be a historical
    # release policy: fetch its exact file, never substitute current checkout policy.
    require(subprocess.run(['git', '-C', str(root), 'merge-base', '--is-ancestor',
                            source, 'origin/main'], capture_output=True, timeout=15).returncode == 0,
            'Candidate source not part of fetched origin/main history')
    require(git(root, 'rev-parse', source) == source, 'Candidate source abbreviated or unresolved')
    release = r4b.json_unique(git(root, 'show', source + ':config/release-signing.json').encode())
    debug = r4b.json_unique(git(root, 'show', source + ':config/debug-signing.json').encode())
    return r4b.exact_release_policy(release, debug, require_enrolled=True) or ''


def external(argv: list[str], *, timeout: int = 120, max_output: int = 2_000_000) -> str:
    # No shell, no root, no downloaded executables. Limit and never log full stderr.
    require(all(isinstance(a, str) and 0 < len(a) < 2048 for a in argv), 'Invalid subprocess argument')
    try:
        p = subprocess.run(argv, capture_output=True, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise Refused('Required local Android/ADB tool unavailable or timed out') from exc
    if p.returncode != 0 or len(p.stdout) > max_output:
        raise Refused('Android/ADB operation failed or exceeded output limit')
    try:
        return p.stdout.decode('utf-8', errors='strict')
    except UnicodeDecodeError as exc:
        raise Refused('Invalid Android tool output encoding') from exc


def tool(path: str, name: str) -> str:
    p = Path(path)
    require(p.name == name and p.is_file() and os.access(p, os.X_OK) and not p.is_symlink(),
            'Required reviewed Android tool path missing or unsafe')
    return str(p)


def apk_metadata(apk: Path, *, aapt: str, apksigner: str, cert: str) -> tuple[int, str]:
    r4b.regular_file(apk, r4b.MAX_APK)
    signature = external([apksigner, 'verify', '--verbose', '--print-certs',
                          '--min-sdk-version', '26', str(apk)])
    r4b.parse_signer(signature, cert)
    badging = external([aapt, 'dump', 'badging', str(apk)])
    return r4b.parse_apk_metadata(badging)


def verify_candidate(root: Path, candidate_dir: Path, *, source: str,
                     run: int, attempt: int, aapt: str, apksigner: str) -> dict:
    require(type(run) is int and run > 0 and type(attempt) is int and attempt > 0,
            'Invalid workflow run identity')
    cert = source_policy(root, source)
    require(candidate_dir.is_dir() and not candidate_dir.is_symlink(),
            'Candidate directory missing or unsafe')
    names = {p.name for p in candidate_dir.iterdir()}
    require(names == {r4b.OUTPUT_NAME, r4b.RECEIPT_NAME}, 'Unexpected candidate directory contents')
    apk = r4b.regular_file(candidate_dir / r4b.OUTPUT_NAME, r4b.MAX_APK)
    receipt = r4b.read_json(candidate_dir / r4b.RECEIPT_NAME)
    code, version = apk_metadata(apk, aapt=aapt, apksigner=apksigner, cert=cert)
    r4b.validate_receipt(receipt, source=source, run=run, attempt=attempt,
                         cert=cert, apk=apk, version_code=code, version_name=version)
    return receipt


def adb(serial: str, args: list[str], timeout: int = 60) -> str:
    require(SERIAL.fullmatch(serial) is not None, 'Invalid ADB serial')
    require(args and args[0] in {'get-state', 'shell', 'pull', 'install'}, 'Unknown device operation')
    return external(['adb', '-s', serial, *args], timeout=timeout)


def device_readonly(serial: str, *, dest: Path) -> dict:
    # 'adb pull' reads the installed base APK to the *host* temporary directory.
    # It is not a device filesystem mutation. Split installs and inaccessible APKs
    # fail closed; signing of the installed bytes is checked separately.
    require(adb(serial, ['get-state']).strip() == 'device', 'Device not online')
    sdk = adb(serial, ['shell', 'getprop', 'ro.build.version.sdk']).strip()
    require(sdk.isascii() and sdk.isdecimal() and int(sdk) >= 26,
            'Android API level unsupported')
    model = adb(serial, ['shell', 'getprop', 'ro.product.model']).strip()
    require(0 < len(model) <= 100 and '\n' not in model and '\r' not in model,
            'Invalid device model')
    paths = adb(serial, ['shell', 'pm', 'path', APP]).strip().splitlines()
    require(len(paths) == 1, 'Package not installed, split installation or unknown state')
    match = PM_PATH.fullmatch(paths[0].strip())
    require(match is not None, 'Unexpected installed base APK path')
    require(not dest.exists() and not dest.is_symlink(), 'Readback output collision')
    adb(serial, ['pull', match.group(1), str(dest)], timeout=180)
    r4b.regular_file(dest, r4b.MAX_APK)
    return {'model': model, 'api': int(sdk), 'installed_sha256': r4b.sha256(dest)}


def make_plan(root: Path, candidate_dir: Path, baseline: Path, *, source: str,
              run: int, attempt: int, serial: str, aapt: str, apksigner: str) -> dict:
    candidate = verify_candidate(root, candidate_dir, source=source, run=run,
                                 attempt=attempt, aapt=aapt, apksigner=apksigner)
    require(SERIAL.fullmatch(serial) is not None, 'Unsafe device serial')
    cert = candidate['certificate_sha256']
    old_code, old_name = apk_metadata(baseline, aapt=aapt, apksigner=apksigner, cert=cert)
    require(old_code < candidate['version_code'],
            'Candidate must be a strictly higher version than known installed baseline')
    with tempfile.TemporaryDirectory(prefix='r4c-readonly-') as temp:
        readback = Path(temp) / 'base.apk'
        device = device_readonly(serial, dest=readback)
        require(device['installed_sha256'] == r4b.sha256(baseline),
                'Installed APK bytes differ from operator-supplied baseline')
        # Independently inspect the installed APK bytes, not just supplied baseline.
        require(apk_metadata(readback, aapt=aapt, apksigner=apksigner, cert=cert) ==
                (old_code, old_name), 'Installed APK identity differs from supplied baseline')
    return {
        'schema': SCHEMA,
        'status': 'READY_FOR_EXPLICIT_TRIAL_NOT_DEVICE_QUALIFIED',
        'repository': r4b.REPOSITORY,
        'application_id': APP,
        'source_sha': source, 'run_id': run, 'attempt': attempt,
        'serial': serial, 'model': device['model'], 'api': device['api'],
        'certificate_sha256': cert,
        'baseline_sha256': r4b.sha256(baseline),
        'baseline_version_code': old_code, 'baseline_version_name': old_name,
        'candidate_sha256': candidate['apk_sha256'],
        'candidate_version_code': candidate['version_code'],
        'candidate_version_name': candidate['version_name'],
        'rollback_guaranteed': False,
        'release_qualified': False, 'device_qualified': False,
        'hooks_verified': False, 'independently_attested': False,
    }


def checked_plan(data: dict) -> None:
    require(data.get('schema') == SCHEMA and
            data.get('status') == 'READY_FOR_EXPLICIT_TRIAL_NOT_DEVICE_QUALIFIED' and
            data.get('rollback_guaranteed') is False and
            data.get('release_qualified') is False and
            data.get('device_qualified') is False and
            data.get('hooks_verified') is False and
            data.get('independently_attested') is False,
            'Plan is missing or claims an unsafe qualification')


def write_exclusive(path: Path, obj: dict) -> None:
    require(not path.is_symlink() and not path.exists(), 'Receipt/journal already exists')
    require(path.parent.is_dir() and not path.parent.is_symlink(), 'Unsafe receipt directory')
    r4b.save_receipt(path, obj)


def journal_update(path: Path, obj: dict, *, old_status: str, new_status: str) -> None:
    current = r4b.read_json(path)
    require(current.get('status') == old_status, 'Journal status changed or unexpected')
    updated = {**obj, 'status': new_status}
    # os.replace is atomic for this host path; never rewrite an unrelated file.
    temp = path.with_name('.r4c-journal-' + str(os.getpid()) + '.tmp')
    write_exclusive(temp, updated)
    os.replace(temp, path)


def run_trial(root: Path, candidate_dir: Path, baseline: Path, *, source: str,
              run: int, attempt: int, serial: str, aapt: str, apksigner: str,
              plan_path: Path, journal_path: Path,
              ack_mutation: bool, ack_backup: bool, ack_rollback: bool) -> dict:
    require(ack_mutation and ack_backup and ack_rollback,
            'Explicit mutation, backup and downgrade-risk acknowledgements required')
    require(not journal_path.exists() and not journal_path.is_symlink(), 'Trial journal already exists')
    expected = r4b.read_json(plan_path)
    checked_plan(expected)
    refreshed = make_plan(root, candidate_dir, baseline, source=source,
                          run=run, attempt=attempt, serial=serial,
                          aapt=aapt, apksigner=apksigner)
    require(expected == refreshed, 'Plan or device changed since read-only preflight')
    journal = {**refreshed, 'status': 'TRIAL_STARTED_NOT_QUALIFIED',
               'mutation': 'adb install -r (candidate only)',
               'restore_attempted': False}
    write_exclusive(journal_path, journal)   # durable before ANY device mutation
    apk = candidate_dir / r4b.OUTPUT_NAME
    try:
        response = adb(serial, ['install', '-r', str(apk)], timeout=180)
        require(response.strip().splitlines()[-1:] == ['Success'],
                'Android install did not explicitly confirm success')
    except Refused:
        journal_update(journal_path, journal, old_status='TRIAL_STARTED_NOT_QUALIFIED',
                       new_status='INSTALL_FAILED_STATE_REQUIRES_INSPECTION')
        raise
    with tempfile.TemporaryDirectory(prefix='r4c-postinstall-') as temp:
        try:
            device = device_readonly(serial, dest=Path(temp) / 'base.apk')
            require(device['installed_sha256'] == refreshed['candidate_sha256'],
                    'Installed APK bytes do not match the candidate')
            require(device['model'] == refreshed['model'] and device['api'] == refreshed['api'],
                    'Device identity changed after installation')
        except (Refused, r4b.Refused, OSError, ValueError):
            journal_update(journal_path, journal, old_status='TRIAL_STARTED_NOT_QUALIFIED',
                           new_status='INSTALL_UNVERIFIED_STATE_REQUIRES_INSPECTION')
            raise
    journal_update(journal_path, journal, old_status='TRIAL_STARTED_NOT_QUALIFIED',
                   new_status='TRIAL_INSTALLED_HASH_VERIFIED_NOT_QUALIFIED')
    return r4b.read_json(journal_path)


def attempt_restore(root: Path, candidate_dir: Path, baseline: Path, *, source: str,
                    run: int, attempt: int, serial: str, aapt: str, apksigner: str,
                    journal_path: Path, ack_downgrade: bool, ack_backup: bool) -> dict:
    require(ack_downgrade and ack_backup, 'Explicit downgrade/backup acknowledgement required')
    journal = r4b.read_json(journal_path)
    checked_plan({**journal, 'status': 'READY_FOR_EXPLICIT_TRIAL_NOT_DEVICE_QUALIFIED'})
    require(journal.get('status') == 'TRIAL_INSTALLED_HASH_VERIFIED_NOT_QUALIFIED' and
            journal.get('schema') == SCHEMA and journal.get('serial') == serial,
            'No exact completed trial journal authorizing restore')
    candidate = verify_candidate(root, candidate_dir, source=source, run=run,
                                 attempt=attempt, aapt=aapt, apksigner=apksigner)
    cert = candidate['certificate_sha256']
    old_code, old_name = apk_metadata(baseline, aapt=aapt, apksigner=apksigner, cert=cert)
    require(journal.get('candidate_sha256') == candidate['apk_sha256'] and
            journal.get('source_sha') == source and journal.get('run_id') == run and
            journal.get('attempt') == attempt and journal.get('baseline_sha256') == r4b.sha256(baseline) and
            journal.get('baseline_version_code') == old_code and journal.get('baseline_version_name') == old_name,
            'Candidate or baseline changed since trial')
    with tempfile.TemporaryDirectory(prefix='r4c-prerestore-') as temp:
        device = device_readonly(serial, dest=Path(temp) / 'base.apk')
    require(device['installed_sha256'] == journal['candidate_sha256'] and
            device['model'] == journal['model'] and device['api'] == journal['api'],
            'Candidate is no longer installed or device changed')
    journal_update(journal_path, journal, old_status='TRIAL_INSTALLED_HASH_VERIFIED_NOT_QUALIFIED',
                   new_status='RESTORE_ATTEMPTED_STATE_UNVERIFIED')
    try:
        # Android may refuse downgrade of a non-debuggable APK. NEVER uninstall,
        # clear data, downgrade via root, or claim rollback succeeded on failure.
        response = adb(serial, ['install', '-r', '-d', str(baseline)], timeout=180)
        require(response.strip().splitlines()[-1:] == ['Success'],
                'Android downgrade did not explicitly confirm success')
        with tempfile.TemporaryDirectory(prefix='r4c-postrestore-') as temp:
            device = device_readonly(serial, dest=Path(temp) / 'base.apk')
        require(device['installed_sha256'] == journal['baseline_sha256'],
                'Restoration APK hash not verified')
    except Refused:
        journal_update(journal_path, journal, old_status='RESTORE_ATTEMPTED_STATE_UNVERIFIED',
                       new_status='RESTORE_FAILED_MANUAL_RECOVERY_REQUIRED')
        raise
    journal_update(journal_path, journal, old_status='RESTORE_ATTEMPTED_STATE_UNVERIFIED',
                   new_status='BASELINE_APK_RESTORED_NOT_DATA_VERIFIED')
    return r4b.read_json(journal_path)


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('action', choices=('verify', 'plan', 'trial', 'restore'))
    p.add_argument('--root', type=Path, default=Path.cwd())
    p.add_argument('--candidate-dir', type=Path, required=True)
    p.add_argument('--source', required=True)
    p.add_argument('--run', type=int, required=True)
    p.add_argument('--attempt', type=int, required=True)
    p.add_argument('--aapt', required=True)
    p.add_argument('--apksigner', required=True)
    p.add_argument('--baseline-apk', type=Path)
    p.add_argument('--serial')
    p.add_argument('--plan-file', type=Path)
    p.add_argument('--journal-file', type=Path)
    p.add_argument('--ack-device-mutation', action='store_true')
    p.add_argument('--ack-backup-confirmed', action='store_true')
    p.add_argument('--ack-downgrade-may-fail', action='store_true')
    args = p.parse_args()
    try:
        root = args.root.resolve(strict=True)
        aapt, signer = tool(args.aapt, 'aapt'), tool(args.apksigner, 'apksigner')
        candidate_dir = args.candidate_dir.absolute()
        common = dict(root=root, candidate_dir=candidate_dir,
                      source=args.source, run=args.run, attempt=args.attempt,
                      aapt=aapt, apksigner=signer)
        if args.action == 'verify':
            result = verify_candidate(**common)
            print('R4C_CANDIDATE_BYTES_SIGNER_RECEIPT_PASS_NOT_RELEASE',
                  result['source_sha'][:12], result['run_id'], result['attempt'])
        else:
            require(args.baseline_apk is not None and args.serial and args.plan_file is not None,
                    'Baseline, serial, and plan file required for device trial')
            baseline = args.baseline_apk.absolute()
            require(r4b.regular_file(baseline, r4b.MAX_APK).is_file(), 'Invalid baseline APK')
            extra = dict(**common, baseline=baseline, serial=args.serial)
            if args.action == 'plan':
                plan = make_plan(**extra)
                write_exclusive(args.plan_file.absolute(), plan)
                print('R4C_READONLY_PLAN_PASS_NOT_QUALIFIED', plan['source_sha'][:12], plan['serial'])
            elif args.action == 'trial':
                require(args.journal_file is not None, 'Trial journal required')
                result = run_trial(**extra, plan_path=args.plan_file.absolute(),
                                   journal_path=args.journal_file.absolute(),
                                   ack_mutation=args.ack_device_mutation,
                                   ack_backup=args.ack_backup_confirmed,
                                   ack_rollback=args.ack_downgrade_may_fail)
                print('R4C_INSTALLED_CANDIDATE_HASH_PASS_NOT_DEVICE_QUALIFIED', result['serial'])
            else:
                require(args.journal_file is not None, 'Restore journal required')
                result = attempt_restore(**extra, journal_path=args.journal_file.absolute(),
                                         ack_downgrade=args.ack_downgrade_may_fail,
                                         ack_backup=args.ack_backup_confirmed)
                print('R4C_BASELINE_APK_HASH_RESTORED_DATA_UNVERIFIED', result['serial'])
        return 0
    except (OSError, r4b.Refused, ValueError, KeyError, subprocess.TimeoutExpired) as exc:
        print('R4C_DEVICE_ACCEPTANCE_REFUSED', type(exc).__name__, file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
