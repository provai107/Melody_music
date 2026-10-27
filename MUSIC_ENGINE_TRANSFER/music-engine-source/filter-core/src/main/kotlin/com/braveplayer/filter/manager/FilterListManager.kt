package com.braveplayer.filter.manager

import com.braveplayer.filter.engine.BraveRulesFilterEngine
import com.braveplayer.filter.engine.NetworkFilterEngine
import com.braveplayer.filter.model.FilterRequest
import com.braveplayer.filter.model.FilterResult
import com.braveplayer.filter.model.FilterStatistics
import com.braveplayer.filter.policy.AudioCdnPolicy
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages the lifecycle of network filter lists, providing safe atomic updates,
 * checksum verification, automatic rollback on corrupted updates, and statistics telemetry.
 */
class FilterListManager(
    initialRules: List<String> = emptyList(),
    private val trustedDomains: Set<String> = emptySet(),
    private val audioCdnPolicy: AudioCdnPolicy = AudioCdnPolicy(),
    val statisticsTracker: FilterStatisticsTracker = FilterStatisticsTracker()
) {
    private val activeEngineRef = AtomicReference<NetworkFilterEngine>(
        BraveRulesFilterEngine(initialRules, trustedDomains)
    )

    private val previousEngineRef = AtomicReference<NetworkFilterEngine?>(null)

    val currentEngine: NetworkFilterEngine
        get() = activeEngineRef.get()

    sealed class UpdateResult {
        data class Success(val ruleCount: Int, val sha256: String) : UpdateResult()
        data class IntegrityError(val expectedSha256: String, val actualSha256: String) : UpdateResult()
        data class ParseError(val message: String, val cause: Throwable?) : UpdateResult()
    }

    /**
     * Evaluates a network request against the active filter rules and AudioCdnPolicy.
     * Audio CDN protection takes precedence for audio requests to guarantee uninterrupted playback.
     */
    fun evaluate(request: FilterRequest): FilterResult {
        // Step 1: Check Audio CDN Policy first if this is an audio stream request
        val cdnDecision = audioCdnPolicy.evaluateAudioRequest(request)
        if (cdnDecision != null) {
            statisticsTracker.record(request, cdnDecision)
            return cdnDecision
        }

        // Step 2: Evaluate using active filter rule engine
        val engine = activeEngineRef.get()
        val result = engine.evaluate(request)

        // Step 3: Record metrics
        statisticsTracker.record(request, result)
        return result
    }

    /**
     * Atomically updates the filter list.
     * Validates SHA-256 checksum (if provided) and compiles the new rule set before swapping.
     * If compilation fails or checksum mismatches, the active engine remains completely untouched.
     */
    fun updateRules(rawRulesText: String, expectedSha256: String? = null): UpdateResult {
        val actualSha256 = calculateSha256(rawRulesText)
        if (expectedSha256 != null && !actualSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
            return UpdateResult.IntegrityError(expectedSha256, actualSha256)
        }

        val rulesList = rawRulesText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("!") && !it.startsWith("#") }

        return try {
            val newEngine = BraveRulesFilterEngine(rulesList, trustedDomains)
            val oldEngine = activeEngineRef.getAndSet(newEngine)
            previousEngineRef.set(oldEngine)
            UpdateResult.Success(rulesList.size, actualSha256)
        } catch (e: Throwable) {
            UpdateResult.ParseError(e.message ?: "Failed to compile rules", e)
        }
    }

    /**
     * Rolls back to the previously active engine if an update was problematic.
     */
    fun rollbackToPrevious(): Boolean {
        val prev = previousEngineRef.getAndSet(null) ?: return false
        activeEngineRef.set(prev)
        return true
    }

    fun getStatisticsSnapshot(): FilterStatistics {
        return statisticsTracker.getSnapshot()
    }

    fun resetStatistics() {
        statisticsTracker.reset()
    }

    private fun calculateSha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
