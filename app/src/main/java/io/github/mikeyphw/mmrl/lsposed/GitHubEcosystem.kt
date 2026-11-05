package io.github.mikeyphw.mmrl.lsposed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Public, user-requested GitHub metadata only. No credentials, root or auto-install. */
internal object GitHubEcosystem {
    private const val REPOSITORY = "Mikeyphw/MMRL-LSPosed"
    private const val API = "https://api.github.com"
    private const val MAX_RESPONSE = 1_048_576
    private const val MAX_RELEASE_FILE = 104_857_600L
    private val repoName = Regex("[A-Za-z0-9_.-]{1,100}")
    private val digestFormat = Regex("sha256:[a-f0-9]{64}")

    data class Repository(val fullName: String, val description: String, val stars: Int)
    data class Asset(val name: String, val size: Long, val digest: String?,
                     val url: String, val releaseTag: String = "") {
        /** Repository/tag/filename/URL binding plus digest; NOT an APK signing claim. */
        val verifiedDownloadable: Boolean get() = size in 1..MAX_RELEASE_FILE &&
            digest != null && digestFormat.matches(digest) &&
            ReleaseIdentityPolicy.asset(releaseTag, name, url) &&
            VerifiedDownload.allowedSourceUrl(url) && VerifiedDownload.allowedName(name)
    }
    data class Release(val name: String, val tag: String, val htmlUrl: String, val assets: List<Asset>)
    data class Run(val id: Long, val status: String, val headSha: String,
                   val attempt: Int, val htmlUrl: String, val event: String = "",
                   val headBranch: String = "") {
        val commit: String get() = headSha.take(12)
    }
    data class Artifact(val id: Long, val name: String, val bytes: Long,
                        val expired: Boolean, val digest: String?, val url: String)

    fun repositoryUrl(fullName: String): String? {
        val parts = fullName.split('/')
        if (parts.size != 2 || parts.any { !repoName.matches(it) || it == "." || it == ".." }) return null
        return "https://github.com/${parts[0]}/${parts[1]}"
    }

