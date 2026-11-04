"""Regression for S09+S10 CI run 41767060096: Compose assertion imports."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
TEST=ROOT/'app/src/androidTest/java/io/github/mikeyphw/mmrl/lsposed/S09EmulatorUiTest.kt'

class ComposeAssertionImports(unittest.TestCase):
    def test_disabled_semantics_assertion_imported_as_extension(self):
        src=TEST.read_text(encoding='utf-8')
        self.assertEqual(src.count('import androidx.compose.ui.test.assertIsNotEnabled\n'),1)
        self.assertEqual(src.count('.assertIsNotEnabled()'),1)

    def test_member_assertions_not_imported_as_extensions(self):
        src=TEST.read_text(encoding='utf-8')
        for member in ('assertExists','assertDoesNotExist'):
            with self.subTest(member=member):
                self.assertNotIn(f'import androidx.compose.ui.test.{member}\n',src)
                self.assertIn(f'.{member}()',src)
        self.assertIn('import androidx.compose.ui.test.assertTextContains\n',src)

    def test_ci_stays_nonroot_and_four_tests(self):
        src=TEST.read_text(encoding='utf-8')
        self.assertEqual(src.count('@Test fun '),4)
        self.assertIn('assertNoImplicitVectorConnection()',src)
        self.assertNotIn('VectorMutation.change(',src)
        self.assertNotIn('S08LabWriteDeviceTest(',src)

if __name__=='__main__': unittest.main()
