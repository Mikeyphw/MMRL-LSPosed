package io.github.mikeyphw.mmrl.lsposed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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
    private const val MAX_ETAG_BYTES = 160
    private const val CACHE_FILE = "vector_module_catalog_v1.json"
    private const val META_FILE = "vector_module_catalog_v1.meta.json"
    private const val CACHE_SCHEMA = "mmrl-lsposed.vector-catalog-etag.v1"
    // RFC 9110 opaque-tag subset: no embedded quotes, controls, spaces or CRLF.
    private val SAFE_ETAG = Regex("(?:W/)?\"[!#-~]*\"")
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

    /** Metadata contains only a public server validator, bound to the exact cache bytes.
     *  It never contains tokens or privileged-device data. The SHA is a consistency
     *  check, NOT a signature or evidence that a publisher is trustworthy. */
    internal fun cacheReceipt(body: String, etag: String?): String? {
        if (etag == null || etag.length !in 2..MAX_ETAG_BYTES || !SAFE_ETAG.matches(etag))
            return null
        return JSONObject()
            .put("schema", CACHE_SCHEMA)
            .put("etag", etag)
            .put("cache_sha256", sha256(body))
            .toString()
    }

    internal fun validatedCacheEtag(receipt: String?, body: String?): String? {
        if (receipt == null || body == null || receipt.length > 1024 ||
            body.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        return runCatching {
            val data = JSONObject(receipt)
            val etag = data.getString("etag")
            if (data.optString("schema") != CACHE_SCHEMA ||
                !data.optString("cache_sha256").equals(sha256(body), ignoreCase = false) ||
                etag.length !in 2..MAX_ETAG_BYTES || !SAFE_ETAG.matches(etag)) null
            else etag
        }.getOrNull()
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }

    private fun cacheText(cache: File): String? = runCatching {
        if (!cache.isFile || cache.length() !in 1L..MAX_BYTES.toLong()) null
        else cache.readText(Charsets.UTF_8)
    }.getOrNull()

    private data class Reply(val body: String?, val etag: String?, val notModified: Boolean)

    /** Serve the last valid catalog immediately, before attempting DNS/network. */
    fun cached(context: Context): Result? = runCatching {
        val cache = File(context.cacheDir, CACHE_FILE)
        val saved = cacheText(cache) ?: return@runCatching null
        Result(parse(saved), cached = true,
            warning = "Showing saved LSPosed catalog · refresh when online")
    }.getOrNull()

    /** Refresh is only invoked by the explicit Discover action. 304 is usable only
     *  with a previously parsed body and an exact cache/ETag receipt match. */
    fun load(context: Context): Result {
        val cache = File(context.cacheDir, CACHE_FILE)
        val metadata = File(context.cacheDir, META_FILE)
        val savedText = cacheText(cache)
        val savedModules = runCatching { savedText?.let(::parse) }.getOrNull()
        val receipt = runCatching {
            if (metadata.isFile && metadata.length() in 1L..1024L)
                metadata.readText(Charsets.UTF_8) else null
        }.getOrNull()
        val validator = if (savedModules == null) null
            else validatedCacheEtag(receipt, savedText)

        val response = runCatching { fetch(validator) }.getOrNull()
        if (response?.notModified == true && savedModules != null && validator != null)
            return Result(savedModules, cached = false) // Online revalidated: no new body.
        if (response?.body != null) {
            val parsed = runCatching { parse(response.body) }.getOrNull()
            if (parsed != null) {
                // Cache persistence is best-effort; verified network data is still usable.
                runCatching { saveCache(cache, metadata, response.body, response.etag) }
                return Result(parsed, cached = false)
            }
        }
        if (savedModules != null) return Result(savedModules, cached = true,
            warning = "Could not refresh · showing the last saved LSPosed catalog")
        throw IOException("LSPosed catalog unavailable. Check your connection or Private DNS and retry.")
    }

    private fun saveCache(cache: File, metadata: File, body: String, etag: String?) {
        val temp = File(cache.parentFile, cache.name + ".tmp")
        val metaTemp = File(metadata.parentFile, metadata.name + ".tmp")
        try {
            temp.writeText(body, Charsets.UTF_8)
            if (!temp.renameTo(cache)) throw IOException("Could not replace catalog cache")
            // Sidecar is written AFTER successful cache replacement. Any stale
            // sidecar is rejected automatically by validatedCacheEtag.
            val receipt = cacheReceipt(body, etag)
            if (receipt == null) metadata.delete()
            else {
                metaTemp.writeText(receipt, Charsets.UTF_8)
                if (!metaTemp.renameTo(metadata)) throw IOException("Could not save ETag receipt")
            }
        } finally {
            temp.delete()
            metaTemp.delete()
        }
    }

    fun filter(modules: List<Module>, query: String): List<Module> {
        val terms = query.trim().take(80).lowercase().split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        return if (terms.isEmpty()) modules else modules.filter { module ->
            val haystack = "${module.title} ${module.packageName} ${module.summary}".lowercase()
            terms.all { it in haystack }
        }
    }

    private fun fetch(etag: String?): Reply {
        val url = URL(LIST_URL)
        require(url.protocol == "https" && url.host == "backup.modules.lsposed.org")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MMRL-LSPosed/0.1")
            if (etag != null) setRequestProperty("If-None-Match", etag)
        }
        try {
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED)
                return Reply(null, null, notModified = true)
            if (status != HttpURLConnection.HTTP_OK)
                throw IOException("LSPosed catalog returned HTTP $status")
            if (connection.contentLengthLong > MAX_BYTES)
                throw IOException("LSPosed catalog too large")
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
            return Reply(content.toString("UTF-8"), connection.getHeaderField("ETag"),
                notModified = false)
        } finally { connection.disconnect() }
    }
}
