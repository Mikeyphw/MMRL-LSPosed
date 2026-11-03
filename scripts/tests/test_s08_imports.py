"""Source regression for S08 Compose dialog/coroutine imports.

This intentionally does not claim to replace hosted Kotlin compilation. It catches
missing Compose/coroutine imports behind GitHub Actions runs 41725607746
and 41727548637.
"""
from pathlib import Path
import re
import unittest

SRC = (Path(__file__).resolve().parents[2] / 'app/src/main/java/io/github/mikeyphw/mmrl/lsposed')


class S08ComposeImports(unittest.TestCase):
    def test_compose_symbols_imported_at_usage_sites(self):
        required = {
            'MainActivity.kt': {
                'AlertDialog': 'androidx.compose.material3.AlertDialog',
                'TextButton': 'androidx.compose.material3.TextButton',
                'rememberCoroutineScope': 'androidx.compose.runtime.rememberCoroutineScope',
            },
            'ScopeDraftPage.kt': {
                'AlertDialog': 'androidx.compose.material3.AlertDialog',
                'TextButton': 'androidx.compose.material3.TextButton',
                'rememberCoroutineScope': 'androidx.compose.runtime.rememberCoroutineScope',
            },
        }
        for filename, symbols in required.items():
            text = (SRC / filename).read_text(encoding='utf-8')
            imports = set(re.findall(r'^import\s+([A-Za-z0-9_.]+)\s*$', text, re.M))
            for symbol, qualified in symbols.items():
                with self.subTest(file=filename, symbol=symbol):
                    self.assertRegex(text, r'\b' + re.escape(symbol) + r'\s*\(')
                    self.assertIn(qualified, imports, f'{filename}: missing import {qualified}')

    def test_coroutine_launch_extension_imported(self):
        for filename in ('MainActivity.kt', 'ScopeDraftPage.kt'):
            source = (SRC / filename).read_text(encoding='utf-8')
            with self.subTest(file=filename):
                self.assertRegex(source, r'\.launch\s*\{')
                self.assertRegex(source, r'(?m)^import kotlinx\.coroutines\.launch\s*$')


if __name__ == '__main__':
    unittest.main()
