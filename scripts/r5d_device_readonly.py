#!/usr/bin/env python3
"""Opt-in, read-only local ADB evidence for R5D. Never installs, roots, or writes device settings."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone

PACKAGE = "io.github.mikeyphw.mmrl.lsposed.debug"
MAX_SCREENSHOT = 25 * 1024 * 1024


def serial_ok(value: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}", value):
        raise ValueError("Invalid ADB serial")
    return value


def adb(serial: str, *args: str, binary: bool = False):
    executable = shutil.which("adb")
    if executable is None:
        raise ValueError("adb not on PATH")
    result = subprocess.run([executable, "-s", serial, *args], capture_output=True,
                            timeout=30, check=False)
    if result.returncode:
        raise ValueError("ADB command failed; confirm device connection and authorization")
    if binary:
        return result.stdout
    return result.stdout.decode("utf-8", errors="replace").strip()


def status(serial: str) -> dict:
    if adb(serial, "get-state") != "device":
        raise ValueError("Device is not authorized")
    model = adb(serial, "shell", "getprop", "ro.product.model")
    sdk = adb(serial, "shell", "getprop", "ro.build.version.sdk")
    installed = adb(serial, "shell", "pm", "path", PACKAGE).startswith("package:")
    return {"serial": serial, "model": model[:100], "sdk": sdk[:12],
            "debug_package": PACKAGE, "installed": installed,
            "root_checked": False, "vector_mutated": False}


def capture(serial: str, out: Path) -> Path:
    if adb(serial, "get-state") != "device":
        raise ValueError("Device is not authorized")
    data = adb(serial, "exec-out", "screencap", "-p", binary=True)
    if not data.startswith(b"\x89PNG\r\n\x1a\n") or not 100 <= len(data) <= MAX_SCREENSHOT:
        raise ValueError("Invalid or oversized PNG screenshot")
    if out.is_symlink():
        raise ValueError("Screenshot directory may not be a symlink")
    out.mkdir(parents=True, exist_ok=True, mode=0o700)
    out.chmod(0o700)
    name = "mmrl-r5d-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + ".png"
    destination = out / name
    with destination.open("xb") as stream:
        os.chmod(destination, 0o600)
        stream.write(data)
    return destination


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("status", "capture"))
    parser.add_argument("--serial", default="localhost:5555")
    parser.add_argument("--out", type=Path,
                        default=Path.home()/".local/state/mmrl-lsposed/r5d-screenshots")
    args = parser.parse_args()
    try:
        serial = serial_ok(args.serial)
        if args.action == "status":
            print(json.dumps(status(serial), indent=2, sort_keys=True))
        else:
            path = capture(serial, args.out.expanduser())
            print("SCREENSHOT_SAVED_LOCALLY", path)
            print("Screenshots may show personal data; inspect before sharing.")
        return 0
    except (OSError, ValueError, subprocess.TimeoutExpired) as exc:
        print("R5D_READONLY_REFUSED:", exc, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
