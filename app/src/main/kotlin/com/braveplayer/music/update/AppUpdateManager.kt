package com.braveplayer.music.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.braveplayer.music.BuildConfig
import com.braveplayer.music.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Result of an update check attempt.
 */
sealed class UpdateCheckResult {
    data class UpdateAvailable(val metadata: UpdateMetadata) : UpdateCheckResult()
    data class UpToDate(val currentVersionCode: Int, val currentVersionName: String) : UpdateCheckResult()
    data class Error(val message: String, val isRateLimited: Boolean = false, val cause: Throwable? = null) : UpdateCheckResult()
}

/**
 * Enterprise-grade, zero-budget app update manager for Melody.
 * Handles update discovery via public GitHub release metadata, rate-limit defense,
 * sha256 checksum verification, APK package signature verification, and secure FileProvider installation.
 */
class AppUpdateManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient = defaultClient()
) {

    companion object {
        private const val PREFS_NAME = "melody_app_update_prefs"
        private const val KEY_LAST_CHECK_MS = "last_check_timestamp_ms"
        private const val KEY_DISMISSED_VERSION = "dismissed_version_code"

        // Rate limit throttle for auto-check (1 hour between background polls)
        private const val AUTO_CHECK_THROTTLE_MS = 60 * 60 * 1000L

        // Primary public direct release asset URL (avoids GitHub API 60 req/hr rate limits)
        const val DEFAULT_METADATA_URL =
            "https://github.com/hernos5/csnix/releases/latest/download/latest.json"

        private fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // In-memory cache for valid metadata within the current app session
    private var cachedMetadata: UpdateMetadata? = null

    /**
     * Checks for an update asynchronously from the GitHub release metadata.
     *
     * @param isManual true if triggered by user from "Check for updates" in settings.
     *                 Bypasses throttle and dismissal checks.
     */
    suspend fun checkForUpdate(
        metadataUrl: String = DEFAULT_METADATA_URL,
        isManual: Boolean = false
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val currentVersionCode = getCurrentVersionCode()
        val currentVersionName = getCurrentVersionName()

        if (!NetworkUtils.isOnline(context)) {
            return@withContext UpdateCheckResult.Error(
                message = "No internet connection. Please connect to the internet to check for updates."
            )
        }

        val now = System.currentTimeMillis()
        val lastCheckMs = prefs.getLong(KEY_LAST_CHECK_MS, 0L)

        // For automatic startup checks, throttle requests to avoid hammering GitHub
        if (!isManual && (now - lastCheckMs) < AUTO_CHECK_THROTTLE_MS) {
            val cached = cachedMetadata
            if (cached != null) {
                if (cached.versionCode > currentVersionCode) {
                    val dismissed = prefs.getInt(KEY_DISMISSED_VERSION, -1)
                    if (cached.versionCode != dismissed || cached.mandatory) {
                        return@withContext UpdateCheckResult.UpdateAvailable(cached)
                    }
                }
                return@withContext UpdateCheckResult.UpToDate(currentVersionCode, currentVersionName)
            }
        }

        val request = Request.Builder()
            .url(metadataUrl)
            .header("User-Agent", "Melody-Android/$currentVersionName")
            .header("Accept", "application/json")
            .build()

        try {
            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                val code = resp.code

                if (code == 404) {
                    // No release or latest.json published yet
                    prefs.edit().putLong(KEY_LAST_CHECK_MS, now).apply()
                    return@use UpdateCheckResult.UpToDate(currentVersionCode, currentVersionName)
                }

                if (code == 403 || code == 429) {
                    return@use UpdateCheckResult.Error(
                        message = "GitHub request limit reached. Please try again later.",
                        isRateLimited = true
                    )
                }

                if (!resp.isSuccessful) {
                    return@use UpdateCheckResult.Error(
                        message = "Unable to check for updates (HTTP $code). Please try again later."
                    )
                }

                val bodyString = resp.body?.string() ?: ""
                val parseResult = UpdateMetadata.parseAndValidate(bodyString)

                val metadata = parseResult.getOrElse { error ->
                    return@use UpdateCheckResult.Error(
                        message = "Failed to parse update information: ${error.message}",
                        cause = error
                    )
                }

                prefs.edit().putLong(KEY_LAST_CHECK_MS, now).apply()
                cachedMetadata = metadata

                if (metadata.versionCode > currentVersionCode) {
                    val dismissed = prefs.getInt(KEY_DISMISSED_VERSION, -1)
                    if (isManual || metadata.versionCode != dismissed || metadata.mandatory) {
                        return@use UpdateCheckResult.UpdateAvailable(metadata)
                    } else {
                        // User chose "Later" previously and this is a non-mandatory auto check
                        return@use UpdateCheckResult.UpToDate(currentVersionCode, currentVersionName)
                    }
                } else {
                    return@use UpdateCheckResult.UpToDate(currentVersionCode, currentVersionName)
                }
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error(
                message = "Network error while checking for updates: ${e.localizedMessage ?: "Connection timed out"}",
                cause = e
            )
        }
    }

    /**
     * Remembers that the user dismissed the update prompt for this version code.
     */
    fun recordDismissal(versionCode: Int) {
        prefs.edit().putInt(KEY_DISMISSED_VERSION, versionCode).apply()
    }

    /**
     * Downloads the APK file from the verified HTTPS URL, calculating the SHA-256 hash streamingly.
     * Verifies the computed SHA-256 matches metadata, checks package identity, and verifies the
     * signing certificate matches the installed Melody app.
     *
     * @param onProgress Callback invoked on the worker thread with (bytesDownloaded, totalBytes, percent)
     */
    suspend fun downloadAndVerifyApk(
        metadata: UpdateMetadata,
        onProgress: (bytesRead: Long, totalBytes: Long, percent: Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val updatesDir = File(context.cacheDir, "updates")
            if (!updatesDir.exists()) {
                updatesDir.mkdirs()
            }

            // Remove any old APKs
            updatesDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".apk") || file.name.endsWith(".tmp")) {
                    file.delete()
                }
            }

            val targetFile = File(updatesDir, "Melody-v${metadata.versionCode}.apk")
            val tempFile = File(updatesDir, "Melody-v${metadata.versionCode}.apk.tmp")
            if (tempFile.exists()) tempFile.delete()

            val request = Request.Builder()
                .url(metadata.apkUrl)
                .header("User-Agent", "Melody-Android/${getCurrentVersionName()}")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IllegalStateException("Failed to download update APK (HTTP ${response.code})")
            }

            val body = response.body ?: throw IllegalStateException("Empty response body from update server")
            val contentLength = body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")

            var totalBytesRead = 0L
            val buffer = ByteArray(8192)

            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null

            try {
                inputStream = body.byteStream()
                outputStream = FileOutputStream(tempFile)

                while (true) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break

                    digest.update(buffer, 0, read)
                    outputStream.write(buffer, 0, read)

                    totalBytesRead += read
                    val percent = if (contentLength > 0) {
                        ((totalBytesRead * 100) / contentLength).toInt().coerceIn(0, 100)
                    } else {
                        -1
                    }
                    onProgress(totalBytesRead, contentLength, percent)
                }

                outputStream.flush()
            } finally {
                try { inputStream?.close() } catch (_: Exception) {}
                try { outputStream?.close() } catch (_: Exception) {}
                try { body.close() } catch (_: Exception) {}
            }

            // 1. Checksum Verification
            val computedHash = digest.digest().joinToString("") { "%02x".format(it) }
            val expectedHash = metadata.sha256.lowercase()

            if (!computedHash.equals(expectedHash, ignoreCase = true)) {
                tempFile.delete()
                throw SecurityException(
                    "Checksum verification failed! Downloaded file SHA-256 ($computedHash) did not match official release ($expectedHash)."
                )
            }

            // Rename temp to target APK
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                throw IllegalStateException("Failed to finalize downloaded APK file.")
            }

            // 2. Package Integrity & Signature Verification
            verifyPackageArchive(targetFile, metadata.versionCode)

            targetFile
        }
    }

    /**
     * Inspects the APK archive structure, package name, version, and signing certificate.
     * Rejects corrupted, incompatible, or incorrectly signed binaries.
     */
    fun verifyPackageArchive(apkFile: File, expectedVersionCode: Int) {
        val pm = context.packageManager

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: throw IllegalArgumentException("The downloaded file is not a valid Android APK archive.")

        // Verify package ID
        val expectedPackageName = context.packageName
        if (archiveInfo.packageName != expectedPackageName) {
            apkFile.delete()
            throw SecurityException(
                "Package ID mismatch! Expected '$expectedPackageName' but found '${archiveInfo.packageName}'."
            )
        }

        // Verify version code
        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            archiveInfo.versionCode
        }

        if (archiveVersionCode != expectedVersionCode) {
            apkFile.delete()
            throw IllegalArgumentException(
                "Version code mismatch! Expected $expectedVersionCode but APK contains $archiveVersionCode."
            )
        }

        if (archiveVersionCode <= getCurrentVersionCode()) {
            apkFile.delete()
            throw IllegalArgumentException(
                "The update version code ($archiveVersionCode) is not greater than the installed version (${getCurrentVersionCode()})."
            )
        }

        // Verify signing certificate matches the currently running app
        val installedPackageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(expectedPackageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(expectedPackageName, flags)
            }
        } catch (_: Exception) {
            null
        }

        if (installedPackageInfo != null) {
            val installedCertHash = extractCertSha256(installedPackageInfo)
            val archiveCertHash = extractCertSha256(archiveInfo)

            if (installedCertHash != null && archiveCertHash != null && !installedCertHash.equals(archiveCertHash, ignoreCase = true)) {
                apkFile.delete()
                throw SecurityException(
                    "Signing certificate mismatch! The update APK is signed with a different key than your installed Melody app."
                )
            }
        }
    }

    private fun extractCertSha256(packageInfo: PackageInfo): String? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo ?: return null
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }

        val firstCert = signatures?.firstOrNull()?.toByteArray() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(firstCert)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Launches Android's official package installer for the verified APK.
     *
     * Handles Android 8.0+ unknown app installation permission flow if required.
     */
    fun startInstallation(activity: Activity, apkFile: File) {
        if (!apkFile.exists()) {
            throw IllegalStateException("APK file not found: ${apkFile.absolutePath}")
        }

        // Check for Android 8.0+ unknown app sources permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                return
            }
        }

        val apkUri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        activity.startActivity(installIntent)
    }

    fun getCurrentVersionCode(): Int {
        return BuildConfig.VERSION_CODE
    }

    fun getCurrentVersionName(): String {
        return BuildConfig.VERSION_NAME
    }
}
