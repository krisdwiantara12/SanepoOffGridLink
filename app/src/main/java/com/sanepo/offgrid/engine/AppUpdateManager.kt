package com.sanepo.offgrid.engine

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.sanepo.offgrid.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object AppUpdateManager {

    const val GITHUB_OWNER = "krisdwiantara12"
    const val GITHUB_REPO = "SanepoOffGridLink"
    const val REPOSITORY_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"
    const val RELEASES_PAGE_URL = "$REPOSITORY_URL/releases"
    const val LATEST_RELEASE_API = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    const val ALL_RELEASES_API = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases?per_page=10"
    const val RAW_LATEST_JSON_URL =
        "https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/main/releases/latest.json"

    private const val PREFS_NAME = "sanepo_offgrid_update_prefs"
    private const val KEY_AUTO_CHECK = "auto_check_on_launch"
    private const val KEY_INCLUDE_PRERELEASE = "include_prerelease"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    private const val KEY_LAST_REMOTE_TAG = "last_remote_tag"
    private const val AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L

    data class ReleaseInfo(
        val tagName: String,
        val releaseName: String,
        val body: String,
        val publishedAt: String,
        val htmlUrl: String,
        val isPreRelease: Boolean,
        val apkAssetName: String?,
        val apkDownloadUrl: String?,
        val apkSizeBytes: Long,
        val expectedSha256: String?,
        val updateAvailable: Boolean
    )

    sealed class CheckOutcome {
        data class Success(val release: ReleaseInfo, val checkedAtMs: Long) : CheckOutcome()
        data class NoReleasePublished(val checkedAtMs: Long, val detail: String) : CheckOutcome()
        data class Error(val message: String) : CheckOutcome()
    }

    sealed class DownloadState {
        data class Progress(val percent: Int, val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
        data class ReadyToInstall(val apkFile: File, val shaVerified: Boolean?, val message: String) : DownloadState()
        data class Failed(val reason: String) : DownloadState()
    }

    private data class SemVer(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val preRelease: String
    ) : Comparable<SemVer> {
        override fun compareTo(other: SemVer): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            if (patch != other.patch) return patch.compareTo(other.patch)
            if (preRelease.isEmpty() && other.preRelease.isNotEmpty()) return 1
            if (preRelease.isNotEmpty() && other.preRelease.isEmpty()) return -1
            return preRelease.compareTo(other.preRelease, ignoreCase = true)
        }
    }

    private fun parseSemVer(rawVersion: String): SemVer? {
        val cleaned = rawVersion.trim()
            .removePrefix("refs/tags/")
            .trimStart('v', 'V')
        val match = Regex("""(\d+)\.(\d+)(?:\.(\d+))?(?:-([A-Za-z0-9.\-]+))?""").find(cleaned)
            ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        val patch = match.groupValues[3].toIntOrNull() ?: 0
        val pre = match.groupValues[4]
        return SemVer(major, minor, patch, pre)
    }

    fun isRemoteNewer(currentVersion: String, remoteTag: String): Boolean {
        val current = parseSemVer(currentVersion)
        val remote = parseSemVer(remoteTag)
        if (current != null && remote != null) {
            return remote > current
        }
        return remoteTag.trimStart('v', 'V').compareTo(currentVersion.trimStart('v', 'V'), ignoreCase = true) > 0
    }

    fun parseRawManifestJson(rawJson: String, currentVersion: String): ReleaseInfo? {
        return try {
            val obj = JSONObject(rawJson)
            val tagName = obj.optString("tagName").trim()
            val url = obj.optString("apkDownloadUrl").trim()
            if (tagName.isEmpty() || url.isEmpty()) return null
            val releaseName = obj.optString("releaseName").ifBlank { tagName }
            val body = obj.optString("body").orEmpty()
            val publishedAt = obj.optString("publishedAt").orEmpty()
            val htmlUrl = obj.optString("htmlUrl").ifBlank { RELEASES_PAGE_URL }
            val isPre = obj.optBoolean("isPreRelease", false)
            val assetName = obj.optString("apkAssetName").ifBlank { "SanepoOffGridLink-$tagName.apk" }
            val sizeBytes = obj.optLong("apkSizeBytes", 0L)
            val sha = obj.optString("expectedSha256").trim().takeIf { it.length == 64 }
            ReleaseInfo(
                tagName = tagName,
                releaseName = releaseName,
                body = body,
                publishedAt = publishedAt,
                htmlUrl = htmlUrl,
                isPreRelease = isPre,
                apkAssetName = assetName,
                apkDownloadUrl = url,
                apkSizeBytes = sizeBytes,
                expectedSha256 = sha,
                updateAvailable = isRemoteNewer(currentVersion, tagName)
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun parseReleaseJson(rawJson: String, isArrayPayload: Boolean, currentVersion: String): ReleaseInfo? {
        return try {
            val releaseObj: JSONObject = if (isArrayPayload) {
                val arr = JSONArray(rawJson)
                var chosen: JSONObject? = null
                for (i in 0 until arr.length()) {
                    val candidate = arr.optJSONObject(i) ?: continue
                    if (!candidate.optBoolean("draft", false)) {
                        chosen = candidate
                        break
                    }
                }
                chosen ?: return null
            } else {
                val single = JSONObject(rawJson)
                if (single.optBoolean("draft", false)) return null
                single
            }

            val tagName = releaseObj.optString("tag_name").trim()
            if (tagName.isEmpty()) return null
            val releaseName = releaseObj.optString("name").trim().ifBlank { tagName }
            val body = releaseObj.optString("body").orEmpty()
            val publishedAt = releaseObj.optString("published_at").orEmpty()
            val htmlUrl = releaseObj.optString("html_url").ifBlank { RELEASES_PAGE_URL }
            val isPre = releaseObj.optBoolean("prerelease", false)

            var assetName: String? = null
            var downloadUrl: String? = null
            var sizeBytes = 0L
            val assets = releaseObj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        assetName = name
                        downloadUrl = asset.optString("browser_download_url")
                        sizeBytes = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            val shaRegex = Regex("""(?i)sha[-_ ]?256\s*[:=]\s*([0-9a-f]{64})""")
            val expectedSha = shaRegex.find(body)?.groupValues?.get(1)?.lowercase()

            ReleaseInfo(
                tagName = tagName,
                releaseName = releaseName,
                body = body,
                publishedAt = publishedAt,
                htmlUrl = htmlUrl,
                isPreRelease = isPre,
                apkAssetName = assetName,
                apkDownloadUrl = downloadUrl,
                apkSizeBytes = sizeBytes,
                expectedSha256 = expectedSha,
                updateAvailable = isRemoteNewer(currentVersion, tagName)
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun checkForUpdate(context: Context, includePreReleases: Boolean): CheckOutcome {
        if (!hasNetworkConnection(context)) {
            return CheckOutcome.Error("Tidak ada koneksi internet aktif. Mode off-grid tidak memerlukan internet untuk radio.")
        }
        val urlStr = if (includePreReleases) ALL_RELEASES_API else LATEST_RELEASE_API
        var conn: HttpURLConnection? = null
        return try {
            conn = openHttpConnection(urlStr, isJsonApi = true)
            val code = conn.responseCode
            val nowMs = System.currentTimeMillis()
            when (code) {
                HttpURLConnection.HTTP_OK -> {
                    val payload = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val parsed = parseReleaseJson(payload, includePreReleases, BuildConfig.VERSION_NAME)
                    if (parsed != null && !parsed.apkDownloadUrl.isNullOrBlank()) {
                        saveSuccessfulCheckMeta(context, nowMs, parsed.tagName)
                        CheckOutcome.Success(parsed, nowMs)
                    } else {
                        val rawRel = checkRawFallback(BuildConfig.VERSION_NAME)
                        if (rawRel != null && (parsed == null || parsed.tagName.isBlank() || !isRemoteNewer(rawRel.tagName, parsed.tagName))) {
                            saveSuccessfulCheckMeta(context, nowMs, rawRel.tagName)
                            CheckOutcome.Success(rawRel, nowMs)
                        } else if (parsed != null) {
                            saveSuccessfulCheckMeta(context, nowMs, parsed.tagName)
                            CheckOutcome.Success(parsed, nowMs)
                        } else {
                            CheckOutcome.NoReleasePublished(nowMs, "Belum ada rilis publik di GitHub.")
                        }
                    }
                }
                HttpURLConnection.HTTP_NOT_FOUND -> {
                    val rawRel = checkRawFallback(BuildConfig.VERSION_NAME)
                    if (rawRel != null) {
                        saveSuccessfulCheckMeta(context, nowMs, rawRel.tagName)
                        CheckOutcome.Success(rawRel, nowMs)
                    } else {
                        CheckOutcome.NoReleasePublished(nowMs, "Belum ada GitHub Release publik pada $GITHUB_OWNER/$GITHUB_REPO.")
                    }
                }
                else -> {
                    val rawRel = checkRawFallback(BuildConfig.VERSION_NAME)
                    if (rawRel != null) {
                        saveSuccessfulCheckMeta(context, nowMs, rawRel.tagName)
                        CheckOutcome.Success(rawRel, nowMs)
                    } else {
                        CheckOutcome.Error("GitHub API merespons HTTP $code")
                    }
                }
            }
        } catch (t: Throwable) {
            val rawRel = checkRawFallback(BuildConfig.VERSION_NAME)
            if (rawRel != null) {
                val nowMs = System.currentTimeMillis()
                saveSuccessfulCheckMeta(context, nowMs, rawRel.tagName)
                CheckOutcome.Success(rawRel, nowMs)
            } else {
                CheckOutcome.Error("Gagal memeriksa update: ${t.message ?: t.javaClass.simpleName}")
            }
        } finally {
            conn?.disconnect()
        }
    }

    private fun checkRawFallback(currentVersion: String): ReleaseInfo? {
        var conn: HttpURLConnection? = null
        return try {
            conn = openHttpConnection(RAW_LATEST_JSON_URL, isJsonApi = false)
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseRawManifestJson(text, currentVersion)
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun isAutoCheckOnLaunch(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CHECK, true)
    }

    fun setAutoCheckOnLaunch(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
    }

    fun isIncludePreRelease(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_INCLUDE_PRERELEASE, false)
    }

    fun setIncludePreRelease(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_INCLUDE_PRERELEASE, enabled).apply()
    }

    fun shouldRunSilentLaunchCheck(context: Context): Boolean {
        if (!isAutoCheckOnLaunch(context) || !hasNetworkConnection(context)) return false
        val lastMs = getLastCheckTimestampMs(context)
        return System.currentTimeMillis() - lastMs >= AUTO_CHECK_INTERVAL_MS
    }

    fun getLastCheckTimestampMs(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHECK_MS, 0L)
    }

    fun getLastRemoteTag(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_REMOTE_TAG, null)
    }

    fun downloadApkForInstall(
        context: Context,
        release: ReleaseInfo,
        onState: (DownloadState) -> Unit
    ) {
        val url = release.apkDownloadUrl
        if (url.isNullOrBlank()) {
            onState(DownloadState.Failed("URL APK rilis tidak ditemukan."))
            return
        }
        val appCtx = context.applicationContext
        val updatesDir = File(appCtx.cacheDir, "updates").apply { mkdirs() }
        updatesDir.listFiles()?.forEach { old -> if (old.name.endsWith(".apk")) old.delete() }
        val fileName = (release.apkAssetName ?: "SanepoOffGridLink-${release.tagName}.apk")
            .replace(Regex("[^A-Za-z0-9._\\-]"), "_")
        val destFile = File(updatesDir, fileName)

        try {
            val dm = appCtx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (dm != null) {
                val request = DownloadManager.Request(Uri.parse(url))
                    .setTitle("Sanepo OffGrid Link ${release.tagName}")
                    .setDescription("Mengunduh pembaruan APK resmi")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationUri(Uri.fromFile(destFile))
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)

                val downloadId = dm.enqueue(request)
                monitorDownloadManager(appCtx, dm, downloadId, destFile, release.expectedSha256, onState)
                return
            }
        } catch (_: Throwable) {
        }
        onState(DownloadState.Failed("Layanan DownloadManager tidak tersedia di perangkat."))
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun buildInstallUnknownSourcesIntent(context: Context): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        } else null
    }

    fun launchApkInstaller(context: Context, apkFile: File): Boolean {
        return try {
            val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun monitorDownloadManager(
        context: Context,
        dm: DownloadManager,
        downloadId: Long,
        destFile: File,
        expectedSha256: String?,
        onState: (DownloadState) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val doneId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                if (doneId != downloadId || finished) return
                finished = true
                try {
                    context.unregisterReceiver(this)
                } catch (_: Throwable) {
                }
                verifyAndEmitDownloadedApk(dm, downloadId, destFile, expectedSha256, onState)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )

        val poll = object : Runnable {
            override fun run() {
                if (finished) return
                try {
                    dm.query(DownloadManager.Query().setFilterById(downloadId))?.use { c ->
                        if (c.moveToFirst()) {
                            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            val soFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            val pct = if (total > 0) ((soFar * 100L) / total).toInt().coerceIn(0, 100) else 0
                            onState(DownloadState.Progress(pct, soFar, total))
                            if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                                finished = true
                                try {
                                    context.unregisterReceiver(receiver)
                                } catch (_: Throwable) {
                                }
                                verifyAndEmitDownloadedApk(dm, downloadId, destFile, expectedSha256, onState)
                                return
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
                handler.postDelayed(this, 350L)
            }
        }
        handler.postDelayed(poll, 250L)
    }

    private fun verifyAndEmitDownloadedApk(
        dm: DownloadManager,
        downloadId: Long,
        destFile: File,
        expectedSha256: String?,
        onState: (DownloadState) -> Unit
    ) {
        try {
            dm.query(DownloadManager.Query().setFilterById(downloadId))?.use { c ->
                if (!c.moveToFirst()) {
                    onState(DownloadState.Failed("Unduhan APK dibatalkan."))
                    return
                }
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status != DownloadManager.STATUS_SUCCESSFUL) {
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    onState(DownloadState.Failed("Unduhan gagal (kode $reason)."))
                    return
                }
            }
        } catch (_: Throwable) {
        }
        if (!destFile.exists() || destFile.length() < 10_000L) {
            onState(DownloadState.Failed("File APK tidak lengkap."))
            return
        }
        val shaVerified: Boolean? = if (!expectedSha256.isNullOrBlank()) {
            computeSha256Hex(destFile).equals(expectedSha256, ignoreCase = true)
        } else null

        if (shaVerified == false) {
            destFile.delete()
            onState(DownloadState.Failed("Verifikasi SHA-256 tidak cocok! Unduhan dibatalkan demi keamanan."))
            return
        }
        val note = if (shaVerified == true) "SHA-256 terverifikasi. Siap pasang." else "APK siap dipasang."
        onState(DownloadState.ReadyToInstall(destFile, shaVerified, note))
    }

    private fun computeSha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16_384)
            var n = input.read(buf)
            while (n > 0) {
                digest.update(buf, 0, n)
                n = input.read(buf)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun saveSuccessfulCheckMeta(context: Context, checkedAtMs: Long, tagName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_CHECK_MS, checkedAtMs)
            .putString(KEY_LAST_REMOTE_TAG, tagName)
            .apply()
    }

    private fun openHttpConnection(urlStr: String, isJsonApi: Boolean): HttpURLConnection {
        return (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SanepoOffGridLink/${BuildConfig.VERSION_NAME} (Android)")
            if (isJsonApi) {
                setRequestProperty("Accept", "application/vnd.github+json")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun hasNetworkConnection(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            cm.activeNetworkInfo?.isConnected == true
        }
    }
}
