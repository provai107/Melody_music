package com.braveplayer.music.update

import android.content.Context
import org.robolectric.RuntimeEnvironment
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class AppUpdateManagerTest {

    private lateinit var context: Context
    private var mockResponseCode = 200
    private var mockResponseBody = ""
    private var shouldThrowNetworkException = false

    private lateinit var mockClient: OkHttpClient
    private lateinit var updateManager: AppUpdateManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        mockResponseCode = 200
        mockResponseBody = ""
        shouldThrowNetworkException = false

        val interceptor = Interceptor { chain ->
            if (shouldThrowNetworkException) {
                throw IOException("Simulated network timeout / offline")
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(mockResponseCode)
                .message(if (mockResponseCode == 200) "OK" else "Error")
                .body(mockResponseBody.toResponseBody("application/json".toMediaType()))
                .build()
        }

        mockClient = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        updateManager = AppUpdateManager(context, mockClient)
    }

    @Test
    fun testValidMetadataParsing() {
        val json = """
            {
              "versionName": "1.0.1",
              "versionCode": 2,
              "releaseNotes": "Enhanced music recommendations and instant search.",
              "apkUrl": "https://github.com/hernos5/melody/releases/download/v1.0.1/Melody.apk",
              "sha256": "511b4e728ee0f2ef90db07ad4a7b8fa9d854c9d12af8813e6963ffd9a8cc5f56",
              "mandatory": false
            }
        """.trimIndent()

        val result = UpdateMetadata.parseAndValidate(json)
        assertThat(result.isSuccess).isTrue()

        val meta = result.getOrThrow()
        assertThat(meta.versionName).isEqualTo("1.0.1")
        assertThat(meta.versionCode).isEqualTo(2)
        assertThat(meta.releaseNotes).isEqualTo("Enhanced music recommendations and instant search.")
        assertThat(meta.apkUrl).isEqualTo("https://github.com/hernos5/melody/releases/download/v1.0.1/Melody.apk")
        assertThat(meta.sha256).isEqualTo("511b4e728ee0f2ef90db07ad4a7b8fa9d854c9d12af8813e6963ffd9a8cc5f56")
        assertThat(meta.mandatory).isFalse()
    }

    @Test
    fun testMandatoryUpdateParsing() {
        val json = """
            {
              "versionName": "2.0.0",
              "versionCode": 10,
              "releaseNotes": "Critical security and streaming protocol upgrade.",
              "apkUrl": "https://github.com/hernos5/melody/releases/download/v2.0.0/Melody.apk",
              "sha256": "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
              "mandatory": true
            }
        """.trimIndent()

        val result = UpdateMetadata.parseAndValidate(json)
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().mandatory).isTrue()
    }

    @Test
    fun testInvalidJsonThrowsError() {
        val brokenJson = "{ invalid json content: 123 "
        val result = UpdateMetadata.parseAndValidate(brokenJson)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun testMissingRequiredFields() {
        val missingVersionCode = """
            {
              "versionName": "1.1.0",
              "apkUrl": "https://github.com/hernos5/melody/releases/download/v1.1.0/Melody.apk",
              "sha256": "511b4e728ee0f2ef90db07ad4a7b8fa9d854c9d12af8813e6963ffd9a8cc5f56"
            }
        """.trimIndent()

        val result = UpdateMetadata.parseAndValidate(missingVersionCode)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("versionCode")
    }

    @Test
    fun testNonHttpsUrlRejected() {
        val insecureUrl = """
            {
              "versionName": "1.0.1",
              "versionCode": 2,
              "apkUrl": "http://github.com/hernos5/melody/releases/download/v1.0.1/Melody.apk",
              "sha256": "511b4e728ee0f2ef90db07ad4a7b8fa9d854c9d12af8813e6963ffd9a8cc5f56"
            }
        """.trimIndent()

        val result = UpdateMetadata.parseAndValidate(insecureUrl)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("HTTPS")
    }

    @Test
    fun testUntrustedHostRejected() {
        val maliciousHost = """
            {
              "versionName": "1.0.1",
              "versionCode": 2,
              "apkUrl": "https://malicious-thirdparty-site.com/updates/Melody.apk",
              "sha256": "511b4e728ee0f2ef90db07ad4a7b8fa9d854c9d12af8813e6963ffd9a8cc5f56"
            }
        """.trimIndent()

        val result = UpdateMetadata.parseAndValidate(maliciousHost)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Untrusted APK download host")
    }

    @Test
    fun testInvalidSha256FormatRejected() {
        val shortSha = """
            {
              "versionName": "1.0.1",
              "versionCode": 2,
              "apkUrl": "https://github.com/hernos5/melody/releases/download/v1.0.1/Melody.apk",
              "sha256": "tooshort"
            }
        """.trimIndent()

        val result = UpdateMetadata.parseAndValidate(shortSha)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("sha256 checksum format")
    }

    @Test
    fun testNonApkExtensionRejected() {
        val invalidExtension = """
            {
              "versionName": "1.0.1",
              "versionCode": 2,
              "apkUrl": "https://github.com/hernos5/melody/releases/download/v1.0.1/Melody.zip",
              "sha256": "511b4e728ee0f2ef90db07ad4a7b8fa9d854c9d12af8813e6963ffd9a8cc5f56"
            }
        """.trimIndent()

        val result = UpdateMetadata.parseAndValidate(invalidExtension)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains(".apk")
    }

    @Test
    fun testAppIsUpToDate() {
        kotlinx.coroutines.runBlocking {
            mockResponseCode = 200
            mockResponseBody = """
                {
                  "versionName": "1.0.0",
                  "versionCode": 1,
                  "releaseNotes": "Current release",
                  "apkUrl": "https://github.com/hernos5/melody/releases/download/v1.0.0/Melody.apk",
                  "sha256": "511b4e728ee0f2ef90db07ad4a7b8fa9d854c9d12af8813e6963ffd9a8cc5f56",
                  "mandatory": false
                }
            """.trimIndent()

            val result = updateManager.checkForUpdate(isManual = true)
            assertThat(result).isInstanceOf(UpdateCheckResult.UpToDate::class.java)
            val upToDate = result as UpdateCheckResult.UpToDate
            assertThat(upToDate.currentVersionCode).isEqualTo(updateManager.getCurrentVersionCode())
        }
    }

    @Test
    fun testNewerReleaseAvailable() {
        kotlinx.coroutines.runBlocking {
            mockResponseCode = 200
            mockResponseBody = """
                {
                  "versionName": "1.1.0",
                  "versionCode": 999,
                  "releaseNotes": "Major update with smart autoplay enhancements.",
                  "apkUrl": "https://github.com/hernos5/melody/releases/download/v1.1.0/Melody.apk",
                  "sha256": "511b4e728ee0f2ef90db07ad4a7b8fa9d854c9d12af8813e6963ffd9a8cc5f56",
                  "mandatory": false
                }
            """.trimIndent()

            val result = updateManager.checkForUpdate(isManual = true)
            assertThat(result).isInstanceOf(UpdateCheckResult.UpdateAvailable::class.java)
            val available = result as UpdateCheckResult.UpdateAvailable
            assertThat(available.metadata.versionName).isEqualTo("1.1.0")
            assertThat(available.metadata.versionCode).isEqualTo(999)
        }
    }

    @Test
    fun testDismissedOptionalUpdateSuppressedOnAutoCheck() {
        kotlinx.coroutines.runBlocking {
            mockResponseCode = 200
            mockResponseBody = """
                {
                  "versionName": "1.1.0",
                  "versionCode": 999,
                  "releaseNotes": "Optional UI update.",
                  "apkUrl": "https://github.com/hernos5/melody/releases/download/v1.1.0/Melody.apk",
                  "sha256": "511b4e728ee0f2ef90db07ad4a7b8fa9d854c9d12af8813e6963ffd9a8cc5f56",
                  "mandatory": false
                }
            """.trimIndent()

            // User dismissed version 999
            updateManager.recordDismissal(999)

            // Automatic background startup check should respect dismissal
            val autoResult = updateManager.checkForUpdate(isManual = false)
            assertThat(autoResult).isInstanceOf(UpdateCheckResult.UpToDate::class.java)

            // Manual check from Settings should still display the update
            val manualResult = updateManager.checkForUpdate(isManual = true)
            assertThat(manualResult).isInstanceOf(UpdateCheckResult.UpdateAvailable::class.java)
        }
    }

    @Test
    fun testMandatoryUpdateNotSuppressedByDismissal() {
        kotlinx.coroutines.runBlocking {
            mockResponseCode = 200
            mockResponseBody = """
                {
                  "versionName": "1.1.0",
                  "versionCode": 999,
                  "releaseNotes": "Critical patch.",
                  "apkUrl": "https://github.com/hernos5/melody/releases/download/v1.1.0/Melody.apk",
                  "sha256": "511b4e728ee0f2ef90db07ad4a7b8fa9d854c9d12af8813e6963ffd9a8cc5f56",
                  "mandatory": true
                }
            """.trimIndent()

            updateManager.recordDismissal(999)

            // Mandatory update must never be suppressed
            val autoResult = updateManager.checkForUpdate(isManual = false)
            assertThat(autoResult).isInstanceOf(UpdateCheckResult.UpdateAvailable::class.java)
        }
    }

    @Test
    fun testHttp404TreatedAsUpToDate() {
        kotlinx.coroutines.runBlocking {
            mockResponseCode = 404
            mockResponseBody = "Release Not Found"

            val result = updateManager.checkForUpdate(isManual = true)
            assertThat(result).isInstanceOf(UpdateCheckResult.UpToDate::class.java)
        }
    }

    @Test
    fun testRateLimitingHttp403or429() {
        kotlinx.coroutines.runBlocking {
            mockResponseCode = 429
            mockResponseBody = "Rate limit exceeded"

            val result = updateManager.checkForUpdate(isManual = true)
            assertThat(result).isInstanceOf(UpdateCheckResult.Error::class.java)
            val error = result as UpdateCheckResult.Error
            assertThat(error.isRateLimited).isTrue()
        }
    }

    @Test
    fun testNetworkTimeoutOrOfflineHandledGracefully() {
        kotlinx.coroutines.runBlocking {
            shouldThrowNetworkException = true

            val result = updateManager.checkForUpdate(isManual = true)
            assertThat(result).isInstanceOf(UpdateCheckResult.Error::class.java)
            val error = result as UpdateCheckResult.Error
            assertThat(error.message).contains("Network error")
        }
    }

    @Test
    fun testChecksumMismatchRejection() {
        kotlinx.coroutines.runBlocking {
            val fakeApkContent = "This is a fake apk payload with bad checksum"
            mockResponseCode = 200
            mockResponseBody = fakeApkContent

            val metadata = UpdateMetadata(
                versionName = "1.0.1",
                versionCode = 2,
                releaseNotes = "Notes",
                apkUrl = "https://github.com/hernos5/melody/releases/download/v1.0.1/Melody.apk",
                sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
                mandatory = false
            )

            val result = updateManager.downloadAndVerifyApk(metadata) { _, _, _ -> }
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
            assertThat(result.exceptionOrNull()?.message).contains("Checksum verification failed")
        }
    }
}
