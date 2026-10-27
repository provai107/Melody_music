package com.braveplayer.filter.media

import java.io.IOException

/**
 * AndroidX Media3 DataSource abstraction models.
 * Conforms to androidx.media3.datasource.DataSource and DataSpec contracts.
 */
data class DataSpec(
    val uri: String,
    val position: Long = 0L,
    val length: Long = -1L,
    val httpRequestHeaders: Map<String, String> = emptyMap()
)

interface MediaDataSource {
    /**
     * Opens the data source to read the specified data.
     * @return The number of bytes that can be read from the opened source, or -1 if unknown.
     */
    @Throws(IOException::class)
    fun open(dataSpec: DataSpec): Long

    /**
     * Reads up to [length] bytes of data and stores them into [buffer], starting at [offset].
     * @return The number of bytes read, or -1 if the end of the opened range is reached.
     */
    @Throws(IOException::class)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    fun getUri(): String?

    @Throws(IOException::class)
    fun close()
}

/**
 * Exception thrown when a media stream request is blocked by active network filtering.
 */
class AdBlockedMediaException(
    val uri: String,
    val matchedRule: String?,
    val reason: String
) : IOException("Audio playback request blocked: $uri (rule: $matchedRule, reason: $reason)")

/**
 * Configurable retry policy for transient audio streaming network failures.
 */
data class RetryPolicy(
    val maxRetries: Int = 3,
    val initialBackoffMs: Long = 100L,
    val retryableErrorCodes: Set<Int> = setOf(502, 503, 504, 429)
)
