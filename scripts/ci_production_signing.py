#!/usr/bin/env python3
"""R4B isolated hosted release *candidate* signing; never a production release.

This script refuses signing until a distinct committed certificate has been
approved, the GitHub environment is intentionally configured, and the workflow
runs by explicit dispatch on main. Secrets never enter argv, receipts or logs.
"""
from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from typing import Any

REPOSITORY = 'Mikeyphw/MMRL-LSPosed'
APP_ID = 'io.github.mikeyphw.mmrl.lsposed'
DEBUG_POLICY = Path('config/debug-signing.json')
RELEASE_POLICY = Path('config/release-signing.json')
RELEASE_ALIAS = 'mmrl_lsposed_release'
ENVIRONMENT = 'mmrl-lsposed-release-signing'
OUTPUT_NAME = 'MMRL-LSPosed-release-candidate.apk'
RECEIPT_NAME = 'release-candidate.json'
UNSIGNED = Path('app/build/outputs/apk/release/app-release-unsigned.apk')
HEX64 = re.compile(r'[0-9a-f]{64}\Z')
HEX40 = re.compile(r'[0-9a-f]{40}\Z')
BADGING = re.compile(r"(?m)^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'\s*")
CERT = re.compile(r'(?im)^Signer #(\d+) certificate SHA-256 digest:\s*([0-9a-f]{64})\s*$')
MAX_KEYSTORE = 8 * 1024 * 1024
MAX_APK = 250 * 1024 * 1024
KEY = 'MMRL_RELEASE_KEYSTORE_B64'
STORE_PASS = 'MMRL_RELEASE_STORE_PASSWORD'
KEY_PASS = 'MMRL_RELEASE_KEY_PASSWORD'
SENSITIVE_ENV = frozenset({KEY, STORE_PASS, KEY_PASS, 'GITHUB_TOKEN', 'GH_TOKEN'})


class Refused(ValueError):
    pass


def json_unique(data: bytes) -> dict:
    def pairs(values: list[tuple[str, Any]]) -> dict:
        result = {}
        for key, value in values:
            if key in result:
                raise Refused('Duplicate JSON key')
            result[key] = value
        return result
    try:
        obj = json.loads(data.decode('utf-8'), object_pairs_hook=pairs)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise Refused('Malformed JSON') from exc
    if not isinstance(obj, dict):
        raise Refused('Expected JSON object')
    return obj


def read_json(path: Path, maximum: int = 32768) -> dict:
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= maximum:
        raise Refused('Missing, symlinked or oversized JSON input')
    return json_unique(path.read_bytes())


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(part)
    return h.hexdigest()


def exact_release_policy(release: dict, debug: dict, *, require_enrolled: bool) -> str | None:
    exact = {
        'schema': 'mmrl-lsposed.release-signing.v1',
        'repository': REPOSITORY,
        'application_id': APP_ID,
        'ref': 'refs/heads/main',
        'environment': ENVIRONMENT,
        'purpose': 'isolated-release-candidate-only',
        'keystore_type': 'PKCS12',
        'key_alias': RELEASE_ALIAS,
        'publish_allowed': False,
        'release_qualified': False,
        'device_qualified': False,
    }
    if (set(release) != set(exact) | {'enrollment', 'certificate_sha256'} or
            any(type(release.get(k)) is not type(v) or release[k] != v for k, v in exact.items())):
        raise Refused('Release policy has unexpected fields or claims qualification')
    if (debug.get('schema') != 'mmrl-lsposed.debug-signing.v1' or
            debug.get('repository') != REPOSITORY or
            debug.get('purpose') != 'debug-only' or
            debug.get('application_id') != APP_ID + '.debug' or
            not isinstance(debug.get('certificate_sha256'), str) or
            not HEX64.fullmatch(debug['certificate_sha256'])):
        raise Refused('Invalid existing debug signer policy')
    enrollment, cert = release.get('enrollment'), release.get('certificate_sha256')
    if enrollment == 'pending' and cert is None:
        if require_enrolled:
            raise Refused('Release signer is not enrolled: commit and review a different certificate first')
        return None
    if (enrollment != 'enrolled' or type(cert) is not str or
            not HEX64.fullmatch(cert) or cert == debug['certificate_sha256']):
        raise Refused('Release certificate is missing, malformed or matches debug identity')
    return cert


