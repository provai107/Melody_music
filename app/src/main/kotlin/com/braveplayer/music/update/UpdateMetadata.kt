package com.braveplayer.music.update

import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * Immutable data model representing the update metadata fetched from latest.json.
 *
 * Example payload:
 * {
 *   "versionName": "1.0.1",
 *   "versionCode": 2,
 *   "releaseNotes": "Bug fixes and performance improvements",
 *   "apkUrl": "https://github.com/hernos5/melody/releases/download/v1.0.1/Melody.apk",
 *   "sha256": "511b4e728ee0f2ef90db07ad4a7b8fa9d854c9d12af8813e6963ffd9a8cc5f56",
 *   "mandatory": false
 * }
 */
data class UpdateMetadata(
    val versionName: String,
    val versionCode: Int,
    val releaseNotes: String,
    val apkUrl: String,
    val sha256: String,
    val mandatory: Boolean
) {
    companion object {
        const val DEFAULT_OWNER = "hernos5"
        const val DEFAULT_REPO = "melody"

        private val SHA256_REGEX = Regex("^[a-fA-F0-9]{64}$")
        private val VERSION_NAME_REGEX = Regex("^[a-zA-Z0-9._\\-+ ]{1,32}$")

        // Approved hosts for GitHub release assets
        private val ALLOWED_HOSTS = setOf(
            "github.com",
            "www.github.com",
            "objects.githubusercontent.com",
            "github-releases.githubusercontent.com"
        )

        /**
         * Parses and strictly validates update metadata JSON from an untrusted source.
         * Returns Result.success(UpdateMetadata) on valid data, or Result.failure with details.
         */
        fun parseAndValidate(
            jsonString: String,
            expectedOwner: String = DEFAULT_OWNER,
            expectedRepo: String = DEFAULT_REPO
        ): Result<UpdateMetadata> {
            return runCatching {
                val json = JSONObject(jsonString)

                if (!json.has("versionName")) throw IllegalArgumentException("Missing required field: versionName")
                if (!json.has("versionCode")) throw IllegalArgumentException("Missing required field: versionCode")
                if (!json.has("apkUrl")) throw IllegalArgumentException("Missing required field: apkUrl")
                if (!json.has("sha256")) throw IllegalArgumentException("Missing required field: sha256")

                val versionName = json.getString("versionName").trim()
                if (versionName.isEmpty() || !VERSION_NAME_REGEX.matches(versionName)) {
                    throw IllegalArgumentException("Invalid versionName: '$versionName'")
                }

                val versionCode = json.getInt("versionCode")
                if (versionCode <= 0) {
                    throw IllegalArgumentException("Invalid versionCode: $versionCode (must be positive integer)")
                }

                val releaseNotes = if (json.has("releaseNotes")) {
                    json.getString("releaseNotes").trim()
                } else {
                    "Bug fixes and performance improvements."
                }

                val rawApkUrl = json.getString("apkUrl").trim()
                if (!rawApkUrl.startsWith("https://", ignoreCase = true)) {
                    throw IllegalArgumentException("apkUrl must use secure HTTPS protocol: '$rawApkUrl'")
                }

                val uri = try {
                    URI(rawApkUrl)
                } catch (e: Exception) {
                    throw IllegalArgumentException("Malformed apkUrl: '$rawApkUrl'", e)
                }

                val host = uri.host?.lowercase(Locale.ROOT)
                    ?: throw IllegalArgumentException("apkUrl is missing a valid host")

                if (host !in ALLOWED_HOSTS) {
                    throw IllegalArgumentException("Untrusted APK download host '$host'. Only approved GitHub Release domains are permitted.")
                }

                val path = uri.path ?: ""
                if (!path.lowercase(Locale.ROOT).endsWith(".apk")) {
                    throw IllegalArgumentException("apkUrl must target an APK binary file ending in .apk: '$rawApkUrl'")
                }

                // If hosted directly on github.com, enforce repository ownership containment
                if (host == "github.com" || host == "www.github.com") {
                    val expectedPathSegment = "/$expectedOwner/$expectedRepo/releases/download/".lowercase(Locale.ROOT)
                    if (!path.lowercase(Locale.ROOT).contains(expectedPathSegment)) {
                        throw IllegalArgumentException("apkUrl path '$path' does not match repository releases for '$expectedOwner/$expectedRepo'")
                    }
                }

                val sha256 = json.getString("sha256").trim().lowercase(Locale.ROOT)
                if (!SHA256_REGEX.matches(sha256)) {
                    throw IllegalArgumentException("Invalid sha256 checksum format: must be 64 hexadecimal characters.")
                }

                val mandatory = json.optBoolean("mandatory", false)

                UpdateMetadata(
                    versionName = versionName,
                    versionCode = versionCode,
                    releaseNotes = releaseNotes,
                    apkUrl = rawApkUrl,
                    sha256 = sha256,
                    mandatory = mandatory
                )
            }
        }
    }
}
