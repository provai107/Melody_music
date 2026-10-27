package com.braveplayer.filter.media

import com.braveplayer.filter.manager.FilterListManager
import com.braveplayer.filter.policy.AudioCdnPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class PlaybackAndNetworkErrorTest {

    // Upstream Mock DataSource simulating an HTTP media server / CDN
    class MockMediaDataSource(
        private val dataToServe: ByteArray = ByteArray(4096) { 0x42 },
        var failureCountBeforeSuccess: Int = 0,
        var alwaysFailWithIoException: Boolean = false
    ) : MediaDataSource {

        var openedUri: String? = null
        var openedDataSpec: DataSpec? = null
        var openAttemptCount = 0
        var isClosed = false

        override fun open(dataSpec: DataSpec): Long {
            openAttemptCount++
            openedUri = dataSpec.uri
            openedDataSpec = dataSpec

            if (alwaysFailWithIoException) {
                throw IOException("Network timeout / HTTP 503 Service Unavailable")
            }

            if (openAttemptCount <= failureCountBeforeSuccess) {
                throw IOException("Transient network drop (attempt $openAttemptCount)")
            }

            return dataToServe.size.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val bytesToRead = minOf(length, dataToServe.size)
            System.arraycopy(dataToServe, 0, buffer, offset, bytesToRead)
            return bytesToRead
        }

        override fun getUri(): String? = openedUri

        override fun close() {
            isClosed = true
        }
    }

    private val filterRules = listOf(
        "||bad-tracker.com^",
        "||adserver.com^",
        "/videoplayback*" // Broad rule that would break YouTube CDN audio if not protected!
    )

    private val audioCdnPolicy = AudioCdnPolicy()
    private val filterManager = FilterListManager(
        initialRules = filterRules,
        audioCdnPolicy = audioCdnPolicy
    )

    @Test
    @DisplayName("Legitimate audio playback streams smoothly from protected audio CDN")
    fun testLegitimateAudioPlayback() {
        val mockUpstream = MockMediaDataSource()
        val dataSource = FilteringHttpDataSource(mockUpstream, filterManager)

        val audioUri = "https://rr2---sn-4g5ednks.googlevideo.com/videoplayback?expire=123456&id=audio1"
        val dataSpec = DataSpec(uri = audioUri)

        val length = dataSource.open(dataSpec)
        assertThat(length).isEqualTo(4096L)

        val buffer = ByteArray(1024)
        val readBytes = dataSource.read(buffer, 0, buffer.size)
        assertThat(readBytes).isEqualTo(1024)
        assertThat(dataSource.totalBytesRead.get()).isEqualTo(1024L)
        assertThat(dataSource.seekCount.get()).isEqualTo(0)
        assertThat(dataSource.retryCount.get()).isEqualTo(0)

        dataSource.close()
        assertThat(mockUpstream.isClosed).isTrue()
    }

    @Test
    @DisplayName("Seeking (Range requests) accurately passes byte offsets without filter interruption")
    fun testPlaybackSeeking() {
        val mockUpstream = MockMediaDataSource()
        val dataSource = FilteringHttpDataSource(mockUpstream, filterManager)

        val audioUri = "https://audio-ak-spotify-com.akamaized.net/stream/track.m4a"
        val seekOffset = 2097152L // 2MB seek
        val dataSpec = DataSpec(uri = audioUri, position = seekOffset)

        dataSource.open(dataSpec)

        assertThat(dataSource.seekCount.get()).isEqualTo(1)
        assertThat(mockUpstream.openedDataSpec?.position).isEqualTo(seekOffset)

        dataSource.close()
    }

    @Test
    @DisplayName("Provider-required advertisements are preserved to honor terms and stream continuity")
    fun testProviderRequiredAdvertisementsPreserved() {
        val mockUpstream = MockMediaDataSource()
        val dataSource = FilteringHttpDataSource(mockUpstream, filterManager)

        // Stream URL containing provider ad markers (dynamic server-inserted audio ad)
        val providerAdStreamUri = "https://googlevideo.com/videoplayback?id=stream1&ctier=L&adformat=1_2"
        val dataSpec = DataSpec(uri = providerAdStreamUri)

        // Must succeed without throwing AdBlockedMediaException
        val length = dataSource.open(dataSpec)
        assertThat(length).isEqualTo(4096L)
    }

    @Test
    @DisplayName("Transient network errors trigger configured retry policy and recover successfully")
    fun testTransientNetworkErrorRetries() {
        // Upstream fails twice with IOException, then succeeds on 3rd attempt
        val mockUpstream = MockMediaDataSource(failureCountBeforeSuccess = 2)
        val retryPolicy = RetryPolicy(maxRetries = 3, initialBackoffMs = 10L)
        val dataSource = FilteringHttpDataSource(mockUpstream, filterManager, retryPolicy)

        val audioUri = "https://cf-media.sndcdn.com/audio/song.mp3"
        val dataSpec = DataSpec(uri = audioUri)

        val length = dataSource.open(dataSpec)
        assertThat(length).isEqualTo(4096L)
        assertThat(mockUpstream.openAttemptCount).isEqualTo(3)
        assertThat(dataSource.retryCount.get()).isEqualTo(2)
    }

    @Test
    @DisplayName("Permanent network failure exhausts retries and propagates IOException")
    fun testPermanentNetworkFailure() {
        val mockUpstream = MockMediaDataSource(alwaysFailWithIoException = true)
        val retryPolicy = RetryPolicy(maxRetries = 2, initialBackoffMs = 10L)
        val dataSource = FilteringHttpDataSource(mockUpstream, filterManager, retryPolicy)

        val audioUri = "https://cf-media.sndcdn.com/audio/song.mp3"
        val dataSpec = DataSpec(uri = audioUri)

        assertThatThrownBy { dataSource.open(dataSpec) }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("Network timeout / HTTP 503")

        assertThat(dataSource.retryCount.get()).isEqualTo(2)
        assertThat(mockUpstream.openAttemptCount).isEqualTo(3) // initial + 2 retries
    }

    @Test
    @DisplayName("Blocked tracking endpoints fail fast immediately with AdBlockedMediaException and 0 retries")
    fun testBlockedRequestsDoNotRetry() {
        val mockUpstream = MockMediaDataSource()
        val dataSource = FilteringHttpDataSource(mockUpstream, filterManager)

        // Non-audio destination matching blocklist
        val blockedUri = "https://bad-tracker.com/audio_beacon"
        val dataSpec = DataSpec(uri = blockedUri)

        assertThatThrownBy { dataSource.open(dataSpec) }
            .isInstanceOf(AdBlockedMediaException::class.java)
            .hasMessageContaining("bad-tracker.com")

        // Blocked requests must NEVER make upstream network attempts or waste retry cycles
        assertThat(dataSource.retryCount.get()).isEqualTo(0)
        assertThat(mockUpstream.openAttemptCount).isEqualTo(0)
    }
}
