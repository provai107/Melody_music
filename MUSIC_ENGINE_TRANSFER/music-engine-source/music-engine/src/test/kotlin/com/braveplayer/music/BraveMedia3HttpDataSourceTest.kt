package com.braveplayer.music

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.braveplayer.filter.manager.FilterListManager
import com.braveplayer.filter.policy.AudioCdnPolicy
import com.braveplayer.music.media.BraveMedia3HttpDataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class BraveMedia3HttpDataSourceTest {

    class MockUpstreamHttpDataSource : HttpDataSource {
        var openedDataSpec: DataSpec? = null
        var isClosed = false
        val customProperties = mutableMapOf<String, String>()

        override fun open(dataSpec: DataSpec): Long {
            openedDataSpec = dataSpec
            return 1024L
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return minOf(length, 1024)
        }

        override fun getUri(): Uri? = openedDataSpec?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

        override fun getResponseCode(): Int = 200

        override fun setRequestProperty(name: String, value: String) {
            customProperties[name] = value
        }

        override fun clearRequestProperty(name: String) {
            customProperties.remove(name)
        }

        override fun clearAllRequestProperties() {
            customProperties.clear()
        }

        override fun addTransferListener(transferListener: TransferListener) {}

        override fun close() {
            isClosed = true
        }
    }

    private lateinit var filterManager: FilterListManager
    private lateinit var upstream: MockUpstreamHttpDataSource
    private lateinit var dataSource: BraveMedia3HttpDataSource

    @Before
    fun setUp() {
        val rules = listOf(
            "||telemetry.tracker.com^",
            "||malicious-audio.net^",
            "/videoplayback*" // Broad rule that would break YouTube CDN if not protected
        )
        val audioCdnPolicy = AudioCdnPolicy()
        filterManager = FilterListManager(
            initialRules = rules,
            audioCdnPolicy = audioCdnPolicy
        )
        upstream = MockUpstreamHttpDataSource()
        dataSource = BraveMedia3HttpDataSource(upstream, filterManager)
    }

    @Test
    fun testLegitimateAudioCdnPlaybackOpensSuccessfully() {
        val uri = Uri.parse("https://googlevideo.com/videoplayback?id=audio123")
        val dataSpec = DataSpec(uri)

        val bytes = dataSource.open(dataSpec)
        assertThat(bytes).isEqualTo(1024L)
        assertThat(upstream.openedDataSpec?.uri).isEqualTo(uri)

        dataSource.close()
        assertThat(upstream.isClosed).isTrue()
    }

    @Test
    fun testSeekingRangeHeadersPreserved() {
        val uri = Uri.parse("https://googlevideo.com/videoplayback?id=audio123")
        val seekOffset = 5242880L // 5MB seek
        val dataSpec = DataSpec(uri, seekOffset, 1024L)

        dataSource.open(dataSpec)
        assertThat(upstream.openedDataSpec?.position).isEqualTo(seekOffset)
    }

    @Test
    fun testProviderRequiredAdSegmentsPreserved() {
        // Stream URL containing provider ad markers
        val uri = Uri.parse("https://googlevideo.com/videoplayback?id=audio123&ctier=L&adformat=1_2")
        val dataSpec = DataSpec(uri)

        // Must succeed without throwing
        val bytes = dataSource.open(dataSpec)
        assertThat(bytes).isEqualTo(1024L)
    }

    @Test
    fun testBlockedTrackerThrowsHttpDataSourceException() {
        val uri = Uri.parse("https://telemetry.tracker.com/beacon")
        val dataSpec = DataSpec(uri)

        assertThatThrownBy { dataSource.open(dataSpec) }
            .isInstanceOf(HttpDataSource.HttpDataSourceException::class.java)
            .hasMessageContaining("Audio stream blocked by policy")

        assertThat(upstream.openedDataSpec).isNull()
    }
}
