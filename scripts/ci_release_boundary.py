#!/usr/bin/env python3
"""R4A trusted-main *debug-only* signing boundary.

Reconcile signed debug APK bytes, pinned public signer identity, Devtool's
original APK receipt, and R3C portable evidence. This does not sign APKs,
verify GitHub's OIDC attestation, admit a production release, or install files.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys

import ci_federation_portable as portable

REPO = 'Mikeyphw/MMRL-LSPosed'
PACKAGE = 'io.github.mikeyphw.mmrl.lsposed.debug'
APK = 'MMRL-LSPosed-debug-signed.apk'
SHA40 = re.compile(r'[0-9a-f]{40}\Z')
SHA64 = re.compile(r'[0-9a-f]{64}\Z')
DEVTOOL_SHA = 'b164463db51e2cffd12f753043b9e55a08cc43ae'
SIGN_FIELDS = frozenset({
    'schema', 'status', 'repository', 'source_sha', 'run_id', 'attempt',
    'devtool_sha', 'devtool_graph_sha256', 'devtool_node_receipt_sha256',
    'devtool_original_apk_sha256', 'signed_apk', 'signed_apk_sha256',
    'signed_apk_size', 'certificate_sha256', 'application_id', 'version_code',
    'version_name', 'debuggable', 'release_qualified', 'device_qualified',
    'hooks_verified',
})
POLICY_FIELDS = frozenset({
    'schema', 'repository', 'application_id', 'ref', 'alias', 'purpose',
    'certificate_sha256',
})


def _positive(value: object) -> bool:
    return type(value) is int and value > 0


def _hex(value: object, pattern: re.Pattern) -> bool:
    return isinstance(value, str) and pattern.fullmatch(value) is not None


def _sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(part)
    return digest.hexdigest()


def _regular(path: Path, limit: int) -> Path:
    if (path.is_symlink() or not path.is_file() or
            not 0 < path.stat().st_size <= limit):
        raise ValueError('Unsafe, absent, or oversized input file')
    return path


def _json(path: Path) -> dict:
    value = portable.decode(_regular(path, 32768).read_bytes())
    return value


def validate_policy(policy: dict) -> str:
    exact = {
        'schema': 'mmrl-lsposed.debug-signing.v1',
        'repository': REPO, 'application_id': PACKAGE,
        'ref': 'refs/heads/main', 'alias': 'mmrl_lsposed_debug',
        'purpose': 'debug-only',
    }
    if (set(policy) != POLICY_FIELDS or
            any(type(policy.get(k)) is not str or policy[k] != v for k, v in exact.items()) or
            not _hex(policy.get('certificate_sha256'), SHA64)):
        raise ValueError('Committed debug signer policy is not exact')
    return policy['certificate_sha256']


def verify(*, signed: Path, evidence: Path, policy: Path,
           source: str, run: int, attempt: int, devtool_sha: str) -> dict:
    if (not _hex(source, SHA40) or not _hex(devtool_sha, SHA40) or
            devtool_sha != DEVTOOL_SHA or not _positive(run) or not _positive(attempt)):
        raise ValueError('Unrecognized trusted run/source or Devtool pin')
    if signed.is_symlink() or not signed.is_dir():
        raise ValueError('Missing trusted-main signed directory')
    cert = validate_policy(_json(policy))
    evidence_files = portable.directory_files(evidence, require_contract=True)
    portable.check(evidence_files, {
        'repository': REPO, 'source_sha': source, 'run_id': run,
        'attempt': attempt, 'event': 'push', 'devtool_sha': devtool_sha,
    })
    plan, original, index = portable.identities(evidence_files)
    if index['debug_apk_sha256'] != original['apk_sha256']:
        raise ValueError('Original APK SHA differs from portable evidence')
    receipt = _json(signed / 'signed-debug.json')
    node = _json(signed / 'devtool-node-receipt.json')
    apk = _regular(signed / APK, 250_000_000)
    required = {
        'schema': 'mmrl-lsposed.hosted-signed-debug.v1',
        'status': 'SIGNED_DEBUG_VERIFIED_NOT_RELEASE',
        'repository': REPO, 'source_sha': source, 'run_id': run, 'attempt': attempt,
        'devtool_sha': devtool_sha, 'devtool_graph_sha256': original['graph_sha256'],
        'devtool_original_apk_sha256': original['apk_sha256'],
        'signed_apk': APK, 'signed_apk_size': apk.stat().st_size,
        'certificate_sha256': cert, 'application_id': PACKAGE,
        'debuggable': True, 'release_qualified': False,
        'device_qualified': False, 'hooks_verified': False,
    }
    if set(receipt) != SIGN_FIELDS or any(
        type(receipt.get(k)) is not type(v) or receipt.get(k) != v
        for k, v in required.items()
    ):
        raise ValueError('Signed-debug receipt claims release status or mismatched identity')
    if (not _hex(receipt.get('signed_apk_sha256'), SHA64) or
            not _hex(receipt.get('devtool_node_receipt_sha256'), SHA64) or
            not _positive(receipt.get('version_code')) or
            not isinstance(receipt.get('version_name'), str) or
            not re.fullmatch(r'[A-Za-z0-9._-]{1,80}-debug', receipt['version_name'])):
        raise ValueError('Invalid debug-only APK version or hash')
    if _sha(apk) != receipt['signed_apk_sha256']:
        raise ValueError('Signed APK bytes do not match receipt')
    if _sha(signed / 'devtool-node-receipt.json') != receipt['devtool_node_receipt_sha256']:
        raise ValueError('Immutable Devtool handoff bytes differ')
    if (node.get('schema_version') != 2 or
            node.get('kind') != 'devtool.actions.node-handoff' or
            node.get('source_commit') != source or
            node.get('graph_sha256') != original['graph_sha256'] or
            node.get('run_id') != str(run) or node.get('attempt') != attempt or
            node.get('producer_job') != original['node_ids']['apk'] or
            node.get('release_qualified') is not False or
            not isinstance(node.get('files'), list) or len(node['files']) != 1 or
            node['files'][0] != {
                'path': 'app/build/outputs/apk/debug/app-debug.apk',
                'sha256': original['apk_sha256'], 'size': original['apk_size'],
            }):
        raise ValueError('Devtool native handoff does not bind original debug bytes')
    return {
        'status': 'R4A_TRUSTED_DEBUG_BOUNDARY_PASS_NOT_RELEASE',
        'source_sha': source, 'run_id': run, 'attempt': attempt,
        'signed_apk_sha256': receipt['signed_apk_sha256'],
        'certificate_sha256': cert,
        'release_qualified': False, 'device_qualified': False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['verify'])
    parser.add_argument('--signed', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--policy', type=Path, required=True)
    parser.add_argument('--source', required=True)
    parser.add_argument('--run', required=True, type=int)
    parser.add_argument('--attempt', required=True, type=int)
    parser.add_argument('--devtool-sha', required=True)
    args = parser.parse_args()
    try:
        if (os.environ.get('GITHUB_ACTIONS') != 'true' or
                os.environ.get('GITHUB_EVENT_NAME') != 'push' or
                os.environ.get('GITHUB_REF') != 'refs/heads/main' or
                os.environ.get('GITHUB_REPOSITORY') != REPO or
                os.environ.get('GITHUB_SHA') != args.source or
                os.environ.get('GITHUB_RUN_ID') != str(args.run) or
                os.environ.get('GITHUB_RUN_ATTEMPT') != str(args.attempt)):
            raise ValueError('Verifier restricted to exact trusted-main GitHub run')
        result = verify(signed=args.signed, evidence=args.evidence,
                        policy=args.policy, source=args.source, run=args.run,
                        attempt=args.attempt, devtool_sha=args.devtool_sha)
        print(result['status'], result['source_sha'][:12], result['run_id'], result['attempt'])
        return 0
    except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError) as exc:
        print('R4A_SIGNED_BOUNDARY_REFUSED:', type(exc).__name__, file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