    fun discovery(query: String, context: Context): List<Repository> {
        val safe = query.trim().take(60).filter { it.isLetterOrDigit() || it in " ._-" }
        val githubQuery = "topic:xposed-module" + if (safe.isBlank()) "" else " $safe"
        val url = "$API/search/repositories?q=" + URLEncoder.encode(githubQuery, "UTF-8") +
            "&sort=updated&order=desc&per_page=20"
        val items = requestObject(url, context).optJSONArray("items") ?: JSONArray()
        return (0 until minOf(items.length(), 20)).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val name = item.optString("full_name")
            if (repositoryUrl(name) == null) return@mapNotNull null
            Repository(name, item.optString("description").take(220), item.optInt("stargazers_count"))
        }
    }

    fun releases(context: Context): List<Release> {
        val items = requestArray("$API/repos/$REPOSITORY/releases?per_page=15", context)
        return (0 until minOf(items.length(), 15)).mapNotNull { i ->
            val release = items.optJSONObject(i) ?: return@mapNotNull null
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) return@mapNotNull null
            val link = release.optString("html_url")
            val tag = release.optString("tag_name").take(80)
            if (!ReleaseIdentityPolicy.releasePage(tag, link)) return@mapNotNull null
            val assets = release.optJSONArray("assets") ?: JSONArray()
            Release(release.optString("name").take(100), tag,
                link, (0 until minOf(assets.length(), 30)).mapNotNull { j ->
                    val a = assets.optJSONObject(j) ?: return@mapNotNull null
                    val file = a.optString("name").take(150)
                    val size = a.optLong("size", -1)
                    val rawDigest = a.optString("digest").takeIf { digestFormat.matches(it) }
                    val url = a.optString("browser_download_url")
                    if (file.isBlank() || size !in 1..MAX_RELEASE_FILE ||
                        !VerifiedDownload.allowedSourceUrl(url)) return@mapNotNull null
                    Asset(file, size, rawDigest, url, tag)
                })
        }
    }

    fun recentRuns(context: Context): List<Run> {
        val items = requestObject("$API/repos/$REPOSITORY/actions/runs?per_page=15", context)
            .optJSONArray("workflow_runs") ?: JSONArray()
        return (0 until minOf(items.length(), 15)).mapNotNull { i ->
            val obj = items.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optLong("id", 0)
            val url = "https://github.com/$REPOSITORY/actions/runs/$id"
            if (id <= 0 || obj.optString("html_url") != url) return@mapNotNull null
            val source = obj.optString("head_sha")
            val attempt = obj.optInt("run_attempt", 0)
            if (!Regex("[a-f0-9]{40}").matches(source) || attempt < 1) return@mapNotNull null
            val conclusion = obj.optString("conclusion")
                .takeUnless { it.isBlank() || it == "null" }
            val event = obj.optString("event")
                .takeIf { it == "push" || it == "pull_request" } ?: ""
            val headBranch = obj.optString("head_branch")
                .takeIf { it == "main" } ?: ""
            Run(id, (conclusion ?: obj.optString("status")).take(30),
                source, attempt, url, event, headBranch)
        }
    }

    /** Re-read the exact run immediately before retrieving an artifact ZIP.
     * A rerun can advance run_attempt without changing the run ID or source SHA. */
    fun freshRun(run: Run, context: Context): Boolean {
        if (run.id <= 0 || run.attempt <= 0 || !Regex("[a-f0-9]{40}").matches(run.headSha))
            return false
        return runMatches(run, requestObject("$API/repos/$REPOSITORY/actions/runs/${run.id}", context))
    }

    internal fun runMatches(run: Run, obj: JSONObject): Boolean {
        return obj.optLong("id", 0L) == run.id &&
            obj.optString("html_url") == run.htmlUrl &&
            obj.optString("head_sha") == run.headSha &&
            obj.optInt("run_attempt", 0) == run.attempt &&
            obj.optString("event") == run.event &&
            obj.optString("status") == "completed" &&
            obj.optString("conclusion") == "success" &&
            (run.event == "pull_request" ||
                (run.event == "push" && run.headBranch == "main" &&
                    obj.optString("head_branch") == "main"))
    }

    /** Public CI metadata, never binary download or signer qualification. */
    fun runArtifacts(run: Run, context: Context): List<Artifact> {
        require(run.id > 0 && run.attempt > 0 && Regex("[a-f0-9]{40}").matches(run.headSha))
        val items = requestObject("$API/repos/$REPOSITORY/actions/runs/${run.id}/artifacts?per_page=50", context)
            .optJSONArray("artifacts") ?: JSONArray()
        return decodeRunArtifacts(run, items)
    }

    /** Pure validation of artifact ownership; API does not pin archives to a run attempt. */
    fun decodeRunArtifacts(run: Run, items: JSONArray): List<Artifact> {
        require(run.id > 0 && run.attempt > 0 && Regex("[a-f0-9]{40}").matches(run.headSha))
        return (0 until minOf(items.length(), 50)).mapNotNull { i ->
            val obj = items.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optLong("id", 0)
            val runRef = obj.optJSONObject("workflow_run") ?: return@mapNotNull null
            if (id <= 0 || runRef.optLong("id") != run.id ||
                runRef.optString("head_sha") != run.headSha) return@mapNotNull null
            val name = obj.optString("name").take(120)
            val size = obj.optLong("size_in_bytes", -1)
            if (name.isBlank() || size < 0) return@mapNotNull null
            val digest = obj.optString("digest").takeIf { digestFormat.matches(it) }
            Artifact(id, name, size, obj.optBoolean("expired"), digest,
                "https://github.com/$REPOSITORY/actions/runs/${run.id}/artifacts/$id")
        }
    }

    fun rateLimit(context: Context): String {
        val limits = requestObject("$API/rate_limit", context)
        val core = limits.optJSONObject("resources")?.optJSONObject("core")
        return "GitHub API available · ${core?.optInt("remaining", 0) ?: "?"} core requests remaining"
    }

    /** User-facing diagnosis that does not echo credentials or HTTP response bodies. */
    fun connectionDiagnostic(error: Throwable): String = when (error) {
        is UnknownHostException -> "DNS could not resolve api.github.com. Check Private DNS or network settings."
        is SocketTimeoutException -> "GitHub API timed out. Try again when the connection improves."
        else -> error.message?.take(180) ?: "GitHub API unavailable."
    }

    private fun requestObject(path: String, context: Context): JSONObject = JSONObject(request(path, context))
    private fun requestArray(path: String, context: Context): JSONArray = JSONArray(request(path, context))

    private fun request(path: String, context: Context): String {
        val url = URL(path)
        if (url.protocol != "https" || url.host != "api.github.com")
            throw IOException("Only the public GitHub API is allowed")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 7_000
            readTimeout = 10_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "MMRL-LSPosed/0.1")
            // Optional Keystore credential is confined to the exact github.com API host.
            val token = try { LocalSettings.gitHubToken(context.applicationContext) }
                catch (_: Exception) { throw IOException("Saved GitHub token cannot be read. Remove it in Settings.") }
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val status = connection.responseCode
            if (status != 200) throw IOException(when (status) {
                401 -> "GitHub authentication rejected (HTTP 401). Remove or replace the token in Settings."
                403, 429 -> "GitHub API rate limit or permission denied (HTTP $status)."
                else -> "GitHub API returned HTTP $status."
            })
            connection.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                val data = ByteArrayOutputStream()
                while (true) {
                    val size = stream.read(buffer)
                    if (size < 0) break
                    if (data.size() + size > MAX_RESPONSE) throw IOException("GitHub response too large")
                    data.write(buffer, 0, size)
                }
                return data.toString("UTF-8")
            }
        } finally { connection.disconnect() }
    }
}
