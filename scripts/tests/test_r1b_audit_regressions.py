"""R1B R2 guardrails: UI-only changes, real catalog sorting, safe package/profile mapping."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'app/src/main/java/io/github/mikeyphw/mmrl/lsposed'

class R1BUIAndSortAudit(unittest.TestCase):
    def test_visible_sort_and_module_interactions(self):
        main = (SRC / 'MainActivity.kt').read_text()
        repo = (SRC / 'EcosystemPages.kt').read_text()
        for marker in ('modules_refresh', 'modules_repository', 'module_filter_toggle',
                       'module_search', 'modules_sort', 'modules_check_updates',
                       'module_status_summary', 'InstalledAppIcon',
                       'module.versionName', 'module.description'):
            self.assertIn(marker, main)
        self.assertIn('widthIn(max = 112.dp)', main)
        self.assertIn('discover_sort', repo)
        self.assertIn('Sort: ', repo)

    def test_catalog_sort_stays_local_and_timestamp_is_validated(self):
        txt = (SRC / 'VectorModuleCatalog.kt').read_text()
        self.assertIn('modules.associateWith { releaseMillis(it.updated) }', txt)
        self.assertIn('row.optBoolean("hide")', txt)
        self.assertIn('!seen.add(name)', txt)
        self.assertIn('return modules.sortedWith(ordered)', txt)
        self.assertIn('Instant.parse(raw)', txt)
        self.assertIn('releaseTimes[it] == null', txt)
        self.assertNotIn('return load(context)', txt)

    def test_english_only_resource_contract_does_not_require_stale_pt_br(self):
        # The handoff reference archive retained an obsolete translation; verified GitHub
        # S11 main never contained values-pt-rBR/strings.xml. Avoid a false test failure.
        s01 = (ROOT / 'scripts/tests/test_s01_source.py').read_text()
        s04 = (ROOT / 'scripts/tests/test_s04_s07_readonly.py').read_text()
        self.assertIn('self.assertEqual(translations, []', s01)
        self.assertIn("self.assertFalse(list((ROOT / 'app/src/main/res').glob('values-*/strings.xml')))", s04)
        self.assertFalse(list((ROOT / 'app/src/main/res').glob('values-*/strings.xml')))

    def test_privileged_write_sources_not_replaced_by_presentation_changes(self):
        for name in ('VectorMutation.kt', 'VectorWriteJournal.kt', 'VectorWritePolicy.kt',
                     'VectorRootTransport.kt'):
            self.assertTrue((SRC / name).exists())
        idx = (SRC / 'AppsReverseIndex.kt').read_text()
        self.assertIn('scope.packageName to scope.userId', idx)
        self.assertNotIn('VectorMutation.', idx)

if __name__ == '__main__':
    unittest.main()