def host_identity(env: dict[str, str], source: str, *, require_runner: bool = True) -> tuple[int, int]:
    expected = {
        'GITHUB_ACTIONS': 'true',
        'GITHUB_EVENT_NAME': 'workflow_dispatch',
        'GITHUB_REF': 'refs/heads/main',
        'GITHUB_REPOSITORY': REPOSITORY,
        'GITHUB_SHA': source,
    }
    if any(env.get(k) != v for k, v in expected.items()):
        raise Refused('Release workflow allowed only on exact main workflow_dispatch')
    if require_runner and (env.get('RUNNER_OS') != 'Linux' or env.get('RUNNER_ARCH') != 'X64'):
        raise Refused('Unsupported GitHub release-signing runner')
    if not HEX40.fullmatch(source):
        raise Refused('Invalid source SHA')
    run, attempt = env.get('GITHUB_RUN_ID', ''), env.get('GITHUB_RUN_ATTEMPT', '')
    if (not run.isascii() or not run.isdecimal() or not attempt.isascii() or
            not attempt.isdecimal() or int(run) < 1 or int(attempt) < 1):
        raise Refused('Invalid workflow run or rerun attempt')
    return int(run), int(attempt)


def parse_apk_metadata(badging: str, *, unsigned: bool = False) -> tuple[int, str]:
    found = BADGING.search(badging)
    if not found or found.group(1) != APP_ID or 'application-debuggable' in badging:
        raise Refused('Release APK package identity or debuggable property refused')
    version = found.group(3)
    if (int(found.group(2)) < 1 or len(version) > 80 or
            not re.fullmatch(r'[A-Za-z0-9._-]+', version) or
            version.endswith('-debug')):
        raise Refused('Invalid release version')
    return int(found.group(2)), version


def parse_signer(verify_output: str, expected: str) -> None:
    matches = CERT.findall(verify_output)
    if len(matches) != 1 or matches[0][0] != '1' or matches[0][1].lower() != expected:
        raise Refused('APK signature does not match the committed release certificate')


def invoke(argv: list[str], *, timeout: int = 120, env: dict[str, str] | None = None,
           allow_failure: bool = False) -> tuple[int, str]:
    # All invocations use scrubbed environments. Never include passwords in argv.
    clean_env = {k: v for k, v in (os.environ if env is None else env).items()
                 if k not in SENSITIVE_ENV}
    try:
        proc = subprocess.run(argv, capture_output=True, text=True, timeout=timeout,
                              check=False, env=clean_env)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise Refused('Android signing tool unavailable or timed out') from exc
    if proc.returncode and not allow_failure:
        raise Refused('Android signing tool failed')
    return proc.returncode, proc.stdout


def regular_file(path: Path, max_size: int) -> Path:
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= max_size:
        raise Refused('Input/output not a bounded regular file')
    return path


def env_source(root: Path, env: dict[str, str]) -> tuple[str, int, int]:
    sha = env.get('GITHUB_SHA', '')
    run, attempt = host_identity(env, sha)
    # A credential-bearing signing step may call this: never inherit secrets in git.
    scrubbed = {k: v for k, v in env.items() if k not in SENSITIVE_ENV}
    head = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'],
                                   text=True, env=scrubbed).strip()
    if head != sha:
        raise Refused('Git checkout differs from GitHub source commit')
    return sha, run, attempt


def validate_inputs(root: Path, env: dict[str, str], *, enrolled: bool) -> tuple[str, int, int, str | None]:
    sha, run, attempt = env_source(root, env)
    expected = exact_release_policy(read_json(root / RELEASE_POLICY),
                                    read_json(root / DEBUG_POLICY), require_enrolled=enrolled)
    return sha, run, attempt, expected


