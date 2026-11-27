"""R5D cold-start visual stability and strictly read-only device evidence."""
from pathlib import Path
import importlib.util
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
APP=(ROOT/"app/src/main/java/io/github/mikeyphw/mmrl/lsposed")
SPEC=importlib.util.spec_from_file_location("r5d_readonly", ROOT/"scripts/r5d_device_readonly.py")
assert SPEC and SPEC.loader
mod=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mod)

class FakeRun:
    def __init__(self, stdout=b"device\n", returncode=0):
        self.stdout=stdout; self.returncode=returncode

class R5DStability(unittest.TestCase):
    def test_appearance_persists_and_safe_fallback(self):
        main=(APP/"MainActivity.kt").read_text()
        settings=(APP/"LocalSettings.kt").read_text()
        policy=(APP/"AppearancePreference.kt").read_text()
        self.assertIn("Appearance.valueOf(LocalSettings.appearance(context.applicationContext))", main)
        self.assertIn("LocalSettings.setAppearance(context.applicationContext, selected.name)", main)
        self.assertIn("getString(APPEARANCE, null)", settings)
        self.assertIn("putString(APPEARANCE, value).apply()", settings)
        self.assertIn('?: "SYSTEM"',policy)
        self.assertNotIn("getGitHubToken",policy)

    def test_home_error_never_claims_still_loading(self):
        main=(APP/"MainActivity.kt").read_text()
        for s in ("HomePage(combined, discoveryError, vector",'discoveryError -> "Module scan unavailable"',
                  '"home_retry_module_scan"',"onRetryScan = { candidates = null; refreshKey++ }"):
            self.assertIn(s,main)
        self.assertIn("LaunchedEffect(refreshKey)", main)

    def test_readonly_device_probe_uses_strict_allowlist(self):
        calls=[]
        def fake_run(command,**kwargs):
            calls.append(command)
            tail=command[3:]
            if tail==["get-state"]:return FakeRun()
            if tail==["shell","getprop","ro.product.model"]:return FakeRun(b"M2101K7BG\n")
            if tail==["shell","getprop","ro.build.version.sdk"]:return FakeRun(b"36\n")
            if tail==["shell","pm","path",mod.PACKAGE]:return FakeRun(b"package:/data/app/example/base.apk\n")
            raise AssertionError("Unexpected ADB operation: "+str(tail))
        with patch.object(mod.shutil,"which",return_value="/fake/adb"),patch.object(mod.subprocess,"run",side_effect=fake_run):
            result=mod.status("localhost:5555")
        self.assertTrue(result["installed"])
        self.assertEqual(result["sdk"],"36")
        self.assertFalse(result["root_checked"])
        self.assertEqual(len(calls),4)
        self.assertTrue(all("su" not in c and "install" not in c and "reboot" not in c for c in calls))

    def test_capture_only_on_explicit_command_and_private_output(self):
        png=b"\x89PNG\r\n\x1a\n"+b"x"*120
        calls=[]
        def fake_run(command,**kwargs):
            calls.append(command[3:])
            if command[-1]=="get-state":return FakeRun()
            if command[-2:]==["screencap","-p"]:return FakeRun(png)
            raise AssertionError(command)
        with tempfile.TemporaryDirectory() as temp:
            destination=Path(temp)/"captures"
            with patch.object(mod.shutil,"which",return_value="/fake/adb"),patch.object(mod.subprocess,"run",side_effect=fake_run):
                saved=mod.capture("localhost:5555",destination)
            self.assertEqual(saved.read_bytes(),png)
            self.assertEqual(calls,[["get-state"],["exec-out","screencap","-p"]])
            self.assertEqual(saved.stat().st_mode & 0o777,0o600)

    def test_invalid_serial_refused(self):
        for bad in ("", "--bad", "localhost:5555;reboot", "a b", "../bad"):
            with self.assertRaises(ValueError): mod.serial_ok(bad)

    def test_device_probe_has_no_mutation_or_secrets(self):
        src=(ROOT/"scripts/r5d_device_readonly.py").read_text()
        self.assertNotIn('"su"',src)
        for forbidden in ('"install"', '"uninstall"','"reboot"','"clear"','"setprop"','"force-stop"'):
            self.assertNotIn(forbidden,src)
        self.assertNotIn('gitHubToken(',src)
        self.assertNotIn('VectorMutation.',src)

if __name__=="__main__": unittest.main()
