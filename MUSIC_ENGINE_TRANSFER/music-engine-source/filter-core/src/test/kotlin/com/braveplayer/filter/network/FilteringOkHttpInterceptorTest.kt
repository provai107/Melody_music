package com.braveplayer.filter.network

import com.braveplayer.filter.manager.FilterListManager
import com.braveplayer.filter.model.RequestType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class FilteringOkHttpInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var filterManager: FilterListManager

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()

        val rules = listOf(
            "||telemetry.analytics.com^",
            "||tracker.evil.org^"
        )
        filterManager = FilterListManager(initialRules = rules)

        val interceptor = FilteringOkHttpInterceptor(
            filterListManager = filterManager,
            defaultRequestType = RequestType.METADATA_API
        )

        client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    @DisplayName("Legitimate API requests pass through to backend server and receive 200 OK")
    fun testLegitimateApiRequestPasses() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"status\": \"ok\", \"tracks\": [\"Track 1\"]}")
        )

        val request = Request.Builder()
            .url(server.url("/v1/search?q=Beethoven"))
            .build()

        val response = client.newCall(request).execute()
        assertThat(response.code).isEqualTo(200)
        assertThat(response.body?.string()).contains("Track 1")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    @DisplayName("Blocked metadata/tracker requests receive synthetic 403 without wire transmission")
    fun testBlockedTrackerRequestReturns403() {
        val blockedUrl = "https://telemetry.analytics.com/event"
        val request = Request.Builder()
            .url(blockedUrl)
            .build()

        val response = client.newCall(request).execute()
        assertThat(response.code).isEqualTo(403)
        assertThat(response.header("X-Filter-Blocked")).isEqualTo("true")
        assertThat(response.header("X-Filter-Rule")).isEqualTo("||telemetry.analytics.com^")
        assertThat(response.body?.string()).contains("Blocked by Network Filter")

        // Crucial: Server should receive 0 requests
        assertThat(server.requestCount).isEqualTo(0)
    }
}