def generate_receipt(*, source: str, run: int, attempt: int, expected: str,
                     apk: Path, version_code: int, version_name: str) -> dict:
    return {
        'schema': 'mmrl-lsposed.release-candidate.v1',
        'status': 'SIGNED_RELEASE_CANDIDATE_NOT_QUALIFIED',
        'repository': REPOSITORY, 'source_sha': source,
        'run_id': run, 'attempt': attempt,
        'application_id': APP_ID, 'version_code': version_code,
        'version_name': version_name,
        'apk': OUTPUT_NAME, 'apk_sha256': sha256(apk),
        'apk_size': apk.stat().st_size, 'certificate_sha256': expected,
        'debuggable': False, 'publish_allowed': False,
        'release_qualified': False, 'device_qualified': False,
        'independently_attested': False,
    }


def validate_receipt(receipt: dict, *, source: str, run: int, attempt: int,
                     cert: str, apk: Path, version_code: int, version_name: str) -> None:
    expected = generate_receipt(source=source, run=run, attempt=attempt, expected=cert,
                                apk=apk, version_code=version_code, version_name=version_name)
    if set(receipt) != set(expected) or any(type(receipt.get(k)) is not type(v) or receipt.get(k) != v
                                           for k, v in expected.items()):
        raise Refused('Candidate receipt differs from verified APK or falsely claims qualification')


