package com.braveplayer.music.util

import android.content.Context
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class NetworkUtilsTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testIsOnlineReturnsBooleanWithoutCrashing() {
        val online = NetworkUtils.isOnline(context)
        assertThat(online).isNotNull()
    }

    @Test
    fun testClearAudioStreamingCacheDeletesAudioFiles() {
        val cacheDir = context.cacheDir
        val audioFile1 = File(cacheDir, "sample_stream.mp3")
        val audioFile2 = File(cacheDir, "cached_chunk.mp4")
        val textFile = File(cacheDir, "metadata.json")

        audioFile1.writeText("dummy audio data")
        audioFile2.writeText("dummy video/audio data")
        textFile.writeText("{\"key\": \"value\"}")

        assertThat(audioFile1.exists()).isTrue()
        assertThat(audioFile2.exists()).isTrue()
        assertThat(textFile.exists()).isTrue()

        NetworkUtils.clearAudioStreamingCache(context)

        assertThat(audioFile1.exists()).isFalse()
        assertThat(audioFile2.exists()).isFalse()
        // Non-audio configuration should not be destroyed
        assertThat(textFile.exists()).isTrue()
    }
}
