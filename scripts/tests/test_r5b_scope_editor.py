"""R5B scope UX contracts; root/write protocol must remain untouched."""
from pathlib import Path
import unittest

R = Path(__file__).resolve().parents[2]
SRC = R/'app/src/main/java/io/github/mikeyphw/mmrl/lsposed'

class R5BScopeContracts(unittest.TestCase):
    def test_scope_search_chips_and_stale_read_retry(self):
        page = (SRC/'ScopeDraftPage.kt').read_text()
        for marker in ('scope_summary','scope_clear_search','scope_filter_all',
                'scope_filter_selected','scope_filter_suggested','scope_reset_filters',
                'scope_retry_read','scope_row_$key', 'ScopeEditorPresentation.matches('):
            self.assertIn(marker,page)
        activity=(SRC/'MainActivity.kt').read_text()
        self.assertIn('onRetryRead = { if (!session.writeBusy) scopeRequest++ }',activity)

    def test_unchanged_journaled_immediate_write_boundary(self):
        page=(SRC/'ScopeDraftPage.kt').read_text()
        for marker in ('onCheckedChange = { toggle() }', 'VectorMutation.Request(',
                'VectorMutation.change(', 'onCommitted(it)', 'onWriteBusy(true)',
                'VectorMutation.Action.ADD_SCOPE','VectorMutation.Action.REMOVE_SCOPE',
                'scope_enable_module', 'target.userId == 0', 'target.system',
                'target.savedOnly', '!manager', 'permittedByStatic'):
            self.assertIn(marker,page)
        for disallowed in ('showReview','writeScope','RootService','VectorRootTransport'):
            self.assertNotIn(disallowed,page)
        self.assertIn('No permission decisions or writes.',
            (SRC/'ScopeEditorPresentation.kt').read_text())

    def test_new_model_offline_jvm_tests(self):
        tests=(R/'app/src/test/java/io/github/mikeyphw/mmrl/lsposed/R5BScopePresentationTest.kt').read_text()
        self.assertEqual(tests.count('@Test fun '),3)
        self.assertIn('ScopeEditorPresentation.matches(',tests)

if __name__ == '__main__': unittest.main()
