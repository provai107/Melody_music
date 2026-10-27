package com.braveplayer.music.media

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.braveplayer.filter.manager.FilterListManager
import com.braveplayer.filter.model.FilterRequest
import com.braveplayer.filter.model.RequestType
import java.io.IOException

/**
 * Real AndroidX Media3 HttpDataSource implementation that enforces Brave-style network filtering,
 * Audio CDN protection, and provider-required advertisement preservation.
 *
 * It wraps an upstream [HttpDataSource] (e.g. DefaultHttpDataSource or OkHttpDataSource)
 * and evaluates all audio stream and chunk requests through [FilterListManager].
 */
class BraveMedia3HttpDataSource(
    private val upstreamDataSource: HttpDataSource,
    private val filterListManager: FilterListManager
) : HttpDataSource {

    /**
     * Factory producing instances of [BraveMedia3HttpDataSource] for AndroidX Media3 / ExoPlayer.
     */
    class Factory(
        private val upstreamFactory: HttpDataSource.Factory = DefaultHttpDataSource.Factory(),
        private val filterListManager: FilterListManager
    ) : HttpDataSource.Factory {

        override fun createDataSource(): HttpDataSource {
            return BraveMedia3HttpDataSource(
                upstreamDataSource = upstreamFactory.createDataSource(),
                filterListManager = filterListManager
            )
        }

        override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): HttpDataSource.Factory {
            upstreamFactory.setDefaultRequestProperties(defaultRequestProperties)
            return this
        }
    }

    @Throws(HttpDataSource.HttpDataSourceException::class)
    override fun open(dataSpec: DataSpec): Long {
        val uriString = dataSpec.uri.toString()

        // Extract custom HTTP headers from dataSpec
        val headers = dataSpec.httpRequestHeaders.toMutableMap()
        if (dataSpec.position > 0) {
            headers["Range"] = "bytes=${dataSpec.position}-"
        }

        val request = FilterRequest(
            url = uriString,
            requestType = RequestType.AUDIO_PLAYBACK,
            headers = headers
        )

        val decision = filterListManager.evaluate(request)
        if (decision.isBlocked) {
            throw HttpDataSource.HttpDataSourceException(
                "Audio stream blocked by policy: $uriString (rule: ${decision.matchedRule}, reason: ${decision.reason})",
                dataSpec,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                HttpDataSource.HttpDataSourceException.TYPE_CLOSE
            )
        }

        try {
            return upstreamDataSource.open(dataSpec)
        } catch (e: HttpDataSource.HttpDataSourceException) {
            throw e
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException(
                e,
                dataSpec,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }
    }

    @Throws(HttpDataSource.HttpDataSourceException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        try {
            return upstreamDataSource.read(buffer, offset, length)
        } catch (e: HttpDataSource.HttpDataSourceException) {
            throw e
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException(
                e,
                DataSpec(Uri.EMPTY),
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                HttpDataSource.HttpDataSourceException.TYPE_READ
            )
        }
    }

    override fun getUri(): Uri? = upstreamDataSource.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstreamDataSource.responseHeaders

    override fun getResponseCode(): Int = upstreamDataSource.responseCode

    override fun setRequestProperty(name: String, value: String) {
        upstreamDataSource.setRequestProperty(name, value)
    }

    override fun clearRequestProperty(name: String) {
        upstreamDataSource.clearRequestProperty(name)
    }

    override fun clearAllRequestProperties() {
        upstreamDataSource.clearAllRequestProperties()
    }

    override fun addTransferListener(transferListener: TransferListener) {
        upstreamDataSource.addTransferListener(transferListener)
    }

    @Throws(HttpDataSource.HttpDataSourceException::class)
    override fun close() {
        upstreamDataSource.close()
    }
}
