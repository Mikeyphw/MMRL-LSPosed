"""R5A app UX source guards: no root, mutation or release-policy changes."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / 'app/src/main/java/io/github/mikeyphw/mmrl/lsposed/MainActivity.kt'
MODEL = ROOT / 'app/src/main/java/io/github/mikeyphw/mmrl/lsposed/ModuleListModel.kt'
EMULATOR = ROOT / 'app/src/androidTest/java/io/github/mikeyphw/mmrl/lsposed/S09EmulatorUiTest.kt'
JVM = ROOT / 'app/src/test/java/io/github/mikeyphw/mmrl/lsposed/R5AModulesPresentationTest.kt'

class R5APresentationSafety(unittest.TestCase):
    def test_modules_screen_has_recovery_and_stable_semantics(self):
        text = MAIN.read_text()
        modules = text.split('private fun ModuleListPage(', 1)[1].split('private fun ModuleDetailPage(', 1)[0]
        for marker in ('modules_clear_search', 'modules_reset_filters', 'modules_filter_enabled',
                       'modules_filter_disabled', 'modules_filter_unindexed', 'modules_empty_reset',
                       'module_row_${module.packageName}', 'module_status_summary',
                       'modules_result_count', 'modules_check_updates'):
            self.assertIn(marker, modules)
        self.assertIn('newerCount > 0', modules)
        self.assertIn('!summary.savedAvailable', modules)
        for forbidden in ('VectorMutation.', 'VectorReadOnly.inspect(',
                          'VectorModuleCatalog.load(', 'GitHubEcosystem.', 'VerifiedDownload.'):
            self.assertNotIn(forbidden, modules)

    def test_detail_preserves_confirmation_and_fresh_read(self):
        text = MAIN.read_text().split('private fun ModuleDetailPage(', 1)[1].split('private fun ReconcilePanel(', 1)[0]
        for clause in ('module_detail_saved_state', 'module_detail_technical_toggle',
                       'if (detailsExpanded)', 'VectorReadOnly.inspect(context.applicationContext)',
                       'VectorReadOnly.scopes(context.applicationContext, candidate.packageName)',
                       'if (now?.enabled != stored.enabled)', 'VectorMutation.change(',
                       'Confirm one change', 'onWriteBusy(true)', 'onWriteBusy(false)'):
            self.assertIn(clause, text)
        self.assertNotIn('onClick = { VectorMutation.change(', text)

    def test_summary_does_not_infer_live_hook_status_or_other_vector_entries(self):
        m = MODEL.read_text()
        t = JVM.read_text()
        self.assertIn('enabledByPackage == null', m)
        self.assertIn('candidates.count { enabledByPackage[it.packageName] == true }', m)
        self.assertIn('candidates.size - enabled - disabled', m)
        self.assertIn('summaryCountsOnlyInstalledAndNeverCountsOtherVectorEntries', t)
        self.assertIn('unknownVectorStateIsNotMisrepresentedAsDisabled', t)

    def test_emulator_has_four_nonroot_tests_and_r5a_semantics(self):
        src = EMULATOR.read_text()
        self.assertEqual(4, src.count('@Test fun '))
        for marker in ('modules_filter_all', 'modules_filter_enabled', 'modules_clear_search'):
            self.assertIn(marker, src)
        for forbidden in ('VectorMutation.change(', 'VectorReadOnly.inspect(',
                          'RootService.bind(', 'adb shell'):
            self.assertNotIn(forbidden, src)

if __name__ == '__main__':
    unittest.main()
