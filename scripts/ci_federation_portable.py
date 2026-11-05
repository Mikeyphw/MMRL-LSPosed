#!/usr/bin/env python3
"""R3C: portable, fail-closed read-only Devtool federation evidence contract.

Producer: emits a self-described contract alongside existing source/graph receipts.
Consumer: verifies an untrusted ZIP against an *independently supplied* GitHub
repository, source commit, run, attempt, event, and reviewed Devtool pin.
This does not authenticate GitHub as a producer or qualify APK signing/releases.
No network, GitHub token, Android device, root or third-party Python modules.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import zipfile

SCHEMA = 'mmrl-lsposed.devtool-federation-portable.v1'
INDEX_SCHEMA = 'mmrl-lsposed.devtool-federation-index.v1'
FILES = ('plan.json', 'verification.json', 'federation-index.json')
CONTRACT = 'portable-federation-contract.json'
ALL_FILES = (*FILES, CONTRACT)
MAX_FILE = 32768
MAX_TOTAL = 131072
SHA40 = re.compile(r'[a-f0-9]{40}\Z')
SHA64 = re.compile(r'[a-f0-9]{64}\Z')
REPOSITORY = re.compile(r'[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}\Z')
NODE = re.compile(r'node-[0-9]{2,3}\Z')
NAME = re.compile(r'[a-z][a-z0-9_-]{0,31}\Z')
UNQUALIFIED = {'release_qualified': False, 'device_qualified': False,
               'hooks_verified': False, 'independently_attested': False}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def canonical(doc: dict) -> bytes:
    return json.dumps(doc, ensure_ascii=True, sort_keys=True,
                      separators=(',', ':'), allow_nan=False).encode('ascii')


def no_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Repeated JSON key: ' + key)
        result[key] = value
    return result


def decode(data: bytes) -> dict:
    if not 1 <= len(data) <= MAX_FILE:
        raise ValueError('Evidence file length outside 1..32768 bytes')
    value = json.loads(data.decode('utf-8'), object_pairs_hook=no_duplicate_keys,
                       parse_constant=lambda s: (_ for _ in ()).throw(ValueError('Nonfinite JSON value')))
    if not isinstance(value, dict):
        raise ValueError('Evidence JSON must be an object')
    return value


def positive(value) -> bool:
    return type(value) is int and value > 0


def hex40(value) -> bool:
    return isinstance(value, str) and SHA40.fullmatch(value) is not None


def hex64(value) -> bool:
    return isinstance(value, str) and SHA64.fullmatch(value) is not None


def identities(files: dict[str, bytes]) -> tuple[dict, dict, dict]:
    plan, verified, index = (decode(files[name]) for name in FILES)
    repo, source, devtool = index.get('repository'), index.get('source_sha'), index.get('devtool_sha')
    run, attempt, event = index.get('run_id'), index.get('attempt'), index.get('event')
    graph = index.get('graph_sha256')
    nodes = index.get('node_ids')
    if (index.get('schema') != INDEX_SCHEMA or not isinstance(repo, str) or
            not REPOSITORY.fullmatch(repo) or not hex40(source) or not hex40(devtool) or
            not hex64(graph) or not positive(run) or not positive(attempt) or
            event not in ('push', 'pull_request') or
            not isinstance(nodes, dict) or len(nodes) not in range(1, 25) or
            any(not isinstance(k, str) or not NAME.fullmatch(k) or
                    not isinstance(v, str) or not NODE.fullmatch(v)
                    for k, v in nodes.items()) or len(set(nodes.values())) != len(nodes) or
            index.get('verification_status') != 'VERIFIED_DEBUG_ONLY' or
            index.get('evidence_artifact_name') != f'mmrl-lsposed-s01-evidence-{run}-attempt{attempt}'):
        raise ValueError('Invalid source/Devtool/run identity or unrecognized federation schema')
    for key, value in UNQUALIFIED.items():
        if index.get(key) is not value:
            raise ValueError('Federation index falsely claims qualification: ' + key)
    if (index.get('devtool_target') != 'lsposed_app' or index.get('devtool_workflow') != 'ci'):
        raise ValueError('Unreviewed Devtool target/workflow')
    for label, doc in (('plan', plan), ('verification', verified)):
        if (doc.get('schema') != 'mmrl.lsposed.s01.devtool-plan.v1' or
                doc.get('source_sha') != source or doc.get('devtool_sha') != devtool or
                doc.get('graph_sha256') != graph or doc.get('node_ids') != nodes or
                doc.get('release_qualified') is not False or doc.get('device_qualified') is not False or
                doc.get('package') != 'io.github.mikeyphw.mmrl.lsposed.debug'):
            raise ValueError(label+' does not match the index and reviewed consumer graph')
    if (verified.get('status') != 'VERIFIED_DEBUG_ONLY' or
            type(verified.get('run_id')) is not int or verified.get('run_id') != run or
            type(verified.get('attempt')) is not int or verified.get('attempt') != attempt or
            verified.get('apk_sha256') != index.get('debug_apk_sha256') or
            verified.get('apk_size') != index.get('debug_apk_size') or
            verified.get('debug_signer_sha256') != index.get('debug_apk_signer_sha256') or
            not hex64(verified.get('apk_sha256')) or not hex64(verified.get('debug_signer_sha256')) or
            not positive(verified.get('apk_size')) or verified['apk_size'] < 1000 or
            verified.get('package_verified') is not True or
            verified.get('signature_verified') is not True or
            verified.get('hooks_verified') is not False):
        raise ValueError('Devtool verification is not exact-source debug-only evidence')
    return plan, verified, index


def compose(files: dict[str, bytes]) -> dict:
    _, _, i = identities(files)
    result = {
        'schema': SCHEMA,
        'kind': 'devtool.actions.consumer-evidence-handoff',
        'producer': {k: i[k] for k in ('repository', 'source_sha', 'run_id', 'attempt', 'event')},
        'devtool': {k: i[k] for k in ('devtool_sha', 'devtool_target', 'devtool_workflow',
                                   'graph_sha256', 'node_ids')},
        'debug_artifact': {
            'sha256': i['debug_apk_sha256'], 'size': i['debug_apk_size'],
            'debug_signer_sha256': i['debug_apk_signer_sha256'],
            'evidence_artifact_name': i['evidence_artifact_name'],
        },
        'source_file_sha256': {name: sha(files[name]) for name in FILES},
        'qualifications': dict(UNQUALIFIED),
        'verification_status': 'VERIFIED_DEBUG_ONLY',
        'trust_model': 'self-reported consistency only; not an independent attestation',
    }
    result['contract_sha256'] = sha(canonical(result))
    return result


def check(files: dict[str, bytes], expected: dict) -> dict:
    doc = decode(files[CONTRACT])
    # Exact structural comparison also catches unexpected, conflicting trust fields.
    if doc != compose(files):
        raise ValueError('Contract differs from the exact source files or claims more trust')
    if not hex64(doc.get('contract_sha256')):
        raise ValueError('Contract digest malformed')
    producer = doc['producer']
    devtool = doc['devtool']
    for k, want in expected.items():
        source = devtool if k == 'devtool_sha' else producer
        if source.get(k) != want:
            raise ValueError('Independent GitHub identity mismatch: ' + k)
    return doc


def directory_files(root: Path, *, require_contract: bool) -> dict[str, bytes]:
    wanted = ALL_FILES if require_contract else FILES
    if not root.is_dir() or root.is_symlink():
        raise ValueError('Evidence directory absent or symlinked')
    result = {}
    for name in wanted:
        path = root/name
        if path.is_symlink() or not path.is_file() or path.stat().st_size not in range(1, MAX_FILE + 1):
            raise ValueError('Missing or invalid evidence file: ' + name)
        result[name] = path.read_bytes()
    return result


def archive_files(path: Path) -> dict[str, bytes]:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 1024*1024:
        raise ValueError('Unsafe or oversized evidence archive')
    with zipfile.ZipFile(path) as z:
        members = z.infolist()
        if len(members) != len(ALL_FILES) or {i.filename for i in members} != set(ALL_FILES):
            raise ValueError('Evidence ZIP must contain exactly four root-level JSON receipts')
        result = {}
        for item in members:
            unix_type = (item.external_attr >> 16) & 0o170000
            if (item.is_dir() or item.flag_bits & 1 or item.compress_type not in (
                    zipfile.ZIP_STORED,zipfile.ZIP_DEFLATED) or
                    unix_type not in (0, stat.S_IFREG) or
                    item.file_size not in range(1, MAX_FILE+1)):
                raise ValueError('Unsafe or oversized ZIP member: '+item.filename)
            data = z.read(item)
            if len(data) != item.file_size:
                raise ValueError('Archive member length mismatch')
            result[item.filename] = data
        if sum(len(x) for x in result.values()) > MAX_TOTAL:
            raise ValueError('Evidence bundle expanded beyond cap')
        return result


def generate(directory: Path) -> dict:
    files = directory_files(directory, require_contract=False)
    # Trust the existing R3A hosted validation only after re-executing it
    # against GITHUB_SHA/attempt/ref and the reviewed Devtool source pin.
    import ci_federation
    plan, verified, index = identities(files)
    if ci_federation.build_index(plan, verified, dict(os.environ)) != index:
        raise ValueError('R3A hosted federation index differs from verified source')
    contract = compose(files)
    output = directory/CONTRACT
    if output.exists() or output.is_symlink():
        raise ValueError('Refusing overwrite of existing portable contract')
    with output.open('x', encoding='ascii') as stream:
        json.dump(contract, stream, sort_keys=True, indent=2, allow_nan=False)
        stream.write('\n')
        stream.flush()
        os.fsync(stream.fileno())
    return contract


def cli() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    make = commands.add_parser('generate', help='Write alongside hosted Devtool evidence once')
    make.add_argument('--directory', type=Path, required=True)
    verify = commands.add_parser('verify', help='Offline verification against independent GitHub identity')
    origin = verify.add_mutually_exclusive_group(required=True)
    origin.add_argument('--archive', type=Path)
    origin.add_argument('--directory', type=Path)
    verify.add_argument('--repository', required=True)
    verify.add_argument('--source-sha', required=True)
    verify.add_argument('--run-id', type=int, required=True)
    verify.add_argument('--attempt', type=int, required=True)
    verify.add_argument('--event', choices=('push','pull_request'), required=True)
    verify.add_argument('--devtool-sha', required=True)
    args = parser.parse_args()
    try:
        if args.command == 'generate':
            result = generate(args.directory)
            print('R3C_PORTABLE_CONTRACT_PASS',result['producer']['source_sha'][:12])
        else:
            if (not REPOSITORY.fullmatch(args.repository) or not hex40(args.source_sha) or
                    not hex40(args.devtool_sha) or args.run_id <= 0 or args.attempt <= 0):
                raise ValueError('Invalid independent GitHub identity arguments')
            files = archive_files(args.archive) if args.archive else directory_files(args.directory, require_contract=True)
            expected = dict(repository=args.repository,source_sha=args.source_sha,
                            run_id=args.run_id,attempt=args.attempt,event=args.event,
                            devtool_sha=args.devtool_sha)
            result = check(files, expected)
            print('R3C_PORTABLE_VERIFY_PASS',result['producer']['source_sha'][:12],
                  result['producer']['run_id'],result['producer']['attempt'])
        return 0
    except (OSError,ValueError,KeyError,TypeError,zipfile.BadZipFile,UnicodeError,
            json.JSONDecodeError) as e:
        print('R3C_PORTABLE_REFUSED:',str(e),file=sys.stderr)
        return 2

if __name__ == '__main__':
    raise SystemExit(cli())
