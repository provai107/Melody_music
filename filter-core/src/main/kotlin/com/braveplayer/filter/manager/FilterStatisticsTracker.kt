package com.braveplayer.filter.manager

import com.braveplayer.filter.model.FilterRequest
import com.braveplayer.filter.model.FilterResult
import com.braveplayer.filter.model.FilterStatistics
import com.braveplayer.filter.model.RequestType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe, non-blocking telemetry tracker for network filtering decisions.
 */
class FilterStatisticsTracker {

    private val totalRequests = AtomicLong(0)
    private val blockedRequests = AtomicLong(0)
    private val allowedRequests = AtomicLong(0)
    private val allowlistedRequests = AtomicLong(0)
    private val audioStreamBypasses = AtomicLong(0)
    private val lastBlockedTimestamp = AtomicLong(0)

    private val domainBlockedCounts = ConcurrentHashMap<String, AtomicLong>()
    private val ruleHitCounts = ConcurrentHashMap<String, AtomicLong>()

    fun record(request: FilterRequest, result: FilterResult) {
        totalRequests.incrementAndGet()

        if (result.isBlocked) {
            blockedRequests.incrementAndGet()
            lastBlockedTimestamp.set(System.currentTimeMillis())

            val host = request.host
            if (host.isNotEmpty()) {
                domainBlockedCounts.computeIfAbsent(host) { AtomicLong(0) }.incrementAndGet()
            }
        } else if (result.matchedRule == "AUDIO_CDN_BYPASS") {
            audioStreamBypasses.incrementAndGet()
        } else if (result.isAllowlisted) {
            allowlistedRequests.incrementAndGet()
        } else {
            allowedRequests.incrementAndGet()
        }

        val rule = result.matchedRule
        if (rule != null) {
            ruleHitCounts.computeIfAbsent(rule) { AtomicLong(0) }.incrementAndGet()
        }
    }

    fun getSnapshot(): FilterStatistics {
        val topDomains = domainBlockedCounts.entries
            .associate { it.key to it.value.get() }
            .toList()
            .sortedByDescending { it.second }
            .take(50)
            .toMap()

        val rules = ruleHitCounts.entries
            .associate { it.key to it.value.get() }

        val lastBlocked = lastBlockedTimestamp.get()

        return FilterStatistics(
            totalRequestsEvaluated = totalRequests.get(),
            blockedRequests = blockedRequests.get(),
            allowedRequests = allowedRequests.get(),
            allowlistedRequests = allowlistedRequests.get(),
            audioStreamBypasses = audioStreamBypasses.get(),
            lastBlockedTimestampMs = if (lastBlocked > 0) lastBlocked else null,
            topBlockedDomains = topDomains,
            ruleHitCounts = rules
        )
    }

    fun reset() {
        totalRequests.set(0)
        blockedRequests.set(0)
        allowedRequests.set(0)
        allowlistedRequests.set(0)
        audioStreamBypasses.set(0)
        lastBlockedTimestamp.set(0)
        domainBlockedCounts.clear()
        ruleHitCounts.clear()
    }
}