def save_receipt(path: Path, data: dict) -> None:
    encoded = (json.dumps(data, sort_keys=True, indent=2) + '\n').encode('utf-8')
    descriptor = os.open(str(path), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(descriptor, 'wb') as stream:
        stream.write(encoded)
        stream.flush()
        os.fsync(stream.fileno())


def sign(root: Path, env: dict[str, str]) -> dict:
    source, run, attempt, cert = validate_inputs(root, env, enrolled=True)
    assert cert is not None
    decoded = env.get(KEY, '')
    store_password, key_password = env.get(STORE_PASS, ''), env.get(KEY_PASS, '')
    if (not decoded or not store_password or not key_password or len(decoded) > 12_000_000 or
            '\n' in store_password or '\r' in store_password or
            '\n' in key_password or '\r' in key_password):
        raise Refused('Required environment-scoped signing credentials unavailable')
    try:
        keystore_bytes = base64.b64decode(decoded, validate=True)
    except (ValueError, binascii.Error) as exc:
        raise Refused('Keystore is not valid base64') from exc
    if not 100 < len(keystore_bytes) <= MAX_KEYSTORE:
        raise Refused('Keystore size invalid')
    sdk = Path(env.get('ANDROID_SDK_ROOT', '')) / 'build-tools' / '36.0.0'
    aapt, apksigner, zipalign = (sdk / name for name in ('aapt', 'apksigner', 'zipalign'))
    if any(not t.is_file() or not os.access(t, os.X_OK) for t in (aapt, apksigner, zipalign)):
        raise Refused('Pinned Android build tools unavailable')
    unsigned = regular_file(root / UNSIGNED, MAX_APK)
    exit_code, _ = invoke([str(apksigner), 'verify', '--print-certs', str(unsigned)], allow_failure=True)
    if exit_code == 0:
        raise Refused('Expected unsigned release APK, received an already signed artifact')
    _, badging = invoke([str(aapt), 'dump', 'badging', str(unsigned)])
    code, name = parse_apk_metadata(badging)
    runner_temp = Path(env['RUNNER_TEMP'])
    if runner_temp.is_symlink() or not runner_temp.is_dir() or not runner_temp.is_absolute():
        raise Refused('Invalid runner temporary directory')
    out = runner_temp / 'r4b-release-candidate'
    if out.exists() or out.is_symlink():
        raise Refused('Release candidate output already exists')
    out.mkdir(mode=0o700)
    signed = out / OUTPUT_NAME
    with tempfile.TemporaryDirectory(prefix='r4b-key-', dir=runner_temp) as private:
        priv = Path(private)
        ks, sp, kp = (priv / name for name in ('release.p12', 'store.pass', 'key.pass'))
        for f, b in [(ks, keystore_bytes), (sp, store_password.encode()), (kp, key_password.encode())]:
            f.write_bytes(b)
            f.chmod(0o600)
        aligned = priv / 'aligned.apk'
        invoke([str(zipalign), '-f', '-p', '4', str(unsigned), str(aligned)])
        invoke([str(apksigner), 'sign', '--ks', str(ks), '--ks-type', 'PKCS12',
                '--ks-key-alias', RELEASE_ALIAS, '--ks-pass', 'file:' + str(sp),
                '--key-pass', 'file:' + str(kp), '--out', str(signed), str(aligned)], timeout=180)
    regular_file(signed, MAX_APK)
    _, signed_verification = invoke([str(apksigner), 'verify', '--verbose', '--print-certs',
                                      '--min-sdk-version', '26', str(signed)])
    parse_signer(signed_verification, cert)
    _, final_badging = invoke([str(aapt), 'dump', 'badging', str(signed)])
    if parse_apk_metadata(final_badging) != (code, name):
        raise Refused('Signing changed package or version')
    receipt = generate_receipt(source=source, run=run, attempt=attempt, expected=cert,
                               apk=signed, version_code=code, version_name=name)
    save_receipt(out / RECEIPT_NAME, receipt)
    validate_receipt(read_json(out / RECEIPT_NAME), source=source, run=run,
                     attempt=attempt, cert=cert, apk=signed, version_code=code, version_name=name)
    return receipt


def verify(root: Path, env: dict[str, str]) -> dict:
    source, run, attempt, cert = validate_inputs(root, env, enrolled=True)
    assert cert is not None
    out = Path(env['RUNNER_TEMP']) / 'r4b-release-candidate'
    if out.is_symlink() or not out.is_dir():
        raise Refused('No candidate artifact directory')
    apk = regular_file(out / OUTPUT_NAME, MAX_APK)
    metadata = read_json(out / RECEIPT_NAME)
    sdk = Path(env.get('ANDROID_SDK_ROOT', '')) / 'build-tools' / '36.0.0'
    aapt, apksigner = (sdk / name for name in ('aapt', 'apksigner'))
    _, verified = invoke([str(apksigner), 'verify', '--verbose', '--print-certs',
                          '--min-sdk-version', '26', str(apk)])
    parse_signer(verified, cert)
    _, badging = invoke([str(aapt), 'dump', 'badging', str(apk)])
    code, name = parse_apk_metadata(badging)
    validate_receipt(metadata, source=source, run=run, attempt=attempt, cert=cert,
                     apk=apk, version_code=code, version_name=name)
    return metadata


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('preflight', 'sign', 'verify'))
    parser.add_argument('--root', type=Path, default=Path.cwd())
    parser.add_argument('--require-enrolled', action='store_true')
    args = parser.parse_args()
    try:
        root = args.root.resolve(strict=True)
        if args.action == 'preflight':
            sha, run, attempt, cert = validate_inputs(root, dict(os.environ),
                                                       enrolled=args.require_enrolled)
            print('R4B_RELEASE_ENROLLMENT_REQUIRED' if cert is None else 'R4B_RELEASE_POLICY_ENROLLED',
                  sha[:12], run, attempt)
        elif args.action == 'sign':
            result = sign(root, dict(os.environ))
            print('R4B_SIGNED_CANDIDATE_NOT_RELEASE', result['source_sha'][:12], result['run_id'])
        else:
            result = verify(root, dict(os.environ))
            print('R4B_CANDIDATE_VERIFIED_NOT_RELEASE', result['source_sha'][:12], result['run_id'])
        return 0
    except (OSError, Refused, ValueError, KeyError, subprocess.CalledProcessError) as exc:
        # Never print exception contents from signing tools or secret inputs.
        print('R4B_RELEASE_SIGNING_REFUSED:', type(exc).__name__, file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
