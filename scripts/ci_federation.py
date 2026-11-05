#!/usr/bin/env python3
"""Fail-closed *self-reported* Devtool federation index for hosted evidence.

A JSON index is not an attestation, release certificate, or independent trust root.
It is an ordinary artifact bundled with the existing source/graph evidence.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys

from hosted_devtool import DEVTOOL_SHA, PACKAGE, REPO, TARGET, WORKFLOW

HEX40 = re.compile(r'[a-f0-9]{40}\Z')
HEX64 = re.compile(r'[a-f0-9]{64}\Z')
NODES = {'apk': 'node-01', 'lint': 'node-02', 'test': 'node-03'}


def build_index(plan: dict, verified: dict, env: dict[str, str]) -> dict:
    source, run, attempt = env.get('GITHUB_SHA', ''), env.get('GITHUB_RUN_ID', ''), env.get('GITHUB_RUN_ATTEMPT', '')
    event = env.get('GITHUB_EVENT_NAME', '')
    if (env.get('GITHUB_ACTIONS') != 'true' or env.get('GITHUB_REPOSITORY') != REPO or
            not HEX40.fullmatch(source) or not run.isascii() or not run.isdecimal() or
            not attempt.isascii() or not attempt.isdecimal() or int(run) < 1 or
            int(attempt) < 1 or event not in ('push', 'pull_request')):
        raise ValueError('GitHub-hosted run/source/attempt identity not admitted')
    if event == 'push' and env.get('GITHUB_REF') != 'refs/heads/main':
        raise ValueError('Only trusted-main push may use push federation path')
    if event == 'pull_request' and not re.fullmatch(r'refs/pull/[1-9][0-9]*/merge', env.get('GITHUB_REF', '')):
        raise ValueError('Unexpected pull-request ref')
    required = {
        'schema': 'mmrl.lsposed.s01.devtool-plan.v1',
        'source_sha': source, 'devtool_sha': DEVTOOL_SHA,
        'node_ids': NODES, 'package': PACKAGE,
        'release_qualified': False, 'device_qualified': False,
    }
    for name, document in [('plan', plan), ('verification', verified)]:
        if not isinstance(document, dict) or any(document.get(k) != v for k, v in required.items()):
            raise ValueError(f'{name} is not the exact source-bound Devtool plan')
        if not isinstance(document.get('graph_sha256'), str) or not HEX64.fullmatch(document['graph_sha256']):
            raise ValueError(f'{name} missing SHA-256 graph identity')
    if verified['graph_sha256'] != plan['graph_sha256']:
        raise ValueError('Devtool DAG graph differs from verified receipt')
    if (verified.get('status') != 'VERIFIED_DEBUG_ONLY' or
            verified.get('run_id') != int(run) or verified.get('attempt') != int(attempt) or
            verified.get('package_verified') is not True or
            verified.get('signature_verified') is not True or
            verified.get('release_qualified') is not False or
            verified.get('device_qualified') is not False or
            verified.get('hooks_verified') is not False or
            not HEX64.fullmatch(str(verified.get('apk_sha256', ''))) or
            not HEX64.fullmatch(str(verified.get('debug_signer_sha256', ''))) or
            not isinstance(verified.get('apk_size'), int) or verified['apk_size'] < 1000):
        raise ValueError('Unqualified or unbound Devtool verification')
    return {
        'schema': 'mmrl-lsposed.devtool-federation-index.v1',
        'repository': REPO, 'source_sha': source,
        'run_id': int(run), 'attempt': int(attempt), 'event': event,
        'devtool_sha': DEVTOOL_SHA, 'devtool_target': TARGET,
        'devtool_workflow': WORKFLOW, 'graph_sha256': plan['graph_sha256'],
        'node_ids': dict(NODES),
        'debug_apk_sha256': verified['apk_sha256'],
        'debug_apk_size': verified['apk_size'],
        'debug_apk_signer_sha256': verified['debug_signer_sha256'],
        'evidence_artifact_name': f'mmrl-lsposed-s01-evidence-{run}-attempt{attempt}',
        'verification_status': 'VERIFIED_DEBUG_ONLY',
        'device_qualified': False, 'release_qualified': False,
        'hooks_verified': False, 'independently_attested': False,
        'note': 'Self-reported hosted Devtool evidence index; review immutable receipts independently.',
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--verification', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        inputs = (args.plan, args.verification)
        for path in inputs:
            if path.is_symlink() or not path.is_file() or path.stat().st_size not in range(1, 32769):
                raise ValueError('Invalid or unsafe evidence input')
        data = build_index(*(json.loads(x.read_text('utf-8')) for x in inputs), dict(os.environ))
        if args.output.is_symlink() or args.output.exists():
            raise ValueError('Refusing federation index overwrite')
        if args.output.parent != args.plan.parent or args.output.parent != args.verification.parent:
            raise ValueError('Federation index must be written alongside its evidence')
        with args.output.open('x', encoding='utf-8') as output:
            json.dump(data, output, sort_keys=True, indent=2)
            output.write('\n')
        print('R3A_DEVTOOL_FEDERATION_INDEX_PASS', data['source_sha'][:12], data['run_id'], data['attempt'])
        return 0
    except (OSError, ValueError, TypeError, KeyError) as exc:
        print('R3A_FEDERATION_REFUSED:', str(exc), file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
