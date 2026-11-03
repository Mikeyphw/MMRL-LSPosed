package io.github.mikeyphw.mmrl.lsposed

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.Properties
import java.util.zip.ZipFile

/** Unprivileged APK-only inspection; does not execute APKs, root, or module hooks. */
internal object ModuleDiscovery {
    enum class Format { MODERN, LEGACY, BOTH, UNKNOWN }

    data class Candidate(
        val packageName: String,
        val name: String,
        val format: Format,
        val versionName: String = "",
        val description: String = "",
        val declaredScopes: List<String> = emptyList(),
        val staticScope: Boolean = false,
        val minApiVersion: Int = 0,
        val targetApiVersion: Int = 0,
    )

    private val packagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private val initFiles = listOf("META-INF/xposed/java_init.list", "META-INF/xposed/native_init.list")
    private const val MAX_METADATA_BYTES = 16_384
    private const val MAX_SCOPES = 256

    @Suppress("DEPRECATION")
    fun scan(context: Context): List<Candidate> {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .mapNotNull { app -> inspect(pm, app) }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.lowercase(Locale.ROOT) })
    }

    @Suppress("DEPRECATION")
    private fun inspect(pm: PackageManager, app: ApplicationInfo): Candidate? {
        val meta = app.metaData
        val legacy = hasLegacyFlag(app) || meta?.containsKey("xposedminversion") == true
        var modern = false
        var legacyEntry = false
        var modernScope = emptyList<String>()
        var staticScope = false
        var minApi = 0
        var targetApi = 0

        // Vector's own manager checks split APKs. Keep independent source paths bounded.
        val apkPaths = (listOfNotNull(app.sourceDir) + app.splitSourceDirs.orEmpty())
            .distinct().take(32)
        for (apkPath in apkPaths) {
            runCatching {
                ZipFile(apkPath).use { zip ->
                    if (zip.getEntry("assets/xposed_init") != null) legacyEntry = true
                    if (initFiles.any { zip.getEntry(it) != null }) {
                        modern = true
                        if (modernScope.isEmpty()) {
                            modernScope = readZipText(zip, "META-INF/xposed/scope.list")
                                ?.lineSequence()?.map { it.trim() }
                                ?.filter { it.isNotBlank() }?.take(MAX_SCOPES + 1)?.toList()
                                ?.takeIf { it.size <= MAX_SCOPES }?.let(::normalizeScopes).orEmpty()
                        }
                        readZipBytes(zip, "META-INF/xposed/module.prop")?.let { bytes ->
                            runCatching {
                                val props = Properties().apply { load(ByteArrayInputStream(bytes)) }
                                minApi = props.getProperty("minApiVersion").toApi()
                                targetApi = props.getProperty("targetApiVersion").toApi()
                                staticScope = props.getProperty("staticScope") == "true"
                            }
                        }
                    }
                }
            }
        }
        if (!modern && !legacy && !legacyEntry) return null
        val legacySupported = legacy || legacyEntry
        val declared = if (modern) modernScope else legacyScopes(pm, app)
        if (declared.isEmpty()) staticScope = false
        val label = runCatching { pm.getApplicationLabel(app).toString().trim() }
            .getOrDefault(app.packageName).ifBlank { app.packageName }
        val description = if (modern) runCatching { app.loadDescription(pm)?.toString()?.trim().orEmpty() }
            .getOrDefault("") else legacyDescription(pm, app)
        return Candidate(
            packageName = app.packageName,
            name = label,
            format = when {
                modern && legacySupported -> Format.BOTH
                modern -> Format.MODERN
                else -> Format.LEGACY
            },
            versionName = runCatching { pm.getPackageInfo(app.packageName, 0).versionName.orEmpty() }
                .getOrDefault(""),
            description = description.take(1024),
            declaredScopes = declared,
            staticScope = staticScope,
            minApiVersion = if (modern) minApi else legacyApi(app),
            targetApiVersion = targetApi,
        )
    }

    @Suppress("DEPRECATION")
    private fun hasLegacyFlag(info: ApplicationInfo): Boolean = when (val flag = info.metaData?.get("xposedmodule")) {
        is Boolean -> flag
        is String -> flag.equals("true", ignoreCase = true)
        else -> false
    }

    @Suppress("DEPRECATION")
    private fun legacyApi(info: ApplicationInfo): Int = when (val raw = info.metaData?.get("xposedminversion")) {
        is Number -> raw.toInt().coerceAtLeast(0)
        is String -> raw.toApi()
        else -> 0
    }

    @Suppress("DEPRECATION")
    private fun legacyDescription(pm: PackageManager, info: ApplicationInfo): String {
        val value = info.metaData?.get("xposeddescription")
        return when (value) {
            is String -> value.trim()
            is Int -> runCatching { pm.getResourcesForApplication(info).getString(value).trim() }.getOrDefault("")
            else -> ""
        }.take(1024)
    }

    @Suppress("DEPRECATION")
    private fun legacyScopes(pm: PackageManager, info: ApplicationInfo): List<String> {
        val value = info.metaData?.get("xposedscope")
        val scopes = when (value) {
            is String -> value.split(';')
            is Int -> runCatching { pm.getResourcesForApplication(info).getStringArray(value).toList() }
                .getOrDefault(emptyList())
            else -> emptyList()
        }
        val swapped = scopes.take(MAX_SCOPES + 1).map { scope -> when (scope.trim()) {
            "android" -> "system"
            "system" -> "android"
            else -> scope
        } }
        return if (swapped.size > MAX_SCOPES) emptyList() else normalizeScopes(swapped)
    }

    private fun normalizeScopes(value: List<String>): List<String> = value.map { it.trim() }
        .filter { s -> s == "android" || s == "system" ||
            (s.length <= 180 && packagePattern.matches(s)) }
        .distinct()

    private fun String?.toApi(): Int = this?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0

    private fun readZipText(zip: ZipFile, entry: String): String? =
        readZipBytes(zip, entry)?.toString(Charsets.UTF_8)

    private fun readZipBytes(zip: ZipFile, entry: String): ByteArray? {
        val file = zip.getEntry(entry) ?: return null
        if (file.isDirectory || file.size > MAX_METADATA_BYTES) return null
        return zip.getInputStream(file).use { input -> readBounded(input) }
    }

    private fun readBounded(input: InputStream): ByteArray? {
        val buffer = ByteArray(2048)
        val output = ByteArrayOutputStream()
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (output.size() + count > MAX_METADATA_BYTES) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
