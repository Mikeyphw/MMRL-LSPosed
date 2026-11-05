"""R2A source-contract checks: conditional ETags never bypass cache validation."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
CAT = ROOT / "app/src/main/java/io/github/mikeyphw/mmrl/lsposed/VectorModuleCatalog.kt"
UI = ROOT / "app/src/main/java/io/github/mikeyphw/mmrl/lsposed/EcosystemPages.kt"
TEST = ROOT / "app/src/test/java/io/github/mikeyphw/mmrl/lsposed/R2CatalogCacheTest.kt"

class R2CatalogCacheContracts(unittest.TestCase):
    def test_network_and_cache_validators_are_bound(self):
        source = CAT.read_text()
        for piece in (
            'private val SAFE_ETAG', '"cache_sha256"', 'MessageDigest.getInstance("SHA-256")',
            'validatedCacheEtag(receipt, savedText)', 'savedModules != null && validator != null',
            'setRequestProperty("If-None-Match", etag)',
            'HttpURLConnection.HTTP_NOT_MODIFIED', 'if (status != HttpURLConnection.HTTP_OK)',
            'instanceFollowRedirects = false', 'if (content.size() + n > MAX_BYTES)',
            'response?.body != null', 'saveCache(cache, metadata, response.body, response.etag)',
        ):
            with self.subTest(piece=piece): self.assertIn(piece,source)
        self.assertTrue(TEST.exists())
        self.assertNotIn('Authorization', source)
        self.assertNotIn('github_token', source)
        self.assertNotIn('VectorMutation', source)

    def test_refresh_remains_explicit_and_sort_has_no_network(self):
        ui = UI.read_text()
        source = CAT.read_text()
        self.assertIn('VectorModuleCatalog.load(context.applicationContext)', ui)
        self.assertIn('VectorModuleCatalog.cached(context.applicationContext)', ui)
        block = source.split('    fun sort(')[1].split('    fun parse(')[0]
        for prohibited in ('fetch(', 'HttpURLConnection', 'setRequestProperty(', 'saveCache('):
            self.assertNotIn(prohibited,block)
        fetch_calls = re.findall(r'(?<!fun )fetch\(', source)
        self.assertEqual(len(fetch_calls),1, 'No background/network polling paths')

    def test_offline_fallback_not_false_empty_catalog(self):
        source = CAT.read_text()
        self.assertIn('if (savedModules != null) return Result(savedModules, cached = true,',source)
        self.assertIn('Could not refresh · showing the last saved LSPosed catalog',source)
        self.assertIn('throw IOException("LSPosed catalog unavailable.',source)
