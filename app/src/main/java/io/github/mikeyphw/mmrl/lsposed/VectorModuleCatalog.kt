package io.github.mikeyphw.mmrl.lsposed

import android.content.Context
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * LSPosed module catalog used by Vector itself, not arbitrary GitHub topic search.
 * Vector 2.2's RepoRepository uses backup.modules.lsposed.org/modules.json for
 * the full list (modules.lsposed.org returns HTTP 403 for this path).
 * Cache contains public module metadata only, never GitHub credentials.
 */
internal object VectorModuleCatalog {
    private const val LIST_URL = "https://backup.modules.lsposed.org/modules.json"
    private const val MAX_BYTES = 6 * 1024 * 1024
    private val PACKAGE = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")

    data class Module(
        val packageName: String,
        val title: String,
        val summary: String,
        val stars: Int,
        val updated: String,
        val version: String,
        val pageUrl: String,
    )
    data class Result(val modules: List<Module>, val cached: Boolean, val warning: String = "")

    enum class Sort { UPDATED_NEWEST, UPDATED_OLDEST, MOST_STARS, FEWEST_STARS, NAME_AZ, NAME_ZA }

    /** Catalog metadata is mostly UTC ISO-8601; handle offsets and partial legacy dates.
     * Missing/malformed timestamps always sort last in either direction. */
    private fun releaseMillis(value: String): Long? {
        val raw = value.trim()
        if (raw.isEmpty()) return null
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { YearMonth.parse(raw).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }

    /** Pure, deterministic ordering. Works with cached catalog data and makes no network calls. */
    fun sort(modules: List<Module>, by: Sort): List<Module> {
        val name = compareBy<Module> { it.title.lowercase(java.util.Locale.ROOT) }
            .thenBy { it.packageName }
        // Parse timestamps only once per row; sorting 1,000+ modules stays cheap.
        val releaseTimes = if (by == Sort.UPDATED_NEWEST || by == Sort.UPDATED_OLDEST)
            modules.associateWith { releaseMillis(it.updated) } else emptyMap()
        val ordered = when (by) {
            Sort.UPDATED_NEWEST -> compareBy<Module> { releaseTimes[it] == null }
                .thenByDescending { releaseTimes[it] ?: Long.MIN_VALUE }.then(name)
            Sort.UPDATED_OLDEST -> compareBy<Module> { releaseTimes[it] == null }
                .thenBy { releaseTimes[it] ?: Long.MAX_VALUE }.then(name)
            Sort.MOST_STARS -> compareByDescending<Module> { it.stars }.then(name)
            Sort.FEWEST_STARS -> compareBy<Module> { it.stars }.then(name)
            Sort.NAME_AZ -> name
            Sort.NAME_ZA -> compareByDescending<Module> { it.title.lowercase(java.util.Locale.ROOT) }
                .thenBy { it.packageName }
        }
        return modules.sortedWith(ordered)
    }

    fun parse(data: String): List<Module> {
        val rows = JSONArray(data)
        if (rows.length() > 2500) throw IOException("Catalog exceeds expected module count")
        val seen = HashSet<String>()
        val modules = ArrayList<Module>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val name = row.optString("name")
            if (!PACKAGE.matches(name) || row.optBoolean("hide")) continue
            val releases = row.optJSONArray("releases")
            if (releases == null || releases.length() == 0 || !seen.add(name)) continue
            val version = row.optString("latestRelease").take(65)
            modules.add(Module(
                packageName = name,
                title = row.optString("description").takeIf { it.isNotBlank() }?.take(110) ?: name,
                summary = row.optString("summary").take(240),
                stars = row.optInt("stargazerCount", 0).coerceAtLeast(0),
                updated = row.optString("latestReleaseTime").take(40),
                version = version,
                pageUrl = "https://modules.lsposed.org/module/$name",
            ))
        }
        if (modules.isEmpty()) throw IOException("The module catalog contains no usable entries")
        return sort(modules, Sort.UPDATED_NEWEST)
    }

    /** Serve the last valid catalog immediately, before attempting DNS/network. */
    fun cached(context: Context): Result? = runCatching {
        val cache = File(context.cacheDir, "vector_module_catalog_v1.json")
        if (!cache.isFile || cache.length() > MAX_BYTES) return@runCatching null
        Result(parse(cache.readText()), cached = true,
            warning = "Showing saved LSPosed catalog · refresh when online")
    }.getOrNull()

    fun load(context: Context): Result {
        val cache = File(context.cacheDir, "vector_module_catalog_v1.json")
        val fresh = runCatching { fetch() }
        val text = fresh.getOrNull()
        if (text != null) {
            val parsed = runCatching { parse(text) }.getOrNull()
            if (parsed != null) {
                runCatching {
                    val tmp = File(cache.parentFile, cache.name + ".tmp")
                    tmp.writeText(text)
                    check(tmp.renameTo(cache))
                }
                return Result(parsed, cached = false)
            }
        }
        val previous = cached(context)
        if (previous != null) return previous.copy(
            warning = "Offline · showing the last saved LSPosed catalog")
        throw IOException("LSPosed catalog unavailable. Check your connection or Private DNS and retry.")
    }

    fun filter(modules: List<Module>, query: String): List<Module> {
        val terms = query.trim().take(80).lowercase().split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        return if (terms.isEmpty()) modules else modules.filter { module ->
            val haystack = "${module.title} ${module.packageName} ${module.summary}".lowercase()
            terms.all { it in haystack }
        }
    }

    private fun fetch(): String {
        val url = URL(LIST_URL)
        require(url.protocol == "https" && url.host == "backup.modules.lsposed.org")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MMRL-LSPosed/0.1")
        }
        try {
            if (connection.responseCode != 200)
                throw IOException("LSPosed catalog returned HTTP ${connection.responseCode}")
            val buffer = ByteArray(8192)
            val content = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    if (content.size() + n > MAX_BYTES) throw IOException("LSPosed catalog too large")
                    content.write(buffer, 0, n)
                }
            }
            return content.toString("UTF-8")
        } finally { connection.disconnect() }
    }
}
