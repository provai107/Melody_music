package com.braveplayer.filter.model

import java.net.URI

/**
 * Encapsulates an outgoing network request to be evaluated by the network filter engine.
 */
data class FilterRequest(
    val url: String,
    val requestType: RequestType,
    val sourceUrl: String? = null,
    val headers: Map<String, String> = emptyMap()
) {
    val host: String by lazy {
        try {
            val uri = URI.create(url)
            val parsedHost = uri.host?.lowercase()
            if (!parsedHost.isNullOrEmpty()) {
                parsedHost
            } else {
                extractHostFallback(url)
            }
        } catch (_: Exception) {
            extractHostFallback(url)
        }
    }

    val path: String by lazy {
        try {
            val uri = URI.create(url)
            uri.rawPath ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractHostFallback(rawUrl: String): String {
        val withoutScheme = rawUrl.substringAfter("://", rawUrl)
        val hostPart = withoutScheme.substringBefore('/').substringBefore(':').substringBefore('?')
        return hostPart.lowercase()
    }
}
