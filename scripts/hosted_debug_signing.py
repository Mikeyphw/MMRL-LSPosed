#!/usr/bin/env python3
"""Sign an independently verified S01 debug APK on a trusted hosted main push.

This deliberately does *not* modify Devtool's native debug Gradle DAG or claim
release-signing authority. It uses the pinned Devtool AndroidSigningManager for
keystore identity inspection and independent signed-APK verification.

The private key exists only in a temporary 0600 file on the hosted runner. Its
password is supplied by a scoped GitHub secret environment variable, never by
arguments, Gradle properties, source files, or report contents.
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
import shutil
import subprocess
import sys
import tempfile

REPO = "Mikeyphw/MMRL-LSPosed"
PACKAGE = "io.github.mikeyphw.mmrl.lsposed.debug"
BRANCH_REF = "refs/heads/main"
POLICY = "config/debug-signing.json"
ALIAS = "mmrl_lsposed_debug"
SOURCE_APK = "app/build/outputs/apk/debug/app-debug.apk"
SOURCE_VERIFICATION = "s01-evidence/verification.json"
NATIVE_RECEIPT = "s01-outputs/node-01/receipt.json"
SECRET_KEY = "MMRL_DEBUG_KEYSTORE_B64"
SECRET_PASS = "MMRL_DEBUG_STORE_PASSWORD"
_SHA = re.compile(r"[0-9a-f]{64}\Z")
_BADGING = re.compile(r"(?m)^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'\s*")
_CERT = re.compile(r"(?im)^Signer #(\d+) certificate SHA-256 digest:\s*([0-9a-f]{64})\s*$")


class SignRefused(Exception):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(data)
    return digest.hexdigest()


def regular(path: Path, *, max_size: int) -> Path:
    if path.is_symlink() or not path.is_file():
        raise SignRefused("Required input must be a regular non-symlink file")
    if not 0 < path.stat().st_size <= max_size:
        raise SignRefused("Required input has an invalid size")
    return path


def read_json(path: Path) -> dict:
    regular(path, max_size=32768)
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise SignRefused("Expected JSON object")
    return value


def validate_policy(data: dict) -> str:
    required = {
        "schema": "mmrl-lsposed.debug-signing.v1",
        "repository": REPO,
        "application_id": PACKAGE,
        "ref": BRANCH_REF,
        "alias": ALIAS,
        "purpose": "debug-only",
    }
    for key, value in required.items():
        if data.get(key) != value or type(data.get(key)) is not str:
            raise SignRefused(f"Invalid debug-signing policy field: {key}")
    digest = data.get("certificate_sha256")
    if not isinstance(digest, str) or not _SHA.fullmatch(digest):
        raise SignRefused("Committed policy must pin a real lowercase SHA-256 certificate fingerprint")
    if set(data) != set(required) | {"certificate_sha256"}:
        raise SignRefused("Unexpected debug-signing policy field")
    return digest


def parse_badging(text: str) -> tuple[str, int, str]:
    found = _BADGING.search(text)
    if not found:
        raise SignRefused("APK package/version could not be verified")
    if "application-debuggable" not in text.splitlines():
        raise SignRefused("Signed APK must remain debuggable")
    return found.group(1), int(found.group(2)), found.group(3)


def parse_exactly_one_signer(text: str) -> str:
    matches = _CERT.findall(text)
    if len(matches) != 1 or matches[0][0] != "1":
        raise SignRefused("Expected exactly one verified APK signer")
    return matches[0][1].lower()


def invoke(argv: list[str], *, timeout: int = 120) -> str:
    # Never print a tool's potentially sensitive stderr or arguments.
    try:
        result = subprocess.run(argv, capture_output=True, text=True, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise SignRefused("Signing/verification tool unavailable or timed out") from exc
    if result.returncode != 0:
        raise SignRefused("Signing/verification command returned nonzero status")
    return result.stdout


def host_identity(root: Path) -> tuple[str, int, int, Path]:
    expected = {
        "GITHUB_ACTIONS": "true",
        "RUNNER_OS": "Linux",
        "RUNNER_ARCH": "X64",
        "GITHUB_REPOSITORY": REPO,
        "GITHUB_EVENT_NAME": "push",
        "GITHUB_REF": BRANCH_REF,
    }
    if any(os.environ.get(k) != v for k, v in expected.items()):
        raise SignRefused("Signing is restricted to GitHub-hosted Linux main-branch pushes")
    source = os.environ.get("GITHUB_SHA", "")
    if not re.fullmatch(r"[0-9a-f]{40}", source):
        raise SignRefused("Missing exact GitHub source SHA")
    actual = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
    if source != actual:
        raise SignRefused("GitHub run SHA differs from checked-out source")
    run, attempt = int(os.environ["GITHUB_RUN_ID"]), int(os.environ["GITHUB_RUN_ATTEMPT"])
    if run <= 0 or attempt <= 0:
        raise SignRefused("Invalid run/attempt identity")
    runner_temp = Path(os.environ["RUNNER_TEMP"]).resolve(strict=True)
    if runner_temp.is_symlink() or not runner_temp.is_dir():
        raise SignRefused("Invalid runner temporary directory")
    return source, run, attempt, runner_temp


def run(root: Path) -> dict:
    source, run_id, attempt, temp = host_identity(root)
    cert_sha = validate_policy(read_json(root / POLICY))
    pre = read_json(temp / SOURCE_VERIFICATION)
    if (pre.get("status") != "VERIFIED_DEBUG_ONLY"
            or pre.get("source_sha") != source
            or pre.get("run_id") != run_id or pre.get("attempt") != attempt
            or pre.get("package") != PACKAGE
            or pre.get("signature_verified") is not True
            or pre.get("package_verified") is not True
            or pre.get("release_qualified") is not False
            or not isinstance(pre.get("apk_sha256"), str)
            or not _SHA.fullmatch(pre["apk_sha256"])):
        raise SignRefused("Trusted Devtool input verification is missing or mismatched")
    original = regular(temp / "s01-outputs/node-01/payload" / SOURCE_APK, max_size=250_000_000)
    native = regular(temp / NATIVE_RECEIPT, max_size=32768)
    if sha256(original) != pre["apk_sha256"]:
        raise SignRefused("Original APK differs from verified Devtool artifact")
    receipt = read_json(native)
    if (receipt.get("source_commit") != source
            or receipt.get("graph_sha256") != pre.get("graph_sha256")
            or receipt.get("run_id") != str(run_id)
            or receipt.get("attempt") != attempt
            or receipt.get("kind") != "devtool.actions.node-handoff"):
        raise SignRefused("Devtool native receipt no longer matches the pinned build")
    encoded = os.environ.get(SECRET_KEY, "")
    password = os.environ.get(SECRET_PASS, "")
    if not encoded or not password:
        raise SignRefused("Required GitHub debug-signing secrets are not configured")
    if len(encoded) > 12_000_000:
        raise SignRefused("Keystore secret exceeds approved size")
    try:
        keystore_bytes = base64.b64decode(encoded, validate=True)
    except (ValueError, binascii.Error) as exc:
        raise SignRefused("Keystore secret is not valid base64") from exc
    if not 100 < len(keystore_bytes) <= 8_000_000:
        raise SignRefused("Decoded keystore size is invalid")
    sdk = Path(os.environ["ANDROID_SDK_ROOT"]) / "build-tools" / "36.0.0"
    apksigner, aapt = sdk / "apksigner", sdk / "aapt"
    for tool in (apksigner, aapt):
        if not tool.is_file() or not os.access(tool, os.X_OK):
            raise SignRefused("Pinned Android build-tools are unavailable")
    signed_dir = temp / "s01-signed"
    if signed_dir.is_symlink() or signed_dir.exists():
        raise SignRefused("Signed output directory already exists; no overwrites")
    signed_dir.mkdir(mode=0o700)
    signed_apk = signed_dir / "MMRL-LSPosed-debug-signed.apk"
    try:
        with tempfile.TemporaryDirectory(prefix="mmrl-debug-key-", dir=temp) as private_dir:
            keystore = Path(private_dir) / "debug-signing.p12"
            keystore.write_bytes(keystore_bytes)
            keystore.chmod(0o600)
            # Use Devtool's canonical identity parsing and fingerprint check.
            from devtool.android.signing import AndroidSigningManager, SigningProfile
            manager = AndroidSigningManager()
            profile = SigningProfile(
                name="debug", mode="project", policy="optional",
                keystore=str(keystore), alias=ALIAS, store_type="PKCS12",
                expected_cert_sha256=cert_sha,
            )
            identity = manager.inspect_profile(profile, store_password=password)
            if identity.sha256 != cert_sha:
                raise SignRefused("GitHub keystore differs from committed certificate identity")
            invoke([str(apksigner), "sign", "--ks", str(keystore),
                    "--ks-type", "PKCS12", "--ks-key-alias", ALIAS,
                    "--ks-pass", f"env:{SECRET_PASS}", "--key-pass", f"env:{SECRET_PASS}",
                    "--out", str(signed_apk), str(original)])
            signed = regular(signed_apk, max_size=250_000_000)
            verdict = manager.verify_artifact(signed, expected_sha256=cert_sha)
            if not verdict.ok or verdict.identity is None or verdict.identity.sha256 != cert_sha:
                raise SignRefused("Devtool rejected the newly signed APK identity")
        sig_output = invoke([str(apksigner), "verify", "--verbose", "--print-certs", str(signed_apk)])
        if parse_exactly_one_signer(sig_output) != cert_sha:
            raise SignRefused("APK signer fingerprint differs from committed trust anchor")
        original_badging = parse_badging(invoke([str(aapt), "dump", "badging", str(original)]))
        signed_badging = parse_badging(invoke([str(aapt), "dump", "badging", str(signed_apk)]))
        if original_badging != signed_badging or signed_badging[0] != PACKAGE:
            raise SignRefused("Signing changed package identity or version")
        signed_sha = sha256(signed_apk)
        proof = {
            "schema": "mmrl-lsposed.hosted-signed-debug.v1",
            "status": "SIGNED_DEBUG_VERIFIED_NOT_RELEASE", "repository": REPO,
            "source_sha": source, "run_id": run_id, "attempt": attempt,
            "devtool_sha": pre["devtool_sha"], "devtool_graph_sha256": pre["graph_sha256"],
            "devtool_node_receipt_sha256": sha256(native),
            "devtool_original_apk_sha256": pre["apk_sha256"],
            "signed_apk": signed_apk.name, "signed_apk_sha256": signed_sha,
            "signed_apk_size": signed_apk.stat().st_size,
            "certificate_sha256": cert_sha,
            "application_id": PACKAGE, "version_code": signed_badging[1],
            "version_name": signed_badging[2], "debuggable": True,
            "release_qualified": False, "device_qualified": False,
            "hooks_verified": False,
        }
        output_receipt = signed_dir / "signed-debug.json"
        with output_receipt.open("x", encoding="utf-8") as handle:
            json.dump(proof, handle, indent=2, sort_keys=True)
            handle.write("\n")
        shutil.copyfile(native, signed_dir / "devtool-node-receipt.json")
        print("SIGNED_DEBUG_VERIFIED_NOT_RELEASE: " + json.dumps({
            "source_sha": source, "run_id": run_id, "attempt": attempt,
            "apk_sha256": signed_sha, "certificate_sha256": cert_sha,
            "package": PACKAGE, "debuggable": True,
            "release_qualified": False,
        }, sort_keys=True))
        return proof
    except BaseException:
        shutil.rmtree(signed_dir)
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    options = parser.parse_args()
    try:
        run(Path(options.root).resolve(strict=True))
        return 0
    except (SignRefused, ValueError, OSError, KeyError, RuntimeError, subprocess.SubprocessError) as exc:
        # Never echo exception internals containing paths, passwords or keytool output.
        print(f"S01_SIGNING_REFUSED: {type(exc).__name__}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
