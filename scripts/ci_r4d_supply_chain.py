#!/usr/bin/env python3
"""R4D read-only supply-chain inventory, identity checks and BLOCKED release report.

Outputs are independently re-creatable but NOT a proof of signing, licensing,
reproducible builds, device acceptance or suitability for release.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tomllib
from urllib.parse import quote

import ci_production_signing as r4b

SCHEMA = 'mmrl-lsposed.r4d-inventory.v1'
SOURCE_INPUTS = (
    'gradle/libs.versions.toml', 'settings.gradle.kts', 'app/build.gradle.kts',
    'hook-lab-module/build.gradle.kts', 'hook-lab-target/build.gradle.kts',
    'config/debug-signing.json',
    'config/release-signing.json',
)
OUTPUT_FILES = ('sbom.cdx.json', 'dependency-inventory.json', 'license-audit.json',
                'release-qualification.json', 'gradle-release-dependencies.txt')
CHECKED_FILES = OUTPUT_FILES + ('manifest.json',)
HEX40 = re.compile(r'[0-9a-f]{40}\Z')
HEX64 = re.compile(r'[0-9a-f]{64}\Z')
COORD = re.compile(r'([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([A-Za-z0-9_.+\-]+)\Z')
DEPENDENCY_LINE = re.compile(r'(?:\+---|\\---)\s+(\S+)(?:\s+->\s+(\S+))?')
DECLARED = re.compile(r'(?m)^\s*(implementation|debugImplementation|testImplementation|androidTestImplementation)\(\s*(?:"([^"]+)"|libs\.([\w.]+))\s*\)')
MAX_REPORT = 4 * 1024 * 1024
MAX_OUT = 8 * 1024 * 1024
REPOSITORY = 'Mikeyphw/MMRL-LSPosed'


class Refused(r4b.Refused):
    pass


def dump(data: dict) -> bytes:
    return (json.dumps(data, sort_keys=True, indent=2, ensure_ascii=True) + '\n').encode('utf-8')


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def strict_json(data: bytes) -> dict:
    try:
        return r4b.json_unique(data)
    except r4b.Refused as exc:
        raise Refused(str(exc)) from exc


def bounded(path: Path, maximum: int = MAX_OUT) -> bytes:
    if path.is_symlink() or not path.is_file():
        raise Refused(f'Missing/unsafe regular file: {path.name}')
    size = path.stat().st_size
    if not 0 < size <= maximum:
        raise Refused(f'Missing/oversize content: {path.name}')
    return path.read_bytes()


def parse_identity(source: str, run: str | int, attempt: str | int,
                   event: str, ref: str) -> dict:
    if not HEX40.fullmatch(source):
        raise Refused('Invalid exact 40-hex source SHA')
    if type(run) is not str or not run.isascii() or not run.isdecimal() or not 0 < len(run) <= 20 or int(run) < 1:
        raise Refused('Invalid GitHub run identifier')
    if type(attempt) is not str or not attempt.isascii() or not attempt.isdecimal() or not 0 < len(attempt) <= 8 or int(attempt) < 1:
        raise Refused('Invalid GitHub run attempt')
    if event not in ('pull_request', 'push', 'workflow_dispatch'):
        raise Refused('Unexpected workflow event')
    if event != 'pull_request' and ref != 'refs/heads/main':
        raise Refused('Non-PR evidence must originate on main')
    if event == 'pull_request' and not re.fullmatch(r'refs/pull/[1-9][0-9]*/merge', ref):
        raise Refused('PR evidence needs explicit merge ref')
    return {'repository': REPOSITORY, 'source_sha': source, 'run_id': int(run),
            'attempt': int(attempt), 'event': event, 'ref': ref}


def check_source(root: Path, identity: dict, *, enforce_checkout: bool = True) -> dict:
    release = strict_json(bounded(root / 'config/release-signing.json', 32768))
    debug = strict_json(bounded(root / 'config/debug-signing.json', 32768))
    try:
        cert = r4b.exact_release_policy(release, debug, require_enrolled=False)
    except r4b.Refused as exc:
        raise Refused(str(exc)) from exc
    if enforce_checkout:
        try:
            head = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'],
                                           text=True, stderr=subprocess.DEVNULL,
                                           timeout=10).strip()
        except (OSError, subprocess.SubprocessError) as exc:
            raise Refused('Cannot independently check checkout HEAD') from exc
        if head != identity['source_sha']:
            raise Refused('Checkout does not match event source SHA')
    input_hashes = {p: sha(bounded(root / p)) for p in SOURCE_INPUTS}
    return {'certificate': cert, 'input_sha256': input_hashes}


def declared_deps(root: Path) -> list[dict]:
    data = tomllib.loads(bounded(root / 'gradle/libs.versions.toml').decode('utf-8'))
    versions, libraries = data['versions'], data['libraries']
    result = []
    for p in ('app/build.gradle.kts', 'hook-lab-module/build.gradle.kts',
              'hook-lab-target/build.gradle.kts'):
        for m in DECLARED.finditer(bounded(root / p).decode('utf-8')):
            configuration, literal, alias = m.groups()
            if alias:
                key = alias.replace('.', '-')
                if key not in libraries:
                    raise Refused(f'Unknown version-catalog alias: {alias}')
                item = libraries[key]
                literal = item['module'] + ':' + (item['version'] if isinstance(item.get('version'), str) else versions[item['version']['ref']])
            if not COORD.fullmatch(literal):
                raise Refused(f'Unsupported direct Gradle dependency: {literal}')
            result.append({'configuration': configuration, 'coordinate': literal,
                           'declared_at': p})
    if len(result) < 5:
        raise Refused('No plausible Gradle declarations discovered')
    return sorted(result, key=lambda x: (x['declared_at'], x['configuration'], x['coordinate']))


def resolved_dependencies(report: bytes) -> tuple[list[str], int]:
    if not 0 < len(report) <= MAX_REPORT or b'\x00' in report:
        raise Refused('Empty, invalid, or oversized Gradle dependency report')
    try:
        content = report.decode('utf-8')
    except UnicodeDecodeError as exc:
        raise Refused('Invalid Gradle UTF-8 report') from exc
    if not re.search(r'(?m)^releaseRuntimeClasspath\s+-\s+', content):
        raise Refused('Gradle output does not identify releaseRuntimeClasspath')
    if re.search(r'(?im)(?:\bFAILED\b|\bUNRESOLVED\b|Could not resolve)', content):
        raise Refused('Unresolved release dependencies')
    found: set[str] = set()
    skipped = 0
    for line in content.splitlines():
        m = DEPENDENCY_LINE.search(line)
        if not m:
            continue
        coordinate, target = m.groups()
        if coordinate.startswith('project ') or coordinate == 'project':
            skipped += 1
            continue
        if target and target.endswith('(*)'):
            target = target[:-3]
        # Gradle selection prints 'group:name:requested -> chosen', often a bare version.
        raw = coordinate.split('(')[0]
        c = COORD.fullmatch(raw)
        if not c:
            skipped += 1
            continue
        group, name, version = c.groups()
        if target and re.fullmatch(r'[A-Za-z0-9_.+\-]+', target):
            version = target
        if version == 'unspecified' or version == 'FAILED':
            raise Refused('Unresolved version')
        found.add(f'{group}:{name}:{version}')
    if len(found) < 4:
        raise Refused('Too few resolved releaseRuntimeClasspath dependencies')
    return sorted(found), skipped


def purl(coord: str) -> str:
    g, n, v = COORD.fullmatch(coord).groups()
    return f'pkg:maven/{quote(g, safe=".")}/{quote(n, safe="-._")}@{quote(v, safe=".-_")}'


def generate(root: Path, report: bytes, identity: dict, *, enforce_checkout: bool = True) -> dict[str, bytes]:
    state = check_source(root, identity, enforce_checkout=enforce_checkout)
    resolved, skipped = resolved_dependencies(report)
    direct = declared_deps(root)
    direct_release = {':'.join(x['coordinate'].split(':')[:2]) for x in direct
                      if x['configuration'] == 'implementation' and x['declared_at'] == 'app/build.gradle.kts'}
    components = []
    for coordinate in resolved:
        group, name, version = COORD.fullmatch(coordinate).groups()
        components.append({'type': 'library', 'group': group, 'name': name,
                           'version': version, 'bom-ref': purl(coordinate),
                           'purl': purl(coordinate), 'properties': [
                               {'name': 'mmrl:license-review', 'value': 'UNKNOWN_NOT_VERIFIED'},
                               {'name': 'mmrl:app-direct-runtime-dependency',
                                'value': 'true' if f'{group}:{name}' in direct_release else 'false'}]})
    props = [{'name': 'mmrl:repository', 'value': REPOSITORY},
             {'name': 'mmrl:source-sha', 'value': identity['source_sha']},
             {'name': 'mmrl:run-id', 'value': str(identity['run_id'])},
             {'name': 'mmrl:attempt', 'value': str(identity['attempt'])},
             {'name': 'mmrl:release-qualification', 'value': 'BLOCKED'},
             {'name': 'mmrl:limitations', 'value': 'Runtime Gradle report only; licenses and reproducibility unverified'}]
    sbom = {'bomFormat': 'CycloneDX', 'specVersion': '1.6', 'version': 1,
            'metadata': {'component': {'type': 'application', 'name': 'MMRL-LSPosed',
                                       'bom-ref': 'app:mmrl-lsposed'}, 'properties': props},
            'components': components,
            'compositions': [{'aggregate': 'incomplete',
                              'assemblies': ['app:mmrl-lsposed']}],
            'dependencies': [{'ref': 'app:mmrl-lsposed', 'dependsOn': [x['bom-ref'] for x in components]}]}
    inventory = {'schema': SCHEMA, 'identity': identity,
                 'gradle_configuration': 'releaseRuntimeClasspath',
                 'resolved_component_count': len(components),
                 'skipped_unparsed_gradle_lines': skipped,
                 'transitive_edges_independently_verified': False,
                 'dependency_lock_or_checksum_verification': False,
                 'build_plugin_dependencies_covered': False,
                 'declarations': direct,
                 'release_components': resolved,
                 'source_inputs_sha256': state['input_sha256']}
    licenses = {'schema': 'mmrl-lsposed.r4d-license-audit.v1', 'identity': identity,
                'repository_license_text': 'GNU General Public License v3 text; verify individual copyright notices',
                'repository_license_note': 'LICENSE file is GPL version 3; third-party obligations remain unverified',
                'third_party_license_verified': False,
                'unverified_count': len(components),
                'unverified_components': [c['purl'] for c in components]}
    blockers = ['UNVERIFIED_TRANSITIVE_DEPENDENCY_GRAPH',
                'UNVERIFIED_DEPENDENCY_CHECKSUMS_AND_REPRODUCIBILITY',
                'THIRD_PARTY_LICENSE_ATTRIBUTIONS_MISSING',
                'SIGNED_CANDIDATE_PROVENANCE_NOT_INDEPENDENTLY_VERIFIED',
                'NO_API26_RELEASE_SMOKE_EVIDENCE',
                'S11_PHYSICAL_UX_REQUALIFICATION_PENDING',
                'R4C_PHYSICAL_UPGRADE_DATA_AND_RESTORE_PENDING',
                'PROTECTED_ENVIRONMENT_SETTINGS_NOT_INDEPENDENTLY_ATTESTED',
                'NO_AUTHORIZED_MANUAL_PUBLICATION_GATE']
    if state['certificate'] is None:
        blockers.append('PRODUCTION_RELEASE_CERTIFICATE_NOT_ENROLLED')
    qualification = {'schema': 'mmrl-lsposed.r4d-qualification.v1', 'identity': identity,
                     'status': 'BLOCKED', 'publish_allowed': False,
                     'release_qualified': False, 'device_qualified': False,
                     'r4d_inventory_created': True,
                     'candidate_has_valid_independent_attestation': False,
                     'sbom_completeness': 'INCOMPLETE',
                     'blockers': sorted(blockers),
                     'notice': 'A passing inventory workflow never authorizes APK signing, installation or publication.'}
    files = {'sbom.cdx.json': dump(sbom), 'dependency-inventory.json': dump(inventory),
             'license-audit.json': dump(licenses),
             'release-qualification.json': dump(qualification),
             'gradle-release-dependencies.txt': report}
    manifest = {'schema': 'mmrl-lsposed.r4d-evidence-manifest.v1',
                'identity': identity, 'release_decision': 'BLOCKED',
                'inputs_sha256': state['input_sha256'],
                'outputs_sha256': {k: sha(v) for k, v in sorted(files.items())},
                'publication_authorized': False, 'contains_production_signing_material': False}
    files['manifest.json'] = dump(manifest)
    return files


def write_exclusive(out: Path, files: dict[str, bytes]):
    if out.is_symlink() or out.exists():
        raise Refused('Evidence output directory must not exist')
    out.mkdir(parents=False, mode=0o700)
    for name, data in files.items():
        target = out / name
        with target.open('xb') as f:
            f.write(data)


def verify(out: Path, root: Path, identity: dict, *, enforce_checkout: bool = True):
    if out.is_symlink() or not out.is_dir():
        raise Refused('No safe R4D evidence directory')
    if {x.name for x in out.iterdir()} != set(CHECKED_FILES):
        raise Refused('Unexpected or missing evidence file')
    manifest = strict_json(bounded(out / 'manifest.json'))
    if manifest.get('identity') != identity or manifest.get('release_decision') != 'BLOCKED' or manifest.get('publication_authorized') is not False:
        raise Refused('Identity or release gate mismatch')
    actual_files = {name: bounded(out / name, MAX_REPORT if name.endswith('.txt') else MAX_OUT)
                    for name in OUTPUT_FILES}
    if manifest.get('outputs_sha256') != {k: sha(v) for k, v in sorted(actual_files.items())}:
        raise Refused('R4D evidence artifact SHA-256 mismatch')
    expected_files = generate(root, actual_files['gradle-release-dependencies.txt'],
                              identity, enforce_checkout=enforce_checkout)
    for name in CHECKED_FILES:
        content = bounded(out / name)
        if content != expected_files[name]:
            raise Refused('R4D evidence diverges from independently recomputed source inventory: ' + name)
    return strict_json(actual_files['release-qualification.json'])


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('action', choices=('generate', 'verify'))
    p.add_argument('--root', type=Path, default=Path.cwd())
    p.add_argument('--gradle-report', type=Path)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--source', required=True)
    p.add_argument('--run', required=True)
    p.add_argument('--attempt', required=True)
    p.add_argument('--event', required=True)
    p.add_argument('--ref', required=True)
    args = p.parse_args()
    try:
        root = args.root.resolve(strict=True)
        identity = parse_identity(args.source, args.run, args.attempt, args.event, args.ref)
        # Validate the exact hosted runner repository as well as source/ref.
        if os.environ.get('GITHUB_ACTIONS') == 'true':
            expected = {'GITHUB_REPOSITORY': REPOSITORY, 'GITHUB_SHA': args.source,
                        'GITHUB_RUN_ID': args.run, 'GITHUB_RUN_ATTEMPT': args.attempt,
                        'GITHUB_EVENT_NAME': args.event, 'GITHUB_REF': args.ref}
            if any(os.environ.get(k) != v for k, v in expected.items()):
                raise Refused('GitHub workflow environment identity mismatch')
        if args.action == 'generate':
            if args.gradle_report is None:
                raise Refused('Expected hosted release dependency report')
            report = bounded(args.gradle_report, MAX_REPORT)
            files = generate(root, report, identity)
            write_exclusive(args.out.absolute(), files)
            print('R4D_INVENTORY_CREATED_RELEASE_BLOCKED', identity['source_sha'],
                  identity['run_id'], identity['attempt'])
        else:
            result = verify(args.out.absolute(), root, identity)
            print('R4D_HASH_SOURCE_POLICY_RECHECK_PASS_RELEASE_BLOCKED', result['status'])
        return 0
    except (Refused, r4b.Refused, OSError, ValueError, KeyError, UnicodeError) as exc:
        print('R4D_REFUSED:', exc, file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
