#!/usr/bin/env python3
"""Diagnostic-only, bounded KSP plugin-marker/cache probe for PR CI.

No Gradle commands, credentials, configuration edits, dependency downloads,
source mutations or CI gate overrides. Network results are advisory only.
"""
from __future__ import annotations
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import ssl
import subprocess
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, HTTPSHandler, ProxyHandler, Request, build_opener

SCHEMA = "mmrl-lsposed.gradle-plugin-probe.v1"
VERSION = "2.3.6"
MARKER = "com/google/devtools/ksp/com.google.devtools.ksp.gradle.plugin/2.3.6/com.google.devtools.ksp.gradle.plugin-2.3.6.pom"
IMPLEMENTATION = "com/google/devtools/ksp/symbol-processing-gradle-plugin/2.3.6/symbol-processing-gradle-plugin-2.3.6.pom"
ALLOWED = frozenset(("repo.maven.apache.org", "plugins.gradle.org", "plugins-artifacts.gradle.org", "dl.google.com"))
TARGETS = (
    ("central_marker", "https://repo.maven.apache.org/maven2/" + MARKER),
    ("portal_marker", "https://plugins.gradle.org/m2/" + MARKER),
    ("google_marker", "https://dl.google.com/dl/android/maven2/" + MARKER),
    ("central_implementation", "https://repo.maven.apache.org/maven2/" + IMPLEMENTATION),
    ("portal_implementation", "https://plugins.gradle.org/m2/" + IMPLEMENTATION),
)

class SafeRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urlparse(newurl)
        if parsed.scheme != "https" or parsed.hostname not in ALLOWED or parsed.username or parsed.password:
            raise URLError("DISALLOWED_REDIRECT")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def probe(name, url):
    parsed = urlparse(url)
    assert parsed.scheme == "https" and parsed.hostname in ALLOWED
    opener = build_opener(ProxyHandler({}), HTTPSHandler(context=ssl.create_default_context()), SafeRedirect())
    result = {"target": name, "host": parsed.hostname, "status": "INCONCLUSIVE"}
    try:
        with opener.open(Request(url, headers={"User-Agent": "mmrl-gradle-probe/1"}), timeout=7) as response:
            payload = response.read(32769)
            result["status"] = "HTTP_" + str(response.status)
            result["final_host"] = urlparse(response.geturl()).hostname
            result["oversize"] = len(payload) > 32768
            if not result["oversize"]:
                result["sha256"] = hashlib.sha256(payload).hexdigest()
                result["is_maven_pom"] = b"<project" in payload or b"<project " in payload
    except HTTPError as e:
        result["status"] = "HTTP_" + str(e.code)
    except (URLError, TimeoutError, ssl.SSLError, OSError, ValueError) as e:
        # Never serialize exception strings: proxies and networking errors can contain secrets.
        reason = e.reason if isinstance(e, URLError) else e
        result["status"] = "TRANSPORT_ERROR"
        result["error_type"] = type(e).__name__
        result["category"] = (
            "DNS" if isinstance(reason, socket.gaierror)
            else "TLS" if isinstance(reason, ssl.SSLError)
            else "TIMEOUT" if isinstance(reason, TimeoutError)
            else "CONNECTION" if isinstance(reason, (ConnectionError, ConnectionRefusedError))
            else "OTHER"
        )
    return result


def cache_status(home):
    base = home / "caches/modules-2/files-2.1/com.google.devtools.ksp"
    result = {}
    for artifact in ("com.google.devtools.ksp.gradle.plugin", "symbol-processing-gradle-plugin"):
        folder = base / artifact / VERSION
        regular = []
        if folder.is_dir() and not folder.is_symlink():
            for sub in list(folder.iterdir())[:48]:
                if sub.is_dir() and not sub.is_symlink():
                    regular += [p for p in list(sub.iterdir())[:24] if p.is_file() and not p.is_symlink()]
        result[artifact] = {"version_dir": folder.is_dir(), "pom_count": sum(p.suffix == ".pom" for p in regular),
                            "jar_count": sum(p.suffix == ".jar" for p in regular), "module_count": sum(p.suffix == ".module" for p in regular)}
    cache_root = home / "caches/modules-2"
    result["metadata_dirs"] = len([p for p in cache_root.glob("metadata-*") if p.is_dir()]) if cache_root.is_dir() else 0
    return result


def source_hashes(root):
    docs = ("settings.gradle.kts", "gradle/libs.versions.toml", "gradle.properties",
            "app/build.gradle.kts", "gradle/wrapper/gradle-wrapper.properties")
    return {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in docs}


def generate(root, job):
    catalog = (root / "gradle/libs.versions.toml").read_text(encoding="utf-8")
    assert re.search(r'^ksp\s*=\s*"2\.3\.6"\s*$', catalog, re.M), "unexpected KSP catalog"
    git = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"], capture_output=True, text=True, timeout=10)
    if git.returncode or not re.fullmatch(r"[a-f0-9]{40}\n?", git.stdout):
        raise RuntimeError("source identity unavailable")
    source = git.stdout.strip()
    if os.getenv("GITHUB_SHA") and source != os.getenv("GITHUB_SHA"):
        raise RuntimeError("CI checkout source mismatch")
    home = Path(os.getenv("GRADLE_USER_HOME") or str(Path.home() / ".gradle"))
    try:
        cache = cache_status(home)
    except OSError:
        cache = {"status": "INCONCLUSIVE"}
    with ThreadPoolExecutor(max_workers=5) as pool:
        futures = [pool.submit(probe, n, u) for n,u in TARGETS]
        network = []
        for (name, _), future in zip(TARGETS, futures):
            try:
                network.append(future.result(timeout=15))
            except Exception as exc:
                network.append({"target": name, "status": "INCONCLUSIVE",
                                "error_type": type(exc).__name__})
    return {
        "schema": SCHEMA, "job": job, "source_sha": source,
        "github_run_id": os.getenv("GITHUB_RUN_ID", ""),
        "github_run_attempt": os.getenv("GITHUB_RUN_ATTEMPT", ""),
        "runner_os": os.getenv("RUNNER_OS", ""),
        "runner_arch": os.getenv("RUNNER_ARCH", ""),
        "proxy_environment_present": any(os.getenv(k) for k in ("HTTPS_PROXY", "HTTP_PROXY", "https_proxy", "http_proxy")),
        "proxy_used_by_probe": False,
        "plugin_repositories": ("google", "mavenCentral", "gradlePluginPortal"),
        "ksp_version": VERSION,
        "source_files_sha256": source_hashes(root),
        "gradle_cache": cache,
        "endpoints": network,
        "advisory_only": True,
        "release_qualified": False,
        "device_qualified": False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--job", choices=("s01", "r4d"), required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    output = args.out.absolute()
    if output.is_symlink() or output.exists() or not output.parent.is_dir():
        raise SystemExit("KSP_PROBE_REFUSED: unsafe output path")
    report = generate(Path.cwd(), args.job)
    with output.open("x", encoding="utf-8") as fp:
        json.dump(report, fp, indent=2, sort_keys=True)
        fp.write("\n")
    print("KSP_PROBE_REPORT=" + output.name)
    print("KSP_PROBE_SOURCE=" + report["source_sha"])
    print("KSP_PROBE_CACHE=" + json.dumps(report["gradle_cache"], sort_keys=True))
    for record in report["endpoints"]:
        print("KSP_PROBE_ENDPOINT=" + record["target"] + ":" + record["status"])
    print("KSP_PROBE_ADVISORY_ONLY=true")

if __name__ == "__main__":
    main()
