package com.braveplayer.filter.media

import com.braveplayer.filter.manager.FilterListManager
import com.braveplayer.filter.model.FilterRequest
import com.braveplayer.filter.model.RequestType
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * AndroidX Media3 DataSource wrapper that integrates Brave-style network filtering,
 * Audio CDN protection, seeking preservation, and transient network error retries.
 */
class FilteringHttpDataSource(
    private val upstreamDataSource: MediaDataSource,
    private val filterListManager: FilterListManager,
    private val retryPolicy: RetryPolicy = RetryPolicy()
) : MediaDataSource {

    val seekCount = AtomicInteger(0)
    val retryCount = AtomicInteger(0)
    val totalBytesRead = AtomicLong(0)

    private var currentDataSpec: DataSpec? = null

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        this.currentDataSpec = dataSpec

        // Check if this request represents a seek operation (position > 0)
        if (dataSpec.position > 0) {
            seekCount.incrementAndGet()
        }

        // 1. Evaluate audio stream URL against filter manager and AudioCdnPolicy
        val headers = dataSpec.httpRequestHeaders.toMutableMap()
        if (dataSpec.position > 0) {
            headers["Range"] = "bytes=${dataSpec.position}-"
        }

        val filterReq = FilterRequest(
            url = dataSpec.uri,
            requestType = RequestType.AUDIO_PLAYBACK,
            headers = headers
        )

        val decision = filterListManager.evaluate(filterReq)
        if (decision.isBlocked) {
            // Blocked by policy - fail fast immediately, never attempt network connection or retries
            throw AdBlockedMediaException(
                uri = dataSpec.uri,
                matchedRule = decision.matchedRule,
                reason = decision.reason
            )
        }

        // 2. Open upstream with retry handling for transient network failures
        var attempts = 0
        var lastException: IOException? = null

        while (attempts <= retryPolicy.maxRetries) {
            try {
                return upstreamDataSource.open(dataSpec)
            } catch (e: IOException) {
                // If the error is an AdBlockedMediaException or non-retryable, rethrow immediately
                if (e is AdBlockedMediaException) {
                    throw e
                }

                lastException = e
                attempts++
                if (attempts <= retryPolicy.maxRetries) {
                    retryCount.incrementAndGet()
                    try {
                        Thread.sleep(retryPolicy.initialBackoffMs * attempts)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
            }
        }

        throw lastException ?: IOException("Failed to open media stream after $attempts attempts: ${dataSpec.uri}")
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = upstreamDataSource.read(buffer, offset, length)
        if (bytesRead > 0) {
            totalBytesRead.addAndGet(bytesRead.toLong())
        }
        return bytesRead
    }

    override fun getUri(): String? = upstreamDataSource.getUri()

    @Throws(IOException::class)
    override fun close() {
        upstreamDataSource.close()
    }
}
