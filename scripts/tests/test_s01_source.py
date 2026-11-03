"""Offline S01 source contracts; does not build or run Android code."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


class S01SourceContracts(unittest.TestCase):
    def test_independent_identity(self):
        build = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn('namespace = "io.github.mikeyphw.mmrl.lsposed"', build)
        self.assertIn('applicationId = "io.github.mikeyphw.mmrl.lsposed"', build)
        self.assertIn('applicationIdSuffix = ".debug"', build)
        self.assertNotIn('namespace = "com.dergoogler.mmrl"', build)

    def test_app_has_no_privileged_entrypoints(self):
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        root = ET.fromstring(manifest)
        ns = "{http://schemas.android.com/apk/res/android}"
        permissions = [e.get(ns + "name") for e in root.findall("uses-permission")]
        self.assertEqual(permissions, ["android.permission.QUERY_ALL_PACKAGES"])
        application = root.find("application")
        self.assertIsNotNone(application)
        self.assertEqual(len(application.findall("activity")), 1)
        self.assertEqual(len(application.findall("service")), 0)
        self.assertEqual(len(application.findall("receiver")), 0)
        self.assertEqual(len(application.findall("provider")), 0)

    def test_no_cli_or_root_transport(self):
        source = "\n".join(p.read_text() for p in (ROOT / "app/src/main/java").rglob("*.kt"))
        for pattern in (r'ProcessBuilder\s*\(', r'Runtime\.getRuntime\s*\(',
                        r'RootService', r'IVectorCli', r'\bsu\s+-c\b',
                        r'/data/adb/', r'\bexec\s*\('):
            with self.subTest(pattern=pattern):
                self.assertNotRegex(source, pattern)
        self.assertIn("ZipFile", source)
        self.assertIn("getInstalledApplications", source)

    def test_hosted_devtool_only(self):
        flow = (ROOT / ".github/workflows/s01-hosted-devtool.yml").read_text()
        self.assertIn('scripts/hosted_devtool.py run --node apk', flow)
        self.assertIn('scripts/hosted_devtool.py run --node lint', flow)
        self.assertNotRegex(flow, r'(?m)^\s+(?:gradle|\./gradlew)\s')
        config = (ROOT / ".devtool.toml").read_text()
        self.assertIn('[targets.lsposed_app.workflows]', config)
        self.assertIn('ci_android_wrapper_jar_sha256 = "d3b261c2820e9e3d8d639ed084900f11f4a86050a8f83342ade7b6bc9b0d2bdd"', config)
        self.assertIn('gradle_tasks = [":app:assembleDebug"]', config)
        self.assertIn('gradle_tasks = [":app:lintDebug"]', config)

    def test_english_only_resources_and_symbols_exist(self):
        resource_root = ROOT / "app/src/main/res"
        en = ET.parse(resource_root / "values/strings.xml").getroot()
        translations = sorted(resource_root.glob("values-*/strings.xml"))
        self.assertEqual(translations, [],
                         "S01 is English-only; unexpected localized string resources")
        src = (ROOT / "app/src/main/java/io/github/mikeyphw/mmrl/lsposed/MainActivity.kt").read_text()
        symbols = set(re.findall(r"R\.string\.([A-Za-z][A-Za-z0-9_]*)", src))
        defined = {e.get("name") for e in en}
        self.assertFalse(symbols - defined, symbols - defined)

    def test_android_resource_ascii_apostrophes_are_escaped(self):
        # XML parsers permit raw ASCII apostrophes, but Android AAPT does not
        # accept them inside ordinary <string> resource bodies.
        resource = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
        for item in resource.findall("string"):
            value = "".join(item.itertext())
            if value.startswith('"') and value.endswith('"'):
                continue
            with self.subTest(resource=item.get("name")):
                self.assertFalse(self._has_raw_apostrophe(value),
                                 f"Unescaped apostrophe in {item.get('name')}")

    @staticmethod
    def _has_raw_apostrophe(value: str) -> bool:
        for index, char in enumerate(value):
            if char != "'":
                continue
            n = 0
            at = index - 1
            while at >= 0 and value[at] == "\\":
                n += 1
                at -= 1
            if n % 2 == 0:
                return True
        return False

    def test_apostrophe_detector_examples(self):
        self.assertTrue(self._has_raw_apostrophe("Vector's"))
        self.assertFalse(self._has_raw_apostrophe("Vector\\'s"))
        self.assertTrue(self._has_raw_apostrophe("Vector\\\\'s"))

    def test_drawable_references_exist(self):
        src = (ROOT / "app/src/main/java/io/github/mikeyphw/mmrl/lsposed/MainActivity.kt").read_text()
        for symbol in set(re.findall(r"R\.drawable\.([A-Za-z][A-Za-z0-9_]*)", src)):
            self.assertTrue((ROOT / "app/src/main/res/drawable" / (symbol + ".xml")).is_file())
        for f in (ROOT / "app/src/main/res/drawable").glob("*.xml"):
            ET.parse(f)

    def test_android_code_does_not_claim_hooks(self):
        src = (ROOT / "app/src/main/java/io/github/mikeyphw/mmrl/lsposed/MainActivity.kt").read_text()
        self.assertIn("R.string.installed_not_enabled", src)
        self.assertIn("R.string.config_not_verified", src)
        self.assertIn("enabled = false", src)


if __name__ == "__main__":
    unittest.main()
