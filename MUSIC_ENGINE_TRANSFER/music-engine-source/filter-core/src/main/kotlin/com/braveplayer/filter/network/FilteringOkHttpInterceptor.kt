package com.braveplayer.filter.network

import com.braveplayer.filter.manager.FilterListManager
import com.braveplayer.filter.model.FilterRequest
import com.braveplayer.filter.model.RequestType
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/**
 * Custom OkHttp Interceptor for filtering API metadata and general network queries.
 */
class FilteringOkHttpInterceptor(
    private val filterListManager: FilterListManager,
    private val defaultRequestType: RequestType = RequestType.METADATA_API
) : Interceptor {

    class BlockedRequestException(
        val url: String,
        val matchedRule: String?,
        val reason: String
    ) : IOException("Request blocked by network filter: $url (rule: $matchedRule, reason: $reason)")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val urlString = request.url.toString()

        // Tag or header can override RequestType if specified
        val requestTypeHeader = request.header("X-Player-Request-Type")
        val requestType = if (requestTypeHeader != null) {
            try {
                RequestType.valueOf(requestTypeHeader)
            } catch (_: Exception) {
                defaultRequestType
            }
        } else {
            defaultRequestType
        }

        val headersMap = request.headers.names().associateWith { request.header(it) ?: "" }
        val filterRequest = FilterRequest(
            url = urlString,
            requestType = requestType,
            headers = headersMap
        )

        val decision = filterListManager.evaluate(filterRequest)
        if (decision.isBlocked) {
            // Return an HTTP 403 Forbidden synthetic response without hitting the wire
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(403)
                .message("Blocked by Network Filter: ${decision.reason}")
                .addHeader("X-Filter-Blocked", "true")
                .addHeader("X-Filter-Rule", decision.matchedRule ?: "unknown")
                .body("Blocked by Network Filter".toResponseBody("text/plain".toMediaType()))
                .build()
        }

        return chain.proceed(request)
    }
}
