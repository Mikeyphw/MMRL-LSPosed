"""Fail-closed S08 source contracts; hosted JVM tests cover pure write-policy behavior."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'app/src/main/java/io/github/mikeyphw/mmrl/lsposed'


class S08Safety(unittest.TestCase):
    def read(self, file):
        return (SRC / file).read_text()

    def test_root_service_has_fixed_single_target_operations(self):
        root = self.read('VectorCliRootService.kt')
        for name in ('modules enable', 'modules disable', 'scope add', 'scope rm'):
            self.assertEqual(root.count(f'"{name}" ->'), 1)
        for prohibited in ('"scope set" ->', '"db reset" ->', '"config set" ->',
                           '"log clear" ->', 'listOf("sh"', '"su -c"'):
            self.assertNotIn(prohibited, root)
        self.assertIn('Process.myUid() != 0', root)
        self.assertIn('safeScopeTarget', root)
        self.assertIn('target.endsWith("/0")', root)

    def test_mutations_are_single_and_journaled_before_cli(self):
        source = self.read('VectorMutation.kt')
        self.assertIn('VectorWriteJournal.pending(app)', source)
        self.assertIn('val entry = VectorWriteJournal.begin(', source)
        self.assertIn('val ack = VectorRootTransport.mutate(', source)
        self.assertLess(source.index('VectorWriteJournal.begin('),
                        source.index('VectorRootTransport.mutate('))
        self.assertLess(source.index('VectorWritePolicy.verifyReadBack('),
                        source.index('VectorWriteJournal.verified('))
        self.assertIn('module.enabled != request.expectedEnabled || oldScopes != request.expectedScopes', source)
        self.assertIn('VectorWritePolicy.verifyScopeAcknowledgement', source)
        self.assertIn('VectorWritePolicy.verifyModuleAcknowledgement', source)
        self.assertNotIn('VectorRootTransport.mutate(', self.read('MainActivity.kt'))
        self.assertNotIn('VectorRootTransport.mutate(', self.read('ScopeDraftPage.kt'))

    def test_scope_restrictions_and_side_effect(self):
        source = self.read('VectorMutation.kt')
        for marker in ('Action.ADD_SCOPE && !module.enabled', 'ApplicationInfo.FLAG_SYSTEM',
                       'ApplicationInfo.FLAG_UPDATED_SYSTEM_APP',
                       'target == context.packageName', 'module.staticScope',
                       'target == module.packageName', 'expectedScopes', '"${request.target}/0"'):
            self.assertIn(marker, source)
        self.assertIn('Request(', source)
        self.assertNotIn('scope set', source)

    def test_uncertain_result_blocks_until_two_explicit_reads(self):
        journal = self.read('VectorWriteJournal.kt')
        mutation = self.read('VectorMutation.kt')
        home = self.read('MainActivity.kt')
        for marker in ('"PENDING"', '.commit()', 'fun pending(', 'fun begin(',
                       'fun reconciled(', '"RECONCILED_BY_USER"'):
            self.assertIn(marker, journal)
        for marker in ('fun reviewUncertain(', 'fun acknowledgeReview(',
                       'VectorReadOnly.inspect(context)', 'VectorReadOnly.scopes(context,',
                       'latest.modules != review.snapshot.modules || scopes != review.scopes'):
            self.assertIn(marker, mutation)
        self.assertIn('Acknowledge observed state', home)
        self.assertIn('Inspect saved state', home)
        self.assertNotIn('revert', mutation.lower())
        self.assertNotIn('fun rollback(', mutation)

    def test_scope_checkbox_executes_single_verified_change_without_draft(self):
        page = self.read('ScopeDraftPage.kt')
        self.assertIn('VectorMutation.change(', page)
        self.assertIn('VectorMutation.Action.ADD_SCOPE', page)
        self.assertIn('VectorMutation.Action.REMOVE_SCOPE', page)
        self.assertIn('VectorMutation.Action.ENABLE', page)
        self.assertIn('enabled == false', page)
        self.assertIn('onWriteBusy(true)', page)
        self.assertIn('onWriteBusy(false)', page)
        self.assertIn('onCommitted(it)', page)
        self.assertIn('scope_enable_module', page)
        self.assertNotIn('Review draft', page)
        self.assertNotIn('Confirm single change', page)
        self.assertNotIn('scope set', page)
        self.assertNotIn('VectorRootTransport.mutate(', page)

    def test_hosted_ci_signing_and_source_pins_remain_unchanged(self):
        flow = (ROOT / '.github/workflows/s01-hosted-devtool.yml').read_text()
        self.assertIn('scripts/hosted_devtool.py run --node test', flow)
        self.assertIn('scripts/hosted_debug_signing.py', flow)
        self.assertIn('actions/attest@', flow)
        self.assertIn('b164463db51e2cffd12f753043b9e55a08cc43ae', flow)
        tests = ROOT / 'app/src/test/java/io/github/mikeyphw/mmrl/lsposed/VectorWritePolicyTest.kt'
        self.assertIn('assertThrows', tests.read_text())
        self.assertIn('verifyReadBack', tests.read_text())


if __name__ == '__main__':
    unittest.main()
