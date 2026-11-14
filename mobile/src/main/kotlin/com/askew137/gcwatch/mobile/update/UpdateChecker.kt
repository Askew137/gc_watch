package com.askew137.gcwatch.mobile.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub Releases for a newer version of GC Watch.
 *
 * This is a v1 implementation: it only reads the latest release and returns
 * [UpdateInfo] when the remote tag is newer than [currentVersion]. The UI
 * will display a notification/snackbar prompting the user to visit the
 * release page manually. [AppUpdater] can download and install it in the app.
 */
object UpdateChecker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val RELEASES_URL = com.askew137.gcwatch.mobile.BuildConfig.UPDATE_URL

    /**
     * Makes an HTTP GET to the GitHub Releases API.
     *
     * @param currentVersion Semantic version string of the installed app, e.g. "1.0.0".
     * @return [UpdateInfo] if a newer release exists, or `null` if up to date / network error.
     */
    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? {
        val info = fetch(RELEASES_URL) ?: return null
        Timber.d("Latest GitHub release: ${info.version} (installed: $currentVersion)")
        return info.takeIf { isNewerVersion(remote = it.version, current = currentVersion) }
    }

    /**
     * The release for exactly [versionName] (tag `v<versionName>`), whatever is installed.
     * Used to bring the phone and the watch to the same version.
     */
    suspend fun releaseFor(versionName: String): UpdateInfo? =
        fetch(RELEASES_URL.replace("/latest", "/tags/v$versionName"))

    private suspend fun fetch(url: String): UpdateInfo? = withContext(Dispatchers.IO) {
        // Blocking OkHttp call: must not run on the main thread (callers are viewModelScopes).
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .build()

            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("GitHub API returned HTTP ${response.code} for $url")
                    return@withContext null
                }
                response.body?.string() ?: return@withContext null
            }

            val json = JSONObject(body)
            val assets = json.optJSONArray("assets")
            fun assetUrl(name: String): String? {
                if (assets == null) return null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name") == name) return a.optString("browser_download_url").takeIf { it.isNotEmpty() }
                }
                return null
            }

            UpdateInfo(
                version = json.getString("tag_name").trimStart('v'),
                releaseUrl = json.getString("html_url"),
                mobileApkUrl = assetUrl("mobile-release.apk"),
                wearApkUrl = assetUrl("wear-release.apk"),
            )
        } catch (e: Exception) {
            // Silent fail – update check is non-critical.
            Timber.w(e, "Release lookup failed (silent)")
            null
        }
    }

    /**
     * Compares two semver strings (`MAJOR.MINOR.PATCH`).
     * Returns `true` if [remote] is strictly greater than [current].
     */
    internal fun isNewerVersion(remote: String, current: String): Boolean {
        val remoteParts  = remote.split(".").mapNotNull { it.trim().toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.trim().toIntOrNull() }

        for (i in 0 until maxOf(remoteParts.size, currentParts.size)) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false // equal
    }
}

data class UpdateInfo(
    val version: String,
    val releaseUrl: String,
    /** Direct download URLs of the release assets; null when the release has no such file. */
    val mobileApkUrl: String? = null,
    val wearApkUrl: String? = null,
) {
    val canUpdateInApp: Boolean get() = mobileApkUrl != null && wearApkUrl != null
}
