#!/usr/bin/env python3
"""Hosted-only KSP resolution preflight; no native node replay or qualification waiver.

Gradle's setup-gradle cache is the sole dependency cache. Validate the content-addressed
KSP objects before reuse and after successful resolution. Offline success demonstrates
that the restored metadata resolves; it is not APK, test, or release evidence.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile
import ssl
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, HTTPSHandler, Request, build_opener

from ci_gradle_plugin_diagnostics import VERSION, TARGETS, SafeRedirect, probe, source_hashes

GROUP = 'com.google.devtools.ksp'
MARKER = 'com.google.devtools.ksp.gradle.plugin'
IMPL = 'symbol-processing-gradle-plugin'
MAX_ATTEMPTS = 3
DELAYS = (15, 45)
MAX_FILE = 128 * 1024 * 1024
# Actual successful S01 run 46105518678 attempt 2, artifact 15247711755,
# SHA256 0c374d24786cb5501f83a4ee72f85d23c239d983386643f0bed2ad13349844c4.
# Both canonical endpoints returned these identical POM bytes (HTTP 200).
POM_PINS = {
    MARKER: 'ba778d7727675c1b3d8165772914dfb8631fa2d0a04867730649422c6859e98c',
    IMPL: '3bbd560d946fd520f82ccce065c4b2eb14ef4951d9d18c752d518bb04e19ab41',
}
HEX64 = re.compile(r'[0-9a-f]{64}\Z')
HEX40 = re.compile(r'[0-9a-f]{40}\Z')
TRANSIENT = re.compile(r'(?i)(?:HTTP(?: status code)?\s*(?:429|502|503|504)|status code (?:429|502|503|504)|connection timed out|read timed out|connection reset|temporary failure in name resolution)')
PLUGIN_MISSING = "Plugin [id: 'com.google.devtools.ksp', version: '" + VERSION + "'] was not found"
OFFLINE_MISSING = re.compile(r'(?i)(?:no cached version|not available for offline mode|no cached resource|offline mode)')
DETERMINISTIC = re.compile(r'(?i)(?:dependency verification failed|checksum mismatch|signature verification failed|script compilation error|compilation error|unsupported class file|permission denied|certificate|PKIX)')

class ResolutionFailure(RuntimeError):
    def __init__(self, message, attempts):
        super().__init__(message)
        self.attempts = attempts


def digest(path, algorithm):
    h = hashlib.new(algorithm)
    with path.open('rb') as fp:
        while block := fp.read(65536):
            h.update(block)
    return h.hexdigest()


def checked_cache(home):
    """Validate Gradle content-addressed cache bytes; never repair/touch cache in-place.

    SHA-1 is the existing Gradle object address, not publisher authentication. SHA-256
    evidence binds observed bytes. Trust remains with canonical repositories and
    setup-gradle's trusted-main-only cache writer, not an arbitrary restored directory.
    """
    root = home / 'caches/modules-2/files-2.1' / GROUP
    records = []
    required = {(MARKER, '.pom'), (IMPL, '.pom'), (IMPL, '.jar')}
    seen = set()
    if not root.exists():
        return False, records
    if root.is_symlink() or not root.is_dir() or not root.resolve().is_relative_to(home.resolve()):
        raise ValueError('unsafe KSP cache root')
    for artifact in (MARKER, IMPL):
        base = root / artifact / VERSION
        if not base.exists():
            continue
        if base.is_symlink() or base.parent.is_symlink() or not base.is_dir():
            raise ValueError('unsafe KSP cache directory')
        objects = sorted(base.iterdir())
        if len(objects) > 32:
            raise ValueError('excessive KSP cache objects')
        by_name = {}
        for obj in objects:
            if obj.is_symlink() or not obj.is_dir() or not HEX40.fullmatch(obj.name):
                raise ValueError('invalid KSP cache object address')
            for file in sorted(obj.iterdir()):
                if (file.is_symlink() or not file.is_file() or not 0 < file.stat().st_size <= MAX_FILE or
                        file.name not in {f'{artifact}-{VERSION}{suffix}' for suffix in ('.pom','.jar','.module')} or
                        digest(file, 'sha1') != obj.name):
                    raise ValueError('KSP cache object failed address/size validation')
                sha256 = digest(file, 'sha256')
                if file.name in by_name and by_name[file.name] != sha256:
                    raise ValueError('ambiguous KSP cache object')
                by_name[file.name] = sha256
                if file.suffix == '.pom':
                    if sha256 != POM_PINS[artifact]:
                        raise ValueError('KSP POM differs from independently observed bytes')
                    if file.stat().st_size > 1024 * 1024:
                        raise ValueError('oversized KSP POM')
                    raw = file.read_bytes()
                    if b'<!DOCTYPE' in raw.upper() or b'<!ENTITY' in raw.upper():
                        raise ValueError('unsafe KSP POM')
                    pom = ET.fromstring(raw)
                    def field(key):
                        return pom.findtext('{*}' + key)
                    if (field('groupId'), field('artifactId'), field('version')) != (GROUP, artifact, VERSION):
                        raise ValueError('KSP POM coordinate mismatch')
                    if artifact == MARKER and not any(
                            (dep.findtext('{*}groupId'), dep.findtext('{*}artifactId'), dep.findtext('{*}version')) == (GROUP, IMPL, VERSION)
                            for dep in pom.findall('{*}dependencies/{*}dependency')):
                        raise ValueError('KSP marker does not bind expected implementation')
                if artifact == IMPL and file.suffix == '.jar':
                    with zipfile.ZipFile(file) as jar:
                        descriptor = jar.getinfo('META-INF/gradle-plugins/com.google.devtools.ksp.properties')
                        if descriptor.file_size > 4096 or b'implementation-class=' not in jar.read(descriptor):
                            raise ValueError('invalid KSP plugin descriptor')
                seen.add((artifact, file.suffix))
                records.append({'artifact': artifact, 'name': file.name, 'sha256': sha256, 'bytes': file.stat().st_size})
    return required <= seen, records



def receipt_identity(root):
    # Include the resolution program itself; a change invalidates an old receipt.
    return {'version': VERSION, 'configuration': source_hashes(root),
            'resolver_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            'runner_os': os.environ.get('RUNNER_OS'), 'runner_arch': os.environ.get('RUNNER_ARCH')}


def read_trusted_receipt(path, identity):
    if not path.exists():
        if path.is_symlink():
            raise ValueError('unsafe cache receipt')
        return {}
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= 65536:
        raise ValueError('unsafe cache receipt')
    obj = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(obj, dict) or not isinstance(obj.get('objects'), list) or obj.get('schema') != 'mmrl-lsposed.ksp-cache-receipt.v1' or obj.get('identity') != identity or obj.get('origin') != 'canonical-maven-https':
        raise ValueError('stale or foreign cache receipt')
    result = {}
    for row in obj.get('objects', []):
        if not isinstance(row, dict):
            raise ValueError('invalid cached verification row')
        name = row.get('name'); artifact = row.get('artifact'); value = row.get('sha256'); size = row.get('bytes')
        if artifact not in (MARKER, IMPL) or name not in {f'{artifact}-{VERSION}{ext}' for ext in ('.pom','.jar','.module')} or not isinstance(value, str) or not HEX64.fullmatch(value) or type(size) is not int or not 0 < size <= MAX_FILE or name in result:
            raise ValueError('invalid cached verification record')
        if name.endswith('.pom') and value != POM_PINS[artifact]:
            raise ValueError('cached receipt conflicts with POM pin')
        result[name] = (value, size)
    if not {f'{MARKER}-{VERSION}.pom', f'{IMPL}-{VERSION}.pom', f'{IMPL}-{VERSION}.jar'} <= set(result):
        raise ValueError('incomplete cached verification receipt')
    return result


def canonical_sha256(record, sleep=time.sleep):
    # Official HTTPS artifact bytes (not an arbitrary mirror or a checksum stored alongside
    # an untrusted local file). Read/hash bounded data; no credential or signed URL is sent.
    suffix = GROUP.replace('.', '/') + '/' + record['artifact'] + '/' + VERSION + '/' + record['name']
    urls = ('https://repo.maven.apache.org/maven2/' + suffix, 'https://plugins.gradle.org/m2/' + suffix)
    for i in range(MAX_ATTEMPTS):
        rate = False; retry_after = 0
        for url in urls:
            opener = build_opener(ProxyHandler({}), HTTPSHandler(context=ssl.create_default_context()), SafeRedirect())
            try:
                with opener.open(Request(url, headers={'User-Agent':'mmrl-ksp-verification/1', 'Accept-Encoding':'identity'}), timeout=12) as response:
                    if response.status != 200:
                        raise ValueError('canonical artifact response not successful')
                    h = hashlib.sha256(); count = 0
                    deadline = time.monotonic() + 90
                    while block := response.read(65536):
                        count += len(block)
                        if count > record['bytes'] or count > MAX_FILE or time.monotonic() > deadline:
                            raise ValueError('canonical artifact length or deadline exceeded')
                        h.update(block)
                    if count != record['bytes']:
                        raise ValueError('canonical artifact length mismatch')
                    return h.hexdigest()
            except HTTPError as error:
                if error.code == 404:
                    continue
                if error.code not in (429, 502, 503, 504):
                    raise ValueError('canonical artifact request refused') from None
                rate = True
                after = error.headers.get('Retry-After')
                if after is not None:
                    # Honor bounded seconds or HTTP date. Do not retry sooner than the server asks.
                    try:
                        if after.isdecimal(): requested = int(after)
                        else:
                            from email.utils import parsedate_to_datetime
                            requested = max(0, int(parsedate_to_datetime(after).timestamp() - time.time()) + 1)
                        if requested > 60:
                            raise ValueError('server retry-after exceeds bounded policy')
                        retry_after = max(retry_after, requested)
                    except (TypeError, ValueError, OverflowError):
                        raise ValueError('server retry-after exceeds bounded policy') from None
                # Portal currently redirects to Central; do not amplify a rate limit with another request.
                break
            except (URLError, TimeoutError, ConnectionError):
                rate = True; break
        if not rate or i == MAX_ATTEMPTS - 1:
            raise RuntimeError('canonical KSP verification unavailable; no cache proof accepted')
        sleep(max(DELAYS[i], retry_after))
    raise AssertionError('unreachable')


def verify_records(records, trusted, online=canonical_sha256):
    for row in records:
        value = (row['sha256'], row['bytes'])
        known = trusted.get(row['name'])
        if known is not None:
            if known != value:
                raise ValueError('restored KSP cache differs from trusted receipt')
        elif row['name'].endswith('.pom'):
            if POM_PINS[row['artifact']] != row['sha256']:
                raise ValueError('KSP POM pin mismatch')
        elif online(row) != row['sha256']:
            raise ValueError('KSP cache differs from canonical HTTPS bytes')
        # Reuse only bytes independently checked within this invocation or from trusted main cache.
        trusted[row['name']] = value


def transient_failure(log, network):
    # Never retry compiler/test/verification failures or arbitrary unknown build errors.
    if DETERMINISTIC.search(log):
        return False
    if PLUGIN_MISSING not in log and 'Could not resolve' not in log:
        return False
    if TRANSIENT.search(log):
        return True
    if PLUGIN_MISSING not in log:
        return False
    statuses = [record.get('status') for record in network if record.get('target') in ('central_marker', 'portal_marker', 'central_implementation', 'portal_implementation')]
    return bool(statuses) and any(status in {'HTTP_429','HTTP_502','HTTP_503','HTTP_504','TRANSPORT_ERROR'} for status in statuses)


def run_gradle(root, offline=False):
    args = [str(root / 'gradlew'), '--no-daemon', '--console=plain']
    if offline:
        args.append('--offline')
    args.append('help')
    # Bounded wall time per configuration attempt; no Gradle execution locally.
    try:
        with tempfile.TemporaryFile(mode='w+b') as out:
            result = subprocess.run(args, cwd=root, stdout=out, stderr=subprocess.STDOUT, timeout=240, check=False)
            out.seek(max(0, out.tell() - 131072))
            log = out.read().decode('utf-8', 'replace')
            # Do not emit potentially sensitive raw resolution URLs to CI stdout.
            return result.returncode, log
    except subprocess.TimeoutExpired:
        return 124, ''


def prepare(root, home, execute=run_gradle, inspect=checked_cache, network=None, sleep=time.sleep, verify=lambda records: None):
    if network is None:
        network = lambda: [probe(n, u) for n, u in TARGETS if n != 'google_marker']
    complete, records = inspect(home)
    verify(records)
    attempts = []
    if complete:
        code, log = execute(root, True)
        attempts.append({'mode':'offline', 'exit':code})
        if code == 0:
            complete, records = inspect(home)
            if not complete:
                raise ResolutionFailure('verified KSP objects missing after offline configuration', attempts)
            verify(records)
            return attempts, records
        if code == 124 or DETERMINISTIC.search(log) or not (OFFLINE_MISSING.search(log) or PLUGIN_MISSING in log):
            raise ResolutionFailure('offline configuration failed outside missing-cache class', attempts)
    for i in range(MAX_ATTEMPTS):
        code, log = execute(root, False)
        attempts.append({'mode':'online', 'attempt':i+1, 'exit':code})
        if code == 0:
            complete, records = inspect(home)
            if not complete:
                raise ResolutionFailure('KSP configuration succeeded without required cache objects', attempts)
            verify(records)
            return attempts, records
        if i == MAX_ATTEMPTS - 1 or not transient_failure(log, network() if PLUGIN_MISSING in log else []):
            raise ResolutionFailure(f'Gradle configuration failed (exit {code}); no further admissible retry', attempts)
        print(f'KSP_PREFLIGHT_TRANSIENT_RETRY={i+1}', flush=True)
        sleep(DELAYS[i])
    raise AssertionError('unreachable')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--cache-receipt', type=Path, required=True)
    args = parser.parse_args()
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('RUNNER_OS') != 'Linux':
        raise SystemExit('KSP_PREFLIGHT_REFUSED: hosted Linux only')
    root = Path.cwd()
    source = subprocess.check_output(['git','rev-parse','HEAD'], text=True).strip()
    if source != os.environ.get('GITHUB_SHA') or not HEX40.fullmatch(source):
        raise SystemExit('KSP_PREFLIGHT_REFUSED: checkout identity')
    home = Path(os.environ.get('GRADLE_USER_HOME') or str(Path.home() / '.gradle'))
    out = args.out.absolute()
    if out.exists() or out.is_symlink() or not out.parent.is_dir():
        raise SystemExit('KSP_PREFLIGHT_REFUSED: unsafe evidence path')
    try:
        identity = receipt_identity(root)
        receipt = args.cache_receipt.absolute()
        if receipt.parent.is_symlink():
            raise ValueError('unsafe receipt directory')
        trusted = read_trusted_receipt(receipt, identity)
        attempts, records = prepare(root, home, verify=lambda rows: verify_records(rows, trusted))
        receipt.parent.mkdir(parents=True, exist_ok=True)
        # Trusted-main cache save is separately enforced by the workflow; PRs never save this file to cache.
        if not receipt.exists():
            with receipt.open('x', encoding='utf-8') as fp:
                json.dump({'schema':'mmrl-lsposed.ksp-cache-receipt.v1', 'identity':identity,
                           'origin':'canonical-maven-https', 'objects':records}, fp, sort_keys=True, indent=2)
                fp.write('\n')
        report = {'schema':'mmrl-lsposed.ksp-resolution.v1', 'source_sha':source,
                  'run':os.environ.get('GITHUB_RUN_ID'), 'attempt':os.environ.get('GITHUB_RUN_ATTEMPT'),
                  'source_files_sha256':source_hashes(root), 'ksp_version':VERSION,
                  'outcome':'RESOLVED', 'resolution_attempts':attempts, 'cache_objects':records, 'apk_qualified':False,
                  'release_qualified':False, 'publisher_authenticated':False}
        with out.open('x', encoding='utf-8') as fp:
            json.dump(report, fp, sort_keys=True, indent=2)
            fp.write('\n')
    except (OSError, ValueError, RuntimeError, ET.ParseError, zipfile.BadZipFile, KeyError) as error:
        if not out.exists():
            with out.open('x', encoding='utf-8') as fp:
                json.dump({'schema':'mmrl-lsposed.ksp-resolution.v1', 'source_sha':source,
                           'run':os.environ.get('GITHUB_RUN_ID'), 'attempt':os.environ.get('GITHUB_RUN_ATTEMPT'),
                           'outcome':'REFUSED', 'error_type':type(error).__name__,
                           'resolution_attempts':getattr(error, 'attempts', []),
                           'apk_qualified':False, 'release_qualified':False}, fp, sort_keys=True)
                fp.write('\n')
        # Only controlled validation messages are suitable for logs; suppress external exception content.
        raise SystemExit('KSP_PREFLIGHT_REFUSED: ' + (str(error) if type(error) is RuntimeError else type(error).__name__)) from None
    print('KSP_PREFLIGHT_PASS', flush=True)


if __name__ == '__main__':
    main()
